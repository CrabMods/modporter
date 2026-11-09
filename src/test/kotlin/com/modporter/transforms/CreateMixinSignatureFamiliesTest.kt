package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCreateMixinSignatureFamilies
import com.modporter.core.transforms.structural.migrateLegacyComponentSerializationSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CreateMixinSignatureFamiliesTest {
    private fun normalized(source: String): String = source.replace(Regex("""\s+"""), " ").trim()

    private fun cameraPass(multiplierReturn: String = "float"): StructuralRefactorPass =
        StructuralRefactorPass().apply {
            projectValueFacts = ProjectValueFacts(
                methods = mapOf(
                    "com.simibubi.create.content.trains.CameraDistanceModifier" to mapOf(
                        "getMultiplier/0" to listOf(
                            ProjectValueFacts.Method(multiplierReturn, emptyList())
                        ),
                        "getMultiplier/1" to listOf(
                            ProjectValueFacts.Method("float", listOf("float"))
                        )
                    )
                )
            )
        }

    private fun cameraSource(returnExpression: String): String = """
        import com.simibubi.create.content.trains.CameraDistanceModifier;
        import net.minecraft.client.Camera;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.ModifyArg;

        @Mixin(Camera.class)
        class Example {
            @ModifyArg(
                method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
                at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;getMaxZoom(D)D"
                ),
                index = 0
            )
            private double create${'$'}offset(double originalValue) {
                return $returnExpression;
            }
        }
    """.trimIndent()

    @Test
    fun `beehive smoke injection follows useItemOn and keeps the player argument capture`() {
        val source = """
            import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
            import com.llamalad7.mixinextras.sugar.Local;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.block.BeehiveBlock;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;

            @Mixin(BeehiveBlock.class)
            class Example {
                @ModifyExpressionValue(
                    method = "use",
                    at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
                    )
                )
                private static boolean create${'$'}smoke(
                    boolean original,
                    @Local(argsOnly = true) Player player
                ) {
                    return original || player != null;
                }
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        val migrated = pass.migrateCreateMixinSignatureFamilies(source)
        assertTrue(migrated.contains("""method = "useItemOn""""), migrated)
        assertTrue(migrated.contains("@Local(argsOnly = true) Player player"), migrated)
        assertEquals(migrated, pass.migrateCreateMixinSignatureFamilies(migrated))
    }

    @Test
    fun `fully qualified beehive types are admitted without relying on simple names`() {
        val source = """
            @org.spongepowered.asm.mixin.Mixin(net.minecraft.world.level.block.BeehiveBlock.class)
            class Example {
                @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
                    method = "use",
                    at = @org.spongepowered.asm.mixin.injection.At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
                    )
                )
                private static boolean create${'$'}smoke(
                    boolean original,
                    @com.llamalad7.mixinextras.sugar.Local(argsOnly = true)
                    net.minecraft.world.entity.player.Player player
                ) {
                    return original;
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateCreateMixinSignatureFamilies(source)
        assertTrue(migrated.contains("""method = "useItemOn""""), migrated)
    }

    @Test
    fun `smithing expression target uses recipe input and holder lookup provider`() {
        val source = """
            import net.minecraft.world.inventory.SmithingMenu;
            import net.minecraft.world.item.ItemStack;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;
            import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

            @Mixin(SmithingMenu.class)
            class Example {
                @ModifyExpressionValue(
                    method = "createResult",
                    at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/item/crafting/SmithingRecipe;assemble(Lnet/minecraft/world/Container;Lnet/minecraft/core/RegistryAccess;)Lnet/minecraft/world/item/ItemStack;"
                    )
                )
                private ItemStack create${'$'}filter(ItemStack original) {
                    return original;
                }
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        val migrated = pass.migrateCreateMixinSignatureFamilies(source)
        assertTrue(
            migrated.contains(
                "assemble(Lnet/minecraft/world/item/crafting/RecipeInput;" +
                    "Lnet/minecraft/core/HolderLookup${'$'}Provider;)"
            ),
            migrated
        )
        assertFalse(migrated.contains("Lnet/minecraft/world/Container;"), migrated)
        assertEquals(migrated, pass.migrateCreateMixinSignatureFamilies(migrated))
    }

    @Test
    fun `map saved data handlers carry native registries into station marker serialization`() {
        val source = """
            package com.simibubi.create.foundation.mixin;

            import com.simibubi.create.content.trains.station.StationMarker;
            import java.util.Map;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

            class HolderLookup {
                static class Provider {}
            }

            @Mixin(MapItemSavedData.class)
            class Example {
                Map<String, StationMarker> create${'$'}stationMarkers;

                @Inject(
                    method = "load(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;",
                    at = @At("RETURN")
                )
                private static void create${'$'}load(
                    CompoundTag compound,
                    CallbackInfoReturnable<MapItemSavedData> cir
                ) {
                    ListTag listTag = new ListTag();
                    for (int i = 0; i < listTag.size(); i++) {
                        StationMarker marker = StationMarker.load(listTag.getCompound(i));
                    }
                }

                @Inject(
                    method = "save(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
                    at = @At("RETURN")
                )
                public void create${'$'}save(
                    CompoundTag compound,
                    CallbackInfoReturnable<CompoundTag> cir
                ) {
                    ListTag listTag = new ListTag();
                    for (StationMarker stationMarker : create${'$'}stationMarkers.values()) {
                        listTag.add(stationMarker.save());
                    }
                }
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        val migrated = pass.migrateCreateMixinSignatureFamilies(source)
        assertTrue(
            migrated.contains(
                "load(Lnet/minecraft/nbt/CompoundTag;" +
                    "Lnet/minecraft/core/HolderLookup${'$'}Provider;)"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "save(Lnet/minecraft/nbt/CompoundTag;" +
                    "Lnet/minecraft/core/HolderLookup${'$'}Provider;)"
            ),
            migrated
        )
        val compact = normalized(migrated)
        assertTrue(
            compact.contains(
                "CompoundTag compound, net.minecraft.core.HolderLookup.Provider registries, " +
                    "CallbackInfoReturnable<MapItemSavedData> cir"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "CompoundTag compound, net.minecraft.core.HolderLookup.Provider registries, " +
                    "CallbackInfoReturnable<CompoundTag> cir"
            ),
            migrated
        )
        assertTrue(migrated.contains("StationMarker.load(listTag.getCompound(i), registries)"), migrated)
        assertTrue(migrated.contains("stationMarker.save(registries)"), migrated)
        assertFalse(migrated.contains("import net.minecraft.core.HolderLookup;"), migrated)
        assertEquals(migrated, pass.migrateCreateMixinSignatureFamilies(migrated))
    }

    @Test
    fun `map load and save pair is never half migrated`() {
        val source = """
            import com.simibubi.create.content.trains.station.StationMarker;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

            @Mixin(MapItemSavedData.class)
            class Example {
                @Inject(
                    method = "load(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;",
                    at = @At("RETURN")
                )
                private static void create${'$'}load(
                    CompoundTag compound,
                    CallbackInfoReturnable<MapItemSavedData> cir
                ) {
                    ListTag listTag = new ListTag();
                    StationMarker marker = StationMarker.load(listTag.getCompound(0));
                }
            }
        """.trimIndent()

        assertEquals(source, StructuralRefactorPass().migrateCreateMixinSignatureFamilies(source))
    }

    @Test
    fun `station marker consumes the provider exposed by the native map handlers`() {
        val source = """
            package com.simibubi.create.content.trains.station;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;

            public class StationMarker {
                public static StationMarker load(CompoundTag tag) {
                    Component name = Component.Serializer.fromJson(tag.getString("name"));
                    return null;
                }

                public CompoundTag save() {
                    CompoundTag tag = new CompoundTag();
                    Component name = null;
                    tag.putString("name", Component.Serializer.toJson(name));
                    return tag;
                }
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        val widened = pass.migrateCreateMixinSignatureFamilies(source)
        assertEquals(widened, pass.migrateCreateMixinSignatureFamilies(widened))
        val migrated = pass.migrateLegacyComponentSerializationSource(widened)
        val compact = normalized(migrated)
        assertTrue(
            compact.contains(
                "load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "save(net.minecraft.core.HolderLookup.Provider registries)"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "Component.Serializer.fromJson(tag.getString(\"name\"), registries)"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "Component.Serializer.toJson(name, registries)"
            ),
            migrated
        )
        assertFalse(migrated.contains("BuiltInRegistries"), migrated)
    }

    @Test
    fun `camera modify arg follows native float descriptor and source proven float multiplier`() {
        val source = cameraSource("originalValue * CameraDistanceModifier.getMultiplier()")
        val pass = cameraPass()
        val migrated = pass.migrateCreateMixinSignatureFamilies(source)
        assertTrue(migrated.contains("getMaxZoom(F)F"), migrated)
        assertTrue(migrated.contains("private float create${'$'}offset(float originalValue)"), migrated)
        assertFalse(migrated.contains("private double"), migrated)
        assertEquals(migrated, pass.migrateCreateMixinSignatureFamilies(migrated))
    }

    @Test
    fun `camera rejects missing double or behavior changing multiplier evidence`() {
        val validBody = cameraSource("originalValue * CameraDistanceModifier.getMultiplier()")
        assertEquals(
            validBody,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(validBody)
        )
        assertEquals(
            validBody,
            cameraPass("double").migrateCreateMixinSignatureFamilies(validBody)
        )

        val sideEffectBody = cameraSource(
            "originalValue * CameraDistanceModifier.getMultiplier() + audit(originalValue)"
        )
        assertEquals(
            sideEffectBody,
            cameraPass().migrateCreateMixinSignatureFamilies(sideEffectBody)
        )

        val localHelper = validBody
            .replace("import com.simibubi.create.content.trains.CameraDistanceModifier;\n", "")
            .replace(
                "\n@Mixin(Camera.class)",
                "\nclass CameraDistanceModifier {" +
                    " static float getMultiplier() { return 1.0F; } }\n\n" +
                    "@Mixin(Camera.class)"
            )
        assertEquals(
            localHelper,
            cameraPass().migrateCreateMixinSignatureFamilies(localHelper)
        )
    }

    @Test
    fun `documentation strings and lookalike annotations do not authorize beehive migration`() {
        val docOnly = """
            import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
            import com.llamalad7.mixinextras.sugar.Local;
            import net.minecraft.world.entity.player.Player;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;

            /** @Mixin(BeehiveBlock.class), method = "use" */
            class OtherBlock {}
            @Mixin(OtherBlock.class)
            class Example {
                String example = "Lnet/minecraft/world/level/block/BeehiveBlock;";
                @ModifyExpressionValue(
                    method = "use",
                    at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
                    )
                )
                private static boolean change(
                    boolean original,
                    @Local(argsOnly = true) Player player
                ) {
                    return original;
                }
            }
        """.trimIndent()
        assertEquals(docOnly, StructuralRefactorPass().migrateCreateMixinSignatureFamilies(docOnly))

        val fakeAnnotations = """
            @interface Mixin { Class<?> value(); }
            @interface At { String value(); String target(); }
            @interface ModifyExpressionValue { String method(); At at(); }
            @interface Local { boolean argsOnly(); }

            @Mixin(net.minecraft.world.level.block.BeehiveBlock.class)
            class Example {
                @ModifyExpressionValue(
                    method = "use",
                    at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
                    )
                )
                private static boolean change(
                    boolean original,
                    @Local(argsOnly = true) net.minecraft.world.entity.player.Player player
                ) {
                    return original;
                }
            }
        """.trimIndent()
        assertEquals(
            fakeAnnotations,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(fakeAnnotations)
        )
    }

    @Test
    fun `local target and handler lookalikes fail closed`() {
        val localTarget = """
            import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
            import com.llamalad7.mixinextras.sugar.Local;
            import net.minecraft.world.entity.player.Player;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;

            class BeehiveBlock {}
            @Mixin(BeehiveBlock.class)
            class Example {
                @ModifyExpressionValue(
                    method = "use",
                    at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
                    )
                )
                private static boolean change(
                    boolean original,
                    @Local(argsOnly = true) Player player
                ) {
                    return original;
                }
            }
        """.trimIndent()
        assertEquals(
            localTarget,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(localTarget)
        )

        val localPlayer = """
            import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
            import com.llamalad7.mixinextras.sugar.Local;
            import net.minecraft.world.level.block.BeehiveBlock;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;

            class Player {}
            @Mixin(BeehiveBlock.class)
            class Example {
                @ModifyExpressionValue(
                    method = "use",
                    at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/level/block/CampfireBlock;isSmokeyPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
                    )
                )
                private static boolean change(
                    boolean original,
                    @Local(argsOnly = true) Player player
                ) {
                    return original;
                }
            }
        """.trimIndent()
        assertEquals(
            localPlayer,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(localPlayer)
        )

        val localItemStack = """
            import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
            import net.minecraft.world.inventory.SmithingMenu;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;

            class ItemStack {}
            @Mixin(SmithingMenu.class)
            class Example {
                @ModifyExpressionValue(
                    method = "createResult",
                    at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/item/crafting/SmithingRecipe;assemble(Lnet/minecraft/world/Container;Lnet/minecraft/core/RegistryAccess;)Lnet/minecraft/world/item/ItemStack;"
                    )
                )
                private ItemStack change(ItemStack original) {
                    return original;
                }
            }
        """.trimIndent()
        assertEquals(
            localItemStack,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(localItemStack)
        )
    }

    @Test
    fun `local callback and component lookalikes fail closed`() {
        val localCallback = """
            import com.simibubi.create.content.trains.station.StationMarker;
            import java.util.Map;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;

            class CallbackInfoReturnable<T> {}
            @Mixin(MapItemSavedData.class)
            class Example {
                Map<String, StationMarker> create${'$'}stationMarkers;
                @Inject(
                    method = "load(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;",
                    at = @At("RETURN")
                )
                private static void load(
                    CompoundTag tag,
                    CallbackInfoReturnable<MapItemSavedData> cir
                ) {
                    ListTag list = new ListTag();
                    StationMarker marker = StationMarker.load(list.getCompound(0));
                }
                @Inject(
                    method = "save(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
                    at = @At("RETURN")
                )
                private void save(
                    CompoundTag tag,
                    CallbackInfoReturnable<CompoundTag> cir
                ) {
                    for (StationMarker stationMarker : create${'$'}stationMarkers.values()) {
                        stationMarker.save();
                    }
                }
            }
        """.trimIndent()
        assertEquals(
            localCallback,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(localCallback)
        )

        val localComponent = """
            package com.simibubi.create.content.trains.station;
            import net.minecraft.nbt.CompoundTag;
            class Component {
                static class Serializer {
                    static Component fromJson(String value) { return null; }
                    static String toJson(Component value) { return ""; }
                }
            }
            public class StationMarker {
                public static StationMarker load(CompoundTag tag) {
                    Component.Serializer.fromJson("");
                    return null;
                }
                public CompoundTag save() {
                    Component.Serializer.toJson(null);
                    return null;
                }
            }
        """.trimIndent()
        assertEquals(
            localComponent,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(localComponent)
        )
    }
}
