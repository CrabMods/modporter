package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyLootFunctionSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyLootFunctionClassMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `single file rules preserve the legacy target for the project policy phase`() {
        val source = """
            package example;
            import net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction;
            import org.spongepowered.asm.mixin.Mixin;
            @Mixin(LootingEnchantFunction.class)
            class LootMixin {}
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateLegacyLootFunctionSource(source)

        assertTrue(migrated == source, migrated)
    }

    @Test
    fun `looting function mixin target follows the 121 class without touching text`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("LootMixin.java")
        file.writeText("""
            package example;
            import net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction;
            import org.spongepowered.asm.mixin.Mixin;
            @Mixin(LootingEnchantFunction.class)
            class LootMixin {
                private static final String DOC = "LootingEnchantFunction";
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("import net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction;"), migrated)
        assertTrue(migrated.contains("@Mixin(EnchantedCountIncreaseFunction.class)"), migrated)
        assertTrue(migrated.contains("\"LootingEnchantFunction\""), migrated)
        assertFalse(migrated.contains("import net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction;"), migrated)
    }
}
