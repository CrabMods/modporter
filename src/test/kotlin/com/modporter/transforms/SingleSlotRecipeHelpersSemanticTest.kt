package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateSingleSlotRecipeHelpers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleSlotRecipeHelpersSemanticTest {
    @TempDir lateinit var directory: Path

    private fun normalizedExecutable(source: String): String =
        source.replace(Regex("""\s+"""), " ")

    @Test
    fun `actual Create wrapper shape prepares once before cooking input construction`() {
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
                private static final RecipeWrapper INPUT =
                    new RecipeWrapper(new ItemStackHandler(1));
                private static <T extends Recipe<Container>> boolean query(
                    ItemStack stack, Level level, RecipeType<T> type
                ) {
                    INPUT.setItem(0, stack.copy());
                    return level.getRecipeManager().getRecipeFor(type, INPUT, level).isPresent();
                }
                boolean use(ItemStack stack, Level level) {
                    return query(stack, level, RecipeType.SMELTING)
                        || query(stack, level, RecipeType.SMOKING);
                }
                static class Nested {
                    private static boolean query(
                        ItemStack stack, Level level, RecipeType<?> type
                    ) {
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
        assertTrue(
            source.contains(
                "java.util.function.Function<net.neoforged.neoforge.items.wrapper.RecipeWrapper, " +
                    "ModporterRecipeInput> modporterRecipeInputFactory"
            ),
            source
        )
        assertTrue(
            code.contains(
                "query(stack, level, RecipeType.SMELTING, modporterRecipeWrapper -> " +
                    "new net.minecraft.world.item.crafting.SingleRecipeInput(" +
                    "modporterRecipeWrapper.getItem(0)))"
            ),
            source
        )
        assertTrue(
            code.contains(
                "query(stack, level, RecipeType.SMOKING, modporterRecipeWrapper -> " +
                    "new net.minecraft.world.item.crafting.SingleRecipeInput(" +
                    "modporterRecipeWrapper.getItem(0)))"
            ),
            source
        )
        assertEquals(1, Regex("""stack\.copy\(\)""").findAll(source).count(), source)
        val preparation = source.indexOf("INPUT_HANDLER.setStackInSlot(0, stack.copy())")
        val construction = source.indexOf("modporterRecipeInputFactory.apply(INPUT)")
        assertTrue(preparation >= 0 && construction > preparation, source)
        assertTrue(source.contains("return query(stack, level, RecipeType.BLASTING);"), source)
        assertFalse(source.contains("SingleRecipeInput(stack.copy())"), source)
    }

    @Test
    fun `mixed cooking and proven processing enum use factory and identity after shared prep`() {
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
                private static final ItemStackHandler HANDLER = new ItemStackHandler(1);
                private static final RecipeWrapper INPUT = new RecipeWrapper(HANDLER);
                private static <T extends Recipe<Container>> boolean query(
                    ItemStack stack, Level level, RecipeType<T> type
                ) {
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
        assertTrue(
            code.contains(
                "query(stack, level, RecipeType.SMELTING, modporterRecipeWrapper -> " +
                    "new net.minecraft.world.item.crafting.SingleRecipeInput(" +
                    "modporterRecipeWrapper.getItem(0)))"
            ),
            source
        )
        assertTrue(
            source.contains(
                "query(stack, level, WRAPPED.exactType(), java.util.function.Function.identity())"
            ),
            source
        )
        assertEquals(1, Regex("""stack\.copy\(\)""").findAll(source).count(), source)
        val preparation = source.indexOf("HANDLER.setStackInSlot(0, stack.copy())")
        val construction = source.indexOf("modporterRecipeInputFactory.apply(INPUT)")
        assertTrue(preparation >= 0 && construction > preparation, source)
    }

    @Test
    fun `wrapper ownership capacity and exact copy preparation are required`() {
        val file = directory.resolve("src/main/java/example/UnsafeQueries.java")
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

            class WrongBacking {
                private static final ItemStackHandler BACKING = new ItemStackHandler(1);
                private static final ItemStackHandler OTHER = new ItemStackHandler(1);
                private static final RecipeWrapper INPUT = new RecipeWrapper(BACKING);
                private static <T extends Recipe<Container>> boolean query(
                    ItemStack stack, Level level, RecipeType<T> type
                ) {
                    OTHER.setStackInSlot(0, stack.copy());
                    return level.getRecipeManager().getRecipeFor(type, INPUT, level).isPresent();
                }
                boolean use(ItemStack stack, Level level) {
                    return query(stack, level, RecipeType.SMELTING);
                }
            }
            class WrongCapacity {
                private static final RecipeWrapper INPUT =
                    new RecipeWrapper(new ItemStackHandler(2));
                private static <T extends Recipe<Container>> boolean query(
                    ItemStack stack, Level level, RecipeType<T> type
                ) {
                    INPUT.setItem(0, stack.copy());
                    return level.getRecipeManager().getRecipeFor(type, INPUT, level).isPresent();
                }
                boolean use(ItemStack stack, Level level) {
                    return query(stack, level, RecipeType.SMELTING);
                }
            }
            class MissingCopy {
                private static final RecipeWrapper INPUT =
                    new RecipeWrapper(new ItemStackHandler(1));
                private static <T extends Recipe<Container>> boolean query(
                    ItemStack stack, Level level, RecipeType<T> type
                ) {
                    INPUT.setItem(0, stack);
                    return level.getRecipeManager().getRecipeFor(type, INPUT, level).isPresent();
                }
                boolean use(ItemStack stack, Level level) {
                    return query(stack, level, RecipeType.SMELTING);
                }
            }
            class UnknownFactory {
                private static final RecipeWrapper INPUT = createInput();
                private static RecipeWrapper createInput() {
                    return new RecipeWrapper(new ItemStackHandler(1));
                }
                private static <T extends Recipe<Container>> boolean query(
                    ItemStack stack, Level level, RecipeType<T> type
                ) {
                    INPUT.setItem(0, stack.copy());
                    return level.getRecipeManager().getRecipeFor(type, INPUT, level).isPresent();
                }
                boolean use(ItemStack stack, Level level) {
                    return query(stack, level, RecipeType.SMELTING);
                }
            }
        """.trimIndent())
        val before = file.readText()

        val source = StructuralRefactorPass().migrateSingleSlotRecipeHelpers(before)
        assertEquals(before, source)
        assertFalse(source.contains("ModporterRecipeInput"), source)
        assertFalse(source.contains("SingleRecipeInput"), source)
        assertFalse(source.contains("modporterRecipeInputFactory"), source)
    }
}
