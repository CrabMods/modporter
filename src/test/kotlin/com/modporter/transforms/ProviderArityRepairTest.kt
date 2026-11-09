package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Calls that still miss the provider their (project or platform) target now takes get the one in scope. */
class ProviderArityRepairTest {

    @TempDir
    lateinit var tempDir: Path

    private val srcDir: Path get() = tempDir.resolve("src/main/java/com/example")

    private fun java(name: String, text: String): Path {
        srcDir.createDirectories()
        val file = srcDir.resolve("$name.java")
        file.writeText(text.trimIndent() + "\n")
        return file
    }

    private fun read(name: String): String = srcDir.resolve("$name.java").readText()

    @Test
    fun `super calls, lambda parameters and platform handlers pass the provider in scope`() {
        java("Behaviour", """
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            public class Behaviour {
                public void write(CompoundTag nbt, boolean clientPacket, HolderLookup.Provider registries) {
                }
            }
        """)
        java("SmartBase", """
            package com.example;

            import java.util.List;
            import java.util.function.Consumer;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            public class SmartBase extends Base
                implements Marker {
                private final List<Behaviour> behaviours = List.of();
                private final ItemStackHandler inventory = new ItemStackHandler(1);

                protected void write(CompoundTag tag, boolean clientPacket, HolderLookup.Provider registries) {
                    forEachBehaviour(tb -> tb.write(tag, clientPacket));
                    tag.put("Items", inventory.serializeNBT());
                }

                public void forEachBehaviour(Consumer<Behaviour> action) {
                    behaviours.forEach(action);
                }
            }
        """)
        java("Base", """
            package com.example;

            public abstract class Base {
            }
        """)
        java("Marker", """
            package com.example;

            public interface Marker {
            }
        """)
        java("Chute", """
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            public class Chute extends SmartBase {
                @Override
                protected void write(CompoundTag compound, boolean clientPacket, HolderLookup.Provider registries) {
                    super.write(compound, clientPacket);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val base = read("SmartBase")
        val chute = read("Chute")
        assertTrue(base.contains("forEachBehaviour(tb -> tb.write(tag, clientPacket, registries));"), base)
        assertTrue(base.contains("inventory.serializeNBT(registries)"), base)
        assertTrue(chute.contains("super.write(compound, clientPacket, registries);"), chute)
    }

    @Test
    fun `a registry buffer reaches the whole override family past annotations`() {
        java("MenuBase", """
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;

            public abstract class MenuBase<T> {
                protected MenuBase(FriendlyByteBuf extraData) {
                    createOnClient(extraData);
                }

                protected abstract T createOnClient(FriendlyByteBuf extraData);
            }
        """)
        java("GhostMenu", """
            package com.example;

            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.api.distmarker.OnlyIn;

            public class GhostMenu extends MenuBase<ItemStack> {
                protected GhostMenu(RegistryFriendlyByteBuf extraData) {
                    super(extraData);
                }

                @Override
                @OnlyIn(Dist.CLIENT)
                protected ItemStack createOnClient(RegistryFriendlyByteBuf extraData) {
                    return ItemStack.OPTIONAL_STREAM_CODEC.decode(extraData);
                }
            }
        """)
        java("ToolboxMenu", """
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;

            public class ToolboxMenu extends MenuBase<Integer> {
                protected ToolboxMenu(FriendlyByteBuf extraData) {
                    super(extraData);
                }

                @Override
                protected Integer createOnClient(FriendlyByteBuf extraData) {
                    return extraData.readVarInt();
                }
            }
        """)

        java("BlueprintMenu", """
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.api.distmarker.OnlyIn;

            public class BlueprintMenu extends MenuBase<Integer> {
                protected BlueprintMenu(FriendlyByteBuf extraData) {
                    super(extraData);
                }

                @Override
                @OnlyIn(Dist.CLIENT)
                protected Integer createOnClient(FriendlyByteBuf extraData) {
                    return extraData.readVarInt();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        assertTrue(read("BlueprintMenu").contains("protected Integer createOnClient(RegistryFriendlyByteBuf extraData)"), read("BlueprintMenu"))
        assertTrue(read("MenuBase").contains("protected abstract T createOnClient(RegistryFriendlyByteBuf extraData);"), read("MenuBase"))
        assertTrue(read("ToolboxMenu").contains("protected Integer createOnClient(RegistryFriendlyByteBuf extraData)"), read("ToolboxMenu"))
    }

    @Test
    fun `detached block entity metadata helpers thread the caller provider to both static leaves`() {
        java("DetachedMetadata", """
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class DetachedMetadata {
                public static CompoundTag prepareBlockEntityData(BlockState state, BlockEntity blockEntity) {
                    return blockEntity.saveWithFullMetadata();
                }

                public static CompoundTag getRequiredItems(BlockState state, BlockEntity blockEntity) {
                    blockEntity.getLevel().holderLookup(Registries.BLOCK);
                    return blockEntity.saveWithFullMetadata();
                }
            }
        """)
        java("BlockHelper", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class BlockHelper {
                public static CompoundTag prepareBlockEntityData(BlockState state, BlockEntity blockEntity) {
                    return DetachedMetadata.prepareBlockEntityData(state, blockEntity);
                }
            }
        """)
        java("ItemRequirement", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class ItemRequirement {
                public static CompoundTag of(BlockState state, BlockEntity blockEntity) {
                    return DetachedMetadata.getRequiredItems(state, blockEntity);
                }
            }
        """)
        java("SchematicRoot", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class SchematicRoot {
                public static CompoundTag copy(Level level, BlockState state, BlockEntity blockEntity) {
                    BlockHelper.prepareBlockEntityData(state, blockEntity);
                    return ItemRequirement.of(state, blockEntity);
                }
            }
        """)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val detached = read("DetachedMetadata")
        val blockHelper = read("BlockHelper")
        val itemRequirement = read("ItemRequirement")
        val root = read("SchematicRoot")
        assertTrue(detached.contains(
            "prepareBlockEntityData(BlockState state, BlockEntity blockEntity, HolderLookup.Provider registries)"
        ), detached)
        assertTrue(detached.contains(
            "getRequiredItems(BlockState state, BlockEntity blockEntity, HolderLookup.Provider registries)"
        ), detached)
        assertTrue(
            Regex("""blockEntity\.saveWithFullMetadata\(registries\)""").findAll(detached).count() == 2,
            detached
        )
        assertTrue(detached.contains("registries.lookupOrThrow(Registries.BLOCK);"), detached)
        assertTrue(blockHelper.contains(
            "DetachedMetadata.prepareBlockEntityData(state, blockEntity, registries)"
        ), blockHelper)
        assertTrue(itemRequirement.contains(
            "DetachedMetadata.getRequiredItems(state, blockEntity, registries)"
        ), itemRequirement)
        assertTrue(root.contains(
            "BlockHelper.prepareBlockEntityData(state, blockEntity, level.registryAccess())"
        ), root)
        assertTrue(root.contains(
            "ItemRequirement.of(state, blockEntity, level.registryAccess())"
        ), root)
        val all = listOf(detached, blockHelper, itemRequirement, root).joinToString("\n")
        assertFalse(all.contains("blockEntity.getLevel()"), all)
        assertFalse(all.contains("BuiltInRegistries"), all)
        assertFalse(all.contains("RegistryAccess.fromRegistryOfRegistries"), all)
    }

    @Test
    fun `detached metadata propagation stays closed when its only caller is a framework callback`() {
        java("FrameworkCallback", """
            package com.example;

            public @interface FrameworkCallback {
            }
        """)
        java("StrandedMetadata", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public class StrandedMetadata {
                public static CompoundTag snapshot(BlockEntity blockEntity) {
                    return blockEntity.saveWithFullMetadata();
                }
            }
        """)
        java("FrameworkEntry", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public class FrameworkEntry {
                @FrameworkCallback
                public static CompoundTag capture(BlockEntity blockEntity) {
                    return StrandedMetadata.snapshot(blockEntity);
                }
            }
        """)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val leaf = read("StrandedMetadata")
        val entry = read("FrameworkEntry")
        assertTrue(leaf.contains("snapshot(BlockEntity blockEntity)"), leaf)
        assertTrue(leaf.contains("blockEntity.saveWithFullMetadata()"), leaf)
        assertTrue(entry.contains("capture(BlockEntity blockEntity)"), entry)
        assertTrue(entry.contains("StrandedMetadata.snapshot(blockEntity)"), entry)
        val all = "$leaf\n$entry"
        assertFalse(all.contains("HolderLookup.Provider"), all)
        assertFalse(all.contains("getLevel()"), all)
        assertFalse(all.contains("BuiltInRegistries"), all)
    }

    @Test
    fun `compound list method reference closes only the widened compound overload`() {
        val file = java("PackageOrderWithCrafts", """
            package com.example;

            import java.util.List;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.item.ItemStack;

            public class PackageOrderWithCrafts {
                public static List<CraftingEntry> read(CompoundTag tag, HolderLookup.Provider registries) {
                    return NBTHelper.readCompoundList(tag.getList("Entries", 10), CraftingEntry::read);
                }

                public static CraftingEntry readBuffer(RegistryFriendlyByteBuf buffer, HolderLookup.Provider registries) {
                    return CraftingEntry.read(buffer);
                }

                public static class CraftingEntry {
                    private final ItemStack stack;

                    CraftingEntry(ItemStack stack) {
                        this.stack = stack;
                    }

                    public static CraftingEntry read(CompoundTag tag, HolderLookup.Provider registries) {
                        return new CraftingEntry(ItemStack.parseOptional(registries, tag));
                    }

                    public static CraftingEntry read(RegistryFriendlyByteBuf buffer) {
                        return new CraftingEntry(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
                    }
                }
            }
        """)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val migrated = file.readText()
        assertTrue(
            migrated.contains(
                "read(CompoundTag tag, HolderLookup.Provider registries)"
            ),
            migrated
        )
        assertTrue(
            Regex(
                """\b([A-Za-z_$][\w$]*)\s*->\s*CraftingEntry\.read\(\s*\1\s*,\s*registries\s*\)"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            migrated.contains("read(RegistryFriendlyByteBuf buffer)"),
            migrated
        )
        assertTrue(migrated.contains("return CraftingEntry.read(buffer);"), migrated)
        assertFalse(migrated.contains("CraftingEntry.read(buffer, registries)"), migrated)
        assertFalse(migrated.contains("CraftingEntry::read"), migrated)
    }

    @Test
    fun `compound list method reference stays when a compound overload still fits`() {
        val file = java("LegacyListReader", """
            package com.example;

            import java.util.List;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;

            public class LegacyListReader {
                public static List<Entry> read(ListTag tags) {
                    return NBTHelper.readCompoundList(tags, Entry::read);
                }

                public static class Entry {
                    public static Entry read(CompoundTag tag) {
                        return new Entry();
                    }
                }
            }
        """)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val migrated = file.readText()
        assertTrue(migrated.contains("Entry::read"), migrated)
        assertFalse(migrated.contains("modporterTag ->"), migrated)
    }

    @Test
    fun `compound callbacks keep overload evidence local to the receiver owner`() {
        java("BigItemStack", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class BigItemStack {
                public static BigItemStack read(CompoundTag tag) {
                    ItemStack.of(tag);
                    return new BigItemStack();
                }
            }
        """)
        java("PackageOrder", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.RegistryFriendlyByteBuf;

            public class PackageOrder {
                public static PackageOrder read(CompoundTag tag) {
                    return new PackageOrder();
                }

                public static PackageOrder read(RegistryFriendlyByteBuf buffer) {
                    return new PackageOrder();
                }
            }
        """)
        java("RequestPromiseQueue", """
            package com.example;

            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.Tag;

            public class RequestPromiseQueue {
                public static RequestPromiseQueue read(CompoundTag tag, Runnable changed) {
                    NBTHelper.iterateCompoundList(tag.getList("List", Tag.TAG_COMPOUND),
                        nested -> BigItemStack.read(nested));
                    return new RequestPromiseQueue();
                }
            }
        """)
        java("CraftingEntry", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.item.ItemStack;

            public class CraftingEntry {
                public static CraftingEntry read(CompoundTag tag) {
                    ItemStack.of(tag);
                    return new CraftingEntry();
                }

                public static CraftingEntry read(RegistryFriendlyByteBuf buffer) {
                    return new CraftingEntry();
                }
            }
        """)
        java("ProviderRoot", """
            package com.example;

            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.network.RegistryFriendlyByteBuf;

            public class ProviderRoot {
                public static void load(CompoundTag tag, HolderLookup.Provider registries) {
                    RequestPromiseQueue.read(tag, () -> {});
                    NBTHelper.iterateCompoundList(tag.getList("Entries", Tag.TAG_COMPOUND),
                        nested -> CraftingEntry.read(nested));
                }

                public static CraftingEntry readBuffer(RegistryFriendlyByteBuf buffer) {
                    return CraftingEntry.read(buffer);
                }

                public static PackageOrder unrelatedBuffer(RegistryFriendlyByteBuf buffer) {
                    return PackageOrder.read(buffer);
                }
            }
        """)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val big = read("BigItemStack")
        val queue = read("RequestPromiseQueue")
        val entry = read("CraftingEntry")
        val root = read("ProviderRoot")
        assertTrue(big.contains("read(CompoundTag tag, HolderLookup.Provider registries)"), big)
        assertTrue(queue.contains(
            "read(CompoundTag tag, Runnable changed, HolderLookup.Provider registries)"
        ), queue)
        assertTrue(queue.contains("BigItemStack.read(nested, registries)"), queue)
        assertTrue(root.contains("RequestPromiseQueue.read(tag, () -> {}, registries)"), root)
        assertTrue(entry.contains("read(CompoundTag tag, HolderLookup.Provider registries)"), entry)
        assertTrue(entry.contains("read(RegistryFriendlyByteBuf buffer)"), entry)
        assertTrue(root.contains("CraftingEntry.read(nested, registries)"), root)
        assertTrue(root.contains("return CraftingEntry.read(buffer);"), root)
        assertTrue(root.contains("return PackageOrder.read(buffer);"), root)
        assertFalse(root.contains("CraftingEntry.read(buffer, registries)"), root)
        assertFalse(root.contains("PackageOrder.read(buffer, registries)"), root)
    }
}
