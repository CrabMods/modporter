package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class RecipeInputBoundsTest {
    @TempDir lateinit var tempDir: Path

    private fun java(name: String, text: String): Path = tempDir.resolve("src/main/java/com/example/$name.java").also {
        it.parent.createDirectories()
        it.writeText(text.trimIndent())
    }

    @Test
    fun `inherited container recipe input and annotated hooks change together without rewriting text`() {
        java("Parent", """
            package com.example;
            import net.minecraft.world.Container;
            import net.minecraft.world.item.crafting.Recipe;
            public abstract class Parent<I extends Container> implements Recipe<I> {}
        """)
        val file = java("Child", """
            package com.example;
            import net.minecraft.world.Container;
            import net.minecraft.world.level.Level;
            import javax.annotation.Nonnull;
            public abstract class Child extends Parent<Container> {
                public String description() { return "Recipe<Container>"; }
                @Override
                public boolean matches(Container input, @Nonnull Level world) {
                    return false;
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("extends Parent<RecipeInput>"), result)
        assertTrue(result.contains("matches(RecipeInput input, @Nonnull Level world)"), result)
        assertTrue(result.contains("return \"Recipe<Container>\""), result)
    }

    @Test
    fun `recipe input constraints follow the actual generic position and inherited recipe types`() {
        val parent = java("Parent", """
            package com.example;
            import net.minecraft.world.Container;
            import net.minecraft.world.item.crafting.Recipe;
            public abstract class Parent<N extends Container, T extends Container> implements Recipe<T> {
                public int other(N inv) {
                    return inv.getContainerSize();
                }
            }
        """)
        val child = java("Child", """
            package com.example;
            import net.minecraft.world.Container;
            import net.minecraft.world.SimpleContainer;
            public abstract class Child<X extends Container> extends Parent<SimpleContainer, X> {
            }
        """)
        val helper = java("Helper", """
            package com.example;
            import net.minecraft.world.Container;
            public class Helper {
                public static <C extends Container, R extends Child<C>> int count(C inv, R recipe) {
                    return inv.getContainerSize();
                }
                public static <C extends Container> int ordinary(C inv) {
                    return inv.getContainerSize();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val parentText = parent.readText()
        assertTrue(parentText.contains("Parent<N extends Container, T extends RecipeInput>"), parentText)
        assertTrue(parentText.contains("return inv.getContainerSize();"), parentText)
        val childText = child.readText()
        assertTrue(childText.contains("Child<X extends RecipeInput>"), childText)
        val helperText = helper.readText()
        assertTrue(helperText.contains("<C extends RecipeInput, R extends Child<C>>"), helperText)
        assertTrue(helperText.contains("return inv.size();"), helperText)
        assertTrue(helperText.contains("<C extends Container> int ordinary(C inv)"), helperText)
        assertTrue(helperText.contains("return inv.getContainerSize();"), helperText)
    }
}
