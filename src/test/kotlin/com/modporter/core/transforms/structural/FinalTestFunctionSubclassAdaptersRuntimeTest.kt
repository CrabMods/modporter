package com.modporter.core.transforms.structural

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FinalTestFunctionSubclassAdaptersRuntimeTest {
    @TempDir
    lateinit var project: Path

    @Test
    fun `generated adapter compiles and executes callback lifecycle exactly once`() {
        writeTargetStubs()
        writeSourceWrapper()
        writeNativeBoundaries()
        writeRuntimeProbe()

        FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)

        val wrapper = read("src/main/java/sample/SourceTest.java")
        assertTrue(
            wrapper.contains(
                "this.modporter\$sourceFunction.accept(SourceHelper.of(helper));"
            ),
            wrapper
        )
        assertTrue(
            wrapper.contains(
                "new com.modporter.generated.gametest." +
                    "LegacyGameTestCallback(this::run)"
            ),
            wrapper
        )
        assertTrue(
            wrapper.indexOf("this.modporter\$maxAttempts,") <
                wrapper.indexOf("this.modporter\$requiredSuccesses,"),
            wrapper
        )

        val classes = project.resolve("compiled")
        classes.createDirectories()
        compileJava(classes)

        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val probe = loader.loadClass("sample.RuntimeProbe")
            val result = probe.getMethod("verify").invoke(null)
            assertEquals("verified", result)
        }
    }

    private fun writeTargetStubs() {
        write(
            "src/main/java/net/minecraft/world/level/block/Rotation.java",
            """
            package net.minecraft.world.level.block;

            public enum Rotation {
                NONE
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/gametest/framework/GameTestHelper.java",
            """
            package net.minecraft.gametest.framework;

            import java.util.List;

            public class GameTestHelper {
                public final List<String> events;

                public GameTestHelper(List<String> events) {
                    this.events = events;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/gametest/framework/TestFunction.java",
            """
            package net.minecraft.gametest.framework;

            import java.util.function.Consumer;
            import net.minecraft.world.level.block.Rotation;

            public record TestFunction(
                    String batchName,
                    String testName,
                    String structureName,
                    Rotation rotation,
                    int maxTicks,
                    long setupTicks,
                    boolean required,
                    boolean manualOnly,
                    int maxAttempts,
                    int requiredSuccesses,
                    boolean skyAccess,
                    Consumer<GameTestHelper> function) {
                public void run(GameTestHelper helper) {
                    function.accept(helper);
                }

                @Override
                public String toString() {
                    return this.testName;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/gametest/framework/GameTestInfo.java",
            """
            package net.minecraft.gametest.framework;

            public final class GameTestInfo {
                private final TestFunction testFunction;

                public GameTestInfo(TestFunction testFunction) {
                    this.testFunction = testFunction;
                }

                public TestFunction getTestFunction() {
                    return testFunction;
                }

                public GameTestInfo startExecution(int delay) {
                    return this;
                }

                private boolean ensureStructureIsPlaced() {
                    startExecution(0);
                    return true;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/core/BlockPos.java",
            """
            package net.minecraft.core;

            public final class BlockPos {
                private final int x;
                private final int y;
                private final int z;

                public BlockPos(int x, int y, int z) {
                    this.x = x;
                    this.y = y;
                    this.z = z;
                }

                public int getY() {
                    return this.y;
                }

                public String toShortString() {
                    return this.x + ", " + this.y + ", " + this.z;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/util/RandomSource.java",
            """
            package net.minecraft.util;

            public interface RandomSource {
                int nextIntBetweenInclusive(int minimum, int maximum);
            }
            """.trimIndent()
        )
        write(
            "src/main/java/net/minecraft/gametest/framework/GameTestServer.java",
            """
            package net.minecraft.gametest.framework;

            import java.util.List;

            public final class GameTestServer {
                private final List<TestFunction> testFunctions = List.of();
            }
            """.trimIndent()
        )
        write(
            "src/main/java/org/spongepowered/asm/mixin/Mixin.java",
            """
            package org.spongepowered.asm.mixin;

            public @interface Mixin {
                Class<?>[] value();
            }
            """.trimIndent()
        )
        write(
            "src/main/java/org/spongepowered/asm/mixin/Final.java",
            """
            package org.spongepowered.asm.mixin;

            public @interface Final {
            }
            """.trimIndent()
        )
        write(
            "src/main/java/org/spongepowered/asm/mixin/Shadow.java",
            """
            package org.spongepowered.asm.mixin;

            public @interface Shadow {
            }
            """.trimIndent()
        )
        write(
            "src/main/java/org/spongepowered/asm/mixin/injection/At.java",
            """
            package org.spongepowered.asm.mixin.injection;

            public @interface At {
                String value();
                String target() default "";
                int ordinal() default -1;
            }
            """.trimIndent()
        )
        write(
            "src/main/java/org/spongepowered/asm/mixin/injection/Redirect.java",
            """
            package org.spongepowered.asm.mixin.injection;

            public @interface Redirect {
                String[] method();
                At at();
                int require() default -1;
            }
            """.trimIndent()
        )
        write(
            "src/main/java/org/spongepowered/asm/mixin/injection/ModifyArg.java",
            """
            package org.spongepowered.asm.mixin.injection;

            public @interface ModifyArg {
                String[] method();
                At at();
                int index();
                int require() default -1;
            }
            """.trimIndent()
        )
    }

    private fun writeSourceWrapper() {
        write(
            "src/main/java/sample/SourceHelper.java",
            """
            package sample;

            import net.minecraft.gametest.framework.GameTestHelper;

            public final class SourceHelper extends GameTestHelper {
                private SourceHelper(GameTestHelper original) {
                    super(original.events);
                }

                public static SourceHelper of(GameTestHelper original) {
                    return new SourceHelper(original);
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/sample/SourceTest.java",
            """
            package sample;

            import java.util.ArrayList;
            import java.util.Comparator;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.function.Consumer;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.gametest.framework.TestFunction;
            import net.minecraft.world.level.block.Rotation;

            public final class SourceTest extends TestFunction {
                public static final Map<String, SourceTest> BY_ID = new HashMap<>();

                private final String fullName;
                private final String simpleName;

                SourceTest(
                        String fullName,
                        String simpleName,
                        String batch,
                        String sourceTestName,
                        String structureName,
                        Rotation rotation,
                        int maxTicks,
                        long setupTicks,
                        boolean required,
                        int requiredSuccesses,
                        int maxAttempts,
                        Consumer<GameTestHelper> function) {
                    super(
                        batch,
                        sourceTestName,
                        structureName,
                        rotation,
                        maxTicks,
                        setupTicks,
                        required,
                        requiredSuccesses,
                        maxAttempts,
                        function);
                    this.fullName = fullName;
                    this.simpleName = simpleName;
                    BY_ID.put(fullName, this);
                }

                @Override
                public String getTestName() {
                    return simpleName;
                }

                @Override
                public void run(GameTestHelper helper) {
                    helper.events.add("setup:" + fullName);
                    super.run(SourceHelper.of(helper));
                    helper.events.add("teardown:" + fullName);
                }

                public static TestFunction create(
                        String id,
                        String simple,
                        Consumer<GameTestHelper> function) {
                    return new SourceTest(
                        id,
                        simple,
                        "batch",
                        "source:old_name",
                        "source:structure",
                        Rotation.NONE,
                        80,
                        3,
                        true,
                        2,
                        5,
                        function);
                }

                public static List<TestFunction> sorted(
                        TestFunction first,
                        TestFunction second) {
                    List<TestFunction> values =
                        new ArrayList<>(List.of(first, second));
                    values.sort(Comparator.comparing(TestFunction::getTestName));
                    return values;
                }

                public static String sourceFacingToString(String id) {
                    return BY_ID.get(id).toString();
                }
            }
            """.trimIndent()
        )
    }

    private fun writeNativeBoundaries() {
        write(
            "src/main/java/sample/Lookup.java",
            """
            package sample;

            import net.minecraft.gametest.framework.TestFunction;

            public final class Lookup {
                public static TestFunction byId(String id) {
                    SourceTest source = SourceTest.BY_ID.get(id);
                    if (source == null)
                        throw new IllegalStateException("missing " + id);
                    return source;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/sample/Registrar.java",
            """
            package sample;

            import java.util.Collection;
            import net.minecraft.gametest.framework.TestFunction;

            public final class Registrar {
                public static Collection<TestFunction> generateTests() {
                    return SourceTest.sorted(
                        SourceTest.create("b", "B.test", helper -> {}),
                        SourceTest.create("a", "A.test", helper -> {}));
                }
            }
            """.trimIndent()
        )
    }

    private fun writeRuntimeProbe() {
        write(
            "src/main/java/sample/RuntimeProbe.java",
            """
            package sample;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.atomic.AtomicInteger;
            import com.modporter.generated.gametest.LegacyGameTestCallback;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.gametest.framework.GameTestInfo;
            import net.minecraft.gametest.framework.TestFunction;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.level.block.Rotation;

            public final class RuntimeProbe {
                public static String verify() {
                    AtomicInteger callbacks = new AtomicInteger();
                    TestFunction nativeFunction = SourceTest.create(
                        "full.id",
                        "Simple.test",
                        helper -> {
                            callbacks.incrementAndGet();
                            helper.events.add(
                                "callback:" + helper.getClass().getSimpleName());
                        });

                    if (Lookup.byId("full.id") != nativeFunction)
                        throw new AssertionError("native record identity changed");
                    if (!nativeFunction.batchName().equals("batch"))
                        throw new AssertionError(nativeFunction.batchName());
                    if (!nativeFunction.testName().equals("Simple.test"))
                        throw new AssertionError(nativeFunction.testName());
                    if (!nativeFunction.structureName().equals("source:structure"))
                        throw new AssertionError(nativeFunction.structureName());
                    if (nativeFunction.manualOnly())
                        throw new AssertionError("manualOnly");
                    if (!nativeFunction.skyAccess())
                        throw new AssertionError("skyAccess");
                    if (nativeFunction.maxAttempts() != 5)
                        throw new AssertionError("maxAttempts");
                    if (nativeFunction.requiredSuccesses() != 2)
                        throw new AssertionError("requiredSuccesses");
                    if (!nativeFunction.toString().equals("Simple.test"))
                        throw new AssertionError(nativeFunction.toString());
                    if (!SourceTest.sourceFacingToString("full.id").equals(
                            "source:old_name"))
                        throw new AssertionError(
                            SourceTest.sourceFacingToString("full.id"));

                    GameTestInfo legacyInfo = new GameTestInfo(nativeFunction);
                    if (LegacyGameTestCallback.initialPlacementDelay(
                            legacyInfo, 0) != 1)
                        throw new AssertionError("legacy initial delay");
                    if (LegacyGameTestCallback.initialPlacementDelay(
                            legacyInfo, 1) != 1)
                        throw new AssertionError("legacy retry delay");

                    TestFunction nativeTargetFunction = new TestFunction(
                        "batch",
                        "Native.test",
                        "native:structure",
                        Rotation.NONE,
                        20,
                        0,
                        true,
                        false,
                        1,
                        1,
                        false,
                        helper -> {}
                    );
                    if (LegacyGameTestCallback.initialPlacementDelay(
                            new GameTestInfo(nativeTargetFunction), 0) != 0)
                        throw new AssertionError("native initial delay");

                    List<TestFunction> legacyOnly = List.of(nativeFunction);
                    CountingRandom legacyRandom = new CountingRandom(7);
                    if (LegacyGameTestCallback.serverOriginX(
                            legacyOnly, legacyRandom, -10, 10) != 0)
                        throw new AssertionError("legacy origin x");
                    if (LegacyGameTestCallback.serverOriginZ(
                            legacyOnly, legacyRandom, -10, 10) != 0)
                        throw new AssertionError("legacy origin z");
                    if (legacyRandom.calls != 0)
                        throw new AssertionError("legacy origin consumed RNG");

                    List<TestFunction> nativeOnly = List.of(nativeTargetFunction);
                    CountingRandom nativeRandom = new CountingRandom(7);
                    if (LegacyGameTestCallback.serverOriginX(
                            nativeOnly, nativeRandom, -10, 10) != 7)
                        throw new AssertionError("native origin x");
                    if (LegacyGameTestCallback.serverOriginZ(
                            nativeOnly, nativeRandom, -10, 10) != 7)
                        throw new AssertionError("native origin z");
                    if (nativeRandom.calls != 2)
                        throw new AssertionError("native origin RNG count");

                    List<TestFunction> mixed =
                        List.of(nativeFunction, nativeTargetFunction);
                    CountingRandom mixedRandom = new CountingRandom(-4);
                    if (LegacyGameTestCallback.serverOriginX(
                            mixed, mixedRandom, -10, 10) != -4)
                        throw new AssertionError("mixed origin x");
                    if (LegacyGameTestCallback.serverOriginZ(
                            mixed, mixedRandom, -10, 10) != -4)
                        throw new AssertionError("mixed origin z");
                    if (mixedRandom.calls != 2)
                        throw new AssertionError("mixed origin RNG count");

                    CountingRandom emptyRandom = new CountingRandom(3);
                    if (LegacyGameTestCallback.serverOriginX(
                            List.of(), emptyRandom, -10, 10) != 3)
                        throw new AssertionError("empty origin x");
                    if (LegacyGameTestCallback.serverOriginZ(
                            List.of(), emptyRandom, -10, 10) != 3)
                        throw new AssertionError("empty origin z");
                    if (emptyRandom.calls != 2)
                        throw new AssertionError("empty origin RNG count");

                    List<String> events = new ArrayList<>();
                    nativeFunction.run(new GameTestHelper(events));
                    if (callbacks.get() != 1)
                        throw new AssertionError("callback count " + callbacks);
                    if (!events.equals(List.of(
                            "setup:full.id",
                            "callback:SourceHelper",
                            "teardown:full.id")))
                        throw new AssertionError(events);

                    List<TestFunction> generated =
                        new ArrayList<>(Registrar.generateTests());
                    if (!generated.get(0).testName().equals("A.test"))
                        throw new AssertionError(generated);
                    if (!generated.get(1).testName().equals("B.test"))
                        throw new AssertionError(generated);

                    List<String> failureEvents = new ArrayList<>();
                    TestFunction failure = SourceTest.create(
                        "failure.id",
                        "Failure.test",
                        helper -> {
                            helper.events.add("callback:throw");
                            throw new IllegalStateException("boom");
                        });
                    try {
                        failure.run(new GameTestHelper(failureEvents));
                        throw new AssertionError("missing callback failure");
                    } catch (IllegalStateException expected) {
                        if (!expected.getMessage().equals("boom"))
                            throw expected;
                    }
                    if (!failureEvents.equals(List.of(
                            "setup:failure.id",
                            "callback:throw")))
                        throw new AssertionError(failureEvents);

                    return "verified";
                }

                private static final class CountingRandom implements RandomSource {
                    private final int value;
                    private int calls;

                    private CountingRandom(int value) {
                        this.value = value;
                    }

                    @Override
                    public int nextIntBetweenInclusive(int minimum, int maximum) {
                        if (this.value < minimum || this.value > maximum)
                            throw new AssertionError("out-of-range fixture");
                        this.calls++;
                        return this.value;
                    }
                }
            }
            """.trimIndent()
        )
    }

    private fun compileJava(output: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for generated adapter execution test")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use {
                fileManager ->
            val sources = Files.walk(project.resolve("src/main/java")).use { stream ->
                stream.filter { it.extension == "java" }.toList()
            }
            val units = fileManager.getJavaFileObjectsFromPaths(sources)
            val success = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf("-d", output.toString()),
                null,
                units
            ).call()
            assertTrue(
                success,
                diagnostics.diagnostics.joinToString("\n") { it.toString() }
            )
        }
    }

    private fun write(relative: String, source: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(source)
    }

    private fun read(relative: String): String =
        project.resolve(relative).readText()
}
