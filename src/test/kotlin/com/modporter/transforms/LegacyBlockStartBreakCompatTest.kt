package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyBlockStartBreakCompatMigration
import com.modporter.core.transforms.structural.LegacyMigrationArtifactInventory
import com.modporter.core.transforms.structural.LegacyMigrationArtifactPair
import com.modporter.core.transforms.structural.SealedLegacyPlantSourceHeritage
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyEntitySizeEyeHeight
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyBlockStartBreakCompatTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `exact Java17 platform image resolves current runtime classes`() {
        val heritage = SealedLegacyPlantSourceHeritage.java17Platform()

        assertTrue(heritage.hasClass("java.lang.Object"))
        assertTrue(heritage.hasClass("java.lang.Iterable"))
        assertTrue(
            heritage.directHeritage("java.lang.Iterable").orEmpty()
                .contains("java.lang.Object")
        )
    }

    @Test
    fun `missing optional heritage stays latent until traversal reaches it`() {
        val inventory = fixture(dependencyOverride = false).inventory

        val heritage = SealedLegacyPlantSourceHeritage.from(inventory)
        assertTrue(heritage.hasClass("dep.OptionalIntegration"))
        val failure = assertFailsWith<IllegalArgumentException> {
            heritage.directHeritage("dep.OptionalIntegration")
        }

        assertTrue(failure.message.orEmpty().contains("missing/OptionalContract"))
    }

    @Test
    fun `stack cancellation call retains the proven old default without a native hook`() {
        val fixture = fixture(dependencyOverride = false)
        val sourceFile = fixture.project.resolve("src/main/java/example/Deployer.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;

            class Deployer {
                static boolean harvest(ServerPlayer player, BlockPos pos) {
                    if (player.getMainHandItem().onBlockStartBreak(pos, player))
                        return false;
                    return breakBlock(pos);
                }
            }
            """.trimIndent()
        )

        val pass = StructuralRefactorPass()
        val changes = LegacyBlockStartBreakCompatMigration.migrate(
            pass,
            fixture.project,
            dryRun = false,
            inventory = fixture.inventory
        )
        val migrated = sourceFile.readText()
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertTrue(changes.isNotEmpty())
        assertTrue(
            compact.contains(
                "com.modporter.compat.item.v1." +
                    "LegacyBlockStartBreakHooks.onBlockStartBreak(" +
                    "player.getMainHandItem(),pos,player)"
            ),
            migrated
        )
        assertEquals(
            1,
            Regex("""player\.getMainHandItem\(\)""").findAll(migrated).count(),
            migrated
        )

        val compat = fixture.project.resolve(
            "src/main/java/com/modporter/compat/item/v1/" +
                "LegacyBlockStartBreakHooks.java"
        )
        val helper = compat.readText()
        assertTrue(
            helper.contains(
                "public static boolean onBlockStartBreak(\n" +
                    "        net.minecraft.world.item.ItemStack stack,"
            ),
            helper
        )
        assertTrue(
            helper.contains(
                "public static boolean onBlockStartBreak(\n" +
                    "        net.minecraft.world.item.Item item,"
            ),
            helper
        )
        assertTrue(
            helper.contains(
                "return onBlockStartBreak(stack.getItem(), stack, pos, player);"
            ),
            helper
        )
        assertTrue(helper.contains("java.util.Objects.requireNonNull(item);"), helper)
        assertEquals(1, Regex("""return false;""").findAll(helper).count(), helper)
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/compat/item/v1/" +
                    "LegacyBlockStartBreakItem.java"
            ).exists()
        )

        assertTrue(
            LegacyBlockStartBreakCompatMigration.migrate(
                pass,
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            ).isEmpty()
        )
    }

    @Test
    fun `generated default helper preserves nullness and argument evaluation order`() {
        val fixture = fixture(dependencyOverride = false)
        val sourceFile = fixture.project.resolve("src/main/java/example/Deployer.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;
            class Deployer {
                static boolean harvest(ServerPlayer player, BlockPos pos) {
                    return player.getMainHandItem()
                        .onBlockStartBreak(pos, player);
                }
            }
            """.trimIndent()
        )
        LegacyBlockStartBreakCompatMigration.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false,
            inventory = fixture.inventory
        )

        val semanticSources = fixture.project.resolve("semantic-sources")
        fun javaSource(path: String, source: String): Path =
            semanticSources.resolve(path).also {
                it.parent.createDirectories()
                it.writeText(source.trimIndent() + "\n")
            }

        val blockPos = javaSource(
            "net/minecraft/core/BlockPos.java",
            """
            package net.minecraft.core;
            public final class BlockPos {}
            """
        )
        val player = javaSource(
            "net/minecraft/world/entity/player/Player.java",
            """
            package net.minecraft.world.entity.player;
            public class Player {}
            """
        )
        val item = javaSource(
            "net/minecraft/world/item/Item.java",
            """
            package net.minecraft.world.item;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            public class Item {
                public boolean onBlockStartBreak(ItemStack stack, BlockPos pos, Player player) {
                    return false;
                }
            }
            """
        )
        val itemStack = javaSource(
            "net/minecraft/world/item/ItemStack.java",
            """
            package net.minecraft.world.item;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import probe.Probe;
            public final class ItemStack {
                private final Item item;
                public ItemStack(Item item) {
                    this.item = item;
                }
                public Item getItem() {
                    Probe.record("getItem");
                    return item;
                }
                public boolean onBlockStartBreak(BlockPos pos, Player player) {
                    return getItem().onBlockStartBreak(this, pos, player);
                }
            }
            """
        )
        val probe = javaSource(
            "probe/Probe.java",
            """
            package probe;
            import com.modporter.compat.item.v1.LegacyBlockStartBreakHooks;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            public final class Probe {
                private static final StringBuilder TRACE = new StringBuilder();
                private static ItemStack selectedStack;
                private static Item selectedItem;

                public static void record(String value) {
                    if (TRACE.length() > 0) TRACE.append(',');
                    TRACE.append(value);
                }

                private static ItemStack stack() {
                    record("stack");
                    return selectedStack;
                }

                private static Item item() {
                    record("item");
                    return selectedItem;
                }

                private static BlockPos pos() {
                    record("pos");
                    return new BlockPos();
                }

                private static Player player() {
                    record("player");
                    return new Player();
                }

                @FunctionalInterface
                private interface Action {
                    boolean run();
                }

                private static String capture(Action action) {
                    TRACE.setLength(0);
                    try {
                        boolean result = action.run();
                        return TRACE + "|return:" + result;
                    } catch (Throwable failure) {
                        return TRACE + "|throw:" + failure.getClass().getName();
                    }
                }

                private static void compare(
                    String name,
                    String expected,
                    Action legacy,
                    Action migrated
                ) {
                    String oldResult = capture(legacy);
                    String newResult = capture(migrated);
                    if (!expected.equals(oldResult) || !oldResult.equals(newResult)) {
                        throw new AssertionError(
                            name + ": expected=" + expected +
                                ", legacy=" + oldResult + ", migrated=" + newResult
                        );
                    }
                }

                public static String verify() {
                    selectedStack = null;
                    compare(
                        "null stack",
                        "stack,pos,player|throw:java.lang.NullPointerException",
                        () -> stack().onBlockStartBreak(pos(), player()),
                        () -> LegacyBlockStartBreakHooks.onBlockStartBreak(
                            stack(), pos(), player()
                        )
                    );

                    selectedStack = new ItemStack(null);
                    compare(
                        "null item from stack",
                        "stack,pos,player,getItem|throw:java.lang.NullPointerException",
                        () -> stack().onBlockStartBreak(pos(), player()),
                        () -> LegacyBlockStartBreakHooks.onBlockStartBreak(
                            stack(), pos(), player()
                        )
                    );

                    selectedStack = new ItemStack(new Item());
                    compare(
                        "stack default",
                        "stack,pos,player,getItem|return:false",
                        () -> stack().onBlockStartBreak(pos(), player()),
                        () -> LegacyBlockStartBreakHooks.onBlockStartBreak(
                            stack(), pos(), player()
                        )
                    );

                    selectedStack = new ItemStack(new Item());
                    selectedItem = null;
                    compare(
                        "null item receiver",
                        "item,stack,pos,player|throw:java.lang.NullPointerException",
                        () -> item().onBlockStartBreak(stack(), pos(), player()),
                        () -> LegacyBlockStartBreakHooks.onBlockStartBreak(
                            item(), stack(), pos(), player()
                        )
                    );

                    selectedItem = new Item();
                    compare(
                        "item default",
                        "item,stack,pos,player|return:false",
                        () -> item().onBlockStartBreak(stack(), pos(), player()),
                        () -> LegacyBlockStartBreakHooks.onBlockStartBreak(
                            item(), stack(), pos(), player()
                        )
                    );
                    return "passed";
                }
            }
            """
        )

        val helper = fixture.project.resolve(
            "src/main/java/com/modporter/compat/item/v1/" +
                "LegacyBlockStartBreakHooks.java"
        )
        val classes = fixture.project.resolve("semantic-classes")
        classes.createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for block-start helper semantics test")
        val compileResult = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-d",
            classes.toString(),
            blockPos.toString(),
            player.toString(),
            item.toString(),
            itemStack.toString(),
            helper.toString(),
            probe.toString()
        )
        assertEquals(0, compileResult, diagnostics.toString(Charsets.UTF_8))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            assertEquals(
                "passed",
                loader.loadClass("probe.Probe").getMethod("verify").invoke(null)
            )
        }
    }

    @Test
    fun `source item override fails before any source or helper write`() {
        val fixture = fixture(dependencyOverride = false)
        val sourceFile = fixture.project.resolve("src/main/java/example/LegacyTool.java")
        sourceFile.parent.createDirectories()
        val source = """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            class LegacyTool extends Item {
                @Override
                public boolean onBlockStartBreak(
                    ItemStack stack,
                    BlockPos pos,
                    Player player
                ) {
                    audit(stack, pos, player);
                    return super.onBlockStartBreak(stack, pos, player);
                }
            }

            class Deployer {
                static boolean harvest(ServerPlayer player, BlockPos pos) {
                    return player.getMainHandItem()
                        .onBlockStartBreak(pos, player);
                }
            }
        """.trimIndent()
        sourceFile.writeText(source)

        val failure = assertFailsWith<IllegalStateException> {
            LegacyBlockStartBreakCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "has no verified client/server player-mining dispatch"
            ),
            failure.message
        )
        assertEquals(source, sourceFile.readText())
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/compat/item/v1"
            ).exists()
        )
    }

    @Test
    fun `native platform item override fails before any source or helper write`() {
        val fixture = fixture(
            dependencyOverride = false,
            platformDeclaration = PlatformDeclaration.ITEM_OVERRIDE
        )
        val sourceFile = fixture.project.resolve("src/main/java/example/Deployer.java")
        sourceFile.parent.createDirectories()
        val source = """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;
            class Deployer {
                static boolean harvest(ServerPlayer player, BlockPos pos) {
                    return player.getMainHandItem()
                        .onBlockStartBreak(pos, player);
                }
            }
        """.trimIndent()
        sourceFile.writeText(source)

        val failure = assertFailsWith<IllegalStateException> {
            LegacyBlockStartBreakCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "declares native legacy Item override " +
                    "net.minecraft.world.item.NativeLegacyTool#onBlockStartBreak"
            ),
            failure.message
        )
        assertEquals(source, sourceFile.readText())
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/compat/item/v1"
            ).exists()
        )
    }

    @Test
    fun `unknown native platform declaration ancestry fails before any write`() {
        val fixture = fixture(
            dependencyOverride = false,
            platformDeclaration = PlatformDeclaration.UNKNOWN_ANCESTRY
        )
        val sourceFile = fixture.project.resolve("src/main/java/example/Deployer.java")
        sourceFile.parent.createDirectories()
        val source = """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;
            class Deployer {
                static boolean harvest(ServerPlayer player, BlockPos pos) {
                    return player.getMainHandItem()
                        .onBlockStartBreak(pos, player);
                }
            }
        """.trimIndent()
        sourceFile.writeText(source)

        val failure = assertFailsWith<IllegalStateException> {
            LegacyBlockStartBreakCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "Cannot classify sealed Forge source platform declaration " +
                    "generated:forge-source:platform.UnknownLegacyOwner#onBlockStartBreak"
            ),
            failure.message
        )
        assertEquals(source, sourceFile.readText())
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/compat/item/v1"
            ).exists()
        )
    }

    @Test
    fun `unrelated native platform declaration does not block call migration`() {
        val fixture = fixture(
            dependencyOverride = false,
            platformDeclaration = PlatformDeclaration.UNRELATED
        )
        val sourceFile = fixture.project.resolve("src/main/java/example/Deployer.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;
            class Deployer {
                static boolean harvest(ServerPlayer player, BlockPos pos) {
                    return player.getMainHandItem()
                        .onBlockStartBreak(pos, player);
                }
            }
            """.trimIndent()
        )

        val changes = LegacyBlockStartBreakCompatMigration.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false,
            inventory = fixture.inventory
        )

        assertTrue(changes.isNotEmpty())
        assertTrue(
            sourceFile.readText().contains(
                "com.modporter.compat.item.v1.LegacyBlockStartBreakHooks." +
                    "onBlockStartBreak"
            )
        )
    }

    @Test
    fun `unrelated conflicting dependency duplicates do not block migration`() {
        val fixture = fixture(
            dependencyOverride = false,
            duplicateClasspath = DuplicateClasspath.UNRELATED_CONFLICT
        )
        val sourceFile = writeBlockStartCaller(fixture)

        val changes = LegacyBlockStartBreakCompatMigration.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false,
            inventory = fixture.inventory
        )

        assertTrue(changes.isNotEmpty())
        assertTrue(
            sourceFile.readText().contains(
                "com.modporter.compat.item.v1.LegacyBlockStartBreakHooks." +
                    "onBlockStartBreak"
            )
        )
    }

    @Test
    fun `shadowed dependency override does not replace first ordered class`() {
        val fixture = fixture(
            dependencyOverride = false,
            duplicateClasspath = DuplicateClasspath.SHADOWED_OVERRIDE
        )
        val sourceFile = writeBlockStartCaller(fixture)

        val changes = LegacyBlockStartBreakCompatMigration.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false,
            inventory = fixture.inventory
        )

        assertTrue(changes.isNotEmpty())
        assertTrue(
            sourceFile.readText().contains(
                "com.modporter.compat.item.v1.LegacyBlockStartBreakHooks." +
                    "onBlockStartBreak"
            )
        )
    }

    @Test
    fun `first ordered dependency override rejects with shadow evidence`() {
        val fixture = fixture(
            dependencyOverride = false,
            duplicateClasspath = DuplicateClasspath.SELECTED_OVERRIDE
        )
        val sourceFile = writeBlockStartCaller(fixture)
        val source = sourceFile.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyBlockStartBreakCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "Dependency example:first-duplicate:1.0 declares unconverted legacy " +
                    "dep.OrderedTool#onBlockStartBreak"
            ),
            failure.message
        )
        assertTrue(
            failure.message.orEmpty().contains(
                "shadowed definitions: 2|example:second-duplicate:1.0"
            ),
            failure.message
        )
        assertEquals(source, sourceFile.readText())
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/compat/item/v1"
            ).exists()
        )
    }

    @Test
    fun `unconverted dependency item override fails before any source write`() {
        val fixture = fixture(dependencyOverride = true)
        val sourceFile = fixture.project.resolve("src/main/java/example/Deployer.java")
        sourceFile.parent.createDirectories()
        val source = """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;
            class Deployer {
                static boolean harvest(ServerPlayer player, BlockPos pos) {
                    return player.getMainHandItem()
                        .onBlockStartBreak(pos, player);
                }
            }
        """.trimIndent()
        sourceFile.writeText(source)

        val failure = assertFailsWith<IllegalStateException> {
            LegacyBlockStartBreakCompatMigration.migrate(
                StructuralRefactorPass(),
                fixture.project,
                dryRun = false,
                inventory = fixture.inventory
            )
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "declares unconverted legacy dep.LegacyTool#onBlockStartBreak"
            ),
            failure.message
        )
        assertEquals(source, sourceFile.readText())
        assertFalse(
            fixture.project.resolve(
                "src/main/java/com/modporter/compat/item/v1"
            ).exists()
        )
    }

    @Test
    fun `entity size eye height is retained in the target dimensions value`() {
        val source = """
            package example;
            import net.neoforged.neoforge.event.entity.EntityEvent;
            class DeployerEvents {
                static void eyesAtFeet(EntityEvent.Size event) {
                    audit(event.getNewSize());
                    event.setNewEyeHeight(0);
                    continueSizing(event);
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyEntitySizeEyeHeight(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertFalse(migrated.contains("setNewEyeHeight"), migrated)
        assertTrue(
            compact.contains(
                "event.setNewSize(" +
                    "event.getNewSize().withEyeHeight(0));"
            ),
            migrated
        )
        assertTrue(
            migrated.indexOf("audit(event.getNewSize());") <
                migrated.indexOf("event.setNewSize"),
            migrated
        )
        assertTrue(
            migrated.indexOf("event.setNewSize") <
                migrated.indexOf("continueSizing(event);"),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateLegacyEntitySizeEyeHeight(migrated)
        )
    }

    @Test
    fun `source owned size event eye height method is rejected`() {
        val source = """
            package example;
            class EntityEvent {
                static class Size {
                    void setNewEyeHeight(float value) {
                        eyeHeight = value;
                    }
                }
            }
            class LocalEvents {
                static void resize(EntityEvent.Size event) {
                    event.setNewEyeHeight(0.5F);
                }
            }
        """.trimIndent()

        assertEquals(
            source,
            StructuralRefactorPass().migrateLegacyEntitySizeEyeHeight(source)
        )
    }

    private enum class PlatformDeclaration {
        NONE,
        ITEM_OVERRIDE,
        UNKNOWN_ANCESTRY,
        UNRELATED
    }

    private enum class DuplicateClasspath {
        NONE,
        UNRELATED_CONFLICT,
        SHADOWED_OVERRIDE,
        SELECTED_OVERRIDE
    }

    private fun fixture(
        dependencyOverride: Boolean,
        platformDeclaration: PlatformDeclaration = PlatformDeclaration.NONE,
        duplicateClasspath: DuplicateClasspath = DuplicateClasspath.NONE
    ): Fixture {
        require(
            !dependencyOverride ||
                platformDeclaration == PlatformDeclaration.NONE &&
                duplicateClasspath == DuplicateClasspath.NONE
        )
        require(
            platformDeclaration == PlatformDeclaration.NONE ||
                duplicateClasspath == DuplicateClasspath.NONE
        )
        val project = temporary.resolve(
            when {
                dependencyOverride -> "dependency-override"
                platformDeclaration == PlatformDeclaration.ITEM_OVERRIDE ->
                    "native-item-override"
                platformDeclaration == PlatformDeclaration.UNKNOWN_ANCESTRY ->
                    "native-unknown-ancestry"
                platformDeclaration == PlatformDeclaration.UNRELATED ->
                    "native-unrelated-owner"
                duplicateClasspath == DuplicateClasspath.UNRELATED_CONFLICT ->
                    "unrelated-duplicate-classpath"
                duplicateClasspath == DuplicateClasspath.SHADOWED_OVERRIDE ->
                    "shadowed-override-classpath"
                duplicateClasspath == DuplicateClasspath.SELECTED_OVERRIDE ->
                    "selected-override-classpath"
                else -> "source-override"
            }
        )
        val run = project.resolve(".modporter/inventory/run")
        val sourcePlatform = jar(
            run.resolve("source-platform.jar"),
            buildMap {
                put(
                    "net/minecraft/world/item/Item.class",
                    classBytes(
                        "net/minecraft/world/item/Item",
                        "java/lang/Object",
                        interfaces = listOf(
                            "net/minecraftforge/common/extensions/IForgeItem"
                        )
                    )
                )
                put(
                    "net/minecraft/world/item/ItemStack.class",
                    classBytes(
                        "net/minecraft/world/item/ItemStack",
                        "java/lang/Object",
                        interfaces = listOf(
                            "net/minecraftforge/common/extensions/IForgeItemStack"
                        )
                    )
                )
                put(
                    "net/minecraftforge/common/extensions/IForgeItemStack.class",
                    classBytes(
                        "net/minecraftforge/common/extensions/IForgeItemStack",
                        "java/lang/Object",
                        methods = listOf(
                            Method(
                                "onBlockStartBreak",
                                "(Lnet/minecraft/core/BlockPos;" +
                                    "Lnet/minecraft/world/entity/player/Player;)Z"
                            )
                        )
                    )
                )
                put(
                    "net/minecraftforge/common/extensions/IForgeItem.class",
                    classBytes(
                        "net/minecraftforge/common/extensions/IForgeItem",
                        "java/lang/Object",
                        methods = listOf(
                            Method(
                                "onBlockStartBreak",
                                "(Lnet/minecraft/world/item/ItemStack;" +
                                    "Lnet/minecraft/core/BlockPos;" +
                                    "Lnet/minecraft/world/entity/player/Player;)Z"
                            )
                        )
                    )
                )
                put(
                    "net/minecraft/world/entity/LivingEntity.class",
                    classBytes(
                        "net/minecraft/world/entity/LivingEntity",
                        "java/lang/Object"
                    )
                )
                put(
                    "net/minecraft/world/entity/player/Player.class",
                    classBytes(
                        "net/minecraft/world/entity/player/Player",
                        "net/minecraft/world/entity/LivingEntity"
                    )
                )
                put(
                    "net/minecraft/server/level/ServerPlayer.class",
                    classBytes(
                        "net/minecraft/server/level/ServerPlayer",
                        "net/minecraft/world/entity/player/Player"
                    )
                )
                when (platformDeclaration) {
                    PlatformDeclaration.NONE -> Unit
                    PlatformDeclaration.ITEM_OVERRIDE -> put(
                        "net/minecraft/world/item/NativeLegacyTool.class",
                        classBytes(
                            "net/minecraft/world/item/NativeLegacyTool",
                            "net/minecraft/world/item/Item",
                            methods = listOf(blockStartBreakItemMethod())
                        )
                    )
                    PlatformDeclaration.UNKNOWN_ANCESTRY -> put(
                        "platform/UnknownLegacyOwner.class",
                        classBytes(
                            "platform/UnknownLegacyOwner",
                            "missing/UnknownNativeParent",
                            methods = listOf(blockStartBreakItemMethod())
                        )
                    )
                    PlatformDeclaration.UNRELATED -> put(
                        "platform/UnrelatedLegacyOwner.class",
                        classBytes(
                            "platform/UnrelatedLegacyOwner",
                            "java/lang/Object",
                            methods = listOf(blockStartBreakItemMethod())
                        )
                    )
                }
            }
        )
        val targetPlatform = jar(
            run.resolve("target-platform.jar"),
            mapOf(
                "net/minecraft/world/item/Item.class" to classBytes(
                    "net/minecraft/world/item/Item",
                    "java/lang/Object",
                    interfaces = listOf(
                        "net/neoforged/neoforge/common/extensions/IItemExtension"
                    )
                ),
                "net/minecraft/world/item/ItemStack.class" to classBytes(
                    "net/minecraft/world/item/ItemStack",
                    "java/lang/Object",
                    interfaces = listOf(
                        "net/neoforged/neoforge/common/extensions/IItemStackExtension"
                    )
                ),
                "net/neoforged/neoforge/common/extensions/IItemStackExtension.class" to
                    classBytes(
                        "net/neoforged/neoforge/common/extensions/IItemStackExtension",
                        "java/lang/Object"
                    ),
                "net/neoforged/neoforge/common/extensions/IItemExtension.class" to
                    classBytes(
                        "net/neoforged/neoforge/common/extensions/IItemExtension",
                        "java/lang/Object"
                    )
            )
        )
        val sourceArtifact = artifact(
            "generated:forge-source",
            LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM,
            sourcePlatform
        )
        val targetArtifact = artifact(
            "generated:neoforge-target",
            LegacyMigrationArtifactInventory.ComponentKind.GENERATED_PLATFORM,
            targetPlatform
        )
        val dependencyArtifacts = when {
            dependencyOverride -> listOf(
                artifact(
                    "example:legacy-dependency:1.0",
                    LegacyMigrationArtifactInventory.ComponentKind.MODULE,
                    jar(
                        run.resolve("legacy-dependency.jar"),
                        mapOf(
                            "dep/LegacyTool.class" to classBytes(
                                "dep/LegacyTool",
                                "net/minecraft/world/item/Item",
                                methods = listOf(blockStartBreakItemMethod())
                            ),
                            "dep/OptionalIntegration.class" to classBytes(
                                "dep/OptionalIntegration",
                                "java/lang/Object",
                                interfaces = listOf("missing/OptionalContract")
                            )
                        )
                    )
                )
            )
            duplicateClasspath != DuplicateClasspath.NONE -> {
                val firstDefinesOverride =
                    duplicateClasspath == DuplicateClasspath.SELECTED_OVERRIDE
                val secondDefinesOverride =
                    duplicateClasspath == DuplicateClasspath.SHADOWED_OVERRIDE
                val duplicateName =
                    if (duplicateClasspath == DuplicateClasspath.UNRELATED_CONFLICT) {
                        "dup/Unrelated"
                    } else {
                        "dep/OrderedTool"
                    }
                val firstParent = when {
                    duplicateClasspath == DuplicateClasspath.UNRELATED_CONFLICT ->
                        "java/lang/Object"
                    firstDefinesOverride -> "net/minecraft/world/item/Item"
                    else -> "java/lang/Object"
                }
                val secondParent = when {
                    duplicateClasspath == DuplicateClasspath.UNRELATED_CONFLICT ->
                        "missing/ShadowOnlyBase"
                    secondDefinesOverride -> "net/minecraft/world/item/Item"
                    else -> "java/lang/Object"
                }
                listOf(
                    artifact(
                        "example:first-duplicate:1.0",
                        LegacyMigrationArtifactInventory.ComponentKind.MODULE,
                        jar(
                            run.resolve("first-duplicate.jar"),
                            mapOf(
                                "$duplicateName.class" to classBytes(
                                    duplicateName,
                                    firstParent,
                                    methods = if (firstDefinesOverride) {
                                        listOf(blockStartBreakItemMethod())
                                    } else {
                                        emptyList()
                                    }
                                )
                            )
                        )
                    ),
                    artifact(
                        "example:second-duplicate:1.0",
                        LegacyMigrationArtifactInventory.ComponentKind.MODULE,
                        jar(
                            run.resolve("second-duplicate.jar"),
                            mapOf(
                                "$duplicateName.class" to classBytes(
                                    duplicateName,
                                    secondParent,
                                    methods = if (secondDefinesOverride) {
                                        listOf(blockStartBreakItemMethod())
                                    } else {
                                        emptyList()
                                    }
                                )
                            )
                        )
                    )
                )
            }
            else -> listOf(
                artifact(
                    "example:ordinary-dependency:1.0",
                    LegacyMigrationArtifactInventory.ComponentKind.MODULE,
                    jar(
                        run.resolve("ordinary-dependency.jar"),
                        mapOf(
                            "dep/Ordinary.class" to classBytes(
                                "dep/Ordinary",
                                "java/lang/Object"
                            ),
                            "dep/OptionalIntegration.class" to classBytes(
                                "dep/OptionalIntegration",
                                "java/lang/Object",
                                interfaces = listOf("missing/OptionalContract")
                            )
                        )
                    )
                )
            )
        }
        val inventory = LegacyMigrationArtifactPair(
            ownerProject = project,
            leafProject = project,
            sealedRunRoot = run,
            source = LegacyMigrationArtifactInventory.Source(
                LegacyMigrationArtifactInventory.PlatformIdentity(
                    "legacy-forge",
                    "1.20.1",
                    "47.1.33",
                    "net.minecraftforge:forge:1.20.1-47.1.33"
                ),
                LegacyMigrationArtifactInventory.PlatformArtifacts(
                    sourceArtifact,
                    sourceArtifact,
                    sourceArtifact
                ),
                listOf(sourceArtifact.withResolutionOrder(0)) +
                    dependencyArtifacts.mapIndexed { index, artifact ->
                        artifact.withResolutionOrder(index + 1)
                    },
                classRuntimeFeature = 17,
                compileClasspathConfiguration = ":|compileClasspath"
            ),
            target = LegacyMigrationArtifactInventory.Target(
                LegacyMigrationArtifactInventory.PlatformIdentity(
                    "neoforge",
                    "1.21.1",
                    "21.1.230",
                    "net.neoforged:neoforge:21.1.230"
                ),
                LegacyMigrationArtifactInventory.PlatformArtifacts(
                    targetArtifact,
                    targetArtifact,
                    targetArtifact
                ),
                listOf(targetArtifact)
            )
        )
        return Fixture(project, inventory)
    }

    private fun writeBlockStartCaller(fixture: Fixture): Path {
        val sourceFile = fixture.project.resolve("src/main/java/example/Deployer.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;
            class Deployer {
                static boolean harvest(ServerPlayer player, BlockPos pos) {
                    return player.getMainHandItem()
                        .onBlockStartBreak(pos, player);
                }
            }
            """.trimIndent()
        )
        return sourceFile
    }

    private fun artifact(
        component: String,
        kind: LegacyMigrationArtifactInventory.ComponentKind,
        file: Path
    ): LegacyMigrationArtifactInventory.Artifact =
        LegacyMigrationArtifactInventory.Artifact(
            component = component,
            componentKind = kind,
            displayName = file.fileName.toString(),
            configurations = setOf("compileClasspath", "runtimeClasspath"),
            file = file,
            sha256 = sha256(file)
        )

    private fun LegacyMigrationArtifactInventory.Artifact.withResolutionOrder(
        order: Int
    ): LegacyMigrationArtifactInventory.Artifact =
        LegacyMigrationArtifactInventory.Artifact(
            component = component,
            componentKind = componentKind,
            displayName = displayName,
            configurations = configurations,
            file = file,
            sha256 = sha256,
            resolutionConfigurations =
                setOf(":|compileClasspath", ":|runtimeClasspath"),
            configurationOrder = mapOf(
                ":|compileClasspath" to order,
                ":|runtimeClasspath" to order
            )
        )

    private fun jar(file: Path, entries: Map<String, ByteArray>): Path {
        file.parent.createDirectories()
        ZipOutputStream(Files.newOutputStream(file)).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    private data class Method(
        val name: String,
        val descriptor: String,
        val access: Int = 0x0001
    )

    private fun blockStartBreakItemMethod(): Method =
        Method(
            "onBlockStartBreak",
            "(Lnet/minecraft/world/item/ItemStack;" +
                "Lnet/minecraft/core/BlockPos;" +
                "Lnet/minecraft/world/entity/player/Player;)Z"
        )

    private sealed interface ClassEntry {
        data class Utf8(val value: String) : ClassEntry
        data class Class(val name: Int) : ClassEntry
    }

    private fun classBytes(
        name: String,
        parent: String,
        interfaces: List<String> = emptyList(),
        methods: List<Method> = emptyList()
    ): ByteArray {
        val entries = mutableListOf<ClassEntry>()
        val utf8 = linkedMapOf<String, Int>()
        val classes = linkedMapOf<String, Int>()
        fun utf8Index(value: String): Int =
            utf8.getOrPut(value) {
                entries += ClassEntry.Utf8(value)
                entries.size
            }
        fun classIndex(value: String): Int =
            classes.getOrPut(value) {
                val nameIndex = utf8Index(value)
                entries += ClassEntry.Class(nameIndex)
                entries.size
            }

        val ownIndex = classIndex(name)
        val parentIndex = classIndex(parent)
        val interfaceIndexes = interfaces.map(::classIndex)
        val methodIndexes = methods.map {
            Triple(it, utf8Index(it.name), utf8Index(it.descriptor))
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0xCAFEBABE.toInt())
                output.writeShort(0)
                output.writeShort(61)
                output.writeShort(entries.size + 1)
                for (entry in entries) {
                    when (entry) {
                        is ClassEntry.Utf8 -> {
                            output.writeByte(1)
                            output.writeUTF(entry.value)
                        }
                        is ClassEntry.Class -> {
                            output.writeByte(7)
                            output.writeShort(entry.name)
                        }
                    }
                }
                output.writeShort(0x0021)
                output.writeShort(ownIndex)
                output.writeShort(parentIndex)
                output.writeShort(interfaceIndexes.size)
                interfaceIndexes.forEach(output::writeShort)
                output.writeShort(0)
                output.writeShort(methodIndexes.size)
                for ((method, methodName, descriptor) in methodIndexes) {
                    output.writeShort(method.access)
                    output.writeShort(methodName)
                    output.writeShort(descriptor)
                    output.writeShort(0)
                }
                output.writeShort(0)
            }
            bytes.toByteArray()
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(
            Files.readAllBytes(path)
        ).joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val project: Path,
        val inventory: LegacyMigrationArtifactPair
    )
}
