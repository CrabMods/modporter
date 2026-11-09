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

class ProviderAwareNbtContractMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exact Item use callback owns the Level provider even when its stack came from a player`() {
        val file = javaFile(
            "StackWritingItem.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResultHolder;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class StackWritingItem extends Item {
                StackWritingItem(Properties properties) {
                    super(properties);
                }

                @Override
                public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
                    ItemStack stack = player.getItemInHand(hand);
                    CompoundTag tag = stack.copy().save(new CompoundTag());
                    return InteractionResultHolder.success(stack);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("stack.copy().saveOptional(level.registryAccess())"), migrated)
        assertFalse(migrated.contains("player.registryAccess()"), migrated)
    }

    @Test
    fun `project CompoundTag serializable contracts migrate definitions bodies and call sites`() {
        val inventory = javaFile(
            "ProjectInventory.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.common.util.INBTSerializable;
            import net.neoforged.neoforge.items.ItemStackHandler;

            public class ProjectInventory implements INBTSerializable<CompoundTag> {
                private final ItemStackHandler backing = new ItemStackHandler();

                @Override
                public CompoundTag serializeNBT() {
                    return backing.serializeNBT();
                }

                @Override
                public void deserializeNBT(CompoundTag tag) {
                    backing.deserializeNBT(tag);
                }
            }
            """.trimIndent()
        )
        val owner = javaFile(
            "InventoryBlockEntity.java",
            """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            public class InventoryBlockEntity extends BlockEntity {
                private final ProjectInventory inventory = new ProjectInventory();

                public InventoryBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }

                protected void write(CompoundTag tag, boolean clientPacket) {
                    tag.put("Inventory", inventory.serializeNBT());
                }

                protected void read(CompoundTag tag, boolean clientPacket) {
                    inventory.deserializeNBT(tag.getCompound("Inventory"));
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedInventory = inventory.readText()
        val migratedOwner = owner.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val serializeProvider = Regex(
            """public CompoundTag serializeNBT\((?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+)\)"""
        ).find(migratedInventory)?.groupValues?.get(1)
        assertTrue(serializeProvider != null, migratedInventory)
        assertTrue(migratedInventory.contains("backing.serializeNBT($serializeProvider)"), migratedInventory)
        val deserializeProvider = Regex(
            """public void deserializeNBT\((?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+), CompoundTag tag\)"""
        ).find(migratedInventory)?.groupValues?.get(1)
        assertTrue(deserializeProvider != null, migratedInventory)
        assertTrue(
            migratedInventory.contains("backing.deserializeNBT($deserializeProvider, tag)"),
            migratedInventory
        )
        val writeProvider = Regex(
            """write\(CompoundTag tag, boolean clientPacket, (?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+)\)"""
        ).find(migratedOwner)?.groupValues?.get(1)
        val readProvider = Regex(
            """read\(CompoundTag tag, boolean clientPacket, (?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+)\)"""
        ).find(migratedOwner)?.groupValues?.get(1)
        assertTrue(writeProvider != null, migratedOwner)
        assertTrue(readProvider != null, migratedOwner)
        assertTrue(migratedOwner.contains("inventory.serializeNBT($writeProvider)"), migratedOwner)
        assertTrue(
            migratedOwner.contains("inventory.deserializeNBT($readProvider, tag.getCompound(\"Inventory\"))"),
            migratedOwner
        )
        assertFalse(migratedOwner.contains("getLevel().registryAccess()"), migratedOwner)
        assertFalse(migratedOwner.contains("inventory.deserializeNBT(tag.getCompound(\"Inventory\"),"), migratedOwner)
    }

    @Test
    fun `ItemStackHandler overrides preserve the provider first contract in super calls`() {
        val file = javaFile(
            "ProcessingInventory.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            public class ProcessingInventory extends ItemStackHandler {
                @Override
                public CompoundTag serializeNBT() {
                    CompoundTag tag = super.serializeNBT();
                    tag.putBoolean("Locked", true);
                    return tag;
                }

                @Override
                public void deserializeNBT(CompoundTag tag) {
                    super.deserializeNBT(tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("public CompoundTag serializeNBT(HolderLookup.Provider registries)"),
            migrated
        )
        assertTrue(migrated.contains("super.serializeNBT(registries)"), migrated)
        assertTrue(
            migrated.contains("public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag)"),
            migrated
        )
        assertTrue(migrated.contains("super.deserializeNBT(registries, tag)"), migrated)
        assertFalse(migrated.contains("deserializeNBT(CompoundTag tag, HolderLookup.Provider"), migrated)
    }

    @Test
    fun `provider demand closes an override family whose other files contain no nbt types`() {
        val root = javaFile(
            "DisplayTarget.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;

            public abstract class DisplayTarget {
                protected CompoundTag metadata() {
                    return new CompoundTag();
                }
                public abstract void accept(DisplayContext context);
            }
            """.trimIndent()
        )
        val direct = javaFile(
            "SerializedDisplayTarget.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            public class SerializedDisplayTarget extends DisplayTarget {
                private final ItemStackHandler contents = new ItemStackHandler();

                @Override
                public void accept(DisplayContext context) {
                    context.store(serialize());
                }

                private CompoundTag serialize() {
                    CompoundTag tag = contents.serializeNBT();
                    return tag;
                }
            }
            """.trimIndent()
        )
        val peer = javaFile(
            "PlainDisplayTarget.java",
            """
            package com.example;

            public class PlainDisplayTarget extends DisplayTarget {
                @Override
                public void accept(DisplayContext context) {
                    context.markAccepted();
                }
            }
            """.trimIndent()
        )
        javaFile(
            "DisplayContext.java",
            """
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.nbt.CompoundTag;

            public interface DisplayContext {
                ItemStack stack();
                void store(CompoundTag tag);
                void markAccepted();
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedRoot = root.readText()
        val migratedDirect = direct.readText()
        val migratedPeer = peer.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val providerParameter = "(?:net\\.minecraft\\.core\\.)?HolderLookup\\.Provider"
        assertTrue(Regex("accept\\(DisplayContext context, $providerParameter").containsMatchIn(migratedRoot), migratedRoot)
        assertTrue(Regex("accept\\(DisplayContext context, $providerParameter").containsMatchIn(migratedDirect), migratedDirect)
        assertTrue(Regex("accept\\(DisplayContext context, $providerParameter").containsMatchIn(migratedPeer), migratedPeer)
        assertTrue(Regex("""contents\.serializeNBT\(\w+\)""").containsMatchIn(migratedDirect), migratedDirect)
    }

    @Test
    fun `entity attachment lambda derives the provider from the exact data receiver`() {
        javaFile(
            "CartData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.common.util.INBTSerializable;

            public class CartData implements INBTSerializable<CompoundTag> {
                @Override
                public CompoundTag serializeNBT() {
                    return new CompoundTag();
                }

                @Override
                public void deserializeNBT(CompoundTag tag) {
                }
            }
            """.trimIndent()
        )
        javaFile(
            "DataAttachments.java",
            """
            package com.example;

            import java.util.function.Supplier;
            import net.neoforged.neoforge.attachment.AttachmentType;

            public class DataAttachments {
                public static final Supplier<AttachmentType<CartData>> CART_DATA = null;
            }
            """.trimIndent()
        )
        val packet = javaFile(
            "CartPacket.java",
            """
            package com.example;

            import com.modporter.generated.example.compat.LazyOptional;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;

            public class CartPacket {
                void apply(Entity exactEntity, CompoundTag tag) {
                    LazyOptional.ofNullable(exactEntity.getData(DataAttachments.CART_DATA.get()))
                        .ifPresent(data -> data.deserializeNBT(tag));
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = packet.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("data.deserializeNBT(exactEntity.registryAccess(), tag)"),
            migrated
        )
        assertFalse(migrated.contains("level.registryAccess()"), migrated)
        assertFalse(migrated.contains("RegistryAccess.EMPTY"), migrated)
    }

    @Test
    fun `non CompoundTag INBTSerializable contracts are not generalized`() {
        val file = javaFile(
            "ListData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.neoforged.neoforge.common.util.INBTSerializable;

            public class ListData implements INBTSerializable<ListTag> {
                @Override
                public ListTag serializeNBT() {
                    return new ListTag();
                }

                @Override
                public void deserializeNBT(ListTag tag) {
                }

                public CompoundTag documentationValue() {
                    return new CompoundTag();
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("public ListTag serializeNBT()"), migrated)
        assertTrue(migrated.contains("public void deserializeNBT(ListTag tag)"), migrated)
        assertFalse(migrated.contains("HolderLookup.Provider"), migrated)
    }

    @Test
    fun `same arity overloads migrate only the exact provider demanded declaration and typed call`() {
        val payload = javaFile(
            "Payload.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.item.ItemStack;

            public class Payload {
                ItemStack stack;

                public static Payload read(CompoundTag tag) {
                    Payload payload = new Payload();
                    payload.stack = ItemStack.of(tag.getCompound("Stack"));
                    return payload;
                }

                public static Payload read(FriendlyByteBuf buffer) {
                    return new Payload();
                }

                public void preserveTags(CompoundTag primary, CompoundTag optional) {
                }
            }
            """.trimIndent()
        )
        val caller = javaFile(
            "PayloadReader.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;

            public class PayloadReader {
                Payload read(Level level, CompoundTag tag) {
                    return Payload.read(tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedPayload = payload.readText()
        val migratedCaller = caller.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            Regex("""read\(CompoundTag tag, (?:net\.minecraft\.core\.)?HolderLookup\.Provider \w+\)""")
                .containsMatchIn(migratedPayload),
            migratedPayload
        )
        assertTrue(migratedPayload.contains("read(FriendlyByteBuf buffer)"), migratedPayload)
        assertFalse(migratedPayload.contains("read(FriendlyByteBuf buffer,"), migratedPayload)
        assertTrue(
            migratedPayload.contains("preserveTags(CompoundTag primary, CompoundTag optional)"),
            migratedPayload
        )
        assertTrue(
            Regex("""Payload\.read\(tag, level\.registryAccess\(\)\)""").containsMatchIn(migratedCaller),
            migratedCaller
        )
    }

    @Test
    fun `record methods and component accessors participate in exact provider propagation`() {
        val payload = javaFile(
            "RecordPayload.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public record RecordPayload(ItemStack stack) {
                public static RecordPayload read(CompoundTag tag) {
                    return new RecordPayload(ItemStack.of(tag.getCompound("Stack")));
                }
            }
            """.trimIndent()
        )
        val caller = javaFile(
            "RecordPayloadReader.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public class RecordPayloadReader {
                ItemStack readStack(Level level, CompoundTag tag) {
                    return RecordPayload.read(tag).stack();
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedPayload = payload.readText()
        val migratedCaller = caller.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            Regex("""read\(CompoundTag tag, (?:net\.minecraft\.core\.)?HolderLookup\.Provider \w+\)""")
                .containsMatchIn(migratedPayload),
            migratedPayload
        )
        assertTrue(migratedCaller.contains("RecordPayload.read(tag, level.registryAccess()).stack()"), migratedCaller)
    }

    @Test
    fun `super calls bind only the direct superclass method in provider graphs`() {
        val hierarchy = javaFile(
            "PayloadHierarchy.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class PayloadBase {
                ItemStack stack;

                protected CompoundTag write() {
                    return stack.serializeNBT();
                }
            }

            class PayloadChild extends PayloadBase {
                @Override
                protected CompoundTag write() {
                    return super.write();
                }
            }
            """.trimIndent()
        )
        val caller = javaFile(
            "PayloadHierarchyReader.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;

            public class PayloadHierarchyReader {
                CompoundTag write(Level level, PayloadChild payload) {
                    return payload.write();
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedHierarchy = hierarchy.readText()
        val migratedCaller = caller.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            Regex("""PayloadBase[\s\S]*write\((?:net\.minecraft\.core\.)?HolderLookup\.Provider \w+\)""")
                .containsMatchIn(migratedHierarchy),
            migratedHierarchy
        )
        assertTrue(Regex("""super\.write\(\w+\)""").containsMatchIn(migratedHierarchy), migratedHierarchy)
        assertTrue(migratedCaller.contains("payload.write(level.registryAccess())"), migratedCaller)
    }

    @Test
    fun `external Block placement override keeps its target signature and uses its contract provider`() {
        val block = javaFile(
            "PlacementBlock.java",
            """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;

            public class PlacementBlock extends Block {
                @Override
                public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
                    CompoundTag saved = stack.serializeNBT();
                    super.setPlacedBy(level, pos, state, placer, stack);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = block.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("stack.saveOptional(level.registryAccess())"), migrated)
        assertFalse(
            Regex("""setPlacedBy\([^)]*HolderLookup\.Provider""").containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains("super.setPlacedBy(level, pos, state, placer, stack);"), migrated)
    }

    @Test
    fun `project type names ending in ItemStack do not impersonate vanilla ItemStack factories`() {
        val wrapper = javaFile(
            "FilterItemStack.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class FilterItemStack {
                public static FilterItemStack of(ItemStack filter) {
                    return new FilterItemStack();
                }

                public static FilterItemStack of(CompoundTag tag) {
                    return of(ItemStack.of(tag));
                }
            }
            """.trimIndent()
        )
        val behaviour = javaFile(
            "FilteringBehaviour.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public class FilteringBehaviour {
                FilterItemStack filter;

                public void setFilter(ItemStack stack) {
                    filter = FilterItemStack.of(stack);
                }

                public void read(Level level, CompoundTag tag) {
                    filter = FilterItemStack.of(tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedWrapper = wrapper.readText()
        val migratedBehaviour = behaviour.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migratedWrapper.contains("of(ItemStack filter)"), migratedWrapper)
        assertFalse(Regex("""of\(ItemStack filter,\s*(?:net\.minecraft\.core\.)?HolderLookup\.Provider""")
            .containsMatchIn(migratedWrapper), migratedWrapper)
        assertTrue(migratedWrapper.contains("ItemStack.parseOptional("), migratedWrapper)
        assertTrue(migratedBehaviour.contains("setFilter(ItemStack stack)"), migratedBehaviour)
        assertTrue(migratedBehaviour.contains("FilterItemStack.of(stack);"), migratedBehaviour)
        assertFalse(migratedBehaviour.contains("FilterItemStack.parseOptional"), migratedBehaviour)
        assertTrue(
            migratedBehaviour.contains("FilterItemStack.of(tag, level.registryAccess())"),
            migratedBehaviour
        )
    }

    @Test
    fun `generic return type declarations are not rewritten as provider call sites`() {
        val file = javaFile(
            "GenericCapture.java",
            """
            package com.example;

            import com.mojang.datafixers.util.Pair;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public class GenericCapture {
                protected Pair<String, ItemStack> capture(Level world, CompoundTag tag) {
                    return Pair.of("item", ItemStack.of(tag));
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("capture(Level world, CompoundTag tag)"),
            migrated
        )
        assertFalse(migrated.contains("pos, world.registryAccess())"), migrated)
        assertTrue(migrated.contains("ItemStack.parseOptional(world.registryAccess(), tag)"), migrated)
    }

    @Test
    fun `exact Level provider roots stop project NBT demand propagation`() {
        val file = javaFile(
            "LevelOwnedCodec.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class LevelOwnedCodec {
                static LevelOwnedCodec fromNBT(Level world, CompoundTag tag, boolean packet) {
                    LevelOwnedCodec codec = new LevelOwnedCodec();
                    codec.readNBT(world, tag, packet);
                    return codec;
                }

                void readNBT(Level world, CompoundTag tag, boolean packet) {
                    ItemStack stack = ItemStack.of(tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("fromNBT(Level world, CompoundTag tag, boolean packet)"),
            migrated
        )
        assertTrue(
            migrated.contains("readNBT(Level world, CompoundTag tag, boolean packet)"),
            migrated
        )
        assertTrue(migrated.contains("codec.readNBT(world, tag, packet);"), migrated)
        assertTrue(migrated.contains("ItemStack.parseOptional(world.registryAccess(), tag)"), migrated)
        assertFalse(migrated.contains("HolderLookup.Provider"), migrated)
    }

    @Test
    fun `exact Level provider roots stop demand across an abstract override family`() {
        val file = javaFile(
            "LevelOwnedFamily.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            abstract class LevelOwnedFamily {
                abstract void place(Level world);

                void invoke(Level world) {
                    place(world);
                }
            }

            class LevelOwnedChild extends LevelOwnedFamily {
                @Override
                void place(Level world) {
                    ItemStack stack = ItemStack.of(new CompoundTag());
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("abstract void place(Level world);"), migrated)
        assertTrue(migrated.contains("void place(Level world)"), migrated)
        assertTrue(migrated.contains("place(world);"), migrated)
        assertTrue(
            migrated.contains("ItemStack.parseOptional(world.registryAccess(), new CompoundTag())"),
            migrated
        )
        assertFalse(migrated.contains("HolderLookup.Provider"), migrated)
    }

    @Test
    fun `inferred level root outranks an entity without widening project methods`() {
        val helper = javaFile(
            "AmbiguousProviderHelper.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;

            class AmbiguousProviderHelper {
                static void load(Level world, Player player, BlockEntity blockEntity, CompoundTag tag) {
                    blockEntity.load(tag);
                }

                static void invoke(Level world, Player player, BlockEntity blockEntity, CompoundTag tag) {
                    load(world, player, blockEntity, tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = helper.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("load(Level world, Player player, BlockEntity blockEntity, CompoundTag tag)"), migrated)
        assertTrue(migrated.contains("blockEntity.loadWithComponents(tag, world.registryAccess())"), migrated)
        assertTrue(migrated.contains("load(world, player, blockEntity, tag);"), migrated)
        assertFalse(migrated.contains("HolderLookup.Provider"), migrated)
        assertFalse(migrated.contains("blockEntity.loadWithComponents(tag, player.registryAccess())"), migrated)
    }

    @Test
    fun `multiple provider roots without provider demand do not block migration`() {
        val helper = javaFile(
            "ProviderNeutralHelper.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.Level;

            class ProviderNeutralHelper {
                void initialize(Level world, Entity entity) {
                    CompoundTag tag = new CompoundTag();
                    tag.putBoolean("Initialized", true);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = helper.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("initialize(Level world, Entity entity)"), migrated)
        assertFalse(migrated.contains("HolderLookup.Provider"), migrated)
    }

    @Test
    fun `instance Entity field is not selected when another instance Level can own the provider`() {
        val helper = javaFile(
            "AmbiguousInstanceProviderHelper.java",
            """
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class AmbiguousInstanceProviderHelper {
                public Entity entity;
                protected Level collisionLevel;

                ItemStack load(CompoundTag tag) {
                    return ItemStack.of(tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = helper.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val provider = Regex(
            """load\(CompoundTag tag, (?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+)\)"""
        ).find(migrated)?.groupValues?.get(1)
        assertTrue(provider != null, migrated)
        assertTrue(migrated.contains("ItemStack.parseOptional($provider, tag)"), migrated)
        assertFalse(migrated.contains("entity.registryAccess()"), migrated)
        assertFalse(migrated.contains("collisionLevel.registryAccess()"), migrated)
    }

    @Test
    fun `provider contracts stay closed across external BlockEntity calls`() {
        val snapshot = javaFile(
            "SnapshotHelper.java",
            """
            package com.example;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class SnapshotHelper {
                Object capture(Level level, BlockEntity blockEntity) {
                    return blockEntity.getUpdateTag();
                }
                Object invoke(Level level, BlockEntity blockEntity) {
                    return capture(level, blockEntity);
                }
            }
            """.trimIndent()
        )
        val result = StructuralRefactorPass().apply(tempDir)
        val migratedSnapshot = snapshot.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            Regex("""blockEntity\.getUpdateTag\((?:modporterRegistries|level\.registryAccess\(\))\)""")
                .containsMatchIn(migratedSnapshot),
            migratedSnapshot
        )
        assertTrue(
            Regex("""capture\(level, blockEntity(?:, level\.registryAccess\(\))?\)""")
                .containsMatchIn(migratedSnapshot),
            migratedSnapshot
        )
    }

    private fun javaFile(name: String, source: String): Path {
        val directory = tempDir.resolve("src/main/java/com/example").createDirectories()
        return directory.resolve(name).also { it.writeText(source) }
    }
}
