package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 1.21.1 registry, block entity and packet surfaces migrated from source evidence. */
class RegistryAndPacketSurfacesTest {

    @TempDir
    lateinit var tempDir: Path

    private val srcDir: Path get() = tempDir.resolve("src/main/java/com/example")

    private fun java(name: String, text: String) {
        srcDir.createDirectories()
        srcDir.resolve("$name.java").writeText(text.trimIndent() + "\n")
    }

    private fun read(name: String): String = srcDir.resolve("$name.java").readText()

    private fun mod() = java("ExampleMod", """
        package com.example;

        import net.neoforged.fml.common.Mod;

        @Mod("example")
        public class ExampleMod {
        }
    """)

    @Test
    fun `forge tag manager streams become vanilla registry tag streams`() {
        mod()
        java("Categories", """
            package com.example;

            import java.util.Objects;
            import java.util.Set;
            import java.util.stream.Collectors;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.registries.tags.ITagManager;

            public class Categories {
                public Set<Block> blocks(String category) {
                    ITagManager<Block> tags = Objects.requireNonNull(BuiltInRegistries.BLOCK.tags(), "no block tags registry");
                    return tags.getTag(TagKey.create(Registries.BLOCK, ResourceLocation.parse(category))).stream().collect(Collectors.toSet());
                }

                public long items(TagKey<Item> key) {
                    return BuiltInRegistries.ITEM.tags().getTag(key).stream().count();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val categories = read("Categories")

        assertTrue(categories.contains("return BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, ResourceLocation.parse(category))).stream().flatMap(net.minecraft.core.HolderSet.Named::stream).map(net.minecraft.core.Holder::value).collect(Collectors.toSet());"), categories)
        assertTrue(categories.contains("return BuiltInRegistries.ITEM.getTag(key).stream().flatMap(net.minecraft.core.HolderSet.Named::stream).map(net.minecraft.core.Holder::value).count();"), categories)
        assertFalse(categories.contains("ITagManager"), categories)
    }

    @Test
    fun `forge tag manager reads become vanilla registry tag reads`() {
        mod()
        java("TagReads", """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.tags.ItemTags;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.registries.tags.ITag;
            import net.neoforged.neoforge.registries.tags.ITagManager;

            public class TagReads {
                public boolean hidden(TagKey<Item> tag) {
                    ITagManager<Item> tagManager = BuiltInRegistries.ITEM.tags();
                    return !tagManager.isKnownTagName(tag) || tagManager.getTag(tag).isEmpty();
                }

                public List<Item> logs() {
                    List<Item> logs = new ArrayList<>();
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    tags.getTag(ItemTags.LOGS).forEach(logs::add);
                    return logs;
                }

                public ItemStack[] stacks(TagKey<Item> tag) {
                    ITagManager<Item> tagManager = BuiltInRegistries.ITEM.tags();
                    if (tagManager.isKnownTagName(tag)) {
                        ITag<Item> taggedItems = tagManager.getTag(tag);
                        if (!taggedItems.isEmpty()) {
                            ItemStack[] stacks = new ItemStack[taggedItems.size()];
                            int i = 0;
                            for (Item item : taggedItems) {
                                stacks[i++] = new ItemStack(item);
                            }
                            return stacks;
                        }
                    }
                    return new ItemStack[0];
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val reads = read("TagReads")

        assertTrue(reads.contains("return !BuiltInRegistries.ITEM.getTag(tag).isPresent() || (BuiltInRegistries.ITEM.getTag(tag).map(net.minecraft.core.HolderSet.Named::size).orElse(0) == 0);"), reads)
        assertTrue(reads.contains("BuiltInRegistries.ITEM.getTag(ItemTags.LOGS).stream().flatMap(net.minecraft.core.HolderSet.Named::stream).map(net.minecraft.core.Holder::value).forEach(logs::add);"), reads)
        assertTrue(reads.contains("if (BuiltInRegistries.ITEM.getTag(tag).isPresent()) {"), reads)
        assertTrue(reads.contains("java.util.List<Item> taggedItems = BuiltInRegistries.ITEM.getTag(tag).stream().flatMap(net.minecraft.core.HolderSet.Named::stream).map(net.minecraft.core.Holder::value).toList();"), reads)
        assertFalse(reads.contains("ITagManager"), reads)
        assertFalse(reads.contains("ITag<"), reads)
        assertFalse(reads.contains("registries.tags.ITag;"), reads)
    }

    @Test
    fun `a local shadowing a project typed field is called by its own type`() {
        mod()
        java("Slot", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class Slot {
                private ItemStack stack = ItemStack.EMPTY;

                public CompoundTag serializeNBT() {
                    CompoundTag tag = new CompoundTag();
                    tag.put("Item", stack.serializeNBT());
                    return tag;
                }
            }
        """)
        java("Holder", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class Holder {
                private Slot filter = new Slot();

                public CompoundTag saved() {
                    return filter.serializeNBT();
                }

                public void copy(CompoundTag tag, ItemStack held) {
                    ItemStack filter = held.copy();
                    tag.put("Filter", filter.serializeNBT());
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val holder = read("Holder")

        // the field is a Slot, the local that shadows it a stack
        assertTrue(holder.contains("return filter.serializeNBT(registries);"), holder)
        assertTrue(holder.contains("tag.put(\"Filter\", filter.saveOptional(registries));"), holder)
    }

    @Test
    fun `a platform method overridden without the annotation keeps its signature`() {
        mod()
        java("NoteScreen", """
            package com.example;

            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.ItemStack;

            public class NoteScreen extends Screen {
                private ItemStack note = ItemStack.EMPTY;

                protected NoteScreen() {
                    super(Component.empty());
                }

                public boolean mouseClicked(double mouseX, double mouseY, int button) {
                    save();
                    return super.mouseClicked(mouseX, mouseY, button);
                }

                private CompoundTag save() {
                    return note.save(new CompoundTag());
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val screen = read("NoteScreen")

        // Screen#mouseClicked comes from ContainerEventHandler: the method overrides it, annotated or not
        assertTrue(screen.contains("public boolean mouseClicked(double mouseX, double mouseY, int button) {"), screen)
    }

    @Test
    fun `a method calling its super method under a library supertype keeps its signature`() {
        mod()
        java("NoteScreen", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import org.library.gui.LibraryScreen;

            public class NoteScreen extends LibraryScreen {
                private ItemStack note = ItemStack.EMPTY;

                public boolean onClick(double mouseX, double mouseY, int button) {
                    save();
                    return super.onClick(mouseX, mouseY, button);
                }

                private CompoundTag save() {
                    return note.save(new CompoundTag());
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val screen = read("NoteScreen")

        // super.onClick(..) proves an override of the library type's method, which no project type declares
        assertTrue(screen.contains("public boolean onClick(double mouseX, double mouseY, int button) {"), screen)
    }

    @Test
    fun `a stack parameter elsewhere does not make a same named field a stack`() {
        mod()
        java("Slot", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class Slot {
                private ItemStack stack = ItemStack.EMPTY;

                public static Slot of(ItemStack stack) {
                    Slot slot = new Slot();
                    slot.stack = stack;
                    return slot;
                }

                public CompoundTag serializeNBT() {
                    CompoundTag tag = new CompoundTag();
                    tag.put("Item", stack.serializeNBT());
                    return tag;
                }
            }
        """)
        java("Observer", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class Observer {
                private Slot filter = new Slot();

                public void setFilter(ItemStack filter) {
                    this.filter = Slot.of(filter.copy());
                }

                public void write(CompoundTag nbt) {
                    nbt.put("Filter", filter.serializeNBT());
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val observer = read("Observer")

        assertFalse(observer.contains("filter.saveOptional("), observer)
        assertTrue(Regex("""nbt\.put\("Filter", filter\.serializeNBT\(\w+\)\);""").containsMatchIn(observer), observer)
    }

    @Test
    fun `block entity load and save calls take the registries in scope`() {
        mod()
        java("TileEntities", """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public class TileEntities {
                static boolean place(Level world, BlockPos position, CompoundTag tag) {
                    BlockEntity tileEntity = BlockEntity.loadStatic(position, world.getBlockState(position), tag);
                    return tileEntity != null;
                }

                static void mirror(ServerLevel mirrorWorld, BlockPos pos, CompoundTag copy) {
                    BlockEntity target = BlockEntity.loadStatic(pos, mirrorWorld.getBlockState(pos), copy);
                }

                public static CompoundTag copy(BlockEntity tile) {
                    return tile.saveWithId();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val tiles = read("TileEntities")

        assertTrue(tiles.contains("BlockEntity.loadStatic(position, world.getBlockState(position), tag, world.registryAccess())"), tiles)
        assertTrue(tiles.contains("BlockEntity.loadStatic(pos, mirrorWorld.getBlockState(pos), copy, mirrorWorld.registryAccess())"), tiles)
        assertTrue(tiles.contains("return tile.saveWithId(tile.getLevel().registryAccess());"), tiles)
    }

    @Test
    fun `command senders parse components with the registries of their source`() {
        mod()
        java("CommandSender", """
            package com.example;

            import net.minecraft.commands.CommandSourceStack;
            import net.minecraft.network.chat.Component;

            public class CommandSender {
                private final CommandSourceStack sender;

                public CommandSender(CommandSourceStack sender) {
                    this.sender = sender;
                }

                public void print(String json) {
                    java.util.Objects.requireNonNull(sender);
                    sender.sendSystemMessage(Component.Serializer.fromJson(json));
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val sender = read("CommandSender")

        assertTrue(sender.contains("Component.Serializer.fromJson(json, sender.registryAccess())"), sender)
    }

    @Test
    fun `level data spawn coordinates, server tick time and block entity packets follow 1_21`() {
        mod()
        java("WorldInfo", """
            package com.example;

            import net.minecraft.Util;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
            import net.minecraft.server.dedicated.DedicatedServer;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.entity.StructureBlockEntity;
            import net.minecraft.world.level.storage.LevelData;

            public class WorldInfo {
                private final DedicatedServer server;

                WorldInfo(DedicatedServer server) {
                    this.server = server;
                }

                int spawnX(Level level) {
                    LevelData worldInfo = level.getLevelData();
                    return worldInfo.getXSpawn() + worldInfo.getZSpawn();
                }

                void tick() {
                    server.nextTickTime = Util.getMillis();
                }

                Object packet(BlockPos pos, CompoundTag nbt) {
                    return ClientboundBlockEntityDataPacket.create(new StructureBlockEntity(pos, Blocks.STRUCTURE_BLOCK.defaultBlockState()), __ -> nbt);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val info = read("WorldInfo")

        assertTrue(info.contains("return worldInfo.getSpawnPos().getX() + worldInfo.getSpawnPos().getZ();"), info)
        assertTrue(info.contains("server.nextTickTimeNanos = net.minecraft.Util.getNanos();"), info)
        assertTrue(info.contains("Blocks.STRUCTURE_BLOCK.defaultBlockState()), (__, registries) -> nbt);"), info)
    }

    @Test
    fun `saved stacks keep their compound type where one is required`() {
        mod()
        java("Stacks", """
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.item.ItemStack;

            public class Stacks {
                static String describe(CompoundTag tag) {
                    return tag.toString();
                }

                static void write(ItemStack stack, ListTag list, HolderLookup.Provider registries) {
                    String text = describe(stack.save(new CompoundTag()));
                    list.add(stack.save(new CompoundTag()));
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val stacks = read("Stacks")

        assertTrue(stacks.contains("describe(((net.minecraft.nbt.CompoundTag) stack.saveOptional(registries)))"), stacks)
        assertTrue(stacks.contains("list.add(stack.saveOptional(registries));"), stacks)
    }

    @Test
    fun `access transformer methods follow classes that moved package`() {
        tempDir.resolve("src/main/resources/META-INF").createDirectories()
        tempDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
            minecraft {
                accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')
            }
        """.trimIndent())
        tempDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").writeText(
            "public net.minecraft.server.level.ServerChunkCache m_8456_(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture; # getChunkFutureMainThread\n"
        )

        BuildSystemPass().apply(tempDir)
        val at = tempDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()

        assertTrue(at.contains("public net.minecraft.server.level.ServerChunkCache getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"), at)
    }
}
