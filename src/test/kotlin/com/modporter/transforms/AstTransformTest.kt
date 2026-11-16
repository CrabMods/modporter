package com.modporter.transforms

import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AstTransformTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createTestFile(filename: String, content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve(filename)
        file.writeText(content)
        return tempDir
    }

    @Test
    fun `ResourceLocation two-arg constructor transformed to fromNamespaceAndPath`() {
        val projectDir = createTestFile("TestRL.java", """
            package com.example;
            import net.minecraft.resources.ResourceLocation;
            public class TestRL {
                public void test() {
                    ResourceLocation id = new ResourceLocation("mymod", "my_item");
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestRL.java").readText()

        assertTrue(transformed.contains("ResourceLocation.fromNamespaceAndPath"))
        assertFalse(transformed.contains("new ResourceLocation("))
        assertTrue(result.changes.any { it.ruleId == "ast-rl-two-arg" })
    }

    @Test
    fun `ResourceLocation single-arg constructor transformed to parse`() {
        val projectDir = createTestFile("TestRL2.java", """
            package com.example;
            import net.minecraft.resources.ResourceLocation;
            public class TestRL2 {
                public void test() {
                    ResourceLocation id = new ResourceLocation("mymod:my_item");
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestRL2.java").readText()

        assertTrue(transformed.contains("ResourceLocation.parse"))
        assertTrue(result.changes.any { it.ruleId == "ast-rl-one-arg" })
    }

    @Test
    fun `Cancelable annotation transformed to ICancellableEvent interface`() {
        val projectDir = createTestFile("TestEvent.java", """
            package com.example;
            import net.minecraftforge.eventbus.api.Cancelable;
            import net.minecraftforge.eventbus.api.Event;
            @Cancelable
            public class TestEvent extends Event {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestEvent.java").readText()

        assertFalse(transformed.contains("@Cancelable"))
        assertFalse(transformed.contains("import net.minecraftforge.eventbus.api.Cancelable;"))
        assertTrue(transformed.contains("import net.neoforged.bus.api.ICancellableEvent;"))
        assertTrue(transformed.contains("public class TestEvent extends Event implements ICancellableEvent"))
        assertTrue(result.changes.any { it.ruleId == "ast-cancelable" })
    }

    @Test
    fun `stale cancelable import is removed even when no annotation changed`() {
        val projectDir = createTestFile("DocumentedEvent.java", """
            package com.example;
            import net.neoforged.bus.api.Cancelable;
            import net.neoforged.neoforge.event.entity.EntityEvent;

            /**
             * This event is not {@link Cancelable}.
             */
            public class DocumentedEvent extends EntityEvent {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/DocumentedEvent.java").readText()

        assertFalse(transformed.contains("import net.neoforged.bus.api.Cancelable;"))
        assertTrue(transformed.contains("This event is not {@link Cancelable}."))
        assertTrue(result.changes.any { it.ruleId == "ast-stale-imports" })
    }

    @Test
    fun `Mod EventBusSubscriber annotation simplified`() {
        val projectDir = createTestFile("TestEvents.java", """
            package com.example;
            import net.minecraftforge.fml.common.Mod;
            @Mod.EventBusSubscriber(modid = "mymod")
            public class TestEvents {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestEvents.java").readText()

        assertTrue(transformed.contains("@EventBusSubscriber"))
        assertFalse(transformed.contains("@Mod.EventBusSubscriber"))
        assertTrue(result.changes.any { it.ruleId == "ast-eventbus-subscriber" })
    }

    @Test
    fun `Widget render renamed to renderWidget for EditBox subclass`() {
        val projectDir = createTestFile("TestWidget.java", """
            package com.example;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.components.EditBox;
            import net.minecraft.network.chat.Component;
            import net.minecraft.client.gui.Font;
            public class TestWidget extends EditBox {
                public TestWidget(Font font, int x, int y, int w, int h, Component label) {
                    super(font, x, y, w, h, label);
                }
                @Override
                public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
                    // custom render
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestWidget.java").readText()

        assertTrue(transformed.contains("renderWidget"))
        assertFalse(transformed.contains("void render("))
        assertTrue(result.changes.any { it.ruleId == "ast-widget-render-rename" })
    }

    @Test
    fun `Widget render NOT renamed for Screen subclass`() {
        val projectDir = createTestFile("TestScreen.java", """
            package com.example;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.network.chat.Component;
            public class TestScreen extends Screen {
                protected TestScreen(Component title) {
                    super(title);
                }
                @Override
                public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
                    super.render(guiGraphics, mouseX, mouseY, partialTicks);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestScreen.java").readText()

        assertTrue(transformed.contains("void render("))
        assertFalse(transformed.contains("renderWidget"))
        assertFalse(result.changes.any { it.ruleId == "ast-widget-render-rename" })
    }

    @Test
    fun `Widget render renamed for ObjectSelectionList subclass`() {
        val projectDir = createTestFile("TestList.java", """
            package com.example;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.components.ObjectSelectionList;
            import net.minecraft.client.Minecraft;
            public class TestList extends ObjectSelectionList<TestList.Entry> {
                public TestList(Minecraft mc, int w, int h, int y, int ih) {
                    super(mc, w, h, y, ih);
                }
                @Override
                public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
                    // custom render
                }
                public static class Entry extends ObjectSelectionList.Entry<Entry> {
                    public void render(GuiGraphics g, int i, int t, int l, int w, int h, int mx, int my, boolean hov, float pt) {}
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestList.java").readText()

        assertTrue(transformed.contains("void renderWidget("))
        assertTrue(result.changes.any { it.ruleId == "ast-widget-render-rename" })
    }

    @Test
    fun `Widget render renamed for ImageButton subclass`() {
        val projectDir = createTestFile("ImageButtonChild.java", """
            package com.example;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.components.ImageButton;
            import net.minecraft.client.gui.components.WidgetSprites;
            import net.minecraft.resources.ResourceLocation;
            public class ImageButtonChild extends ImageButton {
                public ImageButtonChild(WidgetSprites sprites) {
                    super(0, 0, 20, 20, sprites, button -> {});
                }
                @Override
                public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
                    super.render(guiGraphics, mouseX, mouseY, partialTicks);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/ImageButtonChild.java").readText()

        assertTrue(transformed.contains("void renderWidget("))
        assertFalse(transformed.contains("void render("))
        assertTrue(result.changes.any { it.ruleId == "ast-widget-render-rename" })
    }

    @Test
    fun `Widget render renamed for anonymous ImageButton subclass`() {
        val projectDir = createTestFile("ImageButtonFactory.java", """
            package com.example;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.components.ImageButton;
            import net.minecraft.client.gui.components.WidgetSprites;
            public class ImageButtonFactory {
                public ImageButton make(WidgetSprites sprites) {
                    return new ImageButton(0, 0, 20, 20, sprites, button -> {}) {
                        @Override
                        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
                            super.render(guiGraphics, mouseX, mouseY, partialTicks);
                        }
                    };
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/ImageButtonFactory.java").readText()

        assertTrue(transformed.contains("void renderWidget("))
        assertFalse(transformed.contains("void render("))
        assertTrue(result.changes.any { it.ruleId == "ast-widget-render-rename" })
    }

    @Test
    fun `SelectionList super constructor 6-param transformed to 5-param`() {
        val projectDir = createTestFile("TestSelectionList.java", """
            package com.example;
            import net.minecraft.client.gui.components.ObjectSelectionList;
            import net.minecraft.client.Minecraft;
            public class TestSelectionList extends ObjectSelectionList<TestSelectionList.Entry> {
                public TestSelectionList(Minecraft mc, int width, int height, int top, int bottom, int slotHeight) {
                    super(mc, width, height, top, bottom, slotHeight);
                }
                public static class Entry extends ObjectSelectionList.Entry<Entry> {
                    public void render(net.minecraft.client.gui.GuiGraphics g, int i, int t, int l, int w, int h, int mx, int my, boolean hov, float pt) {}
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestSelectionList.java").readText()

        // super() should now have 5 args (bottom removed)
        assertTrue(result.changes.any { it.ruleId == "ast-selection-list-super" })
        // The 'bottom' parameter should be removed from the constructor too
        assertTrue(result.changes.any { it.ruleId == "ast-selection-list-constructor" })
    }

    @Test
    fun `endVertex calls are removed`() {
        val projectDir = createTestFile("TestRenderer.java", """
            package com.example;
            public class TestRenderer {
                public void render() {
                    builder.vertex(matrix, x, y, z).color(r, g, b, a).uv(u, v).endVertex();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = AstTransformPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestRenderer.java").readText()

        assertFalse(transformed.contains("endVertex()"))
        assertFalse(transformed.contains("endVertex() removed"))
        assertTrue(result.changes.any { it.ruleId == "ast-remove-endvertex" })
        assertTrue(result.changes.filter { it.ruleId == "ast-remove-endvertex" }.all { it.confidence == Confidence.HIGH })
        assertFalse(result.changes.any { it.after.contains("/*") || it.after.contains("*/") })
    }

    @Test
    fun `modern Java source syntax is parsed without skipped AST transforms`() {
        val projectDir = createTestFile("ModernSyntax.java", """
            package com.example;
            import java.util.List;

            public record ModernSyntax(int size) {
                public String describe(Object value) {
                    if (value instanceof String text) {
                        return text;
                    }
                    return switch (size) {
                        case 0 -> "empty";
                        case 1 -> {
                            yield List.of("one").get(0);
                        }
                        default -> "many";
                    };
                }
            }
        """.trimIndent())

        val pass = AstTransformPass(MappingDatabase.loadDefault())
        val result = pass.analyze(projectDir)

        assertTrue(result.skipped.isEmpty(), "AST pass must parse modern Java source syntax: ${result.skipped}")
    }
}
