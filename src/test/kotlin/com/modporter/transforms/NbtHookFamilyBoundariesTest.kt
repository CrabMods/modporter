package com.modporter.transforms

import com.modporter.core.transforms.structural.NbtHookFamilies
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class NbtHookFamilyBoundariesTest {
    @TempDir lateinit var tempDir: Path

    private fun source(name: String, text: String) = tempDir.resolve("$name.java").also { it.writeText(text.trimIndent()) }

    @Test
    fun `an external common parent does not merge unrelated project hooks`() {
        val files = listOf(
            source("First", """
                public class First extends ExternalBase {
                    public void write(String text) {}
                }
            """),
            source("Second", """
                public class Second extends ExternalBase {
                    public void write(String text) {}
                }
            """)
        )
        val families = NbtHookFamilies.build(files)
        assertEquals(setOf("First"), families.members("First", "write", 1))
    }

    @Test
    fun `all overrides of a hook declared by a project parent remain one family`() {
        val files = listOf(
            source("Base", """
                public class Base {
                    public void write(String text) {}
                }
            """),
            source("First", """
                public class First extends Base {
                    @Override
                    public void write(String text) {}
                }
            """),
            source("Second", """
                public class Second extends Base {
                    @Override
                    public void write(String text) {}
                }
            """)
        )
        val families = NbtHookFamilies.build(files)
        assertEquals(setOf("Base", "First", "Second"), families.members("First", "write", 1))
    }
}
