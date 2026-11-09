package com.modporter.integration

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.MethodDeclaration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Compile and execute the exact methods emitted into the native client harness. */
class LegacySpawnClassResourceOriginTest {
    private val resource = "com/example/PackageEntity.class"
    private val bytes = byteArrayOf(0x43, 0x41, 0x46, 0x45)

    @Test
    fun `immutable evidence preparation is registered on client setup without changing tick fences`() {
        val source = LegacyClientSpawnRuntimeHarnessSource.source("example.benchmark", "create")
        val unit = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
            .parse(source).result.orElseThrow()
        val preparation = unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration::class.java)
            .single { it.nameAsString == "ClientPreparation" }
        val annotation = preparation.annotations.single { it.nameAsString == "EventBusSubscriber" }.toString()
        assertTrue(annotation.contains("Dist.CLIENT") && annotation.contains("EventBusSubscriber.Bus.MOD"), annotation)
        val setup = preparation.methods.single { it.nameAsString == "onClientSetup" }
        assertEquals("FMLClientSetupEvent", setup.parameters.single().typeAsString)
        assertTrue(setup.toString().contains("initializeLocked()"))
        assertTrue(source.contains("READY_FENCE_TICKS = 40;"))
        assertTrue(source.contains("STAGE_TIMEOUT_TICKS = 400;"))
        assertTrue(source.contains("GLOBAL_TIMEOUT_TICKS = 2400;"))
    }

    @Test
    fun `native first tick fence accepts only fresh join then one wrapper increment before baseTick`(@TempDir root: Path) {
        compiledProbe(root) { type ->
            val check = type.getMethod("tick", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            // Observed fresh461 and the pinned 21.1.230 ClientLevel call order.
            assertEquals(true, check.invoke(null, 0, 1, true))
            for ((join, pre, first) in listOf(
                Triple(0, 0, true), Triple(0, 2, true), Triple(0, 1, false),
                Triple(1, 2, true), Triple(-1, 0, true), Triple(0, -1, true)
            )) assertEquals(false, check.invoke(null, join, pre, first), "join=$join pre=$pre first=$first")
        }
    }

    @Test
    fun `union URL with encoded id binds actual bytes without treating the URL as a disk path`(@TempDir root: Path) {
        val classes = directory(root.resolve("classes + # % Ω"))
        val url = URL(null, "union:${classes.toUri().rawPath}%23191!/$resource", object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = error("Origin binding must not reopen an opaque URL")
        })
        probe(root) { check -> assertEquals(classes.toRealPath(), check(url, bytes, listOf(classes))) }
    }

    @Test
    fun `ordinary file resources still bind their exact classpath directory`(@TempDir root: Path) {
        val classes = directory(root.resolve("classes"))
        probe(root) { check ->
            assertEquals(classes.toRealPath(), check(classes.resolve(resource).toUri().toURL(), bytes, listOf(classes)))
        }
    }

    @Test
    fun `jar resources with escaped path characters bind the exact archive`(@TempDir root: Path) {
        val jar = jar(root.resolve("classes + # % Ω.jar"))
        probe(root) { check ->
            assertEquals(jar.toRealPath(), check(URL("jar:${jar.toUri()}!/$resource"), bytes, listOf(jar)))
        }
    }

    @Test
    fun `duplicate class resources are rejected even when their bytes agree`(@TempDir root: Path) {
        val classes = directory(root.resolve("classes"))
        val jar = jar(root.resolve("duplicate.jar"))
        probe(root) { check ->
            val failure = assertFailsWith<InvocationTargetException> {
                check(classes.resolve(resource).toUri().toURL(), bytes, listOf(classes, jar))
            }
            assertTrue(failure.targetException is IllegalStateException)
        }
    }

    @Test
    fun `unique origin with different loaded bytes is rejected`(@TempDir root: Path) {
        val classes = directory(root.resolve("classes"))
        probe(root) { check ->
            val failure = assertFailsWith<InvocationTargetException> {
                check(classes.resolve(resource).toUri().toURL(), byteArrayOf(1, 2, 3), listOf(classes))
            }
            assertTrue(failure.targetException is IllegalStateException)
        }
    }

    @Test
    fun `resource absent from bound roots is rejected`(@TempDir root: Path) {
        val classes = directory(root.resolve("classes"))
        val empty = root.resolve("unrelated").createDirectories()
        probe(root) { check ->
            val failure = assertFailsWith<InvocationTargetException> {
                check(classes.resolve(resource).toUri().toURL(), bytes, listOf(empty))
            }
            assertTrue(failure.targetException is IllegalStateException)
        }
    }

    private fun directory(path: Path): Path {
        path.resolve(resource).parent.createDirectories()
        path.resolve(resource).writeBytes(bytes)
        return path
    }

    private fun jar(path: Path): Path {
        JarOutputStream(Files.newOutputStream(path)).use { out ->
            out.putNextEntry(JarEntry(resource))
            out.write(bytes)
            out.closeEntry()
        }
        return path
    }

    private fun probe(root: Path, run: ((URL, ByteArray, List<Path>) -> Path) -> Unit) {
        compiledProbe(root) { type ->
            val method = type.getMethod("check", URL::class.java, String::class.java, ByteArray::class.java, List::class.java)
            run { url, loaded, roots -> method.invoke(null, url, resource, loaded, roots) as Path }
        }
    }

    private fun compiledProbe(root: Path, run: (Class<*>) -> Unit) {
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val unit = parser.parse(LegacyClientSpawnRuntimeHarnessSource.source("example.benchmark", "create"))
            .result.orElseThrow()
        val names = listOf("resolveClassResourceOrigin", "readClasspathResource", "firstPackageTick")
        val methods = names.joinToString("\n") { name ->
            unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == name }.toString()
        }
        val source = root.resolve("ResourceOriginProbe.java")
        source.writeText("""
            import java.io.*;
            import java.net.*;
            import java.nio.file.*;
            import java.util.*;
            import java.util.zip.*;
            public class ResourceOriginProbe {
                $methods
                public static Path check(URL url, String resource, byte[] bytes, List<Path> roots) throws IOException {
                    return resolveClassResourceOrigin(url, resource, bytes, roots);
                }
                public static boolean tick(int join, int pre, boolean first) {
                    return firstPackageTick(join, pre, first);
                }
            }
        """.trimIndent())
        val output = root.resolve("compiled").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, "-d", output.toString(), source.toString()), diagnostics.toString())
        URLClassLoader(arrayOf(output.toUri().toURL()), null).use { loader ->
            run(loader.loadClass("ResourceOriginProbe"))
        }
    }
}
