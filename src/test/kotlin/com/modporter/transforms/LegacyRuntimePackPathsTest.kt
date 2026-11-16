package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyRuntimePackPaths
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyRuntimePackPathsTest {
    @TempDir
    lateinit var tempDir: Path

    private fun write(relative: String, content: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        return file.also { it.writeText(content.trimIndent()) }
    }

    private fun writePack() {
        write(
            "example/pack/MemoryPack.java",
            """
            package example.pack;

            import com.google.gson.JsonElement;
            import java.io.InputStream;
            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.packs.PackResources;
            import net.minecraft.server.packs.PackType;
            import net.minecraft.server.packs.resources.IoSupplier;

            public class MemoryPack implements PackResources {
                private final Map<String, IoSupplier<InputStream>> files = new HashMap<>();
                private final PackType type;

                public MemoryPack(PackType type) {
                    this.type = type;
                }

                private static String path(PackType type, ResourceLocation location) {
                    return type.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath();
                }

                public MemoryPack put(ResourceLocation location, JsonElement json) {
                    files.put(path(type, location.withSuffix(".json")), () -> null);
                    return this;
                }

                @Override
                public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
                    return files.get(path(type, location));
                }
            }
            """
        )
        write(
            "example/data/ExampleResult.java",
            """
            package example.data;

            import com.google.gson.JsonObject;
            import net.minecraft.data.recipes.FinishedRecipe;
            import net.minecraft.resources.ResourceLocation;

            public abstract class ExampleResult implements FinishedRecipe {
                public abstract ResourceLocation getId();
            }
            """
        )
    }

    @Test
    fun `pack entries holding recipe and tag content use the 1_21 data folders`() {
        writePack()
        val generator = write(
            "example/data/Generator.java",
            """
            package example.data;

            import com.google.gson.JsonObject;
            import com.mojang.serialization.JsonOps;
            import example.pack.MemoryPack;
            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagFile;

            public class Generator {
                private static final Map<ResourceLocation, JsonObject> FILES = new HashMap<>();

                static void insert(MemoryPack pack, ResourceLocation tag, TagFile file) {
                    FILES.forEach(pack::put);
                    pack.put(tag.withPrefix("tags/items/"), TagFile.CODEC.encodeStart(JsonOps.INSTANCE, file).result().orElseThrow());
                }

                static void add(ExampleResult result) {
                    FILES.put(result.getId().withPrefix("recipes/"), result.serializeRecipe());
                }
            }
            """
        )

        LegacyRuntimePackPaths.migrate(StructuralRefactorPass(), tempDir, dryRun = false)
        val migrated = generator.readText()

        assertTrue(migrated.contains("pack.put(tag.withPrefix(\"tags/item/\"), TagFile.CODEC"), migrated)
        assertTrue(migrated.contains("FILES.put(result.getId().withPrefix(\"recipe/\"), result.serializeRecipe());"), migrated)
        val again = migrated
        LegacyRuntimePackPaths.migrate(StructuralRefactorPass(), tempDir, dryRun = false)
        assertEquals(again, generator.readText())
    }

    @Test
    fun `prefixes without pack flow or matching content stay unchanged`() {
        writePack()
        val source = """
            package example.data;

            import com.google.gson.JsonObject;
            import example.pack.MemoryPack;
            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.resources.ResourceLocation;

            public class Unrelated {
                private static final Map<ResourceLocation, JsonObject> LOCAL = new HashMap<>();
                private static final Map<ResourceLocation, Object> EXPORT = new HashMap<>();

                static void insert(MemoryPack pack, ResourceLocation id, JsonObject json, ExampleResult result) {
                    LOCAL.forEach(pack::put);
                    pack.put(id.withPrefix("recipes/"), json);
                    pack.put(id.withPrefix("advancements/"), json);
                    LOCAL.put(id.withPrefix("tags/items/"), json);
                    EXPORT.put(result.getId().withPrefix("recipes/"), result.serializeRecipe());
                }
            }
        """.trimIndent()
        val file = write("example/data/Unrelated.java", source)

        LegacyRuntimePackPaths.migrate(StructuralRefactorPass(), tempDir, dryRun = false)

        assertEquals(source, file.readText())
    }
}
