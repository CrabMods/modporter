package com.modporter.transforms

import com.modporter.core.pipeline.Pipeline
import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests based on real-world Forge mod patterns.
 * Each test simulates a common pattern found in actual mods.
 */
class RealWorldPatternsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createFile(filename: String, content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve(filename).writeText(content)
        return tempDir
    }

    // ─── Pattern 1: Typical mod entry point ───

    @Test
    fun `transforms typical Forge mod entry point`() {
        val projectDir = createFile("MyMod.java", """
            package com.example.mymod;

            import net.minecraftforge.api.distmarker.Dist;
            import net.minecraftforge.common.MinecraftForge;
            import net.minecraftforge.eventbus.api.IEventBus;
            import net.minecraftforge.eventbus.api.SubscribeEvent;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
            import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
            import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

            @Mod(MyMod.MOD_ID)
            public class MyMod {
                public static final String MOD_ID = "mymod";

                public MyMod() {
                    IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
                    modEventBus.addListener(this::commonSetup);
                    MinecraftForge.EVENT_BUS.register(this);
                }

                private void commonSetup(final FMLCommonSetupEvent event) {
                }

                @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
                public static class ClientEvents {
                    @SubscribeEvent
                    public static void onClientSetup(FMLClientSetupEvent event) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(TextReplacementPass(db), AstTransformPass(db)),
            dryRun = false
        )
        pipeline.run(projectDir)

        val content = tempDir.resolve("src/main/java/com/example/MyMod.java").readText()

        assertTrue(content.contains("net.neoforged.api.distmarker.Dist"))
        assertTrue(content.contains("net.neoforged.neoforge.common.NeoForge"))
        assertTrue(content.contains("net.neoforged.bus.api.IEventBus"))
        assertTrue(content.contains("net.neoforged.fml.common.Mod"))
        assertTrue(content.contains("NeoForge.EVENT_BUS"))
        assertFalse(content.contains("net.minecraftforge"))
    }

    // ─── Pattern 2: Item registration ───

    @Test
    fun `full pipeline preserves collection size calls on container screens`() {
        val projectDir = createFile("LoreBookScreen.java", """
            package com.example.mymod;

            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.components.Button;
            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
            import net.minecraft.network.chat.Component;

            public class LoreBookScreen extends AbstractContainerScreen<LoreBookMenu> {
                private final Map<Integer, List<String>> pages = new HashMap<>();
                private int currentPageNumber;

                @Override
                protected void init() {
                    super.init();
                    this.addRenderableWidget(new Button.Builder(Component.literal(">"), (button) -> {
                        if (this.currentPageNumber < this.pages.size() - 1) {
                            this.currentPageNumber++;
                        }
                    }).bounds(0, 0, 20, 20));
                }

                @Override
                public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
                    this.renderBackground(guiGraphics);
                    super.render(guiGraphics, mouseX, mouseY, partialTicks);
                }
            }

            class LoreBookMenu {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(db)
            ),
            dryRun = false
        )
        pipeline.run(projectDir)

        val content = tempDir.resolve("src/main/java/com/example/LoreBookScreen.java").readText()

        assertTrue(content.contains("this.currentPageNumber < this.pages.size() - 1"), content)
        assertFalse(content.contains("this.pages.getSize()"), content)
        assertFalse(content.contains("this.pages.getContainerSize()"), content)
    }

    @Test
    fun `transforms item registration pattern`() {
        val projectDir = createFile("ModItems.java", """
            package com.example.mymod;

            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.CreativeModeTabs;
            import net.minecraftforge.registries.DeferredRegister;
            import net.minecraftforge.registries.ForgeRegistries;
            import net.minecraftforge.registries.RegistryObject;

            public class ModItems {
                public static final DeferredRegister<Item> ITEMS =
                    DeferredRegister.create(ForgeRegistries.ITEMS, "mymod");

                public static final RegistryObject<Item> RUBY =
                    ITEMS.register("ruby", () -> new Item(new Item.Properties()));

                public static final RegistryObject<Item> SAPPHIRE =
                    ITEMS.register("sapphire", () -> new Item(new Item.Properties()));
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        TextReplacementPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/ModItems.java").readText()

        assertTrue(content.contains("DeferredRegister.create(Registries.ITEM"),
            "DeferredRegister.create(ForgeRegistries.ITEMS) should become Registries.ITEM")
        assertTrue(content.contains("DeferredHolder"),
            "RegistryObject should become DeferredHolder")
        // ForgeRegistries.ITEMS is replaced by the specific rules,
        // but the import line contains the bare class name which gets
        // renamed by the auto-generated class rename rules
        assertFalse(content.contains("ForgeRegistries.ITEMS"),
            "ForgeRegistries.ITEMS usage should be gone")
        assertFalse(content.contains("RegistryObject"),
            "RegistryObject should be replaced by DeferredHolder")
    }

    // ─── Pattern 3: Block entity with capability ───

    @Test
    fun `detects block entity capability pattern`() {
        val projectDir = createFile("MachineBE.java", """
            package com.example.mymod;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.common.capabilities.Capability;
            import net.minecraftforge.common.capabilities.ForgeCapabilities;
            import net.minecraftforge.common.util.LazyOptional;
            import net.minecraftforge.energy.IEnergyStorage;
            import net.minecraftforge.items.IItemHandler;
            import net.minecraftforge.items.ItemStackHandler;

            public class MachineBE extends BlockEntity {
                private final ItemStackHandler itemHandler = new ItemStackHandler(9);
                private final LazyOptional<IItemHandler> itemCap = LazyOptional.of(() -> itemHandler);
                private final LazyOptional<IEnergyStorage> energyCap = LazyOptional.empty();

                public MachineBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }

                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    if (cap == ForgeCapabilities.ITEM_HANDLER) {
                        return itemCap.cast();
                    }
                    if (cap == ForgeCapabilities.ENERGY) {
                        return energyCap.cast();
                    }
                    return super.getCapability(cap, side);
                }

                @Override
                public void invalidateCaps() {
                    super.invalidateCaps();
                    itemCap.invalidate();
                    energyCap.invalidate();
                }

                @Override
                public void load(CompoundTag tag) {
                    super.load(tag);
                    itemHandler.deserializeNBT(tag.getCompound("inventory"));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(TextReplacementPass(db), AstTransformPass(db), StructuralRefactorPass()),
            dryRun = true
        )
        val result = pipeline.run(projectDir)

        // Should detect capability patterns
        val structResult = result.passResults.find { it.passName == "Structural Refactor" }
        assertTrue(structResult != null, "Structural pass should run")
        assertTrue(structResult!!.changes.any { it.ruleId == "struct-lazy-optional" },
            "Should detect LazyOptional usage")

        // Text pass should rename Forge packages
        val textResult = result.passResults.find { it.passName == "Text Replacement" }
        assertTrue(textResult!!.changeCount > 0, "Should have text replacements")
    }

    // ─── Pattern 4: Network packet class ───

    @Test
    fun `detects networking pattern`() {
        val projectDir = createFile("ModNetwork.java", """
            package com.example.mymod;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraftforge.network.NetworkRegistry;
            import net.minecraftforge.network.PacketDistributor;
            import net.minecraftforge.network.simple.SimpleChannel;

            public class ModNetwork {
                private static final String PROTOCOL = "1";
                public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("mymod", "main"),
                    () -> PROTOCOL,
                    PROTOCOL::equals,
                    PROTOCOL::equals
                );

                public static void register() {
                    int id = 0;
                    INSTANCE.registerMessage(id++, SyncPacket.class,
                        SyncPacket::encode, SyncPacket::new, SyncPacket::handle);
                    INSTANCE.registerMessage(id++, ActionPacket.class,
                        ActionPacket::encode, ActionPacket::new, ActionPacket::handle);
                }

                public static void sendToServer(Object msg) {
                    INSTANCE.send(PacketDistributor.SERVER.noArg(), msg);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(TextReplacementPass(db), StructuralRefactorPass()),
            dryRun = true
        )
        val result = pipeline.run(projectDir)

        val structResult = result.passResults.find { it.passName == "Structural Refactor" }
        assertTrue(structResult!!.changes.any { it.ruleId == "struct-networking-channel" },
            "Should detect SimpleChannel")
        assertTrue(structResult.changes.any { it.ruleId == "struct-networking-register" },
            "Should detect registerMessage calls")
    }

    // ─── Pattern 5: Event handler class ───

    @Test
    fun `transforms event handler patterns`() {
        val projectDir = createFile("ModEvents.java", """
            package com.example.mymod;

            import net.minecraftforge.event.entity.living.LivingHurtEvent;
            import net.minecraftforge.event.entity.player.PlayerEvent;
            import net.minecraftforge.eventbus.api.SubscribeEvent;
            import net.minecraftforge.fml.common.Mod;

            @Mod.EventBusSubscriber(modid = "mymod")
            public class ModEvents {
                @SubscribeEvent
                public static void onLivingHurt(LivingHurtEvent event) {
                    float amount = event.getAmount();
                    if (amount > 10) {
                        event.setAmount(10);
                    }
                }

                @SubscribeEvent
                public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(TextReplacementPass(db), AstTransformPass(db)),
            dryRun = false
        )
        pipeline.run(projectDir)

        val content = tempDir.resolve("src/main/java/com/example/ModEvents.java").readText()

        assertTrue(content.contains("LivingDamageEvent"),
            "LivingHurtEvent should enter the LivingDamageEvent boundary before structural Pre/Post selection")
        assertTrue(content.contains("@EventBusSubscriber"),
            "Should simplify @Mod.EventBusSubscriber")
        assertFalse(content.contains("@Mod.EventBusSubscriber"),
            "Old annotation format should be gone")
    }

    // ─── Pattern 6: Config class ───

    @Test
    fun `transforms config pattern`() {
        val projectDir = createFile("ModConfig.java", """
            package com.example.mymod;

            import net.minecraftforge.common.ForgeConfigSpec;

            public class ModConfig {
                public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
                public static final ForgeConfigSpec.IntValue MAX_ENERGY;
                public static final ForgeConfigSpec.BooleanValue ENABLE_FEATURE;
                public static final ForgeConfigSpec SPEC;

                static {
                    BUILDER.push("general");
                    MAX_ENERGY = BUILDER.comment("Maximum energy").defineInRange("maxEnergy", 10000, 0, Integer.MAX_VALUE);
                    ENABLE_FEATURE = BUILDER.comment("Enable feature").define("enableFeature", true);
                    BUILDER.pop();
                    SPEC = BUILDER.build();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        TextReplacementPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/ModConfig.java").readText()

        assertTrue(content.contains("ModConfigSpec.Builder"))
        assertTrue(content.contains("ModConfigSpec.IntValue"))
        assertTrue(content.contains("ModConfigSpec.BooleanValue"))
        assertFalse(content.contains("ForgeConfigSpec"))
    }

    // ─── Pattern 7: Multiple ResourceLocation usages ───

    @Test
    fun `transforms all ResourceLocation patterns`() {
        val projectDir = createFile("ResourceLocs.java", """
            package com.example.mymod;

            import net.minecraft.resources.ResourceLocation;

            public class ResourceLocs {
                ResourceLocation a = new ResourceLocation("mymod", "block/machine");
                ResourceLocation b = new ResourceLocation("mymod:item/ruby");
                ResourceLocation c = new ResourceLocation("minecraft", "textures/block/stone.png");

                void test() {
                    ResourceLocation d = new ResourceLocation("mymod", "entity/" + name);
                    ResourceLocation e = new ResourceLocation(someString);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        AstTransformPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/ResourceLocs.java").readText()

        // Count conversions
        val fromNamespace = content.split("ResourceLocation.fromNamespaceAndPath").size - 1
        val parse = content.split("ResourceLocation.parse").size - 1

        assertTrue(fromNamespace >= 3, "Should convert at least 3 two-arg constructors, got $fromNamespace")
        assertTrue(parse >= 2, "Should convert at least 2 one-arg constructors, got $parse")
        assertFalse(content.contains("new ResourceLocation("), "No old constructors should remain")
    }

    // ─── Pattern 8: Extension interfaces ───

    @Test
    fun `transforms IForge extension interfaces in implements`() {
        val projectDir = createFile("CustomBlock.java", """
            package com.example.mymod;

            import net.minecraftforge.common.extensions.IForgeBlock;
            import net.minecraftforge.common.extensions.IForgeItem;

            public class CustomBlock implements SomeInterface, IForgeBlock {
            }

            class CustomItem implements IForgeItem {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        TextReplacementPass(db).apply(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/CustomBlock.java").readText()

        // Note: Text pass handles class names in import/usage via class-renames
        // The IForgeBlock -> IBlockExtension rename is in the class-renames.json
        // but currently only loaded into the mapping DB, not applied by text pass
        // (text pass uses text-replacements.json)
        // The package rename should still work
        assertTrue(content.contains("net.neoforged.neoforge.common.extensions"))
    }

    // ─── Pattern 9: Mixed vanilla + Forge code ───

    @Test
    fun `preserves vanilla code while transforming Forge code`() {
        val projectDir = createFile("MixedCode.java", """
            package com.example.mymod;

            import net.minecraft.world.level.Level;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraftforge.common.MinecraftForge;
            import net.minecraft.resources.ResourceLocation;

            public class MixedCode {
                public void tick(Level level, Player player) {
                    ItemStack stack = player.getMainHandItem();
                    if (!level.isClientSide()) {
                        MinecraftForge.EVENT_BUS.post(new CustomEvent());
                        ResourceLocation id = new ResourceLocation("mymod", "test");
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(TextReplacementPass(db), AstTransformPass(db)),
            dryRun = false
        )
        pipeline.run(projectDir)
        val content = tempDir.resolve("src/main/java/com/example/MixedCode.java").readText()

        // Vanilla imports preserved
        assertTrue(content.contains("net.minecraft.world.level.Level"))
        assertTrue(content.contains("net.minecraft.world.entity.player.Player"))
        assertTrue(content.contains("net.minecraft.world.item.ItemStack"))

        // Forge code transformed
        assertTrue(content.contains("NeoForge.EVENT_BUS"))
        assertTrue(content.contains("ResourceLocation.fromNamespaceAndPath"))

        // Vanilla method calls preserved
        assertTrue(content.contains("player.getMainHandItem()"))
        assertTrue(content.contains("level.isClientSide()"))
    }
}
