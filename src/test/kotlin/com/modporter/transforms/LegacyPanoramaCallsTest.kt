package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyPanoramaCalls
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyPanoramaCallsTest {
    @TempDir lateinit var directory: Path

    private fun project(name: String): Path = directory.resolve(name).createDirectories()

    private fun java(root: Path, relative: String, source: String): Path {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
        return file
    }

    private fun metadata(root: Path): Path = java(root, "src/main/resources/META-INF/neoforge.mods.toml", """
        modLoader="javafml"
        loaderVersion="[4,)"
        license="MIT"

        [[mods]]
        modId="example"
        version="1"
        displayName="Example"
    """)

    private fun basicProject(name: String): Triple<Path, Path, Path> {
        val root = project(name)
        java(root, "src/main/java/example/ExampleMod.java", """
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("example") public class ExampleMod {}
        """)
        val source = java(root, "src/main/java/example/Caller.java", """
            package example;
            import net.minecraft.client.renderer.PanoramaRenderer;
            class Caller {
                void draw(PanoramaRenderer panorama, Other other) {
                    panorama.render(3.0F, 0.5F);
                    other.render(2.0F, 0.8F);
                }
            }
            class Child extends PanoramaRenderer {
                public void render(float delta, float fade) { super.render(delta, fade); }
            }
        """)
        return Triple(root, source, metadata(root))
    }

    @Test
    fun `migration publishes one client mixin family and is dry run safe and idempotent`() {
        val (root, sourceFile, metadataFile) = basicProject("migration")
        val originalSource = sourceFile.readText()
        val originalMetadata = metadataFile.readText()
        val generated = root.resolve("src/main/java/com/modporter/generated/example/compat")
        val config = root.resolve("src/main/resources/example.modporter-legacy-panorama.mixins.json")

        val dryRun = StructuralRefactorPass().migrateLegacyPanoramaCalls(root, dryRun = true)
        assertTrue(dryRun.isNotEmpty())
        assertEquals(originalSource, sourceFile.readText())
        assertEquals(originalMetadata, metadataFile.readText())
        assertFalse(generated.exists())
        assertFalse(config.exists())

        val changes = StructuralRefactorPass().migrateLegacyPanoramaCalls(root, dryRun = false)
        assertTrue(changes.isNotEmpty())
        val source = sourceFile.readText()
        assertTrue(source.contains("LegacyPanoramaRenderer.render(panorama, 3.0F, 0.5F)"), source)
        assertTrue(source.contains("LegacyPanoramaRenderer.renderBase(this, delta, fade)"), source)
        assertTrue(source.contains("other.render(2.0F, 0.8F)"), source)

        val helper = generated.resolve("LegacyPanoramaRenderer.java").readText()
        val bridge = generated.resolve("LegacyPanoramaRendererBridge.java").readText()
        val mixin = generated.resolve("mixin/LegacyPanoramaRendererMixin.java").readText()
        assertFalse(helper.contains("java.lang.reflect"), helper)
        assertFalse(helper.contains("getMethod("), helper)
        assertFalse(helper.contains("getDeclaredField("), helper)
        assertTrue(bridge.contains("void render(float partialTick, float fade)"), bridge)
        assertTrue(bridge.contains("modporter\$renderLegacyPanoramaBase"), bridge)
        assertTrue(mixin.contains("@Shadow\n    @Final\n    private Minecraft minecraft;"), mixin)
        assertTrue(mixin.contains("@Shadow\n    @Final\n    private CubeMap cubeMap;"), mixin)
        assertTrue(mixin.contains("private float spin;"), mixin)
        assertTrue(mixin.contains("private float bob;"), mixin)
        assertTrue(mixin.contains("this.cubeMap.render(this.minecraft, 10.0F, -this.spin, fade);"), mixin)

        val mixinConfig = config.readText()
        assertTrue(mixinConfig.contains("\"client\""), mixinConfig)
        assertFalse(mixinConfig.contains("\"mixins\""), mixinConfig)
        assertFalse(mixinConfig.contains("\"server\""), mixinConfig)
        val metadata = metadataFile.readText()
        assertEquals(1, Regex("config=\"example\\.modporter-legacy-panorama\\.mixins\\.json\"").findAll(metadata).count())

        assertTrue(StructuralRefactorPass().migrateLegacyPanoramaCalls(root, dryRun = true).isEmpty())
        assertTrue(StructuralRefactorPass().migrateLegacyPanoramaCalls(root, dryRun = false).isEmpty())
    }

    @Test
    fun `emitted typed bridge compiles and simulated weave preserves legacy virtual dispatch`() {
        val (root, _, _) = basicProject("compiled")
        StructuralRefactorPass().migrateLegacyPanoramaCalls(root, dryRun = false)
        val generated = root.resolve("src/main/java/com/modporter/generated/example/compat")
        val helper = generated.resolve("LegacyPanoramaRenderer.java")
        val bridge = generated.resolve("LegacyPanoramaRendererBridge.java")
        val mixin = generated.resolve("mixin/LegacyPanoramaRendererMixin.java")

        val artifactSources = mutableListOf<Path>()
        artifactSources.addAll(mixinAnnotationStubs(root.resolve("artifact-stubs")))
        artifactSources.add(java(root, "artifact-stubs/net/minecraft/client/Minecraft.java", """
            package net.minecraft.client;
            public class Minecraft {
                public final Options options = new Options();
                public static class Options { public Setting panoramaSpeed() { return new Setting(); } }
                public static class Setting { public Double get() { return 2.0; } }
            }
        """))
        artifactSources.add(java(root, "artifact-stubs/net/minecraft/client/renderer/CubeMap.java", """
            package net.minecraft.client.renderer;
            import net.minecraft.client.Minecraft;
            public class CubeMap {
                public void render(Minecraft client, float pitch, float yaw, float fade) {}
            }
        """))
        artifactSources.add(java(root, "artifact-stubs/net/minecraft/client/renderer/PanoramaRenderer.java", """
            package net.minecraft.client.renderer;
            import net.minecraft.client.Minecraft;
            public class PanoramaRenderer {
                private final Minecraft minecraft = new Minecraft();
                private final CubeMap cubeMap;
                private float spin, bob;
                public PanoramaRenderer(CubeMap cubeMap) { this.cubeMap = cubeMap; }
                public void render(Object graphics, int width, int height, float fade, float partialTick) {}
                private static float wrap(float value, float maximum) {
                    return value > maximum ? value - maximum : value;
                }
            }
        """))
        artifactSources.addAll(listOf(helper, bridge, mixin))
        compileJava(artifactSources, root.resolve("artifact-classes").createDirectories())

        val behaviorSources = mutableListOf<Path>()
        behaviorSources.add(java(root, "behavior/net/minecraft/client/Minecraft.java", """
            package net.minecraft.client;
            public class Minecraft {
                public final Options options = new Options();
                public static class Options { public Setting panoramaSpeed() { return new Setting(); } }
                public static class Setting { public Double get() { return 2.0; } }
            }
        """))
        behaviorSources.add(java(root, "behavior/net/minecraft/client/renderer/CubeMap.java", """
            package net.minecraft.client.renderer;
            import net.minecraft.client.Minecraft;
            public class CubeMap {
                public int calls;
                public float pitch, yaw, fade;
                public void render(Minecraft client, float pitch, float yaw, float fade) {
                    calls++; this.pitch = pitch; this.yaw = yaw; this.fade = fade;
                }
            }
        """))
        behaviorSources.add(java(
            root,
            "behavior/net/minecraft/client/renderer/PanoramaRenderer.java",
            simulatedWovenPanorama(mixin.readText(), "com.modporter.generated.example.compat")
        ))
        behaviorSources.add(java(root, "behavior/example/Probe.java", """
            package example;
            import com.modporter.generated.example.compat.LegacyPanoramaRenderer;
            import net.minecraft.client.renderer.CubeMap;
            import net.minecraft.client.renderer.PanoramaRenderer;
            public class Probe {
                public static class Child extends PanoramaRenderer {
                    public int overrides;
                    public Child(CubeMap cube) { super(cube); }
                    public void render(float delta, float fade) {
                        overrides++;
                        LegacyPanoramaRenderer.renderBase(this, delta, fade);
                    }
                }
                private static void close(float expected, float actual, float tolerance, String message) {
                    if (Math.abs(expected - actual) > tolerance)
                        throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
                }
                public static void check() {
                    CubeMap cube = new CubeMap();
                    PanoramaRenderer panorama = new PanoramaRenderer(cube);
                    LegacyPanoramaRenderer.render(panorama, 3.0F, 0.5F);
                    if (cube.calls != 1 || panorama.nativeOverlays != 0)
                        throw new AssertionError("cube draw or native overlay order changed");
                    close(10.0F, cube.pitch, 0.0F, "pitch");
                    close(-0.6F, cube.yaw, 0.00001F, "yaw");
                    close(0.5F, cube.fade, 0.0F, "fade");
                    close(0.6F, panorama.modporter${'$'}testSpin(), 0.00001F, "spin");
                    close(0.006F, panorama.modporter${'$'}testBob(), 0.00001F, "bob");

                    CubeMap spinCube = new CubeMap();
                    PanoramaRenderer spinWrap = new PanoramaRenderer(spinCube);
                    LegacyPanoramaRenderer.render(spinWrap, 1800.5F, 0.2F);
                    close(0.1F, spinWrap.modporter${'$'}testSpin(), 0.001F, "spin wrap");

                    CubeMap bobCube = new CubeMap();
                    PanoramaRenderer bobWrap = new PanoramaRenderer(bobCube);
                    LegacyPanoramaRenderer.render(bobWrap, 3141.6428F, 0.3F);
                    close(0.0001F, bobWrap.modporter${'$'}testBob(), 0.001F, "bob wrap");

                    CubeMap childCube = new CubeMap();
                    Child child = new Child(childCube);
                    LegacyPanoramaRenderer.render(child, 1.0F, 0.7F);
                    if (child.overrides != 1 || childCube.calls != 1 || child.nativeOverlays != 0)
                        throw new AssertionError("legacy subclass override dispatch changed");
                    LegacyPanoramaRenderer.renderBase(child, 1.0F, 0.8F);
                    if (child.overrides != 1 || childCube.calls != 2 || child.nativeOverlays != 0)
                        throw new AssertionError("base dispatch recursed through the override");
                }
            }
        """))
        behaviorSources.addAll(listOf(helper, bridge))
        val classes = root.resolve("behavior-classes").createDirectories()
        compileJava(behaviorSources, classes)
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("example.Probe").getMethod("check").invoke(null)
        }
    }

    @Test
    fun `foreign generated artifacts refuse the entire panorama family before writes`() {
        val (helperRoot, helperSource, helperMetadata) = basicProject("foreign-helper")
        val foreignHelper = helperRoot.resolve(
            "src/main/java/com/modporter/generated/example/compat/LegacyPanoramaRenderer.java"
        )
        foreignHelper.parent.createDirectories()
        foreignHelper.writeText("package com.modporter.generated.example.compat; public final class LegacyPanoramaRenderer {}\n")
        val helperSourceBefore = helperSource.readText()
        val helperMetadataBefore = helperMetadata.readText()
        assertFailsWith<IllegalArgumentException> {
            StructuralRefactorPass().migrateLegacyPanoramaCalls(helperRoot, dryRun = false)
        }
        assertEquals(helperSourceBefore, helperSource.readText())
        assertEquals(helperMetadataBefore, helperMetadata.readText())
        assertFalse(foreignHelper.parent.resolve("LegacyPanoramaRendererBridge.java").exists())

        val (configRoot, configSource, configMetadata) = basicProject("foreign-config")
        val foreignConfig = configRoot.resolve("src/main/resources/example.modporter-legacy-panorama.mixins.json")
        foreignConfig.parent.createDirectories()
        foreignConfig.writeText("{\"foreign\":true}\n")
        val configSourceBefore = configSource.readText()
        val configMetadataBefore = configMetadata.readText()
        assertFailsWith<IllegalArgumentException> {
            StructuralRefactorPass().migrateLegacyPanoramaCalls(configRoot, dryRun = false)
        }
        assertEquals(configSourceBefore, configSource.readText())
        assertEquals(configMetadataBefore, configMetadata.readText())
        assertFalse(configRoot.resolve("src/main/java/com/modporter/generated/example/compat/LegacyPanoramaRenderer.java").exists())
    }

    private fun mixinAnnotationStubs(root: Path): List<Path> = listOf(
        java(root, "org/spongepowered/asm/mixin/Mixin.java", """
            package org.spongepowered.asm.mixin;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE)
            public @interface Mixin { Class<?>[] value() default {}; }
        """),
        java(root, "org/spongepowered/asm/mixin/Shadow.java", """
            package org.spongepowered.asm.mixin;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface Shadow {}
        """),
        java(root, "org/spongepowered/asm/mixin/Final.java", """
            package org.spongepowered.asm.mixin;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.FIELD)
            public @interface Final {}
        """),
        java(root, "org/spongepowered/asm/mixin/Unique.java", """
            package org.spongepowered.asm.mixin;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface Unique { boolean silent() default false; }
        """)
    )

    private fun simulatedWovenPanorama(mixinSource: String, packageName: String): String {
        var source = mixinSource
            .replace("package $packageName.mixin;", "package net.minecraft.client.renderer;")
            .replace("import net.minecraft.client.renderer.PanoramaRenderer;\n", "")
            .lineSequence()
            .filterNot { it.startsWith("import org.spongepowered.asm.mixin.") }
            .joinToString("\n")
        source = source
            .replace("@Mixin(PanoramaRenderer.class)\n", "")
            .replace(
                "public abstract class LegacyPanoramaRendererMixin",
                "public class PanoramaRenderer"
            )
            .replace(Regex("""(?m)^[ \t]*@(Shadow|Final|Unique)[ \t]*\r?\n"""), "")
        // the emitted mixin imports the bridge, so the woven class keeps the simple name
        val header = "public class PanoramaRenderer implements LegacyPanoramaRendererBridge {"
        val testSurface = """
            $header
                public int nativeOverlays;
                public PanoramaRenderer(CubeMap cubeMap) {
                    this.minecraft = new Minecraft();
                    this.cubeMap = cubeMap;
                }
                public void render(Object graphics, int width, int height, float fade, float partialTick) {
                    nativeOverlays++;
                }
                public float modporter${'$'}testSpin() { return this.spin; }
                public float modporter${'$'}testBob() { return this.bob; }
        """.trimIndent()
        require(source.contains(header)) { source }
        return source.replace(header, testSurface) + "\n"
    }

    private fun compileJava(sources: List<Path>, classes: Path) {
        val diagnostics = ByteArrayOutputStream()
        val arguments = listOf("-d", classes.toString()) + sources.map { it.toString() }
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler().run(
                null,
                diagnostics,
                diagnostics,
                *arguments.toTypedArray()
            ),
            diagnostics.toString()
        )
    }
}
