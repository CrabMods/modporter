package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** Build-pass source surfaces that are small enough to test on their own. */
class BuildSystemSurfacesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `an event subscriber already limited to a dist by its positional value keeps its annotation`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        tempDir.resolve("build.gradle").writeText("plugins {\n    id 'net.minecraftforge.gradle' version '[6.0,6.2)'\n}\n")
        srcDir.resolve("ClientEvents.java").writeText("""
            package com.example;

            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.client.event.ClientTickEvent;

            @EventBusSubscriber(Dist.CLIENT)
            public class ClientEvents {
                @SubscribeEvent
                public static void onTick(ClientTickEvent.Post event) {
                    net.minecraft.client.Minecraft.getInstance();
                }
            }
        """.trimIndent())

        BuildSystemPass().apply(tempDir)

        val events = srcDir.resolve("ClientEvents.java").readText()
        // the positional and the named form of the single `value` member are the same annotation
        val annotations = Regex("""@EventBusSubscriber\(([^)]*)\)""")
            .findAll(events)
            .map { it.groupValues[1].trim() }
            .toList()
        assertTrue(
            annotations.size == 1 && annotations.single() in setOf("Dist.CLIENT", "value = Dist.CLIENT"),
            events
        )
    }
}
