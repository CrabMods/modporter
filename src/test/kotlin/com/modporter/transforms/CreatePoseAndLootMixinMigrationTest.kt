package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.CreatePoseAndLootMixinMigration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class CreatePoseAndLootMixinMigrationTest {
    companion object {
        private val FIXTURE_ROOT = Path.of(
            "src/test/resources/fixtures/create-pose-loot-c27/project"
        )
        private val FIXTURE_MANIFEST = Path.of(
            "src/test/resources/fixtures/create-pose-loot-c27/manifest.json"
        )
        private const val ENTITY =
            "src/main/java/com/simibubi/create/foundation/mixin/EntityMixin.java"
        private const val PLAYER =
            "src/main/java/com/simibubi/create/foundation/mixin/PlayerMixin.java"
        private const val ACCESSOR =
            "src/main/java/com/simibubi/create/foundation/mixin/accessor/" +
                "BlockLootSubProviderAccessor.java"
        private const val ALL_BLOCKS =
            "src/main/java/com/simibubi/create/AllBlocks.java"
        private const val CONFIG = "src/main/resources/create.mixins.json"
        private val EXPECTED_INPUT_HASHES = linkedMapOf(
            ENTITY to "F1CF5FE58E081A69EC11654BB6918D566A350102B7EB249D5350FC1161D1D797",
            PLAYER to "13EDF1471E54657B81FCB051C15AEBA7415FB832C2D2947E9197E221A9155A57",
            ACCESSOR to "4FE0AA49C165A689EC7C87D510EEB7BDEEEF5735CE8C1CDAA8B8515FA6535018",
            ALL_BLOCKS to "49BE7536F1D43225B7E84BD000CD44DF4AFF8160BE40234B8975248557A18D40",
            CONFIG to "02CF1AE8E857C8B26DD096E6D1409E362CD656F29EC1315255F3CE23A21699C8"
        )
        private val EXPECTED_INPUT_LENGTHS = linkedMapOf(
            ENTITY to 1298,
            PLAYER to 1298,
            ACCESSOR to 485,
            ALL_BLOCKS to 120538,
            CONFIG to 2562
        )
    }

    @TempDir
    lateinit var temp: Path

    @Test
    fun `actual C27 bytes migrate to the exact official target family`() {
        assertActualFixtureHashes()
        val project = copyFixture(temp.resolve("project"))
        val beforePaths = projectFiles(project)
        val configBefore = project.resolve(CONFIG).readBytes()
        val playerBefore = project.resolve(PLAYER).readText()
        assertTrue(playerBefore.contains("import net.minecraft.world.entity.EquipmentSlot;"))
        assertTrue(playerBefore.contains("// avoid touching all items"))

        val result = CreatePoseAndLootMixinMigration.migrate(
            project,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(
            setOf(ENTITY, PLAYER, ACCESSOR, ALL_BLOCKS),
            result.changes.map {
                slash(project.relativize(it.file.toAbsolutePath().normalize()))
            }.toSet()
        )
        assertEquals(beforePaths, projectFiles(project))
        assertArrayEquals(configBefore, project.resolve(CONFIG).readBytes())
        assertFalse(
            project.resolve(
                "src/main/java/com/simibubi/create/foundation/mixin/PlayerPoseMixin.java"
            ).exists()
        )

        val entity = compact(project.resolve(ENTITY).readText())
        assertTrue(entity.contains("@Mixin(value=Entity.class,priority=1500)"))
        assertTrue(
            entity.contains(
                "@ModifyReturnValue(method=\"fireImmune()Z\",at=@At(\"RETURN\"))" +
                    "publicbooleancreate\$onFireImmune(booleanoriginal){" +
                    "return((Entity)(Object)this).getPersistentData()" +
                    ".getBoolean(NetheriteDivingHandler.FIRE_IMMUNE_KEY)||original;}"
            )
        )
        assertFalse(entity.contains("canEnterPose"))
        assertFalse(entity.contains("CardboardArmorHandler"))
        assertFalse(entity.contains("net.minecraft.world.entity.Pose"))

        val playerSource = project.resolve(PLAYER).readText()
        val player = compact(playerSource)
        assertTrue(player.contains("@Mixin(value=Player.class,priority=1500)"))
        assertTrue(
            player.contains(
                "@ModifyExpressionValue(" +
                    "method=\"canPlayerFitWithinBlocksAndEntitiesWhen\"," +
                    "at=@At(value=\"INVOKE\",target=\"" +
                    "Lnet/minecraft/world/level/Level;noCollision" +
                    "(Lnet/minecraft/world/entity/Entity;" +
                    "Lnet/minecraft/world/phys/AABB;)Z\"))" +
                    "privatebooleancreate\$playerHidingAsBoxIsCrouchingNotSwimming(" +
                    "booleanoriginal,@Local(argsOnly=true)Posepose){" +
                    "returnoriginal||(pose==Pose.CROUCHING&&" +
                    "CardboardArmorHandler.testForStealth(this));}"
            )
        )
        assertTrue(player.contains("method=\"aiStep\""))
        assertFalse(player.contains("require=0"))
        assertFalse(player.contains("noBlockCollision"))
        assertFalse(player.contains("wouldNotSuffocateAtTargetPose"))
        assertTrue(playerSource.contains("import net.minecraft.world.entity.EquipmentSlot;"))
        assertTrue(playerSource.contains("// avoid touching all items"))

        val accessor = compact(project.resolve(ACCESSOR).readText())
        assertTrue(
            accessor.contains(
                "@Invoker(\"hasSilkTouch\")" +
                    "LootItemCondition.Buildercreate\$hasSilkTouch();"
            )
        )
        assertFalse(accessor.contains("@Accessor"))
        assertFalse(accessor.contains("HAS_SILK_TOUCH"))

        val allBlocks = project.resolve(ALL_BLOCKS).readText()
        assertEquals(
            2,
            occurrences(
                compact(allBlocks),
                "((BlockLootSubProviderAccessor)r).create\$hasSilkTouch()"
            )
        )
        assertEquals(0, occurrences(allBlocks, "create\$HAS_SILK_TOUCH"))
        val parsedAllBlocks = JavaParser().parse(allBlocks).result.orElseThrow()
        val silkCalls = parsedAllBlocks.findAll(MethodCallExpr::class.java)
            .filter { it.nameAsString == "create\$hasSilkTouch" }
        assertEquals(2, silkCalls.size)
        silkCalls.forEach { call ->
            val enclosed = call.scope.orElseThrow() as EnclosedExpr
            assertTrue(enclosed.inner is CastExpr)
            val cast = enclosed.inner as CastExpr
            assertEquals("BlockLootSubProviderAccessor", cast.typeAsString)
            assertEquals("r", cast.expression.toString())
        }

        val config = project.resolve(CONFIG).readText()
        assertEquals(1, occurrences(config, "\"PlayerMixin\""))
        assertEquals(
            1,
            occurrences(config, "\"accessor.BlockLootSubProviderAccessor\"")
        )
        assertTrue(Regex(""""defaultRequire"\s*:\s*1""").containsMatchIn(config))
    }

    @Test
    fun `actual C27 migration is byte idempotent`() {
        val project = copyFixture(temp.resolve("idempotent"))
        val first = CreatePoseAndLootMixinMigration.migrate(project, dryRun = false)
        assertTrue(first.gates.isEmpty(), first.gates.joinToString())
        val once = captureProject(project)

        val second = CreatePoseAndLootMixinMigration.migrate(project, dryRun = false)

        assertTrue(second.gates.isEmpty(), second.gates.joinToString())
        assertTrue(second.changes.isEmpty())
        assertProjectBytes(once, project)
    }

    @Test
    fun `dry run proves all four outputs without changing actual C27 bytes`() {
        val project = copyFixture(temp.resolve("dry-run"))
        val before = captureProject(project)

        val result = CreatePoseAndLootMixinMigration.migrate(project, dryRun = true)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(4, result.changes.size)
        assertProjectBytes(before, project)
    }

    @Test
    fun `aggregate publication rolls every partial publication point back`() {
        (0..3).forEach { failedAfterIndex ->
            val project = copyFixture(
                temp.resolve("rollback-after-${failedAfterIndex + 1}")
            )
            val before = captureProject(project)

            val result = CreatePoseAndLootMixinMigration.migrate(
                project,
                dryRun = false
            ) { index, _ ->
                if (index == failedAfterIndex) {
                    error("injected failure after publish ${index + 1}")
                }
            }

            assertTrue(result.changes.isEmpty())
            assertEquals(1, result.gates.size)
            assertTrue(result.gates.single().contains("injected failure"))
            assertProjectBytes(before, project)
        }
    }

    @Test
    fun `semantic near matches fail closed before every project write`() {
        val mutations = listOf(
            Mutation("collision-family") { project ->
                replaceOnce(
                    project.resolve(ENTITY),
                    "Level;noCollision",
                    "Level;noBlockCollision"
                )
            },
            Mutation("fire-hook") { project ->
                replaceOnce(
                    project.resolve(ENTITY),
                    "NetheriteDivingHandler.FIRE_IMMUNE_KEY",
                    "NetheriteDivingHandler.OTHER_KEY"
                )
            },
            Mutation("entity-native-mixin-import") { project ->
                replaceOnce(
                    project.resolve(ENTITY),
                    "import org.spongepowered.asm.mixin.Mixin;",
                    "import example.shadow.Mixin;"
                )
            },
            Mutation("player-owner") { project ->
                replaceOnce(
                    project.resolve(PLAYER),
                    "public abstract class PlayerMixin extends LivingEntity",
                    "public abstract class PlayerMixin extends Player"
                )
            },
            Mutation("player-native-living-entity-import") { project ->
                replaceOnce(
                    project.resolve(PLAYER),
                    "import net.minecraft.world.entity.LivingEntity;",
                    "import example.shadow.LivingEntity;"
                )
            },
            Mutation("accessor-target") { project ->
                replaceOnce(
                    project.resolve(ACCESSOR),
                    "@Accessor(\"HAS_SILK_TOUCH\")",
                    "@Accessor(\"OTHER_FIELD\")"
                )
            },
            Mutation("accessor-native-provider-import") { project ->
                replaceOnce(
                    project.resolve(ACCESSOR),
                    "import net.minecraft.data.loot.BlockLootSubProvider;",
                    "import example.shadow.BlockLootSubProvider;"
                )
            },
            Mutation("silk-polarity") { project ->
                replaceOnce(
                    project.resolve(ALL_BLOCKS),
                    "create\$HAS_SILK_TOUCH().invert()",
                    "create\$HAS_SILK_TOUCH()"
                )
            },
            Mutation("loot-subtree-item") { project ->
                replaceOnce(
                    project.resolve(ALL_BLOCKS),
                    "LootItem.lootTableItem(Items.STRING)",
                    "LootItem.lootTableItem(Items.STICK)"
                )
            },
            Mutation("all-blocks-native-accessor-import") { project ->
                replaceOnce(
                    project.resolve(ALL_BLOCKS),
                    "import com.simibubi.create.foundation.mixin.accessor." +
                        "BlockLootSubProviderAccessor;",
                    "import example.shadow.BlockLootSubProviderAccessor;"
                )
            },
            Mutation("all-blocks-local-accessor-shadow") { project ->
                replaceOnce(
                    project.resolve(ALL_BLOCKS),
                    "public class AllBlocks {",
                    "public class AllBlocks {\n" +
                        "\tprivate interface BlockLootSubProviderAccessor {}"
                )
            },
            Mutation("third-silk-caller") { project ->
                replaceOnce(
                    project.resolve(ALL_BLOCKS),
                    "public class AllBlocks {",
                    "public class AllBlocks {\n" +
                        "\tstatic final Object EXTRA_SILK = " +
                        "BlockLootSubProviderAccessor.create\$HAS_SILK_TOUCH();"
                )
            },
            Mutation("required-gate") { project ->
                replaceOnce(
                    project.resolve(CONFIG),
                    "\"defaultRequire\": 1",
                    "\"defaultRequire\": 0"
                )
            },
            Mutation("mixin-config-package") { project ->
                replaceOnce(
                    project.resolve(CONFIG),
                    "\"package\": \"com.simibubi.create.foundation.mixin\"",
                    "\"package\": \"example.shadow\""
                )
            },
            Mutation("mixin-config-duplicate-required-key") { project ->
                replaceOnce(
                    project.resolve(CONFIG),
                    "\"required\": true,",
                    "\"required\": true,\n  \"required\": true,"
                )
            }
        )

        mutations.forEach { mutation ->
            val project = copyFixture(temp.resolve("reject-${mutation.name}"))
            mutation.apply(project)
            val before = captureProject(project)

            val result = CreatePoseAndLootMixinMigration.migrate(
                project,
                dryRun = false
            )

            assertTrue(
                result.gates.isNotEmpty(),
                "${mutation.name} unexpectedly admitted"
            )
            assertTrue(result.changes.isEmpty())
            assertProjectBytes(before, project)
        }
    }

    @Test
    fun `partial accessor-only migration is rejected without repairing around it`() {
        val project = copyFixture(temp.resolve("partial"))
        val accessor = project.resolve(ACCESSOR)
        var source = accessor.readText()
        source = source.replace(
            "import org.spongepowered.asm.mixin.gen.Accessor;",
            "import org.spongepowered.asm.mixin.gen.Invoker;"
        )
        val legacyMethod =
            "\t@Accessor(\"HAS_SILK_TOUCH\")\n" +
                "\tstatic LootItemCondition.Builder create\$HAS_SILK_TOUCH() {\n" +
                "\t\tthrow new AssertionError();\n" +
                "\t}"
        assertEquals(1, occurrences(source, legacyMethod))
        source = source.replace(
            legacyMethod,
            "\t@Invoker(\"hasSilkTouch\")\n" +
                "\tLootItemCondition.Builder create\$hasSilkTouch();"
        )
        accessor.writeText(source)
        val before = captureProject(project)

        val result = CreatePoseAndLootMixinMigration.migrate(project, dryRun = false)

        assertTrue(result.gates.single().contains("partially migrated"))
        assertTrue(result.changes.isEmpty())
        assertProjectBytes(before, project)
    }

    @Test
    fun `preexisting extra pose class is rejected and never registered`() {
        val project = copyFixture(temp.resolve("extra-route"))
        val extra = project.resolve(
            "src/main/java/com/simibubi/create/foundation/mixin/PlayerPoseMixin.java"
        )
        extra.parent.createDirectories()
        extra.writeText(
            """
            package com.simibubi.create.foundation.mixin;
            public class PlayerPoseMixin {}
            """.trimIndent() + "\n"
        )
        val before = captureProject(project)

        val result = CreatePoseAndLootMixinMigration.migrate(project, dryRun = false)

        assertTrue(result.gates.single().contains("must not create PlayerPoseMixin"))
        assertTrue(result.changes.isEmpty())
        assertProjectBytes(before, project)
        assertFalse(project.resolve(CONFIG).readText().contains("PlayerPoseMixin"))
    }

    @Test
    fun `project-wide source closure rejects external accessor uses and method references`() {
        val externalUses = listOf(
            "BlockLootSubProviderAccessor.create\$HAS_SILK_TOUCH();",
            "((BlockLootSubProviderAccessor) provider).create\$hasSilkTouch();",
            "BlockLootSubProviderAccessor::create\$HAS_SILK_TOUCH;"
        )
        externalUses.forEachIndexed { index, externalUse ->
            val project = copyFixture(temp.resolve("external-caller-$index"))
            val extra = project.resolve(
                "src/main/java/com/simibubi/create/UnexpectedSilkCaller.java"
            )
            extra.writeText(
                """
                package com.simibubi.create;

                import com.simibubi.create.foundation.mixin.accessor.BlockLootSubProviderAccessor;

                class UnexpectedSilkCaller {
                    Object use(Object provider) {
                        return $externalUse
                    }
                }
                """.trimIndent() + "\n"
            )
            val before = captureProject(project)

            val result = CreatePoseAndLootMixinMigration.migrate(project, dryRun = false)

            assertTrue(result.gates.isNotEmpty(), "external use $index was admitted")
            assertTrue(result.changes.isEmpty())
            assertProjectBytes(before, project)
        }
    }

    @Test
    fun `pose and accessor documentation survive their structural move`() {
        val project = copyFixture(temp.resolve("documentation"))
        replaceOnce(
            project.resolve(ENTITY),
            "\t@ModifyExpressionValue(method = \"canEnterPose\"",
            "\t/** Exact cardboard pose contract. */\n" +
                "\t@ModifyExpressionValue(method = \"canEnterPose\""
        )
        replaceOnce(
            project.resolve(ACCESSOR),
            "\t@Accessor(\"HAS_SILK_TOUCH\")",
            "\t/** Exact provider registry binding. */\n" +
                "\t@Accessor(\"HAS_SILK_TOUCH\")"
        )

        val result = CreatePoseAndLootMixinMigration.migrate(project, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(
            "Exact cardboard pose contract.",
            attachedDocumentation(
                project.resolve(PLAYER).readText(),
                "create\$playerHidingAsBoxIsCrouchingNotSwimming"
            )
        )
        assertFalse(
            project.resolve(ENTITY).readText()
                .contains("Exact cardboard pose contract")
        )
        assertEquals(
            "Exact provider registry binding.",
            attachedDocumentation(
                project.resolve(ACCESSOR).readText(),
                "create\$hasSilkTouch"
            )
        )
    }

    private fun assertActualFixtureHashes() {
        assertTrue(FIXTURE_MANIFEST.exists(), "Missing C27 fixture manifest")
        val manifestRoot = Json.parseToJsonElement(
            FIXTURE_MANIFEST.readText()
        ) as JsonObject
        assertEquals(
            "fixture://create-c27-converted-output",
            manifestRoot["sourceRoot"]?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "2026-11-02T11:08:00+09:00",
            manifestRoot["capturedAt"]?.jsonPrimitive?.contentOrNull
        )
        val manifestFiles = manifestRoot["files"] as JsonArray
        val manifestEntries = manifestFiles.associate { raw ->
            val entry = raw as JsonObject
            entry["relativePath"]!!.jsonPrimitive.content to entry
        }
        assertEquals(EXPECTED_INPUT_HASHES.keys, manifestEntries.keys)
        EXPECTED_INPUT_HASHES.forEach { (relative, expected) ->
            val file = FIXTURE_ROOT.resolve(relative)
            assertTrue(file.exists(), "Missing actual C27 fixture: $relative")
            assertEquals(expected, sha256(file.readBytes()), relative)
            assertEquals(
                expected,
                manifestEntries[relative]?.get("sha256")
                    ?.jsonPrimitive?.contentOrNull
            )
            assertEquals(
                EXPECTED_INPUT_LENGTHS[relative],
                manifestEntries[relative]?.get("length")
                    ?.jsonPrimitive?.intOrNull
            )
            assertEquals(EXPECTED_INPUT_LENGTHS[relative]?.toLong(), Files.size(file))
        }
        assertEquals(EXPECTED_INPUT_HASHES.keys, projectFiles(FIXTURE_ROOT))
    }

    private fun copyFixture(destination: Path): Path {
        assertActualFixtureHashes()
        Files.walk(FIXTURE_ROOT).use { paths ->
            paths.sorted().forEach { source ->
                val relative = FIXTURE_ROOT.relativize(source)
                val target = destination.resolve(relative)
                if (Files.isDirectory(source)) {
                    target.createDirectories()
                } else {
                    target.parent.createDirectories()
                    Files.copy(source, target)
                }
            }
        }
        return destination
    }

    private fun captureProject(project: Path): Map<String, ByteArray> =
        projectFiles(project).associateWith { relative ->
            project.resolve(relative).readBytes()
        }

    private fun assertProjectBytes(
        expected: Map<String, ByteArray>,
        project: Path
    ) {
        assertEquals(expected.keys, projectFiles(project))
        expected.forEach { (relative, bytes) ->
            assertArrayEquals(bytes, project.resolve(relative).readBytes(), relative)
        }
    }

    private fun projectFiles(project: Path): LinkedHashSet<String> {
        if (!project.exists()) return linkedSetOf()
        val result = linkedSetOf<String>()
        Files.walk(project).use { paths ->
            paths.filter(Files::isRegularFile)
                .map { slash(project.relativize(it)) }
                .sorted()
                .forEach(result::add)
        }
        return result
    }

    private fun replaceOnce(path: Path, old: String, replacement: String) {
        val source = path.readText()
        assertEquals(1, occurrences(source, old), "mutation anchor in $path")
        path.writeText(source.replace(old, replacement))
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), "")

    private fun attachedDocumentation(source: String, method: String): String {
        val unit = JavaParser().parse(source).result.orElseThrow()
        val declaration = unit.findAll(
            com.github.javaparser.ast.body.MethodDeclaration::class.java
        ).single { it.nameAsString == method }
        val comment = declaration.comment.orElseThrow {
            AssertionError("Missing attached documentation on $method:\n$source")
        }
        return comment.content
            .lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun occurrences(source: String, token: String): Int =
        Regex(Regex.escape(token)).findAll(source).count()

    private fun slash(path: Path): String = path.toString().replace('\\', '/')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }

    private data class Mutation(
        val name: String,
        val apply: (Path) -> Unit
    )
}
