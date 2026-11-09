package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleSlotRecipeHelpersTest {
    @TempDir lateinit var directory: Path

    private fun normalizedExecutable(source: String): String =
        source.replace(Regex("""\s+"""), " ")

    @Test
    fun `cooking helpers build inputs from the prepared wrapper without rewriting nested class calls`() {
        val file = directory.resolve("src/main/java/example/Queries.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.world.Container;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.neoforged.neoforge.items.ItemStackHandler;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
            class Queries {
                static final ItemStackHandler HANDLER = new ItemStackHandler(1);
                static final RecipeWrapper INPUT = new RecipeWrapper(HANDLER);
                private static <T extends Recipe<Container>> boolean query(ItemStack stack, Level level, RecipeType<T> type) {
                    HANDLER.setStackInSlot(0, stack.copy());
                    return level.getRecipeManager().getRecipeFor(type, INPUT, level).isPresent();
                }
                boolean use(ItemStack stack, Level level) {
                    return query(stack, level, RecipeType.SMELTING) || query(stack, level, RecipeType.SMOKING);
                }
                static class Nested {
                    private static boolean query(ItemStack stack, Level level, RecipeType<?> type) {
                        return false;
                    }
                    boolean use(ItemStack stack, Level level) {
                        return query(stack, level, RecipeType.BLASTING);
                    }
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        val code = normalizedExecutable(source)
        assertTrue(source.contains("T extends Recipe<ModporterRecipeInput>"), source)
        assertTrue(code.contains("query(stack, level, RecipeType.SMELTING, modporterRecipeWrapper -> new net.minecraft.world.item.crafting.SingleRecipeInput(modporterRecipeWrapper.getItem(0)))"), source)
        assertTrue(code.contains("query(stack, level, RecipeType.SMOKING, modporterRecipeWrapper -> new net.minecraft.world.item.crafting.SingleRecipeInput(modporterRecipeWrapper.getItem(0)))"), source)
        assertTrue(source.contains("return query(stack, level, RecipeType.BLASTING);"), source)
        assertTrue(source.contains("private static boolean query(ItemStack stack, Level level, RecipeType<?> type)"), source)
        assertTrue(source.contains("getRecipeFor(type, modporterRecipeInputFactory.apply(INPUT), level)"), source)
        assertTrue(source.contains("HANDLER.setStackInSlot(0, stack.copy())"), source)
        assertTrue(Regex("""stack\.copy\(\)""").findAll(source).count() == 1, source)
        assertFalse(source.contains("SingleRecipeInput(stack.copy())"), source)
    }

    @Test
    fun `unknown recipe type getters leave the entire mixed helper unchanged`() {
        val file = directory.resolve("src/main/java/example/Queries.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.world.Container;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.neoforged.neoforge.items.ItemStackHandler;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
            class Queries {
                static final ItemStackHandler HANDLER = new ItemStackHandler(1);
                static final RecipeWrapper INPUT = new RecipeWrapper(HANDLER);
                private static <T extends Recipe<Container>> boolean query(ItemStack stack, Level level, RecipeType<T> type) {
                    HANDLER.setStackInSlot(0, stack.copy());
                    return level.getRecipeManager().getRecipeFor(type, INPUT, level).isPresent();
                }
                boolean use(ItemStack stack, Level level) {
                    return query(stack, level, RecipeType.SMELTING)
                        || query(stack, level, AllRecipeTypes.CUTTING.getType())
                        || query(stack, level, AllRecipeTypes.MILLING.getType());
                }
            }
            enum AllRecipeTypes {
                CUTTING, MILLING;
                <T extends Recipe<Container>> RecipeType<T> getType() {
                    return null;
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("T extends Recipe<Container>"), source)
        assertTrue(source.contains("getRecipeFor(type, INPUT, level)"), source)
        assertTrue(source.contains("query(stack, level, RecipeType.SMELTING)"), source)
        assertTrue(source.contains("query(stack, level, AllRecipeTypes.CUTTING.getType())"), source)
        assertTrue(source.contains("query(stack, level, AllRecipeTypes.MILLING.getType())"), source)
        assertFalse(source.contains("ModporterRecipeInput"), source)
        assertFalse(source.contains("SingleRecipeInput"), source)
    }

    @Test
    fun `generic enum factory proof uses identity beside prepared cooking inputs`() {
        val file = directory.resolve("src/main/java/example/Queries.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import java.util.function.Supplier;
            import net.minecraft.world.Container;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.neoforged.neoforge.items.ItemStackHandler;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
            import static example.Kinds.WRAPPED;

            class Params {}
            interface RenamedFactory<R extends BaseRecipe<?>> {
                R create(Params params);
            }
            abstract class BaseRecipe<I> implements Recipe<I> {}
            abstract class MiddleRecipe<I> extends BaseRecipe<I> {}
            class WrappedRecipe extends MiddleRecipe<RecipeWrapper> {
                WrappedRecipe(Params params) {}
            }
            enum Kinds {
                WRAPPED(WrappedRecipe::new);
                private final Supplier<RecipeType<?>> type;
                Kinds(Supplier<RecipeSerializer<?>> serializer) {
                    type = () -> RecipeType.SMELTING;
                }
                Kinds(RenamedFactory<?> factory) {
                    this(() -> null);
                }
                @SuppressWarnings("unchecked")
                <T extends RecipeType<?>> T exactType() {
                    return (T) type.get();
                }
            }
            class Queries {
                static final ItemStackHandler HANDLER = new ItemStackHandler(1);
                static final RecipeWrapper INPUT = new RecipeWrapper(HANDLER);
                private static <T extends Recipe<Container>> boolean query(ItemStack stack, Level level, RecipeType<T> type) {
                    HANDLER.setStackInSlot(0, stack.copy());
                    return level.getRecipeManager().getRecipeFor(type, INPUT, level).isPresent();
                }
                boolean use(ItemStack stack, Level level) {
                    return query(stack, level, RecipeType.SMELTING)
                        || query(stack, level, WRAPPED.exactType());
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        val code = normalizedExecutable(source)
        assertTrue(code.contains("query(stack, level, RecipeType.SMELTING, modporterRecipeWrapper -> new net.minecraft.world.item.crafting.SingleRecipeInput(modporterRecipeWrapper.getItem(0)))"), source)
        assertTrue(source.contains("query(stack, level, WRAPPED.exactType(), java.util.function.Function.identity())"), source)
        assertTrue(source.contains("getRecipeFor(type, modporterRecipeInputFactory.apply(INPUT), level)"), source)
        assertTrue(source.contains("T extends Recipe<ModporterRecipeInput>"), source)
        assertTrue(Regex("""stack\.copy\(\)""").findAll(source).count() == 1, source)
    }
}
