package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCreateMixinSignatureFamilies
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

class CreateDispenserInvokerMigrationTest {
    @TempDir
    lateinit var directory: Path

    private val legacyAccessor = """
        package com.simibubi.create.foundation.mixin.accessor;

        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.gen.Invoker;

        import net.minecraft.core.dispenser.DispenseItemBehavior;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.block.DispenserBlock;

        @Mixin(DispenserBlock.class)
        public interface DispenserBlockAccessor {
            @Invoker("getDispenseMethod")
            DispenseItemBehavior create${'$'}callGetDispenseMethod(ItemStack stack);
        }
    """.trimIndent()

    private val legacyConverter = """
        package com.simibubi.create.impl.contraption.dispenser;

        import org.jetbrains.annotations.Nullable;

        import com.simibubi.create.foundation.mixin.accessor.DispenserBlockAccessor;

        import net.minecraft.core.dispenser.DispenseItemBehavior;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.block.Blocks;

        public enum DispenserBehaviorConverter {
            INSTANCE;

            @Nullable
            private static DispenseItemBehavior getDispenseMethod(ItemStack stack) {
                return ((DispenserBlockAccessor) Blocks.DISPENSER).create${'$'}callGetDispenseMethod(stack);
            }

            public static DispenseItemBehavior probe(ItemStack stack) {
                return getDispenseMethod(stack);
            }
        }
    """.trimIndent()

