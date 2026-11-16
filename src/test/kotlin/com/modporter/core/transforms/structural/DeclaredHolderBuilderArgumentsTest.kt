package com.modporter.core.transforms.structural

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DeclaredHolderBuilderArgumentsTest {
    @Test
    fun `registrate loot callback uses its instance and enchantment provider root`() {
        val source =
            """
            package example;

            import com.tterrag.registrate.providers.loot.RegistrateBlockLootTables;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.storage.loot.entries.LootItem;
            import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;

            class OreCatalog {
                Object ore = builder.loot((tables, block) ->
                    tables.add(block,
                        RegistrateBlockLootTables.createSilkTouchDispatchTable(
                            block,
                            tables.applyExplosionDecay(
                                block,
                                LootItem.lootTableItem(raw)
                                    .apply(ApplyBonusCount.addOreBonusCount(
                                        Enchantments.FORTUNE))))));
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateDeclaredHolderBuilderArguments(source)
        val compact = compact(migrated)

        assertTrue(
            compact.contains(
                "tables.createSilkTouchDispatchTable(block, " +
                    "tables.applyExplosionDecay("
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "ApplyBonusCount.addOreBonusCount(" +
                    "tables.getRegistries().holderOrThrow(Enchantments.FORTUNE))"
            ),
            migrated
        )
        assertFalse(
            compact.contains(
                "RegistrateBlockLootTables.createSilkTouchDispatchTable"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateDeclaredHolderBuilderArguments(migrated)
        )
    }

    @Test
    fun `built in fluid key lookup receives deferred holder values`() {
        val source =
            """
            package example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.neoforged.neoforge.common.NeoForgeMod;

            class FluidAliases {
                void aliases(Object oldStill, Object oldFlowing) {
                    BuiltInRegistries.FLUID.addAlias(
                        oldStill,
                        BuiltInRegistries.FLUID.getKey(NeoForgeMod.MILK));
                    BuiltInRegistries.FLUID.addAlias(
                        oldFlowing,
                        BuiltInRegistries.FLUID.getKey(NeoForgeMod.FLOWING_MILK));
                }

                Object unrelated() {
                    return anotherRegistry.getKey(NeoForgeMod.MILK);
                }

                Object otherElement() {
                    return BuiltInRegistries.ITEM.getKey(NeoForgeMod.MILK);
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateDeclaredHolderBuilderArguments(source)
        val compact = compact(migrated)

        assertTrue(
            compact.contains(
                "BuiltInRegistries.FLUID.getKey(NeoForgeMod.MILK.value())"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "BuiltInRegistries.FLUID.getKey(NeoForgeMod.FLOWING_MILK.value())"
            ),
            migrated
        )
        assertTrue(compact.contains("anotherRegistry.getKey(NeoForgeMod.MILK)"), migrated)
        // the item registry's element type is not the holder's fluid value type
        assertTrue(compact.contains("BuiltInRegistries.ITEM.getKey(NeoForgeMod.MILK)"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateDeclaredHolderBuilderArguments(migrated)
        )
    }

    @Test
    fun `project builder argument receives the value only its declared overloads accept`() {
        val pass = passWith(builder(), generator())
        val migrated = pass.migrateDeclaredHolderBuilderArguments(drainRecipes())
        val compact = compact(migrated)

        assertTrue(compact.contains(".output(NeoForgeMod.MILK.value(), 250)"), migrated)
        assertTrue(compact.contains(".output(NeoForgeMod.FLOWING_MILK.value(), 125)"), migrated)
        assertTrue(compact.contains("other.output(NeoForgeMod.MILK, 250)"), migrated)
        assertEquals(migrated, pass.migrateDeclaredHolderBuilderArguments(migrated))
    }

    @Test
    fun `holder accepting or unknown overloads keep the holder argument`() {
        val supplierOverload = builder().replace(
            "public Builder<T> output(String mod, String id) { return this; }",
            "public Builder<T> output(Supplier<Fluid> fluid, int amount) { return this; }"
        ).replace(
            "import net.minecraft.world.level.material.Fluid;",
            "import java.util.function.Supplier;\nimport net.minecraft.world.level.material.Fluid;"
        )
        val genericOverload = builder().replace(
            "public Builder<T> output(String mod, String id) { return this; }",
            "public <F> Builder<T> output(F fluid, int amount) { return this; }"
        )
        val disagreeingCallbacks = generator().replace(
            "protected <T> Object create(Object id, UnaryOperator<Builder<T>> transform) { return id; }",
            "protected Object create(Object id, UnaryOperator<Object> transform) { return id; }"
        )
        listOf(
            "supplier overload" to passWith(supplierOverload, generator()),
            "type variable overload" to passWith(genericOverload, generator()),
            "unproven callback" to passWith(builder(), disagreeingCallbacks),
            "no project declarations" to StructuralRefactorPass()
        ).forEach { (case, pass) ->
            val source = drainRecipes()
            val migrated = pass.migrateDeclaredHolderBuilderArguments(source)
            assertEquals(source, migrated, case)
        }
    }

    private fun passWith(vararg declarations: String): StructuralRefactorPass =
        StructuralRefactorPass().also {
            it.projectSourceDeclarations = ProjectSourceDeclarations.of(declarations.toList() + drainRecipes())
        }

    private fun builder(): String =
        """
        package example.recipe;

        import net.minecraft.world.level.ItemLike;
        import net.minecraft.world.level.material.Fluid;

        public class Builder<T> {
            public Builder<T> require(ItemLike item) { return this; }
            public Builder<T> output(ItemLike item) { return this; }
            public Builder<T> output(ItemLike item, int amount) { return this; }
            public Builder<T> output(Fluid fluid, int amount) { return this; }
            public Builder<T> output(String mod, String id) { return this; }
        }
        """.trimIndent() + "\n"

    private fun generator(): String =
        """
        package example.recipe;

        import java.util.function.UnaryOperator;

        public abstract class RecipeGenerator {
            protected <T> Object create(String name, UnaryOperator<Builder<T>> transform) { return name; }
            protected <T> Object create(Object id, UnaryOperator<Builder<T>> transform) { return id; }
        }
        """.trimIndent() + "\n"

    private fun drainRecipes(): String =
        """
        package example.data;

        import example.recipe.RecipeGenerator;
        import net.minecraft.world.item.Items;
        import net.neoforged.neoforge.common.NeoForgeMod;

        public final class DrainRecipes extends RecipeGenerator {
            Object first = create("milk", recipe -> recipe.require(Items.BUCKET)
                .output(NeoForgeMod.MILK, 250)
                .output(Items.BUCKET));

            Object second = create(Items.BUCKET, recipe -> recipe
                .output(NeoForgeMod.FLOWING_MILK, 125));

            static final class Unrelated {
                Object retained(UnknownBuilder other) {
                    return other.output(NeoForgeMod.MILK, 250);
                }
            }
        }
        """.trimIndent() + "\n"

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()
}
