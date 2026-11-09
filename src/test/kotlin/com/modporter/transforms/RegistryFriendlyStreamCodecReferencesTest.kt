package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateRegistryFriendlyStreamCodecReferences
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RegistryFriendlyStreamCodecReferencesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `item writer reference uses the optional item stack stream codec`() {
        val source = """
            package example;

            import java.util.List;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.item.ItemStack;

            final class Packet {
                private List<ItemStack> stacks;

                void write(RegistryFriendlyByteBuf buffer) {
                    stacks.forEach(buffer::writeItem);
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateRegistryFriendlyStreamCodecReferences(source)

        assertTrue(
            migrated.contains(
                "java.util.Objects.requireNonNull(buffer)"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC" +
                    ".encode(modporterBuffer, modporterStack)"
            ),
            migrated
        )
        assertTrue(migrated.contains("}::modporterEncode"), migrated)
        assertFalse(migrated.contains("::writeItem"), migrated)
        assertFalse(migrated.contains("ItemStack.STREAM_CODEC"), migrated)
    }

    @Test
    fun `fluid writer reference seeds a registry buffer signature`() {
        val source = """
            package example;

            import java.util.List;
            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.fluids.FluidStack;

            final class FluidWriter {
                List<FluidStack> getStacks() { return null; }

                void write(FriendlyByteBuf packet) {
                    List<FluidStack> local = getStacks();
                    local.stream().forEach(packet::writeFluidStack);
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateRegistryFriendlyStreamCodecReferences(source)

        assertTrue(
            migrated.contains("void write(RegistryFriendlyByteBuf packet)"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "net.neoforged.neoforge.fluids.FluidStack.OPTIONAL_STREAM_CODEC" +
                    ".encode(modporterBuffer, modporterStack)"
            ),
            migrated
        )
        assertTrue(
            migrated.contains("import net.minecraft.network.RegistryFriendlyByteBuf;"),
            migrated
        )
        assertFalse(migrated.contains("::writeFluidStack"), migrated)
        assertFalse(migrated.contains("FluidStack.STREAM_CODEC"), migrated)
    }

    @Test
    fun `capturing adapter null checks before an empty loop and invokes the codec`() {
        val original = """
            package example;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Consumer;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.item.ItemStack;

            public final class Probe {
                static final class CountingList<T> extends ArrayList<T> {
                    static int starts;

                    @Override
                    public void forEach(Consumer<? super T> action) {
                        starts++;
                        super.forEach(action);
                    }
                }

                static void write(List<ItemStack> values, RegistryFriendlyByteBuf buffer) {
                    values.forEach(buffer::writeItem);
                }

                static int nullBeforeEmptyForEach() {
                    CountingList<ItemStack> empty = new CountingList<>();
                    try {
                        write(empty, null);
                        throw new AssertionError("missing eager null check");
                    } catch (NullPointerException expected) {
                        return CountingList.starts;
                    }
                }

                static int codecCalls() {
                    RegistryFriendlyByteBuf spy = new RegistryFriendlyByteBuf();
                    write(List.of(new ItemStack()), spy);
                    return spy.encodes;
                }

                public static void check() {
                    if (nullBeforeEmptyForEach() != 0)
                        throw new AssertionError("forEach began before receiver null check");
                    if (codecCalls() != 1)
                        throw new AssertionError("optional codec did not run exactly once");
                }
            }
        """.trimIndent()
        val parsed = JavaParser().parse(original).result.orElseThrow()
        val legacyReference = parsed.findFirst(MethodReferenceExpr::class.java).orElseThrow()
        assertTrue(legacyReference.scope is TypeExpr, legacyReference.scope.toString())
        val migrated =
            StructuralRefactorPass().migrateRegistryFriendlyStreamCodecReferences(original)
        assertTrue(migrated.contains("java.util.Objects.requireNonNull(buffer)"), migrated)
        assertTrue(migrated.contains("}::modporterEncode"), migrated)
        assertFalse(migrated.contains("buffer::writeItem"), migrated)

        val sources = directory.resolve("sources")
        val probe = writeDraftJava(sources, "example/Probe.java", migrated)
        val buffer = writeDraftJava(
            sources,
            "net/minecraft/network/RegistryFriendlyByteBuf.java",
            """
            package net.minecraft.network;
            public class RegistryFriendlyByteBuf {
                public int encodes;
            }
            """.trimIndent()
        )
        val codec = writeDraftJava(
            sources,
            "net/minecraft/network/codec/StreamCodec.java",
            """
            package net.minecraft.network.codec;
            public interface StreamCodec<B, V> {
                void encode(B buffer, V value);
            }
            """.trimIndent()
        )
        val stack = writeDraftJava(
            sources,
            "net/minecraft/world/item/ItemStack.java",
            """
            package net.minecraft.world.item;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.network.codec.StreamCodec;
            public class ItemStack {
                public static final StreamCodec<RegistryFriendlyByteBuf, ItemStack>
                    OPTIONAL_STREAM_CODEC = (buffer, value) -> buffer.encodes++;
            }
            """.trimIndent()
        )
        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val result = ToolProvider.getSystemJavaCompiler().run(
            null,
            diagnostics,
            diagnostics,
            "-d",
            classes.toString(),
            buffer.toString(),
            codec.toString(),
            stack.toString(),
            probe.toString()
        )
        assertEquals(0, result, diagnostics.toString())
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("example.Probe").getMethod("check").invoke(null)
        }
    }

    @Test
    fun `unknown elements non-buffer receivers and reassigned buffers are rejected`() {
        val source = """
            package example;

            import java.util.List;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.item.ItemStack;

            final class Packet {
                void unknown(List<?> values, RegistryFriendlyByteBuf buffer) {
                    values.forEach(buffer::writeItem);
                }

                void other(List<ItemStack> values, OtherWriter buffer) {
                    values.forEach(buffer::writeItem);
                }

                void reassigned(List<ItemStack> values, RegistryFriendlyByteBuf buffer) {
                    buffer = replacement();
                    values.forEach(buffer::writeItem);
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateRegistryFriendlyStreamCodecReferences(source)

        assertEquals(source, migrated)
    }

    @Test
    fun `conflicting registry buffer simple name rejects friendly buffer widening`() {
        val source = """
            package example;

            import java.util.List;
            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.fluids.FluidStack;

            final class RegistryFriendlyByteBuf {
            }

            final class Packet {
                void write(List<FluidStack> values, FriendlyByteBuf buffer) {
                    values.forEach(buffer::writeFluidStack);
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateRegistryFriendlyStreamCodecReferences(source)

        assertEquals(source, migrated)
    }

    @Test
    fun `stream codec reference migration is idempotent`() {
        val source = """
            package example;

            import java.util.List;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.item.ItemStack;

            final class Packet {
                void write(List<ItemStack> values, RegistryFriendlyByteBuf buffer) {
                    values.forEach(buffer::writeItem);
                }
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        val once = pass.migrateRegistryFriendlyStreamCodecReferences(source)
        assertEquals(once, pass.migrateRegistryFriendlyStreamCodecReferences(once))
    }
}

private fun writeDraftJava(root: Path, relative: String, source: String): Path {
    val file = root.resolve(relative)
    file.parent.createDirectories()
    file.writeText(source)
    return file
}
