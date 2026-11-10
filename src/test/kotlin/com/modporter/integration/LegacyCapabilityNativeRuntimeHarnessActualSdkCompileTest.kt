package com.modporter.integration

import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.LegacyBlockEntityCapabilityQueries
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class LegacyCapabilityNativeRuntimeHarnessActualSdkCompileTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `rendered native capability harness and generated sources compile against actual NeoForge SDK`() {
        val fixture = sdkFixture()
        val sdk = resolveSdk(fixture)
        val mixinExtras = extractMixinExtras(sdk.mergedJar)

        val compatPackage = "com.modporter.generated.create.compat"
        val harnessPackage = "com.modporter.generated.create.validation"
        val sourceRoot = directory.resolve("src/main/java")
        val compatRoot = sourceRoot.resolve(compatPackage.replace('.', '/')).createDirectories()
        compatRoot.resolve("LazyOptional.java").writeText(
            BuildSystemPass.legacyLazyOptionalShim(compatPackage)
        )
        compatRoot.resolve("Capability.java").writeText(
            BuildSystemPass.legacyCapabilityShim(compatPackage)
        )
        writeMigrationProject(compatPackage)

        val syntheticCreateSource = writeJava(
            "com/simibubi/create/content/schematics/cannon/SchematicannonBlockEntity.java",
            reviewedSchematicannonSource(compatPackage)
        )
        val migration = LegacyBlockEntityCapabilityQueries.migrate(
            directory,
            { compatPackage },
            dryRun = false
        )
        assertTrue(migration.gates.isEmpty(), migration.gates.joinToString("\n"))
        assertTrue(
            syntheticCreateSource.readText().contains(
                "LegacyBlockEntityCapabilityQueries.getRetained("
            ),
            syntheticCreateSource.readText()
        )

        syntheticCreateSource.deleteIfExists()
        assertFalse(syntheticCreateSource.exists(), "synthetic Create fixture must not enter javac")
        writeJava(
            harnessPackage.replace('.', '/') + "/" +
                LegacyCapabilityNativeRuntimeHarnessSource.FILE_NAME,
            LegacyCapabilityNativeRuntimeHarnessSource.source(harnessPackage, compatPackage)
        )

        val sources = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.fileName.toString().endsWith(".java") }
                .sorted()
                .toList()
        }
        val relativeSources = sources.map { sourceRoot.relativize(it).toString().replace('\\', '/') }
        val compatPath = compatPackage.replace('.', '/')
        val harnessPath = harnessPackage.replace('.', '/')
        assertEquals(
            setOf(
                "$compatPath/Capability.java",
                "$compatPath/LazyOptional.java",
                "$compatPath/LegacyBlockEntityCapabilityQueries.java",
                "$compatPath/LegacyCapabilities.java",
                "$compatPath/LegacyCapabilityProvider.java",
                "$compatPath/mixin/LegacyCapabilityLevelExtensionMixin.java",
                "$compatPath/mixin/LegacyCapabilityServerLevelMixin.java",
                "$harnessPath/${LegacyCapabilityNativeRuntimeHarnessSource.FILE_NAME}"
            ),
            relativeSources.toSet(),
            relativeSources.joinToString("\n")
        )

        val classes = directory.resolve("classes").createDirectories()
        // Path is Iterable<Path>; add the JARs as elements, not path components.
        val classpathEntries = (sdk.classpath + listOf(sdk.mergedJar, mixinExtras)).distinct()
        assertTrue(sdk.mergedJar in classpathEntries, "Merged SDK JAR is absent from the compiler classpath")
        assertTrue(mixinExtras in classpathEntries, "MixinExtras JAR is absent from the compiler classpath")
        classpathEntries.forEach { entry ->
            assertTrue(entry.isRegularFile(), "Compiler classpath entry is not a regular JAR: $entry")
        }
        val classpath = classpathEntries.joinToString(File.pathSeparator)
        val arguments = buildList {
            addAll(
                listOf(
                    "--release", "21",
                    "-proc:none",
                    "-encoding", "UTF-8",
                    "-classpath", classpath,
                    "-d", classes.toString()
                )
            )
            addAll(sources.map(Path::toString))
        }
        val compilation = compileWithJava21(arguments)
        assertEquals(0, compilation.exitCode, compilation.diagnostics)
    }

    private fun sdkFixture(): Path {
        val property = System.getProperty(SDK_CLASSPATH_PROPERTY)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val environment = System.getenv(SDK_CLASSPATH_ENVIRONMENT)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (property != null && environment != null) {
            val propertyPath = Path.of(property).toAbsolutePath().normalize()
            val environmentPath = Path.of(environment).toAbsolutePath().normalize()
            assertEquals(
                propertyPath,
                environmentPath,
                "$SDK_CLASSPATH_PROPERTY and $SDK_CLASSPATH_ENVIRONMENT select different SDK fixtures"
            )
        }

        val explicit = property ?: environment
        val fixture = runCatching {
            Path.of(explicit ?: DEFAULT_SDK_CLASSPATH).toAbsolutePath().normalize()
        }.getOrElse { failure ->
            fail("Invalid selected actual NeoForge SDK classpath path: ${failure.message}")
        }
        if (!fixture.isRegularFile()) {
            if (explicit == null) {
                assumeTrue(
                    false,
                    "Optional actual NeoForge SDK fixture is absent: $fixture"
                )
                error("unreachable")
            }
            fail("Selected actual NeoForge SDK classpath is not a regular file: $fixture")
        }
        return fixture.toRealPath()
    }

    private fun resolveSdk(fixture: Path): Sdk {
        val rawEntries = Files.readAllLines(fixture, StandardCharsets.UTF_8)
            .map(String::trim)
            .filter(String::isNotEmpty)
        assertTrue(rawEntries.isNotEmpty(), "Actual NeoForge SDK classpath is empty: $fixture")

        val classpath = rawEntries.map { raw ->
            val path = Path.of(raw)
            val resolved = if (path.isAbsolute) path else fixture.parent.resolve(path)
            val normalized = resolved.toAbsolutePath().normalize()
            assertTrue(normalized.isRegularFile(), "SDK classpath entry is missing: $normalized")
            normalized.toRealPath()
        }
        assertEquals(
            classpath.size,
            classpath.distinct().size,
            "Actual NeoForge SDK classpath contains duplicate entries"
        )

        val clientResources = classpath.singleOrNull {
            it.fileName.toString() == CLIENT_RESOURCES_JAR
        } ?: fail("SDK classpath must contain exactly one $CLIENT_RESOURCES_JAR entry")
        val mixin = classpath.singleOrNull {
            it.fileName.toString() == MIXIN_JAR
        } ?: fail("SDK classpath must contain exactly one $MIXIN_JAR entry")
        assertTrue(mixin.isRegularFile(), "Mixin SDK entry is missing: $mixin")

        val mergedJar = clientResources.parent.resolve(MERGED_SDK_JAR).toAbsolutePath().normalize()
        assertTrue(
            mergedJar.isRegularFile(),
            "Pinned actual NeoForge SDK merged jar is missing beside the classpath fixture: $mergedJar"
        )
        return Sdk(classpath, mergedJar.toRealPath())
    }

    private fun extractMixinExtras(mergedJar: Path): Path {
        val output = directory.resolve(MIXIN_EXTRAS_JAR.substringAfterLast('/'))
        JarFile(mergedJar.toFile()).use { jar ->
            val entry = jar.getJarEntry(MIXIN_EXTRAS_JAR)
                ?: fail("Pinned NeoForge SDK is missing nested $MIXIN_EXTRAS_JAR")
            assertTrue(entry.size != 0L, "Nested MixinExtras SDK jar is empty")
            jar.getInputStream(entry).use { input -> Files.copy(input, output) }
        }
        assertTrue(output.isRegularFile(), "MixinExtras extraction did not produce $output")
        return output
    }

    private fun compileWithJava21(arguments: List<String>): Compilation {
        if (Runtime.version().feature() == 21) {
            val diagnostics = ByteArrayOutputStream()
            val compiler = ToolProvider.getSystemJavaCompiler()
                ?: fail("Java 21 ToolProvider did not expose a system compiler")
            val exitCode = compiler.run(
                null,
                diagnostics,
                diagnostics,
                *arguments.toTypedArray()
            )
            return Compilation(
                exitCode,
                diagnostics.toString(StandardCharsets.UTF_8)
            )
        }

        val javac = java21Javac()
        val diagnostics = directory.resolve("javac-21.log")
        val argumentFile = directory.resolve("javac-21.args")
        Files.writeString(
            argumentFile,
            arguments.joinToString("\n", postfix = "\n", transform = ::javacArgument),
            StandardCharsets.UTF_8
        )
        val process = ProcessBuilder(
            javac.toString(),
            "-J-Dfile.encoding=UTF-8",
            "-J-Dstdout.encoding=UTF-8",
            "-J-Dstderr.encoding=UTF-8",
            "@$argumentFile"
        )
            .redirectErrorStream(true)
            .redirectOutput(diagnostics.toFile())
            .start()
        if (!process.waitFor(JAVAC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("Java 21 javac did not finish within $JAVAC_TIMEOUT_SECONDS seconds")
        }
        return Compilation(
            process.exitValue(),
            String(Files.readAllBytes(diagnostics), StandardCharsets.UTF_8)
        )
    }

    private fun javacArgument(argument: String): String {
        require('\n' !in argument && '\r' !in argument) {
            "javac argument contains a line break"
        }
        return "\"" + argument
            .replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\""
    }

    private fun java21Javac(): Path {
        val executable = if (System.getProperty("os.name").lowercase().contains("win")) {
            "javac.exe"
        } else {
            "javac"
        }
        val direct = System.getenv("MODPORTER_BENCHMARK_JAVA21")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?.let { selected ->
                if (selected.fileName.toString().equals(executable, ignoreCase = true)) {
                    selected
                } else {
                    selected.parent?.resolve(executable)
                }
            }
        val homeCandidates = listOf(
            "MODPORTER_BENCHMARK_JAVA21_HOME",
            "JAVA21_HOME",
            "JDK21_HOME"
        ).mapNotNull { name ->
            System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)
        }.map { home ->
            Path.of(home).toAbsolutePath().normalize().resolve("bin").resolve(executable)
        }
        val discovered = listOf(
            Path.of("C:/Program Files/Java"),
            Path.of("C:/Program Files/Eclipse Adoptium"),
            Path.of(System.getProperty("user.home"), ".jdks"),
            Path.of("/usr/lib/jvm")
        ).asSequence()
            .filter(Files::isDirectory)
            .flatMap { root ->
                Files.list(root).use { children ->
                    children.filter(Files::isDirectory)
                        .filter { child -> child.fileName.toString().contains("21") }
                        .map { child -> child.resolve("bin").resolve(executable) }
                        .toList()
                        .asSequence()
                }
            }
        return sequenceOf(direct)
            .filterNotNull()
            .plus(homeCandidates)
            .plus(discovered)
            .firstOrNull(Files::isRegularFile)
            ?.toRealPath()
            ?: fail(
                "Actual NeoForge SDK compilation requires JDK 21. Set " +
                    "MODPORTER_BENCHMARK_JAVA21 or MODPORTER_BENCHMARK_JAVA21_HOME."
            )
    }

    private fun writeMigrationProject(compatPackage: String) {
        directory.resolve("build.gradle").writeText(
            """
            plugins {
                id("net.neoforged.moddev.legacyforge") version "2.0.107"
            }
            legacyForge {
                version = "1.20.1-47.4.0"
            }
            """.trimIndent()
        )
        directory.resolve("src/main/resources/META-INF/neoforge.mods.toml").apply {
            parent.createDirectories()
            writeText(
                "modLoader=\"javafml\"\n" +
                    "loaderVersion=\"[1,)\"\n" +
                    "license=\"MIT\"\n\n" +
                    "[[mods]]\n" +
                    "modId=\"create\"\n" +
                    "version=\"test\"\n" +
                    "displayName=\"Create\"\n"
            )
        }
        val compatRoot = directory.resolve("src/main/java")
            .resolve(compatPackage.replace('.', '/'))
        assertTrue(compatRoot.exists())
    }

    private fun reviewedSchematicannonSource(compatPackage: String): String = """
        package com.simibubi.create.content.schematics.cannon;

        import $compatPackage.LazyOptional;
        import java.util.LinkedHashSet;
        import net.minecraft.core.BlockPos;
        import net.minecraft.core.Direction;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.level.block.entity.BlockEntity;
        import net.neoforged.neoforge.capabilities.Capabilities;
        import net.neoforged.neoforge.items.IItemHandler;

        public class SchematicannonBlockEntity {
            public LinkedHashSet<LazyOptional<IItemHandler>> attachedInventories = new LinkedHashSet<>();
            private final Level level = new Level();
            private final BlockPos worldPosition = new BlockPos(1);

            public void findInventories() {
                attachedInventories.clear();
                for (Direction facing : Direction.values()) {
                    if (!level.isLoaded(worldPosition.relative(facing)))
                        continue;
                    BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(facing));
                    if (blockEntity != null) {
                        LazyOptional<IItemHandler> capability = blockEntity.getCapability(Capabilities.ItemHandler.BLOCK, facing.getOpposite());
                        if (capability.isPresent()) {
                            attachedInventories.add(capability);
                        }
                    }
                }
            }
        }
    """.trimIndent()

    private fun writeJava(relative: String, source: String): Path {
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }

    private data class Sdk(
        val classpath: List<Path>,
        val mergedJar: Path
    )

    private data class Compilation(
        val exitCode: Int,
        val diagnostics: String
    )

    private companion object {
        const val SDK_CLASSPATH_PROPERTY = "modporter.actualNeoForgeSdkClasspath"
        const val SDK_CLASSPATH_ENVIRONMENT = "MODPORTER_ACTUAL_NEOFORGE_SDK_CLASSPATH"
        const val DEFAULT_SDK_CLASSPATH =
            ".modporter/legacy-plant-native-validation/build/moddev/gameTestServerLegacyClasspath.txt"
        const val CLIENT_RESOURCES_JAR =
            "neoforge-21.1.230-client-extra-aka-minecraft-resources.jar"
        const val MERGED_SDK_JAR = "neoforge-21.1.230-merged.jar"
        const val MIXIN_JAR = "sponge-mixin-0.15.2+mixin.0.8.7.jar"
        const val MIXIN_EXTRAS_JAR = "META-INF/jarjar/mixinextras-neoforge-0.5.3.jar"
        const val JAVAC_TIMEOUT_SECONDS = 120L
    }
}
