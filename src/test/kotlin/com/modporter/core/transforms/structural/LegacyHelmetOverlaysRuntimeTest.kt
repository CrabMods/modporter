package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class LegacyHelmetOverlaysRuntimeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `eager consumer capture no longer constructs Gui and render state remains exact`() {
        val legacy = overlaySource()
        val before = compileFixture("before", legacy)
        assertEquals(
            "commandHistory unavailable:captures=0:guiConstructors=1",
            invoke(before, "verifyBefore")
        )

        val pass = StructuralRefactorPass().also {
            it.projectValueFacts = ProjectValueFacts(types = setOf(OVERLAY))
        }
        val migrated = pass.migrateLegacyHelmetOverlayRendering(legacy)
        val after = compileFixture("after", migrated)
        assertEquals(
            "captures=1:guiConstructors=0:super=java.lang.Object:" +
                "partial:true|disableDepth|depthMask:false|enableBlend|" +
                "color:1.0,1.0,1.0,0.75|" +
                "blit:create:package_blur,0,0,-90,128,72|" +
                "disableBlend|depthMask:true|enableDepth|" +
                "color:1.0,1.0,1.0,1.0",
            invoke(after, "verifyAfter")
        )
    }

    private fun compileFixture(name: String, overlay: String): Path {
        val root = tempDir.resolve(name)
        val sources = root.resolve("src").createDirectories()
        fixtureSources().forEach { (relative, source) ->
            val file = sources.resolve(relative)
            file.parent.createDirectories()
            file.writeText(source)
        }
        val overlayFile = sources.resolve(
            "com/simibubi/create/content/equipment/armor/" +
                "CardboardArmorStealthOverlay.java"
        )
        overlayFile.parent.createDirectories()
        overlayFile.writeText(overlay)

        val classes = root.resolve("classes").createDirectories()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for cardboard overlay execution test")
        val diagnostics = ByteArrayOutputStream()
        val javaFiles = Files.walk(sources).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }
        val arguments = listOf("-d", classes.toString()) + javaFiles.map { it.toString() }
        val result = compiler.run(
            null,
            diagnostics,
            diagnostics,
            *arguments.toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString())
        return classes
    }

    private fun invoke(classes: Path, method: String): String =
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("com.simibubi.create.content.equipment.armor.Probe")
                .getMethod(method)
                .invoke(null) as String
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

    private fun fixtureSources(): Map<String, String> = mapOf(
        "com/mojang/blaze3d/systems/RenderSystem.java" to """
            package com.mojang.blaze3d.systems;

            import net.minecraft.client.gui.Trace;

            public final class RenderSystem {
                public static void disableDepthTest() { Trace.add("disableDepth"); }
                public static void depthMask(boolean value) {
                    Trace.add("depthMask:" + value);
                }
                public static void enableBlend() { Trace.add("enableBlend"); }
                public static void disableBlend() { Trace.add("disableBlend"); }
                public static void enableDepthTest() { Trace.add("enableDepth"); }
            }
        """.trimIndent(),
        "com/simibubi/create/Create.java" to """
            package com.simibubi.create;

            import net.minecraft.resources.ResourceLocation;

            public final class Create {
                public static ResourceLocation asResource(String path) {
                    return new ResourceLocation("create", path.replace("textures/misc/", "")
                        .replace(".png", ""));
                }
            }
        """.trimIndent(),
        "com/simibubi/create/content/equipment/armor/CardboardArmorHandler.java" to """
            package com.simibubi.create.content.equipment.armor;

            import net.minecraft.client.player.LocalPlayer;

            public final class CardboardArmorHandler {
                public static boolean testForStealth(LocalPlayer player) { return true; }
            }
        """.trimIndent(),
        "com/simibubi/create/content/equipment/armor/CardboardArmorItem.java" to """
            package com.simibubi.create.content.equipment.armor;

            import java.util.function.Consumer;
            import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

            public class CardboardArmorItem {
                public void initializeClient(Consumer<IClientItemExtensions> consumer) {}
            }
        """.trimIndent(),
        "com/simibubi/create/content/equipment/armor/CardboardHelmetItem.java" to """
            package com.simibubi.create.content.equipment.armor;

            import java.util.function.Consumer;
            import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

            public final class CardboardHelmetItem extends CardboardArmorItem {
                @Override
                public void initializeClient(Consumer<IClientItemExtensions> consumer) {
                    super.initializeClient(consumer);
                    consumer.accept(new CardboardArmorStealthOverlay());
                }
            }
        """.trimIndent(),
        "com/simibubi/create/content/equipment/armor/Probe.java" to """
            package com.simibubi.create.content.equipment.armor;

            import java.util.concurrent.atomic.AtomicInteger;
            import java.util.concurrent.atomic.AtomicReference;
            import net.minecraft.client.DeltaTracker;
            import net.minecraft.client.gui.Gui;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.Trace;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

            public final class Probe {
                public static String verifyBefore() {
                    Gui.constructorCalls = 0;
                    AtomicInteger captures = new AtomicInteger();
                    try {
                        new CardboardHelmetItem().initializeClient(extension ->
                            captures.incrementAndGet()
                        );
                        return "unexpected success";
                    } catch (IllegalStateException expected) {
                        return expected.getMessage() +
                            ":captures=" + captures.get() +
                            ":guiConstructors=" + Gui.constructorCalls;
                    }
                }

                public static String verifyAfter() {
                    Gui.constructorCalls = 0;
                    AtomicInteger captures = new AtomicInteger();
                    AtomicReference<IClientItemExtensions> captured =
                        new AtomicReference<>();
                    new CardboardHelmetItem().initializeClient(extension -> {
                        captures.incrementAndGet();
                        captured.set(extension);
                    });
                    Trace.clear();
                    captured.get().renderHelmetOverlay(
                        new ItemStack(),
                        new Player(),
                        new GuiGraphics(null, null),
                        new DeltaTracker()
                    );
                    return "captures=" + captures.get() +
                        ":guiConstructors=" + Gui.constructorCalls +
                        ":super=" + captured.get().getClass().getSuperclass().getName() +
                        ":" + Trace.join();
                }
            }
        """.trimIndent(),
        "net/createmod/catnip/animation/LerpedFloat.java" to """
            package net.createmod.catnip.animation;

            public final class LerpedFloat {
                public enum Chaser { EXP }
                public static LerpedFloat linear() { return new LerpedFloat(); }
                public LerpedFloat startWithValue(double value) { return this; }
                public LerpedFloat chase(double value, float speed, Chaser chaser) {
                    return this;
                }
                public void tickChaser() {}
                public void updateChaseTarget(double value) {}
                public float getValue(float partialTick) { return 0.75F; }
            }
        """.trimIndent(),
        "net/minecraft/client/DeltaTracker.java" to """
            package net.minecraft.client;

            import net.minecraft.client.gui.Trace;

            public final class DeltaTracker {
                public float getGameTimeDeltaPartialTick(boolean paused) {
                    Trace.add("partial:" + paused);
                    return 0.5F;
                }
            }
        """.trimIndent(),
        "net/minecraft/client/Minecraft.java" to """
            package net.minecraft.client;

            import net.minecraft.client.player.LocalPlayer;

            public final class Minecraft {
                private static final Minecraft INSTANCE = new Minecraft();
                public LocalPlayer player = new LocalPlayer();
                public static Minecraft getInstance() { return INSTANCE; }
                public Object getItemRenderer() { return new Object(); }
                public RenderBuffers renderBuffers() { return new RenderBuffers(); }
                public static final class RenderBuffers {
                    public Object bufferSource() { return new Object(); }
                }
            }
        """.trimIndent(),
        "net/minecraft/client/gui/Gui.java" to """
            package net.minecraft.client.gui;

            import net.minecraft.client.Minecraft;
            import net.minecraft.resources.ResourceLocation;

            public class Gui {
                public static int constructorCalls;
                protected int screenWidth;
                protected int screenHeight;

                public Gui(Minecraft minecraft) {
                    constructorCalls++;
                    throw new IllegalStateException("commandHistory unavailable");
                }

                public Gui(Minecraft minecraft, Object renderer) {
                    constructorCalls++;
                    throw new IllegalStateException("commandHistory unavailable");
                }

                protected void renderTextureOverlay(
                    GuiGraphics graphics,
                    ResourceLocation texture,
                    float alpha
                ) {}
            }
        """.trimIndent(),
        "net/minecraft/client/gui/GuiGraphics.java" to """
            package net.minecraft.client.gui;

            import net.minecraft.client.Minecraft;
            import net.minecraft.resources.ResourceLocation;

            public final class GuiGraphics {
                public GuiGraphics(Minecraft minecraft, Object buffers) {}
                public int guiWidth() { return 128; }
                public int guiHeight() { return 72; }
                public void setColor(float red, float green, float blue, float alpha) {
                    Trace.add("color:" + red + "," + green + "," + blue + "," + alpha);
                }
                public void blit(
                    ResourceLocation texture,
                    int x,
                    int y,
                    int z,
                    float u,
                    float v,
                    int width,
                    int height,
                    int textureWidth,
                    int textureHeight
                ) {
                    Trace.add(
                        "blit:" + texture + "," + x + "," + y + "," + z + "," +
                            width + "," + height
                    );
                }
            }
        """.trimIndent(),
        "net/minecraft/client/gui/Trace.java" to """
            package net.minecraft.client.gui;

            import java.util.ArrayList;
            import java.util.List;

            public final class Trace {
                private static final List<String> CALLS = new ArrayList<>();
                public static void add(String call) { CALLS.add(call); }
                public static void clear() { CALLS.clear(); }
                public static String join() { return String.join("|", CALLS); }
            }
        """.trimIndent(),
        "net/minecraft/client/player/LocalPlayer.java" to """
            package net.minecraft.client.player;

            import net.minecraft.world.entity.player.Player;

            public final class LocalPlayer extends Player {}
        """.trimIndent(),
        "net/minecraft/resources/ResourceLocation.java" to """
            package net.minecraft.resources;

            public record ResourceLocation(String namespace, String path) {
                @Override
                public String toString() { return namespace + ":" + path; }
            }
        """.trimIndent(),
        "net/minecraft/world/entity/player/Player.java" to """
            package net.minecraft.world.entity.player;

            public class Player {}
        """.trimIndent(),
        "net/minecraft/world/item/ItemStack.java" to """
            package net.minecraft.world.item;

            public final class ItemStack {}
        """.trimIndent(),
        "net/neoforged/neoforge/client/extensions/common/IClientItemExtensions.java" to """
            package net.neoforged.neoforge.client.extensions.common;

            import net.minecraft.client.DeltaTracker;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;

            public interface IClientItemExtensions {
                default void renderHelmetOverlay(
                    ItemStack stack,
                    Player player,
                    int width,
                    int height,
                    float partialTick
                ) {}

                default void renderHelmetOverlay(
                    ItemStack stack,
                    Player player,
                    GuiGraphics graphics,
                    DeltaTracker deltaTracker
                ) {
                    renderHelmetOverlay(
                        stack,
                        player,
                        graphics.guiWidth(),
                        graphics.guiHeight(),
                        deltaTracker.getGameTimeDeltaPartialTick(true)
                    );
                }
            }
        """.trimIndent()
    )

    private companion object {
        const val OVERLAY =
            "com.simibubi.create.content.equipment.armor.CardboardArmorStealthOverlay"
    }
}
