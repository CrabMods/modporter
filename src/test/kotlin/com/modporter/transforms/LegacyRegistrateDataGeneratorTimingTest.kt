package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyRegistrateDataGeneratorTiming
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyRegistrateDataGeneratorTimingTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `registrate data generators added from a lowest gather listener move ahead of registrate`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        val main = root.resolve("ExampleMod.java")
        main.writeText(
            """
            package example;

            import com.tterrag.registrate.Registrate;
            import net.neoforged.bus.api.EventPriority;
            import net.neoforged.bus.api.IEventBus;

            public class ExampleMod {
                static final Registrate REGISTRATE = Registrate.create("example");

                public ExampleMod(IEventBus modEventBus) {
                    modEventBus.addListener(EventPriority.LOWEST, ExampleDatagen::gatherData);
                }

                static Registrate registrate() { return REGISTRATE; }
            }
            """.trimIndent() + "\n"
        )
        val datagen = root.resolve("ExampleDatagen.java")
        datagen.writeText(
            """
            package example;

            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ExampleDatagen {
                public static void gatherData(GatherDataEvent event) {
                    addExtraRegistrateData();

                    event.getGenerator();
                }

                private static void addExtraRegistrateData() {
                    ExampleMod.registrate().addDataGenerator(null, provider -> {});
                }
            }
            """.trimIndent() + "\n"
        )

        val changes = LegacyRegistrateDataGeneratorTiming.migrate(directory, dryRun = false)

        assertEquals(2, changes.size, changes.toString())
        val mainText = main.readText()
        assertTrue(
            mainText.contains(
                "modEventBus.addListener(EventPriority.LOWEST, ExampleDatagen::gatherData);\n" +
                    "        modEventBus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, ExampleDatagen::modporterAddRegistrateDataGenerators);"
            ),
            mainText
        )
        val datagenText = datagen.readText()
        val gather = datagenText.substringAfter("public static void gatherData(GatherDataEvent event) {").substringBefore("}")
        assertFalse(gather.contains("addExtraRegistrateData"), datagenText)
        assertTrue(gather.contains("event.getGenerator();"), datagenText)
        assertTrue(
            datagenText.contains(
                "public static void modporterAddRegistrateDataGenerators(net.neoforged.neoforge.data.event.GatherDataEvent event) {\n" +
                    "        ExampleDatagen.addExtraRegistrateData();"
            ),
            datagenText
        )

        val snapshot = mainText to datagenText
        assertTrue(LegacyRegistrateDataGeneratorTiming.migrate(directory, dryRun = false).isEmpty())
        assertEquals(snapshot, main.readText() to datagen.readText())
    }

    @Test
    fun `a gather listener at normal priority or without registrate data stays as it is`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        val main = root.resolve("Plain.java")
        val text = """
            package example;

            import com.tterrag.registrate.Registrate;
            import net.neoforged.bus.api.EventPriority;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class Plain {
                public Plain(IEventBus bus) {
                    bus.addListener(EventPriority.LOWEST, Plain::gather);
                }
                public static void gather(GatherDataEvent event) {
                    prepare();
                }
                private static void prepare() {
                }
                static void unrelated() { Registrate.create("x").addDataGenerator(null, p -> {}); }
            }
        """.trimIndent() + "\n"
        main.writeText(text)

        assertTrue(LegacyRegistrateDataGeneratorTiming.migrate(directory, dryRun = false).isEmpty())
        assertEquals(text, main.readText())
    }
}
