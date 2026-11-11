package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.collectJavaClassDeclarations
import com.modporter.core.transforms.structural.enclosingJavaClassDeclaration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaClassDeclarationSourceCacheTest {
    @Test
    fun `class ranges and owners follow source changes hash collisions and rollback`() {
        val first = "class FB {\n    class Inner extends Before {\n        int marker;\n    }\n}"
        val collision = "class Ea {\n    class Inner extends Before {\n        int marker;\n    }\n}"
        assertEquals(first.hashCode(), collision.hashCode())
        val changed = "\n\n" + collision.replace("Before", "After")
        val pass = StructuralRefactorPass()
        for (source in listOf(first, collision, changed, first)) {
            val expectedOwner = if (source.contains("class FB")) "FB" else "Ea"
            val declarations = pass.collectJavaClassDeclarations(source)
            assertEquals(listOf(expectedOwner, "Inner"), declarations.map { it.name })
            assertEquals(if (source.contains("Before")) "Before" else "After", declarations.last().directSuper)
            val marker = source.indexOf("marker")
            assertEquals("Inner", pass.enclosingJavaClassDeclaration(source, marker)?.name)
            assertEquals(source.indexOf('{'), declarations.first().bodyRange.first)
        }
    }

    @Test
    fun `independent source queries remain correct during concurrent cache replacement`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf("First", "Second").map { name ->
                Callable {
                    val pass = StructuralRefactorPass()
                    repeat(100) {
                        val source = "class $name {\n    class Nested$it {\n        int marker;\n    }\n}"
                        assertEquals(listOf(name, "Nested$it"), pass.collectJavaClassDeclarations(source).map { it.name })
                        assertEquals("Nested$it", pass.enclosingJavaClassDeclaration(source, source.indexOf("marker"))?.name)
                    }
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
