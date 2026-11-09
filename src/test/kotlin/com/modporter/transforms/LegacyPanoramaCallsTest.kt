package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyPanoramaCallsTest {
    @TempDir lateinit var directory: Path
    private fun java(relative: String, source: String): Path {
        val file = directory.resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }

    @Test
    fun `typed panorama fields and native super calls move without changing unrelated render methods`() {
        java("src/main/java/example/ExampleMod.java", """
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("example") public class ExampleMod {}
        """)
        java("src/main/java/example/Views.java", """
            package example;
            import net.minecraft.client.renderer.PanoramaRenderer;
            class Views { public static PanoramaRenderer PANORAMA; }
        """)
        val file = java("src/main/java/example/Caller.java", """
            package example;
            import net.minecraft.client.renderer.PanoramaRenderer;
            class Caller {
                void draw(PanoramaRenderer panorama, Other other) {
                    panorama.render(3.0F, 0.5F);
                    Views.PANORAMA.render(2.0F, 0.8F);
                    other.render(2.0F, 0.8F);
                }
            }
            class Child extends PanoramaRenderer {
                public void render(float delta, float fade) { super.render(delta, fade); }
            }
        """)
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("LegacyPanoramaRenderer.render(panorama, 3.0F, 0.5F)"), source)
        assertTrue(source.contains("LegacyPanoramaRenderer.render(Views.PANORAMA, 2.0F, 0.8F)"), source)
        assertTrue(source.contains("LegacyPanoramaRenderer.renderBase(this, delta, fade)"), source)
        assertTrue(source.contains("other.render(2.0F, 0.8F)"), source)
    }

    @Test
    fun `generated adapter preserves animation and override dispatch without drawing a native overlay`() {
        val sources = mutableListOf<Path>()
        sources += listOf(java("native/net/minecraft/client/Minecraft.java", """
            package net.minecraft.client;
            public class Minecraft {
                public final Options options = new Options();
                public static class Options { public Setting panoramaSpeed() { return new Setting(); } }
                public static class Setting { public Double get() { return 2.0; } }
            }
        """), java("native/net/minecraft/client/renderer/CubeMap.java", """
            package net.minecraft.client.renderer;
            import net.minecraft.client.Minecraft;
            public class CubeMap {
                public int calls;
                public float pitch, yaw, fade;
                public void render(Minecraft client, float pitch, float yaw, float fade) {
                    calls++; this.pitch = pitch; this.yaw = yaw; this.fade = fade;
                }
            }
        """), java("native/net/minecraft/client/renderer/PanoramaRenderer.java", """
            package net.minecraft.client.renderer;
            import net.minecraft.client.Minecraft;
            public class PanoramaRenderer {
                private final Minecraft minecraft = new Minecraft();
                private final CubeMap cubeMap;
                private float spin, bob;
                public int nativeOverlays;
                public PanoramaRenderer(CubeMap cube) { cubeMap = cube; }
                public void render(Object graphics, int width, int height, float fade, float delta) { nativeOverlays++; }
            }
        """))
        val template = javaClass.getResource("/compat/LegacyPanoramaRenderer.java.template")!!.readText().replace("@PACKAGE@", "example")
        sources.add(java("native/example/LegacyPanoramaRenderer.java", template))
        sources.add(java("native/example/Probe.java", """
            package example;
            import net.minecraft.client.renderer.CubeMap;
            import net.minecraft.client.renderer.PanoramaRenderer;
            public class Probe {
                public static class Child extends PanoramaRenderer {
                    public int overrides;
                    public Child(CubeMap cube) { super(cube); }
                    public void render(float delta, float fade) { overrides++; LegacyPanoramaRenderer.renderBase(this, delta, fade); }
                }
                public static void check() throws Exception {
                    CubeMap cube = new CubeMap();
                    PanoramaRenderer panorama = new PanoramaRenderer(cube);
                    LegacyPanoramaRenderer.render(panorama, 3.0F, 0.5F);
                    if (cube.calls != 1 || Math.abs(cube.yaw + 0.6F) > 0.00001F || cube.pitch != 10.0F || cube.fade != 0.5F || panorama.nativeOverlays != 0)
                        throw new AssertionError("cube draw, fade order or overlay changed");
                    java.lang.reflect.Field spin = PanoramaRenderer.class.getDeclaredField("spin");
                    spin.setAccessible(true); spin.setFloat(panorama, 359.9F);
                    LegacyPanoramaRenderer.render(panorama, 1.0F, 0.2F);
                    if (Math.abs(cube.yaw + 0.1F) > 0.0001F) throw new AssertionError("rotation wrap changed");
                    Child child = new Child(cube);
                    LegacyPanoramaRenderer.render(child, 1.0F, 0.7F);
                    if (child.overrides != 1 || cube.calls != 3 || child.nativeOverlays != 0) throw new AssertionError("legacy override dispatch changed");
                }
            }
        """))
        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val arguments = listOf("-d", classes.toString()) + sources.map { it.toString() }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, *arguments.toTypedArray()), diagnostics.toString())
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader -> loader.loadClass("example.Probe").getMethod("check").invoke(null) }
    }
}
