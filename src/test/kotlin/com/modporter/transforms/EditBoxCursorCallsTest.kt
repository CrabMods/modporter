package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class EditBoxCursorCallsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `cursor movement retains shift selection for edit boxes without touching unrelated calls`() {
        val file = directory.resolve("src/main/java/example/Editor.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.client.gui.components.EditBox;
            class Editor extends EditBox {
                void use(EditBox editor, Other other) {
                    moveCursorToEnd();
                    editor.moveCursorToStart();
                    editor.moveCursorToEnd(false);
                    other.moveCursorToEnd();
                }
            }
            class Other { void moveCursorToEnd() {} }
        """.trimIndent())
        StructuralRefactorPass().apply(directory)
        val result = file.readText()
        assertTrue(result.contains("moveCursorToEnd(net.minecraft.client.gui.screens.Screen.hasShiftDown())"), result)
        assertTrue(result.contains("editor.moveCursorToStart(net.minecraft.client.gui.screens.Screen.hasShiftDown())"), result)
        assertTrue(result.contains("editor.moveCursorToEnd(false)"), result)
        assertTrue(result.contains("other.moveCursorToEnd()"), result)
    }
}
