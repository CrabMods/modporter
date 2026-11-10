package com.modporter.transforms

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import com.modporter.core.transforms.structural.exactEnclosingNamedClass
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JavaProjectTypeIndexErasedDeclarationParameterTypesTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `declaration erasure preserves array shapes and resolves only proven type variables`() {
        val source = """
            package sample;

            import java.util.List;

            class SignatureShapes<T, U extends Number, V extends U, W extends Missing> {
                void ownerScalar(T value) {}
                void ownerArray(T[] value) {}
                void ownerVarargs(T... value) {}
                void ownerArrayVarargs(T[]... value) {}
                void ownerTwoDimensional(T[][] value) {}
                void ownerBound(U value) {}
                void ownerChained(V value) {}
                void ownerUnresolvedBound(W value) {}
                <T> void methodUnbounded(T value) {}
                <T extends CharSequence> void methodBound(T value) {}
                <X extends Number & Comparable<X>> void methodIntersection(X value) {}
                <A extends B, B extends A> void methodCycle(A value) {}
                void concreteGeneric(List<Missing> values) {}
                void primitiveArrays(int[][] values) {}
                void unknown(Missing value) {}
            }

            class BoundedShadow<T extends Number, U extends T> {
                <Number> void boundedShadowDirect(T value) {}
                <T> void boundedShadowChained(U value) {}

                static class Number {}
            }
        """.trimIndent()
        val file = write("sample/SignatureShapes.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)

        assertEquals(listOf("java.lang.Object"), signature(index, unit, "ownerScalar"))
        assertEquals(listOf("java.lang.Object[]"), signature(index, unit, "ownerArray"))
        assertEquals(listOf("java.lang.Object[]"), signature(index, unit, "ownerVarargs"))
        assertEquals(listOf("java.lang.Object[][]"), signature(index, unit, "ownerArrayVarargs"))
        assertEquals(listOf("java.lang.Object[][]"), signature(index, unit, "ownerTwoDimensional"))
        assertEquals(listOf("java.lang.Number"), signature(index, unit, "ownerBound"))
        assertEquals(listOf("java.lang.Number"), signature(index, unit, "ownerChained"))
        assertEquals(listOf("java.lang.Number"), signature(index, unit, "boundedShadowDirect"))
        assertEquals(listOf("java.lang.Number"), signature(index, unit, "boundedShadowChained"))
        assertEquals(listOf("java.lang.Object"), signature(index, unit, "methodUnbounded"))
        assertEquals(listOf("java.lang.CharSequence"), signature(index, unit, "methodBound"))
        assertEquals(listOf("java.lang.Number"), signature(index, unit, "methodIntersection"))
        assertEquals(listOf("java.util.List"), signature(index, unit, "concreteGeneric"))
        assertEquals(listOf("int[][]"), signature(index, unit, "primitiveArrays"))
        assertNull(signature(index, unit, "ownerUnresolvedBound"))
        assertNull(signature(index, unit, "methodCycle"))
        assertNull(signature(index, unit, "unknown"))

        val ownerScalar = method(unit, "ownerScalar")
        val ownerArray = method(unit, "ownerArray")
        val unknown = method(unit, "unknown")
        assertNull(index.declaredType(ownerScalar.parameters.single().type, ownerScalar))
        assertNull(index.declaredType(ownerArray.parameters.single().type, ownerArray))
        assertNull(index.declaredType(unknown.parameters.single().type, unknown))
        assertEquals(source, file.readText())
    }

    @Test
    fun `enum constant class bodies have no named owner but constructor arguments retain the enum owner`() {
        val source = """
            package sample;

            enum OwnerBoundary {
                BODY(argument()) {
                    @Override
                    int bodyValue() {
                        return helper();
                    }
                },
                PLAIN(argument()) {
                    @Override
                    int bodyValue() {
                        return helper();
                    }
                };

                OwnerBoundary(int value) {}

                static int argument() {
                    return 1;
                }

                int helper() {
                    return 2;
                }

                abstract int bodyValue();
            }
        """.trimIndent()
        val file = write("sample/OwnerBoundary.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val constant = unit.findAll(EnumConstantDeclaration::class.java)
            .single { it.nameAsString == "BODY" }
        val constructorArgumentCall = constant.arguments.single() as MethodCallExpr
        val bodyMethod = constant.classBody.filterIsInstance<MethodDeclaration>().single()
        val bodyCall = bodyMethod.findAll(MethodCallExpr::class.java).single()

        assertEquals("OwnerBoundary", exactEnclosingNamedClass(constructorArgumentCall)?.nameAsString)
        assertNull(exactEnclosingNamedClass(bodyCall))
        assertNull(index.erasedDeclarationParameterTypes(bodyMethod))
        assertEquals(source, file.readText())
    }

    private fun signature(
        index: JavaProjectTypeIndex,
        unit: CompilationUnit,
        methodName: String
    ): List<String>? = index.erasedDeclarationParameterTypes(method(unit, methodName))

    private fun method(unit: CompilationUnit, name: String): MethodDeclaration =
        unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == name }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
