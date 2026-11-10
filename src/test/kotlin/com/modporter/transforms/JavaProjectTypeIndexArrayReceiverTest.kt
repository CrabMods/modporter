package com.modporter.transforms

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaProjectTypeIndexArrayReceiverTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Create varargs item handlers retain their declared array receiver type`() {
        val source = """
            package sample;

            import net.neoforged.neoforge.items.CombinedInvWrapper;
            import net.neoforged.neoforge.items.IItemHandlerModifiable;

            public class SameSizeCombinedInvWrapper extends CombinedInvWrapper {
                private SameSizeCombinedInvWrapper(int numSlotsPerInv,
                                                   IItemHandlerModifiable... itemHandler) {
                    super(itemHandler);
                }

                public static CombinedInvWrapper create(IItemHandlerModifiable... itemHandler) {
                    if (itemHandler.length == 0)
                        return new CombinedInvWrapper(itemHandler);

                    int firstInvNumSlots = itemHandler[0].getSlots();
                    for (int i = 1; i < itemHandler.length; i++) {
                        if (firstInvNumSlots != itemHandler[i].getSlots())
                            return new CombinedInvWrapper(itemHandler);
                    }
                    return new SameSizeCombinedInvWrapper(firstInvNumSlots, itemHandler);
                }
            }
        """.trimIndent()
        val file = write("sample/SameSizeCombinedInvWrapper.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val receiverCalls = method(unit, "create").findAll(MethodCallExpr::class.java)
            .filter { it.nameAsString == "getSlots" }

        assertEquals(2, receiverCalls.size)
        receiverCalls.forEach { call ->
            assertEquals(ITEM_HANDLER, index.methodCallReceiverType(call))
        }
        assertEquals(source, file.readText())
    }

    @Test
    fun `ordinary arrays and array varargs preserve every declared dimension`() {
        val file = write("sample/ArrayShapes.java", """
            package sample;

            import net.neoforged.neoforge.items.IItemHandlerModifiable;

            class ArrayShapes {
                private String itemHandler;
                private String localHandler;

                void ordinary(IItemHandlerModifiable[] itemHandler) {
                    itemHandler[0].getSlots();
                }

                <T extends IItemHandlerModifiable> void nested(T[]... itemHandler) {
                    itemHandler[0][0].getSlots();
                }

                void parameterShadowsScalar(IItemHandlerModifiable... itemHandler) {
                    itemHandler[0].getSlots();
                }

                void localShadowsScalar() {
                    IItemHandlerModifiable[] localHandler = null;
                    localHandler[0].getSlots();
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)

        listOf("ordinary", "nested", "parameterShadowsScalar", "localShadowsScalar").forEach { name ->
            assertEquals(ITEM_HANDLER, index.methodCallReceiverType(getSlotsCall(unit, name)))
        }

        val nested = method(unit, "nested")
        val accesses = nested.findAll(ArrayAccessExpr::class.java).associateBy { it.toString() }
        val once = accesses.getValue("itemHandler[0]")
        val twice = accesses.getValue("itemHandler[0][0]")
        assertNull(index.expressionType(once, once))
        assertEquals(ITEM_HANDLER, index.expressionType(twice, twice))
    }

    @Test
    fun `scalar shadows refuse array access and unknown names stay unresolved`() {
        val file = write("sample/UnsafeArrays.java", """
            package sample;

            import net.neoforged.neoforge.items.IItemHandlerModifiable;

            class UnsafeArrays {
                private IItemHandlerModifiable[] itemHandler;

                void parameter(String itemHandler) {
                    itemHandler[0].getSlots();
                }

                void local() {
                    String itemHandler = "";
                    itemHandler[0].getSlots();
                }

                void unknown() {
                    missing[0].getSlots();
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)

        listOf("parameter", "local").forEach { name ->
            val failure = assertFailsWith<IllegalStateException> {
                index.methodCallReceiverType(getSlotsCall(unit, name))
            }
            assertTrue(
                failure.message?.contains("has a non-array declared receiver") == true,
                failure.message.orEmpty()
            )
        }
        assertNull(index.methodCallReceiverType(getSlotsCall(unit, "unknown")))
    }

    private fun getSlotsCall(unit: CompilationUnit, methodName: String): MethodCallExpr =
        method(unit, methodName).findAll(MethodCallExpr::class.java)
            .single { it.nameAsString == "getSlots" }

    private fun method(unit: CompilationUnit, name: String): MethodDeclaration =
        unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == name }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }

    private companion object {
        const val ITEM_HANDLER = "net.neoforged.neoforge.items.IItemHandlerModifiable"
    }
}