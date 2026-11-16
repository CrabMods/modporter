package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacySetupRegistryWrites
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacySetupRegistryWritesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `built-in registry writes leave common setup for the registry's register event`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        root.resolve("Triggers.java").writeText(
            """
            package example;

            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class Triggers {
                private static final List<Object> TRIGGERS = new ArrayList<>();
                static Object add(Object trigger) { TRIGGERS.add(trigger); return trigger; }
                public static void register() {
                    TRIGGERS.forEach(trigger -> Registry.register(BuiltInRegistries.TRIGGER_TYPES, null, null));
                }
            }
            """.trimIndent()
        )
        root.resolve("Advancements.java").writeText(
            """
            package example;

            public class Advancements {
                static final Object FIRST = Triggers.add(new Object());
                public static void register() {
                }
            }
            """.trimIndent()
        )
        root.resolve("Defaults.java").writeText(
            """
            package example;

            public class Defaults {
                public static void registerDefaults() {
                }
            }
            """.trimIndent()
        )
        val main = root.resolve("ExampleMod.java")
        main.writeText(
            """
            package example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            public class ExampleMod {
                public ExampleMod(IEventBus modEventBus) {
                    modEventBus.addListener(ExampleMod::init);
                }

                public static void init(final FMLCommonSetupEvent event) {
                    event.enqueueWork(() -> {
                        Defaults.registerDefaults();
                        // --
                        Advancements.register();
                        Triggers.register();
                    });
                }
            }
            """.trimIndent()
        )

        val changes = LegacySetupRegistryWrites.migrate(directory, dryRun = false)

        val text = main.readText()
        assertTrue(changes.single().ruleId == LegacySetupRegistryWrites.RULE_ID)
        assertTrue(text.contains("modEventBus.addListener(ExampleMod::init);\n        modEventBus.addListener(ExampleMod::modporterRegisterTriggerType);"), text)
        assertTrue(text.contains("Defaults.registerDefaults();"), text)
        assertFalse(Regex("""enqueueWork\(\(\) -> \{[^}]*Triggers\.register""").containsMatchIn(text), text)
        val listener = text.substringAfter("private static void modporterRegisterTriggerType(net.neoforged.neoforge.registries.RegisterEvent event) {")
        assertTrue(listener.contains("if (event.getRegistryKey().equals(net.minecraft.core.registries.Registries.TRIGGER_TYPE)) {"), text)
        assertTrue(listener.indexOf("Advancements.register();") in 0 until listener.indexOf("Triggers.register();"), text)
        assertEquals(1, Regex("""Triggers\.register\(\);""").findAll(text).count(), text)

        val migrated = text
        assertTrue(LegacySetupRegistryWrites.migrate(directory, dryRun = false).isEmpty())
        assertEquals(migrated, main.readText())
    }

    @Test
    fun `an empty initialization call whose class fills the writer through another class moves with it`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        root.resolve("Triggers.java").writeText(
            """
            package example;

            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class Triggers {
                private static final List<Object> TRIGGERS = new ArrayList<>();
                static Object add(Object trigger) { TRIGGERS.add(trigger); return trigger; }
                public static void register() {
                    TRIGGERS.forEach(trigger -> Registry.register(BuiltInRegistries.TRIGGER_TYPES, null, null));
                }
            }
            """.trimIndent()
        )
        root.resolve("Advancement.java").writeText(
            """
            package example;

            public class Advancement {
                private final Object trigger;
                Advancement(String id) { trigger = Triggers.add(id); }
            }
            """.trimIndent()
        )
        root.resolve("Advancements.java").writeText(
            """
            package example;

            public class Advancements {
                static final Advancement FIRST = new Advancement("first");
                public static void register() {
                }
            }
            """.trimIndent()
        )
        root.resolve("Sounds.java").writeText(
            """
            package example;

            public class Sounds {
                static int loaded;
                public static void load() {
                    loaded++;
                }
            }
            """.trimIndent()
        )
        val main = root.resolve("ExampleMod.java")
        main.writeText(
            """
            package example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            public class ExampleMod {
                public ExampleMod(IEventBus modEventBus) {
                    modEventBus.addListener(ExampleMod::init);
                }

                public static void init(final FMLCommonSetupEvent event) {
                    event.enqueueWork(() -> {
                        Sounds.load();
                        Advancements.register();
                        Triggers.register();
                    });
                }
            }
            """.trimIndent()
        )

        LegacySetupRegistryWrites.migrate(directory, dryRun = false)

        val text = main.readText()
        val listener = text.substringAfter("private static void modporterRegisterTriggerType(net.neoforged.neoforge.registries.RegisterEvent event) {")
        assertTrue(listener.indexOf("Advancements.register();") in 0 until listener.indexOf("Triggers.register();"), text)
        // a call with its own effects that does not reach the writer keeps its place
        assertTrue(text.substringBefore("private static void modporterRegisterTriggerType").contains("Sounds.load();"), text)
        assertFalse(listener.contains("Sounds.load();"), text)
    }
}
