package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class LegacyPositionValuesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `native position construction relocates while a project type with the same name stays local`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        root.resolve("ExampleMod.java").writeText("""
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("example") public class ExampleMod {}
        """.trimIndent())
        val native = root.resolve("NativePosition.java")
        native.writeText("""
            package example;
            import net.minecraft.core.PositionImpl;
            class NativePosition extends PositionImpl {
                public NativePosition(double x, double y, double z) { super(x, y, z); }
                double first() { return x; }
                Object make() { return new PositionImpl(1, 2, 3); }
            }
        """.trimIndent())
        val local = root.resolve("LocalPosition.java")
        local.writeText("""
            package example;
            class LocalPosition {
                static class PositionImpl {}
                Object make() { return new PositionImpl(); }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        assertTrue(native.readText().contains("extends com.modporter.generated.example.compat.LegacyPositionImpl"), native.readText())
        assertTrue(native.readText().contains("new com.modporter.generated.example.compat.LegacyPositionImpl(1, 2, 3)"), native.readText())
        assertTrue(local.readText().contains("new PositionImpl()"), local.readText())
    }
}
