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

/** Forge runtime surfaces (raw channels, tick listeners, chunk futures, data fixers, component JSON) on 1.21.1. */
class ForgeRuntimeSurfacesTest {

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
    fun `an event network channel becomes a raw bytes payload on the same id`() {
        mod()
        java("Channels", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.network.NetworkRegistry;

            public final class Channels {
                private Channels() {
                }

                public static NetworkRegistry.ChannelBuilder builder(ResourceLocation id) {
                    return NetworkRegistry.ChannelBuilder.named(id)
                        .clientAcceptedVersions(version -> version.equals(NetworkRegistry.ABSENT.version()) || version.equals("1"))
                        .serverAcceptedVersions(version -> true)
                        .networkProtocolVersion(() -> "1");
                }
            }
        """)
        java("CuiChannel", """
            package com.example;

            import java.nio.charset.StandardCharsets;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerPlayer;
            import net.neoforged.neoforge.network.NetworkEvent;
            import net.neoforged.neoforge.network.event.EventNetworkChannel;

            public final class CuiChannel {
                public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("example", "cui");
                private static final EventNetworkChannel CHANNEL = Channels.builder(ID).eventNetworkChannel();

                public static void init() {
                    CHANNEL.addListener(CuiChannel::onPacket);
                }

                public static void onPacket(NetworkEvent.ClientCustomPayloadEvent event) {
                    ServerPlayer player = event.getSource().get().getSender();
                    FriendlyByteBuf buffer = event.getPayload();
                    String text = buffer.toString(StandardCharsets.UTF_8);
                    event.getSource().get().setPacketHandled(true);
                }
            }
        """)
        java("CuiSender", """
            package com.example;

            import io.netty.buffer.Unpooled;
            import java.nio.charset.StandardCharsets;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
            import net.minecraft.server.level.ServerPlayer;

            public final class CuiSender {
                public static void send(ServerPlayer player, String text) {
                    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
                    player.connection.send(new ClientboundCustomPayloadPacket(CuiChannel.ID, buffer));
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val channel = read("CuiChannel")
        val sender = read("CuiSender")

        assertTrue(channel.contains("public record ChannelPayload(byte[] data) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload"), channel)
        assertTrue(channel.contains("new Type<>(CuiChannel.ID)") || channel.contains("new Type<>(ID)"), channel)
        // ABSENT peers were accepted: the payload stays optional
        assertTrue(channel.contains("event.registrar(\"1\").optional().playBidirectional(ChannelPayload.TYPE"), channel)
        assertTrue(channel.contains("public static void onPacket(ChannelPayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context)"), channel)
        assertTrue(channel.contains("ServerPlayer player = ((net.minecraft.server.level.ServerPlayer) context.player());"), channel)
        assertTrue(channel.contains("FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));"), channel)
        assertFalse(channel.contains("setPacketHandled"), channel)
        assertFalse(channel.contains("EventNetworkChannel"), channel)
        assertTrue(sender.contains("new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new CuiChannel.ChannelPayload(io.netty.buffer.ByteBufUtil.getBytes(buffer)))"), sender)
        assertFalse(srcDir.resolve("Channels.java").toFile().exists(), "the channel builder helper had no other caller")
    }

    @Test
    fun `an event-blind tick listener that only works on the server listens to server ticks`() {
        mod()
        java("Ticker", """
            package com.example;

            import net.minecraft.server.MinecraftServer;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.event.TickEvent;
            import net.neoforged.neoforge.server.ServerLifecycleHooks;

            public class Ticker {
                @SubscribeEvent
                public void tick(TickEvent event) {
                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server != null) {
                        server.getPlayerCount();
                    }
                }

                @SubscribeEvent
                public void phased(TickEvent event) {
                    if (event.phase == TickEvent.Phase.END) {
                        ServerLifecycleHooks.getCurrentServer();
                    }
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val ticker = read("Ticker")

        assertTrue(ticker.contains("public void tick(ServerTickEvent.Post event)"), ticker)
        assertTrue(ticker.contains("import net.neoforged.neoforge.event.tick.ServerTickEvent;"), ticker)
        // a listener that reads its event keeps its declared type for the phase rules
        assertFalse(ticker.contains("public void phased(ServerTickEvent.Post event)"), ticker)
    }

    @Test
    fun `frame time read through an inherited screen field comes from the timer`() {
        mod()
        java("ClockScreen", """
            package com.example;

            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

            public abstract class ClockScreen extends AbstractContainerScreen<ClockMenu> {
                protected ClockScreen() {
                    super(null, null, null);
                }

                @Override
                public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                    partialTicks = minecraft.getFrameTime();
                    float delta = this.minecraft.getDeltaFrameTime();
                }
            }
        """)
        java("Animation", """
            package com.example;

            public class Animation {
                public float getDeltaFrameTime() {
                    return 0.5F;
                }

                public float step(Animation other) {
                    return other.getDeltaFrameTime();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val screen = read("ClockScreen")
        val animation = read("Animation")

        assertTrue(screen.contains("partialTicks = minecraft.getTimer().getGameTimeDeltaPartialTick(false);"), screen)
        // the project declares getDeltaFrameTime itself: its calls are left alone
        assertTrue(screen.contains("this.minecraft.getDeltaFrameTime()"), screen)
        assertTrue(animation.contains("return other.getDeltaFrameTime();"), animation)
    }

    @Test
    fun `chunk future continuations read chunk results`() {
        mod()
        java("Chunks", """
            package com.example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.chunk.ChunkAccess;
            import net.minecraft.world.level.chunk.status.ChunkStatus;

            public class Chunks {
                public CompletableFuture<ChunkAccess> load(ServerLevel level, int x, int z) {
                    return level.getChunkSource().getChunkFutureMainThread(x, z, ChunkStatus.FULL, true)
                        .thenApply(either -> either.left().orElse(null));
                }

                public CompletableFuture<Boolean> loaded(ServerLevel level, int x, int z) {
                    return level.getChunkSource().getChunkFutureMainThread(x, z, ChunkStatus.FULL, true)
                        .thenApply(result -> result.left().isPresent());
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val chunks = read("Chunks")

        assertTrue(chunks.contains(".thenApply(either -> either.orElse(null));"), chunks)
        assertTrue(chunks.contains(".thenApply(result -> result.isSuccess());"), chunks)
    }

    @Test
    fun `data fixer builder overrides of removed build methods lose their override annotation`() {
        mod()
        java("Builder", """
            package com.example;

            import com.mojang.datafixers.DataFixer;
            import com.mojang.datafixers.DataFixerBuilder;

            public class Builder extends DataFixerBuilder {
                public Builder(int version) {
                    super(version);
                }

                @Override
                public DataFixer buildUnoptimized() {
                    return null;
                }

                @Override
                public String toString() {
                    return "builder";
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val builder = read("Builder")

        assertFalse(Regex("""@Override\s+public DataFixer buildUnoptimized""").containsMatchIn(builder), builder)
        assertTrue(builder.contains("public DataFixer buildUnoptimized() {"), builder)
        assertTrue(Regex("""@Override\s+public String toString""").containsMatchIn(builder), builder)
    }

    @Test
    fun `component json helpers whose callers cannot supply registries read the built-in ones`() {
        mod()
        java("Signs", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;

            public class Signs {
                private static String text(CompoundTag tag) {
                    return Component.Serializer.fromJson(tag.getString("Text")).getString();
                }

                public static CompoundTag convert(CompoundTag tag) {
                    tag.putString("Plain", text(tag));
                    return tag;
                }
            }
        """)
        java("Fixer", """
            package com.example;

            import java.util.function.UnaryOperator;
            import net.minecraft.nbt.CompoundTag;

            public class Fixer implements UnaryOperator<CompoundTag> {
                @Override
                public CompoundTag apply(CompoundTag tag) {
                    return Signs.convert(tag);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val signs = read("Signs")
        val fixer = read("Fixer")

        // the library hook calling convert has no provider to hand down: nothing is widened
        assertTrue(fixer.contains("return Signs.convert(tag);"), fixer)
        assertTrue(signs.contains("public static CompoundTag convert(CompoundTag tag) {"), signs)
        assertTrue(signs.contains("private static String text(CompoundTag tag) {"), signs)
        assertTrue(signs.contains("Component.Serializer.fromJson(tag.getString(\"Text\"), net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY))"), signs)
    }

    @Test
    fun `a reference to another overload does not strand a widened adapter`() {
        mod()
        java("Adapter", """
            package com.example;

            import java.util.List;
            import java.util.stream.Collectors;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            public final class Adapter {
                public static String adapt(Item item) {
                    return item.toString();
                }

                public static CompoundTag adapt(ItemStack stack) {
                    return stack.save(new CompoundTag());
                }

                public static List<String> names(List<Item> items) {
                    return items.stream().map(Adapter::adapt).collect(Collectors.toList());
                }
            }
        """)
        java("Holder", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.item.ItemStack;

            public class Holder {
                private final ServerPlayer player;

                public Holder(ServerPlayer player) {
                    this.player = player;
                }

                public CompoundTag held() {
                    ItemStack stack = this.player.getMainHandItem();
                    return Adapter.adapt(stack);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)
        val adapter = read("Adapter")
        val holder = read("Holder")

        assertTrue(adapter.contains("public static CompoundTag adapt(ItemStack stack, HolderLookup.Provider registries)"), adapter)
        assertTrue(adapter.contains("public static String adapt(Item item) {"), adapter)
        assertTrue(adapter.contains("return items.stream().map(Adapter::adapt).collect(Collectors.toList());"), adapter)
        assertTrue(holder.contains("public CompoundTag held()"), holder)
        assertFalse(holder.contains("held(HolderLookup.Provider"), holder)
        assertTrue(Regex("""Adapter\.adapt\(stack, (?:this\.)?player\.registryAccess\(\)\)""").containsMatchIn(holder), holder)
    }

    @Test
    fun `a final block use override with annotated parameters becomes useItemOn`() {
        java("BogeyBlock", """
            package com.example;

            import javax.annotation.Nonnull;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public class BogeyBlock extends Block {
                public BogeyBlock(Properties properties) {
                    super(properties);
                }

                @Override
                public final InteractionResult use(@Nonnull BlockState state, @Nonnull Level level, BlockPos pos, Player player, InteractionHand hand,
                                                   BlockHitResult hit) {
                    ItemStack stack = player.getItemInHand(hand);
                    return stack.isEmpty() ? InteractionResult.PASS : InteractionResult.SUCCESS;
                }
            }
        """)
        java("Dispatcher", """
            package com.example;

            public class Dispatcher {
                public String use(String a, String b, String c, String d, String e, String f) {
                    return a;
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val block = read("BogeyBlock")
        assertTrue(block.contains("protected final net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack itemstack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)"), block)
        assertTrue(block.contains("ItemStack stack = itemstack;"), block)
        assertFalse(block.contains("InteractionResult use("), block)
        // a six-parameter method named use on something other than a block keeps its name
        assertTrue(read("Dispatcher").contains("public String use(String a, String b, String c, String d, String e, String f)"), read("Dispatcher"))
    }

    @Test
    fun `entity lerpTo calls drop the removed teleport flag`() {
        java("Crate", """
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.level.Level;

            public abstract class Crate extends LivingEntity {
                protected Crate(EntityType<? extends LivingEntity> type, Level level) {
                    super(type, level);
                }

                void settle(LivingEntity other, int steps) {
                    lerpTo(getX(), getY(), getZ(), getYRot(), getXRot(), steps == 0 ? 3 : steps, true);
                    other.lerpTo(0, 0, 0, 0, 0, 0, false);
                }
            }
        """)
        java("Path", """
            package com.example;

            public class Path {
                void lerpTo(double a, double b, double c, float d, float e, int f, String g) {
                }

                void use() {
                    lerpTo(0, 0, 0, 0, 0, 0, "keep");
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val crate = read("Crate")
        assertTrue(crate.contains("lerpTo(getX(), getY(), getZ(), getYRot(), getXRot(), steps == 0 ? 3 : steps);"), crate)
        assertTrue(crate.contains("other.lerpTo(0, 0, 0, 0, 0, 0);"), crate)
        // a seven-parameter lerpTo of the project's own keeps its calls
        assertTrue(read("Path").contains("lerpTo(0, 0, 0, 0, 0, 0, \"keep\");"), read("Path"))
    }

    @Test
    fun `forge stack equality with a tag flag becomes ItemStack matches on stack receivers only`() {
        java("Inventory", """
            package com.example;

            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;

            public class Inventory {
                private final Player player = null;
                private final ItemStack held = ItemStack.EMPTY;

                boolean same(ItemStack stack) {
                    ItemStack inserted = stack.copy();
                    return inserted.equals(stack, true) && player.getMainHandItem()
                        .equals(held, false);
                }

                boolean other(Matcher matcher, String text) {
                    return matcher.equals(text, false);
                }
            }

            class Matcher {
                boolean equals(String text, boolean strict) {
                    return false;
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val inventory = read("Inventory")
        assertTrue(inventory.contains("inserted.getCount() == stack.getCount()"), inventory)
        assertTrue(inventory.contains("ItemStack.isSameItem(inserted, stack)"), inventory)
        assertTrue(Regex("""(?:net\.minecraft\.world\.item\.)?ItemStack\.matches\(player\.getMainHandItem\(\)\s*, held\)""").containsMatchIn(inventory), inventory)
        assertTrue(inventory.contains("return matcher.equals(text, false);"), inventory)
    }

    @Test
    fun `cancellation results forge never read are dropped and the read ones stay`() {
        java("ClickHandler", """
            package com.example;

            import net.minecraft.world.InteractionResult;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

            public class ClickHandler {
                @SubscribeEvent
                public static void onLeft(PlayerInteractEvent.LeftClickBlock event) {
                    event.setCancellationResult(InteractionResult.FAIL);
                    event.setCanceled(true);
                }

                @SubscribeEvent
                public static void onRight(PlayerInteractEvent.RightClickBlock event) {
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val handler = read("ClickHandler")
        assertFalse(handler.contains("setCancellationResult(InteractionResult.FAIL)"), handler)
        assertTrue(handler.contains("event.setCancellationResult(InteractionResult.SUCCESS);"), handler)
        assertTrue(Regex("""onLeft\(PlayerInteractEvent\.LeftClickBlock event\) \{\s*event\.setCanceled\(true\);""").containsMatchIn(handler), handler)
    }

    @Test
    fun `record codec getter witnesses name the record whose codec they build`() {
        java("RenderMode", """
            package com.example;

            import com.mojang.serialization.Codec;

            public interface RenderMode {
                Codec<? extends RenderMode> codec();
            }
        """)
        java("RenderModes", """
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.world.phys.Vec3;

            public class RenderModes {
                private static void register(String name, Codec<? extends RenderMode> codec) {
                }

                static {
                    register("toward_motion", TowardMotion.CODEC);
                    register("stuck_to_entity", StuckToEntity.CODEC);
                }

                public record TowardMotion(int spriteAngleOffset, float spin) implements RenderMode {
                    public static final Codec<TowardMotion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("sprite_angle_offset").forGetter(i -> i.spriteAngleOffset),
                        Codec.FLOAT.fieldOf("spin").forGetter(i -> i.spin)
                    ).apply(instance, TowardMotion::new));

                    @Override
                    public Codec<? extends RenderMode> codec() {
                        return CODEC;
                    }
                }

                public record StuckToEntity(Vec3 offset) implements RenderMode {
                    public static final Codec<StuckToEntity> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Vec3.CODEC.fieldOf("offset").forGetter(i -> i.offset)
                    ).apply(instance, StuckToEntity::new));

                    @Override
                    public Codec<? extends RenderMode> codec() {
                        return CODEC;
                    }
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val modes = read("RenderModes")
        assertTrue(modes.contains("forGetter((TowardMotion i) -> i.spin())"), modes)
        assertTrue(modes.contains("forGetter((StuckToEntity i) -> i.offset())"), modes)
        assertFalse(modes.contains("(TowardMotion i) -> i.offset"), modes)
    }

    @Test
    fun `forge stack equality on a stack field of another project class becomes ItemStack matches`() {
        java("Transported", """
            package com.example;

            import net.minecraft.world.item.ItemStack;

            public class Transported {
                public ItemStack stack = ItemStack.EMPTY;
            }
        """)
        java("Handler", """
            package com.example;

            import java.util.List;
            import net.minecraft.world.item.ItemStack;

            public class Handler {
                List<Transported> outputs;

                boolean unchanged(ItemStack before) {
                    return outputs.size() == 1 && outputs.get(0).stack.equals(before, false);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val handler = read("Handler")
        assertTrue(handler.contains("ItemStack.matches(outputs.get(0).stack, before)"), handler)
    }

    @Test
    fun `entity data serializers hand their write and read methods to the codec`() {
        java("SyncDataSerializer", """
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.syncher.EntityDataSerializer;

            public class SyncDataSerializer implements EntityDataSerializer<SyncData> {

                @Override
                public void write(FriendlyByteBuf buffer, SyncData data) {
                    buffer.writeInt(data.value);
                }

                @Override
                public SyncData read(FriendlyByteBuf buffer) {
                    return new SyncData(buffer.readInt());
                }

                @Override
                public SyncData copy(SyncData data) {
                    return new SyncData(data.value);
                }
            }

            class SyncData {
                final int value;

                SyncData(int value) {
                    this.value = value;
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val serializer = read("SyncDataSerializer")
        assertTrue(serializer.contains("public net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, SyncData> codec()"), serializer)
        assertTrue(serializer.contains("return net.minecraft.network.codec.StreamCodec.of(this::write, this::read);"), serializer)
        assertFalse(Regex("""@Override\s+public void write""").containsMatchIn(serializer), serializer)
        assertTrue(Regex("""@Override\s+public SyncData copy""").containsMatchIn(serializer), serializer)
    }

    @Test
    fun `seconds on fire overrides move to the overridable tick hook`() {
        java("Contraption", """
            package com.example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.level.Level;

            public abstract class Contraption extends Entity {
                protected Contraption(EntityType<?> type, Level level) {
                    super(type, level);
                }

                @Override
                public void setSecondsOnFire(int seconds) {
                    // contraptions never burn
                }
            }
        """)
        java("Torch", """
            package com.example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.level.Level;

            public abstract class Torch extends Entity {
                protected Torch(EntityType<?> type, Level level) {
                    super(type, level);
                }

                @Override
                public void setSecondsOnFire(int seconds) {
                    super.setSecondsOnFire(seconds * 2);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val contraption = read("Contraption")
        val torch = read("Torch")
        assertTrue(contraption.contains("public void igniteForTicks(int seconds) {"), contraption)
        assertTrue(torch.contains("public void igniteForTicks(int secondsTicks) {"), torch)
        assertTrue(torch.contains("int seconds = secondsTicks / 20;"), torch)
        assertTrue(torch.contains("super.igniteForTicks((seconds * 2) * 20);"), torch)
        assertFalse(torch.contains("setSecondsOnFire"), torch)
    }

    @Test
    fun `single input recipe types read slot zero of a container lookup`() {
        java("Fan", """
            package com.example;

            import net.minecraft.world.Container;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;

            public class Fan {
                boolean smokes(Container wrapper, Level level) {
                    return level.getRecipeManager().getRecipeFor(RecipeType.SMOKING, wrapper, level).isPresent();
                }

                boolean crafts(Container grid, Level level) {
                    return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level).isPresent();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val fan = read("Fan")
        assertTrue(fan.contains("getRecipeFor(RecipeType.SMOKING, new SingleRecipeInput(wrapper.getItem(0)), level)"), fan)
        assertFalse(fan.contains("new SingleRecipeInput(grid"), fan)
    }

    @Test
    fun `removed registry object and resource location factories use their 1_21 replacements`() {
        java("Sounds", """
            package com.example;

            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.sounds.SoundEvent;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class Sounds {
                private final Map<String, ResourceLocation> cache = new HashMap<>();
                DeferredHolder<SoundEvent, SoundEvent> event;

                void prepare(ResourceLocation id) {
                    event = RegistryObject.create(id, BuiltInRegistries.SOUND_EVENT);
                }

                ResourceLocation location(String name) {
                    return cache.computeIfAbsent(name, ResourceLocation::new);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val sounds = read("Sounds")
        assertTrue(sounds.contains("event = DeferredHolder.create(Registries.SOUND_EVENT, id);"), sounds)
        assertTrue(sounds.contains("import net.minecraft.core.registries.Registries;"), sounds)
        assertTrue(sounds.contains("cache.computeIfAbsent(name, ResourceLocation::parse)"), sounds)
    }

    @Test
    fun `codec helpers follow their dfu 7 homes and plain and map codecs meet`() {
        java("Codecs", """
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.DataResult;
            import com.mojang.serialization.MapCodec;
            import java.util.List;
            import net.minecraft.util.ExtraCodecs;
            import net.minecraft.world.item.ItemStack;

            public class Codecs {
                public static final Codec<Integer> POSITIVE = ExtraCodecs.validate(Codec.INT, i -> i > 0 ? DataResult.success(i) : DataResult.error(() -> "negative"));
                public static final Codec<List<ItemStack>> STACKS = ItemStack.CODEC.codec().listOf();
                public static final Codec<Integer> LAZY = ExtraCodecs.lazyInitializedCodec(() -> Codec.INT);
                public static final MapCodec<Codecs> CODEC = MapCodec.unit(new Codecs());

                public Codec<Codecs> getCodec() {
                    return CODEC;
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val codecs = read("Codecs")
        assertTrue(codecs.contains("Codec.INT.validate(i -> i > 0"), codecs)
        assertTrue(codecs.contains("ItemStack.CODEC.listOf()"), codecs)
        assertTrue(codecs.contains("com.mojang.serialization.Codec.lazyInitialized(() -> Codec.INT)"), codecs)
        assertTrue(codecs.contains("return CODEC.codec();"), codecs)
    }

    @Test
    fun `registry delegates of registered values are their reference holders`() {
        java("PlaceBlock", """
            package com.example;

            import net.minecraft.core.Holder;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.world.level.block.Block;

            public record PlaceBlock(Holder<Block> block) {
                public PlaceBlock(Block block) {
                    this(BuiltInRegistries.BLOCK.getDelegateOrThrow(block));
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val place = read("PlaceBlock")
        assertTrue(place.contains("this(BuiltInRegistries.BLOCK.getHolderOrThrow(BuiltInRegistries.BLOCK.getResourceKey(block).orElseThrow()));"), place)
    }

    @Test
    fun `forge tag constants follow their neoforge names`() {
        java("Recipes", """
            package com.example;

            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.Tags;

            public class Recipes {
                Ingredient sand = Ingredient.of(Tags.Items.SAND_COLORLESS);
                Ingredient obsidian = Ingredient.of(Tags.Items.OBSIDIAN);
                Ingredient ingots = Ingredient.of(Tags.Items.INGOTS_IRON);
                String doc = "Tags.Items.OBSIDIAN";
            }
        """)
        java("Strings", """
            package com.example;

            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.Tags.Items;

            public class Strings {
                Ingredient string = Ingredient.of(Items.STRING);
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val recipes = read("Recipes")
        assertTrue(recipes.contains("Ingredient.of(Tags.Items.SANDS_COLORLESS)"), recipes)
        assertTrue(recipes.contains("Ingredient.of(Tags.Items.OBSIDIANS)"), recipes)
        assertTrue(recipes.contains("Ingredient.of(Tags.Items.INGOTS_IRON)"), recipes)
        assertTrue(recipes.contains("\"Tags.Items.OBSIDIAN\""), recipes)
        assertTrue(read("Strings").contains("Ingredient.of(Items.STRINGS)"), read("Strings"))
    }

    @Test
    fun `jukebox discs and single item containers follow their 1_21 api`() {
        java("JukeboxPoint", """
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.RecordItem;
            import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

            public class JukeboxPoint {
                boolean accepts(ItemStack stack, JukeboxBlockEntity jukebox) {
                    return stack.getItem() instanceof RecordItem && jukebox.getFirstItem().isEmpty();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val point = read("JukeboxPoint")
        assertTrue(point.contains("stack.get(DataComponents.JUKEBOX_PLAYABLE) != null && jukebox.getTheItem().isEmpty()"), point)
        assertFalse(point.contains("import net.minecraft.world.item.RecordItem;"), point)
    }

    @Test
    fun `attribute modifier lookups pass the modifier id`() {
        java("Grip", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;

            public class Grip {
                public static final AttributeModifier RANGE = new AttributeModifier(ResourceLocation.parse("example:range"), 3, AttributeModifier.Operation.ADD_VALUE);
            }
        """)
        java("PoleHelper", """
            package com.example;

            import net.minecraft.world.entity.ai.attributes.AttributeInstance;

            public class PoleHelper {
                boolean extended(AttributeInstance reach) {
                    return reach != null && reach.hasModifier(Grip.RANGE);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        assertTrue(read("PoleHelper").contains("reach.hasModifier(Grip.RANGE.id())"), read("PoleHelper"))
    }

    @Test
    fun `tooltip lines for a client's player take that client's level`() {
        java("Overlay", """
            package com.example;

            import java.util.List;
            import net.minecraft.client.Minecraft;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;

            public class Overlay {
                List<Component> lines(ItemStack result) {
                    Minecraft mc = Minecraft.getInstance();
                    return result.getTooltipLines(mc.player, TooltipFlag.NORMAL);
                }

                List<Component> other(ItemStack result, Holder holder) {
                    return result.getTooltipLines(holder.player, TooltipFlag.NORMAL);
                }

                static class Holder {
                    net.minecraft.world.entity.player.Player player;
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val overlay = read("Overlay")
        assertTrue(overlay.contains("result.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)"), overlay)
        assertTrue(overlay.contains("result.getTooltipLines(holder.player, TooltipFlag.NORMAL)"), overlay)
    }

    @Test
    fun `a tooltip reading a block state looks it up through the context's level`() {
        java("ZapperItem", """
            package com.example;

            import java.util.List;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.NbtUtils;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;
            import net.minecraft.world.item.component.CustomData;

            public class ZapperItem extends Item {
                public ZapperItem(Properties properties) {
                    super(properties);
                }

                @Override
                public void appendHoverText(ItemStack stack, Item.TooltipContext worldIn, List<Component> tooltip, TooltipFlag flagIn) {
                    CompoundTag used = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                    tooltip.add(NbtUtils.readBlockState(worldIn.holderLookup(Registries.BLOCK), used.getCompound("BlockUsed")).getBlock().getName());
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val item = read("ZapperItem")
        assertTrue(item.contains("Level tooltipLevel = worldIn != null ? worldIn.level() : null;"), item)
        assertTrue(item.contains("NbtUtils.readBlockState(tooltipLevel.holderLookup(Registries.BLOCK)"), item)
    }

    @Test
    fun `a static equipment slot lookup keeps Forge's stack and equipable order`() {
        mod()
        java("Attributes", """
            package com.example;

            import java.util.function.Predicate;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;

            public class Attributes {
                static final Predicate<ItemStack> EQUIPABLE = s -> LivingEntity.getEquipmentSlotForItem(s)
                    .getType() != EquipmentSlot.Type.HAND;

                EquipmentSlot slotOf(LivingEntity entity, ItemStack stack) {
                    return entity.getEquipmentSlotForItem(stack);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val attributes = read("Attributes")
        assertTrue(attributes.contains("s -> com.modporter.generated.example.compat.LegacyEquipmentSlots.forItem(s)"), attributes)
        assertTrue(attributes.contains("return entity.getEquipmentSlotForItem(stack);"), attributes)
        val helper = tempDir.resolve("src/main/java/com/modporter/generated/example/compat/LegacyEquipmentSlots.java").readText()
        assertTrue(helper.contains("EquipmentSlot slot = stack.getEquipmentSlot();"), helper)
        assertTrue(helper.contains("return equipable != null ? equipable.getEquipmentSlot() : EquipmentSlot.MAINHAND;"), helper)
    }

    @Test
    fun `an advancement helper hands vanilla criteria on as Criterion`() {
        java("Advancements", """
            package com.example;

            import net.minecraft.advancements.Advancement;
            import net.minecraft.advancements.CriterionTriggerInstance;
            import net.minecraft.advancements.critereon.InventoryChangeTrigger;
            import net.minecraft.advancements.critereon.ItemUsedOnLocationTrigger;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.ItemLike;

            public class Advancements {
                private final Advancement.Builder builder = Advancement.Builder.advancement();
                private int keyIndex;

                Advancements whenBlockPlaced(Block block) {
                    return externalTrigger(ItemUsedOnLocationTrigger.TriggerInstance.placedBlock(block));
                }

                Advancements whenItemCollected(ItemLike item) {
                    return externalTrigger(InventoryChangeTrigger.TriggerInstance
                        .hasItems(item));
                }

                Advancements externalTrigger(CriterionTriggerInstance trigger) {
                    builder.addCriterion(String.valueOf(keyIndex), trigger);
                    keyIndex++;
                    return this;
                }

                void keep(CriterionTriggerInstance trigger) {
                    System.out.println(trigger);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val advancements = read("Advancements")
        assertTrue(advancements.contains("Advancements externalTrigger(net.minecraft.advancements.Criterion<?> trigger)"), advancements)
        assertTrue(advancements.contains("void keep(CriterionTriggerInstance trigger)"), advancements)
    }

    @Test
    fun `writes through a copied custom data tag are set back on the stack`() {
        java("PotionFluid", """
            package com.example;

            import java.util.Collection;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.fluids.FluidStack;

            public class PotionFluid {
                public static FluidStack addPotion(FluidStack fs, String potion) {
                    fs.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
                        .putString("Potion", potion);
                    return fs;
                }

                public static void bottle(FluidStack fluid, Enum<?> type) {
                    if (type != null)
                        NBTHelper.writeEnum(fluid.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag(), "Bottle", type);
                }

                public static FluidStack appendEffects(FluidStack fs, Collection<MobEffectInstance> effects) {
                    if (effects.isEmpty())
                        return fs;
                    CompoundTag compoundnbt = fs.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    ListTag listnbt = compoundnbt.getList("CustomPotionEffects", 9);
                    for (MobEffectInstance effect : effects)
                        listnbt.add(effect.save());
                    compoundnbt.put("CustomPotionEffects", listnbt);
                    return fs;
                }

                public static int read(ItemStack stack) {
                    CompoundTag tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    return tag.getInt("Count");
                }

                public static void early(ItemStack stack, boolean skip) {
                    CompoundTag tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    if (skip)
                        return;
                    tag.putInt("Count", 1);
                }

                static class NBTHelper {
                    static void writeEnum(CompoundTag tag, String key, Enum<?> value) {
                        tag.putString(key, value.name());
                    }
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val fluid = read("PotionFluid")
        val setBack = "set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of("
        assertTrue(Regex("""modporterCustomData\d*\s*\.putString\("Potion", potion\);\s*fs\.""" + Regex.escape(setBack) + """modporterCustomData\d*\)\);""").containsMatchIn(fluid), fluid)
        assertTrue(Regex("""if \(type != null\)\s*\{\s*net\.minecraft\.nbt\.CompoundTag (modporterCustomData\d*) = fluid\.getOrDefault\([^;]*\.copyTag\(\);\s*NBTHelper\.writeEnum\(\1, "Bottle", type\);\s*fluid\.""" + Regex.escape(setBack) + """\1\)\);\s*\}""").containsMatchIn(fluid), fluid)
        assertTrue(fluid.contains("compoundnbt.put(\"CustomPotionEffects\", listnbt);\n        fs.${setBack}compoundnbt));\n        return fs;"), fluid)
        assertFalse(fluid.contains("stack.${setBack}tag));"), fluid)
    }

    @Test
    fun `an entity setting itself on fire ignites for those seconds`() {
        java("Projectile", """
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.projectile.ThrowableProjectile;
            import net.minecraft.world.level.Level;

            public abstract class Projectile extends ThrowableProjectile {
                protected Projectile(EntityType<? extends ThrowableProjectile> type, Level level) {
                    super(type, level);
                }

                void burn(boolean fire) {
                    if (fire)
                        setSecondsOnFire(100);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        assertTrue(read("Projectile").contains("igniteForSeconds(100);"), read("Projectile"))
    }

    @Test
    fun `entity dimensions built with a fixed flag use the fixed and scalable factories`() {
        java("Box", """
            package com.example;

            import net.minecraft.world.entity.EntityDimensions;

            public class Box {
                EntityDimensions of(float w, float h, boolean rigid) {
                    EntityDimensions a = new EntityDimensions(w, h, true);
                    EntityDimensions b = new EntityDimensions(Math.max(w, 1f), h, false);
                    return rigid ? a : new EntityDimensions(w, h, rigid);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val box = read("Box")
        assertTrue(box.contains("EntityDimensions a = net.minecraft.world.entity.EntityDimensions.fixed(w, h);"), box)
        assertTrue(box.contains("EntityDimensions b = net.minecraft.world.entity.EntityDimensions.scalable(Math.max(w, 1f), h);"), box)
        assertTrue(box.contains("(rigid ? net.minecraft.world.entity.EntityDimensions.fixed(w, h) : net.minecraft.world.entity.EntityDimensions.scalable(w, h))"), box)
    }

    @Test
    fun `event results held in locals become tri states`() {
        java("Deployer", """
            package com.example;

            import net.minecraftforge.eventbus.api.Event;
            import net.minecraftforge.event.entity.player.PlayerInteractEvent;

            public class Deployer {
                boolean use(PlayerInteractEvent.RightClickBlock event, boolean fire) {
                    Event.Result useBlock = Event.Result.DEFAULT;
                    if (fire)
                        useBlock = event.getUseBlock();
                    return useBlock != Event.Result.DENY;
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val deployer = read("Deployer")
        assertTrue(deployer.contains("TriState useBlock = TriState.DEFAULT;"), deployer)
        assertTrue(deployer.contains("return useBlock != TriState.FALSE;"), deployer)
    }

    @Test
    fun `particle data writing registry content keeps the legacy contract on the registry buffer`() {
        mod()
        java("FluidParticleData", """
            package com.example;

            import com.mojang.brigadier.StringReader;
            import net.minecraft.core.particles.ParticleOptions;
            import net.minecraft.core.particles.ParticleType;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.level.material.Fluids;
            import net.neoforged.neoforge.fluids.FluidStack;

            public class FluidParticleData implements ParticleOptions {
                private ParticleType<FluidParticleData> type;
                private FluidStack fluid;

                public FluidParticleData(ParticleType<FluidParticleData> type, FluidStack fluid) {
                    this.type = type;
                    this.fluid = fluid;
                }

                public static final ParticleOptions.Deserializer<FluidParticleData> DESERIALIZER = new ParticleOptions.Deserializer<>() {
                    public FluidParticleData fromCommand(ParticleType<FluidParticleData> type, StringReader reader) {
                        return new FluidParticleData(type, new FluidStack(Fluids.WATER, 1));
                    }

                    public FluidParticleData fromNetwork(ParticleType<FluidParticleData> type, FriendlyByteBuf buffer) {
                        return new FluidParticleData(type, buffer.readFluidStack());
                    }
                };

                @Override
                public ParticleType<?> getType() {
                    return type;
                }

                @Override
                public void writeToNetwork(FriendlyByteBuf buffer) {
                    buffer.writeFluidStack(fluid);
                }

                @Override
                public String writeToString() {
                    return "fluid";
                }
            }
        """)
        java("CubeParticleData", """
            package com.example;

            import net.minecraft.core.particles.ParticleOptions;
            import net.minecraft.core.particles.ParticleType;
            import net.minecraft.network.FriendlyByteBuf;

            public class CubeParticleData implements ParticleOptions {
                @Override
                public ParticleType<?> getType() {
                    return null;
                }

                @Override
                public void writeToNetwork(FriendlyByteBuf buffer) {
                    buffer.writeInt(1);
                }

                @Override
                public String writeToString() {
                    return "cube";
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val compat = tempDir.resolve("src/main/java/com/modporter/generated/example/compat")
        assertTrue(compat.resolve("LegacyParticleOptions.java").readText().contains("void writeToNetwork(RegistryFriendlyByteBuf buffer);"), read("FluidParticleData") + compat.resolve("LegacyParticleOptions.java").readText())
        assertTrue(compat.resolve("ParticleDeserializer.java").readText().contains("T fromNetwork(ParticleType<T> type, RegistryFriendlyByteBuf buffer);"))
        assertTrue(compat.resolve("LegacyParticleTypes.java").readText().contains("StreamCodec<RegistryFriendlyByteBuf, T> streamCodec("))
        val fluid = read("FluidParticleData")
        assertTrue(Regex("""void writeToNetwork\((?:net\.minecraft\.network\.)?RegistryFriendlyByteBuf buffer\)""").containsMatchIn(fluid), fluid)
        assertTrue(Regex("""fromNetwork\(ParticleType<FluidParticleData> type, (?:net\.minecraft\.network\.)?RegistryFriendlyByteBuf buffer\)""").containsMatchIn(fluid), fluid)
        val cube = read("CubeParticleData")
        assertTrue(cube.contains("public void writeToNetwork(net.minecraft.network.RegistryFriendlyByteBuf buffer)"), cube)
    }

    @Test
    fun `nbt helpers reached through interface hooks and client screens take the provider`() {
        java("Entry", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class Entry {
                public ItemStack icon = ItemStack.EMPTY;

                public CompoundTag writeNBT() {
                    CompoundTag nbt = new CompoundTag();
                    nbt.put("Icon", icon.serializeNBT());
                    return nbt;
                }
            }
        """)
        java("Cloneable", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;

            public interface Cloneable {
                boolean writeToClipboard(CompoundTag tag);
            }
        """)
        java("Behaviour", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;

            public class Behaviour implements Cloneable {
                Entry entry = new Entry();

                @Override
                public boolean writeToClipboard(CompoundTag tag) {
                    tag.put("Entry", entry.writeNBT());
                    return true;
                }
            }
        """)
        java("Holder", """
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            public class Holder {
                void copy(Cloneable cloneable, HolderLookup.Provider registries) {
                    cloneable.writeToClipboard(new CompoundTag());
                }
            }
        """)
        java("EntryScreen", """
            package com.example;

            import net.createmod.catnip.gui.AbstractSimiScreen;
            import net.minecraft.network.chat.Component;

            public class EntryScreen extends AbstractSimiScreen {
                Entry entry = new Entry();

                protected EntryScreen() {
                    super(Component.empty());
                }

                private void send() {
                    System.out.println(entry.writeNBT());
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val entry = read("Entry")
        assertTrue(entry.contains("public CompoundTag writeNBT(HolderLookup.Provider registries)"), entry)
        assertTrue(read("Cloneable").contains("boolean writeToClipboard(CompoundTag tag, HolderLookup.Provider registries);"), read("Cloneable"))
        assertTrue(read("Behaviour").contains("entry.writeNBT(registries)"), read("Behaviour"))
        assertTrue(read("EntryScreen").contains("entry.writeNBT(getMinecraft().level.registryAccess())"), read("EntryScreen"))
    }

    @Test
    fun `buffer method references made ambiguous by static twins become lambdas`() {
        java("RidingPacket", """
            package com.example;

            import java.util.List;
            import java.util.UUID;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.RegistryFriendlyByteBuf;

            public class RidingPacket {
                List<UUID> uuids;

                RidingPacket(RegistryFriendlyByteBuf buffer) {
                    this.uuids = buffer.readList(FriendlyByteBuf::readUUID);
                }

                void write(RegistryFriendlyByteBuf buffer) {
                    buffer.writeCollection(uuids, FriendlyByteBuf::writeUUID);
                    buffer.writeCollection(uuids, (b, id) -> b.writeUUID(id));
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val packet = read("RidingPacket")
        assertTrue(packet.contains("buffer.readList(modporterBuffer -> modporterBuffer.readUUID())"), packet)
        assertTrue(packet.contains("buffer.writeCollection(uuids, (modporterBuffer, modporterValue1) -> modporterBuffer.writeUUID(modporterValue1))"), packet)
    }

    @Test
    fun `a block use result compared with a held result keeps the interaction result constant`() {
        java("Burner", """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.InteractionResultHolder;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public class Burner extends Block {
                public Burner(Properties properties) {
                    super(properties);
                }

                @Override
                public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
                    ItemStack heldItem = player.getItemInHand(hand);
                    InteractionResultHolder<ItemStack> res = InteractionResultHolder.success(heldItem);
                    return res.getResult() == InteractionResult.SUCCESS ? InteractionResult.SUCCESS : InteractionResult.PASS;
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val burner = read("Burner")
        assertTrue(burner.contains("return res.getResult() == InteractionResult.SUCCESS ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;"), burner)
    }

    @Test
    fun `a client helper reading stacks through a static nbt reader takes the provider without naming tags`() {
        java("Entry", """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class Entry {
                public ItemStack icon = ItemStack.EMPTY;

                public static Entry readNBT(CompoundTag tag) {
                    Entry entry = new Entry();
                    entry.icon = ItemStack.of(tag.getCompound("Icon"));
                    return entry;
                }

                public static List<Entry> readAll(ItemStack clipboard) {
                    List<Entry> entries = new ArrayList<>();
                    entries.add(readNBT(clipboard.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()));
                    return entries;
                }
            }
        """)
        java("AddressHelper", """
            package com.example;

            import java.util.List;
            import net.minecraft.client.Minecraft;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;

            public class AddressHelper {
                public static int suggestions() {
                    Minecraft mc = Minecraft.getInstance();
                    Player player = mc.player;
                    return count(player.getMainHandItem());
                }

                private static int count(ItemStack item) {
                    List<Entry> entries = Entry.readAll(item);
                    return entries.size();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val entry = read("Entry")
        assertTrue(entry.contains("public static List<Entry> readAll(ItemStack clipboard, HolderLookup.Provider registries)"), entry)
        val helper = read("AddressHelper")
        assertTrue(helper.contains("private static int count(ItemStack item, HolderLookup.Provider registries)"), helper)
        assertTrue(helper.contains("Entry.readAll(item, registries)"), helper)
        // nothing in the project calls the public entry point, so it takes the provider as well
        assertTrue(helper.contains("count(player.getMainHandItem(), registries)"), helper)
    }

    @Test
    fun `methods a framework calls by reflection keep their parameters when nbt helpers take the provider`() {
        java("Schedule", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class Schedule {
                ItemStack icon = ItemStack.EMPTY;

                public CompoundTag write() {
                    CompoundTag tag = new CompoundTag();
                    tag.put("Icon", icon.serializeNBT());
                    return tag;
                }
            }
        """)
        java("StationPeripheral", """
            package com.example;

            import dan200.computercraft.api.lua.LuaFunction;
            import net.minecraft.nbt.CompoundTag;

            public class StationPeripheral {
                Schedule schedule = new Schedule();

                @LuaFunction
                public final String getSchedule() {
                    CompoundTag tag = schedule.write();
                    return tag.toString();
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val peripheral = read("StationPeripheral")
        assertTrue(peripheral.contains("public final String getSchedule() {"), peripheral)
    }

    @Test
    fun `annotated nbt helpers use the registry provider from callers declaring throws`() {
        java("TaggedItem", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public class TaggedItem {
                @Deprecated(since = "old,format")
                public static CompoundTag write(ItemStack stack) {
                    return stack.serializeNBT();
                }
            }
        """)
        java("ItemStore", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public class ItemStore {
                public static CompoundTag save(Level level, ItemStack stack) throws IllegalArgumentException {
                    return TaggedItem.write(stack);
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val helper = read("TaggedItem")
        assertTrue(helper.contains("@Deprecated(since = \"old,format\")"), helper)
        assertTrue(helper.contains("write(ItemStack stack, HolderLookup.Provider registries)"), helper)
        assertTrue(helper.contains("stack.saveOptional(registries)"), helper)
        val store = read("ItemStore")
        assertTrue(store.contains("save(Level level, ItemStack stack) throws IllegalArgumentException"), store)
        assertTrue(store.contains("TaggedItem.write(stack, level.registryAccess())"), store)
    }

    @Test
    fun `buffers handed to registry writers through fields and list elements become registry buffers`() {
        java("Output", """
            package com.example;

            import net.minecraft.network.RegistryFriendlyByteBuf;

            public class Output {
                public void write(RegistryFriendlyByteBuf buf) {
                }
            }
        """)
        java("AssemblyRecipe", """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;

            public class AssemblyRecipe {
                protected List<Output> resultPool = new ArrayList<>();
                protected Output transitionalItem;
                protected List<Plain> notes = new ArrayList<>();
            }
        """)
        java("Plain", """
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;

            public class Plain {
                public void write(FriendlyByteBuf buf) {
                }
            }
        """)
        java("AssemblySerializer", """
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;

            public class AssemblySerializer {
                protected void writeToBuffer(FriendlyByteBuf buffer, AssemblyRecipe recipe) {
                    recipe.resultPool.forEach(sr -> sr.write(buffer));
                    recipe.transitionalItem.write(buffer);
                }

                protected void writeName(FriendlyByteBuf buffer, AssemblyRecipe recipe) {
                    buffer.writeUtf(recipe.toString());
                }

                protected void writeNotes(FriendlyByteBuf buffer, AssemblyRecipe recipe) {
                    recipe.notes.forEach(note -> note.write(buffer));
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val serializer = read("AssemblySerializer")
        assertTrue(serializer.contains("protected void writeToBuffer(RegistryFriendlyByteBuf buffer, AssemblyRecipe recipe)"), serializer)
        assertTrue(serializer.contains("protected void writeName(FriendlyByteBuf buffer, AssemblyRecipe recipe)"), serializer)
        assertTrue(serializer.contains("protected void writeNotes(FriendlyByteBuf buffer, AssemblyRecipe recipe)"), serializer)
    }
}
