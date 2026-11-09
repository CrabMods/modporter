package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyHelmetOverlaysTest {
    @Test
    fun `create cardboard overlay drops obsolete Gui construction after exact render migration`() {
        val pass = pass()
        val result = pass.migrateLegacyHelmetOverlayRendering(overlaySource())

        assertTrue(
            result.contains(
                "public class CardboardArmorStealthOverlay implements IClientItemExtensions"
            ),
            result
        )
        assertFalse(result.contains("extends Gui"), result)
        assertFalse(result.contains("CardboardArmorStealthOverlay()"), result)
        assertFalse(result.contains("import net.minecraft.client.gui.Gui;"), result)
        assertTrue(
            result.contains(
                "renderHelmetOverlay(ItemStack stack, Player player, " +
                    "GuiGraphics guiGraphics, DeltaTracker deltaTracker)"
            ),
            result
        )
        assertTrue(
            result.contains(
                "float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);"
            ),
            result
        )
        assertTrue(result.contains("RenderSystem.disableDepthTest();"), result)
        assertTrue(result.contains("RenderSystem.depthMask(false);"), result)
        assertTrue(result.contains("RenderSystem.enableBlend();"), result)
        assertTrue(result.contains("guiGraphics.setColor(1.0F, 1.0F, 1.0F, value);"), result)
        assertTrue(result.contains("guiGraphics.blit(PACKAGE_BLUR_LOCATION"), result)
        assertTrue(result.contains("RenderSystem.disableBlend();"), result)
        assertTrue(result.contains("RenderSystem.depthMask(true);"), result)
        assertTrue(result.contains("RenderSystem.enableDepthTest();"), result)
        assertTrue(result.contains("guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);"), result)
        assertTrue(result.contains("public static void clientTick()"), result)
        assertTrue(pass.migrateLegacyHelmetOverlayRendering(result) == result, result)
    }

    @Test
    fun `legacy helmet consumer registration and capture expression remain unchanged`() {
        val source = """
            package com.simibubi.create.content.equipment.armor;

            import java.util.function.Consumer;
            import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

            public class CardboardHelmetItem extends CardboardArmorItem {
                public void initializeClient(Consumer<IClientItemExtensions> consumer) {
                    super.initializeClient(consumer);
                    consumer.accept(new CardboardArmorStealthOverlay());
                }
            }
        """.trimIndent()

        assertTrue(pass().migrateLegacyHelmetOverlayRendering(source) == source)
    }

    @Test
    fun `Gui base cleanup rejects extra constructor instance state and unscoped calls`() {
        val original = overlaySource()
        val memberAnchor = "    private static final ResourceLocation"
        val extraConstructor = replaceRequired(
            original,
            memberAnchor,
            "    public CardboardArmorStealthOverlay(boolean alternate) {\n" +
                "        super(Minecraft.getInstance(), Minecraft.getInstance().getItemRenderer());\n" +
                "    }\n\n" +
                memberAnchor
        )
        val instanceState = replaceRequired(
            original,
            memberAnchor,
            "    private int presentationState;\n\n" + memberAnchor
        )
        val valueAnchor = "        float value = opacity.getValue(partialTick);"
        val unscopedCall = insertBeforeLastClassBrace(
            replaceRequired(
                original,
                valueAnchor,
                "        helper();\n" + valueAnchor
            ),
            "    private static void helper() {}\n\n"
        )

        listOf(
            "extra constructor" to extraConstructor,
            "instance state" to instanceState,
            "unscoped call" to unscopedCall
        ).forEach { (case, source) ->
            assertTrue(source != original, "$case mutation did not change the fixture")
            val result = pass().migrateLegacyHelmetOverlayRendering(source)
            assertTrue(
                result.contains("extends Gui implements IClientItemExtensions"),
                "$case unexpectedly removed Gui:\n$result"
            )
            assertTrue(
                result.contains("super(Minecraft.getInstance());"),
                "$case unexpectedly removed the Gui constructor:\n$result"
            )
        }
    }

    @Test
    fun `Gui base cleanup rejects a project inventoried subclass`() {
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts(
            types = setOf(OVERLAY, "example.CustomCardboardOverlay"),
            parents = mapOf("example.CustomCardboardOverlay" to OVERLAY)
        )

        val result = pass.migrateLegacyHelmetOverlayRendering(overlaySource())
        assertTrue(result.contains("extends Gui implements IClientItemExtensions"), result)
        assertTrue(result.contains("super(Minecraft.getInstance());"), result)
    }

    private fun pass(): StructuralRefactorPass = StructuralRefactorPass().also {
        it.projectValueFacts = ProjectValueFacts(types = setOf(OVERLAY))
    }

    private fun replaceRequired(source: String, target: String, replacement: String): String {
        assertTrue(source.contains(target), "Fixture does not contain mutation anchor: $target")
        val mutated = source.replaceFirst(target, replacement)
        assertTrue(mutated != source, "Mutation did not change fixture for anchor: $target")
        return mutated
    }

    private fun insertBeforeLastClassBrace(source: String, member: String): String {
        val classClose = source.lastIndexOf('}')
        assertTrue(classClose >= 0, "Fixture has no class-closing brace")
        val mutated = source.substring(0, classClose) + member + source.substring(classClose)
        assertTrue(mutated != source, "Member insertion did not change fixture")
        return mutated
    }

    private fun overlaySource(): String = """
        package com.simibubi.create.content.equipment.armor;

        import com.simibubi.create.Create;
        import net.createmod.catnip.animation.LerpedFloat;
        import net.createmod.catnip.animation.LerpedFloat.Chaser;
        import net.minecraft.client.Minecraft;
        import net.minecraft.client.gui.Gui;
        import net.minecraft.client.gui.GuiGraphics;
        import net.minecraft.client.player.LocalPlayer;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.ItemStack;
        import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

        public class CardboardArmorStealthOverlay extends Gui implements IClientItemExtensions {
            public CardboardArmorStealthOverlay() {
                super(Minecraft.getInstance(), Minecraft.getInstance().getItemRenderer());
            }

            private static final ResourceLocation PACKAGE_BLUR_LOCATION =
                Create.asResource("textures/misc/package_blur.png");
            private static LerpedFloat opacity = LerpedFloat.linear()
                .startWithValue(0)
                .chase(0, 0.25f, Chaser.EXP);

            public static void clientTick() {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null)
                    return;
                opacity.tickChaser();
                opacity.updateChaseTarget(
                    CardboardArmorHandler.testForStealth(player) ? 1 : 0
                );
            }

            @Override
            public void renderHelmetOverlay(
                ItemStack stack, Player player, int width, int height, float partialTick
            ) {
                Minecraft mc = Minecraft.getInstance();
                float value = opacity.getValue(partialTick);
                if (value == 0)
                    return;
                screenWidth = width;
                screenHeight = height;
                renderTextureOverlay(
                    new GuiGraphics(mc, mc.renderBuffers().bufferSource()),
                    PACKAGE_BLUR_LOCATION,
                    value
                );
            }
        }
    """.trimIndent()

    private companion object {
        const val OVERLAY =
            "com.simibubi.create.content.equipment.armor.CardboardArmorStealthOverlay"
    }
}
