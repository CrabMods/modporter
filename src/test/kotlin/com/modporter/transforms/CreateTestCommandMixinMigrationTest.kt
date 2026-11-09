package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCreateTestCommandMixin
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

class CreateTestCommandMixinMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `native Optional redirect preserves lookup and Create fixture tag semantics`() {
        val source = legacyMixin(
            fallback =
                "GameTestRegistry.findTestFunction(testName).orElseThrow()",
            selected = "function.modporter${'$'}asTestFunction()"
        )
        val pass = StructuralRefactorPass()
        val migrated = pass.migrateCreateTestCommandMixin(source)
        val migratedHandler = JavaParser()
            .parse(migrated)
            .result
            .orElseThrow()
            .findAll(MethodDeclaration::class.java)
            .single {
                it.nameAsString ==
                    "create${'$'}getCorrectTestFunction"
            }

        assertTrue(
            migrated.contains(
                "createGameTestInfo(Lnet/minecraft/core/BlockPos;" +
                    "Lnet/minecraft/server/level/ServerLevel;" +
                    "Lnet/minecraft/gametest/framework/RetryOptions;)" +
                    "Ljava/util/Optional;"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "GameTestRegistry;findTestFunction(Ljava/lang/String;)" +
                    "Ljava/util/Optional;"
            ),
            migrated
        )
        assertEquals(
            "java.util.Optional<net.minecraft.gametest.framework.TestFunction>",
            migratedHandler.type.toString()
        )
        assertEquals(
            listOf(
                "String" to "testName",
                "net.minecraft.core.BlockPos" to "pos",
                "net.minecraft.server.level.ServerLevel" to "level",
                "net.minecraft.gametest.framework.RetryOptions" to "retryOptions"
            ),
            migratedHandler.parameters.map {
                it.type.toString() to it.nameAsString
            }
        )
        assertTrue(
            migrated.contains(
                "return net.minecraft.gametest.framework.GameTestRegistry" +
                    ".findTestFunction(testName);"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "return java.util.Optional.of(" +
                    "function.modporter${'$'}asTestFunction());"
            ),
            migrated
        )
        assertTrue(migrated.contains("require = 0"), migrated)
        assertFalse(
            migrated.contains(
                "import net.minecraft.gametest.framework.MultipleTestTracker;"
            ),
            migrated
        )
        assertFalse(
            migrated.contains("findTestFunction(testName).orElseThrow()"),
            migrated
        )
        assertEquals(migrated, pass.migrateCreateTestCommandMixin(migrated))

