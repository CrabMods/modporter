package com.modporter.transforms

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JavaProjectTypeIndexOverrideDiscoveryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `late member type dependency completes override discovery without concurrent modification`() {
        val seedSource = """
            package sample;

            class Seed {
                public void bridge(sample.Parent.Payload value) {}
            }
        """.trimIndent()
        val parentSource = """
            package sample;

            interface Parent {
                class Payload {}
            }
        """.trimIndent()
        val candidateSource = """
            package sample;

            class Candidate extends Seed implements Parent {
                @Override
                public void bridge(Payload value) {}
            }
        """.trimIndent()
        val seedFile = write("sample/Seed.java", seedSource)
        val parentFile = write("sample/Parent.java", parentSource)
        val candidateFile = write("sample/Candidate.java", candidateSource)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val seed = method(index.unit(seedFile), "bridge")

        assertEquals(1, index.loadedSourceCount)
        val family = index.exactProjectOverrideFamily(seed)

        assertEquals(listOf("sample.Candidate", "sample.Seed"), family?.map { it.owner })
        assertEquals(3, index.loadedSourceCount)
        assertSourcesUnchanged(
            seedFile to seedSource,
            parentFile to parentSource,
            candidateFile to candidateSource
        )
    }

    @Test
    fun `late commented header contract prevents a partial override family`() {
        val seedSource = """
            package sample;

            class Seed {
                public void bridge(sample.Parent.Payload value) {}
            }
        """.trimIndent()
        val parentSource = """
            package sample;

            interface Parent {
                class Payload {}
                void bridge/*comment*/(Payload value);
            }
        """.trimIndent()
        val candidateSource = """
            package sample;

            class Candidate extends Seed implements Parent {
                @Override
                public void bridge(Payload value) {}
            }
        """.trimIndent()
        val seedFile = write("sample/Seed.java", seedSource)
        val parentFile = write("sample/Parent.java", parentSource)
        val candidateFile = write("sample/Candidate.java", candidateSource)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val seed = method(index.unit(seedFile), "bridge")

        assertEquals(1, index.loadedSourceCount)
        val family = index.exactProjectOverrideFamily(seed)

        assertNull(family)
        assertEquals(3, index.loadedSourceCount)
        assertSourcesUnchanged(
            seedFile to seedSource,
            parentFile to parentSource,
            candidateFile to candidateSource
        )
    }

    private fun method(unit: CompilationUnit, name: String): MethodDeclaration =
        unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == name }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }

    private fun assertSourcesUnchanged(vararg sources: Pair<Path, String>) {
        sources.forEach { (file, source) -> assertEquals(source, file.readText(), file.toString()) }
    }
}
