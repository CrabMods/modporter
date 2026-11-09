package com.modporter.transforms

import com.modporter.core.transforms.structural.SourceOwnedFluidIngredient
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SourceOwnedFluidIngredientTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `closed source family preserves tag copies and registry name plus VarInt wire framing`() {
        val fixture = writeFixture(directory.resolve("positive"))

        val result = SourceOwnedFluidIngredient.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(1, result.changes.size)
        val migrated = fixture.ingredient.readText()
        assertFalse(migrated.contains(".getTag()"), migrated)
        assertTrue(migrated.contains("CustomData.EMPTY).copyTag()"), migrated)
        assertFalse(migrated.contains("readRegistryId"), migrated)
        assertFalse(migrated.contains("writeRegistryId"), migrated)
        assertFalse(
            Regex(
                """new\s+FluidStack\s*\(\s*fluid\s*,\s*amountRequired\s*,\s*tagToMatch\s*\)"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            migrated.indexOf("buffer.readResourceLocation()") <
                migrated.indexOf("buffer.readVarInt()"),
            migrated
        )
        assertTrue(
            migrated.indexOf("buffer.writeResourceLocation(Registries.FLUID.location())") <
                migrated.indexOf("buffer.writeVarInt("),
            migrated
        )
        assertTrue(migrated.contains("getIdOrThrow(fluid) != fluidId"), migrated)
        assertTrue(migrated.contains("Unknown fluid id "), migrated)
        assertTrue(migrated.contains("CustomData.of(tagToMatch)"), migrated)
        assertTrue(migrated.contains("json.addProperty(\"fluid\", \"example:fluid\")"), migrated)
        assertTrue(migrated.contains("json.add(\"nbt\", tagToMatch.toString())"), migrated)
        assertTrue(migrated.contains("public static final FluidIngredient EMPTY"), migrated)
        assertTrue(migrated.contains("FluidStack.OPTIONAL_STREAM_CODEC.decode(buffer)"), migrated)

        val second = SourceOwnedFluidIngredient.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )
        assertTrue(second.gates.isEmpty(), second.gates.joinToString("\n"))
        assertTrue(second.changes.isEmpty())
    }

    @Test
    fun `post capture stack use keeps the complete family unchanged`() {
        val fixture = writeFixture(directory.resolve("alias-escape"), aliasEscape = true)
        val before = fixture.ingredient.readText()

        val result = SourceOwnedFluidIngredient.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any { it.contains("mutable tag aliasing") },
            result.gates.joinToString("\n")
        )
        assertEquals(before, fixture.ingredient.readText())
    }

    private fun writeFixture(project: Path, aliasEscape: Boolean = false): Fixture {
        val ingredient = project.resolve(
            "src/main/java/com/simibubi/create/foundation/fluid/FluidIngredient.java"
        )
        ingredient.parent.createDirectories()
        ingredient.writeText("""
            package com.simibubi.create.foundation.fluid;

            import java.util.List;
            import com.google.common.collect.ImmutableList;
            import com.google.gson.JsonObject;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.level.material.Fluid;
            import net.neoforged.neoforge.fluids.FluidStack;

            public abstract class FluidIngredient {
                public static final FluidIngredient EMPTY = new FluidStackIngredient();
                protected int amountRequired;

                public static FluidIngredient fromFluidStack(FluidStack fluidStack) {
                    FluidStackIngredient ingredient = new FluidStackIngredient();
                    ingredient.fluid = fluidStack.getFluid();
                    ingredient.amountRequired = fluidStack.getAmount();
                    ingredient.fixFlowing();
                    if (fluidStack.has(DataComponents.CUSTOM_DATA))
                        ingredient.tagToMatch = fluidStack.getTag();
                    return ingredient;
                }

                public static class FluidStackIngredient extends FluidIngredient {
                    protected Fluid fluid;
                    protected CompoundTag tagToMatch;

                    public FluidStackIngredient() {
                        tagToMatch = new CompoundTag();
                    }

                    void fixFlowing() {
                    }

                    protected void readInternal(RegistryFriendlyByteBuf buffer) {
                        fluid = buffer.readRegistryId();
                        tagToMatch = buffer.readNbt();
                    }

                    protected void writeInternal(RegistryFriendlyByteBuf buffer) {
                        buffer.writeRegistryId(BuiltInRegistries.FLUID, fluid);
                        buffer.writeNbt(tagToMatch);
                    }

                    protected void readInternal(JsonObject json) {
                        tagToMatch = new CompoundTag();
                    }

                    protected void writeInternal(JsonObject json) {
                        json.addProperty("fluid", "example:fluid");
                        json.add("nbt", tagToMatch.toString());
                    }

                    protected List<FluidStack> determineMatchingFluidStacks() {
                        return ImmutableList.of(tagToMatch.isEmpty()
                            ? new FluidStack(fluid, amountRequired)
                            : new FluidStack(fluid, amountRequired, tagToMatch));
                    }
                }

                public static class FluidTagIngredient extends FluidIngredient {
                    void readTag(RegistryFriendlyByteBuf buffer) {
                        FluidStack.OPTIONAL_STREAM_CODEC.decode(buffer);
                    }
                }
            }
        """.trimIndent())

        val helper = project.resolve(
            "src/main/java/com/simibubi/create/foundation/fluid/FluidHelper.java"
        )
        helper.writeText("""
            package com.simibubi.create.foundation.fluid;
            import net.neoforged.neoforge.fluids.FluidStack;
            public class FluidHelper {
                public static FluidStack copyStackWithAmount(FluidStack stack, int amount) {
                    return stack.copy();
                }
            }
        """.trimIndent())

        val potion = project.resolve(
            "src/main/java/com/simibubi/create/content/fluids/potion/PotionFluid.java"
        )
        potion.parent.createDirectories()
        potion.writeText("""
            package com.simibubi.create.content.fluids.potion;
            import net.neoforged.neoforge.fluids.FluidStack;
            public class PotionFluid {
                public static FluidStack of() {
                    FluidStack stack;
                    stack = new FluidStack(null, 250);
                    return stack;
                }
                public static FluidStack withEffects() {
                    FluidStack stack = of();
                    return stack;
                }
            }
        """.trimIndent())

        val handler = project.resolve(
            "src/main/java/com/simibubi/create/content/fluids/potion/PotionFluidHandler.java"
        )
        val unsafeBody = if (aliasEscape) {
            """
                FluidIngredient captured = FluidIngredient.fromFluidStack(stack);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                return captured;
            """.trimIndent()
        } else {
            "return FluidIngredient.fromFluidStack(stack);"
        }
        handler.writeText("""
            package com.simibubi.create.content.fluids.potion;
            import com.simibubi.create.foundation.fluid.FluidHelper;
            import com.simibubi.create.foundation.fluid.FluidIngredient;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.item.component.CustomData;
            import net.neoforged.neoforge.fluids.FluidStack;
            public class PotionFluidHandler {
                public static FluidStack getFluidFromPotionItem() {
                    if (System.nanoTime() == 0)
                        return new FluidStack(null, 250);
                    FluidStack stack = PotionFluid.withEffects();
                    return stack;
                }
                public static FluidIngredient direct() {
                    FluidStack stack = getFluidFromPotionItem();
                    $unsafeBody
                }
                public static FluidIngredient copied(FluidStack source) {
                    FluidStack copy = source.copy();
                    return FluidIngredient.fromFluidStack(copy);
                }
                public static FluidIngredient inline() {
                    return FluidIngredient.fromFluidStack(
                        FluidHelper.copyStackWithAmount(getFluidFromPotionItem(), 250));
                }
            }
        """.trimIndent())

        val mixing = project.resolve(
            "src/main/java/com/simibubi/create/content/fluids/potion/PotionMixingRecipes.java"
        )
        mixing.writeText("""
            package com.simibubi.create.content.fluids.potion;
            import com.simibubi.create.foundation.fluid.FluidIngredient;
            import net.neoforged.neoforge.fluids.FluidStack;
            public class PotionMixingRecipes {
                private static FluidIngredient createRecipe(FluidStack fromFluid) {
                    return FluidIngredient.fromFluidStack(fromFluid);
                }
                public static FluidIngredient createAll() {
                    FluidStack fromFluid = PotionFluidHandler.getFluidFromPotionItem();
                    return createRecipe(fromFluid);
                }
            }
        """.trimIndent())
        return Fixture(project, ingredient)
    }

    private data class Fixture(
        val project: Path,
        val ingredient: Path
    )
}