        val sources = listOf(
            writeJava(
                "org/spongepowered/asm/mixin/Mixin.java",
                """
                    package org.spongepowered.asm.mixin;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.TYPE)
                    public @interface Mixin {
                        Class<?> value();
                    }
                """
            ),
            writeJava(
                "org/spongepowered/asm/mixin/injection/At.java",
                """
                    package org.spongepowered.asm.mixin.injection;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface At {
                        String value();
                        String target();
                    }
                """
            ),
            writeJava(
                "org/spongepowered/asm/mixin/injection/Redirect.java",
                """
                    package org.spongepowered.asm.mixin.injection;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.METHOD)
                    public @interface Redirect {
                        String method();
                        At at();
                        int require() default -1;
                    }
                """
            ),
            writeJava(
                "javax/annotation/Nullable.java",
                """
                    package javax.annotation;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Target;
                    @Target({
                        ElementType.PARAMETER,
                        ElementType.METHOD,
                        ElementType.FIELD
                    })
                    public @interface Nullable {}
                """
            ),
            writeJava(
                "net/minecraft/core/BlockPos.java",
                """
                    package net.minecraft.core;
                    public final class BlockPos {}
                """
            ),
            writeJava(
                "net/minecraft/nbt/Tag.java",
                """
                    package net.minecraft.nbt;
                    public interface Tag {
                        int TAG_STRING = 8;
                    }
                """
            ),
            writeJava(
                "net/minecraft/nbt/CompoundTag.java",
                """
                    package net.minecraft.nbt;
                    import java.util.HashMap;
                    import java.util.Map;
                    public final class CompoundTag {
                        private final Map<String, Object> values = new HashMap<>();
                        public boolean contains(String key, int type) {
                            return type == Tag.TAG_STRING &&
                                values.get(key) instanceof String;
                        }
                        public String getString(String key) {
                            Object value = values.get(key);
                            return value instanceof String ? (String) value : "";
                        }
                        public void putString(String key, String value) {
                            values.put(key, value);
                        }
                        public void putInt(String key, int value) {
                            values.put(key, value);
                        }
                        public void remove(String key) {
                            values.remove(key);
                        }
                    }
                """
            ),
            writeJava(
                "net/minecraft/world/level/block/entity/StructureBlockEntity.java",
                """
                    package net.minecraft.world.level.block.entity;
                    import net.minecraft.nbt.CompoundTag;
                    public final class StructureBlockEntity {
                        private final CompoundTag data = new CompoundTag();
                        private String metadata = "";
                        public CompoundTag getPersistentData() {
                            return data;
                        }
                        public String getMetaData() {
                            return metadata;
                        }
                        public void setMetaData(String metadata) {
                            this.metadata = metadata;
                        }
                    }
                """
            ),
            writeJava(
                "net/minecraft/server/level/ServerLevel.java",
                """
                    package net.minecraft.server.level;
                    import java.util.HashMap;
                    import java.util.Map;
                    import net.minecraft.core.BlockPos;
                    public final class ServerLevel {
                        private final Map<BlockPos, Object> entities = new HashMap<>();
                        public int lookups;
                        public void bind(BlockPos pos, Object entity) {
                            entities.put(pos, entity);
                        }
                        public Object getBlockEntity(BlockPos pos) {
                            lookups++;
                            return entities.get(pos);
                        }
                    }
                """
            ),
            writeJava(
                "net/minecraft/gametest/framework/TestFunction.java",
                """
                    package net.minecraft.gametest.framework;
                    public class TestFunction {
                        public final String id;
                        public TestFunction(String id) {
                            this.id = id;
                        }
                    }
                """
            ),
            writeJava(
                "net/minecraft/gametest/framework/MultipleTestTracker.java",
                """
                    package net.minecraft.gametest.framework;
                    public final class MultipleTestTracker {}
                """
            ),
            writeJava(
                "net/minecraft/gametest/framework/RetryOptions.java",
                """
                    package net.minecraft.gametest.framework;
                    public final class RetryOptions {}
                """
            ),
            writeJava(
                "net/minecraft/gametest/framework/GameTestInfo.java",
                """
                    package net.minecraft.gametest.framework;
                    public final class GameTestInfo {
                        public final TestFunction function;
                        public GameTestInfo(TestFunction function) {
                            this.function = function;
                        }
                    }
                """
            ),
            writeJava(
                "net/minecraft/gametest/framework/GameTestRegistry.java",
                """
                    package net.minecraft.gametest.framework;
                    import java.util.HashMap;
                    import java.util.Map;
                    import java.util.Optional;
                    public final class GameTestRegistry {
                        public static final Map<String, TestFunction> FUNCTIONS =
                            new HashMap<>();
                        public static int findCalls;
                        public static Optional<TestFunction> findTestFunction(
                                String name) {
                            findCalls++;
                            return Optional.ofNullable(FUNCTIONS.get(name));
                        }
                        public static void reset() {
                            FUNCTIONS.clear();
                            findCalls = 0;
                        }
                    }
                """
            ),
            writeJava(
                "net/minecraft/gametest/framework/TestCommand.java",
                """
                    package net.minecraft.gametest.framework;
                    import java.util.Optional;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.server.level.ServerLevel;
                    import net.minecraft.world.level.block.entity.StructureBlockEntity;
                    public final class TestCommand {
                        private static Optional<GameTestInfo> createGameTestInfo(
                                BlockPos pos,
                                ServerLevel level,
                                RetryOptions retryOptions) {
                            StructureBlockEntity be =
                                (StructureBlockEntity) level.getBlockEntity(pos);
                            if (be == null)
                                return Optional.empty();
                            String testName = be.getMetaData();
                            Optional<TestFunction> function =
                                GameTestRegistry.findTestFunction(testName);
                            if (function.isEmpty())
                                return Optional.empty();
                            return Optional.of(
                                new GameTestInfo(function.get()));
                        }
                    }
                """
            ),
            writeJava(
                "com/simibubi/create/infrastructure/gametest/CreateTestFunction.java",
                """
                    package com.simibubi.create.infrastructure.gametest;
                    import java.util.HashMap;
                    import java.util.Map;
                    import net.minecraft.gametest.framework.TestFunction;
                    public final class CreateTestFunction {
                        public static final Map<String, CreateTestFunction>
                            NAMES_TO_FUNCTIONS = new HashMap<>();
                        private final TestFunction nativeFunction;
                        public CreateTestFunction(
                                String name,
                                TestFunction nativeFunction) {
                            this.nativeFunction = nativeFunction;
                            NAMES_TO_FUNCTIONS.put(name, this);
                        }
                        public TestFunction modporter${'$'}asTestFunction() {
                            return nativeFunction;
                        }
                    }
                """
            ),
            writeJava(
                "com/simibubi/create/foundation/mixin/TestCommandMixin.java",
                migrated
            ),
            writeJava(
                "probe/Probe.java",
                """
                    package probe;
                    import com.simibubi.create.foundation.mixin.TestCommandMixin;
                    import com.simibubi.create.infrastructure.gametest.CreateTestFunction;
                    import java.lang.reflect.InvocationTargetException;
                    import java.lang.reflect.Method;
                    import java.util.Optional;
                    import net.minecraft.core.BlockPos;
                    import net.minecraft.gametest.framework.GameTestRegistry;
                    import net.minecraft.gametest.framework.RetryOptions;
                    import net.minecraft.gametest.framework.TestFunction;
                    import net.minecraft.server.level.ServerLevel;
                    import net.minecraft.world.level.block.entity.StructureBlockEntity;
                    public final class Probe {
                        private static final Method HANDLER;
                        static {
                            try {
                                HANDLER = TestCommandMixin.class.getDeclaredMethod(
                                    "create${'$'}getCorrectTestFunction",
                                    String.class,
                                    BlockPos.class,
                                    ServerLevel.class,
                                    RetryOptions.class
                                );
                                HANDLER.setAccessible(true);
                            } catch (ReflectiveOperationException exception) {
                                throw new ExceptionInInitializerError(exception);
                            }
                        }
                        @SuppressWarnings("unchecked")
                        private static Optional<TestFunction> invoke(
                                String testName,
                                BlockPos pos,
                                ServerLevel level,
                                RetryOptions retryOptions) {
                            try {
                                return (Optional<TestFunction>) HANDLER.invoke(
                                    null, testName, pos, level, retryOptions);
                            } catch (InvocationTargetException exception) {
                                Throwable cause = exception.getCause();
                                if (cause instanceof RuntimeException runtime)
                                    throw runtime;
                                throw new AssertionError(cause);
                            } catch (ReflectiveOperationException exception) {
                                throw new AssertionError(exception);
                            }
                        }
                        public static String verify() {
                            BlockPos pos = new BlockPos();
                            ServerLevel level = new ServerLevel();
                            RetryOptions retry = new RetryOptions();
                            StructureBlockEntity structure =
                                new StructureBlockEntity();
                            level.bind(pos, structure);

                            GameTestRegistry.reset();
                            Optional<TestFunction> empty =
                                invoke("missing", pos, level, retry);
                            if (empty.isPresent())
                                throw new AssertionError(
                                    "native empty lookup became present");
                            if (GameTestRegistry.findCalls != 1)
                                throw new AssertionError(
                                    "empty lookup did not stay on registry path");

                            TestFunction vanilla =
                                new TestFunction("vanilla");
                            GameTestRegistry.reset();
                            GameTestRegistry.FUNCTIONS.put("vanilla", vanilla);
                            Optional<TestFunction> present =
                                invoke("vanilla", pos, level, retry);
                            if (present.orElseThrow() != vanilla)
                                throw new AssertionError(
                                    "non-Create function identity changed");

                            TestFunction wrongTypeFallback =
                                new TestFunction("wrong-type-fallback");
                            structure.getPersistentData().putInt(
                                "CreateTestFunction", 7);
                            GameTestRegistry.reset();
                            GameTestRegistry.FUNCTIONS.put(
                                "wrong-type", wrongTypeFallback);
                            Optional<TestFunction> wrongType =
                                invoke("wrong-type", pos, level, retry);
                            if (wrongType.orElseThrow() != wrongTypeFallback ||
                                    GameTestRegistry.findCalls != 1)
                                throw new AssertionError(
                                    "non-string fixture tag changed selection");

                            TestFunction selected =
                                new TestFunction("create-selected");
                            CreateTestFunction.NAMES_TO_FUNCTIONS.clear();
                            new CreateTestFunction("fixture.create", selected);
                            structure.getPersistentData().putString(
                                "CreateTestFunction", "fixture.create");
                            GameTestRegistry.reset();
                            GameTestRegistry.FUNCTIONS.put(
                                "metadata-name",
                                new TestFunction("must-not-be-used"));
                            Optional<TestFunction> create =
                                invoke("metadata-name", pos, level, retry);
                            if (create.orElseThrow() != selected)
                                throw new AssertionError(
                                    "Create fixture selection identity changed");
                            if (GameTestRegistry.findCalls != 0)
                                throw new AssertionError(
                                    "Create fixture fell through to registry");

                            CreateTestFunction.NAMES_TO_FUNCTIONS.clear();
                            structure.getPersistentData().putString(
                                "CreateTestFunction", "fixture.missing");
                            GameTestRegistry.reset();
                            try {
                                invoke("metadata-name", pos, level, retry);
                                throw new AssertionError(
                                    "missing Create fixture did not fail");
                            } catch (IllegalStateException expected) {
                                String message =
                                    "Structure block has CreateTestFunction " +
                                    "attached, but test [fixture.missing] " +
                                    "doesn't exist";
                                if (!message.equals(expected.getMessage()))
                                    throw new AssertionError(
                                        "missing fixture message changed");
                            }
                            if (GameTestRegistry.findCalls != 0)
                                throw new AssertionError(
                                    "missing Create fixture queried registry");
                            return "passed";
                        }
                    }
                """
            )
        )
        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for TestCommand migration semantics")
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
                loader.loadClass("probe.Probe").getMethod("verify").invoke(null)
            )
        }
    }

    @Test
    fun `raw upstream return and pipeline fallback are both normalized`() {
        val raw = legacyMixin(
            fallback = "GameTestRegistry.getTestFunction(testName)",
            selected = "function"
        )
        val pipeline = legacyMixin(
            fallback =
                "GameTestRegistry.findTestFunction(testName).orElseThrow()",
            selected = "function.modporter${'$'}asTestFunction()"
        )
        val pass = StructuralRefactorPass()
        val migratedRaw = pass.migrateCreateTestCommandMixin(raw)
        val migratedPipeline = pass.migrateCreateTestCommandMixin(pipeline)

        assertTrue(
            migratedRaw.contains(
                "return java.util.Optional.of(function);"
            ),
            migratedRaw
        )
        assertTrue(
            migratedPipeline.contains(
                "return java.util.Optional.of(" +
                    "function.modporter${'$'}asTestFunction());"
            ),
            migratedPipeline
        )
        for (migrated in listOf(migratedRaw, migratedPipeline)) {
            assertTrue(
                migrated.contains(
                    "GameTestRegistry.findTestFunction(testName)"
                ),
                migrated
            )
            assertFalse(
                migrated.contains("findTestFunction(testName).orElseThrow()"),
                migrated
            )
            assertEquals(
                migrated,
                pass.migrateCreateTestCommandMixin(migrated)
            )
        }
    }

    @Test
    fun `documentation fake owners and changed side effects fail closed`() {
        val documentation = """
            package example;
            final class Notes {
                /*
                 * @Mixin(TestCommand.class)
                 * runTest(ServerLevel, BlockPos, MultipleTestTracker)
                 * GameTestRegistry.getTestFunction(testName)
                 */
            }
        """.trimIndent()
        val fakeMixin = legacyMixin(
            fallback = "GameTestRegistry.getTestFunction(testName)",
            selected = "function"
        ).replace(
            "import org.spongepowered.asm.mixin.Mixin;",
            "import example.Mixin;"
        )
        val wrongOwner = legacyMixin(
            fallback = "GameTestRegistry.getTestFunction(testName)",
            selected = "function"
        ).replace(
            "public class TestCommandMixin",
            "public class OtherMixin"
        )
        val changedSideEffect = legacyMixin(
            fallback = "GameTestRegistry.getTestFunction(testName)",
            selected = "function"
        ).replace(
            "CompoundTag data = be.getPersistentData();",
            "CompoundTag data = be.getPersistentData();\n" +
                "\t\taudit(data);"
        )
        val broadRequire = legacyMixin(
            fallback = "GameTestRegistry.getTestFunction(testName)",
            selected = "function"
        ).replace("require = 0", "require = 1")
        val localRegistry = legacyMixin(
            fallback = "GameTestRegistry.getTestFunction(testName)",
            selected = "function"
        ).replace(
            "import net.minecraft.gametest.framework.GameTestRegistry;\n",
            ""
        ).replace(
            "public class TestCommandMixin {",
            """
                class GameTestRegistry {
                    static TestFunction getTestFunction(String name) {
                        return null;
                    }
                }
                public class TestCommandMixin {
            """.trimIndent()
        )

        val pass = StructuralRefactorPass()
        for (source in listOf(
            documentation,
            fakeMixin,
            wrongOwner,
            changedSideEffect,
            broadRequire,
            localRegistry
        )) {
            assertEquals(source, pass.migrateCreateTestCommandMixin(source))
        }
    }

    private fun legacyMixin(fallback: String, selected: String): String = """
        package com.simibubi.create.foundation.mixin;

        import javax.annotation.Nullable;

        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.Redirect;

        import com.simibubi.create.infrastructure.gametest.CreateTestFunction;

        import net.minecraft.core.BlockPos;
        import net.minecraft.gametest.framework.GameTestRegistry;
        import net.minecraft.gametest.framework.MultipleTestTracker;
        import net.minecraft.gametest.framework.TestCommand;
        import net.minecraft.gametest.framework.TestFunction;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.nbt.Tag;
        import net.minecraft.server.level.ServerLevel;
        import net.minecraft.world.level.block.entity.StructureBlockEntity;

        @Mixin(TestCommand.class)
        public class TestCommandMixin {
            @Redirect(
                    method = "runTest(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/gametest/framework/MultipleTestTracker;)V",
                    at = @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/gametest/framework/GameTestRegistry;getTestFunction(Ljava/lang/String;)Lnet/minecraft/gametest/framework/TestFunction;"
                    ),
                    require = 0 // don't crash if this fails. non-critical
            )
            private static TestFunction create${'$'}getCorrectTestFunction(
                    String testName,
                    ServerLevel level,
                    BlockPos pos,
                    @Nullable MultipleTestTracker tracker) {
                StructureBlockEntity be =
                    (StructureBlockEntity) level.getBlockEntity(pos);
                CompoundTag data = be.getPersistentData();
                if (!data.contains("CreateTestFunction", Tag.TAG_STRING))
                    return $fallback;
                String name = data.getString("CreateTestFunction");
                CreateTestFunction function =
                    CreateTestFunction.NAMES_TO_FUNCTIONS.get(name);
                if (function == null)
                    throw new IllegalStateException(
                        "Structure block has CreateTestFunction attached, but test [" +
                            name + "] doesn't exist");
                return $selected;
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
