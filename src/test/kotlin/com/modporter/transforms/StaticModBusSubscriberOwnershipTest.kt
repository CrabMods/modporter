package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateStaticModBusSubscribers
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class StaticModBusSubscriberOwnershipTest {
    @TempDir
    lateinit var directory: Path

    private fun java(path: String, source: String): Path {
        val file = directory.resolve("src/main/java/$path.java")
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
        return file
    }

    @Test
    fun `nested subscriber uses its complete owner and ignores sibling handlers`() {
        val main = java(
            "example/ExampleMod",
            """
            package example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
                public ExampleMod(IEventBus modEventBus) {
                }
            }
            """
        )
        val events = java(
            "example/events/CommonEvents",
            """
            package example.events;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

            public class CommonEvents {
                @SubscribeEvent
                public static void unrelated(RegisterCapabilitiesEvent event) {
                }

                @EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
                public static class ModBusEvents {
                    @SubscribeEvent
                    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
                    }
                }

                @EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
                public static class OtherModBusEvents {
                    @SubscribeEvent
                    public static void untouched(UnknownModBusEvent event) {
                    }
                }
            }
            """
        )

        val changes = StructuralRefactorPass()
            .migrateStaticModBusSubscribers(directory, dryRun = false)

        assertTrue(changes.isNotEmpty(), changes.toString())
        val mainSource = main.readText()
        assertTrue(
            mainSource.contains(
                "modEventBus.addListener(example.events.CommonEvents.ModBusEvents::registerCapabilities);"
            ),
            mainSource
        )
        assertFalse(mainSource.contains("::unrelated"), mainSource)
        assertFalse(mainSource.contains("example.events.ModBusEvents"), mainSource)
        val eventSource = events.readText()
        assertTrue(eventSource.contains("static void unrelated"), eventSource)
        assertTrue(eventSource.contains("@SubscribeEvent"), eventSource)
        assertFalse(
            eventSource.contains(
                "@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)\n" +
                    "    public static class ModBusEvents"
            ),
            eventSource
        )
        assertTrue(
            eventSource.contains(
                "@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)\n" +
                    "    public static class OtherModBusEvents"
            ),
            eventSource
        )
        assertTrue(eventSource.contains("static void untouched"), eventSource)
    }
}
