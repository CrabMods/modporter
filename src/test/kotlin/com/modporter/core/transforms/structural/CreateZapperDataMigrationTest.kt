package com.modporter.core.transforms.structural

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class CreateZapperDataMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `generated Java preserves swap siblings false throw and distinct processor results`() {
        val fixture = writeFixture(directory.resolve("runtime"))
        val result = CreateZapperDataMigration.migrate(fixture.project, false)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(1, result.changes.size)

        val source = fixture.zapper.readText()
        val snapshot = source.indexOf("CompoundTag nbt = item.getOrDefault")
        val swap = source.indexOf("ShootableGadgetItemMethods.shouldSwap")
        val validation = source.indexOf("validateUsage(item)")
        val firstRootRead = source.indexOf("if (nbt.contains(\"BlockUsed\"))", snapshot)
        val snapshotEnd = source.indexOf(';', snapshot) + 1
        val tryStart = source.indexOf("try {", snapshot)
        val finallyStart = source.indexOf("finally {", tryStart)
        val activatedBranch = source.indexOf("if (activated)", finallyStart)
        assertTrue(snapshot > swap && snapshot > validation, source)
        assertTrue(firstRootRead > snapshotEnd, source)
        assertTrue(source.substring(snapshotEnd, firstRootRead).isBlank(), source)
        assertTrue(tryStart > snapshot && finallyStart > tryStart && activatedBranch > finallyStart, source)
        assertTrue(source.contains("data = nbt.getCompound(\"BlockData\")"), source)

        writeRuntimeProbe(fixture.project)
        val classes = compileJava(fixture.project)
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            loader.loadClass("com.simibubi.create.content.equipment.zapper.ZapperRuntimeProbe")
                .getMethod("run")
                .invoke(null)
        }
    }

    @Test
    fun `dry run is byte preserving and committed migration is idempotent`() {
        val fixture = writeFixture(directory.resolve("idempotent"))
        val before = Files.readAllBytes(fixture.zapper)
        val dryRun = CreateZapperDataMigration.migrate(fixture.project, true)
        assertTrue(dryRun.gates.isEmpty(), dryRun.gates.joinToString("\n"))
        assertEquals(1, dryRun.changes.size)
        assertTrue(before.contentEquals(Files.readAllBytes(fixture.zapper)))

        val committed = CreateZapperDataMigration.migrate(fixture.project, false)
        assertTrue(committed.gates.isEmpty(), committed.gates.joinToString("\n"))
        assertEquals(1, committed.changes.size)
        val after = Files.readAllBytes(fixture.zapper)
        val rerun = CreateZapperDataMigration.migrate(fixture.project, false)
        assertTrue(rerun.gates.isEmpty(), rerun.gates.joinToString("\n"))
        assertTrue(rerun.changes.isEmpty())
        assertTrue(after.contentEquals(Files.readAllBytes(fixture.zapper)))
    }

    @Test
    fun `receiver aliases escapes and post snapshot writes reject without publication`() {
        val variants = linkedMapOf<String, (Fixture) -> Unit>(
            "receiver" to { fixture ->
                replace(fixture.zapper, "CompoundTag nbt = item.getOrCreateTag();",
                    "CompoundTag nbt = other.getOrCreateTag();")
            },
            "root escape" to { fixture ->
                replace(fixture.zapper, "if (nbt.contains(\"BlockUsed\"))",
                    "consume(nbt);\n        if (nbt.contains(\"BlockUsed\"))")
            },
            "child escape" to { fixture ->
                replace(fixture.zapper, "// Raytrace",
                    "consume(data);\n        // Raytrace")
            },
            "post snapshot write" to { fixture ->
                replace(fixture.zapper, "// Raytrace",
                    "item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                        "net.minecraft.world.item.component.CustomData.of(new CompoundTag()));\n" +
                        "        // Raytrace")
            },
            "activate stack write" to { fixture ->
                replace(fixture.worldshaper, "PlacementPatterns.applyPattern(affectedPositions, stack);",
                    "PlacementPatterns.applyPattern(affectedPositions, stack);\n" +
                        "        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                        "net.minecraft.world.item.component.CustomData.of(new CompoundTag()));")
            },
            "preflight presence read" to { fixture ->
                val shootable = fixture.project.resolve(
                    "src/main/java/com/simibubi/create/content/equipment/zapper/" +
                        "ShootableGadgetItemMethods.java"
                )
                replace(shootable, "boolean isSwap = item.getOrDefault(",
                    "boolean present = item.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA);\n" +
                        "        boolean isSwap = item.getOrDefault(")
            }
        )
        variants.forEach { (name, alter) ->
            val fixture = writeFixture(directory.resolve(name.replace(' ', '-')))
            alter(fixture)
            val originals = javaFiles(fixture.project).associateWith(Files::readAllBytes)
            val result = CreateZapperDataMigration.migrate(fixture.project, false)
            assertTrue(result.changes.isEmpty(), name)
            assertTrue(result.gates.isNotEmpty(), "$name unexpectedly admitted")
            originals.forEach { (file, bytes) ->
                assertTrue(bytes.contentEquals(Files.readAllBytes(file)), "$name changed $file")
            }
        }
    }

    @Test
    fun `optional converted Create replay plans without writing source`() {
        val configured = System.getenv("MODPORTER_CREATE_ITEM_DATA_REPLAY")
        assumeTrue(!configured.isNullOrBlank(), "Optional converted Create source replay")
        val sourceRoot = Path.of(configured!!).toAbsolutePath().normalize()
        val project = sourceRoot.parent.parent.parent
        val originals = Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .toList().associateWith(Files::readAllBytes)
        }
        val result = CreateZapperDataMigration.migrate(project, true)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(1, result.changes.size)
        originals.forEach { (file, bytes) ->
            assertTrue(bytes.contentEquals(Files.readAllBytes(file)), file.toString())
        }
    }

    private fun writeFixture(project: Path): Fixture {
        fun write(relative: String, source: String): Path {
            val path = project.resolve("src/main/java").resolve(relative)
            path.parent.createDirectories()
            path.writeText(source.trimIndent() + "\n")
            return path
        }

        val zapper = write(
            "com/simibubi/create/content/equipment/zapper/ZapperItem.java",
            zapperSource()
        )
        val worldshaper = write(
            "com/simibubi/create/content/equipment/zapper/terrainzapper/WorldshaperItem.java",
            worldshaperSource()
        )
        write("com/simibubi/create/content/equipment/zapper/terrainzapper/TerrainTools.java", terrainToolsSource())
        write("com/simibubi/create/content/equipment/zapper/PlacementPatterns.java", placementPatternsSource())
        write("com/simibubi/create/content/equipment/zapper/ShootableGadgetItemMethods.java", shootableSource())
        writeStubs { relative, source -> write(relative, source) }
        return Fixture(project, zapper, worldshaper)
    }
    private fun zapperSource(): String = """
        package com.simibubi.create.content.equipment.zapper;

        import com.simibubi.create.AllTags.AllBlockTags;
        import net.createmod.catnip.nbt.NBTProcessors;
        import net.minecraft.core.BlockPos;
        import net.minecraft.core.registries.Registries;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.nbt.Tag;
        import net.minecraft.network.chat.Component;
        import net.minecraft.world.InteractionHand;
        import net.minecraft.world.InteractionResult;
        import net.minecraft.world.InteractionResultHolder;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.level.block.Blocks;
        import net.minecraft.world.level.block.entity.BlockEntity;
        import net.minecraft.world.level.block.state.BlockState;
        import net.minecraft.world.phys.BlockHitResult;

        public abstract class ZapperItem {
            public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
                ItemStack item = player.getItemInHand(hand);
                CompoundTag nbt = item.getOrCreateTag();
                if (player.shift) {
                    return new InteractionResultHolder<>(InteractionResult.SUCCESS, item);
                }
                if (ShootableGadgetItemMethods.shouldSwap(player, item, hand, this::isZapper))
                    return new InteractionResultHolder<>(InteractionResult.FAIL, item);
                Component msg = validateUsage(item);
                if (msg != null)
                    return new InteractionResultHolder<>(InteractionResult.FAIL, item);
                BlockState stateToUse = Blocks.AIR.defaultBlockState();
                if (nbt.contains("BlockUsed"))
                    stateToUse = Blocks.AIR.load(nbt.getCompound("BlockUsed"));
                CompoundTag data = null;
                if (AllBlockTags.SAFE_NBT.matches(stateToUse) && nbt.contains("BlockData", Tag.TAG_COMPOUND)) {
                    data = nbt.getCompound("BlockData");
                }
                // Raytrace
                int range = getZappingRange(item);
                BlockHitResult raytrace = world.hit;
                BlockPos pos = raytrace.getBlockPos();
                if (player.targetFailure) {
                    ShootableGadgetItemMethods.applyCooldown(player, item, hand, this::isZapper, getCooldownDelay(item));
                    return new InteractionResultHolder<>(InteractionResult.SUCCESS, item);
                }
                if (world.isClientSide)
                    return new InteractionResultHolder<>(InteractionResult.SUCCESS, item);
                if (activate(world, player, item, stateToUse, raytrace, data)) {
                    ShootableGadgetItemMethods.applyCooldown(player, item, hand, this::isZapper, getCooldownDelay(item));
                    player.packetSent = true;
                }
                return new InteractionResultHolder<>(InteractionResult.SUCCESS, item);
            }

            public Component validateUsage(ItemStack item) {
                CompoundTag tag = item.getOrCreateTag();
                if (!canActivateWithoutSelectedBlock(item) && !tag.contains("BlockUsed"))
                    return null;
                return null;
            }

            protected abstract boolean activate(Level world, Player player, ItemStack item,
                BlockState stateToUse, BlockHitResult raytrace, CompoundTag data);
            protected abstract int getZappingRange(ItemStack stack);
            protected abstract int getCooldownDelay(ItemStack item);
            protected boolean canActivateWithoutSelectedBlock(ItemStack stack) { return false; }
            protected boolean isZapper(ItemStack stack) { return true; }

            public static void setBlockEntityData(Level world, BlockPos pos, BlockState state,
                CompoundTag data, Player player) {
                if (data != null && AllBlockTags.SAFE_NBT.matches(state)) {
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity != null) {
                        data = NBTProcessors.process(state, blockEntity, data, !player.isCreative());
                        if (data == null)
                            return;
                        data.putInt("x", pos.getX());
                        data.putInt("y", pos.getY());
                        data.putInt("z", pos.getZ());
                        blockEntity.loadWithComponents(data, world.registryAccess());
                    }
                }
            }
        }
    """

    private fun worldshaperSource(): String = """
        package com.simibubi.create.content.equipment.zapper.terrainzapper;

        import java.util.ArrayList;
        import java.util.List;
        import com.simibubi.create.content.equipment.zapper.PlacementPatterns;
        import com.simibubi.create.content.equipment.zapper.ZapperItem;
        import net.createmod.catnip.nbt.NBTHelper;
        import net.minecraft.core.BlockPos;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.network.chat.Component;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.level.block.state.BlockState;
        import net.minecraft.world.phys.BlockHitResult;

        public class WorldshaperItem extends ZapperItem {
            @Override
            public Component validateUsage(ItemStack item) {
                if (!item.getOrCreateTag().contains("BrushParams"))
                    return null;
                return super.validateUsage(item);
            }

            @Override
            protected int getZappingRange(ItemStack stack) {
                return 128;
            }

            @Override
            protected int getCooldownDelay(ItemStack item) {
                return 2;
            }

            @Override
            protected boolean canActivateWithoutSelectedBlock(ItemStack stack) {
                CompoundTag tag = stack.getOrCreateTag();
                TerrainTools tool = NBTHelper.readEnum(tag, "Tool", TerrainTools.class);
                return !tool.requiresSelectedBlock();
            }

            @Override
            protected boolean activate(Level world, Player player, ItemStack stack,
                BlockState stateToUse, BlockHitResult raytrace, CompoundTag data) {
                BlockPos targetPos = raytrace.getBlockPos();
                List<BlockPos> affectedPositions = new ArrayList<>();
                CompoundTag tag = stack.getOrCreateTag();
                Brush brush = NBTHelper.readEnum(tag, "Brush", TerrainBrushes.class).get();
                BlockPos params = BlockPos.from(tag.getCompound("BrushParams"));
                PlacementOptions option = NBTHelper.readEnum(tag, "Placement", PlacementOptions.class);
                TerrainTools tool = NBTHelper.readEnum(tag, "Tool", TerrainTools.class);
                brush.set(params.getX(), params.getY(), params.getZ());
                targetPos = targetPos.offset(brush.getOffset());
                brush.addToGlobalPositions(world, targetPos, affectedPositions, tool);
                PlacementPatterns.applyPattern(affectedPositions, stack);
                brush.redirectTool(tool).run(world, affectedPositions, raytrace.getDirection(), stateToUse, data, player);
                return true;
            }
        }

        enum TerrainBrushes {
            INSTANCE;
            Brush get() { return new Brush(); }
        }
        enum PlacementOptions { INSTANCE }
        class Brush {
            void set(int x, int y, int z) {}
            BlockPos getOffset() { return new BlockPos(0, 0, 0); }
            void addToGlobalPositions(Level world, BlockPos pos, List<BlockPos> list, TerrainTools tool) {
                list.add(pos);
            }
            TerrainTools redirectTool(TerrainTools tool) { return tool; }
        }
    """

    private fun terrainToolsSource(): String = """
        package com.simibubi.create.content.equipment.zapper.terrainzapper;

        import java.util.List;
        import com.simibubi.create.content.equipment.zapper.ZapperItem;
        import net.minecraft.core.BlockPos;
        import net.minecraft.core.Direction;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.level.block.state.BlockState;

        public enum TerrainTools {
            Clear, Flatten, Fill;
            public boolean requiresSelectedBlock() {
                return this != Clear && this != Flatten;
            }
            public void run(Level world, List<BlockPos> targetPositions, Direction facing,
                BlockState paintedState, CompoundTag data, Player player) {
                BlockPos p = targetPositions.get(0);
                ZapperItem.setBlockEntityData(world, p, paintedState, data, player);
                ZapperItem.setBlockEntityData(world, p, paintedState, data, player);
                ZapperItem.setBlockEntityData(world, p, paintedState, data, player);
                ZapperItem.setBlockEntityData(world, p, paintedState, data, player);
            }
        }
    """

    private fun placementPatternsSource(): String = """
        package com.simibubi.create.content.equipment.zapper;

        import java.util.List;
        import net.minecraft.core.BlockPos;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.world.item.ItemStack;

        public enum PlacementPatterns {
            Solid;
            public static void applyPattern(List<BlockPos> blocksIn, ItemStack stack) {
                CompoundTag tag = stack.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                boolean present = tag.contains("Pattern");
                String pattern = tag.getString("Pattern");
                if (present && pattern.isEmpty()) blocksIn.clear();
            }
        }
    """

    private fun shootableSource(): String = """
        package com.simibubi.create.content.equipment.zapper;

        import java.util.function.Predicate;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.world.InteractionHand;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.ItemStack;

        public class ShootableGadgetItemMethods {
            public static boolean shouldSwap(Player player, ItemStack item, InteractionHand hand,
                Predicate<ItemStack> predicate) {
                boolean isSwap = item.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag().contains("_Swap");
                if (player.swapWrite) {
                    CompoundTag changed = item.getOrDefault(
                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    changed.putString("SwapSibling", isSwap ? "old" : "new");
                    item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(changed));
                }
                return player.swapFailure;
            }
            public static void applyCooldown(Player player, ItemStack item, InteractionHand hand,
                Predicate<ItemStack> predicate, int cooldown) {
                player.cooldownApplied = true;
            }
        }
    """
    private fun writeStubs(write: (String, String) -> Path) {
        write("net/minecraft/core/component/DataComponentType.java", """
            package net.minecraft.core.component;
            public final class DataComponentType<T> {}
        """)
        write("net/minecraft/core/component/DataComponents.java", """
            package net.minecraft.core.component;
            import net.minecraft.world.item.component.CustomData;
            public final class DataComponents {
                public static final DataComponentType<CustomData> CUSTOM_DATA = new DataComponentType<>();
            }
        """)
        write("net/minecraft/nbt/Tag.java", """
            package net.minecraft.nbt;
            public interface Tag { int TAG_COMPOUND = 10; }
        """)
        write("net/minecraft/nbt/CompoundTag.java", """
            package net.minecraft.nbt;
            import java.util.LinkedHashMap;
            import java.util.Map;
            public class CompoundTag implements Tag {
                private final Map<String, Object> values = new LinkedHashMap<>();
                public boolean contains(String key) { return values.containsKey(key); }
                public boolean contains(String key, int type) { return values.get(key) instanceof CompoundTag; }
                public CompoundTag getCompound(String key) {
                    Object value = values.get(key);
                    if (value instanceof CompoundTag tag) return tag;
                    return new CompoundTag();
                }
                public void put(String key, CompoundTag value) { values.put(key, value); }
                public void putInt(String key, int value) { values.put(key, value); }
                public int getInt(String key) { return (int) values.getOrDefault(key, 0); }
                public void putString(String key, String value) { values.put(key, value); }
                public String getString(String key) { return (String) values.getOrDefault(key, ""); }
                public boolean isEmpty() { return values.isEmpty(); }
                public CompoundTag copy() {
                    CompoundTag copy = new CompoundTag();
                    values.forEach((key, value) -> copy.values.put(
                        key, value instanceof CompoundTag tag ? tag.copy() : value));
                    return copy;
                }
            }
        """)
        write("net/minecraft/world/item/component/CustomData.java", """
            package net.minecraft.world.item.component;
            import net.minecraft.nbt.CompoundTag;
            public final class CustomData {
                public static final CustomData EMPTY = new CustomData(new CompoundTag());
                private final CompoundTag tag;
                private CustomData(CompoundTag tag) { this.tag = tag.copy(); }
                public static CustomData of(CompoundTag tag) { return new CustomData(tag); }
                public CompoundTag copyTag() { return tag.copy(); }
            }
        """)
        write("net/minecraft/world/item/ItemStack.java", """
            package net.minecraft.world.item;
            import net.minecraft.core.component.DataComponentType;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.component.CustomData;
            public class ItemStack {
                private CustomData data;
                public int legacyGetCount;
                public boolean has(DataComponentType<?> type) { return data != null; }
                @SuppressWarnings("unchecked")
                public <T> T getOrDefault(DataComponentType<T> type, T fallback) {
                    return (T) (data == null ? fallback : data);
                }
                public <T> T set(DataComponentType<T> type, T value) {
                    T old = getOrDefault(type, null);
                    data = (CustomData) value;
                    return old;
                }
                public CompoundTag getOrCreateTag() {
                    legacyGetCount++;
                    CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
                    data = CustomData.of(tag);
                    return tag;
                }
                public CompoundTag snapshot() {
                    return getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                }
                public Object getItem() { return this; }
            }
        """)
        write("net/minecraft/world/InteractionHand.java", """
            package net.minecraft.world;
            public enum InteractionHand { MAIN_HAND, OFF_HAND }
        """)
        write("net/minecraft/world/InteractionResult.java", """
            package net.minecraft.world;
            public enum InteractionResult { SUCCESS, FAIL }
        """)
        write("net/minecraft/world/InteractionResultHolder.java", """
            package net.minecraft.world;
            public record InteractionResultHolder<T>(InteractionResult result, T object) {}
        """)
        write("net/minecraft/network/chat/Component.java", """
            package net.minecraft.network.chat;
            public class Component {}
        """)
        write("net/minecraft/world/entity/player/Player.java", """
            package net.minecraft.world.entity.player;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.item.ItemStack;
            public class Player {
                public final ItemStack item;
                public boolean shift;
                public boolean swapWrite;
                public boolean swapFailure;
                public boolean targetFailure;
                public boolean cooldownApplied;
                public boolean packetSent;
                public Player(ItemStack item) { this.item = item; }
                public ItemStack getItemInHand(InteractionHand hand) { return item; }
                public boolean isCreative() { return false; }
            }
        """)
        write("net/minecraft/core/Direction.java", """
            package net.minecraft.core;
            public enum Direction { UP }
        """)
        write("net/minecraft/core/BlockPos.java", """
            package net.minecraft.core;
            import net.minecraft.nbt.CompoundTag;
            public record BlockPos(int x, int y, int z) {
                public int getX() { return x; }
                public int getY() { return y; }
                public int getZ() { return z; }
                public BlockPos offset(BlockPos other) {
                    return new BlockPos(x + other.x, y + other.y, z + other.z);
                }
                public static BlockPos from(CompoundTag tag) { return new BlockPos(0, 0, 0); }
            }
        """)
        write("net/minecraft/core/registries/Registries.java", """
            package net.minecraft.core.registries;
            public final class Registries {}
        """)
        write("net/minecraft/world/level/block/state/BlockState.java", """
            package net.minecraft.world.level.block.state;
            import net.minecraft.nbt.CompoundTag;
            public class BlockState {
                public BlockState load(CompoundTag tag) { return this; }
            }
        """)
        write("net/minecraft/world/level/block/Blocks.java", """
            package net.minecraft.world.level.block;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.state.BlockState;
            public final class Blocks {
                public static final Air AIR = new Air();
                public static final class Air {
                    public BlockState defaultBlockState() { return new BlockState(); }
                    public BlockState load(CompoundTag tag) { return new BlockState(); }
                }
            }
        """)
        write("net/minecraft/world/phys/BlockHitResult.java", """
            package net.minecraft.world.phys;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            public class BlockHitResult {
                public BlockPos getBlockPos() { return new BlockPos(1, 2, 3); }
                public Direction getDirection() { return Direction.UP; }
            }
        """)
        write("net/minecraft/world/level/block/entity/BlockEntity.java", """
            package net.minecraft.world.level.block.entity;
            import net.minecraft.nbt.CompoundTag;
            public class BlockEntity {
                public CompoundTag loaded;
                public void loadWithComponents(CompoundTag tag, Object registries) { loaded = tag.copy(); }
            }
        """)
        write("net/minecraft/world/level/Level.java", """
            package net.minecraft.world.level;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.phys.BlockHitResult;
            public class Level {
                public boolean isClientSide;
                public final BlockHitResult hit = new BlockHitResult();
                public final BlockEntity blockEntity = new BlockEntity();
                public BlockEntity getBlockEntity(BlockPos pos) { return blockEntity; }
                public Object registryAccess() { return this; }
            }
        """)
        write("com/simibubi/create/AllTags.java", """
            package com.simibubi.create;
            import net.minecraft.world.level.block.state.BlockState;
            public final class AllTags {
                public enum AllBlockTags {
                    SAFE_NBT;
                    public boolean matches(BlockState state) { return true; }
                }
            }
        """)
        write("net/createmod/catnip/nbt/NBTHelper.java", """
            package net.createmod.catnip.nbt;
            import net.minecraft.nbt.CompoundTag;
            public final class NBTHelper {
                public static <T extends Enum<T>> T readEnum(
                    CompoundTag tag, String key, Class<T> type) {
                    return type.getEnumConstants()[0];
                }
            }
        """)
        write("net/createmod/catnip/nbt/NBTProcessors.java", """
            package net.createmod.catnip.nbt;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            public final class NBTProcessors {
                public static boolean returnDistinct;
                public static CompoundTag process(BlockState state, BlockEntity blockEntity,
                    CompoundTag data, boolean survival) {
                    data.putInt("inputTouched", 1);
                    if (!returnDistinct) return data;
                    CompoundTag distinct = new CompoundTag();
                    distinct.putInt("distinct", 1);
                    return distinct;
                }
            }
        """)
    }
    private fun writeRuntimeProbe(project: Path) {
        val file = project.resolve(
            "src/main/java/com/simibubi/create/content/equipment/zapper/ZapperRuntimeProbe.java"
        )
        file.writeText("""
            package com.simibubi.create.content.equipment.zapper;

            import net.createmod.catnip.nbt.NBTProcessors;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.component.CustomData;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public final class ZapperRuntimeProbe extends ZapperItem {
                private final int mode;
                private ZapperRuntimeProbe(int mode) { this.mode = mode; }

                @Override
                protected boolean activate(Level world, Player player, ItemStack item,
                    BlockState state, BlockHitResult hit, CompoundTag data) {
                    data.putInt("activated", 1);
                    if (mode == 2)
                        throw new IllegalStateException("boom");
                    if (mode == 3)
                        setBlockEntityData(world, hit.getBlockPos(), state, data, player);
                    return mode == 1;
                }

                @Override
                protected int getZappingRange(ItemStack stack) { return 8; }

                @Override
                protected int getCooldownDelay(ItemStack item) { return 2; }

                private static ItemStack stack() {
                    CompoundTag root = new CompoundTag();
                    CompoundTag child = new CompoundTag();
                    root.put("BlockData", child);
                    root.putString("OriginalSibling", "kept");
                    ItemStack stack = new ItemStack();
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
                    return stack;
                }

                private static CompoundTag invoke(int mode, boolean swapFailure) {
                    ItemStack stack = stack();
                    Player player = new Player(stack);
                    player.swapWrite = true;
                    player.swapFailure = swapFailure;
                    Level world = new Level();
                    try {
                        new ZapperRuntimeProbe(mode).use(world, player, InteractionHand.MAIN_HAND);
                        if (mode == 2) throw new AssertionError("throw mode returned");
                    } catch (IllegalStateException expected) {
                        if (mode != 2) throw expected;
                    }
                    CompoundTag root = stack.snapshot();
                    if (!"kept".equals(root.getString("OriginalSibling")))
                        throw new AssertionError("original sibling lost");
                    if (!"new".equals(root.getString("SwapSibling")))
                        throw new AssertionError("pre-snapshot swap sibling lost");
                    if (swapFailure && stack.legacyGetCount != 0)
                        throw new AssertionError("early failure crossed the snapshot anchor");
                    return root;
                }

                public static void run() {
                    ItemStack emptyGuiStack = new ItemStack();
                    Player emptyGuiPlayer = new Player(emptyGuiStack);
                    emptyGuiPlayer.shift = true;
                    new ZapperRuntimeProbe(0).use(new Level(), emptyGuiPlayer, InteractionHand.MAIN_HAND);
                    if (!emptyGuiStack.has(DataComponents.CUSTOM_DATA) || emptyGuiStack.legacyGetCount != 0)
                        throw new AssertionError("early GUI return lost legacy empty root presence");

                    CompoundTag early = invoke(0, true);
                    if (early.getCompound("BlockData").contains("activated"))
                        throw new AssertionError("early failure activated");

                    CompoundTag falseRoot = invoke(0, false);
                    if (falseRoot.getCompound("BlockData").getInt("activated") != 1)
                        throw new AssertionError("false activate mutation lost");

                    CompoundTag throwRoot = invoke(2, false);
                    if (throwRoot.getCompound("BlockData").getInt("activated") != 1)
                        throw new AssertionError("throw activate mutation lost");

                    NBTProcessors.returnDistinct = true;
                    ItemStack stack = stack();
                    Player player = new Player(stack);
                    Level world = new Level();
                    new ZapperRuntimeProbe(3).use(world, player, InteractionHand.MAIN_HAND);
                    CompoundTag child = stack.snapshot().getCompound("BlockData");
                    if (child.getInt("inputTouched") != 1)
                        throw new AssertionError("processor input mutation lost");
                    if (child.contains("distinct") || child.contains("x"))
                        throw new AssertionError("distinct processor result attached to item root");
                    if (world.blockEntity.loaded == null ||
                        world.blockEntity.loaded.getInt("distinct") != 1 ||
                        world.blockEntity.loaded.getInt("x") != 1)
                        throw new AssertionError("distinct processor result not loaded into block entity");
                }
            }
        """.trimIndent() + "\n")
    }

    private fun compileJava(project: Path): Path {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "Tests require a JDK Java compiler"
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val classes = project.resolve("compiled")
        classes.createDirectories()
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val units = manager.getJavaFileObjectsFromPaths(javaFiles(project))
            val success = compiler.getTask(
                null, manager, diagnostics,
                listOf("-proc:none", "-d", classes.toString()),
                null, units
            ).call()
            assertTrue(success, diagnostics.diagnostics.joinToString("\n") { it.toString() })
        }
        return classes
    }

    private fun javaFiles(project: Path): List<Path> =
        Files.walk(project.resolve("src/main/java")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .sorted().toList()
        }

    private fun replace(path: Path, before: String, after: String) {
        val source = path.readText()
        assertTrue(source.contains(before), "Missing fixture fragment in $path: $before")
        path.writeText(source.replace(before, after))
    }

    private data class Fixture(
        val project: Path,
        val zapper: Path,
        val worldshaper: Path
    )
}
