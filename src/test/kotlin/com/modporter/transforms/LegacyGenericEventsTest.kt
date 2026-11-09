package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyGenericEventsTest {
    @TempDir lateinit var tempDir: Path

    private fun source(path: String, text: String): Path = tempDir.resolve("src/main/java/$path").also {
        it.parent.createDirectories()
        it.writeText(text.trimIndent())
    }

    private fun mod() = source("com/example/Example.java", """
        package com.example;
        import net.neoforged.fml.common.Mod;
        @Mod("example")
        public class Example {}
    """)

    @Test
    fun `a generic event remains a target event and retains its runtime type token`() {
        mod()
        val signal = source("com/example/Signal.java", """
            package com.example;
            import net.neoforged.bus.api.GenericEvent;
            public class Signal extends GenericEvent<String> {
                public Signal() {
                    super(String.class);
                }
            }
        """)
        val result = StructuralRefactorPass().apply(tempDir)
        assertTrue(result.errors.isEmpty(), result.errors.joinToString())
        val generated = Files.walk(tempDir.resolve("src/main/java/com/modporter/generated")).use { stream ->
            stream.filter { it.fileName.toString() in setOf("GenericEvent.java", "IGenericEvent.java") }.toList()
        }
        assertEquals(2, generated.size)
        val event = source("net/neoforged/bus/api/Event.java", """
            package net.neoforged.bus.api;
            public abstract class Event {}
        """)
        val classes = tempDir.resolve("classes").createDirectories()
        val code = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classes.toString(), *(generated + listOf(event, signal)).map { it.toString() }.toTypedArray())
        assertEquals(0, code)
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("com.example.Signal")
            val instance = type.getConstructor().newInstance()
            assertEquals(String::class.java, type.getMethod("getGenericType").invoke(instance))
            assertTrue(loader.loadClass("net.neoforged.bus.api.Event").isInstance(instance))
        }
    }

    @Test
    fun `generic listener filtering is reported rather than discarded`() {
        mod()
        val signal = source("com/example/Signal.java", """
            package com.example;
            import net.neoforged.bus.api.GenericEvent;
            public class Signal extends GenericEvent<String> {
                void listen() {
                    bus.addGenericListener(String.class, this::receive);
                }
            }
        """)
        val result = StructuralRefactorPass().apply(tempDir)
        assertTrue(result.errors.any { it.contains("Generic event listener filters require explicit migration") }, result.errors.joinToString())
        assertTrue(signal.readText().contains("import net.neoforged.bus.api.GenericEvent;"))
    }
}
