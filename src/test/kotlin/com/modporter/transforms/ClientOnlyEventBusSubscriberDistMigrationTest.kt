package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateClientOnlyEventBusSubscriberDist
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ClientOnlyEventBusSubscriberDistMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `all client handlers gain class client distribution without body edits`() {
        val source = handlerSource("@EventBusSubscriber")
        val beforeMethods = methods(source)
        val changes = mutableListOf<Change>()
        val migrated = migrate(source, changes)

        assertEquals(
            source.replace(
                "@EventBusSubscriber",
                "@EventBusSubscriber(Dist.CLIENT)"
            ),
            migrated
        )
        assertEquals(beforeMethods, methods(migrated))
        assertEquals(1, changes.size)
        assertEquals(
            "struct-client-only-event-subscriber-dist",
            changes.single().ruleId
        )
        assertEquals(Confidence.HIGH, changes.single().confidence)
        assertEquals(migrated, migrate(migrated))

        val normal = handlerSource(
            "@EventBusSubscriber(" +
                "modid = \"demo\", bus = EventBusSubscriber.Bus.GAME)"
        )
        val normalMigrated = migrate(normal)
        assertTrue(
            normalMigrated.contains(
                "bus = EventBusSubscriber.Bus.GAME, " +
                    "value = Dist.CLIENT)"
            ),
            normalMigrated
        )
        assertEquals(methods(normal), methods(normalMigrated))
    }

    @Test
    fun `distribution metadata skips dedicated registration and client handlers still run`() {
        val migrated = migrate(handlerSource("@EventBusSubscriber"))
        assertTrue(migrated.contains("@EventBusSubscriber(Dist.CLIENT)"))

        val sources = mutableListOf<Path>()
        sources.add(writeJava(
            "net/neoforged/api/distmarker/Dist.java",
            """
                package net.neoforged.api.distmarker;
                public enum Dist { CLIENT, DEDICATED_SERVER }
            """
        ))
        sources.add(writeJava(
            "net/neoforged/api/distmarker/OnlyIn.java",
            """
                package net.neoforged.api.distmarker;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.METHOD})
                public @interface OnlyIn { Dist value(); }
            """
        ))
        sources.add(writeJava(
            "net/neoforged/bus/api/SubscribeEvent.java",
            """
                package net.neoforged.bus.api;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface SubscribeEvent {}
            """
        ))
        sources.add(writeJava(
            "net/neoforged/fml/common/EventBusSubscriber.java",
            """
                package net.neoforged.fml.common;
                import java.lang.annotation.*;
                import net.neoforged.api.distmarker.Dist;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface EventBusSubscriber {
                    Dist[] value() default {
                        Dist.CLIENT, Dist.DEDICATED_SERVER
                    };
                    String modid() default "";
                    Bus bus() default Bus.GAME;
                    enum Bus { GAME, MOD }
                }
            """
        ))
        sources.add(writeJava(
            "demo/Calls.java",
            """
                package demo;
                public final class Calls {
                    public static int count;
                }
            """
        ))
        sources.add(writeJava(
            "demo/ClientEvent.java",
            """
                package demo;
                public final class ClientEvent {}
            """
        ))
        sources.add(writeJava("demo/ClientHandlers.java", migrated))
        sources.add(writeJava(
            "probe/Probe.java",
            """
                package probe;
                import demo.Calls;
                import demo.ClientEvent;
                import demo.ClientHandlers;
                import java.lang.reflect.Method;
                import java.util.Arrays;
                import net.neoforged.api.distmarker.Dist;
                import net.neoforged.bus.api.SubscribeEvent;
                import net.neoforged.fml.common.EventBusSubscriber;
                public final class Probe {
                    private static void dispatch(Dist dist) throws Exception {
                        EventBusSubscriber metadata =
                            ClientHandlers.class.getAnnotation(
                                EventBusSubscriber.class);
                        if (!Arrays.asList(metadata.value()).contains(dist))
                            return;
                        for (Method method :
                                ClientHandlers.class.getDeclaredMethods()) {
                            if (method.isAnnotationPresent(
                                    SubscribeEvent.class))
                                method.invoke(null, new ClientEvent());
                        }
                    }
                    public static String verify() throws Exception {
                        EventBusSubscriber metadata =
                            ClientHandlers.class.getAnnotation(
                                EventBusSubscriber.class);
                        if (metadata.value().length != 1 ||
                                metadata.value()[0] != Dist.CLIENT)
                            throw new AssertionError(
                                "class distribution was not CLIENT only");
                        dispatch(Dist.DEDICATED_SERVER);
                        if (Calls.count != 0)
                            throw new AssertionError(
                                "dedicated registration invoked handlers");
                        dispatch(Dist.CLIENT);
                        if (Calls.count != 2)
                            throw new AssertionError(
                                "client handlers invoked " + Calls.count);
                        return "passed";
                    }
                }
            """
        ))

        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for subscriber semantics")
        val result = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-proc:none",
            "-d",
            classes.toString(),
            *sources.map(Path::toString).toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString(Charsets.UTF_8))
        URLClassLoader(
            arrayOf(classes.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        ).use { loader ->
            assertEquals(
                "passed",
                loader.loadClass("probe.Probe")
                    .getMethod("verify")
                    .invoke(null)
            )
        }
    }

    @Test
    fun `unknown mixed and stateful subscribers fail closed`() {
        val source = handlerSource("@EventBusSubscriber")
        val rejected = linkedMapOf(
            "documentation" to """
                package demo;
                // @EventBusSubscriber @SubscribeEvent
                // @OnlyIn(Dist.CLIENT)
                public final class Notes {}
            """.trimIndent(),
            "fake subscriber owner" to source.replace(
                "import net.neoforged.fml.common.EventBusSubscriber;",
                "import example.EventBusSubscriber;"
            ),
            "fake only-in owner" to source.replace(
                "import net.neoforged.api.distmarker.OnlyIn;",
                "import example.OnlyIn;"
            ),
            "shadow annotation type" to source.replace(
                "public class ClientHandlers {",
                """
                    public class ClientHandlers {
                        public @interface SubscribeEvent {}
                """.trimIndent()
            ),
            "dedicated class value" to source.replace(
                "@EventBusSubscriber",
                "@EventBusSubscriber(Dist.DEDICATED_SERVER)"
            ),
            "dedicated handler value" to source.replaceFirst(
                "@OnlyIn(Dist.CLIENT)",
                "@OnlyIn(Dist.DEDICATED_SERVER)"
            ),
            "mixed handler" to source.replaceFirst(
                "@OnlyIn(Dist.CLIENT)\n" +
                    "    public static void first",
                "public static void first"
            ),
            "instance handler" to source.replaceFirst(
                "public static void first",
                "public void first"
            ),
            "field state" to source.replace(
                "public class ClientHandlers {",
                "public class ClientHandlers {\n    private static int state;"
            ),
            "static initializer" to source.replace(
                "public class ClientHandlers {",
                "public class ClientHandlers {\n    static { Calls.count++; }"
            ),
            "constructor" to source.replace(
                "public class ClientHandlers {",
                """
                    public class ClientHandlers {
                        public ClientHandlers() {}
                """.trimIndent()
            ),
            "inheritance" to source.replace(
                "public class ClientHandlers {",
                "public class ClientHandlers extends BaseHandlers {"
            ),
            "dist type shadow" to source.replace(
                "public class ClientHandlers {",
                "public class ClientHandlers {\n    static class Dist {}"
            ),
            "dist value shadow" to source.replace(
                "public static void helper(ClientEvent event)",
                "public static void helper(ClientEvent Dist)"
            ),
            "nested handlers are not owner handlers" to """
                package demo;

                import net.neoforged.api.distmarker.Dist;
                import net.neoforged.api.distmarker.OnlyIn;
                import net.neoforged.bus.api.SubscribeEvent;
                import net.neoforged.fml.common.EventBusSubscriber;

                @EventBusSubscriber
                public class ClientHandlers {
                    static class NestedHandlers {
                        @SubscribeEvent
                        @OnlyIn(Dist.CLIENT)
                        public static void nested(ClientEvent event) {
                            Calls.count++;
                        }
                    }
                }
            """.trimIndent()
        )
        for ((label, candidate) in rejected) {
            val changes = mutableListOf<Change>()
            assertEquals(candidate, migrate(candidate, changes), label)
            assertTrue(changes.isEmpty(), label)
        }

        val alreadyClient = source.replace(
            "@EventBusSubscriber",
            "@EventBusSubscriber(Dist.CLIENT)"
        )
        assertEquals(alreadyClient, migrate(alreadyClient))
        assertFalse(migrate(source).contains("DEDICATED_SERVER"))
    }

    private fun migrate(
        source: String,
        changes: MutableList<Change> = mutableListOf()
    ): String = StructuralRefactorPass()
        .migrateClientOnlyEventBusSubscriberDist(
            source,
            Path.of("ClientHandlers.java"),
            changes
        )

    private fun methods(source: String): List<String> = JavaParser()
        .parse(source)
        .result
        .orElseThrow()
        .findAll(ClassOrInterfaceDeclaration::class.java)
        .single { it.nameAsString == "ClientHandlers" }
        .methods
        .map(MethodDeclaration::toString)

    private fun handlerSource(classAnnotation: String): String = """
        package demo;

        import net.neoforged.api.distmarker.Dist;
        import net.neoforged.api.distmarker.OnlyIn;
        import net.neoforged.bus.api.SubscribeEvent;
        import net.neoforged.fml.common.EventBusSubscriber;

        $classAnnotation
        public class ClientHandlers {
            @SubscribeEvent
            @OnlyIn(Dist.CLIENT)
            public static void first(ClientEvent event) {
                // Preserve this handler body and its comment byte-for-byte.
                Calls.count++;
            }

            @OnlyIn(Dist.CLIENT)
            @SubscribeEvent
            public static void second(ClientEvent event) {
                Calls.count++;
            }

            public static void helper(ClientEvent event) {
            }
        }
    """.trimIndent()

    private fun writeJava(relative: String, source: String): Path {
        val path = directory.resolve("sources").resolve(relative)
        path.parent.createDirectories()
        path.writeText(source.trimIndent() + "\n")
        return path
    }
}
