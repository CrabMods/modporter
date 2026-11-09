package com.modporter.transforms

import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextReplacementTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createTestFile(content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("TestMod.java")
        file.writeText(content)
        return tempDir
    }

    @Test
    fun `package renames are applied correctly`() {
        val projectDir = createTestFile("""
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.eventbus.api.IEventBus;
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("net.neoforged.neoforge.common.NeoForge"))
        assertTrue(transformed.contains("net.neoforged.fml.common.Mod"))
        assertTrue(transformed.contains("net.neoforged.bus.api.IEventBus"))
        assertTrue(!transformed.contains("net.minecraftforge"))

        assertTrue(result.changeCount > 0)
        assertTrue(result.changes.all { it.confidence == Confidence.HIGH })
    }

    @Test
    fun `forge registry keys and qualified references use the vanilla registries`() {
        val projectDir = createTestFile("""
            package com.example;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraftforge.registries.ForgeRegistries;
            public class TestMod {
                void scan() {
                    for (ResourceLocation name : ForgeRegistries.BLOCKS.getKeys()) {
                    }
                    boolean known = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(new ResourceLocation("a:b"));
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("BuiltInRegistries.BLOCK.keySet()"), transformed)
        assertTrue(transformed.contains("net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey("), transformed)
        assertTrue(!transformed.contains("net.neoforged.neoforge.registries.BuiltInRegistries"), transformed)
    }

    @Test
    fun `nested tick event imports point at the 1_21 classes`() {
        val projectDir = createTestFile("""
            package com.example;
            import net.minecraftforge.event.TickEvent.ClientTickEvent;
            import net.minecraftforge.event.TickEvent.LevelTickEvent;
            import net.minecraftforge.event.TickEvent.PlayerTickEvent;
            import net.minecraftforge.eventbus.api.SubscribeEvent;
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.neoforged.neoforge.client.event.ClientTickEvent;"), transformed)
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.tick.LevelTickEvent;"), transformed)
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.tick.PlayerTickEvent;"), transformed)
        assertTrue(!transformed.contains("import net.neoforged.neoforge.event.ClientTickEvent;"), transformed)
    }

    @Test
    fun `source level test assertions migrate forge names inside strings`() {
        val testDir = tempDir.resolve("src/test/java/com/example")
        testDir.createDirectories()
        testDir.resolve("SourceContractTest.java").writeText("""
            package com.example;

            class SourceContractTest {
                void contract(String source) {
                    assert source.contains("import net.minecraftforge.fluids.FluidStack;");
                    assert source.contains("MinecraftForge.EVENT_BUS.register(ServerEvent.class)");
                    assert source.matches(".*MinecraftForge\\s*\\.\\s*EVENT_BUS.*");
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)
        val transformed = testDir.resolve("SourceContractTest.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.fluids.FluidStack;"), transformed)
        assertTrue(transformed.contains("NeoForge.EVENT_BUS.register(ServerEvent.class)"), transformed)
        assertTrue(transformed.contains("NeoForge\\\\s*\\\\.\\\\s*EVENT_BUS"), transformed)
        assertFalse(transformed.contains("net.minecraftforge"), transformed)
        assertFalse(transformed.contains("MinecraftForge"), transformed)
    }

    @Test
    fun `forge internal names in mixin descriptors use neoforge owners`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
            import org.spongepowered.asm.mixin.injection.At;

            public class TestMod {
                @WrapOperation(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/internal/BrandingControl;forEachLine(ZZLjava/util/function/BiConsumer;)V"))
                private void branding() {}

                String fml = "Lnet/minecraftforge/fml/ModLoadingContext;";
                String bus = "Lnet/minecraftforge/eventbus/api/IEventBus;";
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "forge-internal-name-descriptors" })
        assertTrue(transformed.contains("Lnet/neoforged/neoforge/internal/BrandingControl;"), transformed)
        assertTrue(transformed.contains("Lnet/neoforged/fml/ModLoadingContext;"), transformed)
        assertTrue(transformed.contains("Lnet/neoforged/bus/api/IEventBus;"), transformed)
        assertFalse(transformed.contains("Lnet/minecraftforge/"), transformed)
        assertFalse(transformed.contains("Lnet/neoforged/neoforge/fml/"), transformed)
        assertFalse(transformed.contains("Lnet/neoforged/neoforge/eventbus/"), transformed)
    }

    @Test
    fun `colorless glass tag constants use renamed glass block constants`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.neoforged.neoforge.common.Tags;

            public class TestMod {
                Object blockTag = Tags.Blocks.GLASS_COLORLESS;
                Object itemTag = Tags.Items.GLASS_COLORLESS;
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        assertTrue(transformed.contains("Tags.Blocks.GLASS_BLOCKS_COLORLESS"), transformed)
        assertTrue(transformed.contains("Tags.Items.GLASS_BLOCKS_COLORLESS"), transformed)
        assertFalse(transformed.contains("GLASS_COLORLESS"), transformed)
    }

    @Test
    fun `class renames are applied correctly`() {
        val projectDir = createTestFile("""
            MinecraftForge.EVENT_BUS.register(this);
            ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
            ForgeHooks.onLivingAttack(entity, source, amount);
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("NeoForge.EVENT_BUS"))
        assertTrue(transformed.contains("ModConfigSpec.Builder"))
        assertTrue(transformed.contains("CommonHooks.onLivingAttack"))
    }

    @Test
    fun `forge gui helper migrates to vanilla gui instead of removed extended gui`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.client.gui.overlay.ForgeGui;

            public class TestMod {
                void render(ForgeGui gui) {
                    Object accessor = gui;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.client.gui.Gui;"))
        assertTrue(transformed.contains("void render(Gui gui)"))
        assertFalse(transformed.contains("ExtendedGui"))
        assertFalse(transformed.contains("net.neoforged.neoforge.client.gui.overlay.Gui"))
        assertFalse(transformed.contains("ForgeGui"))
    }

    @Test
    fun `vanilla options screen imports migrate to options package`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.client.gui.screens.OptionsScreen;

            public class TestMod {
                net.minecraft.client.gui.screens.OptionsScreen screen;
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.client.gui.screens.options.OptionsScreen;"), transformed)
        assertTrue(transformed.contains("net.minecraft.client.gui.screens.options.OptionsScreen screen;"), transformed)
        assertFalse(transformed.contains("net.minecraft.client.gui.screens.OptionsScreen"), transformed)
    }

    @Test
    fun `network direction checks are not replaced with constant placeholders`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod {
                public boolean server(Object ctx) {
                    return ctx.get().getDirection().getReceptionSide().isServer();
                }

                public boolean client(Object ctx) {
                    return ctx.get().getDirection().getReceptionSide().isClient();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("ctx.get().getDirection().getReceptionSide().isServer()"))
        assertTrue(transformed.contains("ctx.get().getDirection().getReceptionSide().isClient()"))
        assertFalse(transformed.contains("true /*"))
        assertFalse(transformed.contains("direction check"))
    }

    @Test
    fun `business state get calls are not rewritten as blockstate property reads`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod {
                void render(MirrorConfigState state, MirrorKind kind) {
                    Object value = state.get(kind);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("state.get(kind)"), transformed)
        assertFalse(transformed.contains("state.getValue(kind)"), transformed)
    }

    @Test
    fun `registry constants are replaced`() {
        val projectDir = createTestFile("""
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.FLUIDS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.POI_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.VILLAGER_PROFESSIONS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.FOLIAGE_PLACER_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.TREE_DECORATOR_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.WORLD_CARVERS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MOD_ID);
            ResourceKey.create(ForgeRegistries.Keys.BIOME_MODIFIERS, id);
            DeferredRegister
                .create(ForgeRegistries.RECIPE_TYPES, MOD_ID);
            DeferredRegister.create(
                ForgeRegistries.FEATURES, MOD_ID);
            ForgeRegistries.ITEMS.getKey(item);
            ForgeRegistries.FLUIDS.getValue(fluidId);
            ForgeRegistries.POTIONS.getKey(potion);
            ForgeRegistries.PAINTING_VARIANTS.getKey(art);
            BootstapContext<?> context;
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        // DeferredRegister.create uses Registries (ResourceKey)
        assertTrue(transformed.contains("DeferredRegister.create(Registries.ITEM"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.BLOCK"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.ENTITY_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.FLUID"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.VILLAGER_PROFESSION"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.FOLIAGE_PLACER_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.TREE_DECORATOR_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.CARVER"))
        assertTrue(transformed.contains("DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES"))
        assertTrue(transformed.contains("DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS"))
        assertTrue(transformed.contains("ResourceKey.create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, id)"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.RECIPE_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.FEATURE"))
        // Non-DeferredRegister context uses BuiltInRegistries (Registry instance)
        assertTrue(transformed.contains("BuiltInRegistries.ITEM.getKey"))
        assertTrue(transformed.contains("BuiltInRegistries.FLUID.get(fluidId)"))
        assertTrue(transformed.contains("BuiltInRegistries.POTION.getKey(potion)"))
        assertTrue(transformed.contains("BuiltInRegistries.PAINTING_VARIANT.getKey(art)"))
        assertTrue(transformed.contains("BootstrapContext<?> context"))
    }

    @Test
    fun `syntax sensitive migrations keep valid Java statements`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
            import net.minecraft.world.item.ItemStack;
            import net.minecraftforge.network.NetworkRegistry;
            import net.minecraftforge.network.simple.SimpleChannel;

            public class TestMod extends MeleeAttackGoal {
                public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
                    ExampleMod.prefix("channel"),
                    () -> "1",
                    "1"::equals,
                    "1"::equals
                );

                public void render() {
                    Tesselator tesselator = Tesselator.getInstance();
                    BufferBuilder bufferbuilder = tesselator.getBuilder();
                    bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                }

                public void tick() {
                    if (true)
                        super.tick();
                }

                public void save(CompoundTag tag) {
                    ContainerHelper.saveAllItems(tag, this.getItemStacks());
                    ContainerHelper.loadAllItems(tag, this.getItemStacks());
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(
            transformed.contains("BufferBuilder bufferbuilder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);") ||
                transformed.contains("bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);")
        )
        assertTrue(transformed.contains("super.tick();"))
        assertTrue(transformed.contains("ContainerHelper.saveAllItems(tag, this.getItemStacks());"))
        assertTrue(transformed.contains("ContainerHelper.loadAllItems(tag, this.getItemStacks());"))
        assertTrue(transformed.contains("NetworkRegistry.newSimpleChannel("))
        assertFalse(transformed.contains("TODO: [forge2neo]"))
        assertFalse(transformed.contains("this.getItemStacks(,"))
    }

    @Test
    fun `rendered buffer rename adds mesh data import`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.blaze3d.vertex.BufferBuilder;

            public interface TestMod {
                BufferBuilder.RenderedBuffer build(BufferBuilder builder);
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import com.mojang.blaze3d.vertex.MeshData;"))
        assertTrue(transformed.contains("MeshData build(BufferBuilder builder);"))
        assertFalse(transformed.contains("BufferBuilder.RenderedBuffer"))
    }

    @Test
    fun `gui overlay render event migrates type and layer name access`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.neoforged.neoforge.client.event.RenderGuiOverlayEvent;
            import net.neoforged.neoforge.client.gui.overlay.VanillaGuiOverlay;

            public class TestMod {
                public static void preOverlay(RenderGuiOverlayEvent.Pre event) {
                    if (event.getOverlay().id() == VanillaGuiOverlay.MOUNT_HEALTH.id()) {
                        event.setCanceled(true);
                    }
                }

                public static void anyOverlay(RenderGuiOverlayEvent event) {
                    if (event.getOverlay() == VanillaGuiOverlay.SLEEP_FADE.type()) {
                        event.setCanceled(true);
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.client.gui.VanillaGuiLayers;"))
        assertTrue(transformed.contains("preOverlay(RenderGuiLayerEvent.Pre event)"))
        assertTrue(transformed.contains("anyOverlay(RenderGuiLayerEvent event)"))
        assertTrue(transformed.contains("event.getName().equals(VanillaGuiLayers.VEHICLE_HEALTH)"))
        assertTrue(transformed.contains("event.getName().equals(VanillaGuiLayers.SLEEP_OVERLAY)"))
        assertFalse(transformed.contains("RenderGuiOverlayEvent"))
        assertFalse(transformed.contains("VanillaGuiOverlay"))
        assertFalse(transformed.contains("getOverlay()"))
    }

    @Test
    fun `IForgeXXX interface renames via text rules`() {
        val projectDir = createTestFile("""
            ForgeSpawnEggItem egg = new ForgeSpawnEggItem(entity, color1, color2, props);
            ForgeTier myTier = new ForgeTier(5, 2000, 10.0f, 4.0f, 20, tags, repairIngredient);
            ToolActions.DIG = new ToolAction();
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("DeferredSpawnEggItem"))
        assertTrue(transformed.contains("SimpleTier"))
        assertTrue(transformed.contains("ItemAbilities"))
    }

    @Test
    fun `selection list coordinate rules do not rewrite local variables`() {
        val projectDir = createTestFile("""
            private void fillBox(int x1, int y1, int z1, int x2, int y2, int z2) {
                for (int x = x1; x <= x2; x++) {
                    for (int y = y1; y <= y2; y++) {
                    }
                }
                int left = this.x0;
                int bottom = super.y1;
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("fillBox(int x1, int y1, int z1, int x2, int y2, int z2)"))
        assertTrue(transformed.contains("for (int x = x1; x <= x2; x++)"))
        assertTrue(transformed.contains("for (int y = y1; y <= y2; y++)"))
        assertTrue(transformed.contains("int left = this.getX();"))
        assertTrue(transformed.contains("int bottom = super.getBottom();"))
    }

    @Test
    fun `render highlight chained partial tick uses delta tracker accessor`() {
        val projectDir = createTestFile("""
            double partialTicks = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            double directPartialTicks = event.getPartialTick();
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("event.getDeltaTracker().getGameTimeDeltaPartialTick(false)"))
        assertTrue(transformed.contains("double directPartialTicks = event.getPartialTick();"))
    }

    @Test
    fun `hurtAndBreak lambda callback becomes equipment slot overload`() {
        val projectDir = createTestFile("""
            import net.minecraft.world.InteractionHand;

            public class TestMod {
                void damage(ItemStack wand, Player player) {
                    wand.hurtAndBreak(1, player, e -> e.onEquippedItemBroken(InteractionHand.MAIN_HAND));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.entity.EquipmentSlot;"))
        assertTrue(transformed.contains("wand.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);"))
        assertTrue(!transformed.contains("onEquippedItemBroken(InteractionHand.MAIN_HAND)"))
    }

    @Test
    fun `gui overlay registration migrates to layered draw API`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.client.gui.GuiGraphics;
            import net.neoforged.neoforge.client.event.RegisterGuiOverlaysEvent;
            import net.neoforged.neoforge.client.gui.overlay.ExtendedGui;
            import net.neoforged.neoforge.client.gui.overlay.IGuiOverlay;
            import net.neoforged.neoforge.client.gui.overlay.VanillaGuiOverlay;

            public class TestMod {
                public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
                    event.registerAbove(
                            VanillaGuiOverlay.HOTBAR.id(),
                            "cooldown_hud",
                            new CooldownHudOverlay()
                    );
                }
            }

            class CooldownHudOverlay implements IGuiOverlay {
                public static final ResourceLocation OVERLAY_ID = ResourceLocation.fromNamespaceAndPath("example", "cooldown_hud");
                private static final String DOC = "gui.getFont() must remain documentation";

                @Override
                public void render(ExtendedGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
                    Object activeGui = gui;
                    int textWidth = gui.getFont().width("text");
                    // gui.getFont() must remain a comment
                    for (int i = 0; i < player.getInventory().size(); i++) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.client.gui.VanillaGuiLayers;"))
        assertTrue(transformed.contains("import net.minecraft.client.gui.LayeredDraw;"))
        assertTrue(transformed.contains("import net.minecraft.client.DeltaTracker;"))
        assertTrue(transformed.contains("registerGuiOverlays(RegisterGuiLayersEvent event)"))
        assertTrue(transformed.contains("event.registerAbove(VanillaGuiLayers.HOTBAR, CooldownHudOverlay.OVERLAY_ID, new CooldownHudOverlay())"))
        assertTrue(transformed.contains("class CooldownHudOverlay implements LayeredDraw.Layer"))
        assertTrue(transformed.contains("public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker)"))
        assertTrue(transformed.contains("Object activeGui = net.minecraft.client.Minecraft.getInstance().gui;"), transformed)
        assertTrue(transformed.contains("net.minecraft.client.Minecraft.getInstance().gui.getFont().width(\"text\")"), transformed)
        assertTrue(transformed.contains("DOC = \"gui.getFont() must remain documentation\""), transformed)
        assertTrue(transformed.contains("// gui.getFont() must remain a comment"), transformed)
        assertTrue(transformed.contains("player.getInventory().getContainerSize()"))
        assertTrue(!transformed.contains("RegisterGuiOverlaysEvent"))
        assertTrue(!transformed.contains("IGuiOverlay"))
        assertTrue(!transformed.contains("ExtendedGui"))
        assertTrue(!transformed.contains("VanillaGuiOverlay"))
    }

    @Test
    fun `layered draw overlay signatures add delta tracker import`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.LayeredDraw;

            class CooldownHudOverlay implements LayeredDraw.Layer {
                @Override
                public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
                    guiGraphics.pose();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.client.DeltaTracker;"), transformed)
        assertTrue(transformed.contains("public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker)"), transformed)
    }

    @Test
    fun `text replacement does not rewrite collection size on container screens`() {
        val projectDir = createTestFile("""
            package com.example;

            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

            public class TestMod extends AbstractContainerScreen<MenuSurface> {
                private final Map<Integer, String> pages = new HashMap<>();

                public int pageCount() {
                    return this.pages.size();
                }
            }

            class MenuSurface {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        assertTrue(transformed.contains("return this.pages.size();"), transformed)
        assertTrue(!transformed.contains("this.pages.getSize()"), transformed)
        assertTrue(!transformed.contains("this.pages.getContainerSize()"), transformed)
    }

    @Test
    fun `model render rgba floats migrate to packed color`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;

            public class TestMod {
                private Model model;
                private ModelPart group;
                // group.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
                private static final String RENDER_DOC = "model.renderToBuffer(poseStack, vertexConsumer, packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, alpha);";

                /*
                 public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
                     group.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
                 }
                 */

                @Override
                public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay,
                                           float red, float green, float blue, float alpha) {
                    group.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
                }

                public void renderExternal(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float alpha) {
                    model.renderToBuffer(poseStack, vertexConsumer, packedLight, packedOverlay,
                            1.0F, 1.0F, 1.0F, alpha);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.util.FastColor;"))
        assertTrue(transformed.contains("public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color)"))
        assertTrue(transformed.contains("group.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);"))
        assertTrue(transformed.contains("model.renderToBuffer(poseStack, vertexConsumer, packedLight, packedOverlay, FastColor.ARGB32.colorFromFloat(alpha, 1.0F, 1.0F, 1.0F));"))
        assertTrue(transformed.contains("// group.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);"), transformed)
        assertTrue(transformed.contains("private static final String RENDER_DOC = \"model.renderToBuffer(poseStack, vertexConsumer, packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, alpha);\";"), transformed)
        assertTrue(transformed.contains("group.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);"), transformed)
        assertFalse(transformed.contains("// group.render(poseStack, vertexConsumer, packedLight, packedOverlay, FastColor"), transformed)
        assertFalse(transformed.contains("RENDER_DOC = \"model.renderToBuffer(poseStack, vertexConsumer, packedLight, packedOverlay, FastColor"), transformed)
        assertTrue(!transformed.contains("float red, float green, float blue, float alpha"))
    }

    @Test
    fun `entity synched data definitions migrate to builder`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.network.syncher.EntityDataAccessor;

            public class TestMod {
                @Override
                protected void defineSynchedData() {
                    super.defineSynchedData();
                    this.entityData.define(DATA_LIFETIME, 20);
                    this.getEntityData().define(DATA_LOADING, true);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.network.syncher.SynchedEntityData;"))
        assertTrue(transformed.contains("protected void defineSynchedData(SynchedEntityData.Builder builder)"))
        assertTrue(transformed.contains("super.defineSynchedData(builder);"))
        assertTrue(transformed.contains("builder.define(DATA_LIFETIME, 20);"))
        assertTrue(transformed.contains("builder.define(DATA_LOADING, true);"))
        assertTrue(!transformed.contains("this.entityData.define("))
        assertTrue(!transformed.contains("this.getEntityData().define("))
        assertTrue(!transformed.contains("super.defineSynchedData();"))
    }

    @Test
    fun `public entity synched data definitions keep visibility when migrating to builder`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.network.syncher.EntityDataAccessor;

            public class TestMod {
                @Override
                public void defineSynchedData() {
                    super.defineSynchedData();
                    this.getEntityData().define(DATA_STATE, false);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.network.syncher.SynchedEntityData;"))
        assertTrue(transformed.contains("public void defineSynchedData(SynchedEntityData.Builder builder)"))
        assertTrue(transformed.contains("super.defineSynchedData(builder);"))
        assertTrue(transformed.contains("builder.define(DATA_STATE, false);"))
        assertTrue(!transformed.contains("public void defineSynchedData()"))
        assertTrue(!transformed.contains("super.defineSynchedData();"))
    }

    @Test
    fun `saved data computeIfAbsent migrates to factory provider API`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.saveddata.SavedData;

            public class TestMod extends SavedData {
                private static final String DATA_NAME = "example_data";

                public static TestMod load(CompoundTag tag) {
                    return new TestMod();
                }

                @Override
                public CompoundTag save(CompoundTag tag) {
                    return tag;
                }

                public static TestMod get(ServerLevel level) {
                    return level.getDataStorage().computeIfAbsent(
                            TestMod::load,
                            TestMod::new,
                            DATA_NAME
                    );
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.core.HolderLookup;"))
        assertTrue(transformed.contains("public static TestMod load(CompoundTag tag, HolderLookup.Provider registries)"))
        assertTrue(transformed.contains("public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries)"))
        assertTrue(transformed.contains("computeIfAbsent(new SavedData.Factory<>(TestMod::new, TestMod::load), DATA_NAME"))
        assertTrue(!transformed.contains("TestMod::load,\n"))
        assertTrue(!transformed.contains("TestMod::new,\n"))
    }

    @Test
    fun `text replacements do not migrate business load compound tag methods without structural owner evidence`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;

            public abstract class TestMod {
                public abstract void load(CompoundTag tag);

                public static class Memory extends TestMod {
                    @Override
                    public void load(CompoundTag tag) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public abstract void load(CompoundTag tag);"), transformed)
        assertTrue(transformed.contains("public void load(CompoundTag tag)"), transformed)
        assertFalse(transformed.contains("loadAdditional"), transformed)
        assertFalse(transformed.contains("HolderLookup.Provider registries"), transformed)
    }

    @Test
    fun `text replacements do not inject registry access fallbacks for item stack serialization`() {
        val projectDir = createTestFile("""
            package com.example;

            import java.util.Optional;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.item.ItemStack;

            public class TestMod {
                public void save(CompoundTag tag, ItemStack stack) {
                    ItemStack restored = ItemStack.of(tag.getCompound("Stack"));
                    Optional.of(tag).map(ItemStack::of);
                    ContainerHelper.saveAllItems(tag, this.getItemStacks());
                    ContainerHelper.loadAllItems(tag, this.getItemStacks());
                    tag.put("Stack", stack.save(new CompoundTag()));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("ItemStack restored = ItemStack.of(tag.getCompound(\"Stack\"));"), transformed)
        assertTrue(transformed.contains("Optional.of(tag).map(ItemStack::of);"), transformed)
        assertTrue(transformed.contains("ContainerHelper.saveAllItems(tag, this.getItemStacks());"), transformed)
        assertTrue(transformed.contains("ContainerHelper.loadAllItems(tag, this.getItemStacks());"), transformed)
        assertTrue(transformed.contains("tag.put(\"Stack\", stack.save(new CompoundTag()));"), transformed)
        assertFalse(transformed.contains("RegistryAccess.EMPTY"), transformed)
        assertFalse(transformed.contains("player.registryAccess()"), transformed)
    }

    @Test
    fun `common 1_21 API moves migrate by text rules`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.chunk.ChunkStatus;
            import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
            import net.neoforged.neoforge.event.level.BlockEvent;
            import net.neoforged.neoforge.event.tick.LevelTickEvent;

            public class TestMod {
                public static void onMobSpawn(MobSpawnEvent.FinalizeSpawn event) {
                }

                public static void onWorldTick(LevelTickEvent event) {
                    Object level = event.level;
                    Object status = ChunkStatus.FULL;
                }

                @Override
                public int getUseDuration(ItemStack stack) {
                    return 20;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.level.chunk.status.ChunkStatus;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.level.BlockEvent;"))
        assertTrue(transformed.contains("public static void onMobSpawn(FinalizeSpawnEvent event)"))
        assertTrue(transformed.contains("public static void onWorldTick(LevelTickEvent event)"))
        assertTrue(transformed.contains("Object level = event.level;"))
        assertTrue(transformed.contains("public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity entity)"))
        assertTrue(!transformed.contains("MobSpawnEvent.FinalizeSpawn"))
        assertFalse(transformed.contains("LevelTickEvent.Post"))
    }

    @Test
    fun `jade tooltip element helper migrates only declared ITooltip receivers`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import snownee.jade.api.ITooltip;

            public class TestMod {
                public void appendTooltip(ITooltip tooltip, OtherHelper other, ItemStack stack) {
                    tooltip.add(tooltip.getElementHelper().smallItem(stack));
                    tooltip.append(tooltip.getElementHelper().smallItem(stack));
                    other.getElementHelper();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "jade-tooltip-elementhelper-static" })
        assertTrue(transformed.contains("import snownee.jade.api.ui.IElementHelper;"))
        assertTrue(transformed.contains("tooltip.add(IElementHelper.get().smallItem(stack));"))
        assertTrue(transformed.contains("tooltip.append(IElementHelper.get().smallItem(stack));"))
        assertTrue(transformed.contains("other.getElementHelper();"))
        assertFalse(transformed.contains("tooltip.getElementHelper()"))
    }

    @Test
    fun `jade tooltip element helper migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import snownee.jade.api.ITooltip;

            public class TestMod {
                private static final String DOC = "tooltip.getElementHelper()";

                /*
                 public void docs(ITooltip tooltip, ItemStack stack) {
                     tooltip.add(tooltip.getElementHelper().smallItem(stack));
                 }
                 */

                public void appendTooltip(ITooltip tooltip, OtherHelper other, ItemStack stack) {
                    tooltip.add(tooltip.getElementHelper().smallItem(stack));
                    other.getElementHelper();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("tooltip.add(IElementHelper.get().smallItem(stack));"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"tooltip.getElementHelper()\";"), transformed)
        assertTrue(transformed.contains("tooltip.add(tooltip.getElementHelper().smallItem(stack));"), transformed)
        assertTrue(transformed.contains("other.getElementHelper();"), transformed)
        assertFalse(transformed.contains("DOC = \"IElementHelper.get()"), transformed)
    }

    @Test
    fun `lootr loader-specific init package migrates to neoforge namespace`() {
        val projectDir = createTestFile("""
            package com.example;

            import noobanidus.mods.lootr.init.ModBlocks;
            import noobanidus.mods.lootr.config.ConfigManager;

            public class TestMod {
                public Object chest() {
                    return ModBlocks.CHEST.get();
                }

                public boolean oldTextures() {
                    return ConfigManager.isOldTextures();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "pkg-lootr-neoforge-init" })
        assertTrue(result.changes.any { it.ruleId == "pkg-lootr-neoforge-config" })
        assertTrue(result.changes.any { it.ruleId == "lootr-config-new-textures" })
        assertTrue(transformed.contains("import noobanidus.mods.lootr.neoforge.init.ModBlocks;"))
        assertTrue(transformed.contains("import noobanidus.mods.lootr.neoforge.config.ConfigManager;"))
        assertTrue(transformed.contains("return !ConfigManager.isNewTextures();"))
        assertFalse(transformed.contains("noobanidus.mods.lootr.init"))
        assertFalse(transformed.contains("noobanidus.mods.lootr.config"))
        assertFalse(transformed.contains("isOldTextures"))
    }

    @Test
    fun `deferred holder concrete registrations use registry base generic`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class TestMod {
                public static final DeferredHolder<CoolBlock, CoolBlock> COOL_BLOCK = BLOCKS.register(
                        "cool_block",
                        () -> new CoolBlock()
                );

                public static final DeferredHolder<CoolItem, CoolItem> COOL_ITEM = ITEMS.register(
                        "cool_item",
                        () -> new CoolItem()
                );
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("DeferredHolder<Block, CoolBlock> COOL_BLOCK = BLOCKS.register"))
        assertTrue(transformed.contains("DeferredHolder<Item, CoolItem> COOL_ITEM = ITEMS.register"))
    }

    @Test
    fun `chunk generator codec and abstract signatures migrate to mapcodec api`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.Executor;
            import net.minecraft.core.RegistryAccess;
            import net.minecraft.world.level.StructureManager;
            import net.minecraft.world.level.chunk.ChunkAccess;
            import net.minecraft.world.level.chunk.ChunkGenerator;
            import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
            import net.minecraft.world.level.levelgen.RandomState;
            import net.minecraft.world.level.levelgen.blending.Blender;
            import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestMod extends ChunkGenerator {
                public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS = null;
                public static final DeferredHolder<Codec<TestMod>, Codec<TestMod>> TEST_GENERATOR =
                        CHUNK_GENERATORS.register("test", () -> TestMod.CODEC);

                public static final Codec<TestMod> CODEC = RecordCodecBuilder.create(instance ->
                        instance.group().apply(instance, TestMod::new)
                );

                @Override
                protected Codec<? extends ChunkGenerator> codec() {
                    return CODEC;
                }

                @Override
                public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                             StructureManager structureManager, ChunkAccess chunk,
                                             StructureTemplateManager structureTemplateManager) {
                }

                @Override
                public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random, StructureManager structureManager, ChunkAccess chunk) {
                    return CompletableFuture.completedFuture(chunk);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(transformed.contains("DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS"))
        assertTrue(transformed.contains("DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<TestMod>> TEST_GENERATOR"))
        assertTrue(transformed.contains("public static final MapCodec<TestMod> CODEC = RecordCodecBuilder.mapCodec"))
        assertTrue(transformed.contains("protected MapCodec<? extends ChunkGenerator> codec()"))
        assertTrue(transformed.contains("StructureTemplateManager structureTemplateManager)"))
        assertTrue(transformed.contains("fillFromNoise(Blender blender, RandomState random, StructureManager structureManager, ChunkAccess chunk)"))
    }

    @Test
    fun `common 1_21 signature migrations compile against holder and provider APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.authlib.GameProfile;
            import net.minecraft.advancements.Advancement;
            import net.minecraft.core.BlockPos;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.network.Connection;
            import net.minecraft.network.protocol.PacketFlow;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.chunk.ChunkAccess;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.minecraft.world.level.chunk.status.ChunkStatus;

            import java.util.UUID;

            public class TestMod extends Item {
                public TestMod(Properties properties) {
                    super(properties);
                }

                public void sample(GameTestHelper helper, ServerPlayer player, ChunkAccess chunk,
                                   MobEffectInstance effect, ItemStack stack) {
                    Advancement advancement = player.getServer().getAdvancements()
                            .getAdvancement(ResourceLocation.parse("minecraft:end/root"));
                    Advancement helperAdvancement = PlayerHelper.getAdvancement(player, ResourceLocation.parse("minecraft:story/root"));
                    player.getAdvancements().award(advancement, "entered_end");
                    boolean complete = chunk.getStatus().isOrAfter(ChunkStatus.FULL);
                    Player mock = helper.makeMockSurvivalPlayer();
                    ListTag ender = player.getEnderChestInventory().createTag();
                    player.getEnderChestInventory().fromTag(ender);
                    Tag savedEffect = effect.save(new CompoundTag());
                    int efficiency = stack.getEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY);
                    stack.enchant(Enchantments.BLOCK_EFFICIENCY, 1);
                    stack.getOrCreateTag().putLong("cooldown", 123L);
                }

                @Override
                public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
                    return enchantment == Enchantments.BLOCK_EFFICIENCY;
                }

                static ServerPlayer connected(GameTestHelper helper) {
                    ServerPlayer player = new ServerPlayer(
                            helper.getLevel().getServer(),
                            helper.getLevel(),
                            new GameProfile(UUID.randomUUID(), "test-player")
                    );
                    Connection connection = new Connection(PacketFlow.SERVERBOUND);
                    helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player);
                    return player;
                }

                static void blockEntityCopy(ServerLevel mirrorWorld, LevelChunk targetChunk, BlockPos pos,
                                            CompoundTag copy, BlockEntity sourceBE) {
                    BlockEntity targetBE = BlockEntity.loadStatic(pos, targetChunk.getBlockState(pos), copy);
                    if (targetBE != null) {
                        targetBE.load(copy);
                        targetBE.load(sourceBE.saveWithoutMetadata());
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.advancements.AdvancementHolder;"))
        assertTrue(transformed.contains("import net.minecraft.server.level.ClientInformation;"))
        assertTrue(transformed.contains("import net.minecraft.server.network.CommonListenerCookie;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.GameType;"))
        assertTrue(transformed.contains("AdvancementHolder advancement = player.getServer().getAdvancements()"))
        assertTrue(transformed.contains(".get(ResourceLocation.parse(\"minecraft:end/root\"))"))
        assertTrue(transformed.contains("Advancement helperAdvancement = PlayerHelper.getAdvancement(player, ResourceLocation.parse(\"minecraft:story/root\"));"))
        // ChunkAccess#getStatus is renamed by the structural pass, on chunk-typed receivers only
        assertTrue(transformed.contains("chunk.getStatus().isOrAfter(ChunkStatus.FULL)"))
        assertTrue(transformed.contains("helper.makeMockPlayer(GameType.SURVIVAL)"))
        assertTrue(transformed.contains("player.getEnderChestInventory().createTag(player.registryAccess())"))
        assertTrue(transformed.contains("player.getEnderChestInventory().fromTag(ender, player.registryAccess())"))
        assertTrue(transformed.contains("Tag savedEffect = effect.save();"))
        assertTrue(transformed.contains("new GameProfile(UUID.randomUUID(), \"test-player\"), ClientInformation.createDefault())"))
        assertTrue(transformed.contains("placeNewPlayer(connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false))"))
        assertTrue(transformed.contains("targetBE.loadWithComponents(copy, targetBE.getLevel().registryAccess());"))
        assertTrue(transformed.contains("targetBE.loadWithComponents(sourceBE.saveWithoutMetadata(targetBE.getLevel().registryAccess()), targetBE.getLevel().registryAccess());"))
        assertTrue(transformed.contains("stack.getEnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY))"))
        assertTrue(transformed.contains("stack.enchant(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 1)"))
        assertTrue(transformed.contains("stack.getOrCreateTag().putLong(\"cooldown\", 123L);"))
        assertTrue(transformed.contains("public boolean supportsEnchantment(ItemStack stack, net.minecraft.core.Holder<Enchantment> enchantment)"))
        assertTrue(transformed.contains("return enchantment.is(Enchantments.EFFICIENCY);"))
    }

    @Test
    fun `legacy custom enchantments migrate to resource keys and holder lookups`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class InstantWorldMirror {
                static final String MODID = "example";

                void init(Object modEventBus) {
                    ModEnchantments.ENCHANTMENTS.register(modEventBus);
                }
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        InstantWorldMirror.MODID
                );

                public static final EnchantmentCategory MIRROR_CATEGORY = EnchantmentCategory.create(
                        InstantWorldMirror.MODID + "_mirror",
                        item -> true
                );

                public static final DeferredHolder<Enchantment, Enchantment> PERMANENCE = ENCHANTMENTS.register(
                        "permanence",
                        PermanentMirrorEnchantment::new
                );

                private ModEnchantments() {
                }

                public static boolean hasPermanence(Level level, ItemStack stack) {
                    return stack.getEnchantmentLevel(PERMANENCE.get()) > 0;
                }

                public static void applyPermanence(Level level, ItemStack stack) {
                    stack.enchant(PERMANENCE.get(), 1);
                }

                public static boolean isPermanence(Enchantment enchantment) {
                    return enchantment == PERMANENCE.get();
                }

                private static class PermanentMirrorEnchantment extends Enchantment {
                    private PermanentMirrorEnchantment() {
                        super(Rarity.RARE, MIRROR_CATEGORY, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                    }
                }
            }

            final class Usage {
                void use(ItemStack stack, MirrorItem mirror) {
                    stack.getEnchantmentLevel(ModEnchantments.PERMANENCE.get());
                    mirror.canApplyAtEnchantingTable(stack, ModEnchantments.PERMANENCE.get());
                    new EnchantmentInstance(ModEnchantments.PERMANENCE.get(), 1);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(!transformed.contains("ENCHANTMENTS.register(modEventBus)"))
        assertTrue(!transformed.contains("DeferredRegister<Enchantment> ENCHANTMENTS"))
        assertTrue(!transformed.contains("EnchantmentCategory.create"))
        assertTrue(!transformed.contains("extends Enchantment"))
        assertTrue(transformed.contains("private static net.minecraft.core.Holder<Enchantment> holder(net.minecraft.world.level.Level level, net.minecraft.resources.ResourceKey<Enchantment> key)"))
        assertTrue(transformed.contains("public static final net.minecraft.resources.ResourceKey<Enchantment> PERMANENCE"))
        assertTrue(transformed.contains("public static final java.util.List<net.minecraft.resources.ResourceKey<Enchantment>> ENCHANTMENTS"))
        assertTrue(transformed.contains("java.util.List.of(PERMANENCE)"))
        assertTrue(transformed.contains("ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, \"permanence\")"))
        assertTrue(transformed.contains("stack.getEnchantmentLevel(holder(level, PERMANENCE))"))
        assertTrue(transformed.contains("stack.enchant(holder(level, PERMANENCE), 1)"))
        assertTrue(transformed.contains("public static boolean isPermanence(net.minecraft.core.Holder<Enchantment> enchantment)"))
        assertTrue(transformed.contains("return enchantment.is(PERMANENCE);"))
        assertTrue(transformed.contains("stack.getEnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(ModEnchantments.PERMANENCE))"))
        assertTrue(transformed.contains("mirror.supportsEnchantment(stack, net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(ModEnchantments.PERMANENCE))"))
        assertTrue(transformed.contains("new EnchantmentInstance(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(ModEnchantments.PERMANENCE), 1)"))
    }

    @Test
    fun `legacy top level enchantment registrations migrate with source declared register field`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        val registryFile = srcDir.resolve("ModEnchantments.java")
        registryFile.writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENT = DeferredRegister.create(Registries.ENCHANTMENT, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> SPARK = ENCHANTMENT.register("spark", () -> new SparkEnchantment(Enchantment.Rarity.RARE));
            }
        """.trimIndent())
        val enchantmentFile = srcDir.resolve("SparkEnchantment.java")
        enchantmentFile.writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class SparkEnchantment extends Enchantment {
                public SparkEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.WEAPON, new EquipmentSlot[] { EquipmentSlot.MAINHAND });
                }

                @Override
                public int getMaxLevel() {
                    return 2;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)

        val registry = registryFile.readText()
        val generated = tempDir.resolve("src/generated/resources/data/example/enchantment/spark.json")
        assertTrue(result.changes.any { it.ruleId == "custom-enchantment-resourcekey-context" }, result.changes.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "text-custom-enchantment-data" }, result.changes.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "custom-enchantment-remove-top-level-subclass" }, result.changes.joinToString("\n"))
        assertTrue(registry.contains("public static final net.minecraft.resources.ResourceKey<Enchantment> SPARK"), registry)
        assertTrue(registry.contains("java.util.List.of(SPARK)"), registry)
        assertFalse(registry.contains("DeferredRegister<Enchantment> ENCHANTMENT"), registry)
        assertFalse(registry.contains("ENCHANTMENT.register"), registry)
        assertTrue(generated.exists(), "data-driven enchantment JSON should be generated")
        assertFalse(enchantmentFile.exists(), "migrated top-level Enchantment subclass should be removed when unreferenced")
    }

    @Test
    fun `legacy private static enchantment subclass removal uses executable java structure`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                private static final String DOC = "private static class Docs extends Enchantment { }";

                public static boolean stillMigrates(ItemStack stack) {
                    return stack.isEmpty();
                }

                // private static class CommentedEnchantment extends Enchantment { }
                private static final class NestedEnchantment extends net.minecraft.world.item.enchantment.Enchantment {
                    private NestedEnchantment() {
                        super(Rarity.RARE, null, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                    }

                    boolean accepts(ItemStack stack) {
                        if (stack.isEmpty()) {
                            return false;
                        }
                        return "{ not a block }".length() > 0;
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "custom-enchantment-remove-subclasses" }, result.changes.joinToString("\n"))
        assertTrue(transformed.contains("public final class ModEnchantments"))
        assertTrue(transformed.contains("public static boolean stillMigrates(ItemStack stack)"))
        assertFalse(transformed.contains("private static final class NestedEnchantment"), transformed)
        assertFalse(transformed.contains("private NestedEnchantment()"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"private static class Docs extends Enchantment { }\";"))
        assertTrue(transformed.contains("// private static class CommentedEnchantment extends Enchantment { }"))
    }

    @Test
    fun `legacy custom enchantment registrations generate source derived data json and item tags`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModItems.java").writeText("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModItems {
                public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);
                public static final DeferredHolder<Item, Item> BLOCK_AND_CHAIN = ITEMS.register("block_and_chain", () -> new ChainBlockItem());
            }
        """.trimIndent())
        srcDir.resolve("ModEffects.java").writeText("""
            package com.example;

            import net.minecraft.world.effect.MobEffect;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEffects {
                public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ExampleMod.MODID);
                public static final DeferredHolder<MobEffect, MobEffect> FROSTY = EFFECTS.register("frosty", () -> new FrostyEffect());
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> FIRE_REACT = ENCHANTMENTS.register("fire_react", () -> new FireReactEnchantment(Enchantment.Rarity.UNCOMMON));
                public static final DeferredHolder<Enchantment, Enchantment> CHILL_AURA = ENCHANTMENTS.register("chill_aura", () -> new ChillAuraEnchantment(Enchantment.Rarity.UNCOMMON));
                public static final DeferredHolder<Enchantment, Enchantment> DESTRUCTION = ENCHANTMENTS.register("destruction", () -> new DestructionEnchantment(Enchantment.Rarity.RARE));
                public static final DeferredHolder<Enchantment, Enchantment> DURABLE = ENCHANTMENTS.register("durable", () -> new DurableEnchantment(Enchantment.Rarity.COMMON));
                public static final EnchantmentCategory BLOCK_AND_CHAIN = EnchantmentCategory.create("example_block_and_chain", item -> item instanceof ChainBlockItem);
            }
        """.trimIndent())
        srcDir.resolve("LootOnlyEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class LootOnlyEnchantment extends Enchantment {
                protected LootOnlyEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }

                @Override
                public boolean isTradeable() {
                    return false;
                }

                @Override
                public boolean isTreasureOnly() {
                    return true;
                }

                @Override
                public boolean isDiscoverable() {
                    return false;
                }
            }
        """.trimIndent())
        srcDir.resolve("FireReactEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.util.RandomSource;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.item.enchantment.Enchantments;

            public class FireReactEnchantment extends LootOnlyEnchantment {
                public FireReactEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
                }

                @Override
                public boolean canEnchant(ItemStack stack) {
                    return stack.getItem() instanceof ArmorItem || super.canEnchant(stack);
                }

                @Override
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 9;
                }

                @Override
                public int getMaxCost(int level) {
                    return this.getMinCost(level) + 15;
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }

                @Override
                public void doPostHurt(LivingEntity user, Entity attacker, int level) {
                    RandomSource random = user.getRandom();
                    if (attacker != null && shouldHit(level, random, attacker)) {
                        attacker.setSecondsOnFire(2 + (random.nextInt(level) * 3));
                    }
                }

                public static boolean shouldHit(int level, RandomSource random, Entity attacker) {
                    return level > 0 && random.nextFloat() < 0.15F * (float)level;
                }

                @Override
                protected boolean checkCompatibility(Enchantment other) {
                    return super.checkCompatibility(other) && other != ModEnchantments.CHILL_AURA.get() && other != Enchantments.THORNS;
                }
            }
        """.trimIndent())
        srcDir.resolve("ChillAuraEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.util.RandomSource;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.item.enchantment.Enchantments;

            public class ChillAuraEnchantment extends LootOnlyEnchantment {
                public ChillAuraEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
                }

                @Override
                public boolean canEnchant(ItemStack stack) {
                    return stack.getItem() instanceof ArmorItem || super.canEnchant(stack);
                }

                @Override
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 9;
                }

                @Override
                public int getMaxCost(int level) {
                    return this.getMinCost(level) + 15;
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }

                @Override
                public void doPostHurt(LivingEntity user, Entity attacker, int level) {
                    if (attacker instanceof LivingEntity entity) {
                        doChillAuraEffect(entity, 200, level - 1, this.shouldHit(level, user.getRandom()));
                    }
                }

                public static void doChillAuraEffect(LivingEntity victim, int duration, int amplifier, boolean shouldHit) {
                    if (shouldHit) {
                        victim.addEffect(new MobEffectInstance(ModEffects.FROSTY.get(), duration, amplifier));
                    }
                }

                private boolean shouldHit(int level, RandomSource random) {
                    return level > 0 && random.nextFloat() < 0.15F * level;
                }

                @Override
                protected boolean checkCompatibility(Enchantment other) {
                    return super.checkCompatibility(other) && other != ModEnchantments.FIRE_REACT.get() && other != Enchantments.THORNS;
                }
            }
        """.trimIndent())
        srcDir.resolve("DestructionEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.MobType;
            import net.minecraft.world.item.ItemStack;

            public class DestructionEnchantment extends LootOnlyEnchantment {
                public DestructionEnchantment(Rarity rarity) {
                    super(rarity, ModEnchantments.BLOCK_AND_CHAIN, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                }

                @Override
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 9;
                }

                @Override
                public int getMaxCost(int level) {
                    return this.getMinCost(level) + 15;
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }

                @Override
                public boolean canEnchant(ItemStack stack) {
                    return stack.getItem() instanceof ChainBlockItem;
                }

                @Override
                public float getDamageBonus(int level, MobType type, ItemStack item) {
                    return -level * 1.5F;
                }
            }
        """.trimIndent())
        srcDir.resolve("DurableEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class DurableEnchantment extends Enchantment {
                public DurableEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.BREAKABLE, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        val fireReact = tempDir.resolve("src/generated/resources/data/example/enchantment/fire_react.json").readText()
        val chillAura = tempDir.resolve("src/generated/resources/data/example/enchantment/chill_aura.json").readText()
        val destruction = tempDir.resolve("src/generated/resources/data/example/enchantment/destruction.json").readText()
        val durable = tempDir.resolve("src/generated/resources/data/example/enchantment/durable.json").readText()
        val blockAndChainTag = tempDir.resolve("src/generated/resources/data/example/tags/item/enchantable/block_and_chain.json").readText()

        assertTrue(result.changes.any { it.ruleId == "text-custom-enchantment-data" })
        assertTrue(result.changes.any { it.ruleId == "text-custom-enchantment-item-tag" })
        assertTrue(fireReact.contains(""""supported_items": "#minecraft:enchantable/armor""""))
        assertTrue(fireReact.contains(""""type": "minecraft:ignite""""))
        assertTrue(fireReact.contains(""""example:chill_aura""""))
        assertTrue(fireReact.contains(""""minecraft:thorns""""))
        assertTrue(chillAura.contains(""""type": "minecraft:apply_mob_effect""""))
        assertTrue(chillAura.contains(""""to_apply": "example:frosty""""))
        assertTrue(chillAura.contains(""""min_duration": 10.0"""))
        assertTrue(destruction.contains(""""supported_items": "#example:enchantable/block_and_chain""""))
        assertTrue(destruction.contains(""""slots": [
    "hand"
  ]"""))
        assertTrue(destruction.contains(""""type": "minecraft:add""""))
        assertTrue(destruction.contains(""""base": -1.5"""))
        assertTrue(durable.contains(""""supported_items": "#minecraft:enchantable/durability""""))
        assertTrue(durable.contains(""""slots": [
    "mainhand"
  ]"""))
        assertTrue(blockAndChainTag.contains(""""example:block_and_chain""""))
    }

    @Test
    fun `legacy enchantment costs bind to the exact owner and preserve source schedules`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()

        fun writeJava(name: String, source: String) {
            srcDir.resolve(name).writeText("package com.example;\n\n" + source.trimIndent())
        }

        writeJava("ExampleMod.java", """
            final class ExampleMod {
                static final String MODID = "example";
            }
        """)
        writeJava("ModEnchantments.java", """
            final class ModEnchantments {
                static final DeferredRegister<Enchantment> ENCHANTMENTS =
                    DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                static final DeferredHolder<Enchantment, Enchantment> DEFAULT_COST =
                    ENCHANTMENTS.register("default_cost", DefaultCostEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> MIN_ONLY =
                    ENCHANTMENTS.register("min_only", MinOnlyEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> EXPLICIT_COST =
                    ENCHANTMENTS.register("explicit_cost", ExplicitCostEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> INLINE_OWNER =
                    ENCHANTMENTS.register("inline_owner", InlineOwnerEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> RENAMED_PARAMETER =
                    ENCHANTMENTS.register("renamed_parameter", RenamedParameterEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> INHERITED_CUSTOM =
                    ENCHANTMENTS.register("inherited_custom", InheritedCustomCostEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> UNKNOWN_PARENT =
                    ENCHANTMENTS.register("unknown_parent", UnknownParentEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> NONLINEAR =
                    ENCHANTMENTS.register("nonlinear", NonlinearEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> PARTIAL_LINEAR =
                    ENCHANTMENTS.register("partial_linear", PartialLinearEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> SIDE_EFFECT =
                    ENCHANTMENTS.register("side_effect", SideEffectEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> BRANCH_RETURNS =
                    ENCHANTMENTS.register("branch_returns", BranchReturnsEnchantment::new);
                static final DeferredHolder<Enchantment, Enchantment> UNKNOWN_SYNTAX =
                    ENCHANTMENTS.register("unknown_syntax", UnknownSyntaxEnchantment::new);
            }
        """)
        writeJava("DefaultCostEnchantment.java", """
            final class DefaultCostEnchantment extends Enchantment {
                DefaultCostEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }
            }
        """)
        writeJava("MinOnlyEnchantment.java", """
            final class MinOnlyEnchantment extends Enchantment {
                MinOnlyEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 9;
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }
            }
        """)
        writeJava("ExplicitCostEnchantment.java", """
            final class ExplicitCostEnchantment extends Enchantment {
                ExplicitCostEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 9;
                }

                @Override
                public int getMaxCost(int level) {
                    return this.getMinCost(level) + 15;
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }
            }
        """)
        writeJava("InlineOwnerEnchantment.java", """
            public final class InlineOwnerEnchantment extends Enchantment {
                InlineOwnerEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override public int getMinCost(int level) {
                    return 7 + (level - 1) * 4;
                }

                public int getMinCost(String ignored) {
                    return 999;
                }
            }

            final class CostDistractor extends Enchantment {
                CostDistractor() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override
                public int getMinCost(int level) {
                    return 90 + (level - 1) * 2;
                }
            }
        """)
        writeJava("RenamedParameterEnchantment.java", """
            final class RenamedParameterEnchantment extends Enchantment {
                RenamedParameterEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override
                public synchronized final int getMinCost(final int pLevel) throws IllegalStateException {
                    return 6 + (pLevel - 1) * 8;
                }

                @Override
                public int getMaxCost(int experienceLevel) {
                    return this.getMinCost(experienceLevel) + 12;
                }
            }
        """)
        writeJava("ParentCostEnchantment.java", """
            abstract class ParentCostEnchantment extends Enchantment {
                ParentCostEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }

                @Override
                public int getMinCost(int parentLevel) {
                    return 9 + (parentLevel - 1) * 6;
                }

                @Override
                public int getMaxCost(int parentLevel) {
                    return getMinCost(parentLevel) + 14;
                }
            }
        """)
        writeJava("InheritedCustomCostEnchantment.java", """
            final class InheritedCustomCostEnchantment extends ParentCostEnchantment {
                InheritedCustomCostEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }
            }
        """)
        writeJava("UnknownParentEnchantment.java", """
            final class UnknownParentEnchantment extends ExternalCostEnchantment {
                UnknownParentEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }
            }
        """)
        writeJava("NonlinearEnchantment.java", """
            final class NonlinearEnchantment extends Enchantment {
                NonlinearEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override public int getMinCost(int costLevel) {
                    return costLevel * costLevel + 3;
                }
            }
        """)
        writeJava("PartialLinearEnchantment.java", """
            final class PartialLinearEnchantment extends Enchantment {
                PartialLinearEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override public int getMinCost(int level) {
                    return level * 2 + level * level;
                }
            }
        """)
        writeJava("SideEffectEnchantment.java", """
            final class SideEffectEnchantment extends Enchantment {
                SideEffectEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override public int getMinCost(int level) {
                    CostAudit.capture(level);
                    return 4 + (level - 1) * 7;
                }
            }
        """)
        writeJava("BranchReturnsEnchantment.java", """
            final class BranchReturnsEnchantment extends Enchantment {
                BranchReturnsEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override public int getMinCost(int level) {
                    if (level > 1) {
                        return 8 + (level - 1) * 3;
                    }
                    return 4 + (level - 1) * 7;
                }
            }
        """)
        writeJava("UnknownSyntaxEnchantment.java", """
            final class UnknownSyntaxEnchantment extends Enchantment {
                UnknownSyntaxEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR,
                        new EquipmentSlot[]{EquipmentSlot.CHEST});
                }

                @Override public int getMinCost(int level) {
                    return level * 2 future_cost 7;
                }
            }
        """)

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)
        assertTrue(
            result.errors.any {
                it.contains("custom enchantment 'nonlinear' has an unsupported getMinCost(int level) override")
            },
            result.errors.joinToString("\n")
        )
        listOf("partial_linear", "side_effect", "branch_returns").forEach { registryName ->
            assertTrue(
                result.errors.any {
                    it.contains(
                        "custom enchantment '$registryName' has an unsupported " +
                            "getMinCost(int level) override"
                    )
                },
                result.errors.joinToString("\n")
            )
        }
        assertTrue(
            result.errors.any {
                it.contains(
                    "custom enchantment 'unknown_syntax' cannot parse " +
                        "getMinCost(int) declarations"
                )
            },
            result.errors.joinToString("\n")
        )
        assertTrue(
            result.errors.any {
                it.contains(
                    "custom enchantment 'unknown_parent' has unresolved cost parent " +
                        "'ExternalCostEnchantment'"
                )
            },
            result.errors.joinToString("\n")
        )

        val generatedRoot = tempDir.resolve("src/generated/resources/data/example/enchantment")
        val defaultJson = generatedRoot.resolve("default_cost.json").readText()
        val minOnlyJson = generatedRoot.resolve("min_only.json").readText()
        val explicitJson = generatedRoot.resolve("explicit_cost.json").readText()
        val inlineOwnerJson = generatedRoot.resolve("inline_owner.json").readText()
        val renamedParameterJson = generatedRoot.resolve("renamed_parameter.json").readText()
        val inheritedCustomJson = generatedRoot.resolve("inherited_custom.json").readText()

        fun generatedCost(json: String, field: String, level: Int): Int {
            val start = json.indexOf("\"$field\"")
            assertTrue(start >= 0, "$field missing from $json")
            val end = json.indexOf('}', start)
            assertTrue(end > start, "$field block is incomplete in $json")
            val block = json.substring(start, end + 1)
            val base = Regex("\"base\"\\s*:\\s*(-?\\d+)")
                .find(block)?.groupValues?.get(1)?.toInt()
                ?: error("$field base is missing: $block")
            val perLevel = Regex("\"per_level_above_first\"\\s*:\\s*(-?\\d+)")
                .find(block)?.groupValues?.get(1)?.toInt()
                ?: error("$field per-level term is missing: $block")
            return base + perLevel * (level - 1)
        }

        for (level in 1..3) {
            val inheritedSourceMin = 1 + level * 10
            assertEquals(inheritedSourceMin, generatedCost(defaultJson, "min_cost", level))
            assertEquals(inheritedSourceMin + 5, generatedCost(defaultJson, "max_cost", level))

            val minOnlySource = 5 + (level - 1) * 9
            assertEquals(minOnlySource, generatedCost(minOnlyJson, "min_cost", level))
            assertEquals(minOnlySource + 5, generatedCost(minOnlyJson, "max_cost", level))
            assertEquals(minOnlySource, generatedCost(explicitJson, "min_cost", level))
            assertEquals(minOnlySource + 15, generatedCost(explicitJson, "max_cost", level))

            val inlineOwnerSource = 7 + (level - 1) * 4
            assertEquals(inlineOwnerSource, generatedCost(inlineOwnerJson, "min_cost", level))
            assertEquals(inlineOwnerSource + 5, generatedCost(inlineOwnerJson, "max_cost", level))

            val renamedParameterSource = 6 + (level - 1) * 8
            assertEquals(renamedParameterSource, generatedCost(renamedParameterJson, "min_cost", level))
            assertEquals(renamedParameterSource + 12, generatedCost(renamedParameterJson, "max_cost", level))

            val inheritedCustomSource = 9 + (level - 1) * 6
            assertEquals(inheritedCustomSource, generatedCost(inheritedCustomJson, "min_cost", level))
            assertEquals(inheritedCustomSource + 14, generatedCost(inheritedCustomJson, "max_cost", level))
        }
        assertFalse(generatedRoot.resolve("nonlinear.json").exists())
        assertFalse(generatedRoot.resolve("unknown_parent.json").exists())
        assertFalse(generatedRoot.resolve("partial_linear.json").exists())
        assertFalse(generatedRoot.resolve("side_effect.json").exists())
        assertFalse(generatedRoot.resolve("branch_returns.json").exists())
        assertFalse(generatedRoot.resolve("unknown_syntax.json").exists())
    }

    @Test
    fun `custom enchantment resource keys infer mod id expression from source`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class ExampleMod {
                static final String MODID = "example";
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        ExampleMod.MODID
                );
                private static final EnchantmentCategory MAGIC = EnchantmentCategory.create(ExampleMod.MODID + "_magic", item -> true);
                public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);

                public static boolean hasFlame(Level level, ItemStack stack) {
                    return stack.getEnchantmentLevel(FLAME.get()) > 0;
                }

                public static boolean isFlame(Enchantment enchantment) {
                    return enchantment == FLAME.get();
                }

                private static class FlameEnchantment extends Enchantment {
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, \"flame\")"))
        assertTrue(transformed.contains("stack.getEnchantmentLevel(holder(level, FLAME))"))
        assertTrue(transformed.contains("public static boolean isFlame(net.minecraft.core.Holder<Enchantment> enchantment)"))
        assertTrue(transformed.contains("return enchantment.is(FLAME);"))
        assertFalse(transformed.contains("InstantWorldMirror.MODID"))
        assertFalse(transformed.contains("DeferredHolder<Enchantment, Enchantment> FLAME"))
    }

    @Test
    fun `custom enchantment data rejects unresolved qualified mod id expression`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class ExampleMod {
                static final String MODID = "example";
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        MissingMod.MODID
                );
                public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);

                private static class FlameEnchantment extends Enchantment {
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any {
                it.contains("unresolved mod id expression 'MissingMod.MODID'")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/flame.json").exists())
    }

    @Test
    fun `custom enchantment data does not infer mod id owner from java file names`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public static final String MODID = "example";

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        TestMod.MODID
                );
                public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);
            }

            class FlameEnchantment extends Enchantment {
                public FlameEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.CHEST});
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any { it.contains("unresolved mod id expression 'TestMod.MODID'") },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/flame.json").exists())
    }

    @Test
    fun `custom enchantment data ignores mod ids declared only in text blocks`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ExampleDocs {
                private static final String README = ${"\"\"\""}
                    public static final String MODID = "example";
                    ${"\"\"\""};
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        ExampleDocs.MODID
                );
                public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);

                private static class FlameEnchantment extends Enchantment {
                    private FlameEnchantment() {
                        super(Rarity.COMMON, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.CHEST});
                    }
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any { it.contains("unresolved mod id expression 'ExampleDocs.MODID'") },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/flame.json").exists())
    }

    @Test
    fun `custom enchantment data resolves unqualified mod id from declaring java type`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final String MODID = "example";
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        MODID
                );
                public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);

                private static class FlameEnchantment extends Enchantment {
                    private FlameEnchantment() {
                        super(Rarity.COMMON, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.CHEST});
                    }
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "text-custom-enchantment-data" })
        assertTrue(tempDir.resolve("src/generated/resources/data/example/enchantment/flame.json").exists())
    }

    @Test
    fun `custom enchantment data does not resolve bare mod id by global uniqueness`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public static final String MODID = "example";

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        MODID
                );
                public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);

                private static class FlameEnchantment extends Enchantment {
                    private FlameEnchantment() {
                        super(Rarity.COMMON, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.CHEST});
                    }
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any { it.contains("unresolved mod id expression 'MODID'") },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/flame.json").exists())
    }

    @Test
    fun `custom enchantment data rejects registry reference tail fallback`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.util.RandomSource;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class ExampleMod {
                static final String MODID = "example";
            }

            final class ModEffects {
                public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ExampleMod.MODID);
                public static final DeferredHolder<MobEffect, MobEffect> FROSTY = EFFECTS.register("frosty", FrostyEffect::new);
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> CHILL_AURA = ENCHANTMENTS.register("chill_aura", () -> new ChillAuraEnchantment(Enchantment.Rarity.UNCOMMON));
            }

            class FrostyEffect extends MobEffect {
            }

            class ChillAuraEnchantment extends Enchantment {
                public ChillAuraEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
                }

                @Override
                public void doPostHurt(LivingEntity user, Entity attacker, int level) {
                    if (attacker instanceof LivingEntity entity) {
                        doChillAuraEffect(entity, 200, level - 1, this.shouldHit(level, user.getRandom()));
                    }
                }

                public static void doChillAuraEffect(LivingEntity victim, int duration, int amplifier, boolean shouldHit) {
                    if (shouldHit) {
                        victim.addEffect(new MobEffectInstance(MissingEffects.FROSTY.get(), duration, amplifier));
                    }
                }

                private boolean shouldHit(int level, RandomSource random) {
                    return level > 0 && random.nextFloat() < 0.15F * level;
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any { it.contains("mob effect reference 'MissingEffects.FROSTY' is unresolved") },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/chill_aura.json").exists())
    }

    @Test
    fun `custom enchantment data ignores post hurt helper calls in text blocks`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.util.RandomSource;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class ExampleMod {
                static final String MODID = "example";
            }

            final class ModEffects {
                public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ExampleMod.MODID);
                public static final DeferredHolder<MobEffect, MobEffect> FROSTY = EFFECTS.register("frosty", FrostyEffect::new);
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> CHILL_AURA = ENCHANTMENTS.register("chill_aura", () -> new ChillAuraEnchantment(Enchantment.Rarity.UNCOMMON));
            }

            class FrostyEffect extends MobEffect {
            }

            class ChillAuraEnchantment extends Enchantment {
                public ChillAuraEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
                }

                @Override
                public void doPostHurt(LivingEntity user, Entity attacker, int level) {
                    String docs = ${"\"\"\""}
                        doChillAuraEffect(entity, 200, level - 1, this.shouldHit(level, user.getRandom()));
                        ${"\"\"\""};
                    if (attacker instanceof LivingEntity entity && this.shouldHit(level, user.getRandom())) {
                        entity.setSecondsOnFire(4);
                    }
                }

                public static void doChillAuraEffect(LivingEntity victim, int duration, int amplifier, boolean shouldHit) {
                    if (shouldHit) {
                        victim.addEffect(new MobEffectInstance(ModEffects.FROSTY.get(), duration, amplifier));
                    }
                }

                private boolean shouldHit(int level, RandomSource random) {
                    return level > 0 && random.nextFloat() < 0.15F * level;
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val chillAura = tempDir.resolve("src/generated/resources/data/example/enchantment/chill_aura.json").readText()
        assertTrue(chillAura.contains(""""type": "minecraft:ignite""""), chillAura)
        assertFalse(chillAura.contains(""""type": "minecraft:apply_mob_effect""""), chillAura)
        assertFalse(chillAura.contains(""""to_apply": "example:frosty""""), chillAura)
    }

    @Test
    fun `custom enchantment data requires executable random chance evidence`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class ExampleMod {
                static final String MODID = "example";
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> FIRE_REACT = ENCHANTMENTS.register("fire_react", () -> new FireReactEnchantment(Enchantment.Rarity.UNCOMMON));
            }

            class FireReactEnchantment extends Enchantment {
                public FireReactEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
                }

                @Override
                public void doPostHurt(LivingEntity user, Entity attacker, int level) {
                    String docs = ${"\"\"\""}
                        return level > 0 && random.nextFloat() < 0.15F * level;
                        ${"\"\"\""};
                    if (attacker instanceof LivingEntity entity) {
                        entity.setSecondsOnFire(4);
                    }
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any { it.contains("doPostHurt body has no supported effect shape") },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/fire_react.json").exists())
    }

    @Test
    fun `custom enchantment data rejects bare category references from other owners`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModItems.java").writeText("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModItems {
                public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);
                public static final DeferredHolder<Item, Item> BLOCK_AND_CHAIN = ITEMS.register("block_and_chain", () -> new ChainBlockItem());
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> DESTRUCTION = ENCHANTMENTS.register("destruction", () -> new DestructionEnchantment(Enchantment.Rarity.RARE));
                public static final EnchantmentCategory BLOCK_AND_CHAIN = EnchantmentCategory.create("example_block_and_chain", item -> item instanceof ChainBlockItem);
            }
        """.trimIndent())
        srcDir.resolve("DestructionEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;

            public class DestructionEnchantment extends Enchantment {
                public DestructionEnchantment(Rarity rarity) {
                    super(rarity, BLOCK_AND_CHAIN, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(
            result.errors.any { it.contains("item support expression 'BLOCK_AND_CHAIN' is unresolved") },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/destruction.json").exists())
    }

    @Test
    fun `custom enchantment data rejects invisible supplier classes by simple name`() {
        val exampleDir = tempDir.resolve("src/main/java/com/example")
        val otherDir = tempDir.resolve("src/main/java/com/other")
        exampleDir.createDirectories()
        otherDir.createDirectories()
        exampleDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        exampleDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> GHOST = ENCHANTMENTS.register("ghost", GhostEnchantment::new);
            }
        """.trimIndent())
        otherDir.resolve("GhostEnchantment.java").writeText("""
            package com.other;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class GhostEnchantment extends Enchantment {
                public GhostEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.CHEST});
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(
            result.errors.any { it.contains("class reference 'GhostEnchantment' is unresolved") },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/ghost.json").exists())
    }

    @Test
    fun `custom enchantment data ignores supplier classes declared only in text blocks`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> GHOST = ENCHANTMENTS.register("ghost", GhostEnchantment::new);
            }
        """.trimIndent())
        srcDir.resolve("Docs.java").writeText("""
            package com.example;

            public final class Docs {
                private static final String README = ${"\"\"\""}
                    import net.minecraft.world.entity.EquipmentSlot;
                    import net.minecraft.world.item.enchantment.Enchantment;
                    import net.minecraft.world.item.enchantment.EnchantmentCategory;

                    public class GhostEnchantment extends Enchantment {
                        public GhostEnchantment() {
                            super(Rarity.COMMON, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.CHEST});
                        }
                    }
                    ${"\"\"\""};
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(
            result.errors.any { it.contains("class reference 'GhostEnchantment' is unresolved") },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/ghost.json").exists())
    }

    @Test
    fun `custom enchantment data ignores registrations declared only in text blocks`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("FlameEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class FlameEnchantment extends Enchantment {
                public FlameEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.CHEST});
                }
            }
        """.trimIndent())
        srcDir.resolve("Docs.java").writeText("""
            package com.example;

            public final class Docs {
                private static final String README = ${"\"\"\""}
                    import net.minecraft.world.item.enchantment.Enchantment;
                    import net.neoforged.neoforge.registries.DeferredHolder;
                    import net.neoforged.neoforge.registries.DeferredRegister;

                    public final class ModEnchantments {
                        public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                        public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);
                    }
                    ${"\"\"\""};
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)
        val docs = srcDir.resolve("Docs.java").readText()

        assertFalse(result.changes.any { it.ruleId == "text-custom-enchantment-data" }, result.changes.joinToString("\n"))
        assertTrue(docs.contains("DeferredHolder<Enchantment, Enchantment> FLAME"))
        assertFalse(docs.contains("ResourceKey<Enchantment>"))
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/flame.json").exists())
    }

    @Test
    fun `custom enchantment data ignores item registry entries declared only in text blocks`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final EnchantmentCategory BLOCK_AND_CHAIN = EnchantmentCategory.create("example_block_and_chain", item -> item instanceof ChainBlockItem);
                public static final DeferredHolder<Enchantment, Enchantment> DESTRUCTION = ENCHANTMENTS.register("destruction", DestructionEnchantment::new);
            }

            class DestructionEnchantment extends Enchantment {
                DestructionEnchantment() {
                    super(Rarity.RARE, ModEnchantments.BLOCK_AND_CHAIN, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                }
            }

            class ChainBlockItem {
            }
        """.trimIndent())
        srcDir.resolve("Docs.java").writeText("""
            package com.example;

            public final class Docs {
                private static final String README = ${"\"\"\""}
                    import net.minecraft.world.item.Item;
                    import net.neoforged.neoforge.registries.DeferredHolder;
                    import net.neoforged.neoforge.registries.DeferredRegister;

                    public final class ModItems {
                        public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);
                        public static final DeferredHolder<Item, Item> BLOCK_AND_CHAIN = ITEMS.register("block_and_chain", () -> new ChainBlockItem());
                    }
                    ${"\"\"\""};
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(
            result.errors.any {
                it.contains("item class 'com.example.ChainBlockItem' has no source registry entry")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(tempDir.resolve("src/generated/resources/data/example/enchantment/destruction.json").exists())
        assertFalse(tempDir.resolve("src/generated/resources/data/example/tags/item/enchantable/destruction.json").exists())
    }

    @Test
    fun `custom enchantment data ignores constructor and item support examples in text blocks`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModItems.java").writeText("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModItems {
                public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);
                public static final DeferredHolder<Item, Item> CHAIN_BLOCK = ITEMS.register("chain_block", () -> new ChainBlockItem());
            }

            class ChainBlockItem {
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);
            }
        """.trimIndent())
        srcDir.resolve("FlameEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class FlameEnchantment extends Enchantment {
                private static final String README = ${"\"\"\""}
                    public FlameEnchantment() {
                        super(Rarity.RARE, EnchantmentCategory.WEAPON, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                    }

                    @Override
                    public boolean canEnchant(ItemStack stack) {
                        return stack.getItem() instanceof ChainBlockItem;
                    }
                    ${"\"\"\""};

                public FlameEnchantment() {
                    super(Rarity.COMMON, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.CHEST});
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val flame = tempDir.resolve("src/generated/resources/data/example/enchantment/flame.json").readText()
        assertTrue(flame.contains(""""supported_items": "#minecraft:enchantable/chest_armor""""), flame)
        assertFalse(flame.contains("#example:enchantable/flame"), flame)
        assertFalse(tempDir.resolve("src/generated/resources/data/example/tags/item/enchantable/flame.json").exists())
    }

    @Test
    fun `legacy enchantment category runtime checks migrate to holder item support`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            public class TestMod {
                public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
                    return this.canApplyEnchantment(EnchantmentHelper.getEnchantments(stack).keySet().toArray(new Enchantment[0]));
                }

                public boolean supportsEnchantment(ItemStack stack, net.minecraft.core.Holder<Enchantment> enchantment) {
                    return this.canApplyEnchantment(enchantment);
                }

                private boolean canApplyEnchantment(Enchantment... enchantments) {
                    for (Enchantment enchantment : enchantments) {
                        if (enchantment.category == EnchantmentCategory.DIGGER || enchantment.canEnchant(Items.IRON_AXE.getDefaultInstance()))
                            return true;
                    }
                    return false;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("EnchantmentCategory"))
        assertFalse(transformed.contains("new Enchantment[0]"))
        assertFalse(transformed.contains(".category"))
        assertTrue(transformed.contains("this.canApplyEnchantment(EnchantmentHelper.getEnchantments(stack).keySet())"))
        assertTrue(transformed.contains("private boolean canApplyEnchantment(Iterable<net.minecraft.core.Holder<Enchantment>> enchantments)"))
        assertTrue(transformed.contains("Items.IRON_AXE.getDefaultInstance().supportsEnchantment(enchantment)"))
        assertTrue(transformed.contains("private boolean canApplyEnchantment(net.minecraft.core.Holder<Enchantment> enchantment)"))
    }

    @Test
    fun `legacy enchantment category runtime migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            public class TestMod {
                private static final String DOC = "EnchantmentHelper.getEnchantments(stack).keySet().toArray(new Enchantment[0])";

                /*
                 private boolean canApplyFake(Enchantment... enchantments) {
                     for (Enchantment enchantment : enchantments) {
                         if (enchantment.category == EnchantmentCategory.WEAPON || enchantment.canEnchant(DUMMY_STACK))
                             return true;
                     }
                     return false;
                 }
                 */

                public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
                    return this.canApplyEnchantment(EnchantmentHelper.getEnchantments(stack).keySet().toArray(new Enchantment[0]));
                }

                private boolean canApplyEnchantment(Enchantment... enchantments) {
                    for (Enchantment enchantment : enchantments) {
                        if (enchantment.category == EnchantmentCategory.DIGGER || enchantment.canEnchant(Items.IRON_AXE.getDefaultInstance()))
                            return true;
                    }
                    return false;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("this.canApplyEnchantment(EnchantmentHelper.getEnchantments(stack).keySet())"), transformed)
        assertTrue(transformed.contains("Items.IRON_AXE.getDefaultInstance().supportsEnchantment(enchantment)"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"EnchantmentHelper.getEnchantments(stack).keySet().toArray(new Enchantment[0])\";"), transformed)
        assertTrue(transformed.contains("enchantment.category == EnchantmentCategory.WEAPON"), transformed)
        assertTrue(transformed.contains("enchantment.canEnchant(DUMMY_STACK)"), transformed)
        assertFalse(transformed.contains("DOC = \"EnchantmentHelper.getEnchantments(stack).keySet()\""), transformed)
        assertFalse(transformed.contains("DUMMY_STACK.supportsEnchantment(enchantment)"), transformed)
    }

    @Test
    fun `jei mobtype and unqualified properties rules migrate common 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import mezz.jei.api.forge.ForgeTypes;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.MobType;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.BushBlock;

            public class TestMod extends BushBlock {
                public TestMod() {
                    super(Properties.copy(Blocks.BAMBOO_SAPLING));
                }

                public boolean isIllager(LivingEntity entity) {
                    return entity.getMobType() == MobType.ILLAGER;
                }

                public Object fluidType() {
                    return ForgeTypes.FLUID_STACK;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import mezz.jei.api.neoforge.NeoForgeTypes;"))
        assertTrue(transformed.contains("import net.minecraft.tags.EntityTypeTags;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.block.state.BlockBehaviour;"))
        assertTrue(transformed.contains("super(BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_SAPLING));"))
        assertTrue(transformed.contains("entity.getType().is(EntityTypeTags.ILLAGER)"))
        assertTrue(transformed.contains("NeoForgeTypes.FLUID_STACK"))
        assertTrue(!transformed.contains("mezz.jei.api.forge"))
        assertTrue(!transformed.contains("return ForgeTypes."))
        assertTrue(!transformed.contains("import net.minecraft.world.entity.MobType;"))
        assertTrue(!transformed.contains("getMobType() == MobType"))
        assertTrue(!transformed.contains("Properties.copy"))
    }

    @Test
    fun `removed 1_21 entity and block helper APIs migrate without placeholders`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobType;
            import net.minecraft.world.entity.SpawnPlacements;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.grower.AbstractTreeGrower;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.items.IItemHandler;
            import net.neoforged.neoforge.items.wrapper.EmptyHandler;

            public class TestMod {
                AbstractTreeGrower grower;

                @Override
                public MobType getMobType() {
                    return MobType.UNDEAD;
                }

                protected IItemHandler createUnSidedHandler() {
                    return new EmptyHandler();
                }

                public boolean isValidSpawn(BlockState state, BlockGetter level, BlockPos pos, SpawnPlacements.Type type, EntityType<?> entityType) {
                    return false;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.level.block.grower.TreeGrower;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;"))
        assertTrue(transformed.contains("TreeGrower grower;"))
        assertTrue(transformed.contains("return EmptyItemHandler.INSTANCE;"))
        assertTrue(transformed.contains("isValidSpawn(BlockState state, BlockGetter level, BlockPos pos, EntityType<?> entityType)"))
        assertFalse(transformed.contains("AbstractTreeGrower"))
        assertFalse(transformed.contains("EmptyHandler"))
        assertFalse(transformed.contains("public MobType getMobType()"))
        assertFalse(transformed.contains("SpawnPlacements.Type"))
        assertFalse(transformed.contains("TODO"))
        assertFalse(transformed.contains("[forge2neo]"))
    }

    @Test
    fun `custom registries shaped recipes and registry object parameters migrate to real 1_21 types`() {
        val projectDir = createTestFile("""
            package com.example;

            import java.util.function.Supplier;
            import net.minecraft.core.Registry;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.SpawnPlacements;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.level.block.ChestBlock;
            import net.minecraft.world.item.crafting.ShapedRecipe;
            import net.neoforged.neoforge.event.entity.SpawnPlacementRegisterEvent;
            import net.neoforged.neoforge.common.crafting.IShapedRecipe;
            import net.neoforged.neoforge.registries.IForgeRegistry;
            import net.neoforged.neoforge.registries.RegistryObject;

            class TestMod {
                Supplier<IForgeRegistry<Foo>> REGISTRY;

                void recipe(Object recipe) {
                    if (recipe instanceof IShapedRecipe<?> rec) {
                        int width = rec.getRecipeWidth();
                        int height = rec.getRecipeHeight();
                    }
                }

                void make(RegistryObject<? extends ChestBlock> block) {
                    block.get();
                }

                void addEntityAndEgg(RegistryObject<? extends EntityType<?>> entity) {
                    entity.get();
                }

                void makeCustom(RegistryObject<? extends Foo> foo) {
                    foo.get();
                }

                void registerSpawnPlacements(SpawnPlacementRegisterEvent event, EntityType<?> type) {
                    event.register(type, SpawnPlacements.Type.ON_GROUND, null, null, SpawnPlacementRegisterEvent.Operation.REPLACE);
                }
            }

            record UncraftingRecipe() implements CraftingRecipe, IShapedRecipe<CraftingInput> {
                @Override
                public int getRecipeWidth() {
                    return 3;
                }

                @Override
                public int getRecipeHeight() {
                    return 3;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("Supplier<Registry<Foo>> REGISTRY;"))
        assertTrue(transformed.contains("recipe instanceof ShapedRecipe rec"))
        assertTrue(transformed.contains("rec.getWidth()"))
        assertTrue(transformed.contains("rec.getHeight()"))
        assertTrue(transformed.contains("void make(Supplier<? extends ChestBlock> block)"))
        assertTrue(transformed.contains("void addEntityAndEgg(DeferredHolder<EntityType<?>, ? extends EntityType<?>> entity)"))
        assertTrue(transformed.contains("void makeCustom(DeferredHolder<Foo, ? extends Foo> foo)"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;"))
        assertTrue(transformed.contains("import net.minecraft.world.entity.SpawnPlacementTypes;"))
        assertTrue(transformed.contains("void registerSpawnPlacements(RegisterSpawnPlacementsEvent event, EntityType<?> type)"))
        assertTrue(transformed.contains("SpawnPlacementTypes.ON_GROUND"))
        assertTrue(transformed.contains("RegisterSpawnPlacementsEvent.Operation.REPLACE"))
        assertTrue(transformed.contains("record UncraftingRecipe() implements CraftingRecipe"))
        assertTrue(transformed.contains("public int getWidth()"))
        assertTrue(transformed.contains("public int getHeight()"))
        assertFalse(transformed.contains("IForgeRegistry"))
        assertFalse(transformed.contains("IShapedRecipe"))
        assertFalse(transformed.contains("RegistryObject"))
        assertFalse(Regex("""@Override\s*\r?\n\s*public int get(?:Width|Height)\(""").containsMatchIn(transformed))
        assertFalse(transformed.contains("TODO"))
        assertFalse(transformed.contains("[forge2neo]"))
    }

    @Test
    fun `bonemeal and pathfind signatures migrate when split across lines`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod {
                public static final DeferredHolder<RotatedPillarBlock, RotatedPillarBlock> LOG = BLOCKS
                        .register("log", MapleLogBlock::new);

                public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos,
                        PathComputationType type) {
                    return false;
                }

                public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state,
                        boolean isClient) {
                    return true;
                }

                public void call(BonemealableBlock growable, LevelReader level, BlockPos pos, BlockState top) {
                    growable.isValidBonemealTarget(level, pos.above(), top, false);
                }

                public void tick(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
                    if (CommonHooks.onCropsGrowPre(level, pos, state, random.nextInt(3) == 0)) {
                        level.setBlock(pos, state, 2);
                        CommonHooks.onCropsGrowPost(level, pos, state);
                    }
                    Object props = BlockBehaviour.Properties
                            .copy(Blocks.OAK_LOG);
                    Object stair = new net.minecraft.world.level.block.StairBlock(() -> LOG.get().defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_STAIRS));
                    Object door = new net.minecraft.world.level.block.DoorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_DOOR), net.minecraft.world.level.block.state.properties.BlockSetType.BAMBOO);
                    Object bush = new net.minecraft.world.level.block.BushBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.PLANT).noCollission().instabreak().sound(SoundType.GRASS).randomTicks());
                }

                public boolean canPlaceLiquid(BlockGetter world, BlockPos pos, BlockState state, Fluid fluid) {
                    return false;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("DeferredHolder<Block, RotatedPillarBlock> LOG = BLOCKS"))
        assertTrue(transformed.contains(".register(\"log\", () -> new MapleLogBlock())"))
        assertTrue(transformed.contains("public boolean isPathfindable(BlockState state, PathComputationType type)"))
        assertTrue(transformed.contains("public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state)"))
        assertTrue(transformed.contains("growable.isValidBonemealTarget(level, pos.above(), top);"))
        assertTrue(transformed.contains("net.neoforged.neoforge.common.CommonHooks.canCropGrow(level, pos, state, random.nextInt(3) == 0)"))
        assertTrue(transformed.contains("net.neoforged.neoforge.common.CommonHooks.fireCropGrowPost(level, pos, state);"))
        assertTrue(transformed.contains("BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)"))
        assertTrue(transformed.contains("new net.minecraft.world.level.block.StairBlock(LOG.get().defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_STAIRS))"))
        assertTrue(transformed.contains("new net.minecraft.world.level.block.DoorBlock(net.minecraft.world.level.block.state.properties.BlockSetType.BAMBOO, BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_DOOR))"))
        assertTrue(transformed.contains("protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.BushBlock> codec()"))
        assertTrue(transformed.contains("return com.mojang.serialization.MapCodec.unit(this);"))
        assertTrue(transformed.contains("public boolean canPlaceLiquid(net.minecraft.world.entity.player.Player player, BlockGetter world, BlockPos pos, BlockState state, Fluid fluid)"))
        assertTrue(!transformed.contains("boolean isClient"))
        assertTrue(!transformed.contains(", false)"))
        assertTrue(!transformed.contains("onCropsGrowPost removed"))
    }

    @Test
    fun `registry object wildcard holder migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.neoforged.neoforge.registries.RegistryObject;

            public class TestMod {
                // RegistryObject<? extends Foo> docs;
                private static final String DOC = "RegistryObject<? extends Foo>";

                void make(RegistryObject<? extends Foo> foo) {
                    foo.get();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("void make(DeferredHolder<Foo, ? extends Foo> foo)"), transformed)
        assertTrue(transformed.contains("// RegistryObject<? extends Foo> docs;"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"RegistryObject<? extends Foo>\";"), transformed)
        assertFalse(transformed.contains("// DeferredHolder<Foo, ? extends Foo> docs;"), transformed)
        assertFalse(transformed.contains("DOC = \"DeferredHolder<Foo, ? extends Foo>\""), transformed)
    }

    @Test
    fun `recipe holder network open and fuel APIs migrate to 1_21 surfaces`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.SimpleContainer;
            import net.minecraft.world.Container;
            import net.neoforged.neoforge.network.NetworkHooks;
            import net.neoforged.neoforge.items.ItemHandlerHelper;

            public class TestMod {
                private ResourceLocation lastRecipeID;

                public static final DeferredHolder<MenuType<StoneMortarContainer>, MenuType<StoneMortarContainer>> STONE_MORTAR =
                        CONTAINER_TYPES.register("stone_mortar", () -> IMenuTypeExtension.create(StoneMortarContainer::new));

                private static <C extends Container, T extends Recipe<C>> List<T> findRecipesByType(RecipeType<T> type) {
                    return MC.level.getRecipeManager().getAllRecipesFor(type);
                }

                public Optional<SmeltingRecipe> vanilla(ItemStack stack, Level level) {
                    SimpleContainer container = new SimpleContainer(stack.copy());
                    Optional<SmeltingRecipe> recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, container, level);
                    return recipe;
                }

                public List<CookingPotRecipe> customList(Level level, RecipeWrapper wrapper) {
                    List<CookingPotRecipe> recipes = level.getRecipeManager()
                            .getRecipesFor(RecipeTypeRegistry.COOKING_RECIPE_TYPE.get(), wrapper, level);
                    return recipes;
                }

                public void cached(Level level) {
                    Optional<? extends Recipe<RecipeWrapper>> recipeOpt = level.getRecipeManager()
                            .getAllRecipesFor(RecipeTypeRegistry.COOKING_RECIPE_TYPE.get()).stream()
                            .filter(now -> now.getId().equals(lastRecipeID)).findFirst();
                    if (recipeOpt.isPresent()) {
                        Recipe<RecipeWrapper> recipe = recipeOpt.get();
                    }
                }

                public void experience(Level world, Object2IntMap.Entry<ResourceLocation> entry) {
                    world.getRecipeManager().byKey(entry.getKey()).ifPresent(recipe -> use(((CookingPotRecipe) recipe).getExperience()));
                }

                public void open(ServerPlayer player, MenuProvider menu, BlockPos pos) {
                    NetworkHooks.openScreen(player, menu, pos);
                }

                public int burn(ItemStack stack) {
                    return CommonHooks.getBurnTime(stack, null);
                }

                public IFluidHandlerItem handler(ItemStack stack) {
                    return FluidUtil.getFluidHandler(ItemHandlerHelper.copyStackWithSize(stack, 1)).orElse(null);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.item.crafting.RecipeHolder;"))
        assertFalse(transformed.contains("import net.minecraft.world.item.crafting.SingleRecipeInput;"))
        assertFalse(transformed.contains("import net.neoforged.neoforge.network.NetworkHooks;"))
        assertTrue(transformed.contains("DeferredHolder<MenuType<?>, MenuType<StoneMortarContainer>> STONE_MORTAR"))
        assertTrue(transformed.contains("private static <C extends net.minecraft.world.item.crafting.RecipeInput, T extends Recipe<C>> List<T> findRecipesByType"))
        assertTrue(transformed.contains("SimpleContainer container = new SimpleContainer(stack.copy());"))
        assertTrue(transformed.contains(".getRecipeFor(RecipeType.SMELTING, container, level).map(RecipeHolder::value);"))
        assertTrue(transformed.contains(".getRecipesFor(RecipeTypeRegistry.COOKING_RECIPE_TYPE.get(), wrapper, level).stream().map(RecipeHolder::value).toList();"))
        assertTrue(transformed.contains("var recipeOpt = level.getRecipeManager()"))
        assertTrue(transformed.contains(".filter(now -> now.id().equals(lastRecipeID)).findFirst();"))
        assertTrue(transformed.contains("Recipe<RecipeWrapper> recipe = recipeOpt.get().value();"))
        assertTrue(transformed.contains("((CookingPotRecipe) recipe.value()).getExperience()"))
        assertTrue(transformed.contains("(player).openMenu(menu, pos);"))
        assertTrue(transformed.contains("return stack.getBurnTime(null);"))
        assertTrue(transformed.contains("FluidUtil.getFluidHandler(stack.copyWithCount(1)).orElse(null);"))
    }

    @Test
    fun `inventory recipe holder interface migrates to recipe crafting holder`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.inventory.RecipeHolder;

            // import net.minecraft.world.inventory.RecipeHolder;
            // public class Docs implements RecipeHolder {}
            public class Documentation {
                public static final String IMPORT_DOC = "import net.minecraft.world.inventory.RecipeHolder;";
                public static final String IMPLEMENTS_DOC = "public class Docs implements RecipeHolder {}";
            }

            public class MenuBackedBlockEntity implements RecipeHolder {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.inventory.RecipeCraftingHolder;"))
        assertFalse(transformed.contains("import net.minecraft.world.item.crafting.RecipeHolder;"))
        assertTrue(transformed.contains("implements RecipeCraftingHolder"))
        assertTrue(transformed.contains("// import net.minecraft.world.inventory.RecipeHolder;"), transformed)
        assertTrue(transformed.contains("// public class Docs implements RecipeHolder {}"), transformed)
        assertTrue(transformed.contains("public static final String IMPORT_DOC = \"import net.minecraft.world.inventory.RecipeHolder;\";"), transformed)
        assertTrue(transformed.contains("public static final String IMPLEMENTS_DOC = \"public class Docs implements RecipeHolder {}\";"), transformed)
        assertFalse(transformed.contains("// public class Docs implements RecipeCraftingHolder"), transformed)
        assertFalse(transformed.contains("IMPLEMENTS_DOC = \"public class Docs implements RecipeCraftingHolder"), transformed)
    }

    @Test
    fun `block path type rename does not rewrite project class names`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.level.pathfinder.BlockPathTypes;

            public class AetherBlockPathTypes {
                public static final BlockPathTypes BOSS_DOORWAY = BlockPathTypes.create("BOSS_DOORWAY", -1.0F);
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public class AetherBlockPathTypes"))
        assertTrue(transformed.contains("import net.minecraft.world.level.pathfinder.PathType;"))
        assertTrue(transformed.contains("public static final PathType BOSS_DOORWAY = PathType.create"))
        assertFalse(transformed.contains("public class AetherPathType"))
    }

    @Test
    fun `glass block rename does not rewrite project class names`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.level.block.GlassBlock;

            public class QuicksoilGlassBlock extends GlassBlock {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public class QuicksoilGlassBlock extends TransparentBlock"))
        assertTrue(transformed.contains("import net.minecraft.world.level.block.TransparentBlock;"))
        assertFalse(transformed.contains("public class QuicksoilTransparentBlock"))
        assertFalse(transformed.contains("import net.minecraft.world.level.block.GlassBlock;"))
    }

    @Test
    fun `removed item marker interfaces are deleted from multi-interface implements clauses`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.Vanishable;

            class AccessoryItem extends Item implements ICurioItem, Vanishable {
            }

            class LeatherGlovesItem extends GlovesItem implements DyeableLeatherItem {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("class AccessoryItem extends Item implements ICurioItem"))
        assertTrue(transformed.contains("class LeatherGlovesItem extends GlovesItem {"))
        assertFalse(transformed.contains("Vanishable"))
        assertFalse(transformed.contains("DyeableLeatherItem"))
    }

    @Test
    fun `simple container import is preserved when the class extends simple container`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.SimpleContainer;

            public class LoreInventory extends SimpleContainer {
                public LoreInventory() {
                    super(1);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.SimpleContainer;"))
        assertTrue(transformed.contains("extends SimpleContainer"))
        assertFalse(transformed.contains("import net.minecraft.world.item.crafting.SingleRecipeInput;"))
    }

    @Test
    fun `simple container slot backing store is not rewritten as recipe input by text rules`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.SimpleContainer;
            import net.minecraft.world.inventory.Slot;

            public class CreativeDestroySlot {
                private static final SimpleContainer DESTROY_ITEM_CONTAINER = new SimpleContainer(1);

                void addSlot() {
                    new Slot(DESTROY_ITEM_CONTAINER, 0, 172, 142);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.SimpleContainer;"))
        assertTrue(transformed.contains("private static final SimpleContainer DESTROY_ITEM_CONTAINER = new SimpleContainer(1);"))
        assertTrue(transformed.contains("new Slot(DESTROY_ITEM_CONTAINER, 0, 172, 142);"))
        assertFalse(transformed.contains("SingleRecipeInput"))
    }

    @Test
    fun `generic dirt message screen renames to generic message screen`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.client.gui.screens.GenericDirtMessageScreen;

            public class ScreenUse {
                void open(Component title) {
                    new GenericDirtMessageScreen(title);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.client.gui.screens.GenericMessageScreen;"))
        assertTrue(transformed.contains("new GenericMessageScreen(title);"))
        assertFalse(transformed.contains("GenericDirtMessageScreen"))
    }

    @Test
    fun `command function cacheable function migrates to split cacheable type`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.commands.CommandFunction;

            public class FunctionUse {
                CacheableTarget target(CommandFunction.CacheableFunction function) {
                    return new CacheableTarget(function, CommandFunction.CacheableFunction.NONE);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.commands.CacheableFunction;"))
        assertTrue(transformed.contains("import net.minecraft.commands.functions.CommandFunction;"))
        assertTrue(transformed.contains("CacheableTarget target(CacheableFunction function)"))
        assertTrue(transformed.contains("new CacheableTarget(function, java.util.Optional.empty())"))
        assertFalse(transformed.contains("CommandFunction.CacheableFunction"))
        assertFalse(transformed.contains("import net.minecraft.commands.CommandFunction;"))
    }

    @Test
    fun `removed tag manager item access migrates to registry holders`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.tags.ITagManager;

            public class TagAccess {
                public static void add(TagKey<Item> itemTag, int burnTime) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    if (tags != null) {
                        tags.getTag(itemTag).stream().forEach((item) -> getMap().put(item, burnTime));
                    }
                }

                public static boolean empty(TagKey<Item> itemTag) {
                    boolean flag = true;
                    ITagManager<Item> itemTags = BuiltInRegistries.ITEM.tags();
                    if (itemTags != null) {
                        if (itemTags.getTag(itemTag).isEmpty()) {
                            flag = false;
                        }
                    }
                    return flag;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("ITagManager"))
        assertFalse(transformed.contains("net.neoforged.neoforge.registries.tags"))
        assertTrue(transformed.contains("BuiltInRegistries.ITEM.getTagOrEmpty(itemTag).forEach"), transformed)
        assertTrue(transformed.contains("Holder<Item> itemHolder"), transformed)
        assertTrue(transformed.contains("Item item = itemHolder.value();"), transformed)
        assertTrue(transformed.contains("getMap().put(item, burnTime);"), transformed)
        assertTrue(transformed.contains("if (!BuiltInRegistries.ITEM.getTagOrEmpty(itemTag).iterator().hasNext()) {"), transformed)
    }

    @Test
    fun `removed tag manager migration ignores comments text blocks and unsupported shapes`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.tags.ITagManager;

            public class TagAccess {
                private static final String DOC = ${"\"\"\""}
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    if (tags != null) {
                        tags.getTag(itemTag).stream().forEach((item) -> getMap().put(item, burnTime));
                    }
                    ${"\"\"\""};

                // ITagManager<Item> commented = BuiltInRegistries.ITEM.tags();

                public static void unsupported(TagKey<Item> itemTag) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    if (tags != null) {
                        use(tags.getTag(itemTag));
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.neoforged.neoforge.registries.tags.ITagManager;"), transformed)
        assertTrue(transformed.contains("ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();"), transformed)
        assertTrue(transformed.contains("use(tags.getTag(itemTag));"), transformed)
        assertTrue(transformed.contains("tags.getTag(itemTag).stream().forEach((item) -> getMap().put(item, burnTime));"), transformed)
        assertFalse(transformed.contains("BuiltInRegistries.ITEM.getTagOrEmpty(itemTag).forEach"), transformed)
    }

    @Test
    fun `network hooks open screen handles nested simple menu provider lambda`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.network.NetworkHooks;

            public class TestMod {
                public void open(ServerPlayer serverPlayer) {
                    NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider((id, inventory, playerEntity) -> new LoreBookMenu(id, inventory), Component.translatable("menu.example.book")));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("(serverPlayer).openMenu(new SimpleMenuProvider((id, inventory, playerEntity) -> new LoreBookMenu(id, inventory), Component.translatable(\"menu.example.book\")));"), transformed)
        assertFalse(transformed.contains("buf.writeBlockPos(inventory, playerEntity)"), transformed)
        assertFalse(transformed.contains("NetworkHooks.openScreen"), transformed)
        assertFalse(transformed.contains("import net.minecraftforge.network.NetworkHooks;"), transformed)
    }

    @Test
    fun `network hooks open screen migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.network.NetworkHooks;

            public class TestMod {
                private static final String DOC = "NetworkHooks.openScreen(player, menu, payload)";

                /*
                 NetworkHooks.openScreen(player, menu, payload);
                 */

                public void open(ServerPlayer player, MenuProvider menu) {
                    NetworkHooks.openScreen(player, menu);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("(player).openMenu(menu);"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"NetworkHooks.openScreen(player, menu, payload)\";"), transformed)
        assertTrue(transformed.contains("NetworkHooks.openScreen(player, menu, payload);"), transformed)
        assertFalse(transformed.contains("import net.minecraftforge.network.NetworkHooks;"), transformed)
        assertFalse(transformed.contains("DOC = \"(player).openMenu"), transformed)
    }

    @Test
    fun `network hooks open screen rejects untyped third argument instead of guessing`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.network.NetworkHooks;

            public class TestMod {
                public void open(ServerPlayer player, MenuProvider menu, Object payload) {
                    NetworkHooks.openScreen(player, menu, payload);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.any { it.contains("Cannot safely migrate NetworkHooks.openScreen") }, result.errors.joinToString("\n"))
        assertTrue(transformed.contains("NetworkHooks.openScreen(player, menu, payload);"), transformed)
        assertTrue(transformed.contains("import net.neoforged.neoforge.network.NetworkHooks;") ||
            transformed.contains("import net.minecraftforge.network.NetworkHooks;"), transformed)
    }

    @Test
    fun `network hooks open screen ignores commented third argument type evidence`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.network.NetworkHooks;

            public class TestMod {
                /*
                 BlockPos payload;
                 */

                public void open(ServerPlayer player, MenuProvider menu, Object payload) {
                    NetworkHooks.openScreen(player, menu, payload);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.any { it.contains("Cannot safely migrate NetworkHooks.openScreen") }, result.errors.joinToString("\n"))
        assertTrue(transformed.contains("NetworkHooks.openScreen(player, menu, payload);"), transformed)
        assertFalse(transformed.contains("buf.writeBlockPos(payload)"), transformed)
    }

    @Test
    fun `sakura gui tags bucket event and vertex APIs migrate to 1_21 surfaces`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod {
                public void screen(GuiGraphics ms, int mouseX, int mouseY, float partialTicks, BucketItem bucketItem) {
                    this.renderBackground(ms);
                    Fluid fluid = bucketItem.getFluid();
                }

                public void event(BlockToolModificationEvent event) {
                    ItemAbility ability = event.getToolAction();
                }

                public void tags() {
                    Object itemTags = List.of(Tags.Items.COBBLESTONE, Tags.Items.GRAVEL, Tags.Items.GLASS, Tags.Items.STRING, Tags.Items.STONE, Tags.Items.SAND);
                    Object blockTags = List.of(Tags.Blocks.COBBLESTONE, Tags.Blocks.GRAVEL, Tags.Blocks.GLASS, Tags.Blocks.STONE, Tags.Blocks.SAND);
                }

                public void render(VertexConsumer builder, Matrix4f matrix, Matrix3f normal, float x, float y, float z, float u, float v, int packedLight, float nx, float ny, float nz) {
                    builder.vertex(matrix, x, y, z).setUv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).setNormal(normal, nx, ny, nz).endVertex();
                    builder.vertex(matrix, x, y, z).overlayCoords(0, 10).uv2(packedLight).endVertex();
                    mesh.vertex(x, y, z);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("this.renderBackground(ms, mouseX, mouseY, partialTicks);"))
        assertTrue(transformed.contains("Fluid fluid = bucketItem.content;"))
        assertTrue(transformed.contains("ItemAbility ability = event.getItemAbility();"))
        assertTrue(transformed.contains("Tags.Items.COBBLESTONES"))
        assertTrue(transformed.contains("Tags.Items.GRAVELS"))
        assertTrue(transformed.contains("Tags.Items.GLASS_BLOCKS"))
        assertTrue(transformed.contains("Tags.Items.STRINGS"))
        assertTrue(transformed.contains("Tags.Items.STONES"))
        assertTrue(transformed.contains("Tags.Items.SANDS"))
        assertTrue(transformed.contains("Tags.Blocks.COBBLESTONES"))
        assertTrue(transformed.contains("Tags.Blocks.GRAVELS"))
        assertTrue(transformed.contains("Tags.Blocks.GLASS_BLOCKS"))
        assertTrue(transformed.contains("Tags.Blocks.STONES"))
        assertTrue(transformed.contains("Tags.Blocks.SANDS"))
        assertTrue(transformed.contains("builder.addVertex(matrix, x, y, z).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(nx, ny, nz);"))
        assertTrue(transformed.contains("builder.addVertex(matrix, x, y, z).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight);"))
        assertTrue(transformed.contains("mesh.vertex(x, y, z);"))
        assertFalse(transformed.contains("bucketItem.getFluid()"))
        assertFalse(transformed.contains("event.getToolAction()"))
        assertFalse(transformed.contains("builder.vertex("))
        assertFalse(transformed.contains(".overlayCoords("))
        assertFalse(transformed.contains(".uv2("))
        assertFalse(transformed.contains(".endVertex()"))
    }

    @Test
    fun `sakura effect particle fluid and nested event migrations compile against 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.particles.SimpleParticleType;
            import net.minecraft.world.level.material.FlowingFluid;
            import net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class TestMod {
                public static final DeferredHolder<SimpleParticleType, SimpleParticleType> LEAF = PARTICLES.register("leaf", () -> new SimpleParticleType(false));
                public static final DeferredHolder<FlowingFluid, FlowingFluid> SAKE = FLUIDS.register("sake", () -> new BaseFlowingFluid.Source(PROPS));

                public void onLivingHurt(LivingDamageEvent.Post event, Player player, MobEffectInstance instance) {
                    if (player.hasEffect(EffectRegistry.FIRE_BLADE.get())) {
                        int amplifier = player.getEffect(EffectRegistry.EXP_UP.get()).getAmplifier();
                    }
                    boolean bad = instance.getEffect().isBeneficial();
                    Object effect = new MobEffectInstance(EffectRegistry.GOLDEN_HEART.get(), 20, 0);
                    Object bucket = new BucketItem(FluidRegistry.SAKE_STILL, props);
                    Object block = new LiquidBlock(FluidRegistry.SAKE_FLOWING, props);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.core.particles.ParticleType;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.material.Fluid;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post;"))
        assertFalse(transformed.contains("import net.neoforged.neoforge.event.entity.living.Post;"))
        assertTrue(transformed.contains("DeferredHolder<ParticleType<?>, SimpleParticleType> LEAF"))
        assertTrue(transformed.contains("DeferredHolder<Fluid, FlowingFluid> SAKE"))
        assertTrue(transformed.contains("public void onLivingHurt(Post event"))
        assertTrue(transformed.contains("player.hasEffect(EffectRegistry.FIRE_BLADE)"))
        assertTrue(transformed.contains("player.getEffect(EffectRegistry.EXP_UP)"))
        assertTrue(transformed.contains("instance.getEffect().value().isBeneficial()"))
        assertTrue(transformed.contains("new MobEffectInstance(EffectRegistry.GOLDEN_HEART, 20, 0)"))
        assertTrue(transformed.contains("new BucketItem(FluidRegistry.SAKE_STILL.get(), props)"))
        assertTrue(transformed.contains("new LiquidBlock(FluidRegistry.SAKE_FLOWING.get(), props)"))
        assertFalse(transformed.contains("DeferredHolder<SimpleParticleType, SimpleParticleType>"))
        assertFalse(transformed.contains("EffectRegistry.FIRE_BLADE.get()"))
        assertFalse(transformed.contains("getEffect().isBeneficial()"))
    }

    @Test
    fun `block override signature rules migrate common 1_21 methods`() {
        val projectDir = createTestFile("""
            package com.example;

            import javax.annotation.Nullable;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;

            public class TestMod {
                @Override
                @SuppressWarnings("deprecation")
                @Override
                protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
                    return InteractionResult.PASS;
                }

                @Override
                public boolean isBed(BlockState state, BlockGetter level, BlockPos pos, @Nullable Entity player) {
                    return true;
                }

                @Override
                public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                    super.playerWillDestroy(level, pos, state, player);
                }

                @Override
                public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("@Override\n                @SuppressWarnings"))
        assertTrue(transformed.contains("isBed(BlockState state, BlockGetter level, BlockPos pos, @Nullable LivingEntity player)"))
        assertTrue(transformed.contains("import net.minecraft.world.entity.LivingEntity;"))
        assertTrue(transformed.contains("public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player)"))
        assertTrue(transformed.contains("return super.playerWillDestroy(level, pos, state, player);"))
        assertTrue(transformed.contains("getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state)"))
        assertFalse(transformed.contains("getCloneItemStack(LevelReader level, BlockPos pos, BlockState state)"))
        assertFalse(transformed.contains("import net.minecraft.world.level.LevelReader;"))
    }

    @Test
    fun `effect tick signature migration defers trailing return to structural pass`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod extends MobEffect {
                @Override
                public void applyEffectTick(LivingEntity entity, int amplifier) {
                    entity.getActiveEffects().stream()
                        .filter(e -> !e.getEffect().isBeneficial())
                        .map(MobEffectInstance::getEffect)
                        .toList()
                        .forEach(entity::removeEffect);
                }

                @Override
                public boolean isDurationEffectTick(int duration, int amplifier) {
                    return (duration & 1) == 0;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val afterText = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(afterText.contains("public boolean applyEffectTick(LivingEntity entity, int amplifier)"))
        assertFalse(afterText.contains("return true;"))
        assertTrue(afterText.contains("shouldApplyEffectTickThisTick(int duration, int amplifier)"))
        assertFalse(afterText.contains("public void applyEffectTick"))
        assertFalse(afterText.contains("isDurationEffectTick("))

        StructuralRefactorPass().apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("return true;"))
    }

    @Test
    fun `hotbath holder tooltip and mapcodec migrations compile against 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.serialization.MapCodec;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectCategory;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;

            public class TestMod extends Item {
                public static final Codec<BaseFields> BASE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    EffectEntry.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(BaseFields::effects)
                ).apply(instance, BaseFields::new));

                public void appendHoverText(@NotNull ItemStack stack, @org.jetbrains.annotations.Nullable net.minecraft.world.level.Level level, @NotNull List<Component> tooltipComponents, @NotNull TooltipFlag tooltipFlag) {
                    super.appendHoverText(stack, level, tooltipComponents, tooltipFlag);
                }

                public int getUseDuration(@NotNull ItemStack stack) {
                    return 32;
                }

                public MobEffectInstance toMobEffectInstance(ResourceLocation effect) {
                    MobEffect mobEffect = BuiltInRegistries.MOB_EFFECT.get(effect);
                    if (mobEffect == null) {
                        return null;
                    }
                    return new MobEffectInstance(mobEffect, 20, 0, false, true, true);
                }

                private static boolean isHarmfulEffect(MobEffect effect) {
                    return effect.getCategory() == MobEffectCategory.HARMFUL && effect != MobEffects.BAD_OMEN;
                }

                void remove(MobEffectInstance effectInstance, ServerPlayer player) {
                    MobEffect effectHolder = effectInstance.getEffect();
                    if (isHarmfulEffect(effectHolder) && effectHolder != MobEffects.UNLUCK) {
                        player.removeEffect(effectHolder);
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.core.Holder;"))
        assertTrue(transformed.contains("EffectEntry.CODEC.codec().listOf()"))
        assertTrue(transformed.contains("appendHoverText(@NotNull ItemStack stack, Item.TooltipContext level,"))
        assertTrue(transformed.contains("super.appendHoverText(stack, level, tooltipComponents, tooltipFlag);"))
        assertTrue(transformed.contains("public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity entity)"))
        assertTrue(transformed.contains("Holder<MobEffect> mobEffect = BuiltInRegistries.MOB_EFFECT.getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.MOB_EFFECT, effect)).orElse(null);"))
        assertTrue(transformed.contains("return new MobEffectInstance(mobEffect, 20, 0, false, true, true);"))
        assertTrue(transformed.contains("private static boolean isHarmfulEffect(Holder<MobEffect> effect)"))
        assertTrue(transformed.contains("return effect.value().getCategory() == MobEffectCategory.HARMFUL"))
        assertTrue(transformed.contains("Holder<MobEffect> effectHolder = effectInstance.getEffect();"))
        assertTrue(transformed.contains("player.removeEffect(effectHolder);"))
    }

    @Test
    fun `requirements strategy migrates while plant ownership evidence remains available`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.advancements.RequirementsStrategy;
            import net.neoforged.neoforge.common.IPlantable;
            import net.neoforged.neoforge.common.PlantType;

            public class TestMod implements RequirementsStrategy {
                IPlantable plantable;
                PlantType plantType;
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.advancements.AdvancementRequirements;"))
        assertTrue(transformed.contains("implements AdvancementRequirements.Strategy"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.common.IPlantable;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.common.PlantType;"))
        assertFalse(transformed.contains("PlantType removed"))
        assertFalse(transformed.contains("TODO"))
    }

    @Test
    fun `tool item constructors and durability callbacks migrate to 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.DiggerItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ShovelItem;
            import net.minecraft.world.item.SwordItem;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.SimpleTier;

            public class TestMod extends SwordItem {
                public TestMod(Tier tier, int attackDamageModifier, float attackSpeedModifier, Properties properties) {
                    super(tier, attackDamageModifier, attackSpeedModifier, properties);
                }

                void combat(ItemStack stack, Player player, LivingEntity entityLiving, int timeLeft, Player targetPlayer) {
                    int ticksUsed = getUseDuration(stack) - timeLeft;
                    float sweepRatio = EnchantmentHelper.getSweepingDamageRatio(player);
                    float enchantBonus = EnchantmentHelper.getDamageBonus(stack, entityLiving.getMobType()) / 1.2F;
                    targetPlayer.disableShield(false);
                    stack.hurtAndBreak(2, player, (p) -> p.onEquippedItemBroken(EquipmentSlot.MAINHAND));
                    ItemStack blade = ItemStack.of(tag.getCompound(TAG_BLADE));
                }

                ItemStack remainder(ItemStack stack) {
                    ItemStack copy = stack.copy();
                    if (copy.hurt(1, net.minecraft.util.RandomSource.create(), null)) {
                        return ItemStack.EMPTY;
                    }
                    return copy;
                }
            }

            class BroomItem extends ShovelItem {
                public BroomItem(Tier tier, float attackDamage, float attackSpeed, Properties properties) {
                    super(tier, attackDamage, attackSpeed, properties);
                }

                void damage(UseOnContext context) {
                    context.getItemInHand().hurtAndBreak(1, context.getPlayer(),
                        p -> p.onEquippedItemBroken(context.getHand()));
                }
            }

            class HammerItem extends DiggerItem {
                public HammerItem(Tier tier, float attackDamage, float attackSpeed, Properties properties) {
                    super(attackDamage, attackSpeed, tier, SakuraBlockTags.MINEABLE_WITH_HAMMER, properties);
                }
            }

            class Tiers {
                public static final Tier TACHI = new SimpleTier(3, 457, 7.0F, 3.0F, 18,
                        BlockTags.NEEDS_DIAMOND_TOOL, () -> Ingredient.EMPTY);
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("super(tier, properties.attributes(createAttributes(tier, attackDamageModifier, attackSpeedModifier)));"))
        assertTrue(transformed.contains("super(tier, properties.attributes(createAttributes(tier, attackDamage, attackSpeed)));"))
        assertTrue(transformed.contains("super(tier, SakuraBlockTags.MINEABLE_WITH_HAMMER, properties.attributes(DiggerItem.createAttributes(tier, attackDamage, attackSpeed)));"))
        assertTrue(transformed.contains("new SimpleTier(BlockTags.NEEDS_DIAMOND_TOOL, 457, 7.0F, 3.0F, 18, () -> Ingredient.EMPTY)"))
        assertTrue(transformed.contains("getUseDuration(stack, entityLiving) - timeLeft"))
        assertTrue(transformed.contains("float sweepRatio = EnchantmentHelper.getSweepingDamageRatio(player);"))
        assertFalse(transformed.contains("float sweepRatio = 0.5F;"))
        assertTrue(transformed.contains("float enchantBonus = EnchantmentHelper.getDamageBonus(stack, entityLiving.getMobType()) / 1.2F;"))
        assertTrue(transformed.contains("targetPlayer.disableShield();"))
        assertTrue(transformed.contains("stack.hurtAndBreak(2, player, EquipmentSlot.MAINHAND);"))
        assertTrue(transformed.contains("context.getItemInHand().hurtAndBreak(1, context.getPlayer(), EquipmentSlot.MAINHAND);"))
        assertTrue(transformed.contains("ItemStack blade = ItemStack.of(tag.getCompound(TAG_BLADE));"))
        assertTrue(transformed.contains("copy.setDamageValue(copy.getDamageValue() + 1);"))
        assertTrue(transformed.contains("if (copy.getDamageValue() >= copy.getMaxDamage())"))
        assertFalse(transformed.contains("copy.hurt(1, net.minecraft.util.RandomSource.create(), null)"))
    }

    @Test
    fun `custom particle options migrate to codec and stream codec APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.brigadier.StringReader;
            import com.mojang.brigadier.exceptions.CommandSyntaxException;
            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.core.particles.ParticleOptions;
            import net.minecraft.core.particles.ParticleType;
            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.registries.DeferredHolder;

            import javax.annotation.Nonnull;

            public class LeafParticleData implements ParticleOptions {
                public final int r;
                public final int g;
                public final int b;

                public LeafParticleData(int r, int g, int b) {
                    this.r = r;
                    this.g = g;
                    this.b = b;
                }

                @Nonnull
                @Override
                public ParticleType<?> getType() {
                    return TFParticleType.FALLEN_LEAF.get();
                }

                public static Codec<LeafParticleData> codecLeaf() {
                    return RecordCodecBuilder.create((instance) -> instance.group(
                            Codec.INT.fieldOf("r").forGetter((obj) -> obj.r),
                            Codec.INT.fieldOf("g").forGetter((obj) -> obj.g),
                            Codec.INT.fieldOf("b").forGetter((obj) -> obj.b))
                            .apply(instance, LeafParticleData::new));
                }

                @Override
                public void writeToNetwork(@Nonnull FriendlyByteBuf buf) {
                    buf.writeVarInt(r);
                    buf.writeVarInt(g);
                    buf.writeVarInt(b);
                }

                @Nonnull
                @Override
                public String writeToString() {
                    return String.format("%d %d %d", r, g, b);
                }

                public static class Deserializer implements ParticleOptions.Deserializer<LeafParticleData> {
                    @Nonnull
                    @Override
                    public LeafParticleData fromCommand(@Nonnull ParticleType<LeafParticleData> type, @Nonnull StringReader reader) throws CommandSyntaxException {
                        return new LeafParticleData(reader.readInt(), reader.readInt(), reader.readInt());
                    }

                    @Nonnull
                    @Override
                    public LeafParticleData fromNetwork(@Nonnull ParticleType<LeafParticleData> type, FriendlyByteBuf buf) {
                        return new LeafParticleData(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
                    }
                }
            }

            class TFParticleType {
                public static final DeferredHolder<ParticleType<LeafParticleData>, ParticleType<LeafParticleData>> FALLEN_LEAF =
                        PARTICLES.register("fallen_leaf", () -> new ParticleType<>(false, new LeafParticleData.Deserializer()) {
                            @Override
                            public Codec<LeafParticleData> codec() {
                                return LeafParticleData.codecLeaf();
                            }
                        });
            }

            class ParticlePacket {
                public ParticlePacket(FriendlyByteBuf buf) {
                    ParticleType<?> type = null;
                    readParticle(type, buf);
                }

                private <T extends ParticleOptions> T readParticle(ParticleType<T> particleType, FriendlyByteBuf buf) {
                    return particleType.getDeserializer().fromNetwork(particleType, buf);
                }

                public void encode(FriendlyByteBuf buf) {
                    QueuedParticle queuedParticle = null;
                    queuedParticle.particleOptions.writeToNetwork(buf);
                }

                private record QueuedParticle(ParticleOptions particleOptions) {
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(transformed.contains("import net.minecraft.network.RegistryFriendlyByteBuf;"))
        assertTrue(transformed.contains("import net.minecraft.network.codec.ByteBufCodecs;"))
        assertTrue(transformed.contains("import net.minecraft.network.codec.StreamCodec;"))
        assertTrue(transformed.contains("public record LeafParticleData(int r, int g, int b) implements ParticleOptions"))
        assertTrue(transformed.contains("public static MapCodec<LeafParticleData> CODEC = RecordCodecBuilder.mapCodec"))
        assertTrue(transformed.contains("public static StreamCodec<? super RegistryFriendlyByteBuf, LeafParticleData> STREAM_CODEC = StreamCodec.composite"))
        assertTrue(transformed.contains("ByteBufCodecs.VAR_INT, p -> p.r"))
        assertTrue(transformed.contains("DeferredHolder<ParticleType<?>, ParticleType<LeafParticleData>> FALLEN_LEAF"))
        assertTrue(transformed.contains("new ParticleType<>(false)"))
        assertTrue(transformed.contains("public MapCodec<LeafParticleData> codec()"))
        assertTrue(transformed.contains("return LeafParticleData.CODEC;"))
        assertTrue(transformed.contains("public StreamCodec<? super RegistryFriendlyByteBuf, LeafParticleData> streamCodec()"))
        assertTrue(transformed.contains("return LeafParticleData.STREAM_CODEC;"))
        assertTrue(transformed.contains("ParticlePacket(RegistryFriendlyByteBuf buf)"))
        assertTrue(transformed.contains("return particleType.streamCodec().decode(buf);"))
        assertTrue(transformed.contains("private <T extends ParticleOptions> void writeParticle(T particleOptions, RegistryFriendlyByteBuf buf)"))
        assertTrue(transformed.contains("particleType.streamCodec().encode(buf, particleOptions);"))
        assertTrue(transformed.contains("writeParticle(queuedParticle.particleOptions, buf);"))
        assertFalse(transformed.contains("ParticleOptions.Deserializer"))
        assertFalse(transformed.contains("writeToNetwork"))
        assertFalse(transformed.contains("writeToString"))
        assertFalse(transformed.contains("getDeserializer()"))
        assertFalse(transformed.contains("new LeafParticleData.Deserializer()"))
        assertFalse(transformed.contains("codecLeaf()"))
        assertFalse(transformed.contains("import net.minecraft.network.FriendlyByteBuf;"))
    }

    @Test
    fun `custom particle options migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            public class ParticleDocs {
                /*
                public class LeafParticleData implements ParticleOptions {
                    public final int r;

                    public void writeToNetwork(FriendlyByteBuf buf) {
                        buf.writeVarInt(r);
                    }

                    public static class Deserializer implements ParticleOptions.Deserializer<LeafParticleData> {
                    }
                }
                */
                private static final String DOC = "ParticleOptions.Deserializer implements ParticleOptions writeToNetwork public final int g;";
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(result.changes.any { it.ruleId == "particle-options-codec-streamcodec" }, result.changes.joinToString("\n"))
        assertFalse(transformed.contains("public record LeafParticleData"), transformed)
        assertFalse(transformed.contains("MapCodec<LeafParticleData>"), transformed)
        assertTrue(transformed.contains("public class LeafParticleData implements ParticleOptions"), transformed)
        assertTrue(transformed.contains("public final int r;"), transformed)
        assertTrue(transformed.contains("ParticleOptions.Deserializer implements ParticleOptions writeToNetwork"), transformed)
    }

    @Test
    fun `custom particle options migration preserves commented legacy samples beside real code`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.core.particles.ParticleOptions;
            import net.minecraft.core.particles.ParticleType;
            import net.minecraft.network.FriendlyByteBuf;

            public class ParticleDocs {
                /*
                legacy sample:
                public class LeafParticleData implements ParticleOptions {
                    public final int r;
                    public LeafParticleData(int r) {
                        this.r = r;
                    }
                    public static Codec<LeafParticleData> codecLeaf() {
                        return RecordCodecBuilder.create((instance) -> instance.group(
                                Codec.INT.fieldOf("r").forGetter((obj) -> obj.r))
                                .apply(instance, LeafParticleData::new));
                    }
                    @Override
                    public void writeToNetwork(FriendlyByteBuf buf) {
                        buf.writeVarInt(r);
                    }
                    public static class Deserializer implements ParticleOptions.Deserializer<LeafParticleData> {
                    }
                }
                */
            }

            public class LeafParticleData implements ParticleOptions {
                public final int r;

                public LeafParticleData(int r) {
                    this.r = r;
                }

                public static Codec<LeafParticleData> codecLeaf() {
                    return RecordCodecBuilder.create((instance) -> instance.group(
                            Codec.INT.fieldOf("r").forGetter((obj) -> obj.r))
                            .apply(instance, LeafParticleData::new));
                }

                @Override
                public ParticleType<?> getType() {
                    return null;
                }

                @Override
                public void writeToNetwork(FriendlyByteBuf buf) {
                    buf.writeVarInt(r);
                }

                public static class Deserializer implements ParticleOptions.Deserializer<LeafParticleData> {
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "particle-options-codec-streamcodec" }, result.changes.joinToString("\n"))
        assertTrue(transformed.contains("public record LeafParticleData(int r) implements ParticleOptions"), transformed)
        assertTrue(transformed.contains("public static MapCodec<LeafParticleData> CODEC = RecordCodecBuilder.mapCodec"), transformed)
        assertTrue(transformed.contains("legacy sample:"), transformed)
        assertTrue(transformed.contains("public class LeafParticleData implements ParticleOptions"), transformed)
        assertTrue(transformed.contains("public final int r;"), transformed)
        assertTrue(transformed.contains("public static class Deserializer implements ParticleOptions.Deserializer<LeafParticleData>"), transformed)
    }

    @Test
    fun `custom particle type registration migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            public class ParticleTypeDocs {
                /*
                public static final DeferredHolder<ParticleType<LeafParticleData>, ParticleType<LeafParticleData>> FALLEN_LEAF =
                        PARTICLES.register("fallen_leaf", () -> new ParticleType<>(false, new LeafParticleData.Deserializer()) {
                            @Override
                            public Codec<LeafParticleData> codec() {
                                return LeafParticleData.codecLeaf();
                            }
                        });
                */
                private static final String DOC = "new ParticleType<>(false, new LeafParticleData.Deserializer())";
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(result.changes.any { it.ruleId == "particle-type-codec-streamcodec" }, result.changes.joinToString("\n"))
        assertTrue(transformed.contains("new ParticleType<>(false, new LeafParticleData.Deserializer())"), transformed)
        assertTrue(transformed.contains("DeferredHolder<ParticleType<LeafParticleData>, ParticleType<LeafParticleData>>"), transformed)
        assertFalse(transformed.contains("DeferredHolder<ParticleType<?>, ParticleType<LeafParticleData>>"), transformed)
        assertFalse(transformed.contains("LeafParticleData.STREAM_CODEC"), transformed)
    }

    @Test
    fun `custom particle network migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            public class ParticleNetworkDocs {
                /*
                private <T extends ParticleOptions> T readParticle(ParticleType<T> particleType, FriendlyByteBuf buf) {
                    return particleType.getDeserializer().fromNetwork(particleType, buf);
                }

                public void encode(FriendlyByteBuf buf) {
                    queuedParticle.particleOptions.writeToNetwork(buf);
                }
                */
                private static final String DOC = "ParticleOptions getDeserializer().fromNetwork writeToNetwork FriendlyByteBuf";
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(result.changes.any { it.ruleId == "particle-network-streamcodec" }, result.changes.joinToString("\n"))
        assertTrue(transformed.contains("FriendlyByteBuf buf"), transformed)
        assertTrue(transformed.contains("particleType.getDeserializer().fromNetwork(particleType, buf);"), transformed)
        assertTrue(transformed.contains("queuedParticle.particleOptions.writeToNetwork(buf);"), transformed)
        assertFalse(transformed.contains("RegistryFriendlyByteBuf"), transformed)
        assertFalse(transformed.contains("particleType.streamCodec()"), transformed)
        assertFalse(transformed.contains("void writeParticle("), transformed)
    }

    @Test
    fun `loot datagen builders and global loot modifier generics migrate to 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import net.minecraft.world.level.storage.loot.entries.LootTableReference;
            import net.minecraft.world.level.storage.loot.functions.CopyNbtFunction;
            import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithLootingCondition;
            import net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider;
            import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestMod {
                public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS = null;
                public static final DeferredHolder<Codec<FieryToolSmeltingModifier>, Codec<FieryToolSmeltingModifier>> FIERY_PICK_SMELTING =
                        LOOT_MODIFIERS.register("fiery_pick_smelting", () -> FieryToolSmeltingModifier.CODEC);

                void loot() {
                    pool.add(LootTableReference.lootTableReference(TFLootTables.USELESS_LOOT).setWeight(25));
                    entry.when(LootItemRandomChanceWithLootingCondition.randomChanceAndLootingBoost(0.5F, 0.1F));
                    table.apply(CopyNbtFunction.copyData(ContextNbtProvider.BLOCK_ENTITY).copy("SkullOwner", "SkullOwner"));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.level.storage.loot.entries.NestedLootTable;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithEnchantedBonusCondition;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;"))
        assertTrue(transformed.contains("import net.minecraft.core.component.DataComponents;"))
        assertTrue(transformed.contains("DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<FieryToolSmeltingModifier>> FIERY_PICK_SMELTING"))
        assertTrue(transformed.contains("NestedLootTable.lootTableReference(TFLootTables.USELESS_LOOT)"))
        assertTrue(transformed.contains("LootItemRandomChanceWithEnchantedBonusCondition.randomChanceAndLootingBoost(this.registries, 0.5F, 0.1F)"))
        assertTrue(transformed.contains("CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY).include(DataComponents.PROFILE).include(DataComponents.NOTE_BLOCK_SOUND).include(DataComponents.CUSTOM_NAME)"))
        assertFalse(transformed.contains("LootTableReference"))
        assertFalse(transformed.contains("LootItemRandomChanceWithLootingCondition"))
        assertFalse(transformed.contains("CopyNbtFunction"))
        assertFalse(transformed.contains("ContextNbtProvider"))
        assertFalse(transformed.contains("SkullOwner"))
        assertFalse(transformed.contains("ChunkGenerator"))
        assertFalse(transformed.contains("MapMapCodec"))
    }

    @Test
    fun `generic loot nbt copy builders migrate to custom data copy function`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.level.storage.loot.functions.CopyNbtFunction;
            import net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider;

            public class TestMod {
                void loot() {
                    table.apply(CopyNbtFunction.copyData(ContextNbtProvider.BLOCK_ENTITY)
                            .copy("Locked", "BlockEntityTag.Locked")
                            .copy("Kind", "BlockEntityTag.Kind", CopyNbtFunction.MergeStrategy.REPLACE));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider;"))
        assertTrue(transformed.contains("CopyCustomDataFunction.copyData(ContextNbtProvider.BLOCK_ENTITY)"))
        assertTrue(transformed.contains("CopyCustomDataFunction.MergeStrategy.REPLACE"))
        assertFalse(transformed.contains("CopyNbtFunction"))
    }

    @Test
    fun `legacy loot serializers migrate to MapCodec backed loot types`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ModExistsCondition.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public class ModExistsCondition implements LootItemCondition {
                private final String modID;

                public ModExistsCondition(String modID) {
                    this.modID = modID;
                }

                public LootItemConditionType getType() {
                    return TestLoot.MOD_EXISTS.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class ConditionSerializer implements Serializer<ModExistsCondition> {
                    public void serialize(JsonObject json, ModExistsCondition value, JsonSerializationContext context) {
                        json.addProperty("mod_id", value.modID);
                    }

                    public ModExistsCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new ModExistsCondition(GsonHelper.getAsString(json, "mod_id"));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityTargetCondition.java").writeText("""
            package com.example;

            import com.google.common.collect.ImmutableSet;
            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
            import java.util.Set;

            public class EntityTargetCondition implements LootItemCondition {
                final LootContext.EntityTarget entityTarget;

                private EntityTargetCondition(LootContext.EntityTarget entityTarget) {
                    this.entityTarget = entityTarget;
                }

                public Set<LootContextParam<?>> getReferencedContextParams() {
                    return ImmutableSet.of(this.entityTarget.getParam());
                }

                public LootItemConditionType getType() {
                    return TestLoot.ENTITY_TARGET.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class ConditionSerializer implements Serializer<EntityTargetCondition> {
                    public void serialize(JsonObject json, EntityTargetCondition condition, JsonSerializationContext context) {
                        json.add("entity", context.serialize(condition.entityTarget));
                    }

                    public EntityTargetCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new EntityTargetCondition(GsonHelper.getAsObject(json, "entity", context, LootContext.EntityTarget.class));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("IsMinionCondition.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public record IsMinionCondition(boolean inverse) implements LootItemCondition {
                public LootItemConditionType getType() {
                    return TestLoot.IS_MINION.get();
                }

                public boolean test(LootContext context) {
                    return !inverse;
                }

                public static class ConditionSerializer implements Serializer<IsMinionCondition> {
                    public void serialize(JsonObject json, IsMinionCondition value, JsonSerializationContext context) {
                        json.addProperty("inverse", value.inverse);
                    }

                    public IsMinionCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new IsMinionCondition(GsonHelper.getAsBoolean(json, "inverse", false));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("UncraftingTableEnabledCondition.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public class UncraftingTableEnabledCondition implements LootItemCondition {
                private UncraftingTableEnabledCondition() {
                }

                public LootItemConditionType getType() {
                    return TestLoot.UNCRAFTING_TABLE_ENABLED.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class ConditionSerializer implements Serializer<UncraftingTableEnabledCondition> {
                    public void serialize(JsonObject json, UncraftingTableEnabledCondition value, JsonSerializationContext context) {
                    }

                    public UncraftingTableEnabledCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new UncraftingTableEnabledCondition();
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ConfigEnabled.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public class ConfigEnabled implements LootItemCondition {
                private final ConfigValue config;

                public ConfigEnabled(ConfigValue config) {
                    this.config = config;
                }

                public LootItemConditionType getType() {
                    return TestLoot.CONFIG_ENABLED.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class Serializer implements net.minecraft.world.level.storage.loot.Serializer<ConfigEnabled> {
                    public void serialize(JsonObject json, ConfigEnabled instance, JsonSerializationContext context) {
                        json.addProperty("config", ConfigSerializationUtil.serialize(instance.config));
                    }

                    public ConfigEnabled deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new ConfigEnabled(ConfigSerializationUtil.deserialize(GsonHelper.getAsString(json, "config")));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ModItemSwap.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import com.google.gson.JsonSyntaxException;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class ModItemSwap extends LootItemConditionalFunction {
                private final Item item;
                private final Item oldItem;
                private final boolean success;

                protected ModItemSwap(LootItemCondition[] conditions, Item item, Item old, boolean success) {
                    super(conditions);
                    this.item = item;
                    this.oldItem = old;
                    this.success = success;
                }

                public LootItemFunctionType getType() {
                    return TestLoot.ITEM_OR_DEFAULT.get();
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return new ItemStack(this.item, stack.getCount());
                }

                public static class Builder extends LootItemConditionalFunction.Builder<ModItemSwap.Builder> {
                    private Item item;
                    private Item oldItem;

                    protected ModItemSwap.Builder getThis() {
                        return this;
                    }

                    public LootItemFunction build() {
                        return new ModItemSwap(this.getConditions(), this.item, this.oldItem, true);
                    }
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<ModItemSwap> {
                    public void serialize(JsonObject object, ModItemSwap function, JsonSerializationContext serializationContext) {
                        if (function.success)
                            object.addProperty("item", BuiltInRegistries.ITEM.getKey(function.item).toString());
                        else
                            object.addProperty("default", BuiltInRegistries.ITEM.getKey(function.item).toString());
                        object.addProperty("default", BuiltInRegistries.ITEM.getKey(function.oldItem).toString());
                    }

                    public ModItemSwap deserialize(JsonObject object, JsonDeserializationContext deserializationContext, LootItemCondition[] conditions) {
                        Item item;
                        boolean success;
                        try {
                            item = GsonHelper.getAsItem(object, "item");
                            success = true;
                        } catch (JsonSyntaxException e) {
                            item = GsonHelper.getAsItem(object, "default");
                            success = false;
                        }
                        return new ModItemSwap(conditions, item, GsonHelper.getAsItem(object, "default"), success);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("NoExtraLootFunction.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

            public class NoExtraLootFunction extends LootItemConditionalFunction {
                protected NoExtraLootFunction(LootItemCondition[] conditions) {
                    super(conditions);
                }

                public LootItemFunctionType getType() {
                    return TestLoot.NO_EXTRA.get();
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return stack;
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<NoExtraLootFunction> {
                    public void serialize(JsonObject json, NoExtraLootFunction instance, JsonSerializationContext context) {
                        super.serialize(json, instance, context);
                    }

                    public NoExtraLootFunction deserialize(JsonObject json, JsonDeserializationContext context, LootItemCondition[] conditions) {
                        return new NoExtraLootFunction(conditions);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("SpawnEntityLootFunction.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import com.google.gson.JsonSyntaxException;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

            public class SpawnEntityLootFunction extends LootItemConditionalFunction {
                private final EntityType<?> entityType;
                private final int count;

                protected SpawnEntityLootFunction(LootItemCondition[] conditions, EntityType<?> entityType, int count) {
                    super(conditions);
                    this.entityType = entityType;
                    this.count = count;
                }

                public LootItemFunctionType getType() {
                    return TestLoot.SPAWN_ENTITY.get();
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return stack;
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<SpawnEntityLootFunction> {
                    public void serialize(JsonObject json, SpawnEntityLootFunction instance, JsonSerializationContext context) {
                        super.serialize(json, instance, context);
                        json.addProperty("entity", EntityType.getKey(instance.entityType).toString());
                        json.addProperty("count", instance.count);
                    }

                    public SpawnEntityLootFunction deserialize(JsonObject json, JsonDeserializationContext context, LootItemCondition[] conditions) {
                        EntityType<?> entityType = EntityType.byString(GsonHelper.getAsString(json, "entity")).orElseThrow(() -> new JsonSyntaxException("No value present!"));
                        int count = GsonHelper.getAsInt(json, "count");
                        return new SpawnEntityLootFunction(conditions, entityType, count);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("TestLoot.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestLoot {
                public static final DeferredRegister<LootItemConditionType> CONDITIONS = DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, "example");
                public static final DeferredRegister<LootItemFunctionType> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, "example");

                public static final DeferredHolder<LootItemFunctionType, LootItemFunctionType> ITEM_OR_DEFAULT =
                        FUNCTIONS.register("item_or_default", () -> new LootItemFunctionType(new ModItemSwap.Serializer()));
                public static final DeferredHolder<LootItemFunctionType, LootItemFunctionType> NO_EXTRA =
                        FUNCTIONS.register("no_extra", () -> new LootItemFunctionType(new NoExtraLootFunction.Serializer()));
                public static final DeferredHolder<LootItemFunctionType, LootItemFunctionType> SPAWN_ENTITY =
                        FUNCTIONS.register("spawn_entity", () -> new LootItemFunctionType(new SpawnEntityLootFunction.Serializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> MOD_EXISTS =
                        CONDITIONS.register("mod_exists", () -> new LootItemConditionType(new ModExistsCondition.ConditionSerializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> CONFIG_ENABLED =
                        CONDITIONS.register("config_enabled", () -> new LootItemConditionType(new ConfigEnabled.Serializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> ENTITY_TARGET =
                        CONDITIONS.register("entity_target", () -> new LootItemConditionType(new EntityTargetCondition.ConditionSerializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> IS_MINION =
                        CONDITIONS.register("is_minion", () -> new LootItemConditionType(new IsMinionCondition.ConditionSerializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> UNCRAFTING_TABLE_ENABLED =
                        CONDITIONS.register("uncrafting_table_enabled", () -> new LootItemConditionType(new UncraftingTableEnabledCondition.ConditionSerializer()));
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)

        val modExists = srcDir.resolve("ModExistsCondition.java").readText()
        val entityTarget = srcDir.resolve("EntityTargetCondition.java").readText()
        val isMinion = srcDir.resolve("IsMinionCondition.java").readText()
        val uncrafting = srcDir.resolve("UncraftingTableEnabledCondition.java").readText()
        val configEnabled = srcDir.resolve("ConfigEnabled.java").readText()
        val modItemSwap = srcDir.resolve("ModItemSwap.java").readText()
        val noExtraLootFunction = srcDir.resolve("NoExtraLootFunction.java").readText()
        val spawnEntityLootFunction = srcDir.resolve("SpawnEntityLootFunction.java").readText()
        val registry = srcDir.resolve("TestLoot.java").readText()

        assertTrue(result.changes.any { it.ruleId == "loot-serializer-mapcodec" })
        assertTrue(modExists.contains("public static final MapCodec<ModExistsCondition> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec"))
        assertTrue(modExists.contains("com.mojang.serialization.Codec.STRING.fieldOf(\"mod_id\").forGetter(o -> o.modID)"))
        assertTrue(entityTarget.contains("LootContext.EntityTarget.CODEC.fieldOf(\"entity\").forGetter(o -> o.entityTarget)"))
        assertTrue(isMinion.contains("com.mojang.serialization.Codec.BOOL.optionalFieldOf(\"inverse\", false).forGetter(o -> o.inverse)"))
        assertTrue(uncrafting.contains("public static final MapCodec<UncraftingTableEnabledCondition> CODEC = MapCodec.unit(new UncraftingTableEnabledCondition());"))
        assertTrue(configEnabled.contains("com.mojang.serialization.Codec.STRING.fieldOf(\"config\").forGetter(o -> ConfigSerializationUtil.serialize(o.config))"))
        assertTrue(configEnabled.contains("value -> new ConfigEnabled(ConfigSerializationUtil.deserialize(value))"))
        assertTrue(modItemSwap.contains("public static final MapCodec<ModItemSwap> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec"))
        assertTrue(modItemSwap.contains("protected ModItemSwap(java.util.List<LootItemCondition> conditions, Item item, Item old, boolean success)"))
        assertTrue(modItemSwap.contains("public LootItemFunctionType<ModItemSwap> getType()"))
        assertTrue(noExtraLootFunction.contains("public static final MapCodec<NoExtraLootFunction> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> commonFields(instance).apply(instance, NoExtraLootFunction::new));"))
        assertTrue(noExtraLootFunction.contains("protected NoExtraLootFunction(java.util.List<LootItemCondition> conditions)"))
        assertTrue(noExtraLootFunction.contains("public LootItemFunctionType<NoExtraLootFunction> getType()"))
        assertTrue(spawnEntityLootFunction.contains("BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf(\"entity\").forGetter(o -> o.entityType)"))
        assertTrue(spawnEntityLootFunction.contains("com.mojang.serialization.Codec.INT.fieldOf(\"count\").forGetter(o -> o.count)"))
        assertTrue(spawnEntityLootFunction.contains("protected SpawnEntityLootFunction(java.util.List<LootItemCondition> conditions, EntityType<?> entityType, int count)"))
        assertTrue(spawnEntityLootFunction.contains("public LootItemFunctionType<SpawnEntityLootFunction> getType()"))
        assertTrue(registry.contains("DeferredRegister<LootItemFunctionType<?>> FUNCTIONS"))
        assertTrue(registry.contains("DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<ModItemSwap>> ITEM_OR_DEFAULT"))
        assertTrue(registry.contains("DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<NoExtraLootFunction>> NO_EXTRA"))
        assertTrue(registry.contains("DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<SpawnEntityLootFunction>> SPAWN_ENTITY"))
        assertTrue(registry.contains("new LootItemFunctionType<>(ModItemSwap.CODEC)"))
        assertTrue(registry.contains("new LootItemFunctionType<>(NoExtraLootFunction.CODEC)"))
        assertTrue(registry.contains("new LootItemFunctionType<>(SpawnEntityLootFunction.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(ModExistsCondition.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(ConfigEnabled.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(EntityTargetCondition.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(IsMinionCondition.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(UncraftingTableEnabledCondition.CODEC)"))
        assertFalse(modExists.contains("ConditionSerializer"))
        assertFalse(configEnabled.contains("net.minecraft.world.level.storage.loot.Serializer"))
        assertFalse(modItemSwap.contains("LootItemConditionalFunction.Serializer"))
        assertFalse(noExtraLootFunction.contains("LootItemConditionalFunction.Serializer"))
        assertFalse(spawnEntityLootFunction.contains("LootItemConditionalFunction.Serializer"))
        assertFalse(spawnEntityLootFunction.contains("EntityType.byString"))
        assertFalse(modItemSwap.contains("JsonSyntaxException"))
        assertFalse(modExists.contains("net.minecraft.world.level.storage.loot.Serializer"))
    }

    @Test
    fun `loot conditional function codec migration rejects member name fallbacks`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("AmbiguousItemSwap.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import com.google.gson.JsonSyntaxException;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

            public class AmbiguousItemSwap extends LootItemConditionalFunction {
                private final Item replacement;
                private final Item fallback;
                private final boolean selected;

                protected AmbiguousItemSwap(LootItemCondition[] conditions, Item replacement, Item fallback, boolean selected) {
                    super(conditions);
                    this.replacement = replacement;
                    this.fallback = fallback;
                    this.selected = selected;
                }

                public LootItemFunctionType getType() {
                    return null;
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return new ItemStack(this.replacement, stack.getCount());
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<AmbiguousItemSwap> {
                    public void serialize(JsonObject object, AmbiguousItemSwap function, JsonSerializationContext serializationContext) {
                        object.addProperty("marker", function.selected);
                    }

                    public AmbiguousItemSwap deserialize(JsonObject object, JsonDeserializationContext deserializationContext, LootItemCondition[] conditions) {
                        Item item;
                        boolean success;
                        try {
                            item = GsonHelper.getAsItem(object, "item");
                            success = true;
                        } catch (JsonSyntaxException e) {
                            item = GsonHelper.getAsItem(object, "default");
                            success = false;
                        }
                        return new AmbiguousItemSwap(conditions, item, GsonHelper.getAsItem(object, "default"), success);
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)

        val ambiguous = srcDir.resolve("AmbiguousItemSwap.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertFalse(ambiguous.contains("MapCodec<AmbiguousItemSwap>"), ambiguous)
        assertTrue(ambiguous.contains("LootItemConditionalFunction.Serializer<AmbiguousItemSwap>"), ambiguous)
        assertTrue(ambiguous.contains("GsonHelper.getAsItem(object, \"item\")"), ambiguous)
        assertTrue(ambiguous.contains("GsonHelper.getAsItem(object, \"default\")"), ambiguous)
    }

    @Test
    fun `loot condition codec migration rejects json key member fallbacks`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("AmbiguousKeyCondition.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public class AmbiguousKeyCondition implements LootItemCondition {
                private final String actualValue;

                public AmbiguousKeyCondition(String actualValue) {
                    this.actualValue = actualValue;
                }

                public LootItemConditionType getType() {
                    return TestLoot.AMBIGUOUS.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class ConditionSerializer implements Serializer<AmbiguousKeyCondition> {
                    public void serialize(JsonObject json, AmbiguousKeyCondition value, JsonSerializationContext context) {
                        json.addProperty("marker", value.actualValue);
                    }

                    public AmbiguousKeyCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new AmbiguousKeyCondition(GsonHelper.getAsString(json, "display_name"));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("TestLoot.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestLoot {
                public static final DeferredRegister<LootItemConditionType> CONDITIONS = DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, "example");

                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> AMBIGUOUS =
                        CONDITIONS.register("ambiguous", () -> new LootItemConditionType(new AmbiguousKeyCondition.ConditionSerializer()));
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)

        val ambiguous = srcDir.resolve("AmbiguousKeyCondition.java").readText()
        val registry = srcDir.resolve("TestLoot.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertFalse(ambiguous.contains("MapCodec<AmbiguousKeyCondition>"), ambiguous)
        assertTrue(ambiguous.contains("Serializer<AmbiguousKeyCondition>"), ambiguous)
        assertTrue(ambiguous.contains("GsonHelper.getAsString(json, \"display_name\")"), ambiguous)
        assertTrue(registry.contains("new LootItemConditionType(new AmbiguousKeyCondition.ConditionSerializer())"), registry)
        assertFalse(registry.contains("AmbiguousKeyCondition.CODEC"), registry)
    }

    @Test
    fun `loot condition codec migration ignores commented serializer structure`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CommentOnlyCondition.java").writeText("""
            package com.example;

            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public class CommentOnlyCondition implements LootItemCondition {
                private static final String DOC = "public static class ConditionSerializer implements Serializer<CommentOnlyCondition>";

                /*
                public static class ConditionSerializer implements Serializer<CommentOnlyCondition> {
                    public CommentOnlyCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new CommentOnlyCondition();
                    }
                }
                */

                public LootItemConditionType getType() {
                    return null;
                }

                public boolean test(LootContext context) {
                    return true;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)

        val condition = srcDir.resolve("CommentOnlyCondition.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertFalse(condition.contains("MapCodec<CommentOnlyCondition>"), condition)
        assertTrue(condition.contains("public static class ConditionSerializer implements Serializer<CommentOnlyCondition>"), condition)
        assertTrue(condition.contains("private static final String DOC"), condition)
    }

    @Test
    fun `loot type registry codec migration ignores commented and string serializer constructors`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExistingCondition.java").writeText("""
            package com.example;

            import com.mojang.serialization.MapCodec;

            public class ExistingCondition {
                public static final MapCodec<ExistingCondition> CODEC = null;
            }
        """.trimIndent())
        srcDir.resolve("TestLoot.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestLoot {
                public static final DeferredRegister<LootItemConditionType> CONDITIONS = DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, "example");
                private static final String DOC = "new LootItemConditionType(new ExistingCondition.Serializer())";

                // CONDITIONS.register("commented", () -> new LootItemConditionType(new ExistingCondition.Serializer()));
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)

        val registry = srcDir.resolve("TestLoot.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertFalse(registry.contains("new LootItemConditionType(ExistingCondition.CODEC)"), registry)
        assertTrue(registry.contains("private static final String DOC = \"new LootItemConditionType(new ExistingCondition.Serializer())\";"), registry)
        assertTrue(registry.contains("// CONDITIONS.register(\"commented\", () -> new LootItemConditionType(new ExistingCondition.Serializer()));"), registry)
    }

    @Test
    fun `loot function entity and int codec migration rejects local variable member fallbacks`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("AmbiguousSpawnFunction.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import com.google.gson.JsonSyntaxException;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

            public class AmbiguousSpawnFunction extends LootItemConditionalFunction {
                private final EntityType<?> targetType;
                private final int amount;

                protected AmbiguousSpawnFunction(LootItemCondition[] conditions, EntityType<?> targetType, int amount) {
                    super(conditions);
                    this.targetType = targetType;
                    this.amount = amount;
                }

                public LootItemFunctionType getType() {
                    return null;
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return stack;
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<AmbiguousSpawnFunction> {
                    public void serialize(JsonObject json, AmbiguousSpawnFunction instance, JsonSerializationContext context) {
                        json.addProperty("marker", instance.amount);
                    }

                    public AmbiguousSpawnFunction deserialize(JsonObject json, JsonDeserializationContext context, LootItemCondition[] conditions) {
                        EntityType<?> entityType = EntityType.byString(GsonHelper.getAsString(json, "entity")).orElseThrow(() -> new JsonSyntaxException("No value present!"));
                        int count = GsonHelper.getAsInt(json, "count");
                        return new AmbiguousSpawnFunction(conditions, entityType, count);
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)

        val ambiguous = srcDir.resolve("AmbiguousSpawnFunction.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertFalse(ambiguous.contains("MapCodec<AmbiguousSpawnFunction>"), ambiguous)
        assertTrue(ambiguous.contains("LootItemConditionalFunction.Serializer<AmbiguousSpawnFunction>"), ambiguous)
        assertTrue(ambiguous.contains("EntityType.byString(GsonHelper.getAsString(json, \"entity\"))"), ambiguous)
        assertTrue(ambiguous.contains("GsonHelper.getAsInt(json, \"count\")"), ambiguous)
    }

    @Test
    fun `legacy neoforge recipe condition serializers migrate to condition codecs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.common.conditions.ICondition;
            import net.neoforged.neoforge.common.conditions.IConditionSerializer;

            public class TestMod implements ICondition {
                private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("example", "enabled");
                public static final TestMod INSTANCE = new TestMod();

                private TestMod() {
                }

                @Override
                public ResourceLocation getID() {
                    return ID;
                }

                @Override
                public boolean test(IContext context) {
                    return true;
                }

                public static class Serializer implements IConditionSerializer<TestMod> {
                    public static final Serializer INSTANCE = new Serializer();

                    @Override
                    public ResourceLocation getID() {
                        return ID;
                    }

                    @Override
                    public TestMod read(JsonObject json) {
                        return new TestMod();
                    }

                    @Override
                    public void write(JsonObject json, TestMod value) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "neoforge-condition-serializer-mapcodec" })
        assertTrue(transformed.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(transformed.contains("public static final String CONDITION_ID = \"enabled\";"))
        assertTrue(transformed.contains("public static final MapCodec<TestMod> CODEC = MapCodec.unit(INSTANCE);"))
        assertTrue(transformed.contains("public MapCodec<? extends ICondition> codec()"))
        assertTrue(transformed.contains("return CODEC;"))
        assertFalse(transformed.contains("IConditionSerializer"))
        assertFalse(transformed.contains("ResourceLocation"))
        assertFalse(transformed.contains("JsonObject"))
        assertFalse(transformed.contains("getID()"))
        assertFalse(transformed.contains("class Serializer"))
    }

    @Test
    fun `condition serializer migration ignores braces inside Java text blocks`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.common.conditions.ICondition;
            import net.neoforged.neoforge.common.conditions.IConditionSerializer;

            public class TestMod implements ICondition {
                private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("example", "enabled");
                public static final TestMod INSTANCE = new TestMod();

                @Override
                public ResourceLocation getID() {
                    return ID;
                }

                @Override
                public boolean test(IContext context) {
                    return true;
                }

                public static class Serializer implements IConditionSerializer<TestMod> {
                    public static final Serializer INSTANCE = new Serializer();

                    private static final String DOC = ${"\"\"\""}
                        "quoted text"
                        }
                        ${"\"\"\""};

                    @Override
                    public ResourceLocation getID() {
                        return ID;
                    }

                    @Override
                    public TestMod read(JsonObject json) {
                        return new TestMod();
                    }

                    @Override
                    public void write(JsonObject json, TestMod value) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "neoforge-condition-serializer-mapcodec" })
        assertTrue(transformed.contains("public static final MapCodec<TestMod> CODEC = MapCodec.unit(INSTANCE);"), transformed)
        assertTrue(transformed.contains("public MapCodec<? extends ICondition> codec()"), transformed)
        assertFalse(transformed.contains("class Serializer"), transformed)
        assertFalse(transformed.contains("\"quoted text\""), transformed)
        assertFalse(transformed.contains("IConditionSerializer"), transformed)
    }

    @Test
    fun `condition serializer migration ignores comments and text blocks when deriving codec shape`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.common.conditions.ICondition;
            import net.neoforged.neoforge.common.conditions.IConditionSerializer;

            public class TestMod implements ICondition {
                private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("example", "enabled");
                private static final String DOC = ${"\"\"\""}
                    public static final TestMod INSTANCE = new TestMod();
                    public MapCodec<? extends ICondition> codec()
                    ${"\"\"\""};

                @Override
                public ResourceLocation getID() {
                    return ID;
                }

                @Override
                public boolean test(IContext context) {
                    return true;
                }

                public static class Serializer implements IConditionSerializer<TestMod> {
                    @Override
                    public ResourceLocation getID() {
                        return ID;
                    }

                    @Override
                    public TestMod read(JsonObject json) {
                        return new TestMod();
                    }

                    @Override
                    public void write(JsonObject json, TestMod value) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "neoforge-condition-serializer-mapcodec" })
        assertTrue(transformed.contains("public static final MapCodec<TestMod> CODEC = MapCodec.unit(new TestMod());"), transformed)
        assertFalse(transformed.contains("MapCodec.unit(INSTANCE)"), transformed)
        assertEquals(2, Regex("""MapCodec<\? extends ICondition>\s+codec\(\)""").findAll(transformed).count(), transformed)
        assertTrue(transformed.contains("public static final TestMod INSTANCE = new TestMod();"), transformed)
        assertFalse(transformed.contains("IConditionSerializer"), transformed)
        assertFalse(transformed.contains("class Serializer"), transformed)
    }

    @Test
    fun `condition serializer method cleanup ignores Java methods inside text blocks`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.common.conditions.ICondition;
            import net.neoforged.neoforge.common.conditions.IConditionSerializer;

            public class TestMod implements ICondition {
                private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("example", "enabled");
                public static final TestMod INSTANCE = new TestMod();
                private static final String DOC = ${"\"\"\""}
                    public String getID() {
                        return "documentation";
                    }
                    ${"\"\"\""};

                @Override
                public ResourceLocation getID() {
                    return ID;
                }

                @Override
                public boolean test(IContext context) {
                    return true;
                }

                public static class Serializer implements IConditionSerializer<TestMod> {
                    @Override
                    public ResourceLocation getID() {
                        return ID;
                    }

                    @Override
                    public TestMod read(JsonObject json) {
                        return new TestMod();
                    }

                    @Override
                    public void write(JsonObject json, TestMod value) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "neoforge-condition-serializer-mapcodec" })
        assertTrue(transformed.contains("public String getID()"), transformed)
        assertTrue(transformed.contains("return \"documentation\";"), transformed)
        assertFalse(transformed.contains("public ResourceLocation getID()"), transformed)
        assertFalse(transformed.contains("class Serializer"), transformed)
    }

    @Test
    fun `partial nbt ingredient helpers migrate to data component ingredients`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.Util;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.crafting.PartialNBTIngredient;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class TestMod {
                public final PartialNBTIngredient damagedItem(Item item) {
                    return PartialNBTIngredient.of(item, Util.make(() -> {
                        CompoundTag nbt = new CompoundTag();
                        nbt.putInt(ItemStack.TAG_DAMAGE, item.getMaxDamage());
                        return nbt;
                    }));
                }

                public final PartialNBTIngredient potionIngredient(Potion potion) {
                    return PartialNBTIngredient.of(Items.POTION, Util.make(() -> {
                        CompoundTag nbt = new CompoundTag();
                        nbt.putString("Potion", BuiltInRegistries.POTION.getKey(potion).toString());
                        return nbt;
                    }));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.core.Holder;"))
        assertTrue(transformed.contains("import net.minecraft.core.component.DataComponents;"))
        assertTrue(transformed.contains("import net.minecraft.world.item.alchemy.PotionContents;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.common.crafting.DataComponentIngredient;"))
        assertTrue(transformed.contains("public final Ingredient damagedItem(Item item)"))
        assertTrue(transformed.contains("DataComponentIngredient.of(false, DataComponents.DAMAGE, item.getMaxDamage(), item)"))
        assertTrue(transformed.contains("public final Ingredient potionIngredient(Holder<Potion> potion)"))
        assertTrue(transformed.contains("DataComponentIngredient.of(false, DataComponents.POTION_CONTENTS, new PotionContents(potion), Items.POTION)"))
        assertFalse(transformed.contains("PartialNBTIngredient"))
        assertFalse(transformed.contains("CompoundTag"))
        assertFalse(transformed.contains("Util.make"))
        assertFalse(transformed.contains("ItemStack.TAG_DAMAGE"))
    }

    @Test
    fun `partial nbt ingredient migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.Util;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.crafting.PartialNBTIngredient;

            public class TestMod {
                /*
                 public final PartialNBTIngredient commented(Item item) {
                     return PartialNBTIngredient.of(item, Util.make(() -> {
                         CompoundTag nbt = new CompoundTag();
                         nbt.putInt(ItemStack.TAG_DAMAGE, item.getMaxDamage());
                         return nbt;
                     }));
                 }
                 */
                private static final String DOC = "PartialNBTIngredient.of(item, Util.make(() -> nbt))";

                public final PartialNBTIngredient damagedItem(Item item) {
                    return PartialNBTIngredient.of(item, Util.make(() -> {
                        CompoundTag nbt = new CompoundTag();
                        nbt.putInt(ItemStack.TAG_DAMAGE, item.getMaxDamage());
                        return nbt;
                    }));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public final Ingredient damagedItem(Item item)"), transformed)
        assertTrue(
            transformed.contains("DataComponentIngredient.of(false, DataComponents.DAMAGE, item.getMaxDamage(), item)"),
            transformed
        )
        assertTrue(transformed.contains("public final PartialNBTIngredient commented(Item item)"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"PartialNBTIngredient.of(item, Util.make(() -> nbt))\";"), transformed)
        assertFalse(transformed.contains("public final Ingredient commented(Item item)"), transformed)
        assertFalse(transformed.contains("DOC = \"DataComponentIngredient.of"), transformed)
    }

    @Test
    fun `unsupported partial nbt ingredient helpers are not half migrated`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.Util;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.common.crafting.PartialNBTIngredient;

            public class TestMod {
                public final PartialNBTIngredient custom(Item item) {
                    return PartialNBTIngredient.of(item, Util.make(() -> {
                        CompoundTag nbt = new CompoundTag();
                        nbt.putString("Custom", "value");
                        return nbt;
                    }));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.neoforged.neoforge.common.crafting.PartialNBTIngredient;"))
        assertTrue(transformed.contains("public final PartialNBTIngredient custom(Item item)"))
        assertTrue(transformed.contains("PartialNBTIngredient.of(item"))
        assertFalse(transformed.contains("DataComponentIngredient.of"))
    }

    @Test
    fun `single item recipe result wrappers migrate to recipe output save calls`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.SingleItemRecipeBuilder;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.level.ItemLike;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.core.registries.BuiltInRegistries;
            import org.jetbrains.annotations.Nullable;

            import java.util.function.Consumer;

            public class TestMod {
                protected static void buildRecipes(RecipeOutput consumer) {
                    consumer.accept(stonecutting(Blocks.STONE, ModBlocks.CUT_STONE.get()));
                    consumer.accept(stonecutting(Blocks.STONE, ModBlocks.TILE.get(), 2));
                }

                private static Wrapper stonecutting(ItemLike input, ItemLike output) {
                    return stonecutting(input, output, 1);
                }

                private static Wrapper stonecutting(ItemLike input, ItemLike output, int count) {
                    return new Wrapper(getIdFor(input.asItem(), output.asItem()), Ingredient.of(input), output.asItem(), count);
                }

                private static ResourceLocation getIdFor(Item input, Item output) {
                    String path = String.format("stonecutting/%s/%s", BuiltInRegistries.ITEM.getKey(input).getPath(), BuiltInRegistries.ITEM.getKey(output).getPath());
                    return prefix(path);
                }

                // Wrapper that allows you to not have an advancement
                public static class Wrapper extends SingleItemRecipeBuilder.Result {
                    public Wrapper(ResourceLocation id, Ingredient input, Item output, int count) {
                        super(id, RecipeSerializer.STONECUTTER, "", input, output, count, null, null);
                    }

                    @Nullable
                    @Override
                    public JsonObject serializeAdvancement() {
                        return null;
                    }

                    @Nullable
                    @Override
                    public ResourceLocation getAdvancementId() {
                        return null;
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.data.recipes.RecipeCategory;"))
        assertTrue(transformed.contains("stonecutting(consumer, Blocks.STONE, ModBlocks.CUT_STONE.get());"))
        assertTrue(transformed.contains("stonecutting(consumer, Blocks.STONE, ModBlocks.TILE.get(), 2);"))
        assertTrue(transformed.contains("private static void stonecutting(RecipeOutput recipe, ItemLike input, ItemLike output)"))
        assertTrue(transformed.contains("SingleItemRecipeBuilder.stonecutting(Ingredient.of(input), RecipeCategory.BUILDING_BLOCKS, output.asItem(), count).unlockedBy(\"has_block\", has(input)).save(recipe, getIdFor(input, output));"))
        assertTrue(transformed.contains("private static ResourceLocation getIdFor(ItemLike input, ItemLike output)"))
        assertTrue(transformed.contains("BuiltInRegistries.ITEM.getKey(input.asItem()).getPath()"))
        assertTrue(transformed.contains("CriteriaTriggers.INVENTORY_CHANGED.createCriterion"))
        assertFalse(transformed.contains("SingleItemRecipeBuilder.Result"))
        assertFalse(transformed.contains("consumer.accept(stonecutting"))
        assertFalse(transformed.contains("serializeAdvancement"))
        assertFalse(transformed.contains("RecipeSerializer.STONECUTTER"))
    }

    @Test
    fun `single item recipe result migration ignores comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.SingleItemRecipeBuilder;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.level.ItemLike;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.core.registries.BuiltInRegistries;
            import org.jetbrains.annotations.Nullable;

            public class TestMod {
                private static final String DOC = "consumer.accept(stonecutting(Blocks.DIRT, ModBlocks.DOC.get()));";

                /*
                 consumer.accept(stonecutting(Blocks.DIRT, ModBlocks.DOC.get()));

                 private static ResourceLocation getIdFor(Item input, Item output) {
                     String path = String.format("stonecutting/%s/%s", BuiltInRegistries.ITEM.getKey(input).getPath(), BuiltInRegistries.ITEM.getKey(output).getPath());
                     return prefix(path);
                 }

                 public static class Wrapper extends SingleItemRecipeBuilder.Result {
                 }
                 */

                protected static void buildRecipes(RecipeOutput consumer) {
                    consumer.accept(stonecutting(Blocks.STONE, ModBlocks.CUT_STONE.get()));
                }

                private static Wrapper stonecutting(ItemLike input, ItemLike output) {
                    return stonecutting(input, output, 1);
                }

                private static Wrapper stonecutting(ItemLike input, ItemLike output, int count) {
                    return new Wrapper(getIdFor(input.asItem(), output.asItem()), Ingredient.of(input), output.asItem(), count);
                }

                private static ResourceLocation getIdFor(Item input, Item output) {
                    String path = String.format("stonecutting/%s/%s", BuiltInRegistries.ITEM.getKey(input).getPath(), BuiltInRegistries.ITEM.getKey(output).getPath());
                    return prefix(path);
                }

                public static class Wrapper extends SingleItemRecipeBuilder.Result {
                    public Wrapper(ResourceLocation id, Ingredient input, Item output, int count) {
                        super(id, RecipeSerializer.STONECUTTER, "", input, output, count, null, null);
                    }

                    @Nullable
                    @Override
                    public JsonObject serializeAdvancement() {
                        return null;
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("stonecutting(consumer, Blocks.STONE, ModBlocks.CUT_STONE.get());"), transformed)
        assertTrue(transformed.contains("private static ResourceLocation getIdFor(ItemLike input, ItemLike output)"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"consumer.accept(stonecutting(Blocks.DIRT, ModBlocks.DOC.get()));\";"), transformed)
        assertTrue(transformed.contains("consumer.accept(stonecutting(Blocks.DIRT, ModBlocks.DOC.get()));"), transformed)
        assertTrue(transformed.contains("private static ResourceLocation getIdFor(Item input, Item output)"), transformed)
        assertTrue(transformed.contains("public static class Wrapper extends SingleItemRecipeBuilder.Result"), transformed)
        assertFalse(transformed.contains("stonecutting(consumer, Blocks.DIRT, ModBlocks.DOC.get());"), transformed)
        assertFalse(transformed.contains("DOC = \"stonecutting("), transformed)
        assertFalse(transformed.contains("RecipeSerializer.STONECUTTER"), transformed)
    }

    @Test
    fun `dyeable leather item implementations migrate to dyed item color components`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.ChatFormatting;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;
            import net.minecraft.network.chat.MutableComponent;
            import net.minecraft.network.chat.Style;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.core.component.DataComponents;

            public class TestMod extends ArmorItem implements DyeableLeatherItem {
                private static final MutableComponent TOOLTIP = Component.translatable("item.example.arctic.desc").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY));

                public TestMod(ArmorMaterial armorMaterial, Type type, Properties properties) {
                    super(armorMaterial, type, properties);
                }

                @Override
                public boolean hasCustomColor(ItemStack stack) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    return tag.contains("display", 10) && tag.getCompound("display").contains("color", 3);
                }

                @Override
                public int getColor(ItemStack stack) {
                    return this.getColor(stack, 1);
                }

                @Override
                public void clearColor(ItemStack stack) {
                    this.removeColor(stack);
                }

                public int getColor(ItemStack stack, int type) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    return type == 0 ? 0xFFFFFF : tag.getCompound("display").getInt("color");
                }

                public void removeColor(ItemStack stack) {
                    stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().remove("color");
                }

                @Override
                public void setColor(ItemStack stack, int color) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    tag.putInt("color", color);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public class TestMod extends ArmorItem"))
        assertTrue(transformed.contains("public static final int DEFAULT_COLOR = 0xFFBDCFD9;"))
        assertTrue(transformed.contains("return stack.has(DataComponents.DYED_COLOR);"))
        assertTrue(transformed.contains("return type == 0 ? 0xFFFFFF : DyedItemColor.getOrDefault(stack, DEFAULT_COLOR);"))
        assertTrue(transformed.contains("stack.remove(DataComponents.DYED_COLOR);"))
        assertTrue(transformed.contains("stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color, true));"))
        assertTrue(transformed.contains("import net.minecraft.world.item.component.DyedItemColor;"))
        assertFalse(transformed.contains("DyeableLeatherItem"))
        assertFalse(transformed.contains("CUSTOM_DATA"))
        assertFalse(transformed.contains("CompoundTag"))
        assertFalse(transformed.contains("@Override\n\tpublic boolean hasCustomColor"))
    }

    @Test
    fun `dyeable leather item color migration does not require tooltip fields`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.core.component.DataComponents;

            public class TestMod extends ArmorItem implements DyeableLeatherItem {
                @Override
                public boolean hasCustomColor(ItemStack stack) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    return tag.contains("display", 10) && tag.getCompound("display").contains("color", 3);
                }

                @Override
                public void setColor(ItemStack stack, int color) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    tag.putInt("color", color);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public static final int DEFAULT_COLOR = 0xFFBDCFD9;"))
        assertTrue(transformed.contains("DyedItemColor.getOrDefault(stack, DEFAULT_COLOR);"))
        assertFalse(transformed.contains("DyeableLeatherItem"))
        assertFalse(transformed.contains("CUSTOM_DATA"))
    }

    @Test
    fun `dyeable leather item interface and external color handlers migrate to dyed item color`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val itemDir = srcDir.resolve("item")
        val clientDir = srcDir.resolve("client")
        itemDir.createDirectories()
        clientDir.createDirectories()
        itemDir.resolve("LeatherGlovesItem.java").writeText("""
            package com.example.item;

            import net.minecraft.world.item.DyeableLeatherItem;

            public class LeatherGlovesItem extends GlovesItem implements DyeableLeatherItem {
                public LeatherGlovesItem(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        clientDir.resolve("ColorResolver.java").writeText("""
            package com.example.client;

            import com.example.item.LeatherGlovesItem;
            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.ItemStack;

            public class ColorResolver {
                public int color(ItemStack stack, int tintIndex) {
                    return tintIndex > 0 ? -1 : ((DyeableLeatherItem) stack.getItem()).getColor(stack);
                }

                public int colors(ItemStack stack) {
                    if (stack.getItem() instanceof LeatherGlovesItem leatherGlovesItem) {
                        return leatherGlovesItem.getColor(stack);
                    }
                    return 0xFFFFFF;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(tempDir)

        val item = itemDir.resolve("LeatherGlovesItem.java").readText()
        val colorResolver = clientDir.resolve("ColorResolver.java").readText()

        assertTrue(item.contains("public class LeatherGlovesItem extends GlovesItem"))
        assertFalse(item.contains("DyeableLeatherItem"))
        assertTrue(colorResolver.contains("DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR)"))
        assertTrue(colorResolver.contains("return DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR);"))
        assertTrue(colorResolver.contains("import net.minecraft.world.item.component.DyedItemColor;"))
        assertFalse(colorResolver.contains("DyeableLeatherItem"))
        assertFalse(colorResolver.contains(".getColor(stack)"))
    }

    @Test
    fun `dyeable leather external cast color migration ignores comments and string literals`() {
        val clientDir = tempDir.resolve("src/main/java/com/example/client")
        clientDir.createDirectories()
        clientDir.resolve("ColorResolver.java").writeText("""
            package com.example.client;

            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.ItemStack;

            public class ColorResolver {
                private static final String DOC = "((DyeableLeatherItem) stack.getItem()).getColor(stack)";

                /*
                 return ((DyeableLeatherItem) stack.getItem()).getColor(stack);
                 */

                public int color(ItemStack stack, int tintIndex) {
                    return tintIndex > 0 ? -1 : ((DyeableLeatherItem) stack.getItem()).getColor(stack);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(tempDir)

        val transformed = clientDir.resolve("ColorResolver.java").readText()

        assertTrue(transformed.contains("return tintIndex > 0 ? -1 : DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR);"), transformed)
        assertTrue(transformed.contains("import net.minecraft.world.item.component.DyedItemColor;"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"((DyeableLeatherItem) stack.getItem()).getColor(stack)\";"), transformed)
        assertTrue(transformed.contains("return ((DyeableLeatherItem) stack.getItem()).getColor(stack);"), transformed)
        assertFalse(transformed.contains("DOC = \"DyedItemColor.getOrDefault"), transformed)
        assertFalse(transformed.contains("import net.minecraft.world.item.DyeableLeatherItem;"), transformed)
    }

    @Test
    fun `dyeable leather instanceof color migration ignores comments and string literals`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val itemDir = srcDir.resolve("item")
        val clientDir = srcDir.resolve("client")
        itemDir.createDirectories()
        clientDir.createDirectories()
        itemDir.resolve("LeatherGlovesItem.java").writeText("""
            package com.example.item;

            import net.minecraft.world.item.DyeableLeatherItem;

            public class LeatherGlovesItem extends GlovesItem implements DyeableLeatherItem {
                public LeatherGlovesItem(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        clientDir.resolve("ColorResolver.java").writeText("""
            package com.example.client;

            import com.example.item.LeatherGlovesItem;
            import net.minecraft.world.item.ItemStack;

            public class ColorResolver {
                private static final String DOC = "leatherGlovesItem.getColor(stack)";

                public int color(ItemStack stack) {
                    if (stack.getItem() instanceof LeatherGlovesItem leatherGlovesItem) {
                        /*
                         return leatherGlovesItem.getColor(stack);
                         */
                        return leatherGlovesItem.getColor(stack);
                    }
                    return 0xFFFFFF;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(tempDir)

        val transformed = clientDir.resolve("ColorResolver.java").readText()

        assertTrue(transformed.contains("return DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR);"), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"leatherGlovesItem.getColor(stack)\";"), transformed)
        assertTrue(transformed.contains("return leatherGlovesItem.getColor(stack);"), transformed)
        assertFalse(transformed.contains("DOC = \"DyedItemColor.getOrDefault"), transformed)
    }

    @Test
    fun `dyeable leather class collection ignores commented declarations`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val itemDir = srcDir.resolve("item")
        val clientDir = srcDir.resolve("client")
        itemDir.createDirectories()
        clientDir.createDirectories()
        itemDir.resolve("Docs.java").writeText("""
            package com.example.item;

            public class Docs {
                /*
                 public class FakeLeatherItem extends GlovesItem implements DyeableLeatherItem {
                 }
                 */
            }
        """.trimIndent())
        clientDir.resolve("ColorResolver.java").writeText("""
            package com.example.client;

            import com.example.item.FakeLeatherItem;
            import net.minecraft.world.item.ItemStack;

            public class ColorResolver {
                public int color(ItemStack stack) {
                    if (stack.getItem() instanceof FakeLeatherItem fake) {
                        return fake.getColor(stack);
                    }
                    return 0xFFFFFF;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(tempDir)

        val transformed = clientDir.resolve("ColorResolver.java").readText()

        assertTrue(transformed.contains("return fake.getColor(stack);"), transformed)
        assertFalse(transformed.contains("DyedItemColor.getOrDefault"), transformed)
        assertFalse(transformed.contains("import net.minecraft.world.item.component.DyedItemColor;"), transformed)
    }

    @Test
    fun `tier sorting registry custom tiers migrate to simple tier incorrect block tags`() {
        val projectDir = createTestFile("""
            package com.example.util;

            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.item.Tiers;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.SimpleTier;
            import net.neoforged.neoforge.common.TierSortingRegistry;
            import com.example.ExampleMod;
            import com.example.init.ModItems;

            import java.util.List;

            public class TestMod {
                public static final Tier COPPER = TierSortingRegistry.registerTier(
                        new SimpleTier(2, 512, 6.5F, 2, 25, BlockTags.create(ExampleMod.prefix("needs_copper_tool")), () -> Ingredient.of(ModItems.COPPER_INGOT.get())),
                        ExampleMod.prefix("copper"), List.of(Tiers.IRON), List.of(Tiers.DIAMOND));

                public static final Tier GLASS = TierSortingRegistry.registerTier(
                        new SimpleTier(BlockTags.create(ExampleMod.prefix("needs_glass_tool")), 1, 1.0F, 36.0F, 30, () -> Ingredient.EMPTY),
                        ExampleMod.prefix("glass"), List.of(Tiers.WOOD), List.of());
            }
        """.trimIndent())
        projectDir.resolve("src/generated/resources/data/example").createDirectories()
        projectDir.resolve("src/generated/resources/data/anothermod").createDirectories()
        projectDir.resolve("src/main/java/com/example/ExampleMod.java").writeText("""
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public class ExampleMod {
                public static final String ID = "example";

                public static ResourceLocation prefix(String path) {
                    return ResourceLocation.fromNamespaceAndPath(ID, path);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        val copperTag = projectDir.resolve("src/generated/resources/data/example/tags/block/incorrect_for_copper_tool.json")
        val glassTag = projectDir.resolve("src/generated/resources/data/example/tags/block/incorrect_for_glass_tool.json")

        assertTrue(transformed.contains("""new SimpleTier(BlockTags.create(ExampleMod.prefix("incorrect_for_copper_tool")), 512, 6.5F, 2, 25, () -> Ingredient.of(ModItems.COPPER_INGOT.get()))"""))
        assertTrue(transformed.contains("""new SimpleTier(BlockTags.create(ExampleMod.prefix("incorrect_for_glass_tool")), 1, 1.0F, 36.0F, 30, () -> Ingredient.EMPTY)"""))
        assertTrue(result.changes.any { it.ruleId == "tier-incorrect-block-tag-resource" })
        assertTrue(copperTag.exists())
        assertTrue(copperTag.readText().contains(""""#minecraft:incorrect_for_iron_tool""""))
        assertTrue(glassTag.exists())
        assertTrue(glassTag.readText().contains(""""#minecraft:incorrect_for_wooden_tool""""))
        assertFalse(transformed.contains("TierSortingRegistry"))
        assertFalse(transformed.contains("needs_copper_tool"))
        assertFalse(transformed.contains("needs_glass_tool"))
        assertFalse(transformed.contains("List.of(Tiers"))
    }

    @Test
    fun `tier incorrect tag resources are not emitted from data directory namespace guesses`() {
        val projectDir = createTestFile("""
            package com.example.util;

            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.item.Tiers;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.SimpleTier;
            import net.neoforged.neoforge.common.TierSortingRegistry;

            import java.util.List;

            public class TestMod {
                public static final Tier COPPER = TierSortingRegistry.registerTier(
                        new SimpleTier(2, 512, 6.5F, 2, 25, BlockTags.create(UnknownMod.prefix("needs_copper_tool")), () -> Ingredient.EMPTY),
                        UnknownMod.prefix("copper"), List.of(Tiers.IRON), List.of(Tiers.DIAMOND));
            }
        """.trimIndent())
        projectDir.resolve("src/generated/resources/data/foreignmod").createDirectories()

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        val guessedTag = projectDir.resolve("src/generated/resources/data/foreignmod/tags/block/incorrect_for_copper_tool.json")

        assertTrue(
            result.errors.any { it.contains("Cannot derive namespace for custom tool tier tag") },
            "Expected namespace hard gate, got: ${result.errors}"
        )
        assertTrue(transformed.contains("TierSortingRegistry.registerTier"), transformed)
        assertTrue(transformed.contains("UnknownMod.prefix(\"needs_copper_tool\")"), transformed)
        assertFalse(guessedTag.exists(), "Must not emit generated tags into unrelated data namespace")
    }

    @Test
    fun `tier incorrect tag resources are not emitted from metadata namespace fallback`() {
        val projectDir = createTestFile("""
            package com.example.util;

            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.item.Tiers;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.SimpleTier;
            import net.neoforged.neoforge.common.TierSortingRegistry;

            import java.util.List;

            public class TestMod {
                public static final Tier COPPER = TierSortingRegistry.registerTier(
                        new SimpleTier(2, 512, 6.5F, 2, 25, BlockTags.create(UnknownMod.prefix("needs_copper_tool")), () -> Ingredient.EMPTY),
                        UnknownMod.prefix("copper"), List.of(Tiers.IRON), List.of(Tiers.DIAMOND));
            }
        """.trimIndent())
        val metaInf = projectDir.resolve("src/main/resources/META-INF")
        metaInf.createDirectories()
        metaInf.resolve("mods.toml").writeText("""
            [[mods]]
            modId = "example"
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        val metadataFallbackTag = projectDir.resolve("src/main/resources/data/example/tags/block/incorrect_for_copper_tool.json")

        assertTrue(
            result.errors.any { it.contains("Cannot derive namespace for custom tool tier tag") },
            "Expected namespace hard gate, got: ${result.errors}"
        )
        assertTrue(transformed.contains("TierSortingRegistry.registerTier"), transformed)
        assertTrue(transformed.contains("UnknownMod.prefix(\"needs_copper_tool\")"), transformed)
        assertFalse(metadataFallbackTag.exists(), "Must not emit generated tags from mods.toml namespace fallback")
    }

    @Test
    fun `tier sorting registry drop checks migrate to tier tool properties`() {
        val projectDir = createTestFile("""
            package com.example.util;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.common.TierSortingRegistry;

            public class TestMod {
                public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
                    return TierSortingRegistry.isCorrectTierForDrops(getHarvestLevel(stack), state);
                }

                private Tier getHarvestLevel(ItemStack stack) {
                    return null;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("TierSortingRegistry"))
        assertTrue(transformed.contains("(getHarvestLevel(stack)).createToolProperties("))
        assertTrue(transformed.contains("state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE) ? net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE"))
        assertTrue(transformed.contains("net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE).isCorrectForDrops(state)"))
    }

    @Test
    fun `tier sorting registry migrations ignore comments and string literals`() {
        val projectDir = createTestFile("""
            package com.example.util;

            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.item.Tiers;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.common.SimpleTier;
            import net.neoforged.neoforge.common.TierSortingRegistry;
            import com.example.ExampleMod;
            import com.example.init.ModItems;

            import java.util.List;

            public class TestMod {
                private static final String DOC = "TierSortingRegistry.isCorrectTierForDrops(getHarvestLevel(stack), state)";

                /*
                 public static final Tier DOC_TIER = TierSortingRegistry.registerTier(
                         new SimpleTier(2, 512, 6.5F, 2, 25, BlockTags.create(ExampleMod.prefix("needs_doc_tool")), () -> Ingredient.EMPTY),
                         ExampleMod.prefix("doc"), List.of(Tiers.IRON), List.of(Tiers.DIAMOND));

                 return TierSortingRegistry.isCorrectTierForDrops(getHarvestLevel(stack), state);
                 */

                public static final Tier COPPER = TierSortingRegistry.registerTier(
                        new SimpleTier(2, 512, 6.5F, 2, 25, BlockTags.create(ExampleMod.prefix("needs_copper_tool")), () -> Ingredient.of(ModItems.COPPER_INGOT.get())),
                        ExampleMod.prefix("copper"), List.of(Tiers.IRON), List.of(Tiers.DIAMOND));

                public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
                    return TierSortingRegistry.isCorrectTierForDrops(getHarvestLevel(stack), state);
                }

                private Tier getHarvestLevel(ItemStack stack) {
                    return null;
                }
            }
        """.trimIndent())
        projectDir.resolve("src/generated/resources/data/example").createDirectories()
        projectDir.resolve("src/main/java/com/example/ExampleMod.java").writeText("""
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public class ExampleMod {
                public static final String ID = "example";

                public static ResourceLocation prefix(String path) {
                    return ResourceLocation.fromNamespaceAndPath(ID, path);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        val copperTag = projectDir.resolve("src/generated/resources/data/example/tags/block/incorrect_for_copper_tool.json")
        val docTag = projectDir.resolve("src/generated/resources/data/example/tags/block/incorrect_for_doc_tool.json")

        assertTrue(transformed.contains("""new SimpleTier(BlockTags.create(ExampleMod.prefix("incorrect_for_copper_tool")), 512, 6.5F, 2, 25, () -> Ingredient.of(ModItems.COPPER_INGOT.get()))"""), transformed)
        assertTrue(transformed.contains("(getHarvestLevel(stack)).createToolProperties("), transformed)
        assertTrue(transformed.contains("private static final String DOC = \"TierSortingRegistry.isCorrectTierForDrops(getHarvestLevel(stack), state)\";"), transformed)
        assertTrue(transformed.contains("""needs_doc_tool"""), transformed)
        assertTrue(transformed.contains("return TierSortingRegistry.isCorrectTierForDrops(getHarvestLevel(stack), state);"), transformed)
        assertTrue(copperTag.exists())
        assertFalse(docTag.exists(), "Commented registerTier must not emit generated tag resources")
        assertFalse(transformed.contains("import net.neoforged.neoforge.common.TierSortingRegistry;"), transformed)
        assertFalse(transformed.contains("DOC = \"(getHarvestLevel"), transformed)
    }

    @Test
    fun `item property use duration calls use lambda living entity parameter`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.client.renderer.item.ItemProperties;
            import net.minecraft.resources.ResourceLocation;

            public class TestMod {
                public void register() {
                    ItemProperties.register(ModItems.BOW.get(), ResourceLocation.parse("pull"), (stack, world, entity, seed) -> {
                        if (entity == null) return 0.0F;
                        return entity.getUseItem() != stack ? 0.0F : (stack.getUseDuration() - entity.getUseItemRemainingTicks()) / 20.0F;
                    });
                    String docs = "stack.getUseDuration()";
                }
            }
        """.trimIndent())

        val pass = TextReplacementPass(MappingDatabase.loadDefault())
        val result = pass.apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "itemproperties-use-duration-livingentity" })
        assertTrue(transformed.contains("stack.getUseDuration(entity) - entity.getUseItemRemainingTicks()"), transformed)
        assertTrue(transformed.contains("String docs = \"stack.getUseDuration()\";"), transformed)
    }

    @Test
    fun `loot codec owner pre scan recognizes forge registry serializer sources`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ModItemSwap.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraftforge.registries.ForgeRegistries;

            public class ModItemSwap extends LootItemConditionalFunction {
                private final Item item;
                private final Item oldItem;
                private final boolean success;

                protected ModItemSwap(LootItemCondition[] conditions, Item item, Item old, boolean success) {
                    super(conditions);
                    this.item = item;
                    this.oldItem = old;
                    this.success = success;
                }

                public LootItemFunctionType getType() {
                    return TestLoot.ITEM_OR_DEFAULT.get();
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return new ItemStack(this.item, stack.getCount());
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<ModItemSwap> {
                    public void serialize(JsonObject object, ModItemSwap function, JsonSerializationContext context) {
                        if (function.success) object.addProperty("item", ForgeRegistries.ITEMS.getKey(function.item).toString());
                        else object.addProperty("default", ForgeRegistries.ITEMS.getKey(function.item).toString());
                        object.addProperty("default", ForgeRegistries.ITEMS.getKey(function.oldItem).toString());
                    }

                    public ModItemSwap deserialize(JsonObject object, JsonDeserializationContext context, LootItemCondition[] conditions) {
                        Item item = GsonHelper.getAsItem(object, "item");
                        Item oldItem = GsonHelper.getAsItem(object, "default");
                        return new ModItemSwap(conditions, item, oldItem, true);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("TestLoot.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestLoot {
                public static final DeferredRegister<LootItemFunctionType> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, "example");
                public static final DeferredHolder<LootItemFunctionType, LootItemFunctionType> ITEM_OR_DEFAULT =
                        FUNCTIONS.register("item_or_default", () -> new LootItemFunctionType(new ModItemSwap.Serializer()));
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)
        val registry = srcDir.resolve("TestLoot.java").readText()
        val function = srcDir.resolve("ModItemSwap.java").readText()

        assertTrue(result.changes.any { it.ruleId == "loot-serializer-mapcodec" })
        assertTrue(function.contains("public static final MapCodec<ModItemSwap> CODEC"), function)
        assertTrue(registry.contains("DeferredRegister<LootItemFunctionType<?>> FUNCTIONS"), registry)
        assertTrue(registry.contains("DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<ModItemSwap>> ITEM_OR_DEFAULT"), registry)
        assertTrue(registry.contains("new LootItemFunctionType<>(ModItemSwap.CODEC)"), registry)
        assertFalse(registry.contains("new ModItemSwap.Serializer()"), registry)
    }

    @Test
    fun `dry run does not modify files`() {
        val original = """
            import net.minecraftforge.common.MinecraftForge;
        """.trimIndent()
        val projectDir = createTestFile(original)

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.analyze(projectDir)

        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        assertEquals(original, content, "Dry run should not modify files")
        assertTrue(result.changeCount > 0, "Dry run should still report changes")
    }

    @Test
    fun `player event field rewrite leaves qualified package names alone`() {
        val projectDir = createTestFile("""
            package com.example;

            import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
            import net.minecraftforge.event.TickEvent;

            public class TestMod {
                void onTick(TickEvent.PlayerTickEvent event) {
                    event.player.sendSystemMessage(null);
                    org.bukkit.event.player.PlayerTeleportEvent cause = null;
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "tick-event-player-field" }, result.changes.joinToString("\n") { it.ruleId })
        assertTrue(content.contains("event.getEntity().sendSystemMessage(null);"), content)
        assertTrue(content.contains("import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;"), content)
        assertTrue(content.contains("org.bukkit.event.player.PlayerTeleportEvent cause = null;"), content)
    }

    @Test
    fun `migrates chunk progress listener nbt paths armor trims and loot table keys`() {
        val projectDir = createTestFile("""
            package com.example;

            import java.nio.file.Path;
            import java.util.Optional;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.NbtIo;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.progress.LoggerChunkProgressListener;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.armortrim.ArmorTrim;
            import net.minecraft.world.level.Level;

            public class TestMod {
                static String chunkFileName(int x, int z) { return x + "." + z; }

                void run(Path dir, CompoundTag tag, Level level, ItemStack armor, LivingEntity entity) throws Exception {
                    LoggerChunkProgressListener listener = new LoggerChunkProgressListener(0);
                    NbtIo.writeCompressed(tag, dir.resolve("meta.nbt").toFile());
                    CompoundTag read = NbtIo.readCompressed(dir.resolve("chunk.nbt").toFile());
                    NbtIo.writeCompressed(tag, dir.resolve(chunkFileName(1, 2)).toFile());
                    CompoundTag nested = NbtIo.readCompressed(dir.resolve(chunkFileName(0, 0)).toFile());
                    Optional<ArmorTrim> trim = ArmorTrim.getTrim(level.registryAccess(), armor);
                    ResourceLocation lootId = entity.getLootTable();
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(content.contains("LoggerChunkProgressListener.create(0)"), content)
        assertTrue(content.contains("""NbtIo.writeCompressed(tag, dir.resolve("meta.nbt"));"""), content)
        assertTrue(content.contains("""NbtIo.readCompressed(dir.resolve("chunk.nbt"), net.minecraft.nbt.NbtAccounter.unlimitedHeap())"""), content)
        assertTrue(content.contains("java.util.Optional.ofNullable(armor.get(net.minecraft.core.component.DataComponents.TRIM))"), content)
        assertTrue(content.contains("ResourceLocation lootId = entity.getLootTable().location();"), content)
        assertTrue(content.contains("NbtIo.writeCompressed(tag, dir.resolve(chunkFileName(1, 2)));"), content)
        assertTrue(content.contains("NbtIo.readCompressed(dir.resolve(chunkFileName(0, 0)), net.minecraft.nbt.NbtAccounter.unlimitedHeap())"), content)
        assertFalse(content.contains(".toFile()"), content)
    }

    @Test
    fun `qualified forge annotations and registry object holders migrate`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.registries.RegistryObject;

            @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="example",value=net.minecraftforge.api.distmarker.Dist.CLIENT)
            public class TestMod {
                public static final RegistryObject<com.example.block.HerbBlock> HERB = null;
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(content.contains("@net.neoforged.fml.common.EventBusSubscriber(modid=\"example\",value=net.neoforged.api.distmarker.Dist.CLIENT)"), content)
        assertTrue(content.contains("DeferredHolder<com.example.block.HerbBlock, com.example.block.HerbBlock> HERB"), content)
    }

    @Test
    fun `block entity positions open menus directly and create registry constants map to 1_21`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.projectile.AbstractProjectileDispenseBehavior;
            import net.minecraftforge.network.NetworkHooks;
            import net.minecraftforge.registries.DeferredRegister;
            import net.minecraftforge.registries.ForgeRegistries;
            import org.spongepowered.asm.mixin.Mixin;

            public class TestMod extends SmartBlockEntity {
                private static final DeferredRegister<Object> REGISTER = DeferredRegister.create(ForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, "example");
                static final Object WOODCUTTING = ForgeRegistries.RECIPE_TYPES.getValue(new ResourceLocation("druidcraft", "woodcutting"));

                void open(ServerPlayer player) {
                    NetworkHooks.openScreen(player, this, worldPosition);
                }

                @Mixin(AbstractProjectileDispenseBehavior.class)
                interface AbstractProjectileDispenseBehaviorAccessor {}
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(content.contains("(player).openMenu(this, worldPosition);"), content)
        assertTrue(content.contains("DeferredRegister.create(NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, \"example\")"), content)
        assertTrue(content.contains("BuiltInRegistries.RECIPE_TYPE.get(new ResourceLocation(\"druidcraft\", \"woodcutting\"))"), content)
        assertTrue(content.contains("@Mixin(ProjectileDispenseBehavior.class)"), content)
        assertTrue(content.contains("interface AbstractProjectileDispenseBehaviorAccessor {}"), content)
        assertFalse(Regex("(?<!Neo)ForgeRegistries\\.").containsMatchIn(content), content)
    }

    @Test
    fun `tick event player field rewrite leaves player() accessor calls alone`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod {
                void onTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event, org.spongepowered.api.event.network.ServerSideConnectionEvent.Disconnect quit) {
                    Object tickPlayer = event.player;
                    Object quitPlayer = quit.player();
                    Object other = event.player();
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(content.contains("Object tickPlayer = event.getEntity();"), content)
        assertTrue(content.contains("Object quitPlayer = quit.player();"), content)
        assertTrue(content.contains("Object other = event.player();"), content)
    }

    @Test
    fun `forge convention tags become common tags and tag element writes stay for the structural pass`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.ItemStack;

            public class TestMod {
                static ResourceLocation id(String value) { return ResourceLocation.parse(value); }

                void run(ItemStack stack) {
                    var milk = TagKey.create(Registries.FLUID, id("forge:milk"));
                    stack.getOrCreateTagElement("Custom").putString("Id", "x");
                    var read = stack.getOrCreateTagElement("Custom").getString("Id");
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(content.contains("""TagKey.create(Registries.FLUID, id("c:milk"))"""), content)
        assertTrue(content.contains("""stack.getOrCreateTagElement("Custom").putString("Id", "x");"""), content)
        assertTrue(content.contains("""var read = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getCompound("Custom").getString("Id");"""), content)
    }

    @Test
    fun `shaped recipe checks empty handlers step heights and fire resistance follow 1_21`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.common.base.Supplier;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraftforge.common.crafting.IShapedRecipe;
            import net.minecraftforge.items.IItemHandler;
            import net.minecraftforge.items.wrapper.EmptyHandler;

            public class TestMod {
                Supplier<ItemStack> result;

                int width(Recipe<?> recipe) {
                    return recipe instanceof IShapedRecipe<?> ? ((IShapedRecipe<?>) recipe).getWidth() : 1;
                }

                IItemHandler empty() {
                    return EmptyHandler.INSTANCE;
                }

                float step(Entity entity) {
                    return entity.getStepHeight();
                }

                boolean fireproof(ItemStack stack) {
                    return stack.getItem().isFireResistant();
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(content.contains("return recipe instanceof ShapedRecipe ? ((ShapedRecipe) recipe).getWidth() : 1;"), content)
        assertTrue(content.contains("import net.minecraft.world.item.crafting.ShapedRecipe;"), content)
        assertFalse(content.contains("IShapedRecipe"), content)
        assertTrue(content.contains("return EmptyItemHandler.INSTANCE;"), content)
        assertTrue(content.contains("import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;"), content)
        assertFalse(content.contains("wrapper.EmptyHandler;"), content)
        assertTrue(content.contains("return entity.maxUpStep();"), content)
        assertTrue(content.contains("return stack.has(net.minecraft.core.component.DataComponents.FIRE_RESISTANT);"), content)
        assertTrue(content.contains("import java.util.function.Supplier;"), content)
        assertFalse(content.contains("com.google.common.base.Supplier"), content)
        assertEquals(1, Regex("import java.util.function.Supplier;").findAll(content).count(), content)
    }

    @Test
    fun `widget buttons become sprites and dead returns after playerWillDestroy vanish`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;

            import static net.minecraft.client.gui.components.AbstractWidget.WIDGETS_LOCATION;

            public class TestMod extends Block {
                public TestMod(Properties properties) { super(properties); }

                void render(GuiGraphics graphics, int x, int w, int h) {
                    graphics.blitNineSliced(WIDGETS_LOCATION, x, 0, w, h, 20, 4, 200, 20, 0, 66);
                    graphics.blitNineSliced(WIDGETS_LOCATION, x, 0, w, h, 20, 4, 200, 20, 0, 46);
                }

                @Override
                public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                    if (true) {
                        super.playerWillDestroy(level, pos, state, player);
                        return;
                    }
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(content.contains("""graphics.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace("widget/button"), x, 0, w, h);"""), content)
        assertTrue(content.contains("""graphics.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace("widget/button_disabled"), x, 0, w, h);"""), content)
        assertFalse(content.contains("WIDGETS_LOCATION"), content)
        assertTrue(content.replace("\r\n", "\n").contains("return super.playerWillDestroy(level, pos, state, player);\n        }"), content)
        assertFalse(content.contains("return;"), content)
    }

    @Test
    fun `clone overload family waits for structural ownership proof and preserves direct callers`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;

            public class TestMod extends Block {
                public TestMod(Properties properties) {
                    super(properties);
                }

                void equip(Level world, Player player, BlockPos pos, BlockState state) {
                    player.setItemSlot(EquipmentSlot.CHEST, getCloneItemStack(world, pos, state));
                }

                @Override
                public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
                    return super.getCloneItemStack(level, pos, state);
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val afterText = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(afterText.contains("getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state)"), afterText)
        assertFalse(afterText.contains("getCloneItemStack(LevelReader level, BlockPos pos, BlockState state)"), afterText)
        assertFalse(afterText.contains("import net.minecraft.world.level.LevelReader;"), afterText)

        StructuralRefactorPass().apply(projectDir)
        val migrated = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(migrated.contains("getCloneItemStack(LevelReader level, BlockPos pos, BlockState state)"), migrated)
        assertTrue(Regex("""getCloneItemStack\(\s*BlockState state_,\s*HitResult target,\s*LevelReader level_,\s*BlockPos pos_,\s*Player player_\s*\)""").containsMatchIn(migrated), migrated)
        assertTrue(migrated.contains("return super.getCloneItemStack(level, pos, state);"), migrated)
        assertTrue(migrated.contains("player.setItemSlot(EquipmentSlot.CHEST, getCloneItemStack(world, pos, state));"), migrated)
        assertTrue(migrated.contains("return getCloneItemStack(level_, pos_, state_);"), migrated)
        assertFalse(migrated.contains("new ItemStack(this)"), migrated)
        assertFalse(migrated.contains("@SuppressWarnings"), migrated)
    }


    @Test
    fun `enchantment table types follow vanilla 121 class names`() {
        val projectDir = createTestFile("""
            package com.example;
            import net.minecraft.world.level.block.EnchantmentTableBlock;
            import net.minecraft.world.level.block.entity.EnchantmentTableBlockEntity;
            class TestMod {
                boolean read(Object value) {
                    return value instanceof EnchantmentTableBlockEntity
                        && EnchantmentTableBlock.BOOKSHELF_OFFSETS.length > 0;
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("import net.minecraft.world.level.block.EnchantingTableBlock;"), transformed)
        assertTrue(transformed.contains("import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;"), transformed)
        assertTrue(transformed.contains("value instanceof EnchantingTableBlockEntity"), transformed)
        assertTrue(transformed.contains("EnchantingTableBlock.BOOKSHELF_OFFSETS"), transformed)
        assertFalse(transformed.contains("EnchantmentTableBlock"), transformed)
    }


    @Test
    fun `DistExecutor owner evidence is preserved for the structural pass without placeholders`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.neoforged.fml.DistExecutor;

            public class TestMod {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.neoforged.fml.DistExecutor;"))
        assertFalse(transformed.contains("[forge2neo]"))
        assertFalse(transformed.contains("removed in NeoForge"))
    }


    @Test
    fun `legacy hand references after useWithoutItem migration use main hand`() {
        val projectDir = createTestFile("""
            public class TestMod {
                void useWithoutItem(Player player, ItemStack heldStack) {
                    ItemStack stack = player.getItemInHand(hand);
                    player.swing(handIn);
                    heldStack.hurtAndBreak(1, player, p -> p.onEquippedItemBroken(hand));
                    this.takePlates(level, pos, state, player, handIn);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.InteractionHand;"))
        assertTrue(transformed.contains("import net.minecraft.world.entity.EquipmentSlot;"))
        assertTrue(transformed.contains("ItemStack stack = player.getMainHandItem();"))
        assertTrue(transformed.contains("player.swing(InteractionHand.MAIN_HAND);"))
        assertTrue(transformed.contains("heldStack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);"))
        assertTrue(transformed.contains("this.takePlates(level, pos, state, player, InteractionHand.MAIN_HAND);"))
        assertFalse(transformed.contains("handIn"))
        assertFalse(transformed.contains("onEquippedItemBroken(hand"))
    }


    @Test
    fun `legacy gui overlay owner migrates method reference fields without touching text`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.client.gui.overlay.IGuiOverlay;

            public class TestMod {
                private static final String DOC = "IGuiOverlay OVERLAY";
                public static final IGuiOverlay OVERLAY = TestMod::render;

                private static void render(Object graphics, Object tracker) {}
                // IGuiOverlay remains documentation.
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(result.changes.any { it.ruleId == "gui-overlay-layer-owner" })
        assertTrue(transformed.contains("import net.minecraft.client.gui.LayeredDraw;"), transformed)
        assertTrue(transformed.contains("public static final LayeredDraw.Layer OVERLAY = TestMod::render;"), transformed)
        assertTrue(transformed.contains("\"IGuiOverlay OVERLAY\""), transformed)
        assertTrue(transformed.contains("// IGuiOverlay remains documentation."), transformed)
    }


    @Test
    fun `network hooks open screen resolves inherited block entity world position`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.network.NetworkHooks;

            public class TestMod extends IntermediateMenuBlockEntity {
                public void open(ServerPlayer player, MenuProvider menu) {
                    NetworkHooks.openScreen(player, menu, worldPosition);
                }
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.resolve("IntermediateMenuBlockEntity.java").writeText("""
            package com.example;

            public abstract class IntermediateMenuBlockEntity extends BaseMenuBlockEntity {
            }
        """.trimIndent())
        srcDir.resolve("BaseMenuBlockEntity.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.entity.BlockEntity;

            public abstract class BaseMenuBlockEntity extends BlockEntity {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = srcDir.resolve("TestMod.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("(player).openMenu(menu, worldPosition);"), transformed)
        assertFalse(transformed.contains("NetworkHooks.openScreen"), transformed)
    }


    @Test
    fun `recipe type lookup and entity serializer key use their explicit target registries`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.network.syncher.EntityDataSerializer;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraftforge.registries.DeferredRegister;
            import net.minecraftforge.registries.ForgeRegistries;

            public class TestMod {
                static final DeferredRegister<EntityDataSerializer<?>> SERIALIZERS =
                    DeferredRegister.create(ForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, "example");
                static final RecipeType<?> OPTIONAL_TYPE = ForgeRegistries.RECIPE_TYPES.getValue(
                    ResourceLocation.fromNamespaceAndPath("other", "cutting")
                );
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(transformed.contains("NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS"))
        assertTrue(transformed.contains("BuiltInRegistries.RECIPE_TYPE.get("))
        assertTrue(transformed.contains("import net.neoforged.neoforge.registries.NeoForgeRegistries;"))
        assertTrue(transformed.contains("import net.minecraft.core.registries.BuiltInRegistries;"))
        assertFalse(Regex("""(?<!Neo)\bForgeRegistries\b""").containsMatchIn(transformed))
    }



    @Test
    fun `unrelated fluent registration chains remain structurally intact`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.example.client.ExampleRenderer;
            import com.example.client.ExampleVisual;

            public class TestMod {
                static final Object ENTRY = REGISTRATE
                    .blockEntity("example", ExampleBlockEntity::new)
                    .visual(() -> ExampleVisual::new)
                    .validBlocks(EXAMPLE_BLOCK)
                    .renderer(() -> ExampleRenderer::new)
                    .register();
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${'$'}{result.errors}")
        assertTrue(transformed.contains(".blockEntity(\"example\", ExampleBlockEntity::new)"), transformed)
        assertTrue(transformed.contains(".visual(() -> ExampleVisual::new)"), transformed)
        assertTrue(transformed.contains(".validBlocks(EXAMPLE_BLOCK)"), transformed)
        assertTrue(transformed.contains(".renderer(() -> ExampleRenderer::new)"), transformed)
        assertTrue(transformed.contains(".register();"), transformed)

        projectDir.resolve("src/main/java/com/example/ModNetwork.java").writeText("""
            package com.example;

            public final class ModNetwork {
                private ModNetwork() {}
            }
        """.trimIndent())

        val structuralResult = StructuralRefactorPass().apply(projectDir)
        val structurallyTransformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(structuralResult.errors.isEmpty(), "Unexpected structural errors: ${structuralResult.errors}")
        assertTrue(structurallyTransformed.contains(".blockEntity(\"example\", ExampleBlockEntity::new)"), structurallyTransformed)
        assertTrue(structurallyTransformed.contains(".visual(() -> ExampleVisual::new)"), structurallyTransformed)
        assertTrue(structurallyTransformed.contains(".validBlocks(EXAMPLE_BLOCK)"), structurallyTransformed)
        assertTrue(structurallyTransformed.contains(".renderer(() -> ExampleRenderer::new)"), structurallyTransformed)
        assertTrue(structurallyTransformed.contains(".register();"), structurallyTransformed)
    }


    @Test
    fun `registrate custom enchantments become data keys and item tags without marker shims`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String ID = "example";
                public static final String NAME = "Example";
                private static final CreateRegistrate REGISTRATE = CreateRegistrate.create(ID);
                public static CreateRegistrate registrate() { return REGISTRATE; }
            }
        """.trimIndent())
        srcDir.resolve("ModItems.java").writeText("""
            package com.example;

            import com.tterrag.registrate.util.entry.ItemEntry;

            public final class ModItems {
                private static final CreateRegistrate REGISTRATE = ExampleMod.registrate();
                public static final ItemEntry<BacktankItem> BACKTANK = REGISTRATE
                    .item("backtank", BacktankItem::new)
                    .register();
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import com.tterrag.registrate.util.entry.RegistryEntry;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment.Rarity;

            public final class ModEnchantments {
                private static final CreateRegistrate REGISTRATE = ExampleMod.registrate();

                public static final RegistryEntry<CapacityEnchantment> CAPACITY = REGISTRATE.object("capacity")
                    .enchantment(EnchantmentCategory.ARMOR_CHEST, CapacityEnchantment::new)
                    .addSlots(EquipmentSlot.CHEST)
                    .rarity(Rarity.COMMON)
                    .register();
            }
        """.trimIndent())
        val enchantmentFile = srcDir.resolve("CapacityEnchantment.java")
        enchantmentFile.writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class CapacityEnchantment extends Enchantment {
                public CapacityEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }

                @Override
                public int getMaxLevel() { return 3; }

                @Override
                public boolean canEnchant(ItemStack stack) {
                    return stack.getItem() instanceof CapacityItem;
                }

                public interface CapacityItem {}
            }
        """.trimIndent())
        srcDir.resolve("BacktankItem.java").writeText("""
            package com.example;

            import com.example.CapacityEnchantment.CapacityItem;

            public class BacktankItem implements CapacityItem {
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)
        val registry = srcDir.resolve("ModEnchantments.java").readText()
        val item = srcDir.resolve("BacktankItem.java").readText()
        val enchantmentJson = tempDir.resolve("src/generated/resources/data/example/enchantment/capacity.json")
        val itemTag = tempDir.resolve("src/generated/resources/data/example/tags/item/enchantable/backtank.json")

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${'$'}{result.errors}")
        assertTrue(registry.contains("ResourceKey<Enchantment> CAPACITY"), registry)
        assertFalse(registry.contains(".enchantment("), registry)
        assertFalse(registry.contains("RegistryEntry<CapacityEnchantment>"), registry)
        assertTrue(enchantmentJson.exists(), enchantmentJson.toString())
        assertTrue(enchantmentJson.readText().contains("#example:enchantable/backtank"), enchantmentJson.readText())
        assertTrue(itemTag.exists(), itemTag.toString())
        assertTrue(itemTag.readText().contains("example:backtank"), itemTag.readText())
        assertFalse(item.contains("implements CapacityItem"), item)
        assertFalse(item.contains("CapacityEnchantment.CapacityItem"), item)
        assertFalse(enchantmentFile.exists())
        assertTrue(result.changes.any { it.ruleId == "custom-enchantment-resourcekey-context" })
        assertTrue(result.changes.any { it.ruleId == "text-enchantment-marker-to-item-tag" })
    }


    @Test
    fun `registrate item factories preserve concrete and inherited enchantment support`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String ID = "example";
                private static final CreateRegistrate REGISTRATE = CreateRegistrate.create(ID);
                public static CreateRegistrate registrate() { return REGISTRATE; }
            }
        """.trimIndent())
        srcDir.resolve("ModItems.java").writeText("""
            package com.example;

            import com.tterrag.registrate.util.entry.ItemEntry;

            public final class ModItems {
                private static final CreateRegistrate REGISTRATE = ExampleMod.registrate();
                public static final ItemEntry<ToolItem> TOOL = REGISTRATE
                    .item("tool", ToolItem::new)
                    .register();
                public static final ItemEntry<? extends MarkerItem>
                    MARKER = REGISTRATE.item("marker", MarkerItem::new).register(),
                    LAYERED = REGISTRATE.item("layered", properties -> new MarkerItem.Layered()).register();
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import com.tterrag.registrate.util.entry.RegistryEntry;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment.Rarity;

            public final class ModEnchantments {
                private static final CreateRegistrate REGISTRATE = ExampleMod.registrate();
                public static final RegistryEntry<ConcreteEnchantment> CONCRETE = REGISTRATE.object("concrete")
                    .enchantment(EnchantmentCategory.BOW, ConcreteEnchantment::new)
                    .addSlots(EquipmentSlot.MAINHAND)
                    .rarity(Rarity.UNCOMMON)
                    .register();
                public static final RegistryEntry<MarkerEnchantment> MARKER = REGISTRATE.object("marker")
                    .enchantment(EnchantmentCategory.ARMOR_CHEST, MarkerEnchantment::new)
                    .addSlots(EquipmentSlot.CHEST)
                    .rarity(Rarity.COMMON)
                    .register();
            }
        """.trimIndent())
        srcDir.resolve("ConcreteEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class ConcreteEnchantment extends Enchantment {
                public ConcreteEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }
                public boolean canApplyAtEnchantingTable(ItemStack stack) {
                    return stack.getItem() instanceof ToolItem;
                }
            }
        """.trimIndent())
        srcDir.resolve("MarkerEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class MarkerEnchantment extends Enchantment {
                public MarkerEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }
                public boolean canEnchant(ItemStack stack) {
                    return stack.getItem() instanceof MarkerSupport;
                }
                public interface MarkerSupport {}
            }
        """.trimIndent())
        srcDir.resolve("ToolItem.java").writeText("""
            package com.example;
            public class ToolItem {}
        """.trimIndent())
        val markerItemFile = srcDir.resolve("MarkerItem.java")
        markerItemFile.writeText("""
            package com.example;

            import com.example.MarkerEnchantment.MarkerSupport;

            public class MarkerItem implements MarkerSupport {
                public static class Layered extends MarkerItem {}
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)
        val concreteTag = tempDir.resolve("src/generated/resources/data/example/tags/item/enchantable/tool.json")
        val markerTag = tempDir.resolve("src/generated/resources/data/example/tags/item/enchantable/marker.json")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(concreteTag.exists(), concreteTag.toString())
        assertTrue(concreteTag.readText().contains("example:tool"), concreteTag.readText())
        assertTrue(markerTag.exists(), markerTag.toString())
        assertTrue(markerTag.readText().contains("example:marker"), markerTag.readText())
        assertTrue(markerTag.readText().contains("example:layered"), markerTag.readText())
        assertFalse(markerItemFile.readText().contains("implements MarkerSupport"), markerItemFile.readText())
    }



    @Test
    fun `catnip registry key helper migration is owner exact and leaves other registry methods intact`() {
        val projectDir = createTestFile("""
            package com.example;

            import java.util.function.Function;
            import net.createmod.catnip.platform.CatnipServices;

            public class UsesRegistryHelper {
                Object key(Object value) {
                    return CatnipServices.REGISTRIES.getKeyOrThrow(value);
                }

                Function<Object, Object> keyFunction() {
                    return CatnipServices.REGISTRIES::getKeyOrThrow;
                }

                Object lookup(Object id) {
                    return CatnipServices.REGISTRIES.getItem(id);
                }

                String documentation = "CatnipServices.REGISTRIES.getKeyOrThrow(value)";
                // CatnipServices.REGISTRIES::getKeyOrThrow remains documentation.
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("net.createmod.catnip.registry.RegisteredObjectsHelper.getKeyOrThrow(value)"), transformed)
        assertTrue(transformed.contains("net.createmod.catnip.registry.RegisteredObjectsHelper::getKeyOrThrow"), transformed)
        assertTrue(transformed.contains("CatnipServices.REGISTRIES.getItem(id)"), transformed)
        assertTrue(transformed.contains("\"CatnipServices.REGISTRIES.getKeyOrThrow(value)\""), transformed)
        assertTrue(transformed.contains("// CatnipServices.REGISTRIES::getKeyOrThrow remains documentation."), transformed)
        assertTrue(transformed.contains("import net.createmod.catnip.platform.CatnipServices;"), transformed)
        assertTrue(result.changes.any { it.ruleId == "catnip-registered-objects-key-helper" })
    }


    @Test
    fun `catnip loader service owner migration requires exact import evidence`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.createmod.catnip.platform.ForgeCatnipServices;

            public class UsesFluidRenderer {
                Object renderer() {
                    return ForgeCatnipServices.FLUID_RENDERER;
                }

                String documentation = "ForgeCatnipServices.FLUID_RENDERER";
                // ForgeCatnipServices remains documentation.
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("import net.createmod.catnip.platform.NeoForgeCatnipServices;"), transformed)
        assertTrue(transformed.contains("return NeoForgeCatnipServices.FLUID_RENDERER;"), transformed)
        assertTrue(transformed.contains("\"ForgeCatnipServices.FLUID_RENDERER\""), transformed)
        assertTrue(transformed.contains("// ForgeCatnipServices remains documentation."), transformed)
        assertTrue(result.changes.any { it.ruleId == "catnip-neoforge-platform-services" })
    }


    @Test
    fun `dynamic trees public API classes migrate exactly`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.ferreusveritas.dynamictrees.api.TreeHelper;
            import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
            import com.ferreusveritas.dynamictrees.block.branch.TrunkShellBlock;
            import com.ferreusveritas.dynamictrees.util.BranchDestructionData;

            public class TestMod {
                BranchBlock branch;
                TrunkShellBlock shell;
                BranchDestructionData data;

                boolean branch(Object block) {
                    return com.ferreusveritas.dynamictrees.api.TreeHelper.isBranch(block);
                }

                String documentation = "com.ferreusveritas.dynamictrees.api.TreeHelper";
                // com.ferreusveritas.dynamictrees.util.BranchDestructionData remains documentation.
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(projectDir)
        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("import com.dtteam.dynamictrees.tree.TreeHelper;"), transformed)
        assertTrue(transformed.contains("import com.dtteam.dynamictrees.block.branch.BranchBlock;"), transformed)
        assertTrue(transformed.contains("import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;"), transformed)
        assertTrue(transformed.contains("import com.dtteam.dynamictrees.api.network.BranchDestructionData;"), transformed)
        assertTrue(transformed.contains("com.dtteam.dynamictrees.tree.TreeHelper.isBranch(block)"), transformed)
        assertTrue(transformed.contains("\"com.ferreusveritas.dynamictrees.api.TreeHelper\""), transformed)
        assertTrue(
            transformed.contains("// com.ferreusveritas.dynamictrees.util.BranchDestructionData remains documentation."),
            transformed
        )
        assertTrue(result.changes.any { it.ruleId == "dynamictrees-tree-helper-class" })
        assertTrue(result.changes.any { it.ruleId == "dynamictrees-branch-destruction-data-class" })
        assertTrue(result.changes.any { it.ruleId == "dynamictrees-branch-block-class" })
        assertTrue(result.changes.any { it.ruleId == "dynamictrees-trunk-shell-block-class" })
    }
}
