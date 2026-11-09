package com.modporter.core.transforms.structural

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith

/**
 * Source-admission harness. It uses renamed fixture symbols and a strict fake artifact
 * verifier so source admission can be exercised without launching Gradle or Minecraft.
 *
 * The production verifier remains covered by its exact artifact hashes and classfile/source
 * checks; this harness deliberately does not replace those artifact checks with fixtures.
 */
class LegacyLootingPolicyMigrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `admitted graph captures provenance and plans all hooks atomically`() {
        val project = fixtureProject()
        val inputs = inputs(project)

        val plan = LegacyLootingPolicyMigration.preflight(inputs, FixtureArtifacts())

        plan.evidence.policy.ownerClass shouldBe "org.example.salvage.DropEvents"
        plan.evidence.policy.methodName shouldBe "applyPulverizeBonus"
        plan.evidence.policy.keyExpression shouldBe "org.example.salvage.HazardKeys.PULVERIZE"
        plan.evidence.policy.level shouldBe 3
        plan.evidence.policy.terminalHurtCalls shouldBe 1
        plan.evidence.mixin.className shouldBe "org.example.salvage.mixin.BonusCountMixin"
        plan.evidence.mixin.configEntry shouldBe "BonusCountMixin"

        val writes = plan.mutations.associateBy { it.relativePath }
        writes.keys.any { it.endsWith("/ModporterLegacyLootingPolicy.java") } shouldBe true
        writes.keys.any { it.endsWith("/ModporterLegacyLootingMobMixin.java") } shouldBe true
        writes.keys.any {
            it.endsWith("/ModporterLegacyLootingEnchantmentHelperMixin.java")
        } shouldBe true
        val policy = writes.values.single {
            it.relativePath.endsWith("/ModporterLegacyLootingPolicy.java")
        }.after!!.decodeToString()
        policy shouldContain "source.is(org.example.salvage.HazardKeys.PULVERIZE)"
        policy shouldContain "instanceof LootItemEntityPropertyCondition requirement"

        val helper = writes.values.single {
            it.relativePath.endsWith("/ModporterLegacyLootingEnchantmentHelperMixin.java")
        }.after!!.decodeToString()
        helper shouldContain "@WrapOperation"
        helper shouldContain "runIterationOnEquipment"
        helper shouldContain "ordinal = 1"
        helper shouldContain "Operation<Void> original"
        helper shouldContain "EnchantmentInSlotVisitor decorated"
        helper shouldContain "original.call(equipmentOwner, decorated)"
        helper shouldContain "verifyNativeLootingEquipmentShape(level)"
        helper shouldNotContain "lambda\$processEquipmentDropChance"

        val mob = writes.values.single {
            it.relativePath.endsWith("/ModporterLegacyLootingMobMixin.java")
        }.after!!.decodeToString()
        mob shouldContain "float roll = original.call(random);"
        mob shouldContain "adjustEquipmentRoll(roll, damageSource)"

        val countMixin = writes.getValue(
            "src/main/java/org/example/salvage/mixin/BonusCountMixin.java"
        ).after!!.decodeToString()
        countMixin shouldContain "@Mixin(EnchantedCountIncreaseFunction.class)"
        countMixin shouldContain "if (!this.enchantment.is(Enchantments.LOOTING))"
        countMixin shouldContain "stack.grow(Math.round(increase));"
        countMixin shouldContain "(this.limit > 0)"
        countMixin shouldNotContain "abstract boolean hasLimit()"

