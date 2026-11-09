package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EarlyLegacyCompatibilityGateTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a runtime request without its sealed inventory stops before structural writes`() {
        val source = directory.resolve("src/main/java/example/Fixture.java")
        source.parent.createDirectories()
        val original = """
            package example;
            import net.minecraftforge.api.distmarker.Dist;
            import net.minecraftforge.fml.DistExecutor;
            class Fixture {
                void initialize() {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> System.out.println("client"));
                }
            }
        """.trimIndent()
        source.writeText(original)
        directory.resolve(".modporter/legacy-plant-compat.required").also {
            it.parent.createDirectories()
            it.writeText("unverified request")
        }

        val result = StructuralRefactorPass().apply(directory)

        assertEquals(original, source.readText())
        assertTrue(result.changes.isEmpty())
        assertTrue(result.errors.single().contains("No sealed legacy migration artifact inventory"))
    }
}
