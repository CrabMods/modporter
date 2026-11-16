package com.modporter.transforms

import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Edge case tests to ensure robustness against unusual inputs.
 */
class EdgeCaseTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createFile(filename: String, content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve(filename).writeText(content)
        return tempDir
    }

    // ─── Empty and minimal files ───

    @Test
    fun `handles empty Java file without error`() {
        val projectDir = createFile("Empty.java", "")
        val db = MappingDatabase.loadDefault()

        val textResult = TextReplacementPass(db).apply(projectDir)
        val astResult = AstTransformPass(db).apply(projectDir)

        // Should not crash, zero changes
        assertEquals(0, textResult.changeCount)
        assertEquals(0, astResult.changeCount)
        assertTrue(textResult.errors.isEmpty())
    }

    @Test
    fun `handles Java file with only package declaration`() {
        val projectDir = createFile("PackageOnly.java", "package com.example;\n")
        val db = MappingDatabase.loadDefault()

        val result = TextReplacementPass(db).apply(projectDir)
        assertEquals(0, result.changeCount)
    }

    @Test
    fun `handles Java file with only comments`() {
        val projectDir = createFile("CommentOnly.java", """
            // This is a comment file
            /* Block comment */
            /** Javadoc */
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        val textResult = TextReplacementPass(db).apply(projectDir)
        assertEquals(0, textResult.changeCount)
    }

    // ─── Malformed / unparseable Java ───

    @Test
    fun `AST pass handles malformed Java gracefully`() {
        val projectDir = createFile("Broken.java", """
            package com.example;
            public class Broken {
                public void oops( {  // syntax error
                    new ResourceLocation("test", "broken");
                }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        // AST should fail to parse but not crash
        val result = AstTransformPass(db).apply(projectDir)
        // Should either have 0 changes or errors, but NOT throw
        assertTrue(result.errors.isEmpty() || result.changeCount == 0,
            "Malformed Java should be handled gracefully")
    }

    @Test
    fun `text pass still works on malformed Java`() {
        val projectDir = createFile("BrokenForge.java", """
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            public class Broken {{{{
                MinecraftForge.EVENT_BUS  // broken but text replacement should still work
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        val result = TextReplacementPass(db).apply(projectDir)
        assertTrue(result.changeCount > 0, "Text replacement should work even on malformed Java")

        val content = tempDir.resolve("src/main/java/com/example/BrokenForge.java").readText()
        assertTrue(content.contains("NeoForge.EVENT_BUS"))
    }

    // ─── Nested classes and inner constructs ───

    @Test
    fun `handles nested class with ResourceLocation`() {
        val projectDir = createFile("Nested.java", """
            package com.example;
            import net.minecraft.resources.ResourceLocation;
            public class Outer {
                public static class Inner {
                    public static class DeepInner {
                        ResourceLocation id = new ResourceLocation("mod", "deep");
                    }
                }
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        val result = AstTransformPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/Nested.java").readText()

        assertTrue(content.contains("ResourceLocation.fromNamespaceAndPath"))
        assertTrue(result.changes.any { it.ruleId == "ast-rl-two-arg" })
    }

    @Test
    fun `handles anonymous class with Forge references`() {
        val projectDir = createFile("Anon.java", """
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            public class Anon {
                Runnable r = new Runnable() {
                    public void run() {
                        MinecraftForge.EVENT_BUS.register(this);
                    }
                };
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        val result = TextReplacementPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/Anon.java").readText()

        assertTrue(content.contains("NeoForge.EVENT_BUS"))
    }

    @Test
    fun `handles lambda expressions with Forge references`() {
        val projectDir = createFile("Lambda.java", """
            package com.example;
            import net.minecraft.resources.ResourceLocation;
            import java.util.function.Supplier;
            public class Lambda {
                Supplier<ResourceLocation> supplier = () -> new ResourceLocation("mod", "item");
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        val result = AstTransformPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/Lambda.java").readText()

        assertTrue(content.contains("ResourceLocation.fromNamespaceAndPath"))
    }

    // ─── String content protection ───

    @Test
    fun `does not corrupt string literals containing Forge names`() {
        val original = """
            package com.example;
            public class Strings {
                String desc = "This mod uses MinecraftForge API";
                String url = "https://files.minecraftforge.net/something";
                String docs = ""${'"'}
                    import net.minecraftforge.common.MinecraftForge;
                    ForgeHooks.onLivingJump(entity);
                ""${'"'};
                String guiSample = "event.registerAbove(VanillaGuiLayers.HOTBAR, \"cooldown_hud\", new CooldownHudOverlay())";
                String lootSample = "CopyNbtFunction.copyData(ContextNbtProvider.BLOCK_ENTITY).copy(\"SkullOwner\", \"SkullOwner\")";
                // MinecraftForge and net.minecraftforge in documentation must stay untouched.
            }
        """.trimIndent()
        val projectDir = createFile("Strings.java", original)
        val db = MappingDatabase.loadDefault()

        val result = TextReplacementPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/Strings.java").readText()

        assertEquals(original, content, "Text replacement must not rewrite comments, strings, or text blocks")
        assertEquals(0, result.changeCount, "No executable Forge reference exists in this source")
    }

    // ─── Multiple Forge patterns in single line ───

    @Test
    fun `handles multiple Forge references on same line`() {
        val projectDir = createFile("MultiRef.java", """
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            public class MultiRef {
                Object a = ForgeHooks.class; Object b = ForgeHooksClient.class;
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        TextReplacementPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/MultiRef.java").readText()

        assertTrue(content.contains("CommonHooks"))
        assertTrue(content.contains("ClientHooks"))
        // Ensure ForgeHooksClient wasn't partially replaced
        assertTrue(!content.contains("CommonHooksClient"), "ForgeHooksClient should not become CommonHooksClient")
    }

    // ─── No false positives ───

    @Test
    fun `does not modify file without any Forge references`() {
        val original = """
            package com.example;
            import net.minecraft.world.item.Item;
            public class PureVanilla {
                Item item = new Item(new Item.Properties());
            }
        """.trimIndent()
        val projectDir = createFile("PureVanilla.java", original)
        val db = MappingDatabase.loadDefault()

        TextReplacementPass(db).apply(projectDir)
        AstTransformPass(db).apply(projectDir)

        val content = tempDir.resolve("src/main/java/com/example/PureVanilla.java").readText()
        assertEquals(original, content, "Files without Forge references should not be modified")
    }

    @Test
    fun `does not modify already-migrated NeoForge code`() {
        val original = """
            package com.example;
            import net.neoforged.neoforge.common.NeoForge;
            import net.neoforged.bus.api.IEventBus;
            public class AlreadyMigrated {
                void init(IEventBus bus) {
                    NeoForge.EVENT_BUS.register(this);
                }
            }
        """.trimIndent()
        val projectDir = createFile("AlreadyMigrated.java", original)
        val db = MappingDatabase.loadDefault()

        val result = TextReplacementPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/AlreadyMigrated.java").readText()

        assertEquals(original, content, "Already-migrated code should not be modified")
        assertEquals(0, result.changeCount, "Should have zero changes for already-migrated code")
    }

    // ─── Unicode and special characters ───

    @Test
    fun `handles files with unicode comments and strings`() {
        val projectDir = createFile("Unicode.java", """
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            // 这是一个中文注释 — Forge mod
            public class Unicode {
                String name = "我的模组 - Forge版";
                void init() { MinecraftForge.EVENT_BUS.register(this); }
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        val result = TextReplacementPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/Unicode.java").readText()

        assertTrue(content.contains("NeoForge.EVENT_BUS"))
        assertTrue(content.contains("这是一个中文注释"), "Unicode comments should be preserved")
        assertTrue(content.contains("我的模组"), "Unicode strings should be preserved")
    }

    // ─── Large file handling ───

    @Test
    fun `handles file with many import lines`() {
        val imports = (1..100).joinToString("\n") {
            "import net.minecraftforge.common.extensions.IForgeItem; // line $it"
        }
        val projectDir = createFile("ManyImports.java", """
            package com.example;
            $imports
            public class ManyImports {}
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        val result = TextReplacementPass(db).apply(projectDir)
        assertTrue(result.changeCount >= 100, "Should handle files with many imports")

        val content = tempDir.resolve("src/main/java/com/example/ManyImports.java").readText()
        assertTrue(!content.contains("net.minecraftforge"))
    }
}
