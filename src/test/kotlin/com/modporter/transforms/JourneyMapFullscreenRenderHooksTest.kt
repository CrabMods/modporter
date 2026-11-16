package com.modporter.transforms

import com.modporter.core.transforms.structural.JourneyMapFullscreenRenderHooks
import com.modporter.core.transforms.structural.StructuralRefactorPass
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

class JourneyMapFullscreenRenderHooksTest {
    @TempDir
    lateinit var directory: Path

    private fun project(mixinBody: String): Triple<Path, Path, Path> {
        val java = directory.resolve("src/main/java/example").createDirectories()
        java.resolve("ExampleMod.java").writeText(
            """
            package example;

            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
            }
            """.trimIndent() + "\n"
        )
        val overlay = java.resolve("map").createDirectories().resolve("MapOverlay.java")
        overlay.writeText(
            """
            package example.map;

            import journeymap.client.api.util.UIState;
            import journeymap.client.ui.fullscreen.Fullscreen;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.GuiGraphics;

            public class MapOverlay {
                public static boolean open() {
                    return Minecraft.getInstance().screen instanceof Fullscreen;
                }

                public static void onRender(GuiGraphics graphics, Fullscreen screen, double x, double z, int mX, int mY, float pt) {
                    UIState state = screen.getUiState();
                    if (state == null)
                        return;
                    graphics.pose().translate(screen.width / 2.0f, screen.height / 2.0f, 0);
                    graphics.pose().translate(-x, -z, 0);
                    drawLabel(graphics, screen, mX, mY);
                }

                private static void drawLabel(GuiGraphics graphics, Fullscreen screen, int mouseX, int mouseY) {
                    graphics.fill(mouseX, mouseY, screen.width, screen.height, 0);
                }
            }
            """.trimIndent() + "\n"
        )
        val mixin = java.resolve("mixin/compat").createDirectories().resolve("FullscreenMapMixin.java")
        mixin.writeText(
            """
            package example.mixin.compat;

            import java.awt.geom.Point2D;

            import org.spongepowered.asm.mixin.Final;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.Shadow;
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.At.Shift;
            import org.spongepowered.asm.mixin.injection.Inject;
            import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

            import example.map.MapOverlay;
            import journeymap.client.render.map.GridRenderer;
            import journeymap.client.ui.fullscreen.Fullscreen;
            import net.minecraft.client.gui.GuiGraphics;

            @Mixin(Fullscreen.class)
            public abstract class FullscreenMapMixin {
            $mixinBody
            }
            """.trimIndent() + "\n"
        )
        val config = directory.resolve("src/main/resources").createDirectories().resolve("example.mixins.json")
        config.writeText(
            """
            {
              "required": true,
              "package": "example.mixin",
              "mixins": [
                "ServerMixin"
              ],
              "client": [
                "ClientMixin",
                "compat.FullscreenMapMixin"
              ]
            }
            """.trimIndent() + "\n"
        )
        return Triple(overlay, mixin, config)
    }

    private val hook = """
        @Final
        @Shadow(remap = false)
        private static GridRenderer gridRenderer;

        @Shadow(remap = false)
        private Boolean isScrolling;

        @Shadow(remap = false)
        protected abstract Point2D.Double getMouseDrag();

        @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At(target = "Ljourneymap/client/ui/fullscreen/Fullscreen;drawMap(Lnet/minecraft/client/gui/GuiGraphics;II)V", value = "INVOKE", shift = Shift.AFTER))
        public void example${'$'}afterMap(GuiGraphics graphics, int mouseX, int mouseY, float pt, CallbackInfo ci) {
            boolean dragging = isScrolling;
            Point2D.Double mouseDrag = getMouseDrag();
            double x = gridRenderer.getCenterBlockX() - (dragging ? mouseDrag.x : 0);
            double z = gridRenderer.getCenterBlockZ() - (dragging ? mouseDrag.y : 0);
            MapOverlay.onRender(graphics, (Fullscreen) (Object) this, x, z, mouseX, mouseY, pt);
        }
    """.trimIndent().prependIndent("    ")

