package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class LazyOptionalLifecycleTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `generated optional preserves cast identity and fires invalidation listeners once`() {
        val sources = directory.resolve("example").createDirectories()
        val shim = sources.resolve("LazyOptional.java")
        shim.writeText(BuildSystemPass.legacyLazyOptionalShim("example"))
        val probe = sources.resolve("Probe.java")
        probe.writeText("""
            package example;
            import java.util.concurrent.atomic.AtomicInteger;
            import java.util.function.Consumer;
            public class Probe {
                public static void check() {
                    AtomicInteger calls = new AtomicInteger(), notices = new AtomicInteger();
                    LazyOptional<String> value = LazyOptional.of(() -> { calls.incrementAndGet(); return "stored"; });
                    if ((Object) value.cast() != value) throw new AssertionError("cast lost optional identity");
                    Consumer<LazyOptional<String>> listener = current -> {
                        if (current != value || current.isPresent()) throw new AssertionError("invalidation state or identity");
                        notices.incrementAndGet();
                    };
                    Consumer<LazyOptional<String>> removed = current -> { throw new AssertionError("removed listener fired"); };
                    value.addListener(listener);
                    value.addListener(listener);
                    value.addListener(removed);
                    value.removeListener(removed);
                    value.invalidate();
                    value.invalidate();
                    if (calls.get() != 1 || notices.get() != 1) throw new AssertionError("supplier or notification multiplicity");
                    value.addListener(listener);
                    if (notices.get() != 2) throw new AssertionError("invalid optional must notify immediately");
                    AtomicInteger empty = new AtomicInteger();
                    LazyOptional.empty().addListener(current -> empty.incrementAndGet());
                    if (empty.get() != 1) throw new AssertionError("empty optional must notify immediately");
                }
            }
        """.trimIndent())
        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, "-d", classes.toString(), shim.toString(), probe.toString()), diagnostics.toString())
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("example.Probe").getMethod("check").invoke(null)
        }
    }
}
