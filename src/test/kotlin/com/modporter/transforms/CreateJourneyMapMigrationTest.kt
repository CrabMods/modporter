package com.modporter.core.transforms.structural
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
class CreateJourneyMapMigrationTest {
    private val trainMap =
        "src/main/java/com/simibubi/create/compat/trainmap/JourneyTrainMap.java"
    private val fullscreenMixin =
        "src/main/java/com/simibubi/create/foundation/mixin/compat/journeymap/" +
            "JourneyFullscreenMapMixin.java"
    private val mixinConfig = "src/main/resources/create.mixins.json"
    private val mixinEntry = "compat.journeymap.JourneyFullscreenMapMixin"
    @Test
    fun `fixtures retain the exact original and owned diagnostic bytes`() {
        val expected = mapOf(
            "original" to mapOf(
                trainMap to "305e9c57d6196ab9f6061bba56b1e650f9095b1066d84eeb488ba3b04f3dc20d",
                fullscreenMixin to
                    "32ee8b13315acb4ec25ad0c55435ec273238212260fdb6be250335442366f62e",
                mixinConfig to
                    "8dec7b91a252eecfd34f913d4833a79be37c1c13ebf01ef84b4a8ea58b58c796"
            ),
            "owned" to mapOf(
                trainMap to "d852f5ebc94da21f01fc988d843fd54d72352dcb5eac8933e3a3bfe972232cbd",
                fullscreenMixin to
                    "a8c82a0b56a02944e1a530d9d063e8e4d6c83ca33b45f630041d8133e4f9ad62",
                mixinConfig to
                    "420ed570b0ea3e9d6302cfb39f22c4f361473de53ae892799195b1d619d9a8f8"
            )
        )
        expected.forEach { (fixture, files) ->
            val project = fixtureRoot().resolve(fixture).resolve("project")
            files.forEach { (relative, hash) ->
                val file = project.resolve(relative)
                assertTrue(file.isRegularFile(), "$fixture fixture is missing $relative")
                assertEquals(hash, sha256(file.readBytes()), "$fixture fixture drifted: $relative")
            }
        }
    }
    @Test
    fun `original and owned families migrate to the complete JourneyMap v2 plugin`() {
        listOf("original", "owned").forEach { fixture ->
            val project = copyFixture(fixture)
            val configBefore = project.resolve(mixinConfig).readText()
            val result = CreateJourneyMapMigration.migrate(
                StructuralRefactorPass(),
                project,
                dryRun = false
            )
            assertTrue(result.gates.isEmpty(), "$fixture gates: ${result.gates}")
            assertEquals(3, result.changes.size, "$fixture mutation count")
            assertEquals(
                listOf(trainMap, fullscreenMixin, mixinConfig),
                result.changes.map { project.relativize(it.file).invariantSeparators() }
            )
            assertTrue(
                result.changes.all { it.ruleId == CreateJourneyMapMigration.RULE_ID },
                "$fixture emitted another rule id"
            )
            val migrated = project.resolve(trainMap).readText()
            assertContains(migrated, "import com.simibubi.create.Create;")
            assertContains(migrated, "import journeymap.api.v2.client.IClientAPI;")
            assertContains(migrated, "import journeymap.api.v2.client.IClientPlugin;")
            assertContains(migrated, "import journeymap.api.v2.client.JourneyMapPlugin;")
            assertContains(
                migrated,
                "import journeymap.api.v2.client.event.FullscreenRenderEvent;"
            )
            assertContains(migrated, "import journeymap.api.v2.client.fullscreen.IFullscreen;")
            assertContains(
                migrated,
                "import journeymap.api.v2.common.event.FullscreenEventRegistry;"
            )
            assertContains(migrated, "import net.minecraft.client.gui.screens.Screen;")
            assertContains(migrated, "@JourneyMapPlugin(apiVersion = \"2.0.0\")")
            assertContains(migrated, "public class JourneyTrainMap implements IClientPlugin")
            assertContains(migrated, "public void initialize(IClientAPI jmClientApi)")
            assertContains(
                migrated,
                "FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(" +
                    "Create.ID, JourneyTrainMap::onFullscreenRender);"
            )
            assertContains(migrated, "public String getModId()")
            assertContains(migrated, "return Create.ID;")
            assertContains(
                migrated,
                "private static void onFullscreenRender(FullscreenRenderEvent event)"
            )
            assertContains(migrated, "IFullscreen screen = event.getFullscreen();")
            assertContains(migrated, "screen.getCenterBlockX(true)")
            assertContains(migrated, "screen.getCenterBlockZ(true)")
            assertContains(migrated, "event.getMouseX()")
            assertContains(migrated, "event.getMouseY()")
            assertContains(migrated, "event.getPartialTicks()")
            assertContains(
                migrated,
                "public static void onRender(GuiGraphics graphics, IFullscreen screen, " +
                    "double x, double z, int mX, int mY, float pt)"
            )
            assertContains(migrated, "Screen uiScreen = screen.getScreen();")
            assertContains(migrated, "pose.translate(uiScreen.width / 2.0f")
            assertContains(migrated, "mouseX = mX - uiScreen.width / 2.0f")
            assertContains(
                migrated,
                "TrainMapManager.renderAndPick(graphics, Mth.floor(mouseX), " +
                    "Mth.floor(mouseY), pt, false, bounds)"
            )
            assertContains(
                migrated,
                "renderToggleWidgetAndTooltip(GuiGraphics graphics, Screen screen"
            )
            // Preserve the non-render consumers and behavior around the migrated callback.
            assertContains(migrated, "public static void tick()")
            assertContains(migrated, "TrainMapManager.tick();")
            assertContains(migrated, "TrainMapSyncClient.requestData();")
            assertContains(migrated, "public static void mouseClick(InputEvent.MouseButton.Pre event)")
            assertContains(migrated, "TrainMapManager.handleToggleWidgetClick")
            assertContains(migrated, "showTrainMapOverlay.get()")
            assertContains(migrated, "RemovedGuiUtils.drawHoveringText")
            assertFalse(migrated.contains("journeymap.client.api.display.Context.UI"))
            assertFalse(migrated.contains("journeymap.client.api.util.UIState"))
            assertFalse(migrated.contains("GridRenderer"))
            assertFalse(migrated.contains("getMouseDrag"))
            assertFalse(migrated.contains("isScrolling"))
            assertFalse(project.resolve(fullscreenMixin).exists())
            val configAfter = project.resolve(mixinConfig).readText()
            val expectedConfig = configBefore.replace(
                Regex(
                    """(?m)^[ \t]*"${Regex.escape(mixinEntry)}",[ \t]*(?:\r?\n|$)"""
                ),
                ""
            )
            assertEquals(expectedConfig, configAfter, "$fixture changed unrelated mixin JSON")
            assertFalse(configAfter.contains(mixinEntry))
        }
    }
    @Test
    fun `dry run reports all mutations and writes no bytes`() {
        val project = copyFixture("owned")
        val before = snapshot(project)
        val result = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = true
        )
        assertTrue(result.gates.isEmpty(), result.gates.toString())
        assertEquals(3, result.changes.size)
        assertSnapshot(before, snapshot(project))
    }
    @Test
    fun `successful migration is idempotent`() {
        val project = copyFixture("owned")
        val first = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false
        )
        assertTrue(first.gates.isEmpty(), first.gates.toString())
        val afterFirst = snapshot(project)
        val second = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false
        )
        assertEquals(CreateJourneyMapMigration.Result.EMPTY, second)
        assertSnapshot(afterFirst, snapshot(project))
    }
    @Test
    fun `unknown train and mixin bodies fail closed without writes`() {
        val mutations = listOf<Pair<String, (String) -> String>>(
            "unknown JourneyTrainMap body" to { source ->
                source.replace(
                    "\t\tTrainMapManager.tick();",
                    "\t\tTrainMapManager.tick();\n\t\tTrainMapManager.renderToggleWidget(null, 8, 9);"
                )
            },
            "unknown JourneyFullscreenMapMixin body" to { source ->
                source.replace(
                    "\t\tboolean dragging = isScrolling;",
                    "\t\tboolean dragging = !isScrolling;"
                )
            }
        )
        mutations.forEach { (label, mutate) ->
            val project = copyFixture("owned")
            val relative = if (label.contains("TrainMap")) trainMap else fullscreenMixin
            val target = project.resolve(relative)
            target.writeText(mutate(target.readText()))
            val before = snapshot(project)
            val result = CreateJourneyMapMigration.migrate(
                StructuralRefactorPass(),
                project,
                dryRun = false
            )
            assertTrue(result.changes.isEmpty(), label)
            assertTrue(
                result.gates.any { it.contains("unknown members or method bodies") },
                "$label gates: ${result.gates}"
            )
            assertSnapshot(before, snapshot(project))
        }
    }
    @Test
    fun `foreign JourneyMap source consumer rejects the family without writes`() {
        val project = copyFixture("owned")
        val foreign = project.resolve("src/main/java/example/ForeignJourneyConsumer.java")
        foreign.parent.createDirectories()
        foreign.writeText(
            """
                package example;
                import journeymap.api.v2.client.IClientAPI;
                final class ForeignJourneyConsumer {
                    IClientAPI api;
                }
            """.trimIndent()
        )
        val before = snapshot(project)
        val result = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false
        )
        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any {
                it.contains("Unrecognized JourneyMap source consumers") &&
                    it.contains("ForeignJourneyConsumer.java")
            },
            result.gates.toString()
        )
        assertSnapshot(before, snapshot(project))
    }
    @Test
    fun `each partial legacy family is rejected without removing another member`() {
        listOf(trainMap, fullscreenMixin, mixinConfig).forEach { missing ->
            val project = copyFixture("owned")
            project.resolve(missing).deleteIfExists()
            val before = snapshot(project)
            val result = CreateJourneyMapMigration.migrate(
                StructuralRefactorPass(),
                project,
                dryRun = false
            )
            assertTrue(result.changes.isEmpty(), "missing $missing")
            assertTrue(result.gates.isNotEmpty(), "missing $missing was silently admitted")
            assertTrue(
                result.gates.any { it.contains("incomplete") || it.contains("missing") },
                "missing $missing gates: ${result.gates}"
            )
            assertSnapshot(before, snapshot(project))
        }
    }
    @Test
    fun `commit failure after write and delete restores exact bytes and existence`() {
        val project = copyFixture("original")
        val before = snapshot(project)
        val visited = mutableListOf<String>()
        val result = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false,
            commitProbe = { index, path ->
                visited += project.relativize(path).invariantSeparators()
                if (index == 2) error("injected transaction failure")
            }
        )
        assertEquals(listOf(trainMap, fullscreenMixin, mixinConfig), visited)
        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any {
                it.contains("transaction failed") &&
                    it.contains("injected transaction failure")
            },
            result.gates.toString()
        )
        assertSnapshot(before, snapshot(project))
        assertTrue(project.resolve(trainMap).isRegularFile())
        assertTrue(project.resolve(fullscreenMixin).isRegularFile())
        assertTrue(project.resolve(mixinConfig).isRegularFile())
    }
    @Test
    fun `generated plugin subscribes once and executes native center render behavior`() {
        val project = copyFixture("owned")
        val migration = CreateJourneyMapMigration.migrate(
            StructuralRefactorPass(),
            project,
            dryRun = false
        )
        assertTrue(migration.gates.isEmpty(), migration.gates.toString())
        writeCompilerStubs(project)
        val classes = project.resolve("compiler-classes").createDirectories()
        val sources = Files.walk(project).use { paths ->
            paths.filter { it.isRegularFile() && it.toString().endsWith(".java") }
                .toList()
        }
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("A JDK compiler is required for the JourneyMap generated-source probe")
        val result = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-proc:none",
            "-d",
            classes.toString(),
            *sources.map(Path::toString).toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString(Charsets.UTF_8))
        URLClassLoader(
            arrayOf(classes.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        ).use { loader ->
            assertEquals(
                "passed",
                loader.loadClass("probe.JourneyMapProbe").getMethod("verify").invoke(null)
            )
        }
    }
    private fun writeCompilerStubs(project: Path) {
        val root = project.resolve("compiler-src")
        writeJava(
            root,
            "com/mojang/blaze3d/platform/Window.java",
            """
                package com.mojang.blaze3d.platform;
                public final class Window {
                    private final int screenWidth;
                    private final int screenHeight;
                    private final int guiScaledWidth;
                    private final int guiScaledHeight;
                    public Window(
                        int screenWidth,
                        int screenHeight,
                        int guiScaledWidth,
                        int guiScaledHeight
                    ) {
                        this.screenWidth = screenWidth;
                        this.screenHeight = screenHeight;
                        this.guiScaledWidth = guiScaledWidth;
                        this.guiScaledHeight = guiScaledHeight;
                    }
                    public int getScreenWidth() { return screenWidth; }
                    public int getScreenHeight() { return screenHeight; }
                    public int getGuiScaledWidth() { return guiScaledWidth; }
                    public int getGuiScaledHeight() { return guiScaledHeight; }
                }
            """
        )
        writeJava(
            root,
            "com/mojang/blaze3d/vertex/PoseStack.java",
            """
                package com.mojang.blaze3d.vertex;
                import java.util.ArrayList;
                import java.util.List;
                public final class PoseStack {
                    public int pushes;
                    public int pops;
                    public final List<double[]> translations = new ArrayList<>();
                    public final List<float[]> scales = new ArrayList<>();
                    public void pushPose() { pushes++; }
                    public void popPose() { pops++; }
                    public void translate(double x, double y, double z) {
                        translations.add(new double[] { x, y, z });
                    }
                    public void scale(float x, float y, float z) {
                        scales.add(new float[] { x, y, z });
                    }
                }
            """
        )
        writeJava(
            root,
            "com/simibubi/create/Create.java",
            """
                package com.simibubi.create;
                public final class Create {
                    public static final String ID = "create";
                }
            """
        )
        writeJava(
            root,
            "com/simibubi/create/foundation/gui/RemovedGuiUtils.java",
            """
                package com.simibubi.create.foundation.gui;
                import java.util.List;
                import net.minecraft.client.gui.GuiGraphics;
                import net.minecraft.network.chat.FormattedText;
                public final class RemovedGuiUtils {
                    public static int calls;
                    public static GuiGraphics graphics;
                    public static int mouseX;
                    public static int mouseY;
                    public static int width;
                    public static int height;
                    public static void reset() {
                        calls = 0;
                        graphics = null;
                        mouseX = 0;
                        mouseY = 0;
                        width = 0;
                        height = 0;
                    }
                    public static void drawHoveringText(
                        GuiGraphics value,
                        List<? extends FormattedText> lines,
                        int x,
                        int y,
                        int screenWidth,
                        int screenHeight,
                        int maxWidth,
                        Object font
                    ) {
                        calls++;
                        graphics = value;
                        mouseX = x;
                        mouseY = y;
                        width = screenWidth;
                        height = screenHeight;
                    }
                }
            """
        )
        writeJava(
            root,
            "com/simibubi/create/foundation/utility/CreateLang.java",
            """
                package com.simibubi.create.foundation.utility;
                import net.minecraft.network.chat.FormattedText;
                public final class CreateLang {
                    public static Translation translate(String key) {
                        return new Translation();
                    }
                    public static final class Translation {
                        public FormattedText component() {
                            return new FormattedText() {};
                        }
                    }
                }
            """
        )
        writeJava(
            root,
            "com/simibubi/create/infrastructure/config/AllConfigs.java",
            """
                package com.simibubi.create.infrastructure.config;
                public final class AllConfigs {
                    private static final Client CLIENT = new Client();
                    public static Client client() { return CLIENT; }
                    public static final class Client {
                        public final BooleanValue showTrainMapOverlay = new BooleanValue();
                    }
                    public static final class BooleanValue {
                        private boolean value;
                        public boolean get() { return value; }
                        public void set(boolean next) { value = next; }
                    }
                }
            """
        )
        writeJava(
            root,
            "journeymap/api/v2/client/IClientAPI.java",
            """
                package journeymap.api.v2.client;
                public interface IClientAPI {}
            """
        )
        writeJava(
            root,
            "journeymap/api/v2/client/IClientPlugin.java",
            """
                package journeymap.api.v2.client;
                public interface IClientPlugin {
                    void initialize(IClientAPI api);
                    String getModId();
                }
            """
        )
        writeJava(
            root,
            "journeymap/api/v2/client/JourneyMapPlugin.java",
            """
                package journeymap.api.v2.client;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface JourneyMapPlugin {
                    String apiVersion();
                }
            """
        )
        writeJava(
            root,
            "journeymap/api/v2/client/display/Context.java",
            """
                package journeymap.api.v2.client.display;
                public final class Context {
                    public enum UI {
                        Fullscreen,
                        Other
                    }
                }
            """
        )
        writeJava(
            root,
            "journeymap/api/v2/client/util/UIState.java",
            """
                package journeymap.api.v2.client.util;
                import journeymap.api.v2.client.display.Context.UI;
                public final class UIState {
                    public UI ui;
                    public boolean active;
                    public double blockSize;
                    public UIState(UI ui, boolean active, double blockSize) {
                        this.ui = ui;
                        this.active = active;
                        this.blockSize = blockSize;
                    }
                }
            """
        )
        writeJava(
            root,
            "journeymap/api/v2/client/fullscreen/IFullscreen.java",
            """
                package journeymap.api.v2.client.fullscreen;
                import journeymap.api.v2.client.util.UIState;
                import net.minecraft.client.gui.screens.Screen;
                public interface IFullscreen {
                    UIState getUiState();
                    Screen getScreen();
                    double getCenterBlockX(boolean includeDrag);
                    double getCenterBlockZ(boolean includeDrag);
                }
            """
        )
        writeJava(
            root,
            "journeymap/api/v2/client/event/FullscreenRenderEvent.java",
            """
                package journeymap.api.v2.client.event;
                import journeymap.api.v2.client.fullscreen.IFullscreen;
                import net.minecraft.client.gui.GuiGraphics;
                public final class FullscreenRenderEvent {
                    private final GuiGraphics graphics;
                    private final IFullscreen fullscreen;
                    private final int mouseX;
                    private final int mouseY;
                    private final float partialTicks;
                    public FullscreenRenderEvent(
                        GuiGraphics graphics,
                        IFullscreen fullscreen,
                        int mouseX,
                        int mouseY,
                        float partialTicks
                    ) {
                        this.graphics = graphics;
                        this.fullscreen = fullscreen;
                        this.mouseX = mouseX;
                        this.mouseY = mouseY;
                        this.partialTicks = partialTicks;
                    }
                    public GuiGraphics getGraphics() { return graphics; }
                    public IFullscreen getFullscreen() { return fullscreen; }
                    public int getMouseX() { return mouseX; }
                    public int getMouseY() { return mouseY; }
                    public float getPartialTicks() { return partialTicks; }
                }
            """
        )
        writeJava(
            root,
            "journeymap/api/v2/common/event/FullscreenEventRegistry.java",
            """
                package journeymap.api.v2.common.event;
                import java.util.function.Consumer;
                import journeymap.api.v2.client.event.FullscreenRenderEvent;
                public final class FullscreenEventRegistry {
                    public static final FullscreenEvent FULLSCREEN_RENDER_EVENT =
                        new FullscreenEvent();
                    public static final class FullscreenEvent {
                        public int subscriptions;
                        public int callbacks;
                        public String modId;
                        private Consumer<FullscreenRenderEvent> listener;
                        public void reset() {
                            subscriptions = 0;
                            callbacks = 0;
                            modId = null;
                            listener = null;
                        }
                        public void subscribe(
                            String subscriptionModId,
                            Consumer<FullscreenRenderEvent> callback
                        ) {
                            subscriptions++;
                            modId = subscriptionModId;
                            listener = callback;
                        }
                        public void fire(FullscreenRenderEvent event) {
                            if (listener == null)
                                throw new AssertionError("no fullscreen listener");
                            callbacks++;
                            listener.accept(event);
                        }
                    }
                }
            """
        )
        writeJava(
            root,
            "net/minecraft/client/gui/screens/Screen.java",
            """
                package net.minecraft.client.gui.screens;
                public class Screen {
                    public int width;
                    public int height;
                    public Screen() {}
                    public Screen(int width, int height) {
                        this.width = width;
                        this.height = height;
                    }
                }
            """
        )
        writeJava(
            root,
            "journeymap/client/ui/fullscreen/Fullscreen.java",
            """
                package journeymap.client.ui.fullscreen;
                import journeymap.api.v2.client.fullscreen.IFullscreen;
                import journeymap.api.v2.client.util.UIState;
                import net.minecraft.client.gui.screens.Screen;
                public class Fullscreen extends Screen implements IFullscreen {
                    private final Screen screen;
                    private final UIState state;
                    private final double rawCenterX;
                    private final double rawCenterZ;
                    private final double dragX;
                    private final double dragZ;
                    public int centerXTrueRequests;
                    public int centerXFalseRequests;
                    public int centerZTrueRequests;
                    public int centerZFalseRequests;
                    public Fullscreen(
                        Screen screen,
                        UIState state,
                        double rawCenterX,
                        double rawCenterZ,
                        double dragX,
                        double dragZ
                    ) {
                        super(screen.width, screen.height);
                        this.screen = screen;
                        this.state = state;
                        this.rawCenterX = rawCenterX;
                        this.rawCenterZ = rawCenterZ;
                        this.dragX = dragX;
                        this.dragZ = dragZ;
                    }
                    @Override
                    public UIState getUiState() { return state; }
                    @Override
                    public Screen getScreen() { return screen; }
                    @Override
                    public double getCenterBlockX(boolean includeDrag) {
                        if (includeDrag)
                            centerXTrueRequests++;
                        else
                            centerXFalseRequests++;
                        return includeDrag ? rawCenterX - dragX : rawCenterX;
                    }
                    @Override
                    public double getCenterBlockZ(boolean includeDrag) {
                        if (includeDrag)
                            centerZTrueRequests++;
                        else
                            centerZFalseRequests++;
                        return includeDrag ? rawCenterZ - dragZ : rawCenterZ;
                    }
                }
            """
        )
        writeJava(
            root,
            "net/minecraft/client/Minecraft.java",
            """
                package net.minecraft.client;
                import com.mojang.blaze3d.platform.Window;
                import net.minecraft.client.gui.screens.Screen;
                public final class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public Screen screen;
                    public final MouseHandler mouseHandler = new MouseHandler();
                    public Object font = new Object();
                    private Window window = new Window(400, 200, 200, 100);
                    public static Minecraft getInstance() { return INSTANCE; }
                    public Window getWindow() { return window; }
                    public void setWindow(Window value) { window = value; }
                    public static final class MouseHandler {
                        private double x;
                        private double y;
                        public double xpos() { return x; }
                        public double ypos() { return y; }
                        public void set(double nextX, double nextY) {
                            x = nextX;
                            y = nextY;
                        }
                    }
                }
            """
        )
        writeJava(
            root,
            "net/minecraft/client/gui/GuiGraphics.java",
            """
                package net.minecraft.client.gui;
                import com.mojang.blaze3d.vertex.PoseStack;
                public final class GuiGraphics {
                    private final PoseStack pose = new PoseStack();
                    public PoseStack pose() { return pose; }
                }
            """
        )
        writeJava(
            root,
            "net/minecraft/client/renderer/Rect2i.java",
            """
                package net.minecraft.client.renderer;
                public final class Rect2i {
                    public final int x;
                    public final int y;
                    public final int width;
                    public final int height;
                    public Rect2i(int x, int y, int width, int height) {
                        this.x = x;
                        this.y = y;
                        this.width = width;
                        this.height = height;
                    }
                }
            """
        )
        writeJava(
            root,
            "net/minecraft/network/chat/FormattedText.java",
            """
                package net.minecraft.network.chat;
                public interface FormattedText {}
            """
        )
        writeJava(
            root,
            "net/minecraft/util/Mth.java",
            """
                package net.minecraft.util;
                public final class Mth {
                    public static int floor(double value) {
                        return (int) Math.floor(value);
                    }
                }
            """
        )
        writeJava(
            root,
            "net/neoforged/neoforge/client/event/InputEvent.java",
            """
                package net.neoforged.neoforge.client.event;
                public class InputEvent {
                    public static final class MouseButton {
                        public static final class Pre {
                            private boolean canceled;
                            public void setCanceled(boolean value) { canceled = value; }
                            public boolean isCanceled() { return canceled; }
                        }
                    }
                }
            """
        )
        writeJava(
            root,
            "com/simibubi/create/compat/trainmap/TrainMapSyncClient.java",
            """
                package com.simibubi.create.compat.trainmap;
                public final class TrainMapSyncClient {
                    public static int requests;
                    public static int stops;
                    public static void requestData() { requests++; }
                    public static void stopRequesting() { stops++; }
                }
            """
        )
        writeJava(
            root,
            "com/simibubi/create/compat/trainmap/TrainMapManager.java",
            """
                package com.simibubi.create.compat.trainmap;
                import java.util.List;
                import net.minecraft.client.gui.GuiGraphics;
                import net.minecraft.client.renderer.Rect2i;
                import net.minecraft.network.chat.FormattedText;
                public final class TrainMapManager {
                    public static int ticks;
                    public static int renderCalls;
                    public static int toggleCalls;
                    public static GuiGraphics graphics;
                    public static int mouseX;
                    public static int mouseY;
                    public static float partialTicks;
                    public static boolean linearFiltering;
                    public static Rect2i bounds;
                    public static boolean toggleHovered;
                    public static void reset() {
                        ticks = 0;
                        renderCalls = 0;
                        toggleCalls = 0;
                        graphics = null;
                        mouseX = 0;
                        mouseY = 0;
                        partialTicks = 0;
                        linearFiltering = true;
                        bounds = null;
                        toggleHovered = false;
                    }
                    public static void tick() { ticks++; }
                    public static boolean handleToggleWidgetClick(
                        int mouseX,
                        int mouseY,
                        int x,
                        int y
                    ) {
                        return false;
                    }
                    public static List<FormattedText> renderAndPick(
                        GuiGraphics value,
                        int renderedMouseX,
                        int renderedMouseY,
                        float renderedPartialTicks,
                        boolean renderedLinearFiltering,
                        Rect2i renderedBounds
                    ) {
                        renderCalls++;
                        graphics = value;
                        mouseX = renderedMouseX;
                        mouseY = renderedMouseY;
                        partialTicks = renderedPartialTicks;
                        linearFiltering = renderedLinearFiltering;
                        bounds = renderedBounds;
                        return List.of(new FormattedText() {});
                    }
                    public static void renderToggleWidget(
                        GuiGraphics graphics,
                        int x,
                        int y
                    ) {
                        toggleCalls++;
                    }
                    public static boolean isToggleWidgetHovered(
                        int mouseX,
                        int mouseY,
                        int x,
                        int y
                    ) {
                        return toggleHovered;
                    }
                }
            """
        )
        writeJava(
            root,
            "probe/JourneyMapProbe.java",
            """
                package probe;
                import com.mojang.blaze3d.platform.Window;
                import com.mojang.blaze3d.vertex.PoseStack;
                import com.simibubi.create.compat.trainmap.JourneyTrainMap;
                import com.simibubi.create.compat.trainmap.TrainMapManager;
                import com.simibubi.create.foundation.gui.RemovedGuiUtils;
                import com.simibubi.create.infrastructure.config.AllConfigs;
                import journeymap.api.v2.client.IClientAPI;
                import journeymap.api.v2.client.JourneyMapPlugin;
                import journeymap.api.v2.client.display.Context.UI;
                import journeymap.api.v2.client.event.FullscreenRenderEvent;
                import journeymap.api.v2.client.util.UIState;
                import journeymap.api.v2.common.event.FullscreenEventRegistry;
                import journeymap.client.ui.fullscreen.Fullscreen;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.gui.GuiGraphics;
                import net.minecraft.client.gui.screens.Screen;
                import net.minecraft.client.renderer.Rect2i;
                public final class JourneyMapProbe {
                    private static void check(boolean condition, String message) {
                        if (!condition)
                            throw new AssertionError(message);
                    }
                    private static void close(double expected, double actual, String message) {
                        if (Math.abs(expected - actual) > 0.00001)
                            throw new AssertionError(
                                message + ": expected=" + expected + ", actual=" + actual
                            );
                    }
                    public static String verify() {
                        TrainMapManager.reset();
                        RemovedGuiUtils.reset();
                        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.reset();
                        AllConfigs.client().showTrainMapOverlay.set(true);
                        Minecraft.getInstance().setWindow(new Window(400, 200, 200, 100));
                        JourneyTrainMap plugin = new JourneyTrainMap();
                        plugin.initialize(new IClientAPI() {});
                        check(
                            FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscriptions == 1,
                            "initialize must subscribe exactly once"
                        );
                        check(
                            "create".equals(
                                FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.modId
                            ),
                            "subscription must use Create.ID"
                        );
                        check("create".equals(plugin.getModId()), "plugin mod id");
                        JourneyMapPlugin annotation =
                            JourneyTrainMap.class.getAnnotation(JourneyMapPlugin.class);
                        check(annotation != null, "JourneyMapPlugin annotation missing");
                        check(
                            "2.0.0".equals(annotation.apiVersion()),
                            "JourneyMap plugin API version"
                        );
                        Screen uiScreen = new Screen(200, 100);
                        UIState state = new UIState(UI.Fullscreen, true, 2.0);
                        Fullscreen fullscreen = new Fullscreen(
                            uiScreen,
                            state,
                            110.0,
                            220.0,
                            10.0,
                            20.0
                        );
                        GuiGraphics graphics = new GuiGraphics();
                        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.fire(
                            new FullscreenRenderEvent(
                                graphics,
                                fullscreen,
                                50,
                                70,
                                0.25f
                            )
                        );
                        check(
                            FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.callbacks == 1,
                            "one event must invoke one callback"
                        );
                        check(fullscreen.centerXTrueRequests == 1, "center X must include drag once");
                        check(fullscreen.centerXFalseRequests == 0, "raw center X must not be read");
                        check(fullscreen.centerZTrueRequests == 1, "center Z must include drag once");
                        check(fullscreen.centerZFalseRequests == 0, "raw center Z must not be read");
                        check(TrainMapManager.renderCalls == 1, "enabled overlay render count");
                        check(TrainMapManager.graphics == graphics, "event graphics identity");
                        check(TrainMapManager.mouseX == 50, "rendered map mouse X");
                        check(TrainMapManager.mouseY == 220, "rendered map mouse Y");
                        close(0.25, TrainMapManager.partialTicks, "partial ticks");
                        check(!TrainMapManager.linearFiltering, "linear filtering flag");
                        Rect2i bounds = TrainMapManager.bounds;
                        check(bounds != null, "render bounds missing");
                        check(bounds.x == 0, "bounds X proves center X was not double-subtracted");
                        check(bounds.y == 150, "bounds Y proves center Z was not double-subtracted");
                        check(bounds.width == 200, "bounds width");
                        check(bounds.height == 100, "bounds height");
                        PoseStack pose = graphics.pose();
                        check(pose.pushes == 1, "pose push");
                        check(pose.pops == 1, "pose pop");
                        check(pose.translations.size() == 2, "pose translation count");
                        check(pose.scales.size() == 1, "pose scale count");
                        close(100.0, pose.translations.get(0)[0], "screen center translate X");
                        close(50.0, pose.translations.get(0)[1], "screen center translate Y");
                        close(-100.0, pose.translations.get(1)[0], "map center translate X");
                        close(-200.0, pose.translations.get(1)[1], "map center translate Z");
                        close(1.0, pose.scales.get(0)[0], "map X scale");
                        close(1.0, pose.scales.get(0)[1], "map Y scale");
                        check(RemovedGuiUtils.calls == 1, "train tooltip draw");
                        check(RemovedGuiUtils.graphics == graphics, "tooltip graphics identity");
                        check(RemovedGuiUtils.mouseX == 50, "tooltip mouse X");
                        check(RemovedGuiUtils.mouseY == 70, "tooltip mouse Y");
                        check(RemovedGuiUtils.width == 200, "tooltip screen width");
                        check(RemovedGuiUtils.height == 100, "tooltip screen height");
                        TrainMapManager.reset();
                        RemovedGuiUtils.reset();
                        TrainMapManager.toggleHovered = true;
                        AllConfigs.client().showTrainMapOverlay.set(false);
                        GuiGraphics disabledGraphics = new GuiGraphics();
                        JourneyTrainMap.onRender(
                            disabledGraphics,
                            fullscreen,
                            100.0,
                            200.0,
                            12,
                            14,
                            0.75f
                        );
                        check(TrainMapManager.renderCalls == 0, "disabled overlay rendered map");
                        check(TrainMapManager.toggleCalls == 1, "disabled overlay toggle widget");
                        check(RemovedGuiUtils.calls == 1, "disabled overlay toggle tooltip");
                        check(
                            RemovedGuiUtils.graphics == disabledGraphics,
                            "disabled overlay graphics identity"
                        );
                        check(RemovedGuiUtils.mouseX == 12, "toggle tooltip mouse X");
                        check(RemovedGuiUtils.mouseY == 34, "toggle tooltip mouse Y offset");
                        check(RemovedGuiUtils.width == 200, "toggle tooltip screen width");
                        check(RemovedGuiUtils.height == 100, "toggle tooltip screen height");
                        check(
                            disabledGraphics.pose().pushes == 0,
                            "disabled overlay must not enter map pose"
                        );
                        return "passed";
                    }
                }
            """
        )
    }
    private fun writeJava(root: Path, relative: String, source: String): Path {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }
    private fun copyFixture(name: String): Path {
        val source = fixtureRoot().resolve(name).resolve("project")
        assertTrue(source.exists(), "Missing fixture project: $source")
        val destination = createTempDirectory("create-journeymap-$name-").resolve("project")
        source.toFile().copyRecursively(destination.toFile(), overwrite = true)
        return destination
    }
    private fun fixtureRoot(): Path {
        System.getProperty("modporter.journeymap.fixtureRoot")
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it) }
            ?.toAbsolutePath()
            ?.normalize()
            ?.let { configured ->
                if (configured.resolve("original/project").exists()) return configured
            }
        var cursor: Path? = Path.of("").toAbsolutePath().normalize()
        while (cursor != null) {
            val published = cursor.resolve(
                "src/test/resources/fixtures/create-journeymap-v2"
            )
            if (published.resolve("original/project").exists()) return published
            cursor = cursor.parent
        }
        error(
            "Cannot locate Create JourneyMap fixtures; set " +
                "-Dmodporter.journeymap.fixtureRoot=<fixture directory>"
        )
    }
    private fun snapshot(project: Path): ProjectSnapshot {
        val files = Files.walk(project).use { paths ->
            paths.filter { it.isRegularFile() }
                .toList()
                .associate { file ->
                    project.relativize(file).invariantSeparators() to file.readBytes()
                }
        }
        return ProjectSnapshot(files)
    }
    private fun assertSnapshot(expected: ProjectSnapshot, actual: ProjectSnapshot) {
        assertEquals(expected.files.keys, actual.files.keys, "project file set changed")
        expected.files.forEach { (relative, bytes) ->
            assertContentEquals(bytes, actual.files.getValue(relative), relative)
        }
    }
    private fun Path.invariantSeparators(): String = toString().replace('\\', '/')
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    private data class ProjectSnapshot(val files: Map<String, ByteArray>)
}
