package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyCustomMapDecorations
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateFinalMapDecorationSubclassSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class LegacyCustomMapDecorationsTest {
    @TempDir
    lateinit var directory: Path

    private val java get() = directory.resolve("src/main/java/example")

    private fun write(relative: String, text: String): Path =
        java.resolve(relative).also { it.parent.createDirectories() }.apply { writeText(text.trimIndent() + "\n") }

    /** A 1.20.1 project whose markers drew themselves, after the per-file composition. */
    private fun project(rendererMixinExtra: String = ""): Map<String, Path> {
        write(
            "ExampleMod.java",
            """
            package example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
                public ExampleMod(IEventBus modEventBus) {
                    ExampleItems.register(modEventBus);
                }
            }
            """
        )
        val legacyMarkers = """
            package example.map;

            import com.mojang.blaze3d.vertex.PoseStack;
            import net.minecraft.client.renderer.MultiBufferSource;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

            public class Markers {
                public static final MapDecoration.Type TYPE = MapDecoration.Type.RED_MARKER;

                public static class Decoration extends MapDecoration implements SelfRenderedDecoration {
                    public Decoration(byte x, byte y, Component name) {
                        super(TYPE, x, y, (byte) 0, name);
                    }

                    @Override
                    public boolean renderOnFrame() {
                        return true;
                    }

                    @Override
                    public void render(PoseStack poseStack, MultiBufferSource bufferSource, boolean active, int packedLight, MapItemSavedData mapData, int index) {
                        poseStack.pushPose();
                        poseStack.popPose();
                    }

                    @Override
                    public boolean render(int index) {
                        return true;
                    }
                }
            }
        """.trimIndent()
        // the per-file rules turn the 1.20.1 type into its 1.21 holder and compose the record
        val composed = StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(
            legacyMarkers.replace(
                "public static final MapDecoration.Type TYPE = MapDecoration.Type.RED_MARKER;",
                "public static final Holder<MapDecorationType> TYPE = MapDecorationTypes.RED_MARKER;"
            ).replace(
                "import net.minecraft.network.chat.Component;",
                "import net.minecraft.core.Holder;\nimport net.minecraft.network.chat.Component;\n" +
                    "import net.minecraft.world.level.saveddata.maps.MapDecorationType;\n" +
                    "import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;"
            )
        )
        assertTrue(composed.contains("modporter\$fromDecoration"), composed)
        val markers = write("map/Markers.java", composed)
        write(
            "map/SelfRenderedDecoration.java",
            """
            package example.map;

            import com.mojang.blaze3d.vertex.PoseStack;
            import net.minecraft.client.renderer.MultiBufferSource;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

            public interface SelfRenderedDecoration {
                void render(PoseStack poseStack, MultiBufferSource bufferSource, boolean active, int packedLight, MapItemSavedData mapData, int index);
            }
            """
        )
        val renderer = write(
            "mixin/client/MapInstanceMixin.java",
            """
            package example.mixin.client;

            import example.map.SelfRenderedDecoration;
            import com.mojang.blaze3d.vertex.PoseStack;
            import net.minecraft.client.renderer.MultiBufferSource;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.Shadow;
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

            @Mixin(targets = "net.minecraft.client.gui.MapRenderer${'$'}MapInstance")
            public class MapInstanceMixin {
                @Shadow
                private MapItemSavedData data;

                @Inject(method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/maps/MapDecoration;render(I)Z", remap = false))
                private void example${'$'}draw(PoseStack poseStack, MultiBufferSource bufferSource, boolean active, int packedLight, CallbackInfo ci, int index, MapDecoration decoration) {
                    if (decoration instanceof SelfRenderedDecoration renderer) {
                        renderer.render(poseStack, bufferSource, active, packedLight, data, index);
                    }
                }
            $rendererMixinExtra
            }
            """
        )
        val packet = write(
            "mixin/MapPacketMixin.java",
            """
            package example.mixin;

            import example.map.Markers;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

            @Mixin(ClientboundMapItemDataPacket.class)
            public class MapPacketMixin {
                @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
                private void example${'$'}read(FriendlyByteBuf buf, CallbackInfo ci) {
                    MapDecoration decoration = null;
                    if (decoration instanceof Markers.Decoration) {
                        buf.readVarInt();
                    }
                }

                @Inject(method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
                private void example${'$'}write(FriendlyByteBuf buf, CallbackInfo ci) {
                    buf.writeVarInt(0);
                }
            }
            """
        )
        val config = directory.resolve("src/main/resources").createDirectories().resolve("example.mixins.json")
        config.writeText(
            """
            {
              "package": "example.mixin",
              "mixins": [
                "MapPacketMixin",
                "OtherMixin"
              ],
              "client": [
                "client.MapInstanceMixin"
              ]
            }
            """.trimIndent() + "\n"
        )
        return mapOf("markers" to markers, "renderer" to renderer, "packet" to packet, "config" to config)
    }

    @Test
    fun `self rendered decorations get a registered type and a NeoForge renderer`() {
        val files = project()

        val result = LegacyCustomMapDecorations.migrate(StructuralRefactorPass(), directory, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.toString())
        val types = java.resolve("ModPorterMapDecorationTypes.java").readText()
        assertTrue(types.contains("DeferredRegister.create(Registries.MAP_DECORATION_TYPE, \"example\")"), types)
        assertTrue(types.contains("public static final DeferredHolder<MapDecorationType, MapDecorationType> MARKERS_DECORATION ="), types)
        assertTrue(types.contains("TYPES.register(\"markers_decoration\", () -> {"), types)
        assertTrue(types.contains("MapDecorationType legacy = MapDecorationTypes.RED_MARKER.value();"), types)
        assertTrue(
            types.contains("return new MapDecorationType(legacy.assetId(), true, legacy.mapColor(), legacy.explorationMapElement(), legacy.trackCount());"),
            types
        )

        val renderers = java.resolve("ModPorterMapDecorationRenderers.java").readText()
        assertTrue(renderers.contains("@EventBusSubscriber(modid = \"example\", value = Dist.CLIENT)"), renderers)
        assertTrue(renderers.contains("event.register(ModPorterMapDecorationTypes.MARKERS_DECORATION.value(), new MarkersDecorationRenderer());"), renderers)
        assertTrue(renderers.contains("example.map.Markers.Decoration legacy = example.map.Markers.Decoration.modporter\$fromDecoration(decoration);"), renderers)
        assertTrue(renderers.contains("legacy.render(poseStack, bufferSource, inItemFrame, packedLight, mapData, index);"), renderers)
        assertTrue(renderers.contains("return legacy.render(index);"), renderers)

        val markers = files.getValue("markers").readText()
        assertTrue(
            markers.contains("new MapDecoration(example.ModPorterMapDecorationTypes.MARKERS_DECORATION, x, y, (byte) 0, Optional.ofNullable(name))"),
            markers
        )
        val main = java.resolve("ExampleMod.java").readText()
        assertTrue(main.contains("ModPorterMapDecorationTypes.register(modEventBus);"), main)

        assertFalse(files.getValue("renderer").exists())
        assertFalse(files.getValue("packet").exists())
        val config = Json.parseToJsonElement(files.getValue("config").readText()).jsonObject
        assertEquals(listOf("OtherMixin"), config.getValue("mixins").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyList(), config.getValue("client").jsonArray.map { it.jsonPrimitive.content })

        // the decorations now carry their registered type: nothing left to do
        val snapshot = markers to main
        val again = LegacyCustomMapDecorations.migrate(StructuralRefactorPass(), directory, dryRun = false)
        assertTrue(again.changes.isEmpty() && again.gates.isEmpty(), again.toString())
        assertEquals(snapshot, files.getValue("markers").readText() to java.resolve("ExampleMod.java").readText())
    }

    @Test
    fun `a renderer mixin doing more than dispatching the render code is reported and nothing changes`() {
        val files = project(
            rendererMixinExtra = """
                    @Inject(method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V", at = @At("HEAD"))
                    private void example${'$'}head(PoseStack poseStack, MultiBufferSource bufferSource, boolean active, int packedLight, CallbackInfo ci) {
                        poseStack.pushPose();
                    }
            """
        )
        val before = files.mapValues { it.value.readText() }

        val result = LegacyCustomMapDecorations.migrate(StructuralRefactorPass(), directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.toString())
        assertEquals(1, result.gates.size, result.gates.toString())
        assertTrue(result.gates.single().contains("example.mixin.client.MapInstanceMixin"), result.gates.single())
        assertEquals(before, files.mapValues { it.value.readText() })
        assertFalse(java.resolve("ModPorterMapDecorationTypes.java").exists())
    }
}
