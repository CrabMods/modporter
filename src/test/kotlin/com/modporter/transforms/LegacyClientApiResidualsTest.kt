package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyClientApiResidualsSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyClientApiResidualsTest {
    @Test
    fun `block picking uses the proven local player interaction range`() {
        val source = """
            package example;

            import net.minecraft.client.Minecraft;
            import net.minecraft.client.player.LocalPlayer;

            final class Picking {
                static double reach(LocalPlayer player) {
                    Minecraft mc = Minecraft.getInstance();
                    double reach = mc.gameMode.getPickRange();
                    return reach;
                }

                static double unrelated(Helper helper) {
                    return helper.getPickRange();
                }

                static final class Helper {
                    double getPickRange() {
                        return 7.0;
                    }
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyClientApiResidualsSource(source)

        assertTrue(
            migrated.contains("double reach = player.blockInteractionRange();"),
            migrated
        )
        assertTrue(migrated.contains("return helper.getPickRange();"), migrated)
        assertFalse(migrated.contains("mc.gameMode.getPickRange()"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateLegacyClientApiResidualsSource(migrated)
        )
    }

    @Test
    fun `tick duration uses the guarded local player level tick rate`() {
        val source = """
            package example;

            import net.minecraft.client.Minecraft;
            import net.minecraft.client.player.LocalPlayer;
            import net.minecraft.util.StringUtil;

            final class AirOverlay {
                static String render(int ticks) {
                    Minecraft mc = Minecraft.getInstance();
                    LocalPlayer player = mc.player;
                    if (player == null)
                        return "";
                    return StringUtil.formatTickDuration(ticks);
                }

                static String unguarded(int ticks, LocalPlayer player) {
                    return StringUtil.formatTickDuration(ticks);
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyClientApiResidualsSource(source)

        assertTrue(
            migrated.contains(
                "StringUtil.formatTickDuration(" +
                    "ticks, player.level().tickRateManager().tickrate())"
            ),
            migrated
        )
        assertEquals(
            1,
            Regex("""StringUtil\.formatTickDuration\(ticks\)""").findAll(migrated).count(),
            migrated
        )
    }

    @Test
    fun `gui render buffers reuse the enclosing GuiGraphics buffer source`() {
        val source = """
            package example;

            import com.mojang.blaze3d.vertex.Tesselator;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.renderer.MultiBufferSource;

            final class GuiAnimations {
                static void drain(GuiGraphics graphics) {
                    MultiBufferSource.BufferSource buffer =
                        MultiBufferSource.immediate(Tesselator.getInstance()
                            .getBuilder());
                    buffer.endBatch();
                }

                static void tooltip(GuiGraphics graphics) {
                    MultiBufferSource.BufferSource renderType =
                        MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
                    renderType.endBatch();
                }

                static MultiBufferSource.BufferSource unrelated() {
                    return MultiBufferSource.immediate(
                        Tesselator.getInstance().getBuilder());
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyClientApiResidualsSource(source)

        assertEquals(
            2,
            Regex("""graphics\.bufferSource\(\)""").findAll(migrated).count(),
            migrated
        )
        assertTrue(migrated.contains("buffer.endBatch();"), migrated)
        assertTrue(migrated.contains("renderType.endBatch();"), migrated)
        assertEquals(
            1,
            Regex(
                """MultiBufferSource\.immediate\(\s*""" +
                    """Tesselator\.getInstance\(\)\.getBuilder\(\)\s*\)"""
            ).findAll(migrated).count(),
            migrated
        )
    }

    @Test
    fun `model baking registries propagate ModelResourceLocation keys`() {
        val source = """
            package example;

            import java.util.List;
            import java.util.Map;
            import java.util.function.Function;
            import net.minecraft.client.resources.model.BakedModel;
            import net.minecraft.client.resources.model.ModelResourceLocation;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.client.event.ModelEvent;

            final class ModelSwapper {
                void bake(ModelEvent.ModifyBakingResult event) {
                    Map<ResourceLocation, BakedModel> models = event.getModels();
                    swapModels(models, List.of(), Function.identity());
                    swapModels(models, ModelResourceLocation.inventory(
                        ResourceLocation.withDefaultNamespace("gear")), Function.identity());
                }

                static <T extends BakedModel> void swapModels(
                        Map<ResourceLocation, BakedModel> models,
                        List<ModelResourceLocation> locations,
                        Function<BakedModel, T> factory) {
                }

                static <T extends BakedModel> void swapModels(
                        Map<ResourceLocation, BakedModel> models,
                        ModelResourceLocation location,
                        Function<BakedModel, T> factory) {
                    models.put(location, factory.apply(models.get(location)));
                }

                Map<ResourceLocation, String> labels;
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyClientApiResidualsSource(source)

        assertEquals(
            3,
            Regex("""Map<ModelResourceLocation, BakedModel>""")
                .findAll(migrated)
                .count(),
            migrated
        )
        assertTrue(migrated.contains("models.put(location, factory.apply(models.get(location)));"))
        assertTrue(migrated.contains("Map<ResourceLocation, String> labels;"), migrated)
        assertFalse(migrated.contains("Map<ResourceLocation, BakedModel>"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateLegacyClientApiResidualsSource(migrated)
        )
    }
}
