package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyMixinTargetFacts
import com.modporter.core.transforms.structural.MixinProjectFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyMixinTargets
import com.modporter.core.transforms.structural.migrateMixinInjectorSignatures
import com.modporter.core.transforms.structural.proven
import com.modporter.mapping.LegacyMembers
import com.modporter.mapping.TargetMembers
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyMixinTargetMigrationsTest {
    @TempDir
    lateinit var directory: Path

    private val legacyUse =
        "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;" +
            "Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)" +
            "Lnet/minecraft/world/InteractionResult;"
    private val useItemOn =
        "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;" +
            "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;" +
            "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;"
    private val smokeTarget =
        "Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos(" +
            "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
    private val eatTarget =
        "Lnet/minecraft/world/level/block/CakeBlock;eat(Lnet/minecraft/world/level/LevelAccessor;" +
            "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;" +
            "Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/InteractionResult;"
    private val legacyArmor =
        "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;" +
            "Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;" +
            "Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V"
    private val legacyRunTest =
        "runTest(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/gametest/framework/MultipleTestTracker;)V"
    private val nativeCreateInfo =
        "createGameTestInfo(Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerLevel;" +
            "Lnet/minecraft/gametest/framework/RetryOptions;)Ljava/util/Optional;"
    private val legacyLookup =
        "Lnet/minecraft/gametest/framework/GameTestRegistry;getTestFunction(Ljava/lang/String;)" +
            "Lnet/minecraft/gametest/framework/TestFunction;"
    private val nativeLookup =
        "Lnet/minecraft/gametest/framework/GameTestRegistry;findTestFunction(Ljava/lang/String;)Ljava/util/Optional;"

    // --- facts -----------------------------------------------------------------------------

    @Test
    fun `generated and curated facts agree with the platform member tables`() {
        val legacy = LegacyMixinTargetFacts.legacyUseDescriptor
        assertEquals(legacyUse, legacy)
        assertTrue(LegacyMixinTargetFacts.blockUseSplit.isNotEmpty())
        for ((owner, methods) in LegacyMixinTargetFacts.blockUseSplit) {
            assertTrue(LegacyMembers.declaresMethod(owner, "use", legacy), owner)
            for (method in methods) {
                assertTrue(method.name in setOf("useItemOn", "useWithoutItem"), "$owner ${method.name}")
                assertTrue(method.descriptor in TargetMembers.methodDescriptors(owner, method.name), "$owner ${method.name}")
            }
        }
        val armor = LegacyMixinTargetFacts.forwarder(
            "net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer",
            "renderArmorPiece",
            legacyArmor.removePrefix("renderArmorPiece")
        )
        assertNotNull(armor)
        assertTrue(armor.extendedDescriptor.endsWith("HumanoidModel;FFFFFF)V"), armor.extendedDescriptor)
        assertTrue("Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;" in armor.invokes)
        assertTrue(LegacyMixinTargetFacts.enclosingMoves.isNotEmpty())
        assertTrue(LegacyMixinTargetFacts.enclosingMoves.all { it.proven() })
        assertTrue(LegacyMixinTargetFacts.parameterInsertions.isNotEmpty())
        assertTrue(LegacyMixinTargetFacts.parameterInsertions.all { it.proven })
    }

    // --- split Block#use -------------------------------------------------------------------

    private fun useMixin(
        target: String,
        selector: String,
        at: String,
        handler: String,
        injector: String = "ModifyExpressionValue"
    ): String = """
        package example;

        import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
        import com.llamalad7.mixinextras.sugar.Local;
        import net.minecraft.world.InteractionHand;
        import net.minecraft.world.InteractionResult;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.level.block.$target;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.Inject;
        import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

        @Mixin($target.class)
        class UseExample {
            @$injector(method = "$selector", at = @At(value = "INVOKE", target = "$at"))
            $handler
        }
    """.trimIndent()

    @Test
    fun `use selector follows the split method whose code holds the invoke target`() {
        val pass = StructuralRefactorPass()
        val itemOn = useMixin(
            "BeehiveBlock",
            "use",
            smokeTarget,
            "private static boolean example${'$'}calm(boolean original, @Local(argsOnly = true) Player player) { return original || player == null; }"
        )
        val migrated = pass.migrateLegacyMixinTargets(itemOn)
        assertTrue(migrated.contains("method = \"useItemOn\""), migrated)
        assertTrue(migrated.contains("@Local(argsOnly = true) Player player"), migrated)
        assertEquals(migrated, pass.migrateLegacyMixinTargets(migrated))

        val qualified = useMixin(
            "BeehiveBlock",
            "use$legacyUse",
            smokeTarget,
            "private static boolean example${'$'}calm(boolean original) { return original; }"
        )
        val qualifiedMigrated = pass.migrateLegacyMixinTargets(qualified)
        assertTrue(qualifiedMigrated.contains("method = \"useItemOn$useItemOn\""), qualifiedMigrated)

        val withoutItem = useMixin(
            "CakeBlock",
            "use",
            eatTarget,
            "private void example${'$'}eat(CallbackInfoReturnable<InteractionResult> cir, @Local(argsOnly = true) Player player) {}",
            injector = "Inject"
        )
        val withoutItemMigrated = pass.migrateLegacyMixinTargets(withoutItem)
        assertTrue(withoutItemMigrated.contains("method = \"useWithoutItem\""), withoutItemMigrated)
    }

    @Test
    fun `use selectors the split facts cannot decide stay unchanged`() {
        val pass = StructuralRefactorPass()
        val sources = listOf(
            // the target is invoked by both split methods
            useMixin(
                "ComposterBlock",
                "use",
                "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;",
                "private Comparable<?> example${'$'}value(Comparable<?> original) { return original; }"
            ),
            // the target is invoked by neither
            useMixin(
                "CakeBlock",
                "use",
                "Lnet/minecraft/world/level/Level;isClientSide()Z",
                "private boolean example${'$'}side(boolean original) { return original; }"
            ),
            // useWithoutItem takes no InteractionHand
            useMixin(
                "CakeBlock",
                "use",
                eatTarget,
                "private void example${'$'}eat(CallbackInfoReturnable<InteractionResult> cir, @Local(argsOnly = true) InteractionHand hand) {}",
                injector = "Inject"
            ),
            // useItemOn returns ItemInteractionResult, so the callback type would change
            useMixin(
                "BeehiveBlock",
                "use",
                smokeTarget,
                "private void example${'$'}smoke(CallbackInfoReturnable<InteractionResult> cir) {}",
                injector = "Inject"
            ),
            // a captured local that is not an argument cannot be re-proven
            useMixin(
                "BeehiveBlock",
                "use",
                smokeTarget,
                "private static boolean example${'$'}calm(boolean original, @Local Player player) { return original; }"
            ),
            // a locally declared class shadows the platform target
            useMixin(
                "BeehiveBlock",
                "use",
                smokeTarget,
                "private static boolean example${'$'}calm(boolean original) { return original; }"
            ).replace("import net.minecraft.world.level.block.BeehiveBlock;\n", "")
                .replace("class UseExample {", "class BeehiveBlock {}\nclass UseExample {"),
            // a look-alike Mixin annotation is not Mixin
            useMixin(
                "BeehiveBlock",
                "use",
                smokeTarget,
                "private static boolean example${'$'}calm(boolean original) { return original; }"
            ).replace("import org.spongepowered.asm.mixin.Mixin;\n", "import example.annotations.Mixin;\n"),
            """
                package example;
                /** @Mixin(BeehiveBlock.class) method = "use" isSmokeyPos */
                class Notes {}
            """.trimIndent()
        )
        for (source in sources) {
            assertEquals(source, pass.migrateLegacyMixinTargets(source))
        }
    }

    // --- removed @At INVOKE targets -----------------------------------------------------------

    private val smithingSource = """
        package example;

        import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
        import net.minecraft.world.inventory.SmithingMenu;
        import net.minecraft.world.item.ItemStack;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;

        @Mixin(SmithingMenu.class)
        class SmithingExample {
            @ModifyExpressionValue(
                method = "createResult",
                at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/crafting/SmithingRecipe;assemble(Lnet/minecraft/world/Container;Lnet/minecraft/core/RegistryAccess;)Lnet/minecraft/world/item/ItemStack;"
                )
            )
            private ItemStack example${'$'}result(ItemStack original) {
                return original;
            }
        }
    """.trimIndent()

    private fun cameraSource(body: String): String = """
        package example;

        import net.minecraft.client.Camera;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.ModifyArg;

        @Mixin(Camera.class)
        abstract class CameraExample {
            @ModifyArg(
                method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
                at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(D)D"),
                index = 0
            )
            private double example${'$'}zoom(double distance) {
                $body
            }
        }
    """.trimIndent()

    @Test
    fun `removed invoke target moves to the single same arity overload`() {
        val pass = StructuralRefactorPass()
        val migrated = pass.migrateLegacyMixinTargets(smithingSource)
        assertTrue(
            migrated.contains(
                "SmithingRecipe;assemble(Lnet/minecraft/world/item/crafting/RecipeInput;" +
                    "Lnet/minecraft/core/HolderLookup${'$'}Provider;)Lnet/minecraft/world/item/ItemStack;"
            ),
            migrated
        )
        assertTrue(migrated.contains("private ItemStack example${'$'}result(ItemStack original)"), migrated)
        assertEquals(migrated, pass.migrateLegacyMixinTargets(migrated))
    }

    @Test
    fun `narrowed mirrored value keeps the handler body and casts its result`() {
        val pass = StructuralRefactorPass()
        val migrated = pass.migrateLegacyMixinTargets(cameraSource("return distance * ZoomSettings.factor();"))
        assertTrue(migrated.contains("target = \"Lnet/minecraft/client/Camera;getMaxZoom(F)F\""), migrated)
        assertTrue(migrated.contains("private float example${'$'}zoom(float distance)"), migrated)
        assertTrue(migrated.contains("return (float) (distance * ZoomSettings.factor());"), migrated)
        assertEquals(migrated, pass.migrateLegacyMixinTargets(migrated))

        val handler = withoutAnnotation(
            migrated.substringAfter("abstract class CameraExample {").substringBeforeLast("}"),
            "@ModifyArg("
        )
        compile(
            mapOf(
                "example/ZoomSettings.java" to """
                    package example;
                    final class ZoomSettings { static double factor() { return 2.5; } }
                """,
                "example/CameraExample.java" to """
                    package example;
                    abstract class CameraExample {
                        $handler
                        float probe(float value) { return example${'$'}zoom(value); }
                    }
                """
            )
        )
    }

    @Test
    fun `invoke targets that still exist or cannot be mirrored stay unchanged`() {
        val pass = StructuralRefactorPass()
        val sources = listOf(
            cameraSource("distance = distance * 2.0;\n        return distance;"),
            smithingSource.replace(
                "SmithingRecipe;assemble(Lnet/minecraft/world/Container;Lnet/minecraft/core/RegistryAccess;)",
                "SmithingRecipe;assemble(Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/core/HolderLookup${'$'}Provider;)"
            ),
            smithingSource
                .replace("import com.llamalad7.mixinextras.injector.ModifyExpressionValue;", "import org.spongepowered.asm.mixin.injection.Redirect;")
                .replace("@ModifyExpressionValue(", "@Redirect("),
            smithingSource.replace(
                "import org.spongepowered.asm.mixin.Mixin;",
                "import example.annotations.Mixin;"
            )
        )
        for (source in sources) {
            assertEquals(source, pass.migrateLegacyMixinTargets(source))
        }
    }

    // --- explicit descriptors extended by 1.21.1 ---------------------------------------------

    private fun armorSource(at: String, mirrored: String): String = """
        package example;

        import com.llamalad7.mixinextras.sugar.Local;
        import com.mojang.blaze3d.vertex.PoseStack;
        import net.minecraft.client.model.HumanoidModel;
        import net.minecraft.client.renderer.MultiBufferSource;
        import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
        import net.minecraft.world.entity.EquipmentSlot;
        import net.minecraft.world.entity.LivingEntity;
        import net.minecraft.world.item.ItemStack;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.Inject;
        import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

        @Mixin(HumanoidArmorLayer.class)
        public class ArmorExample {
            @Inject(
                method = "$legacyArmor",
                at = @At(value = "INVOKE", target = "$at"),
                cancellable = true
            )
            private void example${'$'}render($mirrored, CallbackInfo ci, @Local ItemStack stack) {
                if (stack.isEmpty()) ci.cancel();
            }
        }
    """.trimIndent()

    private val armorParameters =
        "PoseStack poseStack, MultiBufferSource buffers, LivingEntity entity, EquipmentSlot slot, int light, HumanoidModel<?> model"

    @Test
    fun `explicit descriptor follows the extended overload its legacy form forwards to`() {
        val pass = StructuralRefactorPass()
        val source = armorSource("Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;", armorParameters)
        val migrated = pass.migrateMixinInjectorSignatures(source)
        assertTrue(
            migrated.contains("method = \"" + legacyArmor.removeSuffix(")V") + "FFFFFF)V\""),
            migrated
        )
        assertTrue(
            migrated.contains(
                "HumanoidModel<?> model, float limbSwing, float limbSwingAmount, float partialTick, " +
                    "float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci, @Local ItemStack stack"
            ),
            migrated
        )
        assertEquals(migrated, pass.migrateMixinInjectorSignatures(migrated))
    }

    @Test
    fun `extended descriptors without bytecode evidence stay unchanged`() {
        val pass = StructuralRefactorPass()
        val sources = listOf(
            // the extended overload does not invoke this target
            armorSource("Lnet/minecraft/world/item/ItemStack;isEmpty()Z", armorParameters),
            // the handler mirrors only part of the legacy parameters
            armorSource(
                "Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;",
                "PoseStack poseStack, MultiBufferSource buffers, LivingEntity entity"
            ),
            armorSource("Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;", armorParameters)
                .replace("import org.spongepowered.asm.mixin.injection.Inject;", "import example.annotations.Inject;")
        )
        for (source in sources) {
            assertEquals(source, pass.migrateMixinInjectorSignatures(source))
        }
    }

    // --- curated enclosing moves ------------------------------------------------------------------

    private fun redirectSource(fallback: String, captured: String, body: String = ""): String = """
        package example;

        import javax.annotation.Nullable;

        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.Redirect;

        import net.minecraft.core.BlockPos;
        import net.minecraft.gametest.framework.GameTestRegistry;
        import net.minecraft.gametest.framework.MultipleTestTracker;
        import net.minecraft.gametest.framework.TestCommand;
        import net.minecraft.gametest.framework.TestFunction;
        import net.minecraft.server.level.ServerLevel;

        @Mixin(TestCommand.class)
        public class LookupExample {
            @Redirect(
                    method = "$legacyRunTest",
                    at = @At(value = "INVOKE", target = "$legacyLookup"),
                    require = 0
            )
            private static TestFunction example${'$'}lookup(String testName, $captured) {
                $body
                TestFunction custom = FixtureFunctions.find(level, pos, testName);
                if (custom == null)
                    return $fallback;
                return custom;
            }
        }
    """.trimIndent()

    private val capturedLegacy = "ServerLevel level, BlockPos pos, @Nullable MultipleTestTracker tracker"

    @Test
    fun `redirect follows the curated move and returns the native Optional`() {
        val pass = StructuralRefactorPass()
        val raw = pass.migrateLegacyMixinTargets(redirectSource("GameTestRegistry.getTestFunction(testName)", capturedLegacy))
        val piped = pass.migrateLegacyMixinTargets(
            redirectSource("GameTestRegistry.findTestFunction(testName).orElseThrow()", capturedLegacy)
        )
        assertEquals(raw, piped)
        assertTrue(raw.contains("method = \"$nativeCreateInfo\""), raw)
        assertTrue(raw.contains("target = \"$nativeLookup\""), raw)
        assertTrue(raw.contains("require = 0"), raw)
        assertTrue(
            raw.contains(
                "private static java.util.Optional<net.minecraft.gametest.framework.TestFunction> example${'$'}lookup(" +
                    "String testName, BlockPos pos, ServerLevel level, net.minecraft.gametest.framework.RetryOptions retryOptions)"
            ),
            raw
        )
        assertTrue(raw.contains("return GameTestRegistry.findTestFunction(testName);"), raw)
        assertTrue(raw.contains("return java.util.Optional.of(custom);"), raw)
        assertFalse(raw.contains("import net.minecraft.gametest.framework.MultipleTestTracker;"), raw)
        assertFalse(raw.contains("import javax.annotation.Nullable;"), raw)
        assertEquals(raw, pass.migrateLegacyMixinTargets(raw))

        val handler = withoutAnnotation(
            raw.substringAfter("public class LookupExample {").substringBeforeLast("}"),
            "@Redirect("
        ).replace("private static", "static")
        compile(
            mapOf(
                "net/minecraft/core/BlockPos.java" to "package net.minecraft.core; public final class BlockPos {}",
                "net/minecraft/server/level/ServerLevel.java" to "package net.minecraft.server.level; public final class ServerLevel {}",
                "net/minecraft/gametest/framework/RetryOptions.java" to "package net.minecraft.gametest.framework; public final class RetryOptions {}",
                "net/minecraft/gametest/framework/TestFunction.java" to "package net.minecraft.gametest.framework; public class TestFunction {}",
                "net/minecraft/gametest/framework/GameTestRegistry.java" to """
                    package net.minecraft.gametest.framework;
                    import java.util.Optional;
                    public final class GameTestRegistry {
                        public static TestFunction registered;
                        public static Optional<TestFunction> findTestFunction(String name) {
                            return Optional.ofNullable("registered".equals(name) ? registered : null);
                        }
                    }
                """,
                "example/FixtureFunctions.java" to """
                    package example;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.gametest.framework.TestFunction;
                    import net.minecraft.server.level.ServerLevel;
                    final class FixtureFunctions {
                        static TestFunction fixture;
                        static TestFunction find(ServerLevel level, BlockPos pos, String name) {
                            return "fixture".equals(name) ? fixture : null;
                        }
                    }
                """,
                "example/LookupExample.java" to """
                    package example;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.gametest.framework.GameTestRegistry;
                    import net.minecraft.gametest.framework.TestFunction;
                    import net.minecraft.server.level.ServerLevel;
                    public class LookupExample {
                        $handler
                    }
                """,
                "example/Probe.java" to """
                    package example;
                    import java.util.Optional;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.gametest.framework.GameTestRegistry;
                    import net.minecraft.gametest.framework.RetryOptions;
                    import net.minecraft.gametest.framework.TestFunction;
                    import net.minecraft.server.level.ServerLevel;
                    public final class Probe {
                        public static String verify() {
                            BlockPos pos = new BlockPos();
                            ServerLevel level = new ServerLevel();
                            RetryOptions retry = new RetryOptions();
                            FixtureFunctions.fixture = new TestFunction();
                            GameTestRegistry.registered = new TestFunction();
                            Optional<TestFunction> fixture = LookupExample.example${'$'}lookup("fixture", pos, level, retry);
                            if (fixture.orElseThrow() != FixtureFunctions.fixture) throw new AssertionError("fixture identity");
                            Optional<TestFunction> registered = LookupExample.example${'$'}lookup("registered", pos, level, retry);
                            if (registered.orElseThrow() != GameTestRegistry.registered) throw new AssertionError("registry identity");
                            if (LookupExample.example${'$'}lookup("missing", pos, level, retry).isPresent())
                                throw new AssertionError("missing test must stay on the native empty path");
                            return "passed";
                        }
                    }
                """
            ),
            probe = "example.Probe"
        )
    }

    @Test
    fun `redirects the curated move cannot carry stay unchanged`() {
        val pass = StructuralRefactorPass()
        val sources = listOf(
            // the dropped tracker is still read
            redirectSource(
                "GameTestRegistry.getTestFunction(testName)",
                capturedLegacy,
                body = "if (tracker != null) return null;"
            ),
            // a null result has no Optional counterpart that keeps the legacy failure
            redirectSource("null", capturedLegacy),
            // a different invoke target is not the curated move
            redirectSource("GameTestRegistry.getTestFunction(testName)", capturedLegacy)
                .replace("target = \"$legacyLookup\"", "target = \"Lexample/Other;lookup(Ljava/lang/String;)Lnet/minecraft/gametest/framework/TestFunction;\""),
            redirectSource("GameTestRegistry.getTestFunction(testName)", capturedLegacy)
                .replace("import org.spongepowered.asm.mixin.injection.Redirect;", "import example.annotations.Redirect;")
        )
        for (source in sources) {
            assertEquals(source, pass.migrateLegacyMixinTargets(source))
        }
    }

    // --- invoker parameter insertions --------------------------------------------------------

    private val accessor = """
        package example.mixin;

        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.gen.Invoker;

        import net.minecraft.core.dispenser.DispenseItemBehavior;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.block.DispenserBlock;

        @Mixin(DispenserBlock.class)
        public interface DispenserAccess {
            @Invoker("getDispenseMethod")
            DispenseItemBehavior example${'$'}dispenseMethod(ItemStack stack);
        }
    """.trimIndent()

    /** [method] is given at column zero; it is placed as a member of the caller class. */
    private fun caller(method: String): String = """
        package example;

        import org.jetbrains.annotations.Nullable;

        import example.mixin.DispenserAccess;

        import net.minecraft.core.dispenser.DispenseItemBehavior;
        import net.minecraft.server.level.ServerLevel;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.level.block.Blocks;

        public final class DispenseLookup {
            METHOD

            public static DispenseItemBehavior probe(ItemStack stack) {
                return lookup(stack);
            }
        }
    """.trimIndent().replace("METHOD", method.trimIndent().replace("\n", "\n    "))

    private fun invokerPass(): StructuralRefactorPass = StructuralRefactorPass().apply {
        projectMixinFacts = MixinProjectFacts.fromSources(this, listOf(accessor), emptyList())
    }

    @Test
    fun `invoker declarations take the inserted parameter`() {
        val pass = StructuralRefactorPass()
        val migrated = pass.migrateLegacyMixinTargets(accessor)
        assertTrue(
            migrated.contains("example${'$'}dispenseMethod(net.minecraft.world.level.Level level, ItemStack stack);"),
            migrated
        )
        assertEquals(migrated, pass.migrateLegacyMixinTargets(migrated))

        val derived = accessor.replace(
            "@Invoker(\"getDispenseMethod\")\n    DispenseItemBehavior example${'$'}dispenseMethod",
            "@Invoker\n    DispenseItemBehavior callGetDispenseMethod"
        )
        assertTrue(
            pass.migrateLegacyMixinTargets(derived)
                .contains("callGetDispenseMethod(net.minecraft.world.level.Level level, ItemStack stack);")
        )
        val facts = MixinProjectFacts.fromSources(pass, listOf(accessor, migrated), emptyList())
        assertEquals(setOf("example${'$'}dispenseMethod"), facts.invokers["example.mixin.DispenserAccess"]?.keys)
    }

    @Test
    fun `invoker callers pass the level in scope`() {
        val pass = invokerPass()
        val source = caller(
            """
            static DispenseItemBehavior lookup(ServerLevel world, ItemStack stack) {
                return ((DispenserAccess) Blocks.DISPENSER).example${'$'}dispenseMethod(stack);
            }
            """.trimIndent()
        )
        val migrated = pass.migrateLegacyMixinTargets(source)
        assertTrue(migrated.contains(".example${'$'}dispenseMethod(world, stack);"), migrated)
        assertEquals(migrated, pass.migrateLegacyMixinTargets(migrated))
    }

    @Test
    fun `nullable invoker callers without a level use the running server overworld`() {
        val pass = invokerPass()
        val source = caller(
            """
            @Nullable
            private static DispenseItemBehavior lookup(ItemStack stack) {
                return ((DispenserAccess) Blocks.DISPENSER).example${'$'}dispenseMethod(stack);
            }
            """.trimIndent()
        )
        val migrated = pass.migrateLegacyMixinTargets(source)
        assertTrue(
            migrated.contains(
                "net.minecraft.server.MinecraftServer server = " +
                    "net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();"
            ),
            migrated
        )
        assertTrue(migrated.contains("if (server == null)\n            return null;"), migrated)
        assertTrue(
            migrated.contains(
                ".example${'$'}dispenseMethod(server.getLevel(net.minecraft.world.level.Level.OVERWORLD), stack);"
            ),
            migrated
        )
        assertEquals(migrated, pass.migrateLegacyMixinTargets(migrated))

        compile(
            mapOf(
                "org/spongepowered/asm/mixin/Mixin.java" to "package org.spongepowered.asm.mixin; public @interface Mixin { Class<?> value(); }",
                "org/spongepowered/asm/mixin/gen/Invoker.java" to "package org.spongepowered.asm.mixin.gen; public @interface Invoker { String value() default \"\"; }",
                "org/jetbrains/annotations/Nullable.java" to "package org.jetbrains.annotations; public @interface Nullable {}",
                "net/minecraft/world/level/Level.java" to "package net.minecraft.world.level; public class Level { public static final Object OVERWORLD = new Object(); }",
                "net/minecraft/server/level/ServerLevel.java" to "package net.minecraft.server.level; public class ServerLevel extends net.minecraft.world.level.Level {}",
                "net/minecraft/world/item/ItemStack.java" to "package net.minecraft.world.item; public final class ItemStack {}",
                "net/minecraft/core/dispenser/DispenseItemBehavior.java" to "package net.minecraft.core.dispenser; public interface DispenseItemBehavior {}",
                "net/minecraft/server/MinecraftServer.java" to """
                    package net.minecraft.server;
                    import net.minecraft.server.level.ServerLevel;
                    public final class MinecraftServer {
                        public final ServerLevel overworld = new ServerLevel();
                        public Object requested;
                        public ServerLevel getLevel(Object key) { requested = key; return overworld; }
                    }
                """,
                "net/neoforged/neoforge/server/ServerLifecycleHooks.java" to """
                    package net.neoforged.neoforge.server;
                    public final class ServerLifecycleHooks {
                        public static net.minecraft.server.MinecraftServer current;
                        public static net.minecraft.server.MinecraftServer getCurrentServer() { return current; }
                    }
                """,
                "example/mixin/DispenserAccess.java" to pass.migrateLegacyMixinTargets(accessor)
                    .replace("import net.minecraft.world.level.block.DispenserBlock;\n", "")
                    .replace("@Mixin(DispenserBlock.class)", "@Mixin(Object.class)"),
                "net/minecraft/world/level/block/DispenserBlock.java" to """
                    package net.minecraft.world.level.block;
                    import net.minecraft.core.dispenser.DispenseItemBehavior;
                    import net.minecraft.world.item.ItemStack;
                    import net.minecraft.world.level.Level;
                    public class DispenserBlock implements example.mixin.DispenserAccess {
                        public static Level received;
                        public static int calls;
                        public static final DispenseItemBehavior BEHAVIOR = new DispenseItemBehavior() {};
                        public DispenseItemBehavior example${'$'}dispenseMethod(Level level, ItemStack stack) {
                            calls++;
                            received = level;
                            return BEHAVIOR;
                        }
                    }
                """,
                "net/minecraft/world/level/block/Blocks.java" to """
                    package net.minecraft.world.level.block;
                    public final class Blocks { public static final DispenserBlock DISPENSER = new DispenserBlock(); }
                """,
                "example/DispenseLookup.java" to migrated,
                "example/Probe.java" to """
                    package example;
                    import net.minecraft.server.MinecraftServer;
                    import net.minecraft.world.item.ItemStack;
                    import net.minecraft.world.level.Level;
                    import net.minecraft.world.level.block.DispenserBlock;
                    import net.neoforged.neoforge.server.ServerLifecycleHooks;
                    public final class Probe {
                        public static String verify() {
                            ServerLifecycleHooks.current = null;
                            if (DispenseLookup.probe(new ItemStack()) != null || DispenserBlock.calls != 0)
                                throw new AssertionError("no server must return null without dispatch");
                            MinecraftServer server = new MinecraftServer();
                            ServerLifecycleHooks.current = server;
                            if (DispenseLookup.probe(new ItemStack()) != DispenserBlock.BEHAVIOR)
                                throw new AssertionError("behavior identity");
                            if (DispenserBlock.received != server.overworld || server.requested != Level.OVERWORLD)
                                throw new AssertionError("overworld argument");
                            return "passed";
                        }
                    }
                """
            ),
            probe = "example.Probe"
        )
    }

    @Test
    fun `invoker callers without a provable level stay unchanged`() {
        val pass = invokerPass()
        val sources = listOf(
            // no Level in scope and no nullable contract to report an absent server
            caller(
                """
                static DispenseItemBehavior lookup(ItemStack stack) {
                    return ((DispenserAccess) Blocks.DISPENSER).example${'$'}dispenseMethod(stack);
                }
                """.trimIndent()
            ),
            // two candidate levels
            caller(
                """
                static DispenseItemBehavior lookup(Level first, ServerLevel second, ItemStack stack) {
                    return ((DispenserAccess) Blocks.DISPENSER).example${'$'}dispenseMethod(stack);
                }
                """.trimIndent()
            ),
            // a same-named method on another receiver
            caller(
                """
                static DispenseItemBehavior lookup(ServerLevel level, ItemStack stack) {
                    return Other.example${'$'}dispenseMethod(stack);
                }
                """.trimIndent()
            )
        )
        for (source in sources) {
            assertEquals(source, pass.migrateLegacyMixinTargets(source))
        }
        val unknown = caller(
            """
            static DispenseItemBehavior lookup(ServerLevel world, ItemStack stack) {
                return ((DispenserAccess) Blocks.DISPENSER).example${'$'}dispenseMethod(stack);
            }
            """.trimIndent()
        )
        assertEquals(unknown, StructuralRefactorPass().migrateLegacyMixinTargets(unknown))
    }

    // --- unreachable plugin guards -----------------------------------------------------------

    private fun plugin(guards: String, declaration: String = "public class ExamplePlugin implements IMixinConfigPlugin"): String = """
        package example.mixin;

        import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

        $declaration {
            @Override
            public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
                $guards
                return true;
            }
        }
    """.trimIndent()

    private val guards = """
        if (targetClassName.startsWith("example.mixin.compat.mapviewer") && !Compat.mapViewerLoaded())
                    return false;
                if (targetClassName.startsWith("net.minecraft.client.gui") && !Compat.clientLoaded())
                    return false;
    """.trimIndent()

    private fun pluginPass(plugin: String? = "example.mixin.ExamplePlugin"): StructuralRefactorPass =
        StructuralRefactorPass().apply {
            projectMixinFacts = MixinProjectFacts(configs = listOf(MixinProjectFacts.Config("example.mixin", plugin)))
        }

    @Test
    fun `plugin guard on its own mixin package tests the mixin class name`() {
        val pass = pluginPass()
        val migrated = pass.migrateLegacyMixinTargets(plugin(guards))
        assertTrue(migrated.contains("if (mixinClassName.startsWith(\"example.mixin.compat.mapviewer\")"), migrated)
        assertTrue(migrated.contains("if (targetClassName.startsWith(\"net.minecraft.client.gui\")"), migrated)
        assertEquals(migrated, pass.migrateLegacyMixinTargets(migrated))
    }

    @Test
    fun `plugin guards without the config proof stay unchanged`() {
        val sources = listOf(
            pluginPass(plugin = null) to plugin(guards),
            pluginPass(plugin = "example.mixin.OtherPlugin") to plugin(guards),
            pluginPass() to plugin(guards.replace("\"example.mixin.compat.mapviewer\"", "\"example.mixinextra\"")),
            pluginPass() to plugin(
                guards,
                declaration = "interface IMixinConfigPlugin {}\npublic class ExamplePlugin implements IMixinConfigPlugin"
            ).replace("import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;\n", ""),
            pluginPass() to plugin("targetClassName = mixinClassName;\n        $guards")
        )
        for ((pass, source) in sources) {
            assertEquals(source, pass.migrateLegacyMixinTargets(source))
        }
    }

    @Test
    fun `mixin configs are read from their package and plugin keys`() {
        val config = MixinProjectFacts.parseConfig(
            """{"required": true, "package": "example.mixin", "plugin": "example.mixin.ExamplePlugin", "mixins": ["A"]}"""
        )
        assertEquals(MixinProjectFacts.Config("example.mixin", "example.mixin.ExamplePlugin"), config)
        assertEquals(
            MixinProjectFacts.Config("example.mixin", null),
            MixinProjectFacts.parseConfig("""{"package": "example.mixin", "client": []}""")
        )
        assertNull(MixinProjectFacts.parseConfig("""{"package": "example.mixin"}"""))
        assertNull(MixinProjectFacts.parseConfig("""{"type": "minecraft:crafting_shaped"}"""))
    }

    // --- helpers -----------------------------------------------------------------------------

    /** [text] without the annotation starting at [opening] (balanced parentheses, string literals skipped). */
    private fun withoutAnnotation(text: String, opening: String): String {
        val start = text.indexOf(opening)
        if (start < 0) return text
        var depth = 0
        var index = start + opening.length - 1
        var quoted = false
        while (index < text.length) {
            val character = text[index]
            when {
                quoted && character == '\\' -> index++
                character == '"' -> quoted = !quoted
                !quoted && character == '(' -> depth++
                !quoted && character == ')' -> if (--depth == 0) return text.removeRange(start, index + 1)
            }
            index++
        }
        error("unbalanced annotation $opening")
    }

    private fun compile(sources: Map<String, String>, probe: String? = null) {
        val root = directory.resolve("compile-${System.nanoTime()}")
        val files = sources.map { (relative, text) ->
            val file = root.resolve("sources").resolve(relative)
            file.parent.createDirectories()
            file.writeText(text.trimIndent() + "\n")
            file
        }
        val classes = root.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler is required")
        val result = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-proc:none",
            "-d",
            classes.toString(),
            *files.map(Path::toString).toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString(Charsets.UTF_8))
        if (probe == null) return
        URLClassLoader(arrayOf(classes.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            assertEquals("passed", loader.loadClass(probe).getMethod("verify").invoke(null))
        }
    }
}
