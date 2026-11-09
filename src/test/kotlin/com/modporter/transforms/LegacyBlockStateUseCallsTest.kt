package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyBlockStateUseCalls
import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyBlockStateUseCallsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `legacy block state use keeps held item fallback results and evaluation order`() {
        val compatPackage = "com.modporter.generated.example.compat"
        writeJava(
            "example/Probe.java",
            """
            package example;

            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.ItemInteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public final class Probe {
                private static final StringBuilder LOG = new StringBuilder();
                private static BlockState state;
                private static Player player;

                private static BlockState state() {
                    LOG.append("r");
                    return state;
                }

                private static Level level() {
                    LOG.append("l");
                    return new Level();
                }

                private static Player player() {
                    LOG.append("p");
                    return player;
                }

                private static InteractionHand hand() {
                    LOG.append("h");
                    return InteractionHand.MAIN_HAND;
                }

                private static BlockHitResult hit() {
                    LOG.append("x");
                    return new BlockHitResult();
                }

                private static InteractionResult invoke() {
                    return state().use(
                        level(), player(), hand(), hit()
                    );
                }

                public static String verify() {
                    ItemStack held = new ItemStack("held");
                    player = new Player(held, LOG);

                    state = new BlockState(
                        ItemInteractionResult.SUCCESS,
                        InteractionResult.FALLBACK,
                        LOG
                    );
                    LOG.setLength(0);
                    InteractionResult success = invoke();
                    if (success != InteractionResult.SUCCESS ||
                        state.seenStack != held ||
                        state.itemCalls != 1 ||
                        state.fallbackCalls != 0 ||
                        player.reads != 1 ||
                        !"rlphxiuo".contentEquals(LOG)) {
                        throw new AssertionError("item path: " + LOG);
                    }

                    state = new BlockState(
                        ItemInteractionResult.FAIL,
                        InteractionResult.FALLBACK,
                        LOG
                    );
                    player.reads = 0;
                    LOG.setLength(0);
                    InteractionResult failure = invoke();
                    if (failure != InteractionResult.FAIL ||
                        state.itemCalls != 1 ||
                        state.fallbackCalls != 0 ||
                        player.reads != 1 ||
                        !"rlphxiuo".contentEquals(LOG)) {
                        throw new AssertionError("terminal path: " + LOG);
                    }

                    state = new BlockState(
                        ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION,
                        InteractionResult.FALLBACK,
                        LOG
                    );
                    player.reads = 0;
                    LOG.setLength(0);
                    InteractionResult fallback = invoke();
                    if (fallback != InteractionResult.FALLBACK ||
                        state.seenStack != held ||
                        state.itemCalls != 1 ||
                        state.fallbackCalls != 1 ||
                        player.reads != 1 ||
                        !"rlphxiuf".contentEquals(LOG)) {
                        throw new AssertionError("fallback path: " + LOG);
                    }

                    state = null;
                    player.reads = 0;
                    LOG.setLength(0);
                    try {
                        invoke();
                        throw new AssertionError("null state did not fail");
                    } catch (NullPointerException expected) {
                        if (player.reads != 0 || !"rlphx".contentEquals(LOG))
                            throw new AssertionError("null state side effects: " + LOG);
                    }

                    state = new BlockState(
                        ItemInteractionResult.SUCCESS,
                        InteractionResult.FALLBACK,
                        LOG
                    );
                    player = null;
                    LOG.setLength(0);
                    try {
                        invoke();
                        throw new AssertionError("null player did not fail");
                    } catch (NullPointerException expected) {
                        if (state.itemCalls != 0 || !"rlphx".contentEquals(LOG))
                            throw new AssertionError("null player side effects: " + LOG);
                    }
                    return "passed";
                }
            }
            """
        )
        writeJava(
            "net/minecraft/world/InteractionHand.java",
            """
            package net.minecraft.world;
            public enum InteractionHand {
                MAIN_HAND
            }
            """
        )
        writeJava(
            "net/minecraft/world/InteractionResult.java",
            """
            package net.minecraft.world;
            public enum InteractionResult {
                SUCCESS,
                FAIL,
                FALLBACK
            }
            """
        )
        writeJava(
            "net/minecraft/world/ItemInteractionResult.java",
            """
            package net.minecraft.world;

            import example.ProbeLog;

            public enum ItemInteractionResult {
                SUCCESS(InteractionResult.SUCCESS),
                FAIL(InteractionResult.FAIL),
                PASS_TO_DEFAULT_BLOCK_INTERACTION(InteractionResult.FALLBACK);

                private final InteractionResult result;

                ItemInteractionResult(InteractionResult result) {
                    this.result = result;
                }

                public InteractionResult result() {
                    ProbeLog.append("o");
                    return result;
                }
            }
            """
        )
        writeJava(
            "example/ProbeLog.java",
            """
            package example;

            public final class ProbeLog {
                private static StringBuilder log;

                public static void bind(StringBuilder target) {
                    log = target;
                }

                public static void append(String value) {
                    if (log != null)
                        log.append(value);
                }
            }
            """
        )
        writeJava(
            "net/minecraft/world/item/ItemStack.java",
            """
            package net.minecraft.world.item;

            public final class ItemStack {
                public final String name;

                public ItemStack(String name) {
                    this.name = name;
                }
            }
            """
        )
        writeJava(
            "net/minecraft/world/entity/player/Player.java",
            """
            package net.minecraft.world.entity.player;

            import example.ProbeLog;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.item.ItemStack;

            public final class Player {
                private final ItemStack held;
                public int reads;

                public Player(ItemStack held, StringBuilder log) {
                    this.held = held;
                    ProbeLog.bind(log);
                }

                public ItemStack getItemInHand(InteractionHand hand) {
                    reads++;
                    ProbeLog.append("i");
                    return held;
                }
            }
            """
        )
        writeJava(
            "net/minecraft/world/level/Level.java",
            """
            package net.minecraft.world.level;
            public final class Level {
            }
            """
        )
        writeJava(
            "net/minecraft/world/phys/BlockHitResult.java",
            """
            package net.minecraft.world.phys;
            public final class BlockHitResult {
            }
            """
        )
        writeJava(
            "net/minecraft/world/level/block/state/BlockState.java",
            """
            package net.minecraft.world.level.block.state;

            import example.ProbeLog;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.ItemInteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.phys.BlockHitResult;

            public final class BlockState {
                private final ItemInteractionResult itemResult;
                private final InteractionResult fallbackResult;
                public ItemStack seenStack;
                public int itemCalls;
                public int fallbackCalls;

                public BlockState(
                    ItemInteractionResult itemResult,
                    InteractionResult fallbackResult,
                    StringBuilder log
                ) {
                    this.itemResult = itemResult;
                    this.fallbackResult = fallbackResult;
                    ProbeLog.bind(log);
                }

                public ItemInteractionResult useItemOn(
                    ItemStack stack,
                    Level level,
                    Player player,
                    InteractionHand hand,
                    BlockHitResult hit
                ) {
                    seenStack = stack;
                    itemCalls++;
                    ProbeLog.append("u");
                    return itemResult;
                }

                public InteractionResult useWithoutItem(
                    Level level,
                    Player player,
                    BlockHitResult hit
                ) {
                    fallbackCalls++;
                    ProbeLog.append("f");
                    return fallbackResult;
                }
            }
            """
        )

        val first = migrate(directory, compatPackage)
        assertTrue(first.isNotEmpty())
        val probe = directory.resolve("src/main/java/example/Probe.java")
        assertTrue(
            probe.readText().contains(
                "$compatPackage.LegacyBlockStateUse.use(" +
                    "state(), level(), player(), hand(), hit())"
            ),
            probe.readText()
        )
        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/example/compat/LegacyBlockStateUse.java"
        )
        assertTrue(helper.isRegularFile())
        val probeAfterFirst = probe.readText()
        val helperAfterFirst = helper.readText()
        assertTrue(
            migrate(directory, compatPackage).isEmpty()
        )
        assertEquals(probeAfterFirst, probe.readText())
        assertEquals(helperAfterFirst, helper.readText())

        val classes = directory.resolve("classes").createDirectories()
        val sources = Files.walk(directory.resolve("src/main/java")).use { paths ->
            paths.filter { it.isRegularFile() && it.toString().endsWith(".java") }.toList()
        }
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for BlockState use semantics")
        assertEquals(
            0,
            compiler.run(
                null,
                diagnostics,
                diagnostics,
                "-proc:none",
                "-d",
                classes.toString(),
                *sources.map(Path::toString).toTypedArray()
            ),
            diagnostics.toString(Charsets.UTF_8)
        )
        URLClassLoader(
            arrayOf(classes.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        ).use { loader ->
            assertEquals(
                "passed",
                loader.loadClass("example.Probe").getMethod("verify").invoke(null)
            )
        }
    }

    @Test
    fun `native nested calls rewrite deepest first while fake types remain unchanged`() {
        val compatPackage = "com.modporter.generated.example.compat"
        val nativeProject = directory.resolve("native")
        val native = writeJava(
            nativeProject,
            "example/Calls.java",
            """
            package example;

            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public final class Calls {
                static BlockState choose(InteractionResult ignored, BlockState fallback) {
                    return fallback;
                }

                static InteractionResult nested(
                    BlockState first,
                    BlockState second,
                    Level level,
                    Player player,
                    InteractionHand hand,
                    BlockHitResult hit
                ) {
                    return choose(
                        first.use (level, player, hand, hit), second
                    ).use(level, player, hand, hit);
                }
            }
            """
        )
        val nativeChanges = migrate(nativeProject, compatPackage)
        assertTrue(nativeChanges.isNotEmpty())
        val migrated = native.readText()
        assertEquals(
            2,
            Regex(Regex.escape("$compatPackage.LegacyBlockStateUse.use("))
                .findAll(migrated)
                .count(),
            migrated
        )
        assertFalse(Regex("""\.\s*use\s*\(""").containsMatchIn(
            migrated.replace("$compatPackage.LegacyBlockStateUse.use(", "")
        ), migrated)

        val fakeProject = directory.resolve("fake")
        val fake = writeJava(
            fakeProject,
            "example/FakeCalls.java",
            """
            package example;

            final class FakeCalls {
                void call(BlockState state, FakeLevel level, Object player, Object hand, Object hit) {
                    state.use (level, player, hand, hit);
                    level.getBlockState(new Object()).use(level, player, hand, hit);
                }
            }

            final class BlockState {
                void use(Object level, Object player, Object hand, Object hit) {
                }
            }

            final class FakeLevel {
                BlockState getBlockState(Object pos) {
                    return new BlockState();
                }
            }
            """
        )
        val fakeBefore = fake.readText()
        assertTrue(
            migrate(fakeProject, compatPackage).isEmpty()
        )
        assertEquals(fakeBefore, fake.readText())
        assertFalse(
            fakeProject.resolve(
                "src/main/java/com/modporter/generated/example/compat/LegacyBlockStateUse.java"
            ).isRegularFile()
        )
    }

    @Test
    fun `inherited project field reaches native block entity getBlockState`() {
        val project = directory.resolve("inherited-native")
        val compatPackage = "com.modporter.generated.example.compat"
        writeJava(
            project,
            "example/SmartBlockEntity.java",
            """
            package example;

            import net.minecraft.world.level.block.entity.BlockEntity;

            public abstract class SmartBlockEntity extends BlockEntity {
            }
            """
        )
        writeJava(
            project,
            "example/Behaviour.java",
            """
            package example;

            public abstract class Behaviour {
                public SmartBlockEntity blockEntity;
            }
            """
        )
        val caller = writeJava(
            project,
            "example/Calls.java",
            """
            package example;

            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.phys.BlockHitResult;

            public final class Calls extends Behaviour {
                void call(
                    Level level,
                    Player player,
                    InteractionHand hand,
                    BlockHitResult hit
                ) {
                    blockEntity.getBlockState()
                        .use(level, player, hand, hit);
                }
            }
            """
        )

        val changes = migrate(project, compatPackage)
        val migrated = caller.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(
            migrated.contains(
                "$compatPackage.LegacyBlockStateUse.use(" +
                    "blockEntity.getBlockState(), level, player, hand, hit)"
            ),
            migrated
        )
    }

    @Test
    fun `inherited project field with fake block entity remains unchanged`() {
        val project = directory.resolve("inherited-fake")
        val compatPackage = "com.modporter.generated.example.compat"
        writeJava(
            project,
            "example/FakeBlockEntity.java",
            """
            package example;

            public class FakeBlockEntity {
                public BlockState getBlockState() {
                    return new BlockState();
                }
            }
            """
        )
        writeJava(
            project,
            "example/SmartBlockEntity.java",
            """
            package example;

            public final class SmartBlockEntity extends FakeBlockEntity {
            }
            """
        )
        writeJava(
            project,
            "example/Behaviour.java",
            """
            package example;

            public abstract class Behaviour {
                public SmartBlockEntity blockEntity;
            }
            """
        )
        writeJava(
            project,
            "example/BlockState.java",
            """
            package example;

            public final class BlockState {
                public void use(
                    Object level,
                    Object player,
                    Object hand,
                    Object hit
                ) {
                }
            }
            """
        )
        val caller = writeJava(
            project,
            "example/Calls.java",
            """
            package example;

            public final class Calls extends Behaviour {
                void call(Object level, Object player, Object hand, Object hit) {
                    blockEntity.getBlockState()
                        .use(level, player, hand, hit);
                }
            }
            """
        )
        val original = caller.readText()

        assertTrue(migrate(project, compatPackage).isEmpty())
        assertEquals(original, caller.readText())
        assertFalse(
            project.resolve(
                "src/main/java/com/modporter/generated/example/compat/LegacyBlockStateUse.java"
            ).isRegularFile()
        )
    }

    @Test
    fun `foreign helper collision fails before changing callers`() {
        val project = directory.resolve("collision")
        val compatPackage = "com.modporter.generated.example.compat"
        val caller = writeJava(
            project,
            "example/Calls.java",
            """
            package example;

            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            final class Calls {
                InteractionResult call(
                    BlockState state,
                    Level level,
                    Player player,
                    InteractionHand hand,
                    BlockHitResult hit
                ) {
                    return state.use(level, player, hand, hit);
                }
            }
            """
        )
        val before = caller.readText()
        val helper = project.resolve(
            "src/main/java/com/modporter/generated/example/compat/LegacyBlockStateUse.java"
        )
        helper.parent.createDirectories()
        helper.writeText(
            "package $compatPackage; public final class LegacyBlockStateUse {}\n"
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            migrate(project, compatPackage)
        }
        assertTrue(failure.message.orEmpty().contains("differs from the canonical"))
        assertEquals(before, caller.readText())
    }

    private fun migrate(project: Path, compatPackage: String) =
        StructuralRefactorPass().let { pass ->
            val javaFiles = Files.walk(project.resolve("src/main/java")).use { paths ->
                paths.filter { it.toString().endsWith(".java") }
                    .filter {
                        !it.toString().replace('\\', '/')
                            .contains("/com/modporter/generated/")
                    }
                    .toList()
            }
            pass.projectValueFacts = ProjectValueFacts.collect(pass, javaFiles)
            LegacyBlockStateUseCalls.migrate(
                pass,
                project,
                { compatPackage },
                dryRun = false
            )
        }

    private fun writeJava(relative: String, source: String): Path {
        return writeJava(directory, relative, source)
    }

    private fun writeJava(root: Path, relative: String, source: String): Path {
        val file = root.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
        return file
    }
}
