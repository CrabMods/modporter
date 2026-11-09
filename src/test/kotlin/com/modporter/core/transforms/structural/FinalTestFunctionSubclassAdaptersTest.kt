package com.modporter.core.transforms.structural

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FinalTestFunctionSubclassAdaptersTest {
    @TempDir
    lateinit var project: Path

    @Test
    fun `stable source subclass becomes one cached native record at every proven boundary`() {
        write(
            "src/main/java/sample/OddHarness.java",
            """
            package sample;

            import java.util.Collection;
            import java.util.Comparator;
            import java.util.HashMap;
            import java.util.Map;
            import java.util.function.Consumer;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.gametest.framework.TestFunction;
            import net.minecraft.world.level.block.Rotation;

            public class OddHarness extends TestFunction {
                // SOURCE-MARKER: TestFunction and super.run stay literal in comments.
                public static final String TEXT = "TestFunction super.run";
                public static final Map<String, OddHarness> BY_ID = new HashMap<>();

                public final String id;
                public final String displayName;

                @SuppressWarnings("this-escape")
                protected OddHarness(
                        String id,
                        String displayName,
                        String batch,
                        String sourceName,
                        String structure,
                        Rotation rotation,
                        int maxTicks,
                        long setupTicks,
                        boolean required,
                        int successes,
                        int attempts,
                        Consumer<GameTestHelper> function) {
                    super(
                        batch,
                        sourceName,
                        structure,
                        rotation,
                        maxTicks,
                        setupTicks,
                        required,
                        successes,
                        attempts,
                        function);
                    this.id = id;
                    this.displayName = displayName;
                    BY_ID.put(id, this);
                }

                @Override
                public String getTestName() {
                    return displayName;
                }

                @Override
                public void run(GameTestHelper helper) {
                    before(helper, id);
                    super.run(ExtendedHelper.of(helper));
                    after(helper, id);
                }

                public static TestFunction create(Consumer<GameTestHelper> callback) {
                    return new OddHarness(
                        "full.id",
                        "Simple.test",
                        "batch",
                        "source:structure",
                        "source:structure",
                        Rotation.NONE,
                        200,
                        7,
                        true,
                        2,
                        5,
                        callback);
                }

                public static Collection<TestFunction> all() {
                    return java.util.stream.Stream.of(create(helper -> {}))
                        .sorted(Comparator.comparing(TestFunction::getTestName))
                        .toList();
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/sample/ExtendedHelper.java",
            """
            package sample;

            import net.minecraft.gametest.framework.GameTestHelper;

            public final class ExtendedHelper {
                public static GameTestHelper of(GameTestHelper helper) {
                    return helper;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/sample/TestLookup.java",
            """
            package sample;

            import net.minecraft.gametest.framework.TestFunction;

            public final class TestLookup {
                public static TestFunction lookup(String id) {
                    OddHarness value = OddHarness.BY_ID.get(id);
                    if (value == null)
                        throw new IllegalStateException(id);
                    return value;
                }

                public static TestFunction directLookup(String id) {
                    return OddHarness.BY_ID.get(id);
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/sample/TestGenerator.java",
            """
            package sample;

            import java.util.Collection;
            import net.minecraft.gametest.framework.TestFunction;

            public final class TestGenerator {
                public static Collection<TestFunction> generateTests() {
                    return OddHarness.all();
                }
            }
            """.trimIndent()
        )

        val changes = FinalTestFunctionSubclassAdapters.migrate(
            project,
            dryRun = false
        )

        assertEquals(6, changes.size)
        val wrapper = read("src/main/java/sample/OddHarness.java")
        val lookup = read("src/main/java/sample/TestLookup.java")
        val generator = read("src/main/java/sample/TestGenerator.java")
        val callback = read(
            "src/main/java/com/modporter/generated/gametest/" +
                "LegacyGameTestCallback.java"
        )
        val mixin = read(
            "src/main/java/com/modporter/generated/gametest/mixin/" +
                "LegacyGameTestWarmupMixin.java"
        )
        val originMixin = read(
            "src/main/java/com/modporter/generated/gametest/mixin/" +
                "LegacyGameTestOriginMixin.java"
        )
        val mixinConfig = read(
            "src/main/resources/modporter.gametest.mixins.json"
        )
        val compact = wrapper.replace(Regex("""\s+"""), " ")

        assertFalse(wrapper.contains("class OddHarness extends TestFunction"), wrapper)
        assertTrue(wrapper.contains("Map<String, OddHarness> BY_ID"), wrapper)
        assertTrue(wrapper.contains("@SuppressWarnings(\"this-escape\")"), wrapper)
        assertTrue(
            wrapper.contains(
                "this.modporter\$requiredSuccesses = successes;"
            ),
            wrapper
        )
        assertTrue(
            wrapper.contains("this.modporter\$maxAttempts = attempts;"),
            wrapper
        )
        assertTrue(
            wrapper.contains(
                "this.modporter\$sourceFunction.accept(ExtendedHelper.of(helper));"
            ),
            wrapper
        )
        assertFalse(wrapper.contains("super.run(ExtendedHelper.of(helper))"), wrapper)
        assertTrue(wrapper.contains("before(helper, id);"), wrapper)
        assertTrue(wrapper.contains("after(helper, id);"), wrapper)
        assertTrue(
            compact.contains(
                "this.modporter\$maxAttempts, " +
                    "this.modporter\$requiredSuccesses,"
            ),
            wrapper
        )
        assertTrue(
            compact.contains("this.modporter\$required, false,"),
            wrapper
        )
        assertTrue(
            compact.contains("this.modporter\$requiredSuccesses, true,"),
            wrapper
        )
        assertTrue(wrapper.contains("this.getTestName()"), wrapper)
        assertTrue(
            compact.contains(
                "new com.modporter.generated.gametest." +
                    "LegacyGameTestCallback(this::run)"
            ),
            wrapper
        )
        assertTrue(
            callback.contains("requestedDelay != 0") &&
                callback.contains(
                    "instanceof LegacyGameTestCallback ? 1 : 0"
                ),
            callback
        )
        assertTrue(
            mixin.contains("method = \"ensureStructureIsPlaced\"") &&
                mixin.contains(
                    "startExecution(I)Lnet/minecraft/gametest/framework/" +
                        "GameTestInfo;"
                ) &&
                mixin.contains("require = 1"),
            mixin
        )
        assertTrue(
            mixinConfig.contains(
                "\"package\": \"com.modporter.generated.gametest.mixin\""
            ) &&
                mixinConfig.contains("\"LegacyGameTestWarmupMixin\"") &&
                mixinConfig.contains("\"LegacyGameTestOriginMixin\""),
            mixinConfig
        )
        assertTrue(
            originMixin.contains(
                "method = \"startTests(Lnet/minecraft/server/level/ServerLevel;)V\""
            ) &&
                originMixin.contains(
                    "nextIntBetweenInclusive(II)I"
                ) &&
                originMixin.contains("ordinal = 0") &&
                originMixin.contains("ordinal = 1") &&
                originMixin.contains("require = 1") &&
                originMixin.contains("private List<TestFunction> testFunctions;") &&
                !originMixin.contains("testFunctions = null") &&
                !originMixin.contains("BlockPos"),
            originMixin
        )
        assertTrue(
            callback.contains("testFunctions.isEmpty()") &&
                callback.contains("instanceof LegacyGameTestCallback") &&
                callback.contains("return random.nextIntBetweenInclusive") &&
                callback.contains(
                    "fixture origin 0, -60, 0 (1.21.1 anchor 0, -59, 0)"
                ) &&
                !callback.contains("serverOriginY"),
            callback
        )
        assertTrue(
            wrapper.contains(
                "new OddHarness("
            ) && Regex(
                """\)\s*\.modporter\${'$'}asTestFunction\(\);"""
            ).containsMatchIn(wrapper),
            wrapper
        )
        assertTrue(
            wrapper.contains("Comparator.comparing(TestFunction::testName)"),
            wrapper
        )
        assertTrue(
            wrapper.contains("return this.modporter\$sourceTestName;"),
            wrapper
        )
        assertTrue(
            wrapper.contains(
                "// SOURCE-MARKER: TestFunction and super.run stay literal in comments."
            ),
            wrapper
        )
        assertTrue(wrapper.contains("\"TestFunction super.run\""), wrapper)

        assertTrue(
            lookup.contains(
                "return value.modporter\$asTestFunction();"
            ),
            lookup
        )
        assertTrue(
            Regex(
                """return OddHarness\.BY_ID\.get\(id\)""" +
                    """\s*\.modporter\${'$'}asTestFunction\(\);"""
            ).containsMatchIn(lookup),
            lookup
        )
        assertTrue(
            generator.contains("return OddHarness.all();"),
            generator
        )
        assertFalse(generator.contains("modporter\$asTestFunction"), generator)
    }

    @Test
    fun `migration is idempotent after inheritance has been removed`() {
        writeStableCandidate()

        FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        val once = read("src/main/java/sample/Stable.java")
        val callbackOnce = read(
            "src/main/java/com/modporter/generated/gametest/" +
                "LegacyGameTestCallback.java"
        )
        val mixinOnce = read(
            "src/main/java/com/modporter/generated/gametest/mixin/" +
                "LegacyGameTestWarmupMixin.java"
        )
        val originMixinOnce = read(
            "src/main/java/com/modporter/generated/gametest/mixin/" +
                "LegacyGameTestOriginMixin.java"
        )
        val second = FinalTestFunctionSubclassAdapters.migrate(
            project,
            dryRun = false
        )

        assertTrue(second.isEmpty())
        assertEquals(once, read("src/main/java/sample/Stable.java"))
        assertEquals(
            callbackOnce,
            read(
                "src/main/java/com/modporter/generated/gametest/" +
                    "LegacyGameTestCallback.java"
            )
        )
        assertEquals(
            mixinOnce,
            read(
                "src/main/java/com/modporter/generated/gametest/mixin/" +
                    "LegacyGameTestWarmupMixin.java"
            )
        )
        assertEquals(
            originMixinOnce,
            read(
                "src/main/java/com/modporter/generated/gametest/mixin/" +
                    "LegacyGameTestOriginMixin.java"
            )
        )
    }

    @Test
    fun `foreign warmup support collision fails before wrapper mutation`() {
        writeStableCandidate()
        val wrapperBefore = read("src/main/java/sample/Stable.java")
        write(
            "src/main/java/com/modporter/generated/gametest/" +
                "LegacyGameTestCallback.java",
            "package foreign; public final class LegacyGameTestCallback {}"
        )

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("already owned"))
        assertEquals(wrapperBefore, read("src/main/java/sample/Stable.java"))
    }

    @Test
    fun `dynamic getTestName fails closed before any file is written`() {
        val source = """
            package sample;

            import java.util.function.Consumer;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.gametest.framework.TestFunction;
            import net.minecraft.world.level.block.Rotation;

            public class DynamicName extends TestFunction {
                private final String prefix;
                private final String name;

                DynamicName(
                        String prefix,
                        String name,
                        Consumer<GameTestHelper> callback) {
                    super(
                        "batch",
                        "source",
                        "structure",
                        Rotation.NONE,
                        20,
                        0,
                        true,
                        1,
                        1,
                        callback);
                    this.prefix = prefix;
                    this.name = name;
                }

                @Override
                public String getTestName() {
                    return prefix + name;
                }
            }
        """.trimIndent()
        write("src/main/java/sample/DynamicName.java", source)

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("one final String field"))
        assertEquals(source, read("src/main/java/sample/DynamicName.java"))
    }

    @Test
    fun `source owned child blocks composition for the whole family`() {
        val parent = stableCandidateSource("Stable")
        val child = """
            package sample;

            public final class Child extends Stable {
                Child() {
                    super(helper -> {});
                }
            }
        """.trimIndent()
        write("src/main/java/sample/Stable.java", parent)
        write("src/main/java/sample/Child.java", child)

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("source subclass"))
        assertEquals(parent, read("src/main/java/sample/Stable.java"))
        assertEquals(child, read("src/main/java/sample/Child.java"))
    }

    @Test
    fun `casts that depended on inheritance fail closed`() {
        val wrapper = stableCandidateSource("Stable")
        val cast = """
            package sample;

            import net.minecraft.gametest.framework.TestFunction;

            public final class UnsafeCast {
                TestFunction cast(Stable value) {
                    return (TestFunction) value;
                }
            }
        """.trimIndent()
        write("src/main/java/sample/Stable.java", wrapper)
        write("src/main/java/sample/UnsafeCast.java", cast)

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("cast"))
        assertEquals(wrapper, read("src/main/java/sample/Stable.java"))
        assertEquals(cast, read("src/main/java/sample/UnsafeCast.java"))
    }

    @Test
    fun `casts from native records back to source wrappers fail closed`() {
        val wrapper = stableCandidateSource("Stable")
        val cast = """
            package sample;

            import net.minecraft.gametest.framework.TestFunction;

            public final class UnsafeReverseCast {
                Stable cast(TestFunction value) {
                    return (Stable) value;
                }
            }
        """.trimIndent()
        write("src/main/java/sample/Stable.java", wrapper)
        write("src/main/java/sample/UnsafeReverseCast.java", cast)

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("cast to source wrapper"))
        assertEquals(wrapper, read("src/main/java/sample/Stable.java"))
        assertEquals(cast, read("src/main/java/sample/UnsafeReverseCast.java"))
    }

    @Test
    fun `instanceof from a native record to a source wrapper fails closed`() {
        val wrapper = stableCandidateSource("Stable")
        val check = """
            package sample;

            import net.minecraft.gametest.framework.TestFunction;

            public final class UnsafeInstanceOf {
                boolean check(TestFunction value) {
                    return value instanceof Stable;
                }
            }
        """.trimIndent()
        write("src/main/java/sample/Stable.java", wrapper)
        write("src/main/java/sample/UnsafeInstanceOf.java", check)

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("instanceof"))
        assertEquals(wrapper, read("src/main/java/sample/Stable.java"))
        assertEquals(check, read("src/main/java/sample/UnsafeInstanceOf.java"))
    }

    @Test
    fun `wrapper returning static factory references fail closed`() {
        val wrapper = stableCandidateSource("Stable").replace(
            "static TestFunction create(Consumer<GameTestHelper> callback) {",
            """
            static Stable raw(Consumer<GameTestHelper> callback) {
                return new Stable(callback);
            }

            static TestFunction create(Consumer<GameTestHelper> callback) {
            """.trimIndent()
        )
        val reference = """
            package sample;

            import java.util.function.Consumer;
            import java.util.function.Function;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.gametest.framework.TestFunction;

            public final class UnsafeFactoryReference {
                Function<Consumer<GameTestHelper>, TestFunction> factory = Stable::raw;
            }
        """.trimIndent()
        write("src/main/java/sample/Stable.java", wrapper)
        write("src/main/java/sample/UnsafeFactoryReference.java", reference)

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("Static method reference"))
        assertEquals(wrapper, read("src/main/java/sample/Stable.java"))
        assertEquals(reference, read("src/main/java/sample/UnsafeFactoryReference.java"))
    }

    @Test
    fun `wrapper arguments without a declared wrapper association fail closed`() {
        val wrapper = stableCandidateSource("Stable")
        val flow = """
            package sample;

            import net.minecraft.gametest.framework.TestFunction;

            public final class UnsafeArgument {
                void register(TestFunction value) {
                }

                void submit(Stable value) {
                    register(value);
                }
            }
        """.trimIndent()
        write("src/main/java/sample/Stable.java", wrapper)
        write("src/main/java/sample/UnsafeArgument.java", flow)

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("no declaration-proven wrapper association"))
        assertEquals(wrapper, read("src/main/java/sample/Stable.java"))
        assertEquals(flow, read("src/main/java/sample/UnsafeArgument.java"))
    }

    @Test
    fun `two source callback dispatches are rejected rather than duplicated`() {
        val source = stableCandidateSource("Twice").replace(
            "super.run(helper);",
            "super.run(helper);\n        super.run(helper);"
        )
        write("src/main/java/sample/Twice.java", source)

        val error = assertFailsWith<IllegalStateException> {
            FinalTestFunctionSubclassAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("exactly once"))
        assertEquals(source, read("src/main/java/sample/Twice.java"))
    }

    private fun writeStableCandidate() {
        write(
            "src/main/java/sample/Stable.java",
            stableCandidateSource("Stable")
        )
    }

    private fun stableCandidateSource(name: String): String =
        """
        package sample;

        import java.util.function.Consumer;
        import net.minecraft.gametest.framework.GameTestHelper;
        import net.minecraft.gametest.framework.TestFunction;
        import net.minecraft.world.level.block.Rotation;

        public class $name extends TestFunction {
            private final String name;

            $name(Consumer<GameTestHelper> callback) {
                super(
                    "batch",
                    "source",
                    "structure",
                    Rotation.NONE,
                    20,
                    0,
                    true,
                    2,
                    4,
                    callback);
                this.name = "stable";
            }

            @Override
            public String getTestName() {
                return name;
            }

            @Override
            public void run(GameTestHelper helper) {
                super.run(helper);
            }

            static TestFunction create(Consumer<GameTestHelper> callback) {
                return new $name(callback);
            }
        }
        """.trimIndent()

    private fun write(relative: String, source: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(source)
    }

    private fun read(relative: String): String =
        project.resolve(relative).readText()
}
