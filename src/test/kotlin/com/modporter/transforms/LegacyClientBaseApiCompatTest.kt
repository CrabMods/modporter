package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyClientBaseApiCompat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyClientBaseApiCompatTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `late gui and provider repairs preserve contexts and lazy registration`() {
        val source = """
            package example;

            import java.util.List;
            import net.createmod.catnip.gui.widget.AbstractSimiWidget;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.Font;
            import net.minecraft.client.gui.Gui;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.LayeredDraw;
            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

            class ScrollInput extends AbstractSimiWidget {
                @Override
                public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
                    return delta > 0;
                }
            }

            class StockScreen extends Screen {
                ItemStack stack;
                List<Component> tooltip() {
                    return stack.getTooltipLines(minecraft.player, TooltipFlag.NORMAL);
                }
            }

            class AddressEditBox {
                AddressEditBox(
                    Screen screen, Font font, int x, int y, int width, int height,
                    boolean anchorToBottom
                ) {
                    this(screen, font, x, y, width, height, anchorToBottom, null);
                }

                AddressEditBox(
                    Screen screen, Font font, int x, int y, int width, int height,
                    boolean anchorToBottom, String localAddress,
                    HolderLookup.Provider registries
                ) {}
            }

            class BlueprintOverlayRenderer {
                static final LayeredDraw.Layer OVERLAY =
                    (graphics, delta) -> BlueprintOverlayRenderer.renderOverlay(
                        Minecraft.getInstance().gui,
                        graphics,
                        delta.getGameTimeDeltaPartialTick(false),
                        graphics.guiWidth(),
                        graphics.guiHeight());

                static void renderOverlay(
                    Gui gui, GuiGraphics graphics, float partialTicks,
                    int width, int height, HolderLookup.Provider registries
                ) {}

                static ItemStack rebuild(
                    Minecraft mc, Recipe recipe, CraftingInput input
                ) {
                    return recipe.assemble(
                        input,
                        mc.sectionAt.getBlueprintWorld().registryAccess());
                }
            }

            interface Recipe {
                ItemStack assemble(
                    CraftingInput input, HolderLookup.Provider registries);
            }

            class ClientEvents {
                static void register(ModContainer container) {
                    container.registerExtensionPoint(
                        IConfigScreenFactory.class,
                        () -> (mc, previousScreen) -> previousScreen);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyClientBaseApiCompat(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertTrue(
            compact.contains(
                "mouseScrolled(doublemouseX,doublemouseY," +
                    "doublescrollX,doubledelta)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "stack.getTooltipLines(" +
                    "Item.TooltipContext.of(minecraft.level)," +
                    "minecraft.player,TooltipFlag.NORMAL)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "this(screen,font,x,y,width,height,anchorToBottom,null," +
                    "Minecraft.getInstance().level.registryAccess())"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "graphics.guiHeight()," +
                    "net.minecraft.client.Minecraft.getInstance()" +
                    ".level.registryAccess())"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "recipe.assemble(input,mc.level.registryAccess())"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "(java.util.function.Supplier<IConfigScreenFactory>)" +
                    "()->(mc,previousScreen)->previousScreen"
            ),
            migrated
        )
        assertTrue(
            migrated.contains("import net.minecraft.world.item.Item;"),
            migrated
        )
        assertEquals(
            migrated,
            pass.migrateLegacyClientBaseApiCompat(migrated)
        )
    }

    @Test
    fun `goal spawn ambient occlusion and debug repairs keep source policy`() {
        val source = """
            package example;

            import net.minecraft.client.Minecraft;
            import net.minecraft.client.renderer.RenderType;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.Mob;
            import net.minecraft.world.entity.SpawnPlacementTypes;
            import net.minecraft.world.entity.ai.goal.GoalSelector;
            import net.minecraft.world.entity.ai.goal.target.TargetGoal;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.NaturalSpawner;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.client.model.data.ModelData;

            class Cardboard {
                static void clear(Mob mob) {
                    mob.targetSelector.getRunningGoals()
                        .forEach(wrappedGoal -> {
                            if (wrappedGoal.getGoal() instanceof TargetGoal target)
                                target.stop();
                        });
                }
            }

            class SoulPulse {
                static boolean canSpawn(Level world, BlockPos at) {
                    EntityType<?> dummy = EntityType.ZOMBIE;
                    return world != null &&
                        NaturalSpawner.isSpawnPositionOk(
                            SpawnPlacementTypes.ON_GROUND,
                            world,
                            at,
                            dummy);
                }
            }

            class PipeModel {
                boolean ao;

                @Override
                public boolean useAmbientOcclusion(
                    BlockState state, RenderType renderType
                ) {
                    return ao;
                }

                @Override
                public boolean useAmbientOcclusion(BlockState state) {
                    return ao;
                }

                @Override
                public boolean useAmbientOcclusion() {
                    return ao;
                }

                ModelData data;
            }

            class Debugger {
                static boolean active() {
                    return Minecraft.getInstance().options.renderDebug;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyClientBaseApiCompat(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertTrue(
            compact.contains(
                "mob.targetSelector.getAvailableGoals().stream()" +
                    ".filter(modporterRunningGoal->" +
                    "modporterRunningGoal.isRunning()).forEach("
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "world!=null&&SpawnPlacementTypes.ON_GROUND" +
                    ".isSpawnPositionOk(world,at,dummy)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "TriStateuseAmbientOcclusion(" +
                    "BlockStatestate,ModelDatadata,RenderTyperenderType)"
            ),
            migrated
        )
        assertTrue(
            compact.contains("return(ao)?TriState.TRUE:TriState.FALSE;") ||
                compact.contains("returnao?TriState.TRUE:TriState.FALSE;"),
            migrated
        )
        assertFalse(
            compact.contains(
                "@OverridepublicbooleanuseAmbientOcclusion(" +
                    "BlockStatestate)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "Minecraft.getInstance().getDebugOverlay()" +
                    ".showDebugScreen()"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "import net.neoforged.neoforge.common.util.TriState;"
            ),
            migrated
        )
        assertEquals(
            migrated,
            pass.migrateLegacyClientBaseApiCompat(migrated)
        )
    }

    @Test
    fun `rendering values and shared panorama keep their original units and branches`() {
        val source = """
            package example;

            import com.mojang.blaze3d.vertex.DefaultVertexFormat;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import com.mojang.blaze3d.vertex.VertexFormat;
            import net.createmod.catnip.gui.AbstractSimiScreen;
            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.client.gui.screens.TitleScreen;
            import net.minecraft.client.renderer.PanoramaRenderer;
            import net.minecraft.world.phys.Vec3;

            class Particle {
                void render(VertexConsumer builder) {
                    Vec3 vec = new Vec3(1, 2, 3);
                    builder.addVertex(vec.x, vec.y, vec.z).setLight(15);
                }
            }

            class Quads {
                static final VertexFormat FORMAT = DefaultVertexFormat.BLOCK;
                static final int VERTEX_STRIDE = FORMAT.getIntegerSize();
            }

            class Menu extends AbstractSimiScreen {
                static final PanoramaRenderer PANORAMA = null;
                PanoramaRenderer vanillaPanorama;

                Menu(Screen parent) {
                    if (parent instanceof TitleScreen titleScreen)
                        vanillaPanorama = titleScreen.panorama;
                    else
                        vanillaPanorama =
                            new PanoramaRenderer(TitleScreen.CUBE_MAP);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyClientBaseApiCompat(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertTrue(
            compact.contains(
                "builder.addVertex((float)(vec.x),(float)(vec.y)," +
                    "(float)(vec.z)).setLight(15)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "VERTEX_STRIDE=FORMAT.getVertexSize()/Integer.BYTES"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "if(parentinstanceofTitleScreentitleScreen)" +
                    "vanillaPanorama=Screen.PANORAMA;" +
                    "elsevanillaPanorama=" +
                    "newPanoramaRenderer(Screen.CUBE_MAP);"
            ),
            migrated
        )
        assertEquals(
            migrated,
            pass.migrateLegacyClientBaseApiCompat(migrated)
        )
    }

    @Test
    fun `shopping list tooltips retain their context level for entry baking`() {
        val source = """
            package com.simibubi.create.content.logistics.tableCloth;

            import java.util.List;
            import javax.annotation.Nullable;
            import net.minecraft.core.BlockPos;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;
            import net.minecraft.world.level.LevelAccessor;

            public class ShoppingListItem extends Item {
                public record ShoppingList() {
                    public Object bakeEntries(
                        LevelAccessor level, @Nullable BlockPos ignored
                    ) {
                        return level.getBlockEntity(ignored);
                    }
                }

                public ShoppingListItem(Properties properties) {
                    super(properties);
                }

                static ShoppingList getList(ItemStack stack) {
                    return null;
                }

                @Override
                public void appendHoverText(
                    ItemStack stack,
                    Item.TooltipContext context,
                    List<Component> tooltip,
                    TooltipFlag flag
                ) {
                    ShoppingList list = getList(stack);
                    if (list != null) {
                        Object baked = list.bakeEntries(context, null);
                    }
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyClientBaseApiCompat(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertTrue(
            compact.contains("list.bakeEntries(context.level(),null)"),
            migrated
        )
        assertTrue(
            compact.contains("returnlevel.getBlockEntity(ignored);"),
            migrated
        )
        assertFalse(migrated.contains("Minecraft.getInstance()"), migrated)
        assertEquals(
            migrated,
            pass.migrateLegacyClientBaseApiCompat(migrated)
        )
    }

    @Test
    fun `deployer packet listener cookie retains the fake player profile`() {
        val source = """
            package com.simibubi.create.content.kinetics.deployer;

            import net.minecraft.network.Connection;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.server.network.ServerGamePacketListenerImpl;
            import net.neoforged.neoforge.common.util.FakePlayer;

            public class DeployerFakePlayer extends FakePlayer {
                private static final Connection NETWORK_MANAGER = null;

                public DeployerFakePlayer(ServerLevel level) {
                    super(level, null);
                }

                private static class FakePlayNetHandler
                    extends ServerGamePacketListenerImpl {
                    public FakePlayNetHandler(
                        MinecraftServer server, ServerPlayer playerIn
                    ) {
                        super(server, NETWORK_MANAGER, playerIn);
                    }
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyClientBaseApiCompat(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertTrue(
            compact.contains(
                "super(server,NETWORK_MANAGER,playerIn," +
                    "CommonListenerCookie.createInitial(" +
                    "playerIn.getGameProfile(),false));"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "import net.minecraft.server.network.CommonListenerCookie;"
            ),
            migrated
        )
        assertEquals(
            migrated,
            pass.migrateLegacyClientBaseApiCompat(migrated)
        )
    }

    @Test
    fun `shopping and deployer near matches retain their declared contracts`() {
        val shopping = """
            package com.simibubi.create.content.logistics.tableCloth;

            import java.util.List;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;

            public class ShoppingListItem extends Item {
                public record ShoppingList() {
                    Object bakeEntries(
                        HolderLookup.Provider registries, BlockPos ignored
                    ) {
                        return registries;
                    }
                }

                ShoppingListItem(Properties properties) {
                    super(properties);
                }

                @Override
                public void appendHoverText(
                    ItemStack stack,
                    Item.TooltipContext context,
                    List<Component> tooltip,
                    TooltipFlag flag
                ) {
                    ShoppingList list = null;
                    list.bakeEntries(context, null);
                }
            }
        """.trimIndent()
        val deployer = """
            package com.simibubi.create.content.kinetics.deployer;

            import net.minecraft.network.Connection;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.server.network.CommonListenerCookie;
            import net.minecraft.server.network.ServerGamePacketListenerImpl;
            import net.neoforged.neoforge.common.util.FakePlayer;

            public class DeployerFakePlayer extends FakePlayer {
                private static final Connection NETWORK_MANAGER = null;

                public DeployerFakePlayer(ServerLevel level) {
                    super(level, null);
                }

                private static class FakePlayNetHandler
                    extends ServerGamePacketListenerImpl {
                    FakePlayNetHandler(
                        MinecraftServer server, ServerPlayer playerIn
                    ) {
                        super(
                            server,
                            NETWORK_MANAGER,
                            playerIn,
                            CommonListenerCookie.createInitial(
                                playerIn.getGameProfile(), false));
                    }
                }
            }
        """.trimIndent()

        assertEquals(
            shopping,
            pass.migrateLegacyClientBaseApiCompat(shopping)
        )
        assertEquals(
            deployer,
            pass.migrateLegacyClientBaseApiCompat(deployer)
        )
    }

    @Test
    fun `similarly named project helpers and direct config factories remain unchanged`() {
        val source = """
            package example;

            import net.neoforged.fml.ModContainer;
            import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

            class Helpers {
                void probe(
                    Helper helper,
                    ModContainer container,
                    ForeignExtensionRegistry registry
                ) {
                    helper.getRunningGoals();
                    container.registerExtensionPoint(
                        IConfigScreenFactory.class,
                        (mod, previousScreen) -> previousScreen);
                    registry.registerExtensionPoint(
                        IConfigScreenFactory.class,
                        () -> (mod, previousScreen) -> previousScreen);
                }
            }

            class ForeignExtensionRegistry {}

            class Helper {
                Object getRunningGoals() {
                    return null;
                }
            }

            class ForeignMinecraft {
                ForeignSection sectionAt;
                ForeignLevel level;
            }

            class ForeignSection {
                ForeignLevel getBlueprintWorld() {
                    return null;
                }
            }

            class ForeignLevel {
                Object registryAccess() {
                    return null;
                }
            }

            class ForeignProviderUse {
                Object provider(ForeignMinecraft mc) {
                    return mc.sectionAt.getBlueprintWorld().registryAccess();
                }
            }

            class Minecraft {
                Options options;

                static Minecraft getInstance() {
                    return null;
                }
            }

            class Options {
                boolean renderDebug;
            }

            class ShadowedDebug {
                boolean active() {
                    return Minecraft.getInstance().options.renderDebug;
                }
            }
        """.trimIndent()

        assertEquals(
            source,
            pass.migrateLegacyClientBaseApiCompat(source)
        )
    }
}
