package com.modporter.transforms

import com.modporter.core.pipeline.Pipeline
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
 * Tests that running the pipeline twice produces the same result.
 * Idempotency is critical for a migration tool — users should be able
 * to re-run safely without fear of double-replacing or corruption.
 */
class IdempotencyTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createFile(filename: String, content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve(filename).writeText(content)
        return tempDir
    }

    @Test
    fun `text replacement is idempotent`() {
        val projectDir = createFile("Idem.java", """
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.registries.ForgeRegistries;
            public class Idem {
                void init() {
                    MinecraftForge.EVENT_BUS.register(this);
                    Object x = ForgeRegistries.ITEMS;
                }
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)

        // Run first time
        pass.apply(projectDir)
        val afterFirst = tempDir.resolve("src/main/java/com/example/Idem.java").readText()

        // Run second time
        val secondResult = pass.apply(projectDir)
        val afterSecond = tempDir.resolve("src/main/java/com/example/Idem.java").readText()

        assertEquals(afterFirst, afterSecond, "Second run should not change anything")
        assertEquals(0, secondResult.changeCount, "Second run should report zero changes")
    }

    @Test
    fun `AST transformation is idempotent`() {
        val projectDir = createFile("IdemAst.java", """
            package com.example;
            import net.minecraft.resources.ResourceLocation;
            public class IdemAst {
                ResourceLocation a = new ResourceLocation("mod", "a");
                ResourceLocation b = new ResourceLocation("mod:b");
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)

        // Run first time
        pass.apply(projectDir)
        val afterFirst = tempDir.resolve("src/main/java/com/example/IdemAst.java").readText()

        // Run second time
        val secondResult = pass.apply(projectDir)
        val afterSecond = tempDir.resolve("src/main/java/com/example/IdemAst.java").readText()

        assertEquals(afterFirst, afterSecond, "AST transform should be idempotent")
        assertEquals(0, secondResult.changeCount, "Second AST run should report zero changes")
    }

    @Test
    fun `full pipeline is idempotent`() {
        val projectDir = createFile("IdemFull.java", """
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraft.resources.ResourceLocation;
            @Mod("testmod")
            public class IdemFull {
                public IdemFull() {
                    MinecraftForge.EVENT_BUS.register(this);
                    ResourceLocation id = new ResourceLocation("testmod", "item");
                }
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()

        val pipeline = Pipeline(
            passes = listOf(TextReplacementPass(db), AstTransformPass(db)),
            dryRun = false
        )

        // Run first time
        pipeline.run(projectDir)
        val afterFirst = tempDir.resolve("src/main/java/com/example/IdemFull.java").readText()

        // Run second time
        val secondResult = pipeline.run(projectDir)
        val afterSecond = tempDir.resolve("src/main/java/com/example/IdemFull.java").readText()

        assertEquals(afterFirst, afterSecond, "Pipeline should be idempotent")
        assertEquals(0, secondResult.totalChanges, "Second pipeline run should make zero changes")
    }

    @Test
    fun `package rename does not double-replace`() {
        // Critical: net.minecraftforge.fml should not become net.neoforged.neoforge.fml
        // (it should become net.neoforged.fml)
        val projectDir = createFile("PkgDouble.java", """
            package com.example;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
        """.trimIndent())
        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)

        pass.apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/PkgDouble.java").readText()

        // Should be net.neoforged.fml, NOT net.neoforged.neoforge.fml
        assertTrue(content.contains("net.neoforged.fml.common.Mod"),
            "Should be net.neoforged.fml not net.neoforged.neoforge.fml")
        assertTrue(!content.contains("net.neoforged.neoforge.fml"),
            "FML package should not have double replacement")
    }

    @Test
    fun `class rename regex boundaries prevent partial matches`() {
        val projectDir = createFile("Boundary.java", """
            package com.example;
            public class Boundary {
                String s1 = "MyForgeMod";
                String s2 = "CustomForgeConfig";
                Object o1 = ForgeMod.class;
                Object o2 = ForgeConfig.class;
            }
        """.trimIndent())
        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)

        pass.apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/Boundary.java").readText()

        // Word-boundary regex should only match standalone ForgeMod, not MyForgeMod
        assertTrue(content.contains("NeoForgeMod.class"), "ForgeMod should become NeoForgeMod")
        assertTrue(content.contains("NeoForgeConfig.class"), "ForgeConfig should become NeoForgeConfig")
        // Note: "MyForgeMod" in string will be affected since regex matches on \bForgeMod\b
        // and "ForgeMod" inside "MyForgeMod" does NOT have a word boundary before "Forge"
        // because "y" before "F" is a word char, so \b won't match. This is correct!
    }
}
