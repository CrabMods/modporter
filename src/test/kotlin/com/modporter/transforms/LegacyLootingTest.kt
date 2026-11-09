package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyLooting
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LegacyLootingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `legacy looting policy and mixin fail closed without partial writes`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val handler = java.resolve("Crusher.java")
        val originalHandler = """
            package example;
            import net.minecraft.world.damagesource.DamageSource;
            import net.minecraftforge.event.entity.living.LootingLevelEvent;
            class Crusher {
                static void fortunate(LootingLevelEvent event) {
                    DamageSource source = event.getDamageSource();
                    if (source == null || !source.is(DamageTypes.CRUSH))
                        return;
                    event.setLootingLevel(2);
                }
            }
        """.trimIndent()
        handler.writeText(originalHandler)
        val mixin = java.resolve("LootingMixin.java")
        val originalMixin = """
            package example;
            import net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction;
            import org.spongepowered.asm.mixin.Mixin;
            @Mixin(LootingEnchantFunction.class)
            abstract class LootingMixin {}
        """.trimIndent()
        mixin.writeText(originalMixin)
        val resources = directory.resolve("src/main/resources").createDirectories()
        val config = resources.resolve("example.mixins.json")
        val originalConfig = """
            {
              "required": true,
              "package": "example",
              "mixins": ["LootingMixin"]
            }
        """.trimIndent()
        config.writeText(originalConfig)

        val failure = assertFailsWith<IllegalStateException> {
            LegacyLooting.migrate(StructuralRefactorPass(), directory, dryRun = false)
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "Lossless LootingLevelEvent/LootingEnchantFunction migration is unavailable"
            ) && failure.message.orEmpty().contains("configuration left unchanged"),
            failure.message.orEmpty()
        )
        assertEquals(originalHandler, handler.readText())
        assertEquals(originalMixin, mixin.readText())
        assertEquals(originalConfig, config.readText())
        assertFalse(java.resolve("ModporterLegacyLootingMobMixin.java").exists())
    }
}
