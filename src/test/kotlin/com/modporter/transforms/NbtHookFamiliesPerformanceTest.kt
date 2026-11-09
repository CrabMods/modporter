package com.modporter.transforms

import com.modporter.core.transforms.structural.NbtHookFamilies
import com.modporter.core.transforms.structural.StaleOverrideAnnotations
import com.modporter.core.transforms.structural.ProviderArityRepair
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.io.path.createDirectories
import kotlin.test.assertTrue
import kotlin.io.path.readText

class NbtHookFamiliesPerformanceTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `provider signature discovery remains bounded and still supplies the proven argument`() {
        val file = tempDir.resolve("src/main/java/Sample.java")
        file.parent.createDirectories()
        file.writeText("""
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            public class Sample {
                static final String DATA =
                    "${"x".repeat(100_000)}";
                Target target;
                public CompoundTag copy(HolderLookup.Provider registries, CompoundTag tag) {
                    return target.load(tag);
                }
            }
            class Target {
                public CompoundTag load(CompoundTag tag, HolderLookup.Provider registries) {
                    return tag;
                }
            }
        """.trimIndent())
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            ProviderArityRepair.migrate(StructuralRefactorPass(), tempDir, false)
            assertTrue(file.readText().contains("target.load(tag, registries)"))
        }
    }

    @Test
    fun `override discovery also skips large masked string literals in linear time`() {
        val file = tempDir.resolve("src/main/java/Sample.java")
        file.parent.createDirectories()
        file.writeText("""
            public class Sample {
                static final String DATA =
                    "${"x".repeat(100_000)}";
                public String name() { return "sample"; }
            }
        """.trimIndent())
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            StaleOverrideAnnotations.migrate(tempDir, false)
        }
    }

    @Test
    fun `large masked literals are not repeatedly parsed as whitespace return types`() {
        val file = tempDir.resolve("Sample.java")
        file.writeText("""
            class Sample {
                static final String DATA =
                    "${"x".repeat(100_000)}";

                public java.util.Map<String, java.util.List<String>> names() {
                    return null;
                }
            }
        """.trimIndent())
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val families = NbtHookFamilies.build(listOf(file))
            assertTrue(families.declares("Sample", "names", 0))
        }
    }
}
