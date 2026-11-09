package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.legacyCachedRenderBoundsType
import com.modporter.core.transforms.structural.migrateLegacyCachedRenderBoundsBase
import com.modporter.core.transforms.structural.migrateLegacyCachedRenderBoundsBridge
import com.modporter.core.transforms.structural.migrateLegacyGuiOverlayLayer
import com.modporter.core.transforms.structural.migrateNeoForgeSubscriberBus
import com.modporter.core.transforms.structural.migrateTransientStepHeightOverride
import com.modporter.core.transforms.structural.migrateWarningScreenFooterLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerStaleGuiApiSurfacesTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `warning screen footer returns a layout while retaining both button actions`() {
        val source = """
            package example;

            import net.minecraft.client.gui.components.Button;
            import net.minecraft.client.gui.screens.TitleScreen;
            import net.minecraft.client.gui.screens.multiplayer.Realms32bitWarningScreen;
            import net.minecraft.client.gui.screens.multiplayer.WarningScreen;
            import net.minecraft.network.chat.Component;

            /**
             * @see Realms32bitWarningScreen
             */
            class PojavWarningScreen extends WarningScreen {
                static final Component CONTINUE = Component.literal("Continue");
                static final Component QUIT = Component.literal("Quit");
                private final TitleScreen titleScreen;

                PojavWarningScreen(TitleScreen titleScreen) {
                    super(CONTINUE, QUIT, null, CONTINUE);
                    this.titleScreen = titleScreen;
                }

                @Override
                protected void initButtons(int yOffset) {
                    this.addRenderableWidget(Button.builder(CONTINUE,
                        button -> this.minecraft.setScreen(this.titleScreen))
                        .bounds(this.width / 2 - 155, 100 + yOffset, 150, 20).build());
                    this.addRenderableWidget(Button.builder(QUIT,
                        button -> this.minecraft.stop())
                        .bounds(this.width / 2 - 155 + 160, 100 + yOffset, 150, 20).build());
                }
            }
        """.trimIndent()

        val migrated = pass.migrateWarningScreenFooterLayout(source)

        assertTrue(migrated.contains("protected Layout addFooterButtons()"), migrated)
        assertTrue(
            migrated.contains("LinearLayout.horizontal().spacing(8)"),
            migrated
        )
        assertTrue(migrated.contains("this.minecraft.setScreen(this.titleScreen)"), migrated)
        assertTrue(migrated.contains("this.minecraft.stop()"), migrated)
        assertFalse(migrated.contains("addRenderableWidget"), migrated)
        assertFalse(migrated.contains(".bounds("), migrated)
        assertFalse(migrated.contains("Realms32bitWarningScreen"), migrated)
        assertEquals(migrated, pass.migrateWarningScreenFooterLayout(migrated))
    }

    @Test
    fun `warning screen footer rejects bodies with unrelated side effects`() {
        val source = """
            package example;
            import net.minecraft.client.gui.components.Button;
            import net.minecraft.client.gui.screens.multiplayer.WarningScreen;
            class UnsafeWarning extends WarningScreen {
                @Override protected void initButtons(int yOffset) {
                    audit();
                    this.addRenderableWidget(Button.builder(A, button -> proceed())
                        .bounds(this.width / 2 - 155, 100 + yOffset, 150, 20).build());
                    this.addRenderableWidget(Button.builder(B, button -> quit())
                        .bounds(this.width / 2 + 5, 100 + yOffset, 150, 20).build());
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateWarningScreenFooterLayout(source))
    }

    @Test
    fun `legacy GUI overlay method reference receives target layer context`() {
        val source = """
            package example;

            import net.minecraft.client.gui.Gui;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.LayeredDraw;
            import net.minecraft.core.HolderLookup;

            class BlueprintOverlay {
                public static final IGuiOverlay OVERLAY = BlueprintOverlay::renderOverlay;

                public static void renderOverlay(
                    Gui gui,
                    GuiGraphics graphics,
                    float partialTicks,
                    int width,
                    int height,
                    HolderLookup.Provider registries
                ) {
                    draw(gui, graphics, partialTicks, width, height, registries);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyGuiOverlayLayer(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertTrue(migrated.contains("LayeredDraw.Layer OVERLAY"), migrated)
        assertTrue(
            compact.contains(
                "net.minecraft.client.Minecraft.getInstance().gui," +
                    "modporterGraphics," +
                    "modporterDelta.getGameTimeDeltaPartialTick(false)," +
                    "modporterGraphics.guiWidth()," +
                    "modporterGraphics.guiHeight()," +
                    "net.minecraft.client.Minecraft.getInstance().level.registryAccess()"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "public static void renderOverlay("
            ) && migrated.contains("HolderLookup.Provider registries"),
            migrated
        )
        assertTrue(migrated.contains("draw(gui, graphics, partialTicks, width, height, registries)"))
        assertEquals(migrated, pass.migrateLegacyGuiOverlayLayer(migrated))
    }

    @Test
    fun `overlay migration rejects a source owned same simple interface`() {
        val source = """
            package example;
            import net.minecraft.client.gui.Gui;
            import net.minecraft.client.gui.GuiGraphics;
            interface IGuiOverlay {}
            class Overlay {
                static final IGuiOverlay LAYER = Overlay::draw;
                static void draw(
                    Gui gui,
                    GuiGraphics graphics,
                    float tick,
                    int width,
                    int height
                ) {}
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyGuiOverlayLayer(source))
    }

    @Test
    fun `explicit Forge subscriber bus becomes explicit game bus`() {
        val source = """
            package example;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.common.EventBusSubscriber.Bus;
            @EventBusSubscriber(bus = Bus.FORGE)
            class GameEvents {}
        """.trimIndent()

        val migrated = pass.migrateNeoForgeSubscriberBus(source)

        assertTrue(migrated.contains("@EventBusSubscriber(bus = Bus.GAME)"), migrated)
        assertFalse(migrated.contains("Bus.FORGE"), migrated)
        assertEquals(migrated, pass.migrateNeoForgeSubscriberBus(migrated))
    }

    @Test
    fun `unowned local subscriber annotation is not rewritten`() {
        val source = """
            package example;
            @EventBusSubscriber(bus = Bus.FORGE)
            class LocalEvents {}
        """.trimIndent()

        assertEquals(source, pass.migrateNeoForgeSubscriberBus(source))
    }

    @Test
    fun `temporary entity step override uses living step height attribute`() {
        val source = """
            package example;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.MoverType;
            import net.minecraft.world.phys.Vec3;
            class BeltMovement {
                static void move(Entity entityIn, boolean isPlayer, Vec3 movement) {
                    float step = entityIn.maxUpStep();
                    if (!isPlayer)
                        entityIn.setMaxUpStep(1);

                    entityIn.move(MoverType.SELF, movement);
                    entityIn.setOnGround(true);

                    if (!isPlayer)
                        entityIn.setMaxUpStep(step);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateTransientStepHeightOverride(source)

        assertTrue(migrated.contains("AttributeInstance modporterStepHeight = null;"), migrated)
        assertTrue(
            migrated.contains(
                "entityIn instanceof LivingEntity"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "((LivingEntity) entityIn)"
            ) && migrated.contains(
                ".getAttribute(Attributes.STEP_HEIGHT)"
            ),
            migrated
        )
        assertTrue(migrated.contains("modporterStepHeight.setBaseValue(1)"), migrated)
        assertTrue(migrated.contains("modporterStepHeight.setBaseValue(step)"), migrated)
        assertTrue(migrated.contains("entityIn.move(MoverType.SELF, movement)"), migrated)
        assertTrue(migrated.contains("entityIn.setOnGround(true)"), migrated)
        assertFalse(migrated.contains("setMaxUpStep"), migrated)
        assertEquals(migrated, pass.migrateTransientStepHeightOverride(migrated))
    }

    @Test
    fun `open step snapshot use rejects attribute transaction`() {
        val source = """
            package example;
            import net.minecraft.world.entity.Entity;
            class UnsafeMovement {
                static float move(Entity entity, boolean change) {
                    float step = entity.maxUpStep();
                    if (change) entity.setMaxUpStep(1);
                    log(step);
                    if (change) entity.setMaxUpStep(step);
                    return step;
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateTransientStepHeightOverride(source))
    }

    @Test
    fun `cached block entity bounds stay dynamic through renderer culling bridge`() {
        pass.projectValueFacts = ProjectValueFacts(
            parents = mapOf(
                "example.block.CachedBoundsBlockEntity" to
                    "example.block.SyncedBlockEntity",
                "example.block.SyncedBlockEntity" to
                    "net.minecraft.world.level.block.entity.BlockEntity"
            ),
            types = setOf(
                "example.block.CachedBoundsBlockEntity",
                "example.block.SyncedBlockEntity"
            )
        )
        val cachedSource = """
            package example.block;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.AABB;
            abstract class CachedBoundsBlockEntity extends SyncedBlockEntity {
                private AABB renderBoundingBox;
                CachedBoundsBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                public AABB getRenderBoundingBox() {
                    if (renderBoundingBox == null)
                        renderBoundingBox = createRenderBoundingBox();
                    return renderBoundingBox;
                }
                protected AABB createRenderBoundingBox() {
                    return super.getRenderBoundingBox();
                }
            }
        """.trimIndent()
        val rendererSource = """
            package example.client;
            import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
            import net.minecraft.world.level.block.entity.BlockEntity;
            abstract class SafeRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
                protected abstract void renderSafe(T blockEntity);
            }
        """.trimIndent()

        val cachedType = pass.legacyCachedRenderBoundsType(cachedSource)
        assertEquals("example.block.CachedBoundsBlockEntity", cachedType)
        val provenType = requireNotNull(cachedType)
        val migratedCached = pass.migrateLegacyCachedRenderBoundsBase(cachedSource)
        val migratedRenderer =
            pass.migrateLegacyCachedRenderBoundsBridge(rendererSource, provenType)

        assertTrue(
            migratedCached.contains("return new AABB(getBlockPos());"),
            migratedCached
        )
        assertTrue(
            migratedRenderer.contains(
                "blockEntity instanceof example.block.CachedBoundsBlockEntity"
            ),
            migratedRenderer
        )
        assertTrue(
            migratedRenderer.contains(
                "return ((example.block.CachedBoundsBlockEntity) blockEntity)" +
                    ".getRenderBoundingBox();"
            ),
            migratedRenderer
        )
        assertTrue(
            migratedRenderer.contains(
                "return BlockEntityRenderer.super.getRenderBoundingBox(blockEntity);"
            ),
            migratedRenderer
        )
        assertEquals(
            migratedCached,
            pass.migrateLegacyCachedRenderBoundsBase(migratedCached)
        )
        assertEquals(
            migratedRenderer,
            pass.migrateLegacyCachedRenderBoundsBridge(migratedRenderer, provenType)
        )
    }

    @Test
    fun `unpaired render fallback is not treated as a cached family`() {
        val source = """
            package example;
            import net.minecraft.world.phys.AABB;
            abstract class ArbitraryBlockEntity extends Base {
                protected AABB createRenderBoundingBox() {
                    return super.getRenderBoundingBox();
                }
            }
        """.trimIndent()

        assertEquals(null, pass.legacyCachedRenderBoundsType(source))
        assertEquals(source, pass.migrateLegacyCachedRenderBoundsBase(source))
    }

    @Test
    fun `complete cached bounds shape on unrelated hierarchy is rejected`() {
        pass.projectValueFacts = ProjectValueFacts(
            parents = mapOf(
                "example.ArbitraryCache" to "example.UnrelatedBase",
                "example.UnrelatedBase" to "java.lang.Object"
            ),
            types = setOf(
                "example.ArbitraryCache",
                "example.UnrelatedBase"
            )
        )
        val source = """
            package example;
            import net.minecraft.world.phys.AABB;
            abstract class ArbitraryCache extends UnrelatedBase {
                private AABB renderBoundingBox;
                public AABB getRenderBoundingBox() {
                    if (renderBoundingBox == null)
                        renderBoundingBox = createRenderBoundingBox();
                    return renderBoundingBox;
                }
                protected AABB createRenderBoundingBox() {
                    return super.getRenderBoundingBox();
                }
            }
        """.trimIndent()

        assertEquals(null, pass.legacyCachedRenderBoundsType(source))
        assertEquals(source, pass.migrateLegacyCachedRenderBoundsBase(source))
    }
}
