package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticModBusSubscriberDistributionTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `owner distribution remains attached when mod bus subscribers become listeners`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText(
            """
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public final class ExampleMod {
                public ExampleMod(IEventBus modEventBus) {
                }
            }
            """.trimIndent()
        )
        writeSubscriber(srcDir, "ClientLifecycle", "Dist.CLIENT")
        writeSubscriber(srcDir, "ServerLifecycle", "Dist.DEDICATED_SERVER")
        writeSubscriber(
            srcDir,
            "BothLifecycle",
            "{Dist.CLIENT, Dist.DEDICATED_SERVER}"
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val main = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            main.contains(
                "if (net.neoforged.fml.loading.FMLLoader.getDist() == " +
                    "net.neoforged.api.distmarker.Dist.CLIENT) {\n" +
                    "            modEventBus.addListener(com.example.ClientLifecycle::setup);"
            ),
            main
        )
        assertTrue(
            main.contains(
                "if (net.neoforged.fml.loading.FMLLoader.getDist() == " +
                    "net.neoforged.api.distmarker.Dist.DEDICATED_SERVER) {\n" +
                    "            modEventBus.addListener(com.example.ServerLifecycle::setup);"
            ),
            main
        )
        assertTrue(
            main.contains("modEventBus.addListener(com.example.BothLifecycle::setup);"),
            main
        )
        assertFalse(
            Regex(
                """if\s*\([^)]*FMLLoader\.getDist\(\)[^)]*\)\s*\{\s*""" +
                    """modEventBus\.addListener\(com\.example\.BothLifecycle::setup\);"""
            ).containsMatchIn(main),
            main
        )

        for (name in listOf("ClientLifecycle", "ServerLifecycle", "BothLifecycle")) {
            val migrated = srcDir.resolve("$name.java").readText()
            assertFalse(migrated.contains("@EventBusSubscriber"), migrated)
            assertFalse(migrated.contains("@SubscribeEvent"), migrated)
        }
    }

    private fun writeSubscriber(srcDir: Path, name: String, distribution: String) {
        srcDir.resolve("$name.java").writeText(
            """
            package com.example;

            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            @EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD, value = $distribution)
            public final class $name {
                @SubscribeEvent
                public static void setup(FMLCommonSetupEvent event) {
                }
            }
            """.trimIndent()
        )
    }
}
