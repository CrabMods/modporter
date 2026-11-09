package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateCommonMetalTagNamespaceMigration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateCommonMetalTagNamespaceMigrationTest {
    @Test
    fun `migrates both exact Create factories and preserves enum metadata`() {
        val project = Files.createTempDirectory("create-common-metal-tags")
        val source = writeCommonMetal(project, legacySource())

        val result = CreateCommonMetalTagNamespaceMigration.migrate(project, dryRun = false)

        assertEquals(1, result.changes.size, result.gates.joinToString())
        assertTrue(result.gates.isEmpty())
        val migrated = source.readText()
        assertEquals(2, "\"c\"".toRegex().findAll(migrated).count())
        assertFalse(migrated.contains("\"forge\""))
        assertFalse(migrated.contains("TODO: change forge to c in 1.21"))
        assertTrue(migrated.contains("SILVER(IE, GS)"))
        assertTrue(migrated.contains("ALUMINUM(IC2, IE)"))

        val second = CreateCommonMetalTagNamespaceMigration.migrate(project, dryRun = false)
        assertTrue(second.changes.isEmpty())
        assertTrue(second.gates.isEmpty())
        assertEquals(migrated, source.readText())
    }

    @Test
    fun `dry run reports the migration without writing`() {
        val project = Files.createTempDirectory("create-common-metal-tags-dry")
        val source = writeCommonMetal(project, legacySource())
        val before = source.readText()

        val result = CreateCommonMetalTagNamespaceMigration.migrate(project, dryRun = true)

        assertEquals(1, result.changes.size, result.gates.joinToString())
        assertTrue(result.gates.isEmpty())
        assertEquals(before, source.readText())
    }

    @Test
    fun `refuses a mixed namespace family without writing`() {
        val project = Files.createTempDirectory("create-common-metal-tags-mixed")
        val source = writeCommonMetal(
            project,
            legacySource().replaceFirst(
                "ResourceLocation.fromNamespaceAndPath(\"forge\", path)",
                "ResourceLocation.fromNamespaceAndPath(\"c\", path)"
            )
        )
        val before = source.readText()

        val result = CreateCommonMetalTagNamespaceMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains("partial or incompatible"),
            result.gates.single()
        )
        assertEquals(before, source.readText())
    }

    @Test
    fun `refuses an altered factory body without writing`() {
        val project = Files.createTempDirectory("create-common-metal-tags-altered")
        val altered = legacySource().replaceFirst(
            Regex(
                """return\s+TagKey\.create\(Registries\.ITEM,\s*""" +
                    """ResourceLocation\.fromNamespaceAndPath\("forge",\s*path\)\);"""
            ),
            "return customItemTag(path);"
        )
        assertFalse(altered.contains("TagKey.create(Registries.ITEM"))
        val source = writeCommonMetal(
            project,
            altered
        )
        val before = source.readText()

        val result = CreateCommonMetalTagNamespaceMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains("partial or incompatible"),
            result.gates.single()
        )
        assertEquals(before, source.readText())
    }

    private fun writeCommonMetal(project: Path, content: String): Path {
        val source = project.resolve(
            "src/main/java/com/simibubi/create/foundation/data/recipe/CommonMetal.java"
        )
        source.parent.createDirectories()
        source.writeText(content)
        return source
    }

    private fun legacySource(): String = """
        package com.simibubi.create.foundation.data.recipe;

        import net.minecraft.core.registries.Registries;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.tags.TagKey;
        import net.minecraft.world.item.Item;
        import net.minecraft.world.level.block.Block;

        public enum CommonMetal {
            SILVER(IE, GS),
            ALUMINUM(IC2, IE);

            CommonMetal(Object... mods) {
            }

            private static TagKey<Item> itemTag(String path) {
                // TODO: change forge to c in 1.21
                return TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("forge", path));
            }

            private static TagKey<Block> blockTag(String path) {
                // TODO: change forge to c in 1.21
                return TagKey.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath("forge", path));
            }
        }
    """.trimIndent()
}
