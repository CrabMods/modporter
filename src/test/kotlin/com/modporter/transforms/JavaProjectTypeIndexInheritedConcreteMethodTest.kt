package com.modporter.transforms

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaProjectTypeIndexInheritedConcreteMethodTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `only proven public concrete class implementations suppress equivalent interface methods`() {
        val file = tempDir.resolve("src/main/java/sample/Family.java")
        file.parent.createDirectories()
        file.writeText("""
            package sample;
            interface Contract {
                int value();
                default int defaultValue() { return 1; }
                void arrays(String... values);
                void overload(Integer value);
                int hidden();
                int packageOnly();
                int staticValue();
                int abstractValue();
            }
            abstract class Parent {
                public int value() { return 2; }
                public int defaultValue() { return 3; }
                public void arrays(String[] values) {}
                public void overload(String value) {}
                private int hidden() { return 4; }
                int packageOnly() { return 5; }
                public static int staticValue() { return 6; }
                public abstract int abstractValue();
            }
            abstract class Child extends Parent implements Contract {}
            abstract class Unrelated extends Parent {}
        """.trimIndent())
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        fun method(owner: String, name: String): MethodDeclaration = unit
            .findAll(ClassOrInterfaceDeclaration::class.java)
            .single { it.nameAsString == owner }
            .methods.single { it.nameAsString == name }
        fun suppresses(name: String, receiver: String = "sample.Child"): Boolean =
            index.concreteClassMethodSuppressesInterfaceMethod(
                receiver, method("Parent", name), method("Contract", name)
            )

        assertTrue(suppresses("value"))
        assertTrue(suppresses("defaultValue"))
        assertTrue(suppresses("arrays"))
        listOf("overload", "hidden", "packageOnly", "staticValue", "abstractValue").forEach {
            assertFalse(suppresses(it), "Must retain the interface candidate for $it")
        }
        assertFalse(suppresses("value", "sample.Unrelated"))
        assertFalse(suppresses("value", "sample.Contract"))
        assertFalse(suppresses("value", "missing.Receiver"))
        assertFalse(index.concreteClassMethodSuppressesInterfaceMethod(
            "sample.Child", method("Contract", "defaultValue"), method("Contract", "defaultValue")
        ))
    }
}