        // Preflight itself is read-only.
        project.resolve(".modporter/evidence/legacy-looting-migration.json").exists() shouldBe false
    }

    @Test
    fun `post-common bootstrap registration remains declarative key ownership`() {
        val project = fixtureProject()
        val keys = project.resolve(
            "src/main/java/org/example/salvage/HazardKeys.java"
        ).readText()

        keys shouldContain "import net.minecraft.data.worldgen.BootstrapContext;"
        keys shouldContain "new DamageTypeBuilder(PULVERIZE)" +
            ".scaling(DamageScaling.ALWAYS).register(context);"

        val plan = LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())

        plan.evidence.policy.keyExpression shouldBe
            "org.example.salvage.HazardKeys.PULVERIZE"
    }

    @Test
    fun `pre-common BootstapContext registration is admitted read-only with exact ABI proof`() {
        val project = fixtureProject()
        useLegacyBootstapContext(project)
        val inputs = inputs(project)
        val before = bytesByRelativePath(project)

        val plan = LegacyLootingPolicyMigration.preflight(
            inputs,
            FixtureArtifacts(legacyBootstrapContextAbi = true)
        )

        plan.evidence.policy.keyExpression shouldBe
            "org.example.salvage.HazardKeys.PULVERIZE"
        bytesByRelativePath(project) shouldBe before
    }

    @Test
    fun `pre-common BootstapContext registration without artifact ABI proof fails closed`() {
        val project = fixtureProject()
        useLegacyBootstapContext(project)

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "unresolved alias or unsupported use"
    }

    @Test
    fun `damage registration through an unowned builder fails closed`() {
        val project = fixtureProject()
        Files.delete(
            project.resolve(
                "src/main/java/org/example/salvage/DamageTypeBuilder.java"
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "unresolved alias or unsupported use"
    }

    @Test
    fun `extra count mixin behavior fails exact body admission`() {
        val project = fixtureProject()
        val file = project.resolve(
            "src/main/java/org/example/salvage/mixin/BonusCountMixin.java"
        )
        file.writeText(
            file.readText().replace(
                "stack.grow(Math.round(increase));",
                "stack.grow(Math.round(increase));\n\t\t\tstack.setCount(stack.getCount());"
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "unsupported extra behavior"
    }

    @Test
    fun `an additional project custom-death consumer fails before a plan exists`() {
        val project = fixtureProject()
        val extra = project.resolve(
            "src/main/java/org/example/salvage/ExtraDeathConsumer.java"
        )
        extra.writeText(
            """
            package org.example.salvage;
            public class ExtraDeathConsumer {
                void dropCustomDeathLoot(Object source, int level, boolean recent) {}
            }
            """.trimIndent()
        )
        val testInputs = inputs(project)
        val before = bytesByRelativePath(project)

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(testInputs, FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "custom-death consumers"
        bytesByRelativePath(project) shouldBe before
    }

    @Test
    fun `a second policy handler fails admission`() {
        val project = fixtureProject()
        project.resolve("src/main/java/org/example/salvage/SecondPolicy.java").writeText(
            """
            package org.example.salvage;
            import net.minecraft.world.damagesource.DamageSource;
            import net.minecraftforge.event.entity.living.LootingLevelEvent;
            import net.minecraftforge.eventbus.api.SubscribeEvent;
            public final class SecondPolicy {
                @SubscribeEvent
                public static void second(LootingLevelEvent event) {
                    DamageSource source = event.getDamageSource();
                    if (source == null || !source.is(HazardKeys.PULVERIZE))
                        return;
                    event.setLootingLevel(3);
                }
            }
            """.trimIndent()
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }
        failure.message.orEmpty() shouldContain "exactly one admitted"
    }

    @Test
    fun `a second mixin configuration closure fails admission`() {
        val project = fixtureProject()
        val second = project.resolve("src/main/resources/duplicate.mixins.json")
        second.writeText(
            """
            {
              "required": true,
              "package": "org.example.salvage.mixin",
              "mixins": ["BonusCountMixin"]
            }
            """.trimIndent()
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }
        failure.message.orEmpty() shouldContain "Expected one mixin config entry"
    }

    @Test
    fun `an entity-bearing policy damage construction fails admission`() {
        val project = fixtureProject()
        val file = project.resolve(
            "src/main/java/org/example/salvage/HazardDamageSources.java"
        )
        file.writeText(
            file.readText()
                .replace(
                    "public static DamageSource pulverize(Level level) {\n" +
                        "\t\treturn source(HazardKeys.PULVERIZE, level);\n\t}",
                    "public static DamageSource pulverize(Level level) {\n" +
                        "\t\treturn source(HazardKeys.PULVERIZE, level, null);\n\t}"
                )
                .replace(
                    "\n\tprivate static DamageSource source(ResourceKey<DamageType> key, LevelReader level) {",
                    "\n\tprivate static DamageSource source(" +
                        "ResourceKey<DamageType> key, LevelReader level, Entity attacker) {\n" +
                        "\t\tRegistry<DamageType> registry =\n" +
                        "\t\t\tlevel.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);\n" +
                        "\t\treturn new DamageSource(registry.getHolderOrThrow(key), attacker);\n" +
                        "\t}\n\n\tprivate static DamageSource source(" +
                        "ResourceKey<DamageType> key, LevelReader level) {"
                )
                .replace(
                    "import net.minecraft.world.level.Level;",
                    "import net.minecraft.world.entity.Entity;\n" +
                        "import net.minecraft.world.level.Level;"
                )
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }
        failure.message.orEmpty() shouldContain "entity-bearing or unresolved"
    }

    @Test
    fun `a policy key alias outside the admitted graph fails closed`() {
        val project = fixtureProject()
        project.resolve("src/main/java/org/example/salvage/HazardAlias.java").writeText(
            """
            package org.example.salvage;
            public final class HazardAlias {
                static final Object VALUE = HazardKeys.PULVERIZE;
            }
            """.trimIndent()
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "unresolved alias or unsupported use"
    }

    @Test
    fun `a bare policy key alias in its owner fails closed`() {
        val project = fixtureProject()
        val file = project.resolve(
            "src/main/java/org/example/salvage/HazardKeys.java"
        )
        file.writeText(
            file.readText().replace(
                "\tprivate HazardKeys() {}",
                "\tpublic static ResourceKey<DamageType> alias() {\n" +
                    "\t\treturn PULVERIZE;\n" +
                    "\t}\n\n" +
                    "\tprivate HazardKeys() {}"
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "unresolved alias or unsupported use"
    }

    @Test
    fun `an instance scoped static producer call fails receiver closure`() {
        val project = fixtureProject()
        val file = project.resolve(
            "src/main/java/org/example/salvage/MillController.java"
        )
        file.writeText(
            file.readText().replace(
                "target.hurt(HazardDamageSources.pulverize(level), amount);",
                "target.hurt(HazardDamageSources.pulverize(level), amount);\n" +
                    "\t\tHazardDamageSources factory = null;\n" +
                    "\t\tfactory.pulverize(level);"
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "unresolved or noncanonical receiver"
    }

    @Test
    fun `same owner same arity overload ambiguity remains fail closed`() {
        val project = fixtureProject()
        val file = project.resolve(
            "src/main/java/org/example/salvage/HazardDamageSources.java"
        )
        file.writeText(
            file.readText().replace(
                "\n\tprivate static DamageSource source(" +
                    "ResourceKey<DamageType> key, LevelReader level) {",
                "\n\tprivate static DamageSource source(Object key, LevelReader level) {\n" +
                    "\t\treturn null;\n" +
                    "\t}\n\n" +
                    "\tprivate static DamageSource source(" +
                    "ResourceKey<DamageType> key, LevelReader level) {"
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "entity-bearing or unresolved"
    }

    @Test
    fun `producer side effects fail exact body admission`() {
        val project = fixtureProject()
        val file = project.resolve(
            "src/main/java/org/example/salvage/HazardDamageSources.java"
        )
        file.writeText(
            file.readText().replace(
                "public static DamageSource pulverize(Level level) {\n" +
                    "\t\treturn source(HazardKeys.PULVERIZE, level);",
                "public static DamageSource pulverize(Level level) {\n" +
                    "\t\tSystem.out.println(level);\n" +
                    "\t\treturn source(HazardKeys.PULVERIZE, level);"
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "entity-bearing or unresolved construction"
    }

    @Test
    fun `plugin side effects fail exact guard admission`() {
        val project = fixtureProject()
        val file = project.resolve(
            "src/main/java/org/example/salvage/mixin/SalvageMixinPlugin.java"
        )
        file.writeText(
            file.readText().replace(
                "\t\treturn true;",
                "\t\tSystem.out.println(targetClassName);\n\t\treturn true;"
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(inputs(project), FixtureArtifacts())
        }

        failure.message.orEmpty() shouldContain "unsupported statements"
    }

    @Test
    fun `target hook drift fails before source planning`() {
        val project = fixtureProject()
        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(
                inputs(project),
                FixtureArtifacts(helperCalls = 3)
            )
        }
        failure.message.orEmpty() shouldContain "exactly two named equipment iterations"
    }

    @Test
    fun `native looting shape drift fails before source planning`() {
        val project = fixtureProject()
        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.preflight(
                inputs(project),
                FixtureArtifacts(nativeShape = "changed")
            )
        }
        failure.message.orEmpty() shouldContain "exact native Looting equipment shape"
    }

    @Test
    fun `actual target helper source proves ordinal one is the attacker iteration`() {
        val actualMethod = """
            public static float processEquipmentDropChance(ServerLevel level, LivingEntity entity, DamageSource damageSource, float equipmentDropChance) {
                MutableFloat mutablefloat = new MutableFloat(equipmentDropChance);
                RandomSource randomsource = entity.getRandom();
                runIterationOnEquipment(entity, (p_347320_, p_347321_, p_347322_) -> {
                    LootContext lootcontext = Enchantment.damageContext(level, p_347321_, entity, damageSource);
                    p_347320_.value().getEffects(EnchantmentEffectComponents.EQUIPMENT_DROPS).forEach(p_347345_ -> {
                        if (p_347345_.enchanted() == EnchantmentTarget.VICTIM && p_347345_.affected() == EnchantmentTarget.VICTIM && p_347345_.matches(lootcontext)) {
                            mutablefloat.setValue(p_347345_.effect().process(p_347321_, randomsource, mutablefloat.floatValue()));
                        }
                    });
                });
                if (damageSource.getEntity() instanceof LivingEntity livingentity) {
                    runIterationOnEquipment(
                        livingentity,
                        (p_347338_, p_347339_, p_347340_) -> {
                            LootContext lootcontext = Enchantment.damageContext(level, p_347339_, entity, damageSource);
                            p_347338_.value()
                                .getEffects(EnchantmentEffectComponents.EQUIPMENT_DROPS)
                                .forEach(
                                    p_347327_ -> {
                                        if (p_347327_.enchanted() == EnchantmentTarget.ATTACKER
                                            && p_347327_.affected() == EnchantmentTarget.VICTIM
                                            && p_347327_.matches(lootcontext)) {
                                            mutablefloat.setValue(p_347327_.effect().process(p_347339_, randomsource, mutablefloat.floatValue()));
                                        }
                                    }
                                );
                        }
                    );
                }
                return mutablefloat.floatValue();
            }
        """.trimIndent()

        LegacyLootingPolicyMigration.verifyTargetEquipmentDropChanceSource(actualMethod)

        val wrongRole = actualMethod.replace(
            "p_347327_.enchanted() == EnchantmentTarget.ATTACKER",
            "p_347327_.enchanted() == EnchantmentTarget.VICTIM"
        )
        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.verifyTargetEquipmentDropChanceSource(wrongRole)
        }
        failure.message.orEmpty() shouldContain
            "ordinal 1 is no longer the attacker equipment iteration"
    }

    @Test
    fun `generated type collisions receive deterministic suffixes`() {
        val project = fixtureProject()
        project.resolve(
            "src/main/java/org/example/salvage/mixin/ModporterLegacyLootingPolicy.java"
        ).writeText(
            """
            package org.example.salvage.mixin;
            final class ModporterLegacyLootingPolicy {}
            """.trimIndent()
        )

        val plan = LegacyLootingPolicyMigration.preflight(
            inputs(project),
            FixtureArtifacts()
        )

        plan.evidence.generatedTypes.contains(
            "org.example.salvage.mixin.ModporterLegacyLootingPolicy2"
        ) shouldBe true
    }

    @Test
    fun `apply rejects a late consumer without replacing any planned source`() {
        val project = fixtureProject()
        val plan = LegacyLootingPolicyMigration.preflight(
            inputs(project),
            FixtureArtifacts()
        )
        val originalPolicy = project.resolve(
            "src/main/java/org/example/salvage/DropEvents.java"
        ).readBytes()
        project.resolve("src/main/java/org/example/salvage/LateConsumer.java").writeText(
            "package org.example.salvage; final class LateConsumer {}"
        )

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLootingPolicyMigration.apply(plan)
        }

        failure.message.orEmpty() shouldContain "graph changed after preflight"
        project.resolve(
            "src/main/java/org/example/salvage/DropEvents.java"
        ).readBytes().contentEquals(originalPolicy) shouldBe true
        project.resolve(
            "src/main/java/org/example/salvage/mixin/ModporterLegacyLootingMobMixin.java"
        ).exists() shouldBe false
    }

    private fun fixtureProject(): Path {
        val root = temporary.resolve("project-${System.nanoTime()}")
        copyTree(fixtureRoot().resolve("admitted"), root)
        root.resolve(
            "src/main/java/org/example/salvage/DamageTypeBuilder.java"
        ).writeText(
            """
            package org.example.salvage;

            import net.minecraft.data.worldgen.BootstrapContext;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.damagesource.DamageScaling;
            import net.minecraft.world.damagesource.DamageType;

            public class DamageTypeBuilder {
                protected final ResourceKey<DamageType> key;

                public DamageTypeBuilder(ResourceKey<DamageType> key) {
                    this.key = key;
                }

                public DamageTypeBuilder scaling(DamageScaling scaling) {
                    return this;
                }

                public DamageType register(BootstrapContext<DamageType> context) {
                    return null;
                }
            }
            """.trimIndent() + "\n"
        )
        return root
    }

    private fun useLegacyBootstapContext(project: Path) {
        listOf(
            project.resolve("src/main/java/org/example/salvage/HazardKeys.java"),
            project.resolve("src/main/java/org/example/salvage/DamageTypeBuilder.java")
        ).forEach { file ->
            file.writeText(file.readText().replace("BootstrapContext", "BootstapContext"))
        }
    }

    private fun inputs(project: Path): LegacyLootingPolicyMigration.Inputs {
        val artifacts = project.resolve("fixture-artifacts")
        artifacts.createDirectories()
        val legacy = artifacts.resolve("legacy.jar").also { it.writeBytes("legacy".encodeToByteArray()) }
        val target = artifacts.resolve("target.jar").also { it.writeBytes("target".encodeToByteArray()) }
        val sources = artifacts.resolve("sources.jar").also { it.writeBytes("sources".encodeToByteArray()) }
        val extras = artifacts.resolve("extras.jar").also { it.writeBytes("extras".encodeToByteArray()) }
        return LegacyLootingPolicyMigration.Inputs(
            project = project,
            legacyPlatformJar = legacy,
            targetPlatformJar = target,
            targetSourcesJar = sources,
            mixinExtrasJar = extras,
            templates = LegacyLootingPolicyMigration.Templates.production()
        )
    }

    private class FixtureArtifacts(
        private val mobCalls: Int = 1,
        private val helperCalls: Int = 2,
        private val visitorPublic: Boolean = true,
        private val legacyBootstrapContextAbi: Boolean = false,
        private val nativeShape: String =
            "one EQUIPMENT_DROPS ATTACKER->VICTIM AddValue(" +
                "LevelBasedValue.Linear(0.01F,0.01F)) with exact attacking-player requirement"
    ) : LegacyLootingPolicyMigration.ArtifactVerifier {
        override fun verify(
            inputs: LegacyLootingPolicyMigration.Inputs
        ): LegacyLootingPolicyMigration.ArtifactEvidence =
            LegacyLootingPolicyMigration.ArtifactEvidence(
                legacyPlatformSha256 = sha256(requireNotNull(inputs.legacyPlatformJar)),
                targetPlatformSha256 = sha256(requireNotNull(inputs.targetPlatformJar)),
                targetSourcesSha256 = sha256(requireNotNull(inputs.targetSourcesJar)),
                mixinExtrasSha256 = sha256(requireNotNull(inputs.mixinExtrasJar)),
                mobNextFloatCalls = mobCalls,
                helperIterationCalls = helperCalls,
                visitorPublic = visitorPublic,
                legacyBootstrapContextAbi = legacyBootstrapContextAbi,
                nativeLootingShape = nativeShape,
                verificationBasis = "fixture verifier"
            )
    }

    private fun fixtureRoot(): Path =
        Path.of("src/test/resources/fixtures/legacy-looting")
            .toAbsolutePath().normalize()

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) destination.createDirectories()
                else {
                    destination.parent.createDirectories()
                    Files.copy(path, destination)
                }
            }
        }
    }

    private fun bytesByRelativePath(root: Path): Map<String, List<Byte>> =
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).iterator().asSequence().associate {
                root.relativize(it).toString().replace('\\', '/') to it.readBytes().toList()
            }
        }

    companion object {
        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