    @Test
    fun `native level reaches the invoker and an absent server never invokes it`() {
        val pass = StructuralRefactorPass()
        val accessor = pass.migrateCreateMixinSignatureFamilies(legacyAccessor)
        val converter = pass.migrateCreateMixinSignatureFamilies(legacyConverter)

        assertTrue(
            accessor.contains(
                "net.minecraft.world.level.Level level, ItemStack stack"
            ),
            accessor
        )
        assertTrue(
            converter.contains(
                "ServerLifecycleHooks.getCurrentServer()"
            ),
            converter
        )
        assertTrue(
            converter.contains(
                "server.getLevel(net.minecraft.world.level.Level.OVERWORLD), stack"
            ),
            converter
        )
        assertEquals(accessor, pass.migrateCreateMixinSignatureFamilies(accessor))
        assertEquals(converter, pass.migrateCreateMixinSignatureFamilies(converter))

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
                "org/spongepowered/asm/mixin/gen/Invoker.java",
                """
                    package org.spongepowered.asm.mixin.gen;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.METHOD)
                    public @interface Invoker {
                        String value();
                    }
                """
            ),
            writeJava(
                "org/jetbrains/annotations/Nullable.java",
                """
                    package org.jetbrains.annotations;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Target;
                    @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
                    public @interface Nullable {}
                """
            ),
            writeJava(
                "net/minecraft/world/level/Level.java",
                """
                    package net.minecraft.world.level;
                    public class Level {
                        public static final Object OVERWORLD = new Object();
                    }
                """
            ),
            writeJava(
                "net/minecraft/world/item/ItemStack.java",
                """
                    package net.minecraft.world.item;
                    public final class ItemStack {}
                """
            ),
            writeJava(
                "net/minecraft/core/dispenser/DispenseItemBehavior.java",
                """
                    package net.minecraft.core.dispenser;
                    public interface DispenseItemBehavior {}
                """
            ),
            writeJava(
                "net/minecraft/server/MinecraftServer.java",
                """
                    package net.minecraft.server;
                    import net.minecraft.world.level.Level;
                    public final class MinecraftServer {
                        private final Level level;
                        public Object requestedKey;
                        public int levelRequests;
                        public MinecraftServer(Level level) {
                            this.level = level;
                        }
                        public Level getLevel(Object key) {
                            requestedKey = key;
                            levelRequests++;
                            return level;
                        }
                    }
                """
            ),
            writeJava(
                "net/neoforged/neoforge/server/ServerLifecycleHooks.java",
                """
                    package net.neoforged.neoforge.server;
                    import net.minecraft.server.MinecraftServer;
                    public final class ServerLifecycleHooks {
                        public static MinecraftServer current;
                        public static MinecraftServer getCurrentServer() {
                            return current;
                        }
                    }
                """
            ),
            writeJava(
                "com/simibubi/create/foundation/mixin/accessor/DispenserBlockAccessor.java",
                accessor
            ),
            writeJava(
                "net/minecraft/world/level/block/DispenserBlock.java",
                """
                    package net.minecraft.world.level.block;
                    import com.simibubi.create.foundation.mixin.accessor.DispenserBlockAccessor;
                    import net.minecraft.core.dispenser.DispenseItemBehavior;
                    import net.minecraft.world.item.ItemStack;
                    import net.minecraft.world.level.Level;
                    public class DispenserBlock implements DispenserBlockAccessor {
                        public static int calls;
                        public static Level receivedLevel;
                        public static ItemStack receivedStack;
                        public static DispenseItemBehavior result;
                        public static void reset() {
                            calls = 0;
                            receivedLevel = null;
                            receivedStack = null;
                        }
                        @Override
                        public DispenseItemBehavior create${'$'}callGetDispenseMethod(
                                Level level, ItemStack stack) {
                            calls++;
                            receivedLevel = level;
                            receivedStack = stack;
                            return result;
                        }
                    }
                """
            ),
            writeJava(
                "net/minecraft/world/level/block/Blocks.java",
                """
                    package net.minecraft.world.level.block;
                    public final class Blocks {
                        public static final DispenserBlock DISPENSER = new DispenserBlock();
                    }
                """
            ),
            writeJava(
                "com/simibubi/create/impl/contraption/dispenser/DispenserBehaviorConverter.java",
                converter
            ),
            writeJava(
                "probe/Probe.java",
                """
                    package probe;
                    import com.simibubi.create.impl.contraption.dispenser.DispenserBehaviorConverter;
                    import net.minecraft.core.dispenser.DispenseItemBehavior;
                    import net.minecraft.server.MinecraftServer;
                    import net.minecraft.world.item.ItemStack;
                    import net.minecraft.world.level.Level;
                    import net.minecraft.world.level.block.DispenserBlock;
                    import net.neoforged.neoforge.server.ServerLifecycleHooks;
                    public final class Probe {
                        public static String verify() {
                            Level sentinelLevel = new Level();
                            ItemStack sentinelStack = new ItemStack();
                            DispenseItemBehavior sentinelBehavior =
                                new DispenseItemBehavior() {};
                            MinecraftServer server = new MinecraftServer(sentinelLevel);
                            ServerLifecycleHooks.current = server;
                            DispenserBlock.reset();
                            DispenserBlock.result = sentinelBehavior;

                            Object actual =
                                DispenserBehaviorConverter.probe(sentinelStack);
                            if (actual != sentinelBehavior)
                                throw new AssertionError("behavior identity changed");
                            if (DispenserBlock.calls != 1)
                                throw new AssertionError("invoker call count");
                            if (DispenserBlock.receivedLevel != sentinelLevel)
                                throw new AssertionError("level identity changed");
                            if (DispenserBlock.receivedStack != sentinelStack)
                                throw new AssertionError("stack identity changed");
                            if (server.requestedKey != Level.OVERWORLD ||
                                    server.levelRequests != 1)
                                throw new AssertionError("overworld lookup was not exact");

                            ServerLifecycleHooks.current = null;
                            DispenserBlock.reset();
                            if (DispenserBehaviorConverter.probe(sentinelStack) != null)
                                throw new AssertionError("absent server must return no behavior");
                            if (DispenserBlock.calls != 0)
                                throw new AssertionError("absent server invoked dispenser");
                            return "passed";
                        }
                    }
                """
            )
        )
        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for dispenser migration semantics")
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
    fun `fully qualified native owners are admitted without simple name guesses`() {
        val accessor = """
            package com.simibubi.create.foundation.mixin.accessor;
            @org.spongepowered.asm.mixin.Mixin(
                net.minecraft.world.level.block.DispenserBlock.class
            )
            public interface DispenserBlockAccessor {
                @org.spongepowered.asm.mixin.gen.Invoker("getDispenseMethod")
                net.minecraft.core.dispenser.DispenseItemBehavior
                    create${'$'}callGetDispenseMethod(
                        net.minecraft.world.item.ItemStack stack);
            }
        """.trimIndent()
        val converter = """
            package com.simibubi.create.impl.contraption.dispenser;
            public enum DispenserBehaviorConverter {
                INSTANCE;
                @org.jetbrains.annotations.Nullable
                private static net.minecraft.core.dispenser.DispenseItemBehavior
                        getDispenseMethod(net.minecraft.world.item.ItemStack stack) {
                    return ((com.simibubi.create.foundation.mixin.accessor.DispenserBlockAccessor)
                        net.minecraft.world.level.block.Blocks.DISPENSER)
                        .create${'$'}callGetDispenseMethod(stack);
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val widened = pass.migrateCreateMixinSignatureFamilies(accessor)
        val bridged = pass.migrateCreateMixinSignatureFamilies(converter)
        assertTrue(widened.contains("net.minecraft.world.level.Level level"), widened)
        assertTrue(bridged.contains("ServerLifecycleHooks.getCurrentServer()"), bridged)
        assertEquals(widened, pass.migrateCreateMixinSignatureFamilies(widened))
        assertEquals(bridged, pass.migrateCreateMixinSignatureFamilies(bridged))
    }

    @Test
    fun `documentation fake owners and nontrivial helper bodies fail closed`() {
        val documentation = """
            package example;
            final class Documentation {
                /*
                 * @Mixin(DispenserBlock.class)
                 * @Invoker("getDispenseMethod")
                 * create${'$'}callGetDispenseMethod(ItemStack stack)
                 */
            }
        """.trimIndent()
        val fakeAnnotations = """
            package com.simibubi.create.foundation.mixin.accessor;
            import example.Mixin;
            import example.Invoker;
            import net.minecraft.core.dispenser.DispenseItemBehavior;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.DispenserBlock;
            @Mixin(DispenserBlock.class)
            public interface DispenserBlockAccessor {
                @Invoker("getDispenseMethod")
                DispenseItemBehavior create${'$'}callGetDispenseMethod(ItemStack stack);
            }
        """.trimIndent()
        val wrongOwner = legacyAccessor.replace(
            "public interface DispenserBlockAccessor",
            "public interface OtherAccessor"
        )
        val extraStatement = legacyConverter.replace(
            "return ((DispenserBlockAccessor)",
            "audit();\n                return ((DispenserBlockAccessor)"
        ).replace(
            "public static DispenseItemBehavior probe",
            "private static void audit() {}\n\n            public static DispenseItemBehavior probe"
        )
        val shadowedBlocks = legacyConverter
            .replace("import net.minecraft.world.level.block.Blocks;\n", "")
            .replace(
                "INSTANCE;",
                """
                    INSTANCE;
                    static final class Blocks {
                        static final net.minecraft.world.level.block.DispenserBlock DISPENSER =
                            new net.minecraft.world.level.block.DispenserBlock();
                    }
                """.trimIndent()
            )

        val pass = StructuralRefactorPass()
        for (source in listOf(
            documentation,
            fakeAnnotations,
            wrongOwner,
            extraStatement,
            shadowedBlocks
        )) {
            assertEquals(source, pass.migrateCreateMixinSignatureFamilies(source))
        }
        assertFalse(
            pass.migrateCreateMixinSignatureFamilies(extraStatement)
                .contains("ServerLifecycleHooks.getCurrentServer()")
        )
    }

    private fun writeJava(relative: String, source: String): Path {
        val path = directory.resolve("sources").resolve(relative)
        path.parent.createDirectories()
        path.writeText(source.trimIndent() + "\n")
        return path
    }
}
