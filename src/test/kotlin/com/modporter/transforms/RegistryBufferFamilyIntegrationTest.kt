package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RegistryBufferFamilyIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fluid codec seed propagates through exact source override family and caller`() {
        val root = tempDir.resolve("src/main/java/example")
        root.createDirectories()
        root.resolve("FluidIngredient.java").writeText(
            """
            package example;

            import java.util.List;
            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.fluids.FluidStack;

            abstract class FluidIngredient {
                abstract void writeInternal(FriendlyByteBuf buffer);

                final void write(FriendlyByteBuf buffer) {
                    writeInternal(buffer);
                }

                static final class StackIngredient extends FluidIngredient {
                    @Override void writeInternal(FriendlyByteBuf buffer) {
                        buffer.writeBoolean(true);
                    }
                }

                static final class TagIngredient extends FluidIngredient {
                    List<FluidStack> stacks() { return null; }

                    @Override void writeInternal(FriendlyByteBuf buffer) {
                        List<FluidStack> matching = stacks();
                        matching.stream().forEach(buffer::writeFluidStack);
                    }
                }
            }
            """.trimIndent()
        )
        root.resolve("Serializer.java").writeText(
            """
            package example;

            import net.minecraft.network.RegistryFriendlyByteBuf;

            final class Serializer {
                void write(RegistryFriendlyByteBuf buffer, FluidIngredient ingredient) {
                    ingredient.write(buffer);
                }
            }
            """.trimIndent()
        )

        StructuralRefactorPass().apply(tempDir)

        val ingredient = root.resolve("FluidIngredient.java").readText()
        assertTrue(
            Regex("""abstract void writeInternal\(RegistryFriendlyByteBuf buffer\)""")
                .containsMatchIn(ingredient),
            ingredient
        )
        assertTrue(
            Regex("""final void write\(RegistryFriendlyByteBuf buffer\)""")
                .containsMatchIn(ingredient),
            ingredient
        )
        assertTrue(
            Regex("""void writeInternal\(RegistryFriendlyByteBuf buffer\)""")
                .findAll(ingredient).count() == 3,
            ingredient
        )
        assertTrue(
            ingredient.contains(
                "FluidStack.OPTIONAL_STREAM_CODEC.encode(modporterBuffer, modporterStack)"
            ) || ingredient.contains(
                "net.neoforged.neoforge.fluids.FluidStack.OPTIONAL_STREAM_CODEC" +
                    ".encode(modporterBuffer, modporterStack)"
            ),
            ingredient
        )
        assertFalse(ingredient.contains("::writeFluidStack"), ingredient)
    }
}
