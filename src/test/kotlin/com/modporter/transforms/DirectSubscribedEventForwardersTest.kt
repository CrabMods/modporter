package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.removeDirectlySubscribedEventForwarders
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DirectSubscribedEventForwardersTest {
    @TempDir
    lateinit var directory: Path

    private fun java(name: String, source: String): Path {
        val file = directory.resolve("src/main/java/example/$name.java")
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }

    @Test
    fun `sole subscribed forwarding wrapper is removed when delegate is directly subscribed`() {
        java(
            "Controller",
            """
                package example;
                import net.neoforged.bus.api.SubscribeEvent;
                import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
                public class Controller {
                    @SubscribeEvent
                    public static void attach(EntityJoinLevelEvent event) {
                        initialize(event.getEntity());
                    }
                }
            """
        )
        val common = java(
            "CommonEvents",
            """
                package example;
                import net.minecraft.world.entity.Entity;
                import net.neoforged.bus.api.SubscribeEvent;
                import net.neoforged.neoforge.event.AttachCapabilitiesEvent;
                @net.neoforged.fml.common.EventBusSubscriber
                public class CommonEvents {
                    @SubscribeEvent
                    public static void attachCapabilities(AttachCapabilitiesEvent<Entity> event) {
                        Controller.attach(event);
                    }
                    @SubscribeEvent
                    public static void keep(OtherEvent event) {
                        Controller.keep(event);
                    }
                }
            """
        )

        val changes = StructuralRefactorPass().removeDirectlySubscribedEventForwarders(directory, false)
        val migrated = common.readText()

        assertTrue(changes.any { it.ruleId == "struct-direct-subscribed-event-forwarder" }, changes.toString())
        assertFalse(migrated.contains("attachCapabilities"), migrated)
        assertFalse(migrated.contains("AttachCapabilitiesEvent"), migrated)
        assertTrue(migrated.contains("public static void keep"), migrated)
        assertTrue(migrated.contains("import net.neoforged.bus.api.SubscribeEvent;"), migrated)
    }

    @Test
    fun `explicit source reference keeps forwarding wrapper`() {
        java(
            "Controller",
            """
                package example;
                public class Controller {
                    @net.neoforged.bus.api.SubscribeEvent
                    public static void attach(NewEvent event) {
                    }
                }
            """
        )
        val common = java(
            "CommonEvents",
            """
                package example;
                public class CommonEvents {
                    @net.neoforged.bus.api.SubscribeEvent
                    public static void attachCapabilities(OldEvent event) {
                        Controller.attach(event);
                    }
                }
            """
        )
        java(
            "Caller",
            """
                package example;
                public class Caller {
                    void call(OldEvent event) {
                        CommonEvents.attachCapabilities(event);
                    }
                }
            """
        )

        val changes = StructuralRefactorPass().removeDirectlySubscribedEventForwarders(directory, false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertTrue(common.readText().contains("attachCapabilities"), common.readText())
    }
}
