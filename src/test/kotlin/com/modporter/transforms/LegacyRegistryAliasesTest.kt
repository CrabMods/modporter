package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyRegistryAliasesTest {
    @TempDir lateinit var directory: Path

    private fun source(body: String): Path {
        val file = directory.resolve("src/main/java/example/Renames.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import java.util.Map;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.Item;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.registries.MissingMappingsEvent;
            import net.neoforged.neoforge.registries.MissingMappingsEvent.Mapping;
            @EventBusSubscriber(modid = "example")
            class Renames {
                static Map<String, ResourceLocation> renamed;
                $body
            }
        """.trimIndent())
        return file
    }

    @Test
    fun `table renames become aliases after registration and keep failure logs`() {
        val file = source("""
            @SubscribeEvent
            public static void names(MissingMappingsEvent event) {
                for (Mapping<Item> mapping : event.getMappings(Registries.ITEM, "example")) {
                    ResourceLocation key = mapping.getKey();
                    String path = key.getPath();
                    ResourceLocation target = renamed.get(path);
                    if (target != null) {
                        Item value = BuiltInRegistries.ITEM.get(target);
                        if (value != null) {
                            log.warn("rename {} {}", key, target);
                            try { mapping.remap(value); }
                            catch (Throwable failure) { log.warn("failed {}", key, failure); }
                        }
                    }
                }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val result = file.readText()
        assertTrue(result.contains("event.enqueueWork("), result)
        assertTrue(result.contains("renamed.entrySet()"), result)
        assertTrue(result.contains("BuiltInRegistries.ITEM.addAlias(key, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(value))"), result)
        assertTrue(result.contains("!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(modporterAlias.getValue())"), result)
        assertTrue(result.contains("log.debug(\"rename {} {}\""), result)
        assertTrue(result.contains("log.warn(\"failed {}\""), result)
        assertTrue(result.contains("EventBusSubscriber.Bus.MOD"), result)
        StructuralRefactorPass().apply(directory)
        assertEquals(result, file.readText())
    }

    @Test
    fun `literal renames retain each source path and unsupported missing mapping actions remain visible`() {
        val file = source("""
            @SubscribeEvent
            public static void names(MissingMappingsEvent event) {
                for (Mapping<Item> mapping : event.getMappings(Registries.ITEM, "example")) {
                    ResourceLocation key = mapping.getKey();
                    String path = key.getPath();
                    if (path.equals("old_widget")) mapping.remap(WIDGET);
                    else if (path.equals("older_widget")) mapping.remap(WIDGET);
                }
            }
        """.trimIndent())
        StructuralRefactorPass().apply(directory)
        val result = file.readText()
        assertTrue(result.contains("java.util.List.of(\"old_widget\", \"older_widget\")"), result)
        assertTrue(result.contains(".getKey(WIDGET)"), result)
        file.writeText(result.replace("net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent", "MissingMappingsEvent"))
        // A separate source with another operation must not be silently reduced to aliases.
        val unsupported = source("""
            @SubscribeEvent
            public static void names(MissingMappingsEvent event) {
                for (Mapping<Item> mapping : event.getMappings(Registries.ITEM, "example")) {
                    ResourceLocation key = mapping.getKey();
                    String path = key.getPath();
                    mapping.ignore();
                }
            }
        """.trimIndent())
        StructuralRefactorPass().apply(directory)
        assertTrue(unsupported.readText().contains("mapping.ignore()"), unsupported.readText())
        assertTrue(unsupported.readText().contains("MissingMappingsEvent event"), unsupported.readText())
    }
}
