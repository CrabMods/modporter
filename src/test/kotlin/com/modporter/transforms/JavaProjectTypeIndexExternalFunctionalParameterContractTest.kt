package com.modporter.transforms

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaProjectTypeIndexExternalFunctionalParameterContractTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `pinned Catnip Couple callbacks bind only their proven receiver element parameter`() {
        val file = write("sample/CoupleCallbacks.java", """
            package sample;

            import net.createmod.catnip.data.Couple;

            class TravellingPoint {
                void write() {
                }
            }

            class TrackNodeLocation {
                void write() {
                }
            }

            class TrackNode {
                TrackNodeLocation getLocation() {
                    return null;
                }
            }

            class CoupleCallbacks {
                Couple<TravellingPoint> points;
                Couple<TrackNode> nodes;

                void serialize() {
                    points.serializeEach(tp -> { tp.write(); return null; });
                }

                void contextual() {
                    points.forEachWithContext((tp, first) -> {
                        tp.write();
                        first.toString();
                    });
                }

                void parameterized(Couple<Integer> weights) {
                    points.forEachWithParams((tp, weight) -> tp.write(), weights);
                }

                void mapped() {
                    nodes.map(TrackNode::getLocation)
                        .serializeEach(location -> { location.write(); return null; });
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)

        listOf("serialize", "contextual", "parameterized").forEach { methodName ->
            val receiver = callReceiver(unit, methodName, "write")
            assertEquals("sample.TravellingPoint", index.expressionType(receiver, receiver), methodName)
        }
        val mapped = callReceiver(unit, "mapped", "write")
        assertEquals("sample.TrackNodeLocation", index.expressionType(mapped, mapped))

        val context = callReceiver(unit, "contextual", "toString")
        assertNull(index.expressionType(context, context), "unmapped Boolean context must remain unsupported")
    }

    @Test
    fun `pinned Catnip NBTHelper infers iterable elements through exact JDK map carriers`() {
        val file = write("sample/NBTHelperCallbacks.java", """
            package sample;

            import java.util.List;
            import java.util.Map;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.nbt.CompoundTag;

            class Value {
                CompoundTag write() {
                    return null;
                }
            }

            class NBTHelperCallbacks {
                List<Value> values;
                Map<String, Value> mapped;
                Map<String, List<Value>> grouped;

                void list() {
                    NBTHelper.writeCompoundList(values, value -> value.write());
                }

                void mapValues() {
                    NBTHelper.writeCompoundList(mapped.values(), value -> value.write());
                }

                void entryValues() {
                    NBTHelper.writeCompoundList(grouped.entrySet(), entry -> {
                        NBTHelper.writeCompoundList(entry.getValue(), value -> value.write());
                        return new CompoundTag();
                    });
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)

        listOf("list", "mapValues", "entryValues").forEach { methodName ->
            val receiver = callReceiver(unit, methodName, "write")
            assertEquals("sample.Value", index.expressionType(receiver, receiver), methodName)
        }
    }

    @Test
    fun `project sources shadow pinned external callback contracts`() {
        write("net/createmod/catnip/data/Couple.java", """
            package net.createmod.catnip.data;

            import java.util.function.Function;

            public class Couple<T> {
                public void serializeEach(Function<String, String> callback) {
                }
            }
        """.trimIndent())
        write("net/createmod/catnip/nbt/NBTHelper.java", """
            package net.createmod.catnip.nbt;

            import java.util.List;
            import java.util.function.Function;

            public class NBTHelper {
                public static void writeCompoundList(List<Integer> values, Function<String, String> callback) {
                }
            }
        """.trimIndent())
        val file = write("sample/ProjectShadowUsage.java", """
            package sample;

            import java.util.List;
            import net.createmod.catnip.data.Couple;
            import net.createmod.catnip.nbt.NBTHelper;

            class ProjectShadowUsage {
                Couple<Integer> values;
                List<Integer> numbers;

                void couple() {
                    values.serializeEach(text -> text.trim());
                }

                void helper() {
                    NBTHelper.writeCompoundList(numbers, text -> text.trim());
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)

        listOf("couple", "helper").forEach { methodName ->
            val receiver = callReceiver(unit, methodName, "trim")
            assertEquals("java.lang.String", index.expressionType(receiver, receiver), methodName)
        }
    }

    @Test
    fun `project source callback ambiguity is not hidden by pinned contracts`() {
        write("net/createmod/catnip/data/Couple.java", """
            package net.createmod.catnip.data;

            import java.util.function.Consumer;
            import java.util.function.Function;

            public class Couple<T> {
                public void serializeEach(Function<String, String> callback) {
                }

                public void serializeEach(Consumer<Integer> callback) {
                }
            }
        """.trimIndent())
        val file = write("sample/AmbiguousProjectCoupleUsage.java", """
            package sample;

            import net.createmod.catnip.data.Couple;

            class AmbiguousProjectCoupleUsage {
                Couple<Long> values;

                void serialize() {
                    values.serializeEach(value -> value.toString());
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)
        val receiver = callReceiver(unit, "serialize", "toString")

        val error = assertFailsWith<IllegalStateException> {
            index.expressionType(receiver, receiver)
        }
        assertTrue(error.message.orEmpty().contains("Ambiguous functional parameter type"), error.message)
    }

    @Test
    fun `raw external generic carriers refuse invented callback element types`() {
        val file = write("sample/RawExternalUsage.java", """
            package sample;

            import java.util.List;
            import net.createmod.catnip.data.Couple;
            import net.createmod.catnip.nbt.NBTHelper;

            class RawExternalUsage {
                Couple couple;
                List values;

                void couple() {
                    couple.serializeEach(value -> value.toString());
                }

                void helper() {
                    NBTHelper.writeCompoundList(values, value -> value.toString());
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)

        val couple = callReceiver(unit, "couple", "toString")
        val coupleError = assertFailsWith<IllegalStateException> {
            index.expressionType(couple, couple)
        }
        assertTrue(coupleError.message.orEmpty().contains("Raw net.createmod.catnip.data.Couple"), coupleError.message)

        val helper = callReceiver(unit, "helper", "toString")
        val helperError = assertFailsWith<IllegalStateException> {
            index.expressionType(helper, helper)
        }
        assertTrue(helperError.message.orEmpty().contains("Raw java.util.List"), helperError.message)
    }

    @Test
    fun `lookalike owners and mismatched external callback shapes remain unresolved`() {
        val file = write("sample/MismatchedExternalUsage.java", """
            package sample;

            import java.util.List;
            import net.createmod.catnip.data.Couple;
            import net.createmod.catnip.nbt.NBTHelper;

            class Value {
                void write() {
                }
            }

            class MismatchedExternalUsage {
                Couple<Value> points;
                List<Value> values;

                void wrongCoupleName() {
                    points.serializeAll(value -> value.write());
                }

                void wrongCoupleArity() {
                    points.serializeEach(value -> value.write(), true);
                }

                void wrongHelperName() {
                    NBTHelper.writeList(values, value -> value.write());
                }

                void wrongHelperArity() {
                    NBTHelper.writeCompoundList(values, value -> value.write(), true);
                }

                void wrongFunctionalArity() {
                    NBTHelper.writeCompoundList(values, (value, ignored) -> value.write());
                }
            }
        """.trimIndent())
        val lookalike = write("sample/LookalikeExternalUsage.java", """
            package sample;

            import java.util.List;
            import other.Couple;
            import other.NBTHelper;

            class LookalikeExternalUsage {
                Couple<Value> points;
                List<Value> values;

                void couple() {
                    points.serializeEach(value -> value.write());
                }

                void helper() {
                    NBTHelper.writeCompoundList(values, value -> value.write());
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)
        val lookalikeUnit = index.unit(lookalike)

        listOf(
            "wrongCoupleName",
            "wrongCoupleArity",
            "wrongHelperName",
            "wrongHelperArity",
            "wrongFunctionalArity"
        ).forEach { methodName ->
            val receiver = callReceiver(unit, methodName, "write")
            assertNull(index.expressionType(receiver, receiver), methodName)
        }
        listOf("couple", "helper").forEach { methodName ->
            val receiver = callReceiver(lookalikeUnit, methodName, "write")
            assertNull(index.expressionType(receiver, receiver), "lookalike $methodName")
        }
    }

    @Test
    fun `pinned Minecraft lists and buffer encoders preserve exact callback types`() {
        val file = write("sample/MinecraftCallbacks.java", """
            package sample;
            import java.util.List;
            import net.minecraft.core.NonNullList;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            class Value { void write() {} }
            class MinecraftCallbacks {
                NonNullList<Value> values;
                List<Value> collection;
                void list() { values.forEach(value -> value.write()); }
                void friendly(FriendlyByteBuf buffer) {
                    buffer.writeCollection(collection, (out, value) -> {
                        out.readNbt();
                        value.write();
                    });
                }
                void registry(RegistryFriendlyByteBuf buffer) {
                    buffer.writeCollection(collection, (out, value) -> {
                        out.readNbt();
                        value.write();
                    });
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)
        for (method in listOf("list", "friendly", "registry")) {
            val value = callReceiver(unit, method, "write")
            assertEquals("sample.Value", index.expressionType(value, value), method)
        }
        for (method in listOf("friendly", "registry")) {
            val buffer = callReceiver(unit, method, "readNbt")
            assertEquals("net.minecraft.network.FriendlyByteBuf", index.expressionType(buffer, buffer), method)
        }
    }

    @Test
    fun `Minecraft raw callback carriers never invent element types`() {
        val file = write("sample/RawMinecraftCallbacks.java", """
            package sample;
            import java.util.Collection;
            import net.minecraft.core.NonNullList;
            import net.minecraft.network.FriendlyByteBuf;
            class RawMinecraftCallbacks {
                NonNullList values;
                Collection collection;
                void list() { values.forEach(value -> value.toString()); }
                void buffer(FriendlyByteBuf buffer) {
                    buffer.writeCollection(collection, (out, value) -> value.toString());
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)
        for (method in listOf("list", "buffer")) {
            val receiver = callReceiver(unit, method, "toString")
            val failure = assertFailsWith<IllegalStateException> { index.expressionType(receiver, receiver) }
            assertTrue(failure.message.orEmpty().contains("Raw"), failure.message)
        }
    }

    @Test
    fun `Minecraft project source callback signatures override pinned facts`() {
        write("net/minecraft/core/NonNullList.java", """
            package net.minecraft.core;
            import java.util.function.Consumer;
            public class NonNullList<T> { public void forEach(Consumer<String> callback) {} }
        """.trimIndent())
        write("net/minecraft/network/FriendlyByteBuf.java", """
            package net.minecraft.network;
            import java.util.List;
            import java.util.function.BiConsumer;
            public class FriendlyByteBuf {
                public void writeCollection(List<Long> values, BiConsumer<Integer, String> callback) {}
            }
        """.trimIndent())
        val file = write("sample/ShadowMinecraftCallbacks.java", """
            package sample;
            import java.util.List;
            import net.minecraft.core.NonNullList;
            import net.minecraft.network.FriendlyByteBuf;
            class ShadowMinecraftCallbacks {
                NonNullList<Long> values;
                List<Long> collection;
                void list() { values.forEach(text -> text.trim()); }
                void buffer(FriendlyByteBuf buffer) {
                    buffer.writeCollection(collection, (number, text) -> {
                        number.intValue();
                        text.trim();
                    });
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)
        for (method in listOf("list", "buffer")) {
            val text = callReceiver(unit, method, "trim")
            assertEquals("java.lang.String", index.expressionType(text, text), "project shadow callback: $method")
        }
        val number = callReceiver(unit, "buffer", "intValue")
        assertEquals("java.lang.Integer", index.expressionType(number, number))
    }

    @Test
    fun `Minecraft callback names arities and collection bounds refuse unrelated shapes`() {
        val file = write("sample/WrongMinecraftCallbacks.java", """
            package sample;
            import java.lang.Iterable;
            import java.util.List;
            import net.minecraft.core.NonNullList;
            import net.minecraft.network.FriendlyByteBuf;
            class Value { void write() {} }
            class WrongMinecraftCallbacks {
                NonNullList<Value> values;
                List<Value> collection;
                Iterable<Value> notCollection;
                void listArity() { values.forEach(value -> value.write(), true); }
                void bufferArity(FriendlyByteBuf buffer) {
                    buffer.writeCollection(collection, (out, value) -> value.write(), true);
                }
                void functionalArity(FriendlyByteBuf buffer) {
                    buffer.writeCollection(collection, value -> value.write());
                }
                void wrongName(FriendlyByteBuf buffer) {
                    buffer.writeCollections(collection, (out, value) -> value.write());
                }
                void iterableOnly(FriendlyByteBuf buffer) {
                    buffer.writeCollection(notCollection, (out, value) -> value.write());
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val unit = index.unit(file)
        for (method in listOf("listArity", "bufferArity", "functionalArity", "wrongName", "iterableOnly")) {
            val receiver = callReceiver(unit, method, "write")
            assertNull(index.expressionType(receiver, receiver), method)
        }
    }

    @Test
    fun `pinned Guava immutable list supplies buffer element unless project source shadows it`() {
        val file = write("sample/ImmutableCallbacks.java", """
            package sample;
            import com.google.common.collect.ImmutableList;
            import net.minecraft.network.FriendlyByteBuf;
            class Value { void write() {} }
            class ImmutableCallbacks {
                ImmutableList<Value> values;
                void encode(FriendlyByteBuf buffer) {
                    buffer.writeCollection(values, (out, value) -> value.write());
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(sourceRoot())
        val receiver = callReceiver(index.unit(file), "encode", "write")
        assertEquals("sample.Value", index.expressionType(receiver, receiver))
        write("com/google/common/collect/ImmutableList.java", """
            package com.google.common.collect;
            public class ImmutableList<T> {}
        """.trimIndent())
        val shadowed = JavaProjectTypeIndex.build(sourceRoot())
        val shadowReceiver = callReceiver(shadowed.unit(file), "encode", "write")
        assertNull(shadowed.expressionType(shadowReceiver, shadowReceiver))
    }

    private fun sourceRoot(): Path = tempDir.resolve("src/main/java")

    private fun callReceiver(unit: CompilationUnit, methodName: String, calledName: String): NameExpr {
        val call = method(unit, methodName).findAll(MethodCallExpr::class.java)
            .single { it.nameAsString == calledName }
        return call.scope.orElseThrow() as NameExpr
    }

    private fun method(unit: CompilationUnit, name: String): MethodDeclaration =
        unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == name }

    private fun write(relative: String, source: String): Path {
        val file = sourceRoot().resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
