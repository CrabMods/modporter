package com.modporter.integration

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.MethodDeclaration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * The runtime harnesses hash a classpath directory in the order the supplemental auditor
 * recomputes it: files sorted by their '/'-separated relative name in code point order, which
 * differs from the platform's case-insensitive absolute-path order (`Files.walk(..).sorted()`).
 */
class RuntimeClasspathTreeOrderTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The auditor's definition (strict-closure directory_manifest) for the given terminator. */
    private fun auditorTree(root: Path, terminator: Byte): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { it to root.relativize(it).toString().replace('\\', '/') }
                .sorted(compareBy { it.second })
                .forEach { (file, name) ->
                    digest.update(name.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                    digest.update(sha256(Files.readAllBytes(file)).toByteArray(Charsets.US_ASCII))
                    digest.update(terminator)
                }
        }
        return hex(digest.digest())
    }

    private fun writeTree(root: Path) {
        // 'a/x' sorts before 'a1' by '/' (0x2F) but after it by '\' (0x5C); 'B' before 'b' by code
        // point but equal to it case-insensitively; 'Z' before 'a' by code point, after it folded.
        listOf("a/x.class", "a1.class", "B.class", "b.class", "Z.class", "a/y/z.class").forEachIndexed { index, name ->
            root.resolve(name).also { it.parent.createDirectories() }.writeBytes(byteArrayOf(index.toByte(), 7, 9))
        }
    }

    private fun compiledTree(
        source: String,
        helpers: List<String>,
        root: Path,
        tree: Path
    ): String {
        val unit = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
            .parse(source).result.orElseThrow()
        val methods = unit.findAll(MethodDeclaration::class.java)
            .filter { it.nameAsString in helpers + "sha256Tree" }
            .joinToString("\n") { it.toString() }
        val file = root.resolve("TreeProbe.java")
        file.writeText(
            """
            import java.io.*;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.*;
            import java.security.*;
            import java.util.*;
            public class TreeProbe {
                $methods
                public static String probe(String root) throws Exception {
                    return sha256Tree(Path.of(root));
                }
            }
            """.trimIndent()
        )
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        assertEquals(
            0,
            checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, errors, errors, "-d", classes.toString(), file.toString()),
            errors.toString()
        )
        return URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("TreeProbe").getMethod("probe", String::class.java).invoke(null, tree.toString()) as String
        }
    }

    @Test
    fun `spawn harness hashes classpath directories in the auditor's lf order`(@TempDir root: Path) {
        val tree = root.resolve("tree").createDirectories()
        writeTree(tree)
        val actual = compiledTree(
            LegacyClientSpawnRuntimeHarnessSource.source("example.benchmark", "create"),
            listOf("sha256", "newSha256", "updateDigest", "hex"),
            root.resolve("spawn").createDirectories(),
            tree
        )
        assertEquals(auditorTree(tree, '\n'.code.toByte()), actual)
    }

    @Test
    fun `validators recompute classpath directories in the same orders`(@TempDir root: Path) {
        val tree = root.resolve("tree").createDirectories()
        writeTree(tree)
        assertEquals(auditorTree(tree, '\n'.code.toByte()), StrictReviewValidationRegistry.sha256RuntimeDirectory(tree))
        assertEquals(auditorTree(tree, 0), CreateStationMapRuntimeValidation.sha256Directory(tree))
    }

    @Test
    fun `station harness hashes classpath directories in the auditor's nul order`(@TempDir root: Path) {
        val tree = root.resolve("tree").createDirectories()
        writeTree(tree)
        val actual = compiledTree(
            CreateStationMapRuntimeHarnessSource.source("example.benchmark", "create"),
            listOf("sha256", "hex"),
            root.resolve("station").createDirectories(),
            tree
        )
        assertEquals(auditorTree(tree, 0), actual)
    }
}
