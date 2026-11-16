package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyMixinMemberMoves
import com.modporter.core.transforms.structural.LegacyMixinTargetFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyMixinMemberMovesTest {
    @TempDir
    lateinit var directory: Path

    private val noCollision =
        "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"

    private fun write(relative: String, text: String): Path {
        val file = directory.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text.trimIndent() + "\n")
        return file
    }

    private fun java(name: String, text: String): Path = write("src/main/java/${name.replace('.', '/')}.java", text)

    private fun config(mixins: List<String>, client: List<String> = emptyList()): Path {
        fun array(entries: List<String>) = entries.joinToString(",\n") { "    \"$it\"" }
        val file = directory.resolve("src/main/resources/example.mixins.json")
        file.parent.createDirectories()
        file.writeText(
            "{\n  \"required\": true,\n  \"package\": \"example.mixin\",\n" +
                "  \"mixins\": [\n${array(mixins)}\n  ],\n  \"client\": [\n${array(client)}\n  ]\n}\n"
        )
        return file
    }

    private fun migrate() = LegacyMixinMemberMoves.migrate(StructuralRefactorPass(), directory, dryRun = false)

    @Test
    fun `curated member moves agree with the platform tables`() {
        assertTrue(LegacyMixinTargetFacts.injectorMoves.isNotEmpty() && LegacyMixinTargetFacts.injectorMoves.all { it.proven })
        assertTrue(LegacyMixinTargetFacts.staticFieldMethods.isNotEmpty() && LegacyMixinTargetFacts.staticFieldMethods.all { it.proven })
        assertTrue(LegacyMixinTargetFacts.invokerFieldMoves.size == 3 && LegacyMixinTargetFacts.invokerFieldMoves.all { it.proven })
        assertTrue(LegacyMixinTargetFacts.removedStaticHelpers.isNotEmpty() && LegacyMixinTargetFacts.removedStaticHelpers.all { it.proven })
        assertEquals(
            listOf("Lnet/minecraft/core/Direction;"),
            LegacyMixinTargetFacts.invokerFieldMoves.single { it.legacyMethod.startsWith("getProjectile") }.appendedParameters
        )
        assertEquals("com.tterrag.registrate.builders.BlockBuilder", LegacyMixinTargetFacts.libraryBuilders.single().type)
    }

    // --- injector moves --------------------------------------------------------------------

    private fun entityHooks(handlerBody: String = "return original || (pose == Pose.CROUCHING && Stealth.hidden((Entity) (Object) this));", at: String = noCollision, priority: String = ", priority = 1200") =
        java(
            "example.mixin.EntityHooks",
            """
            package example.mixin;

            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;

            import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
            import com.llamalad7.mixinextras.injector.ModifyReturnValue;
            import com.llamalad7.mixinextras.sugar.Local;
            import example.Stealth;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.Pose;

            @Mixin(value = Entity.class$priority)
            public class EntityHooks {
            	private int counter;

            	@ModifyExpressionValue(method = "canEnterPose", at = @At(value = "INVOKE", target = "$at"))
            	public boolean example${'$'}fits(boolean original, @Local(argsOnly = true) Pose pose) {
            		$handlerBody
            	}

            	@ModifyReturnValue(method = "fireImmune()Z", at = @At("RETURN"))
            	public boolean example${'$'}fire(boolean original) {
            		return original || ((Entity) (Object) this).isOnFire();
            	}
            }
            """
        )

    private fun playerHooks(mixin: String = "@Mixin(Player.class)") = java(
        "example.mixin.PlayerHooks",
        """
        package example.mixin;

        import org.spongepowered.asm.mixin.Mixin;

        import net.minecraft.world.entity.EntityType;
        import net.minecraft.world.entity.LivingEntity;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.level.Level;

        $mixin
        public abstract class PlayerHooks extends LivingEntity {
        	protected PlayerHooks(EntityType<? extends LivingEntity> type, Level level) {
        		super(type, level);
        	}

        	public void example${'$'}other() {}
        }
        """
    )

    @Test
    fun `injector into a member moved to a subclass follows it into that subclass mixin`() {
        val entity = entityHooks()
        val player = playerHooks()
        config(listOf("EntityHooks", "PlayerHooks"))

        val changes = migrate()
        assertEquals(2, changes.size, changes.joinToString())
        val entityText = entity.readText()
        val playerText = player.readText()
        assertFalse(entityText.contains("example\$fits"), entityText)
        assertTrue(entityText.contains("example\$fire"), entityText)
        assertTrue(entityText.contains("public class EntityHooks {\n\tprivate int counter;\n\n\t@ModifyReturnValue"), entityText)
        for (unused in listOf("ModifyExpressionValue;", "sugar.Local;", "example.Stealth;", "entity.Pose;")) {
            assertFalse(entityText.contains(unused), entityText)
        }
        assertTrue(entityText.contains("import net.minecraft.world.entity.Entity;"), entityText)

        assertTrue(playerText.contains("@Mixin(value = Player.class, priority = 1200)"), playerText)
        assertTrue(
            playerText.contains(
                "\t\tsuper(type, level);\n\t}\n\n" +
                    "\t@ModifyExpressionValue(method = \"canPlayerFitWithinBlocksAndEntitiesWhen\", at = @At(value = \"INVOKE\", target = \"$noCollision\"))\n" +
                    "\tpublic boolean example\$fits(boolean original, @Local(argsOnly = true) Pose pose) {\n" +
                    "\t\treturn original || (pose == Pose.CROUCHING && Stealth.hidden((Entity) (Object) this));\n\t}\n\n" +
                    "\tpublic void example\$other() {}"
            ),
            playerText
        )
        for (import in listOf(
            "org.spongepowered.asm.mixin.injection.At",
            "com.llamalad7.mixinextras.injector.ModifyExpressionValue",
            "com.llamalad7.mixinextras.sugar.Local",
            "example.Stealth",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.Pose"
        )) {
            assertTrue(playerText.contains("import $import;"), "$import\n$playerText")
        }
        assertTrue(migrate().isEmpty())
    }

    @Test
    fun `injector moves without a proven destination or meaning stay unchanged`() {
        val cases = listOf(
            // the handler reads a field of its own mixin
            Triple({ entityHooks(handlerBody = "return original || counter > 0;") }, { playerHooks() }, listOf("EntityHooks", "PlayerHooks") to emptyList<String>()),
            // the injection point does not survive into the 1.21.1 method
            Triple(
                { entityHooks(at = "Lnet/minecraft/world/entity/Entity;getBoundingBoxForPose(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/phys/AABB;") },
                { playerHooks() },
                listOf("EntityHooks", "PlayerHooks") to emptyList()
            ),
            // the player mixin applies on another side
            Triple({ entityHooks() }, { playerHooks() }, listOf("EntityHooks") to listOf("PlayerHooks")),
            // conflicting explicit priorities
            Triple({ entityHooks() }, { playerHooks("@Mixin(value = Player.class, priority = 900)") }, listOf("EntityHooks", "PlayerHooks") to emptyList())
        )
        for ((entity, player, lists) in cases) {
            val entityFile = entity()
            val playerFile = player()
            config(lists.first, lists.second)
            val before = entityFile.readText() to playerFile.readText()
            assertTrue(migrate().isEmpty())
            assertEquals(before, entityFile.readText() to playerFile.readText())
        }
    }

    // --- static field accessors ------------------------------------------------------------

    private fun lootProject(extraCaller: String? = null, transform: String = "axeOnly()") {
        java(
            "example.mixin.LootAccess",
            """
            package example.mixin;

            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.gen.Accessor;

            import net.minecraft.data.loot.BlockLootSubProvider;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

            @Mixin(BlockLootSubProvider.class)
            public interface LootAccess {
            	@Accessor("HAS_SILK_TOUCH")
            	static LootItemCondition.Builder example${'$'}silkTouch() {
            		throw new AssertionError();
            	}
            }
            """
        )
        java(
            "example.ExampleBlockLoot",
            """
            package example;

            import example.mixin.LootAccess;
            import net.minecraft.data.loot.BlockLootSubProvider;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

            public abstract class ExampleBlockLoot extends BlockLootSubProvider {
            	LootItemCondition.Builder silk() {
            		return LootAccess.example${'$'}silkTouch().invert();
            	}
            }
            """
        )
        java(
            "example.ExampleRegistrate",
            """
            package example;

            import com.tterrag.registrate.AbstractRegistrate;

            public class ExampleRegistrate extends AbstractRegistrate<ExampleRegistrate> {
            	static ExampleRegistrate create() { return null; }
            }
            """
        )
        java(
            "example.Tags",
            """
            package example;

            import com.tterrag.registrate.builders.BlockBuilder;
            import com.tterrag.registrate.util.nullness.NonNullFunction;
            import net.minecraft.world.level.block.Block;

            public class Tags {
            	public static <T extends Block, P> NonNullFunction<BlockBuilder<T, P>, BlockBuilder<T, P>> axeOnly() {
            		return b -> b;
            	}
            }
            """
        )
        java(
            "example.ExampleBlocks",
            """
            package example;

            import static example.Tags.axeOnly;

            import example.mixin.LootAccess;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.storage.loot.LootTable;

            public class ExampleBlocks {
            	private static final ExampleRegistrate REGISTRATE = ExampleRegistrate.create();

            	public static final Object BOX = REGISTRATE.block("box", Block::new)
            		.initialProperties(() -> Blocks.STONE)
            		.transform($transform)
            		.loot((tables, block) -> tables.add(block, LootTable.lootTable().when(LootAccess.example${'$'}silkTouch())))
            		.register();
            }
            """
        )
        if (extraCaller != null) java("example.Elsewhere", extraCaller)
        config(listOf("LootAccess"))
    }

    @Test
    fun `static field accessor becomes an invoker called on the proven provider`() {
        lootProject()
        val changes = migrate()
        assertEquals(3, changes.size, changes.joinToString())
        val accessor = directory.resolve("src/main/java/example/mixin/LootAccess.java").readText()
        assertTrue(accessor.contains("\t@Invoker(\"hasSilkTouch\")\n\tLootItemCondition.Builder example\$silkTouch();"), accessor)
        assertTrue(accessor.contains("import org.spongepowered.asm.mixin.gen.Invoker;"), accessor)
        assertFalse(accessor.contains("Accessor;"), accessor)
        assertTrue(
            directory.resolve("src/main/java/example/ExampleBlockLoot.java").readText()
                .contains("return ((LootAccess) this).example\$silkTouch().invert();")
        )
        assertTrue(
            directory.resolve("src/main/java/example/ExampleBlocks.java").readText()
                .contains(".when(((LootAccess) tables).example\$silkTouch())")
        )
        assertTrue(migrate().isEmpty())
    }

    @Test
    fun `static field accessor stays unchanged when any caller lacks a proven provider`() {
        val unprovable = listOf(
            """
            package example;

            import example.mixin.LootAccess;

            class Elsewhere {
            	static Object condition() {
            		return LootAccess.example${'$'}silkTouch();
            	}
            }
            """ to "axeOnly()",
            null to "b -> b"
        )
        for ((caller, transform) in unprovable) {
            lootProject(caller, transform)
            val files = listOf("mixin/LootAccess", "ExampleBlockLoot", "ExampleBlocks")
                .map { directory.resolve("src/main/java/example/$it.java") }
            val before = files.map { it.readText() }
            assertTrue(migrate().isEmpty())
            assertEquals(before, files.map { it.readText() })
            directory.resolve("src/main/java/example/Elsewhere.java").toFile().delete()
        }
    }

    // --- invokers replaced by field members --------------------------------------------------

    private fun projectileProject(launcherParameters: String) {
        java(
            "example.mixin.ProjectileAccess",
            """
            package example.mixin;

            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.gen.Invoker;

            import net.minecraft.core.Position;
            import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
            import net.minecraft.world.entity.projectile.Projectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            @Mixin(ProjectileDispenseBehavior.class)
            public interface ProjectileAccess {
            	@Invoker("getProjectile")
            	Projectile example${'$'}projectile(Level level, Position position, ItemStack stack);

            	@Invoker("getPower")
            	float example${'$'}power();

            	@Invoker("getUncertainty")
            	float example${'$'}uncertainty();
            }
            """
        )
        java(
            "example.Launcher",
            """
            package example;

            import example.mixin.ProjectileAccess;
            import net.minecraft.core.Direction;
            import net.minecraft.core.Position;
            import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
            import net.minecraft.world.entity.projectile.Projectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class Launcher {
            	static Projectile launch(ProjectileDispenseBehavior behavior, $launcherParameters) {
            		ProjectileAccess access = (ProjectileAccess) behavior;
            		Projectile projectile = access.example${'$'}projectile(level, position, stack);
            		projectile.shoot(1, 0, 0, access.example${'$'}power(), access.example${'$'}uncertainty());
            		return projectile;
            	}
            }
            """
        )
        config(listOf("ProjectileAccess"))
    }

    @Test
    fun `invokers of moved projectile members become field accessors with the direction in scope`() {
        projectileProject("Level level, Position position, ItemStack stack, Direction facing")
        val changes = migrate()
        assertEquals(2, changes.size, changes.joinToString())
        val accessor = directory.resolve("src/main/java/example/mixin/ProjectileAccess.java").readText()
        assertTrue(
            accessor.contains(
                "\t@Accessor(\"projectileItem\")\n\tnet.minecraft.world.item.ProjectileItem example\$getProjectileItem();\n\n" +
                    "\t@Accessor(\"dispenseConfig\")\n\tnet.minecraft.world.item.ProjectileItem.DispenseConfig example\$getDispenseConfig();\n}"
            ),
            accessor
        )
        assertFalse(accessor.contains("@Invoker"), accessor)
        assertFalse(accessor.contains("import net.minecraft.core.Position;"), accessor)
        val launcher = directory.resolve("src/main/java/example/Launcher.java").readText()
        assertTrue(launcher.contains("access.example\$getProjectileItem().asProjectile(level, position, stack, facing);"), launcher)
        assertTrue(
            launcher.contains("access.example\$getDispenseConfig().power(), access.example\$getDispenseConfig().uncertainty()"),
            launcher
        )
        assertTrue(migrate().isEmpty())
    }

    @Test
    fun `projectile invokers stay unchanged without a direction in scope`() {
        projectileProject("Level level, Position position, ItemStack stack")
        val files = listOf("mixin/ProjectileAccess", "Launcher").map { directory.resolve("src/main/java/example/$it.java") }
        val before = files.map { it.readText() }
        assertTrue(migrate().isEmpty())
        assertEquals(before, files.map { it.readText() })
    }

    // --- removed static helpers ------------------------------------------------------------

    private fun stewProject(consumer: String, extraMember: String = "") {
        java(
            "example.mixin.StewAccess",
            """
            package example.mixin;

            import java.util.function.Consumer;

            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.gen.Invoker;

            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.SuspiciousStewItem;

            @Mixin(SuspiciousStewItem.class)
            public interface StewAccess {
            	@Invoker("listPotionEffects")
            	static void example${'$'}effects(ItemStack stack, Consumer<MobEffectInstance> output) {
            		throw new AssertionError();
            	}
            $extraMember
            }
            """
        )
        java(
            "example.StewHits",
            """
            package example;

            import example.mixin.StewAccess;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;

            public class StewHits {
            	public boolean hit(ItemStack projectile, Entity target) {
            		if (target instanceof LivingEntity living)
            			StewAccess.example${'$'}effects(projectile, $consumer);

            		return false;
            	}
            }
            """
        )
        config(listOf("EntityHooks", "StewAccess"))
    }

    @Test
    fun `removed stew helper becomes the component iteration and its emptied accessor is unregistered`() {
        stewProject("living::addEffect")
        val changes = migrate()
        assertEquals(3, changes.size, changes.joinToString())
        val hits = directory.resolve("src/main/java/example/StewHits.java").readText()
        assertTrue(
            hits.contains(
                "\t\tif (target instanceof LivingEntity living)\n" +
                    "\t\t\tfor (net.minecraft.world.item.component.SuspiciousStewEffects.Entry entry : " +
                    "projectile.getOrDefault(net.minecraft.core.component.DataComponents.SUSPICIOUS_STEW_EFFECTS, " +
                    "net.minecraft.world.item.component.SuspiciousStewEffects.EMPTY).effects())\n" +
                    "\t\t\t\tliving.addEffect(entry.createEffectInstance());\n"
            ),
            hits
        )
        assertFalse(hits.contains("StewAccess"), hits)
        assertFalse(directory.resolve("src/main/java/example/mixin/StewAccess.java").exists())
        val config = directory.resolve("src/main/resources/example.mixins.json").readText()
        assertFalse(config.contains("StewAccess"), config)
        assertTrue(config.contains("\"EntityHooks\"\n  ]"), config)
        assertTrue(migrate().isEmpty())
    }

    @Test
    fun `removed stew helper keeps an accessor that still has other members`() {
        stewProject(
            "living::addEffect",
            extraMember = "\n            \t@Invoker(\"finishUsingItem\")\n            \tnet.minecraft.world.item.ItemStack example\$finish();"
        )
        migrate()
        val accessor = directory.resolve("src/main/java/example/mixin/StewAccess.java").readText()
        assertFalse(accessor.contains("listPotionEffects"), accessor)
        assertTrue(accessor.contains("example\$finish"), accessor)
        assertTrue(directory.resolve("src/main/resources/example.mixins.json").readText().contains("StewAccess"))
    }

    @Test
    fun `removed stew helper with an unstable consumer stays unchanged`() {
        stewProject("effect -> living.addEffect(effect)")
        val files = listOf("mixin/StewAccess", "StewHits").map { directory.resolve("src/main/java/example/$it.java") }
        val before = files.map { it.readText() }
        assertTrue(migrate().isEmpty())
        assertEquals(before, files.map { it.readText() })
    }
}
