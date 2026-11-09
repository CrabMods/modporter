package com.modporter.transforms

import com.modporter.core.transforms.structural.EnumRecipeTypeInputs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class EnumRecipeTypeInputsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `ambiguous factories unresolved recipe inputs and detached getters produce no facts`() {
        val file = directory.resolve("src/main/java/example/UnsafeKinds.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import java.util.function.Supplier;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

            class Params {}
            abstract class Base<I> implements Recipe<I> {}
            class Concrete extends Base<RecipeWrapper> {
                Concrete(Params params) {}
            }
            class Generic<I> extends Base<I> {
                Generic(Params params) {}
            }
            class SerializerOnly {
                SerializerOnly(Params params) {}
            }
            interface First<R extends Base<?>> { R make(Params params); }
            interface Second<R extends Base<?>> { R make(Params params); }
            interface GenericFactory<R extends Base<?>> { R make(Params params); }
            interface SerializerFactory { SerializerOnly make(Params params); }

            enum Ambiguous {
                ENTRY(Concrete::new);
                private Supplier<RecipeType<?>> type;
                Ambiguous(First<?> factory) { type = () -> RecipeType.SMELTING; }
                Ambiguous(Second<?> factory) { type = () -> RecipeType.SMELTING; }
                <T extends RecipeType<?>> T getType() { return (T) type.get(); }
            }
            enum Unresolved {
                ENTRY(Generic::new);
                private Supplier<RecipeType<?>> type;
                Unresolved(GenericFactory<?> factory) { type = () -> RecipeType.SMELTING; }
                <T extends RecipeType<?>> T getType() { return (T) type.get(); }
            }
            enum NotARecipe {
                ENTRY(SerializerOnly::new);
                private Supplier<RecipeType<?>> type;
                NotARecipe(SerializerFactory factory) { type = () -> RecipeType.SMELTING; }
                <T extends RecipeType<?>> T getType() { return (T) type.get(); }
            }
            enum Detached {
                ENTRY(Concrete::new);
                private Supplier<RecipeType<?>> assigned;
                private Supplier<RecipeType<?>> detached;
                Detached(First<?> factory) { assigned = () -> RecipeType.SMELTING; }
                <T extends RecipeType<?>> T getType() { return (T) detached.get(); }
            }
        """.trimIndent())

        val facts = EnumRecipeTypeInputs.collect(listOf(file)).facts
        assertTrue(facts.isEmpty(), facts.toString())
    }
}
