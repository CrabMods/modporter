package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.enclosingMethodRange
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnclosingMethodRangeSourceCacheTest {
    @Test
    fun `method body queries preserve nested offsets source edits collisions and rollback`() {
        val original = """
            class FB {
                void first() {
                    int outerMarker;
                    class Inner {
                        void nested() { int innerMarker; }
                    }
                }
                void second() { int secondMarker; }
            }
        """.trimIndent()
        val collision = original.replace("FB", "Ea")
        assertEquals(original.hashCode(), collision.hashCode())
        val pass = StructuralRefactorPass()
        for (source in listOf(original, collision, "\n\n" + collision, original)) {
            for ((marker, header) in listOf("outerMarker" to "void first()", "innerMarker" to "void nested()", "secondMarker" to "void second()")) {
                val start = source.indexOf('{', source.indexOf(header))
                val end = if (marker == "outerMarker") source.indexOf("\n    }", start) + 5 else source.indexOf('}', start)
                assertEquals((start + 1) until end, pass.enclosingMethodRange(source, source.indexOf(marker)))
            }
            assertNull(pass.enclosingMethodRange(source, source.indexOf("class")))
        }
    }

    @Test
    fun `concurrent source replacement never crosses method body ranges`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(0, 11).map { padding ->
                Callable {
                    val pass = StructuralRefactorPass()
                    repeat(40) { iteration ->
                        val source = " ".repeat(padding + iteration) + "class C {\n void run() { int marker; }\n}"
                        val start = source.indexOf('{', source.indexOf("void run"))
                        val end = source.indexOf('}', start)
                        assertEquals((start + 1) until end, pass.enclosingMethodRange(source, source.indexOf("marker")))
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
