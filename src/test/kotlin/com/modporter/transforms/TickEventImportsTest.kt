package com.modporter.transforms

import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tick event classes imported as nested TickEvent members follow their 1.21 homes. */
class TickEventImportsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a nested render tick import and parameter become the client render frame event`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ClientEvents.java").writeText("""
            package com.example;

            import net.minecraftforge.event.TickEvent.RenderTickEvent;
            import net.minecraftforge.eventbus.api.SubscribeEvent;

            public class ClientEvents {
                @SubscribeEvent
                public static void onRenderTick(RenderTickEvent event) {
                }
            }
        """.trimIndent())

        TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)
        StructuralRefactorPass().apply(tempDir)

        val events = srcDir.resolve("ClientEvents.java").readText()
        assertTrue(events.contains("import net.neoforged.neoforge.client.event.RenderFrameEvent;"), events)
        assertTrue(events.contains("public static void onRenderTick(RenderFrameEvent event)"), events)
        assertTrue(events.contains("onRenderTick\$modporterPre(RenderFrameEvent.Pre event)"), events)
        assertTrue(events.contains("onRenderTick\$modporterPost(RenderFrameEvent.Post event)"), events)
        assertTrue(events.contains("onRenderTick(event);"), events)
        assertFalse(events.contains("net.neoforged.neoforge.event.RenderFrameEvent"), events)
        assertFalse(events.contains("RenderTickEvent"), events)
    }
}
