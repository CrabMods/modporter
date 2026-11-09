package com.modporter.transforms

import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Forge mod that is one subproject of a multi-project Gradle build. */
class MultiProjectBuildTest {

    @TempDir
    lateinit var tempDir: Path

    private fun write(path: String, text: String) {
        val file = tempDir.resolve(path)
        file.parent.createDirectories()
        file.writeText(text.trimIndent() + "\n")
    }

    private fun multiProjectBuild() {
        write("settings.gradle.kts", """
            rootProject.name = "example"
            include("example-core")
            include("example-forge")
        """)
        write("build.gradle.kts", """
            plugins {
                base
            }
        """)
        write("buildSrc/build.gradle.kts", """
            plugins {
                `kotlin-dsl`
            }

            repositories {
                mavenCentral()
                gradlePluginPortal()
            }

            dependencies {
                implementation("com.github.johnrengelman:shadow:8.1.1")
                implementation("net.minecraftforge.gradle:ForgeGradle:6.0.11")
                implementation("org.spongepowered:mixingradle:0.7.38")
            }
        """)
        write("buildSrc/src/main/kotlin/CommonConfig.kt", """
            import org.gradle.api.Project
            import org.gradle.jvm.toolchain.JavaLanguageVersion

            fun Project.applyCommonConfiguration() {
                plugins.withId("java") {
                    the<JavaPluginExtension>().toolchain {
                        languageVersion.set(JavaLanguageVersion.of(17))
                    }
                }
            }
        """)
        write("example-core/build.gradle.kts", """
            plugins {
                `java-library`
            }
        """)
        write("example-core/src/main/java/com/example/core/Core.java", """
            package com.example.core;

            public class Core {
            }
        """)
        write("example-forge/build.gradle.kts", """
            import net.minecraftforge.gradle.common.util.RunConfig
            import net.minecraftforge.gradle.userdev.UserDevExtension
            import net.minecraftforge.gradle.userdev.tasks.RenameJarInPlace

            plugins {
                id("net.minecraftforge.gradle")
                id("org.spongepowered.mixin")
                `java-library`
            }

            applyPlatformConfiguration(javaRelease = 17)

            val minecraftVersion = "1.20.1"
            val forgeVersion = "47.1.3"

            val apiClasspath = configurations.create("apiClasspath") {
                isCanBeResolved = true
                extendsFrom(configurations.api.get())
            }

            repositories {
                val forgeMaven = maven {
                    name = "Forge Maven"
                    url = uri("https://maven.minecraftforge.net/")
                }
                remove(forgeMaven)
                addFirst(forgeMaven)
            }

            dependencies {
                "api"(project(":example-core"))
                "minecraft"("net.minecraftforge:forge:${'$'}minecraftVersion-${'$'}forgeVersion")
                "annotationProcessor"("org.spongepowered:mixin:0.8.5:processor")
            }

            configure<UserDevExtension> {
                mappings("official", minecraftVersion)

                accessTransformer(file("src/main/resources/META-INF/accesstransformer.cfg"))

                runs {
                    val runConfig = Action<RunConfig> {
                        workingDirectory = project.file("run").canonicalPath
                        source(sourceSets["main"])
                        lazyToken("minecraft_classpath") {
                            apiClasspath.resolve().joinToString(File.pathSeparator) { it.absolutePath }
                        }
                    }
                    create("client", runConfig)
                    create("server", runConfig)
                }
            }

            configure<org.spongepowered.asm.gradle.plugins.MixinExtension> {
                add(sourceSets["main"], "example.mixins.refmap.json")
                config("example.mixins.json")
            }

            val javaComponent = components["java"] as AdhocComponentWithVariants
            javaComponent.withVariantsFromConfiguration(configurations["apiElements"]) {
                skip()
            }

            val reobfApiElements = configurations.register("reobfApiElements") {
                isCanBeConsumed = true
                outgoing.artifact(tasks.named("jar")) {
                    builtBy(project.provider { tasks.named("reobfJar") })
                }
            }

            javaComponent.addVariantsFromConfiguration(reobfApiElements.get()) {
                mapToMavenScope("compile")
            }

            tasks.named<Copy>("processResources") {
                val properties = mapOf("version" to project.version, "forgeVersion" to forgeVersion)
                filesMatching("META-INF/mods.toml") {
                    expand(properties)
                }
            }

            val reobf = extensions.getByName<NamedDomainObjectContainer<RenameJarInPlace>>("reobf")
            reobf.create("shadowJar")
        """)
        write("example-forge/src/main/resources/META-INF/mods.toml", """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"
            [[mods]]
            modId="example"
            version="${'$'}{version}"
        """)
        write("example-forge/src/main/java/com/example/forge/ExampleMod.java", """
            package com.example.forge;

            import net.minecraftforge.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "example";
            }
        """)
    }

    @Test
    fun `the forge subproject of a multi-project build is the mod module`() {
        multiProjectBuild()

        assertEquals(tempDir.resolve("example-forge"), GradleModLayout.modModule(tempDir))
        assertTrue(GradleModLayout.isMultiProject(tempDir))
    }

