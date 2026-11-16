package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyCommonTagNamespaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyCommonTagNamespacesTest {
    @TempDir
    lateinit var tempDir: Path

    private fun write(relative: String, content: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        return file.also { it.writeText(content.trimIndent()) }
    }

    private val tags = """
        package example;

        import static example.ExampleTags.Space.COMMON;
        import static example.ExampleTags.Space.MOD;

        import java.util.Locale;
        import net.createmod.catnip.lang.Lang;
        import net.minecraft.core.registries.Registries;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.tags.TagKey;
        import net.minecraft.world.item.Item;
        import net.minecraft.world.level.block.Block;

        public class ExampleTags {
            public static TagKey<Item> lookup(ResourceLocation id) {
                return TagKey.create(Registries.ITEM, id);
            }

            public static TagKey<Item> commonItem(String path) {
                return lookup(COMMON.id(path));
            }

            public enum Space {
                MOD("example"),
                COMMON("forge");

                public final String id;

                Space(String id) {
                    this.id = id;
                }

                public ResourceLocation id(String path) {
                    return ResourceLocation.fromNamespaceAndPath(this.id, path);
                }

                public ResourceLocation id(Enum<?> entry, String pathOverride) {
                    return this.id(pathOverride != null ? pathOverride : Lang.asId(entry.name()));
                }
            }

            public enum ItemKeys {
                LOCAL,
                GEARS(COMMON, "gears/iron"),
                RODS(COMMON);

                public final TagKey<Item> tag;

                ItemKeys() {
                    this(MOD);
                }

                ItemKeys(Space space) {
                    this(space, null);
                }

                ItemKeys(Space space, String pathOverride) {
                    this.tag = TagKey.create(Registries.ITEM, space.id(this, pathOverride));
                }
            }

            public enum BlockKeys {
                STORAGE(COMMON, "storage_blocks/iron");

                public final TagKey<Block> tag;

                BlockKeys(Space space, String path) {
                    this.tag = TagKey.create(Registries.BLOCK, space.id(this, path.toLowerCase(Locale.ROOT)));
                }
            }
        }
    """

    private val metals = """
        package example.data;

        import example.ExampleTags;
        import net.minecraft.core.registries.Registries;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.tags.ItemTags;
        import net.minecraft.tags.TagKey;
        import net.minecraft.world.item.Item;
        import net.neoforged.neoforge.common.conditions.TagEmptyCondition;

        public enum Metals {
            IRON, COPPER;

            public final TagKey<Item> ingots;
            public final Pair storage;

            Metals() {
                String name = name().toLowerCase();
                this.ingots = itemTag("ingots/" + name);
                this.storage = new Pair("storage_blocks/" + name);
            }

            private static TagKey<Item> itemTag(String path) {
                return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", path));
            }

            static Object condition(String name) {
                return new TagEmptyCondition("forge", "ores/" + name);
            }

            static TagKey<Item> sand() {
                return ItemTags.create(ResourceLocation.fromNamespaceAndPath("forge", "sand"));
            }

            static TagKey<Item> dust(String name) {
                return ExampleTags.commonItem("dusts/" + name);
            }

            public record Pair(TagKey<Item> first, TagKey<Item> second) {
                private Pair(String path) {
                    this(itemTag(path), itemTag(path + "/alt"));
                }
            }
        }
    """

    @Test
    fun `legacy tag namespaces in code follow the data migration table`() {
        val tagFile = write("example/ExampleTags.java", tags)
        val metalFile = write("example/data/Metals.java", metals)

        LegacyCommonTagNamespaces.migrate(tempDir, dryRun = false)
        val migratedTags = tagFile.readText()
        val migratedMetals = metalFile.readText()

        assertTrue(migratedTags.contains("COMMON(\"c\");"), migratedTags)
        assertTrue(migratedTags.contains("MOD(\"example\"),"), migratedTags)
        assertTrue(migratedTags.contains("GEARS(COMMON, \"gears/iron\"),"), migratedTags)
        assertTrue(
            migratedMetals.contains("ResourceLocation.fromNamespaceAndPath(\"c\", path)"),
            migratedMetals
        )
        assertTrue(migratedMetals.contains("new TagEmptyCondition(\"c\", \"ores/\" + name)"), migratedMetals)
        assertTrue(
            migratedMetals.contains("ItemTags.create(ResourceLocation.fromNamespaceAndPath(\"c\", \"sands\"))"),
            migratedMetals
        )
        assertFalse(migratedMetals.contains("\"forge\""), migratedMetals)

        LegacyCommonTagNamespaces.migrate(tempDir, dryRun = false)
        assertEquals(migratedTags, tagFile.readText())
        assertEquals(migratedMetals, metalFile.readText())
    }

    @Test
    fun `namespace strings that are not only tag namespaces stay unchanged`() {
        val tagSource = tags
            .replace(
                "public static TagKey<Item> commonItem(String path) {",
                "public static boolean isCommon(Space space) {\n                return space == COMMON;\n            }\n\n            public static TagKey<Item> commonItem(String path) {"
            )
        val tagFile = write("example/ExampleTags.java", tagSource)
        val other = """
            package example.data;

            import net.minecraft.resources.ResourceLocation;

            public class Settings {
                static ResourceLocation key() {
                    return register(ResourceLocation.fromNamespaceAndPath("forge", "config"));
                }

                static ResourceLocation register(ResourceLocation id) {
                    return id;
                }
            }
        """
        val otherFile = write("example/data/Settings.java", other)

        LegacyCommonTagNamespaces.migrate(tempDir, dryRun = false)

        assertEquals(tagSource.trimIndent(), tagFile.readText())
        assertEquals(other.trimIndent(), otherFile.readText())
    }

    @Test
    fun `tag sites whose paths the table renames indirectly stay unchanged`() {
        val tagSource = tags.replace("GEARS(COMMON, \"gears/iron\"),", "GEARS(COMMON, \"sand\"),")
        val tagFile = write("example/ExampleTags.java", tagSource)
        val helper = """
            package example.data;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;

            public class Helpers {
                static final TagKey<Item> GRAVEL = itemTag("gravel");

                private static TagKey<Item> itemTag(String path) {
                    return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("forge", path));
                }
            }
        """
        val helperFile = write("example/data/Helpers.java", helper)

        LegacyCommonTagNamespaces.migrate(tempDir, dryRun = false)

        assertEquals(tagSource.trimIndent(), tagFile.readText())
        assertEquals(helper.trimIndent(), helperFile.readText())
    }
}
