package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class GuiScrollSignaturesTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `gui scroll keeps its vertical delta and nested suggestion calls and custom methods remain intact`() {
        val file = tempDir.resolve("src/main/java/com/example/Editor.java")
        file.parent.createDirectories()
        file.writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.client.gui.components.CommandSuggestions;
            import net.minecraft.util.Mth;

            public class Editor extends Screen {
                CommandSuggestions suggestions;

                @Override
                public boolean mouseScrolled(double x, double y, double delta) {
                    if (suggestions.mouseScrolled(Mth.clamp(delta, -1.0D, 1.0D))) return true;
                    return super.mouseScrolled(x, y, delta);
                }
            }

            class Custom {
                public boolean mouseScrolled(double a, double b, double c) {
                    return false;
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)
        StructuralRefactorPass().apply(tempDir)

        val result = file.readText()
        assertTrue(result.contains("mouseScrolled(double x, double y, double scrollX, double delta)"), result)
        assertTrue(result.contains("suggestions.mouseScrolled(Mth.clamp(delta, -1.0D, 1.0D))"), result)
        assertTrue(result.contains("super.mouseScrolled(x, y, 0, delta)"), result)
        assertTrue(result.contains("mouseScrolled(double a, double b, double c)"), result)
    }
}
