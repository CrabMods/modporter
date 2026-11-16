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
        assertTrue(
            migrated.contains(
                "ingredient.tagToMatch = fluidStack.getOrDefault(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.EMPTY).copyTag();"
            ),
            migrated
        )
        assertFalse(migrated.contains("readRegistryId"), migrated)
        assertFalse(migrated.contains("writeRegistryId"), migrated)
        assertFalse(
            Regex(
                """new\s+FluidStack\s*\(\s*fluid\s*,\s*amountRequired\s*,\s*tagToMatch\s*\)"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            migrated.contains(
                """
                    protected void readInternal(RegistryFriendlyByteBuf buffer) {
                        ResourceLocation registryName = buffer.readResourceLocation();
                        if (!registryName.equals(Registries.FLUID.location()))
                            throw new IllegalArgumentException("Expected fluid registry, got " + registryName);
                        int fluidId = buffer.readVarInt();
                        fluid = buffer.registryAccess().registryOrThrow(Registries.FLUID).byId(fluidId);
                        if (fluid == null || buffer.registryAccess().registryOrThrow(Registries.FLUID).getIdOrThrow(fluid) != fluidId)
                            throw new IllegalArgumentException("Unknown fluid id " + fluidId);
                        tagToMatch = buffer.readNbt();
                    }
                """.trimIndent().prependIndent("        ")
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                """
                    protected void writeInternal(RegistryFriendlyByteBuf buffer) {
                        buffer.writeResourceLocation(Registries.FLUID.location());
                        buffer.writeVarInt(buffer.registryAccess().registryOrThrow(Registries.FLUID).getIdOrThrow(fluid));
                        buffer.writeNbt(tagToMatch);
                    }
                """.trimIndent().prependIndent("        ")
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                """
                    protected List<FluidStack> determineMatchingFluidStacks() {
                        if (tagToMatch.isEmpty())
                            return ImmutableList.of(new FluidStack(fluid, amountRequired));
                        FluidStack stack = new FluidStack(fluid, amountRequired);
                        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tagToMatch));
                        return ImmutableList.of(stack);
                    }
                """.trimIndent().prependIndent("        ")
            ),
            migrated
        )
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
    fun `a family with other names and the same shape gets the same rewrite`() {
        val project = directory.resolve("renamed")
        val requirement = java(
            project,
            "example/liquid/LiquidRequirement.java",
            """
            package example.liquid;

            import java.util.List;
            import com.google.common.collect.ImmutableList;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.level.material.Fluid;
            import net.neoforged.neoforge.fluids.FluidStack;

            public class LiquidRequirement {
                private Fluid liquid;
                private int volume;
                private CompoundTag filter = new CompoundTag();

                public static LiquidRequirement ofStack(FluidStack source) {
                    LiquidRequirement requirement = new LiquidRequirement();
                    requirement.liquid = source.getFluid();
                    requirement.volume = source.getAmount();
                    if (source.has(DataComponents.CUSTOM_DATA)) {
                        requirement.filter = source.getTag();
                    }
                    return requirement;
                }

                void decode(RegistryFriendlyByteBuf wire) {
                    volume = wire.readVarInt();
                    this.liquid = wire.readRegistryId();
                }

                void encode(RegistryFriendlyByteBuf wire) {
                    wire.writeVarInt(volume);
                    wire.writeRegistryId(BuiltInRegistries.FLUID, liquid);
                }

                List<FluidStack> expand() {
                    return ImmutableList.copyOf(List.of(filter.isEmpty() ? new FluidStack(liquid, volume)
                        : new FluidStack(liquid, volume, filter)));
                }

                FluidStack single() {
                    FluidStack made = new FluidStack(liquid, volume, filter);
                    return made;
                }
            }
            """
        )
        java(
            project,
            "example/liquid/Users.java",
            """
            package example.liquid;
            import net.neoforged.neoforge.fluids.FluidStack;
            public class Users {
                static LiquidRequirement fresh() {
                    return LiquidRequirement.ofStack(new FluidStack(null, 10));
                }
                static LiquidRequirement copied(FluidStack shared) {
                    FluidStack copy = shared.copy();
                    return LiquidRequirement.ofStack(copy);
                }
            }
            """
        )

        val result = SourceOwnedFluidIngredient.migrate(StructuralRefactorPass(), project, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        val migrated = requirement.readText()
        assertTrue(
            migrated.contains(
                "requirement.filter = source.getOrDefault(net.minecraft.core.component.DataComponents" +
                    ".CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();"
            ),
            migrated
        )
        assertTrue(migrated.contains("ResourceLocation registryName = wire.readResourceLocation();"), migrated)
        assertTrue(migrated.contains("int liquidId = wire.readVarInt();"), migrated)
        assertTrue(
            migrated.contains(
                "this.liquid = wire.registryAccess().registryOrThrow(Registries.FLUID).byId(liquidId);"
            ),
            migrated
        )
        assertTrue(migrated.contains("\"Unknown fluid id \" + liquidId"), migrated)
        assertTrue(migrated.contains("wire.writeResourceLocation(Registries.FLUID.location());"), migrated)
        assertTrue(
            migrated.contains(
                "wire.writeVarInt(wire.registryAccess().registryOrThrow(Registries.FLUID).getIdOrThrow(liquid));"
            ),
            migrated
        )
        assertTrue(
            migrated.contains("return ImmutableList.copyOf(List.of(new FluidStack(liquid, volume)));") &&
                migrated.contains("return ImmutableList.copyOf(List.of(stack));"),
            migrated
        )
        assertTrue(
            migrated.contains("FluidStack made = new FluidStack(liquid, volume);") &&
                migrated.contains("if (filter != null)"),
            migrated
        )
        assertFalse(migrated.contains("readRegistryId") || migrated.contains("writeRegistryId"), migrated)
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

    @Test
    fun `an unguarded live tag capture is not admitted and is left to other rules`() {
        val project = directory.resolve("unguarded")
        val holder = java(
            project,
            "example/Holder.java",
            """
            package example;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.fluids.FluidStack;
            public class Holder {
                CompoundTag tag;
                static Holder of(FluidStack stack) {
                    Holder holder = new Holder();
                    holder.tag = stack.getTag();
                    return holder;
                }
            }
            """
        )
        val before = holder.readText()

        val result = SourceOwnedFluidIngredient.migrate(StructuralRefactorPass(), project, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertTrue(result.changes.isEmpty())
        assertEquals(before, holder.readText())
    }

    @Test
    fun `an unrecognized legacy member inside an admitted family gates the family`() {
        val project = directory.resolve("mixed")
        val codec = java(
            project,
            "example/Codec.java",
            """
            package example;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.level.material.Fluid;
            public class Codec {
                Fluid fluid;
                void write(RegistryFriendlyByteBuf buffer) {
                    buffer.writeRegistryId(BuiltInRegistries.FLUID, fluid);
                }
                Fluid read(RegistryFriendlyByteBuf buffer) {
                    return buffer.readRegistryId();
                }
            }
            """
        )
        val before = codec.readText()

        val result = SourceOwnedFluidIngredient.migrate(StructuralRefactorPass(), project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any { it.contains("outside the admitted shapes") },
            result.gates.joinToString("\n")
        )
        assertEquals(before, codec.readText())
    }

    private fun writeFixture(project: Path, aliasEscape: Boolean = false): Fixture {
        val ingredient = java(
            project,
            "example/fluid/FluidIngredient.java",
            """
            package example.fluid;

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
            """
        )

        java(
            project,
            "example/fluid/FluidHelper.java",
            """
            package example.fluid;
            import net.neoforged.neoforge.fluids.FluidStack;
            public class FluidHelper {
                public static FluidStack copyStackWithAmount(FluidStack stack, int amount) {
                    return stack.copy();
                }
            }
            """
        )
        java(
            project,
            "example/potion/PotionFluid.java",
            """
            package example.potion;
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
            """
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
        java(
            project,
            "example/potion/PotionFluidHandler.java",
            """
            package example.potion;
            import example.fluid.FluidHelper;
            import example.fluid.FluidIngredient;
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
            """
        )
        java(
            project,
            "example/potion/PotionMixingRecipes.java",
            """
            package example.potion;
            import example.fluid.FluidIngredient;
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
            """
        )
        return Fixture(project, ingredient)
    }

    private fun java(project: Path, relative: String, source: String): Path {
        val path = project.resolve("src/main/java").resolve(relative)
        path.parent.createDirectories()
        path.writeText(source.trimIndent())
        return path
    }

    private data class Fixture(
        val project: Path,
        val ingredient: Path
    )
}
