package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** A superclass named through an on-demand import resolves like javac does. */
class ProjectValueFactsParentImportTest {
    @TempDir
    lateinit var directory: Path

    private fun java(relative: String, source: String): Path =
        directory.resolve(relative).also {
            it.parent.createDirectories()
            it.writeText(source.trimIndent())
        }

    @Test
    fun `on-demand imported platform parent and same-package shadowing`() {
        val pool = java(
            "example/pool/PoolBlock.java",
            """
            package example.pool;
            import net.minecraft.world.level.block.*;
            public final class PoolBlock extends Block {
                public PoolBlock(Properties properties) { super(properties); }
            }
            """
        )
        val shadow = java(
            "example/shadow/Block.java",
            """
            package example.shadow;
            public class Block {
            }
            """
        )
        val child = java(
            "example/shadow/Child.java",
            """
            package example.shadow;
            import net.minecraft.world.level.block.*;
            public class Child extends Block {
            }
            """
        )
        val pass = StructuralRefactorPass()

        val parents = ProjectValueFacts.collect(pass, listOf(pool, shadow, child)).parents

        assertEquals("net.minecraft.world.level.block.Block", parents["example.pool.PoolBlock"])
        assertEquals("example.shadow.Block", parents["example.shadow.Child"])
    }
}
