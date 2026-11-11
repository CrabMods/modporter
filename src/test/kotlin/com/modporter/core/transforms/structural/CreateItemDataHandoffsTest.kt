package com.modporter.core.transforms.structural

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CreateItemDataHandoffsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun exact_handoffs_migrate_transactionally_and_are_idempotent() {
        val sources = exactSources()
        assertTrue(CreateItemDataHandoffs.hasPendingProjectMigration(sources))

        val migrated = CreateItemDataHandoffs.migrateProject(sources)
        assertNotSame(sources, migrated)
        assertContains(
            migrated.getValue(CLIPBOARD_SCREEN),
            "item.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                "net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
        )
        assertContains(
            migrated.getValue(MINECART_ITEM),
            "itemstack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                "net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
        )
        assertFalse(CreateItemDataHandoffs.hasPendingProjectMigration(migrated))
        assertSame(migrated, CreateItemDataHandoffs.migrateProject(migrated))
    }

    @Test
    fun altered_packet_constructor_or_delayed_encoder_rejects_clipboard_snapshot() {
        val sources = exactSources()
        val changedConstructor = sources.withSource(
            CLIPBOARD_PACKET,
            sources.getValue(CLIPBOARD_PACKET).replace(
                "this.data = data;",
                "this.data = data.copy();"
            )
        )
        assertSame(changedConstructor, CreateItemDataHandoffs.migrateProject(changedConstructor))
        assertTrue(CreateItemDataHandoffs.hasPendingProjectMigration(changedConstructor))

        val changedEncoder = sources.withSource(
            CLIPBOARD_PACKET,
            sources.getValue(CLIPBOARD_PACKET).replace(
                "buffer.writeNbt(data);",
                "buffer.writeNbt(data.copy());"
            )
        )
        assertSame(changedEncoder, CreateItemDataHandoffs.migrateProject(changedEncoder))
        assertTrue(CreateItemDataHandoffs.hasPendingProjectMigration(changedEncoder))
    }

    @Test
    fun unknown_constructor_and_minecart_caller_closure_reject_all_edits() {
        val sources = exactSources()
        val unknownPacketCaller = LinkedHashMap(sources).apply {
            put(
                "rogue/PacketFactory.java",
                """
                package rogue;
                class PacketFactory {
                    Object make() {
                        return new ClipboardEditPacket(0, null, null);
                    }
                }
                """.trimIndent()
            )
        }
        assertSame(
            unknownPacketCaller,
            CreateItemDataHandoffs.migrateProject(unknownPacketCaller)
        )

        val unknownMinecartCaller = LinkedHashMap(sources).apply {
            put(
                "rogue/MinecartCaller.java",
                """
                package rogue;
                class MinecartCaller {
                    void call(Object world, Object stack, Object cart, Object direction) {
                        MinecartContraptionItem.addContraptionToMinecart(
                            world, stack, cart, direction);
                    }
                }
                """.trimIndent()
            )
        }
        assertSame(
            unknownMinecartCaller,
            CreateItemDataHandoffs.migrateProject(unknownMinecartCaller)
        )
    }

    @Test
    fun non_consuming_minecart_caller_and_method_reference_reject_public_helper() {
        val sources = exactSources()
        val nonConsuming = sources.withSource(
            MINECART_ITEM,
            sources.getValue(MINECART_ITEM).replace(
                "stack.shrink(1);",
                "stack.grow(1);"
            )
        )
        assertSame(nonConsuming, CreateItemDataHandoffs.migrateProject(nonConsuming))

        val referenced = LinkedHashMap(sources).apply {
            put(
                "rogue/Reference.java",
                """
                package rogue;
                class Reference {
                    Object reference = MinecartContraptionItem::addContraptionToMinecart;
                }
                """.trimIndent()
            )
        }
        assertSame(referenced, CreateItemDataHandoffs.migrateProject(referenced))
    }

    @Test
    fun failed_active_family_rolls_back_other_and_invalid_input_never_publishes() {
        val sources = exactSources()
        val brokenMinecart = sources.withSource(
            MINECART_ITEM,
            sources.getValue(MINECART_ITEM).replace(
                "itemstack.shrink(1);",
                "itemstack.grow(1);"
            )
        )
        val refused = CreateItemDataHandoffs.migrateProject(brokenMinecart)
        assertSame(brokenMinecart, refused)
        assertContains(refused.getValue(CLIPBOARD_SCREEN), "item.getOrCreateTag()")

        val invalid = LinkedHashMap(sources).apply {
            put("broken/Broken.java", "class Broken { ClipboardEditPacket")
        }
        assertSame(invalid, CreateItemDataHandoffs.migrateProject(invalid))
        assertTrue(CreateItemDataHandoffs.hasPendingProjectMigration(invalid))
    }

    @Test
    fun comments_and_literals_are_not_callers_or_active_surfaces() {
        val decoyOnly = linkedMapOf(
            "sample/Decoy.java" to """
                package sample;
                class Decoy {
                    // new ClipboardEditPacket(0, tag, null)
                    String text = "addContraptionToMinecart MinecartContraptionItem";
                }
            """.trimIndent()
        )
        assertSame(decoyOnly, CreateItemDataHandoffs.migrateProject(decoyOnly))
        assertFalse(CreateItemDataHandoffs.hasPendingProjectMigration(decoyOnly))

        val withDecoys = LinkedHashMap(exactSources()).apply {
            put("sample/Decoy.java", decoyOnly.getValue("sample/Decoy.java"))
        }
        assertNotSame(withDecoys, CreateItemDataHandoffs.migrateProject(withDecoys))
    }

    @Test
    fun clipboard_item_field_and_execution_scope_shadows_reject() {
        val sources = exactSources()
        val staticItem = sources.withSource(
            CLIPBOARD_SCREEN,
            sources.getValue(CLIPBOARD_SCREEN).replace(
                "private final ItemStack item;",
                "private static ItemStack item;"
            )
        )
        assertSame(staticItem, CreateItemDataHandoffs.migrateProject(staticItem))

        val localItem = sources.withSource(
            CLIPBOARD_SCREEN,
            sources.getValue(CLIPBOARD_SCREEN).replace(
                "private void send() {",
                "private void send() {\n                    ItemStack item = this.item;"
            )
        )
        assertSame(localItem, CreateItemDataHandoffs.migrateProject(localItem))

        val nestedLambda = sources.withSource(
            CLIPBOARD_SCREEN,
            sources.getValue(CLIPBOARD_SCREEN).replace(
                "PacketDistributor.sendToServer(",
                "Runnable deferred = () -> PacketDistributor.sendToServer("
            )
        )
        assertContains(nestedLambda.getValue(CLIPBOARD_SCREEN), "Runnable deferred = () ->")
        assertSame(nestedLambda, CreateItemDataHandoffs.migrateProject(nestedLambda))
    }

    @Test
    fun clipboard_import_type_and_static_scope_impostors_reject() {
        val sources = exactSources()
        val distributorField = sources.withSource(
            CLIPBOARD_SCREEN,
            sources.getValue(CLIPBOARD_SCREEN).replace(
                "public class ClipboardScreen {",
                "public class ClipboardScreen {\n" +
                    "    private static final Object PacketDistributor = null;"
            )
        )
        assertSame(distributorField, CreateItemDataHandoffs.migrateProject(distributorField))

        val codecField = sources.withSource(
            CLIPBOARD_PACKET,
            sources.getValue(CLIPBOARD_PACKET).replace(
                "public class ClipboardEditPacket extends SimplePacketBase {",
                "public class ClipboardEditPacket extends SimplePacketBase {\n" +
                    "    private static final Object StreamCodec = null;"
            )
        )
        assertSame(codecField, CreateItemDataHandoffs.migrateProject(codecField))

        val wrongTag = sources.withSource(
            CLIPBOARD_PACKET,
            sources.getValue(CLIPBOARD_PACKET).replace(
                "import net.minecraft.nbt.CompoundTag;",
                "import rogue.CompoundTag;"
            )
        )
        assertSame(wrongTag, CreateItemDataHandoffs.migrateProject(wrongTag))
    }

    @Test
    fun project_source_cannot_supply_an_sdk_fqn() {
        val sources = exactSources()
        val suppliedSdk = LinkedHashMap(sources).apply {
            put(
                "sdk/ItemStack.java",
                "package net.minecraft.world.item; public class ItemStack {}"
            )
        }
        assertSame(suppliedSdk, CreateItemDataHandoffs.migrateProject(suppliedSdk))
    }

    @Test
    fun minecart_exact_types_and_create_static_scopes_reject_impostors() {
        val sources = exactSources()
        val wrongLevel = sources.withSource(
            MINECART_ITEM,
            sources.getValue(MINECART_ITEM).replace(
                "import net.minecraft.world.level.Level;",
                "import rogue.Level;"
            )
        )
        assertSame(wrongLevel, CreateItemDataHandoffs.migrateProject(wrongLevel))

        val contraptionField = sources.withSource(
            MINECART_ITEM,
            sources.getValue(MINECART_ITEM).replace(
                "public class MinecartContraptionItem {",
                "public class MinecartContraptionItem {\n" +
                    "    private static final Object Contraption = null;"
            )
        )
        assertSame(contraptionField, CreateItemDataHandoffs.migrateProject(contraptionField))

        val wrongOriented = sources.withSource(
            MINECART_ITEM,
            sources.getValue(MINECART_ITEM).replace(
                "import com.simibubi.create.content.contraptions.OrientedContraptionEntity;",
                "import rogue.OrientedContraptionEntity;"
            )
        )
        assertSame(wrongOriented, CreateItemDataHandoffs.migrateProject(wrongOriented))
    }

    @Test
    fun generated_java_snapshots_survive_later_source_mutation() {
        val sources = exactSources()
        val migrated = CreateItemDataHandoffs.migrateProject(sources)
        assertNotSame(sources, migrated)

        val allSources = LinkedHashMap<String, String>()
        allSources.putAll(migrated)
        allSources.putAll(runtimeSupport())
        allSources.forEach { (relative, source) -> write(relative, source) }

        val classes = directory.resolve("compiled")
        classes.createDirectories()
        compileJava(classes)

        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val probe = loader.loadClass("probe.RuntimeProbe")
            val result = probe.getMethod("verify").invoke(null)
            assertTrue(result == "verified", result.toString())
        }
    }

    private fun exactSources(): LinkedHashMap<String, String> = linkedMapOf(
        CLIPBOARD_PACKET to """
            package com.simibubi.create.content.equipment.clipboard;

            import javax.annotation.Nullable;
            import com.simibubi.create.foundation.networking.SimplePacketBase;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.network.codec.StreamCodec;
            import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
            import net.minecraft.resources.ResourceLocation;

            public class ClipboardEditPacket extends SimplePacketBase {
                public static final CustomPacketPayload.Type<ClipboardEditPacket> TYPE =
                    new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                        "create", "clipboard_edit_packet"));
                public static final StreamCodec<RegistryFriendlyByteBuf, ClipboardEditPacket>
                    STREAM_CODEC = StreamCodec.of(
                        (buffer, packet) -> packet.write(buffer), ClipboardEditPacket::new);

                private int hotbarSlot;
                private CompoundTag data;
                private BlockPos targetedBlock;

                public ClipboardEditPacket(
                        int hotbarSlot, CompoundTag data, @Nullable BlockPos targetedBlock) {
                    this.hotbarSlot = hotbarSlot;
                    this.data = data;
                    this.targetedBlock = targetedBlock;
                }

                public ClipboardEditPacket(RegistryFriendlyByteBuf buffer) {
                    hotbarSlot = buffer.readVarInt();
                    data = buffer.readNbt();
                    if (buffer.readBoolean())
                        targetedBlock = buffer.readBlockPos();
                }

                @Override
                public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
                    return TYPE;
                }

                @Override
                public void write(RegistryFriendlyByteBuf buffer) {
                    buffer.writeVarInt(hotbarSlot);
                    buffer.writeNbt(data);
                    buffer.writeBoolean(targetedBlock != null);
                    if (targetedBlock != null)
                        buffer.writeBlockPos(targetedBlock);
                }
            }
        """.trimIndent() + "\n",
        CLIPBOARD_SCREEN to """
            package com.simibubi.create.content.equipment.clipboard;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.network.PacketDistributor;

            public class ClipboardScreen {
                private final int targetSlot;
                private final ItemStack item;
                private final BlockPos targetedBlock;

                public ClipboardScreen(int targetSlot, ItemStack item, BlockPos targetedBlock) {
                    this.targetSlot = targetSlot;
                    this.item = item;
                    this.targetedBlock = targetedBlock;
                }

                private void send() {
                    PacketDistributor.sendToServer(
                        new ClipboardEditPacket(
                            targetSlot, item.getOrCreateTag(), targetedBlock));
                }

                public void trigger() {
                    send();
                }
            }
        """.trimIndent() + "\n",
        MINECART_ITEM to """
            package com.simibubi.create.content.contraptions.mounted;

            import javax.annotation.Nullable;
            import com.simibubi.create.content.contraptions.Contraption;
            import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.Direction;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public class MinecartContraptionItem {
                public ItemStack execute(BlockSource source, ItemStack stack) {
                    Level world = source.level();
                    Direction direction = source.direction();
                    AbstractMinecart abstractminecartentity = new AbstractMinecart();
                    addContraptionToMinecart(
                        world, stack, abstractminecartentity, direction);
                    stack.shrink(1);
                    return stack;
                }

                public InteractionResult useOn(UseOnContext context) {
                    Level world = context.getLevel();
                    ItemStack itemstack = context.getItemInHand();
                    if (!world.isClientSide) {
                        AbstractMinecart abstractminecartentity = new AbstractMinecart();
                        Player player = context.getPlayer();
                        addContraptionToMinecart(world, itemstack, abstractminecartentity,
                            player == null ? null : player.getDirection());
                    }
                    itemstack.shrink(1);
                    return InteractionResult.SUCCESS;
                }

                public static void addContraptionToMinecart(
                        Level world, ItemStack itemstack, AbstractMinecart cart,
                        @Nullable Direction newFacing) {
                    CompoundTag tag = itemstack.getOrCreateTag();
                    if (tag.contains("Contraption")) {
                        CompoundTag contraptionTag = tag.getCompound("Contraption");
                        Direction intialOrientation = NBTHelper.readEnum(
                            contraptionTag, "InitialOrientation", Direction.class);
                        Contraption mountedContraption =
                            Contraption.fromNBT(world, contraptionTag, false);
                        OrientedContraptionEntity contraptionEntity =
                            newFacing == null
                                ? OrientedContraptionEntity.create(
                                    world, mountedContraption, intialOrientation)
                                : OrientedContraptionEntity.createAtYaw(
                                    world, mountedContraption, intialOrientation,
                                    newFacing.toYRot());
                        contraptionEntity.startRiding(cart);
                        contraptionEntity.setPos(cart.getX(), cart.getY(), cart.getZ());
                        world.addFreshEntity(contraptionEntity);
                    }
                }
            }
        """.trimIndent() + "\n"
    )

    private fun runtimeSupport(): Map<String, String> = linkedMapOf(
        "javax/annotation/Nullable.java" to """
            package javax.annotation;
            public @interface Nullable {}
        """.trimIndent(),
        "net/minecraft/nbt/CompoundTag.java" to """
            package net.minecraft.nbt;

            import java.util.LinkedHashMap;
            import java.util.Map;

            public class CompoundTag {
                private final Map<String, Object> values = new LinkedHashMap<>();

                public void put(String key, CompoundTag value) { values.put(key, value); }
                public void putString(String key, String value) { values.put(key, value); }
                public String getString(String key) {
                    Object value = values.get(key);
                    return value instanceof String ? (String) value : "";
                }
                public boolean contains(String key) { return values.containsKey(key); }
                public CompoundTag getCompound(String key) {
                    Object value = values.get(key);
                    return value instanceof CompoundTag ? (CompoundTag) value : new CompoundTag();
                }
                public CompoundTag copy() {
                    CompoundTag copy = new CompoundTag();
                    for (Map.Entry<String, Object> entry : values.entrySet()) {
                        Object value = entry.getValue();
                        copy.values.put(entry.getKey(),
                            value instanceof CompoundTag ? ((CompoundTag) value).copy() : value);
                    }
                    return copy;
                }
            }
        """.trimIndent(),
        "net/minecraft/core/BlockPos.java" to """
            package net.minecraft.core;
            public class BlockPos {}
        """.trimIndent(),
        "net/minecraft/core/Direction.java" to """
            package net.minecraft.core;
            public enum Direction {
                NORTH;
                public float toYRot() { return 0.0F; }
            }
        """.trimIndent(),
        "net/minecraft/core/component/DataComponents.java" to """
            package net.minecraft.core.component;
            public final class DataComponents {
                public static final Object CUSTOM_DATA = new Object();
            }
        """.trimIndent(),
        "net/minecraft/world/item/component/CustomData.java" to """
            package net.minecraft.world.item.component;

            import net.minecraft.nbt.CompoundTag;

            public final class CustomData {
                public static final CustomData EMPTY = new CustomData(new CompoundTag(), false);
                private final CompoundTag tag;

                private CustomData(CompoundTag tag, boolean copy) {
                    this.tag = copy ? tag.copy() : tag;
                }
                public static CustomData of(CompoundTag tag) {
                    return new CustomData(tag, true);
                }
                public CompoundTag copyTag() { return tag.copy(); }
                public CompoundTag mutableTag() { return tag; }
            }
        """.trimIndent(),
        "net/minecraft/world/item/ItemStack.java" to """
            package net.minecraft.world.item;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.component.CustomData;

            public class ItemStack {
                private final CustomData data;
                private int count = 1;

                public ItemStack(CustomData data) { this.data = data; }
                public CompoundTag getOrCreateTag() { return data.mutableTag(); }
                public <T> T getOrDefault(Object component, T fallback) {
                    return (T) data;
                }
                public CompoundTag mutableTag() { return data.mutableTag(); }
                public void shrink(int amount) { count -= amount; }
                public void grow(int amount) { count += amount; }
                public int count() { return count; }
            }
        """.trimIndent(),
        "net/minecraft/network/protocol/common/custom/CustomPacketPayload.java" to """
            package net.minecraft.network.protocol.common.custom;
            public interface CustomPacketPayload {
                Type<? extends CustomPacketPayload> type();
                final class Type<T extends CustomPacketPayload> {
                    public Type(Object id) {}
                }
            }
        """.trimIndent(),
        "net/minecraft/network/RegistryFriendlyByteBuf.java" to """
            package net.minecraft.network;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;

            public class RegistryFriendlyByteBuf {
                public CompoundTag written;
                public void writeVarInt(int value) {}
                public int readVarInt() { return 0; }
                public void writeNbt(CompoundTag value) { written = value.copy(); }
                public CompoundTag readNbt() { return new CompoundTag(); }
                public void writeBoolean(boolean value) {}
                public boolean readBoolean() { return false; }
                public void writeBlockPos(BlockPos value) {}
                public BlockPos readBlockPos() { return new BlockPos(); }
            }
        """.trimIndent(),
        "net/minecraft/network/codec/StreamCodec.java" to """
            package net.minecraft.network.codec;

            import java.util.function.BiConsumer;
            import java.util.function.Function;

            public final class StreamCodec<B, P> {
                public static <B, P> StreamCodec<B, P> of(
                        BiConsumer<B, P> encoder, Function<B, P> decoder) {
                    return new StreamCodec<>();
                }
            }
        """.trimIndent(),
        "net/minecraft/resources/ResourceLocation.java" to """
            package net.minecraft.resources;
            public final class ResourceLocation {
                public static ResourceLocation fromNamespaceAndPath(String namespace, String path) {
                    return new ResourceLocation();
                }
            }
        """.trimIndent(),
        "com/simibubi/create/foundation/networking/SimplePacketBase.java" to """
            package com.simibubi.create.foundation.networking;

            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

            public abstract class SimplePacketBase implements CustomPacketPayload {
                public abstract void write(RegistryFriendlyByteBuf buffer);
            }
        """.trimIndent(),
        "net/neoforged/neoforge/network/PacketDistributor.java" to """
            package net.neoforged.neoforge.network;

            import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

            public final class PacketDistributor {
                private static CustomPacketPayload last;
                public static void sendToServer(CustomPacketPayload payload) { last = payload; }
                public static CustomPacketPayload take() { return last; }
            }
        """.trimIndent(),
        "net/minecraft/world/level/Level.java" to """
            package net.minecraft.world.level;
            public class Level {
                public boolean isClientSide;
                public void addFreshEntity(Object entity) {}
            }
        """.trimIndent(),
        "net/minecraft/world/entity/vehicle/AbstractMinecart.java" to """
            package net.minecraft.world.entity.vehicle;
            public class AbstractMinecart {
                public double getX() { return 1.0; }
                public double getY() { return 2.0; }
                public double getZ() { return 3.0; }
            }
        """.trimIndent(),
        "net/minecraft/world/entity/player/Player.java" to """
            package net.minecraft.world.entity.player;
            import net.minecraft.core.Direction;
            public class Player {
                public Direction getDirection() { return Direction.NORTH; }
            }
        """.trimIndent(),
        "net/createmod/catnip/nbt/NBTHelper.java" to """
            package net.createmod.catnip.nbt;

            import net.minecraft.nbt.CompoundTag;

            public final class NBTHelper {
                public static <T extends Enum<T>> T readEnum(
                        CompoundTag tag, String key, Class<T> type) {
                    return type.getEnumConstants()[0];
                }
            }
        """.trimIndent(),
        "com/simibubi/create/content/contraptions/Contraption.java" to """
            package com.simibubi.create.content.contraptions;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;

            public final class Contraption {
                public final CompoundTag retainedNested;
                private Contraption(CompoundTag retainedNested) {
                    this.retainedNested = retainedNested;
                }
                public static Contraption fromNBT(Level world, CompoundTag nbt, boolean spawnData) {
                    return new Contraption(nbt.getCompound("Nested"));
                }
            }
        """.trimIndent(),
        "com/simibubi/create/content/contraptions/OrientedContraptionEntity.java" to """
            package com.simibubi.create.content.contraptions;

            import com.simibubi.create.content.contraptions.Contraption;
            import net.minecraft.core.Direction;
            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.minecraft.world.level.Level;

            public final class OrientedContraptionEntity {
                public static OrientedContraptionEntity last;
                public final Contraption mounted;
                private OrientedContraptionEntity(Contraption mounted) {
                    this.mounted = mounted;
                    last = this;
                }
                public static OrientedContraptionEntity create(
                        Level world, Contraption mounted, Direction orientation) {
                    return new OrientedContraptionEntity(mounted);
                }
                public static OrientedContraptionEntity createAtYaw(
                        Level world, Contraption mounted, Direction orientation, float yaw) {
                    return new OrientedContraptionEntity(mounted);
                }
                public void startRiding(AbstractMinecart cart) {}
                public void setPos(double x, double y, double z) {}
            }
        """.trimIndent(),
        "com/simibubi/create/content/contraptions/mounted/BlockSource.java" to """
            package com.simibubi.create.content.contraptions.mounted;

            import net.minecraft.core.Direction;
            import net.minecraft.world.level.Level;

            public final class BlockSource {
                private final Level level;
                public BlockSource(Level level) { this.level = level; }
                public Level level() { return level; }
                public Direction direction() { return Direction.NORTH; }
            }
        """.trimIndent(),
        "com/simibubi/create/content/contraptions/mounted/UseOnContext.java" to """
            package com.simibubi.create.content.contraptions.mounted;

            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public final class UseOnContext {
                private final Level level;
                private final ItemStack stack;
                private final Player player;
                public UseOnContext(Level level, ItemStack stack, Player player) {
                    this.level = level;
                    this.stack = stack;
                    this.player = player;
                }
                public Level getLevel() { return level; }
                public ItemStack getItemInHand() { return stack; }
                public Player getPlayer() { return player; }
            }
        """.trimIndent(),
        "com/simibubi/create/content/contraptions/mounted/InteractionResult.java" to """
            package com.simibubi.create.content.contraptions.mounted;
            public enum InteractionResult { SUCCESS }
        """.trimIndent(),
        "probe/RuntimeProbe.java" to """
            package probe;

            import com.simibubi.create.content.contraptions.mounted.MinecartContraptionItem;
            import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
            import com.simibubi.create.content.equipment.clipboard.ClipboardEditPacket;
            import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.component.CustomData;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.network.PacketDistributor;

            public final class RuntimeProbe {
                public static String verify() {
                    CompoundTag clipboardNested = new CompoundTag();
                    clipboardNested.putString("value", "before");
                    CompoundTag clipboardRoot = new CompoundTag();
                    clipboardRoot.putString("sibling", "preserved");
                    clipboardRoot.put("Nested", clipboardNested);
                    ItemStack clipboardStack = new ItemStack(CustomData.of(clipboardRoot));

                    new ClipboardScreen(4, clipboardStack, new BlockPos()).trigger();
                    clipboardStack.mutableTag().putString("sibling", "changed");
                    clipboardStack.mutableTag().getCompound("Nested")
                        .putString("value", "changed");

                    ClipboardEditPacket packet =
                        (ClipboardEditPacket) PacketDistributor.take();
                    RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf();
                    packet.write(buffer);
                    require("preserved".equals(buffer.written.getString("sibling")));
                    require("before".equals(
                        buffer.written.getCompound("Nested").getString("value")));

                    CompoundTag retained = new CompoundTag();
                    retained.putString("value", "before");
                    CompoundTag contraption = new CompoundTag();
                    contraption.putString("InitialOrientation", "NORTH");
                    contraption.put("Nested", retained);
                    CompoundTag minecartRoot = new CompoundTag();
                    minecartRoot.putString("sibling", "preserved");
                    minecartRoot.put("Contraption", contraption);
                    ItemStack minecartStack = new ItemStack(CustomData.of(minecartRoot));

                    MinecartContraptionItem.addContraptionToMinecart(
                        new Level(), minecartStack, new AbstractMinecart(), Direction.NORTH);
                    minecartStack.shrink(1);
                    minecartStack.mutableTag().getCompound("Contraption")
                        .getCompound("Nested").putString("value", "changed");
                    require(minecartStack.count() == 0);
                    require("before".equals(OrientedContraptionEntity.last.mounted
                        .retainedNested.getString("value")));
                    require("preserved".equals(
                        minecartStack.mutableTag().getString("sibling")));
                    return "verified";
                }

                private static void require(boolean condition) {
                    if (!condition) throw new AssertionError();
                }
            }
        """.trimIndent()
    )

    private fun compileJava(classes: Path) {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "Tests require a JDK Java compiler"
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val files = Files.walk(directory.resolve("src/main/java")).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                    .sorted()
                    .toList()
            }
            val units = manager.getJavaFileObjectsFromPaths(files)
            val success = compiler.getTask(
                null,
                manager,
                diagnostics,
                listOf("-proc:none", "-d", classes.toString()),
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
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimEnd() + "\n")
    }

    private fun Map<String, String>.withSource(
        path: String,
        source: String
    ): LinkedHashMap<String, String> = LinkedHashMap(this).apply { put(path, source) }

    private companion object {
        const val CLIPBOARD_PACKET =
            "com/simibubi/create/content/equipment/clipboard/ClipboardEditPacket.java"
        const val CLIPBOARD_SCREEN =
            "com/simibubi/create/content/equipment/clipboard/ClipboardScreen.java"
        const val MINECART_ITEM =
            "com/simibubi/create/content/contraptions/mounted/MinecartContraptionItem.java"
    }
}
