package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Public method and evaluation contracts from Forge 1.20.1-47.1.33 LazyOptional. */
class LazyOptionalContractTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `map and filter eagerly return independent java optionals`() {
        checkJava(
            """
            AtomicInteger resolutions = new AtomicInteger();
            AtomicInteger mappings = new AtomicInteger();
            LazyOptional<String> source = LazyOptional.of(() -> {
                resolutions.incrementAndGet();
                return "stored";
            });
            Optional<Integer> mapped = source.map(value -> {
                mappings.incrementAndGet();
                return value.length();
            });
            if (resolutions.get() != 1 || mappings.get() != 1 || mapped.orElse(-1) != 6)
                throw new AssertionError("map must resolve and map immediately");

            AtomicInteger predicates = new AtomicInteger();
            Optional<String> filtered = source.filter(value -> {
                predicates.incrementAndGet();
                return value.equals("stored");
            });
            if (predicates.get() != 1 || resolutions.get() != 1)
                throw new AssertionError("filter must run eagerly using the resolved value");
            source.invalidate();
            if (mapped.orElse(-1) != 6 || !filtered.orElse("").equals("stored"))
                throw new AssertionError("resolved java optionals must survive source invalidation");

            Optional<String> absent = LazyOptional.<String>empty().map(value -> {
                throw new AssertionError("empty map evaluated its callback");
            });
            Optional<String> rejected = LazyOptional.of(() -> "value").filter(value -> false);
            if (absent.isPresent() || rejected.isPresent())
                throw new AssertionError("empty map or rejected filter was present");
            """
        )
    }

    @Test
    fun `lazy map defers evaluation and does not inherit invalidation`() {
        checkJava(
            """
            AtomicInteger resolutions = new AtomicInteger();
            AtomicInteger mappings = new AtomicInteger();
            AtomicInteger notices = new AtomicInteger();
            LazyOptional<String> source = LazyOptional.of(() -> {
                resolutions.incrementAndGet();
                return "stored";
            });
            LazyOptional<Integer> mapped = source.lazyMap(value -> {
                mappings.incrementAndGet();
                return value.length();
            });
            mapped.addListener(ignored -> notices.incrementAndGet());
            if (!source.isPresent() || !mapped.isPresent() || resolutions.get() != 0 || mappings.get() != 0)
                throw new AssertionError("presence and listeners must not resolve lazy suppliers");
            if (mapped.orElse(-1) != 6 || resolutions.get() != 1 || mappings.get() != 1)
                throw new AssertionError("lazy map must resolve once on demand");
            source.invalidate();
            if (!mapped.isPresent() || mapped.orElse(-1) != 6 || notices.get() != 0)
                throw new AssertionError("lazyMap must not inherit the parent's invalidation");
            mapped.invalidate();
            mapped.invalidate();
            if (notices.get() != 1)
                throw new AssertionError("mapped optional must retain its own invalidation lifecycle");

            LazyOptional<String> unresolved = LazyOptional.of(() -> "later");
            LazyOptional<Integer> unresolvedMap = unresolved.lazyMap(String::length);
            unresolved.invalidate();
            if (!unresolvedMap.isPresent())
                throw new AssertionError("unresolved lazyMap inherited invalidation");
            try {
                unresolvedMap.resolve();
                throw new AssertionError("mapping an invalid source must refuse value resolution");
            } catch (IllegalStateException expected) {
                // Forge's lazyMap supplier resolves the parent with getValueUnsafe.
            }
            """
        )
    }

    @Test
    fun `empty identity and null callback refusals match the source contract`() {
        checkJava(
            """
            LazyOptional<String> empty = LazyOptional.empty();
            if ((Object) empty != (Object) LazyOptional.<Integer>empty())
                throw new AssertionError("empty must be a singleton across generic casts");
            if (LazyOptional.<String>of(null) != empty)
                throw new AssertionError("a null supplier must produce the empty singleton");
            AtomicInteger notices = new AtomicInteger();
            empty.addListener(ignored -> notices.incrementAndGet());
            if (empty.isPresent() || notices.get() != 1)
                throw new AssertionError("empty optional must notify immediately without a supplier");
            expectNullPointer(() -> empty.map(null));
            expectNullPointer(() -> empty.filter(null));
            expectNullPointer(() -> empty.lazyMap(null));
            expectNullPointer(() -> empty.ifPresent(null));
            expectNullPointer(() -> LazyOptional.of(() -> "value").map(value -> null));
            """
        )
    }

    private fun checkJava(body: String) {
        val sources = directory.resolve("example").createDirectories()
        val shim = sources.resolve("LazyOptional.java")
        shim.writeText(BuildSystemPass.legacyLazyOptionalShim("example"))
        val probe = sources.resolve("ContractProbe.java")
        probe.writeText(
            """
            package example;
            import java.util.Optional;
            import java.util.concurrent.atomic.AtomicInteger;
            public final class ContractProbe {
                public static void check() {
                    ${body.trimIndent()}
                }
                private static void expectNullPointer(Runnable action) {
                    try {
                        action.run();
                    } catch (NullPointerException expected) {
                        return;
                    }
                    throw new AssertionError("null callback/result must be rejected");
                }
            }
            """.trimIndent()
        )
        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(
            0,
            compiler.run(
                null, diagnostics, diagnostics, "-d", classes.toString(),
                shim.toString(), probe.toString()
            ),
            diagnostics.toString()
        )
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("example.ContractProbe").getMethod("check").invoke(null)
        }
    }
}