    @Test
    fun `a project with its own sources or forge build is its own mod module`() {
        write("build.gradle", """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """)
        write("helper/build.gradle", """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """)
        write("helper/src/main/java/Helper.java", "public class Helper {}")

        assertEquals(tempDir, GradleModLayout.modModule(tempDir))
    }

    @Test
    fun `two forge subprojects leave the root as the module`() {
        multiProjectBuild()
        write("other-forge/build.gradle.kts", """
            plugins {
                id("net.minecraftforge.gradle")
            }
        """)
        write("other-forge/src/main/java/Other.java", "public class Other {}")

        assertEquals(tempDir, GradleModLayout.modModule(tempDir))
    }

    @Test
    fun `the module script and buildSrc of a multi-project build move to ModDev`() {
        multiProjectBuild()

        val result = BuildSystemPass().apply(tempDir)
        val module = tempDir.resolve("example-forge/build.gradle.kts").readText()
        val buildSrc = tempDir.resolve("buildSrc/build.gradle.kts").readText()
        val convention = tempDir.resolve("buildSrc/src/main/kotlin/CommonConfig.kt").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(module.contains("id(\"net.neoforged.moddev\")"), module)
        assertFalse(module.contains("net.minecraftforge"), module)
        assertFalse(module.contains("org.spongepowered.mixin"), module)
        assertFalse(module.contains("reobf"), module)
        assertFalse(module.contains("UserDevExtension") || module.contains("MixinExtension"), module)
        assertFalse(module.contains("withVariantsFromConfiguration"), module)
        assertFalse(module.contains("apiClasspath"), module)
        // what ForgeGradle's runs had on the game classpath (the core project) ModDev's runs get too
        assertTrue(module.contains("configurations.named(\"additionalRuntimeClasspath\") {"), module)
        assertTrue(module.contains("    extendsFrom(configurations.api.get())"), module)
        assertTrue(module.contains("applyPlatformConfiguration(javaRelease = 21)"), module)
        assertTrue(module.contains("val minecraftVersion = \"1.21.1\""), module)
        assertTrue(module.contains("neoForge {"), module)
        assertTrue(module.contains("accessTransformers.from(file(\"src/main/resources/META-INF/accesstransformer.cfg\"))"), module)
        assertTrue(module.contains("register(\"server\") {"), module)
        assertTrue(module.contains("systemProperty(\"neoforge.enabledGameTestNamespaces\", \"example\")"), module)
        assertTrue(module.contains("register(\"example\") {"), module)
        assertTrue(module.contains("filesMatching(\"META-INF/neoforge.mods.toml\")"), module)
        assertTrue(module.contains("\"api\"(project(\":example-core\"))"), module)
        // every brace the script opened is still closed
        assertEquals(module.count { it == '{' }, module.count { it == '}' }, module)
        assertTrue(buildSrc.contains("implementation(\"net.neoforged:moddev-gradle:"), buildSrc)
        assertFalse(buildSrc.contains("ForgeGradle") || buildSrc.contains("mixingradle"), buildSrc)
        assertTrue(buildSrc.contains("https://maven.neoforged.net/releases"), buildSrc)
        // the convention keeps its toolchain for the other projects; the mod module sets its own
        assertTrue(convention.contains("JavaLanguageVersion.of(17)"), convention)
        assertTrue(module.contains("toolchain.languageVersion.set(JavaLanguageVersion.of(21))"), module)
        // the module is not a standalone build: no settings file of its own
        assertFalse(tempDir.resolve("example-forge/settings.gradle").toFile().exists())
        // the converted module is still the one the sources, resources and runs belong to
        assertEquals(tempDir.resolve("example-forge"), GradleModLayout.modModule(tempDir))
    }

    @Test
    fun `display test registrations are dropped in every form`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import java.lang.reflect.InvocationTargetException;
            import java.util.function.Supplier;
            import net.neoforged.fml.IExtensionPoint;
            import net.neoforged.fml.ModLoadingContext;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.network.NetworkConstants;

            @Mod("example")
            public class ExampleMod {
                public ExampleMod() {
                    try {
                        // stay compatible with older Forge
                        ModLoadingContext.class.getDeclaredMethod("registerExtensionPoint", Class.class, Supplier.class).invoke(ModLoadingContext.get(), IExtensionPoint.DisplayTest.class, (Supplier<?>) () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
                    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        throw new IllegalStateException(e);
                    }
                    ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, () -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true));
                    System.out.println("loaded");
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertFalse(mod.contains("DisplayTest") || mod.contains("registerExtensionPoint"), mod)
        assertFalse(mod.contains("import net.neoforged.fml.IExtensionPoint;"), mod)
        assertFalse(mod.contains("NetworkConstants") || mod.contains("InvocationTargetException") || mod.contains("Supplier"), mod)
        assertTrue(mod.contains("System.out.println(\"loaded\");"), mod)
    }
}
