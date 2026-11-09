package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyItemStackMethodReferences
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

class LegacyItemStackMethodReferencesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `unbound item stack predicates become component presence lambdas`() {
        val source = """
            package example;

            import java.util.function.Predicate;
            import net.minecraft.world.item.ItemStack;

            class StackPredicates {
                Predicate<ItemStack> edible = ItemStack::isEdible;
                Predicate<ItemStack> named =
                    net.minecraft.world.item.ItemStack::hasCustomHoverName;
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateLegacyItemStackMethodReferences(source)

        assertTrue(
            migrated.contains(
                "modporterStack -> modporterStack.has(net.minecraft.core.component.DataComponents.FOOD)"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "modporterStack -> " +
                    "modporterStack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)"
            ),
            migrated
        )
        assertFalse(migrated.contains("::isEdible"), migrated)
        assertFalse(migrated.contains("::hasCustomHoverName"), migrated)
    }

    @Test
    fun `bound hover name setter captures receiver once and retains legacy return type`() {
        val source = """
            package example;

            import java.util.function.Consumer;
            import java.util.function.Function;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.ItemStack;

            class StackSetters {
                private ItemStack field;

                Consumer<Component> local(ItemStack stack) {
                    return stack::setHoverName;
                }

                Function<Component, ItemStack> returning(ItemStack stack) {
                    return stack::setHoverName;
                }

                Consumer<Component> field() {
                    return this.field::setHoverName;
                }

                Consumer<Component> getter() {
                    return getStack()::setHoverName;
                }

                private ItemStack getStack() {
                    return field;
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateLegacyItemStackMethodReferences(source)

        assertTrue(
            migrated.contains(
                "private final net.minecraft.world.item.ItemStack modporterStack = " +
                    "java.util.Objects.requireNonNull(stack);"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "private final net.minecraft.world.item.ItemStack modporterStack = " +
                    "java.util.Objects.requireNonNull(StackSetters.this.field);"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "modporterStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, modporterName);"
            ),
            migrated
        )
        assertTrue(migrated.contains("return modporterStack;"), migrated)
        assertTrue(migrated.contains("}::modporterSetHoverName"), migrated)
        assertTrue(migrated.contains("getStack()::setHoverName"), migrated)
        assertFalse(migrated.contains("stack::setHoverName"), migrated)
        assertFalse(migrated.contains("this.field::setHoverName"), migrated)
    }

    @Test
    fun `generated bound adapters preserve capture timing and functional compatibility`() {
        val original = """
            package example;

            import java.util.function.Consumer;
            import java.util.function.Function;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.ItemStack;

            public class Probe {
                private ItemStack field;

                private Probe(ItemStack field) {
                    this.field = field;
                }

                private static Consumer<Component> local(ItemStack stack) {
                    return stack::setHoverName;
                }

                private static Function<Component, ItemStack> returning(ItemStack stack) {
                    return stack::setHoverName;
                }

                private Consumer<Component> fieldSetter() {
                    return this.field::setHoverName;
                }

                public static void check() {
                    Component one = new Component("one");
                    Component two = new Component("two");
                    Component three = new Component("three");
                    ItemStack first = new ItemStack();
                    ItemStack second = new ItemStack();

                    local(first).accept(one);
                    if (first.customName() != one)
                        throw new AssertionError("Consumer did not set the captured local receiver");

                    if (returning(first).apply(two) != first || first.customName() != two)
                        throw new AssertionError("Function lost the legacy ItemStack return");

                    Probe probe = new Probe(first);
                    Consumer<Component> capturedField = probe.fieldSetter();
                    probe.field = second;
                    capturedField.accept(three);
                    if (first.customName() != three || second.customName() != null)
                        throw new AssertionError("field receiver was evaluated after reference creation");

                    try {
                        local(null);
                        throw new AssertionError("null local receiver did not fail at reference creation");
                    } catch (NullPointerException expected) {
                    }

                    probe.field = null;
                    try {
                        probe.fieldSetter();
                        throw new AssertionError("null field receiver did not fail at reference creation");
                    } catch (NullPointerException expected) {
                    }
                }
            }
        """.trimIndent()
        val migrated = StructuralRefactorPass().migrateLegacyItemStackMethodReferences(original)
        assertFalse(migrated.contains("::setHoverName"), migrated)
        assertTrue(migrated.contains("Probe.this.field"), migrated)

        val sources = directory.resolve("sources")
        val probe = writeJava(sources, "example/Probe.java", migrated)
        val componentType = writeJava(
            sources,
            "net/minecraft/core/component/DataComponentType.java",
            """
            package net.minecraft.core.component;
            public final class DataComponentType<T> {
            }
            """.trimIndent()
        )
        val dataComponents = writeJava(
            sources,
            "net/minecraft/core/component/DataComponents.java",
            """
            package net.minecraft.core.component;
            import net.minecraft.network.chat.Component;
            public final class DataComponents {
                public static final DataComponentType<Component> CUSTOM_NAME = new DataComponentType<>();
            }
            """.trimIndent()
        )
        val component = writeJava(
            sources,
            "net/minecraft/network/chat/Component.java",
            """
            package net.minecraft.network.chat;
            public record Component(String value) {
            }
            """.trimIndent()
        )
        val itemStack = writeJava(
            sources,
            "net/minecraft/world/item/ItemStack.java",
            """
            package net.minecraft.world.item;
            import net.minecraft.core.component.DataComponentType;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.network.chat.Component;
            public class ItemStack {
                private Component customName;
                @SuppressWarnings("unchecked")
                public <T> T set(DataComponentType<? super T> type, T value) {
                    if ((Object) type != DataComponents.CUSTOM_NAME)
                        return null;
                    Component previous = customName;
                    customName = (Component) value;
                    return (T) previous;
                }
                public Component customName() {
                    return customName;
                }
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
            componentType.toString(),
            dataComponents.toString(),
            component.toString(),
            itemStack.toString(),
            probe.toString(),
        )
        assertEquals(0, result, diagnostics.toString())
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("example.Probe").getMethod("check").invoke(null)
        }
    }

    @Test
    fun `same named methods on other source types remain untouched`() {
        val source = """
            package example;

            import java.util.function.Predicate;

            class OtherStack {
                boolean isEdible() {
                    return true;
                }
            }

            class OtherPredicates {
                Predicate<OtherStack> edible = OtherStack::isEdible;
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateLegacyItemStackMethodReferences(source)

        assertTrue(migrated.contains("OtherStack::isEdible"), migrated)
        assertFalse(migrated.contains("DataComponents.FOOD"), migrated)
    }

    private fun writeJava(root: Path, relative: String, source: String): Path =
        root.resolve(relative).also {
            it.parent.createDirectories()
            it.writeText(source)
        }
}
