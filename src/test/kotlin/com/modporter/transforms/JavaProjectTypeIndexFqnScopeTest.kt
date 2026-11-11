package com.modporter.transforms

import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNull

class JavaProjectTypeIndexFqnScopeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `an enclosing instance field prevents package head static class fallback`() {
        write("root/Factory.java", """
            package root;
            public class Factory {
                public static String value() { return "static"; }
            }
        """.trimIndent())
        val file = write("sample/Outer.java", """
            package sample;
            class FactoryValue {
                int value() { return 1; }
            }
            class RootValue {
                FactoryValue Factory;
            }
            class Outer {
                RootValue root;
                class Inner {
                    int read() { return root.Factory.value(); }
                }
            }
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val call = index.unit(file).findAll(MethodCallExpr::class.java).single()
        // The field chain is outside this index's modeled lexical values;
        // it must never be replaced by the unrelated root.Factory type.
        assertNull(index.methodCallReceiverType(call))
        assertNull(index.expressionType(call, call))
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
