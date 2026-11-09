package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecipeProviderConstructorsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `the library namespace stays before its registry future`() {
        val file = directory.resolve("src/main/java/example/LibraryRecipes.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.data.PackOutput;
            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            class LibraryRecipes extends AbstractRecipeProvider {
                private static final String NAMESPACE = "example";
                public LibraryRecipes(PackOutput output) { super(output, NAMESPACE); }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        assertTrue(file.readText().contains("super(output, NAMESPACE, lookupProvider)"), file.readText())
    }

    @Test
    fun `a provider suffix on an unrelated project parent does not widen constructors`() {
        val file = directory.resolve("src/main/java/example/OtherRecipes.java")
        file.parent.createDirectories()
        val source = """
            package example;
            import net.minecraft.data.PackOutput;
            class OtherRecipeProvider { OtherRecipeProvider(PackOutput output) {} }
            class OtherRecipes extends OtherRecipeProvider {
                final String namespace;
                public OtherRecipes(PackOutput output, String namespace) {
                    super(output);
                    this.namespace = namespace;
                }
            }
        """.trimIndent()
        file.writeText(source)
        StructuralRefactorPass().apply(directory)
        assertEquals(source, file.readText())
    }

    @Test
    fun `provider constructors retain assignments and later methods while passing output only once`() {
        val file = directory.resolve("src/main/java/example/BaseRecipes.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeProvider;
            import net.minecraft.resources.ResourceLocation;
            public abstract class BaseRecipes extends RecipeProvider {
                protected final String namespace;
                public BaseRecipes(PackOutput output, String namespace) {
                    super(output);
                    this.namespace = namespace;
                }
                protected ResourceLocation id(String path) {
                    return ResourceLocation.fromNamespaceAndPath(namespace, path);
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("super(output, lookupProvider);"), source)
        assertTrue(source.contains("this.namespace = namespace;"), source)
        assertTrue(source.contains("return ResourceLocation.fromNamespaceAndPath(namespace, path);"), source)
        StructuralRefactorPass().apply(directory)
        assertEquals(source, file.readText())
    }
}