    @Test
    fun `a render hook after drawMap becomes a JourneyMap v2 fullscreen render subscription`() {
        val (overlay, mixin, config) = project(hook)

        val result = JourneyMapFullscreenRenderHooks.migrate(StructuralRefactorPass(), directory, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.toString())
        assertFalse(mixin.exists())
        val configJson = Json.parseToJsonElement(config.readText()).jsonObject
        assertEquals(listOf("ClientMixin"), configJson.getValue("client").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("ServerMixin"), configJson.getValue("mixins").jsonArray.map { it.jsonPrimitive.content })

        val plugin = directory.resolve("src/main/java/example/map/ModPorterJourneyMapPlugin.java").readText()
        assertTrue(plugin.contains("@JourneyMapPlugin(apiVersion = \"2.0.0\")\npublic class ModPorterJourneyMapPlugin implements IClientPlugin {"), plugin)
        assertTrue(plugin.contains("FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(\"example\", ModPorterJourneyMapPlugin::afterFullscreenMap);"), plugin)
        assertTrue(plugin.contains("return \"example\";"), plugin)
        assertTrue(
            plugin.contains(
                "IFullscreen fullscreen = event.getFullscreen();\n" +
                    "        // after Fullscreen.drawMap, as example.mixin.compat.FullscreenMapMixin did on JourneyMap 5\n" +
                    "        example.map.MapOverlay.onRender(event.getGraphics(), fullscreen, fullscreen.getCenterBlockX(true), " +
                    "fullscreen.getCenterBlockZ(true), event.getMouseX(), event.getMouseY(), event.getPartialTicks());"
            ),
            plugin
        )

        val overlayText = overlay.readText()
        assertTrue(overlayText.contains("import journeymap.api.v2.client.fullscreen.IFullscreen;"), overlayText)
        assertTrue(overlayText.contains("public static void onRender(GuiGraphics graphics, IFullscreen screen, double x"), overlayText)
        assertTrue(overlayText.contains("UIState state = screen.getUiState();"), overlayText)
        assertTrue(overlayText.contains("translate(screen.getScreen().width / 2.0f, screen.getScreen().height / 2.0f, 0)"), overlayText)
        assertTrue(overlayText.contains("drawLabel(graphics, screen, mX, mY);"), overlayText)
        assertTrue(overlayText.contains("private static void drawLabel(GuiGraphics graphics, IFullscreen screen, int mouseX, int mouseY)"), overlayText)
        assertTrue(overlayText.contains("graphics.fill(mouseX, mouseY, screen.getScreen().width, screen.getScreen().height, 0);"), overlayText)
        // still used by the screen check, and JourneyMap 6 keeps the class
        assertTrue(overlayText.contains("import journeymap.client.ui.fullscreen.Fullscreen;"), overlayText)
        assertTrue(overlayText.contains("screen instanceof Fullscreen;"), overlayText)

        val snapshot = overlayText to config.readText()
        assertTrue(JourneyMapFullscreenRenderHooks.migrate(StructuralRefactorPass(), directory, dryRun = false).changes.isEmpty())
        assertEquals(snapshot, overlay.readText() to config.readText())
    }

    @Test
    fun `a hook at another point of render is reported and left alone`() {
        val tail = hook.replace(
            """at = @At(target = "Ljourneymap/client/ui/fullscreen/Fullscreen;drawMap(Lnet/minecraft/client/gui/GuiGraphics;II)V", value = "INVOKE", shift = Shift.AFTER)""",
            """at = @At("TAIL")"""
        )
        val (overlay, mixin, config) = project(tail)
        val before = Triple(overlay.readText(), mixin.readText(), config.readText())

        val result = JourneyMapFullscreenRenderHooks.migrate(StructuralRefactorPass(), directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.toString())
        assertEquals(1, result.gates.size, result.gates.toString())
        assertTrue(result.gates.single().contains("example.mixin.compat.FullscreenMapMixin"), result.gates.single())
        assertEquals(before, Triple(overlay.readText(), mixin.readText(), config.readText()))
        assertFalse(directory.resolve("src/main/java/example/map/ModPorterJourneyMapPlugin.java").exists())
    }

    @Test
    fun `a center read without the drag is the undragged center`() {
        val undragged = hook.replace(
            "double x = gridRenderer.getCenterBlockX() - (dragging ? mouseDrag.x : 0);",
            "double x = gridRenderer.getCenterBlockX();"
        )
        project(undragged)

        val result = JourneyMapFullscreenRenderHooks.migrate(StructuralRefactorPass(), directory, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.toString())
        val plugin = directory.resolve("src/main/java/example/map/ModPorterJourneyMapPlugin.java").readText()
        assertTrue(plugin.contains("onRender(event.getGraphics(), fullscreen, fullscreen.getCenterBlockX(false), fullscreen.getCenterBlockZ(true),"), plugin)
    }
}
