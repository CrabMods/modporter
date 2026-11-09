package com.modporter.core.transforms.build

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ThirdPartyReflectionMixinMigrationOwnershipTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `optional accessor selects the unique source owning mixin config`() {
        val source = directory.resolve("src/main/java/com/simibubi/create/compat/trainmap/FTBChunksTrainMap.java")
        source.parent.createDirectories()
        source.writeText(
            """
            package com.simibubi.create.compat.trainmap;

            import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
            import dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;

            public class FTBChunksTrainMap {
                void render(LargeMapScreen screen) {
                    Object panel = ObfuscationReflectionHelper.getPrivateValue(
                        LargeMapScreen.class, screen, "regionPanel");
                    if (!(panel instanceof RegionMapPanel regionPanel)) {
                        return;
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        val resources = directory.resolve("src/main/resources")
        resources.createDirectories()
        val ownerConfig = resources.resolve("create.mixins.json")
        val modelConfig = resources.resolve("create.modporter-model-data.mixins.json")
        val gameTestConfig = resources.resolve("modporter.gametest.mixins.json")
        ownerConfig.writeText(config("com.simibubi.create.foundation.mixin"))
        modelConfig.writeText(config("com.modporter.generated.create.compat.mixin"))
        gameTestConfig.writeText(config("com.modporter.generated.gametest.mixin"))

        val changes = ThirdPartyReflectionMixinMigration().migrate(directory, dryRun = false)

        assertTrue(changes.any { it.ruleId == "build-third-party-pseudo-accessor-config" })
        assertTrue(ownerConfig.readText().contains("modporter.ModPorterLargeMapScreenAccessor"))
        assertFalse(modelConfig.readText().contains("ModPorterLargeMapScreenAccessor"))
        assertFalse(gameTestConfig.readText().contains("ModPorterLargeMapScreenAccessor"))
        assertTrue(
            source.readText().contains(
                "com.simibubi.create.foundation.mixin.modporter.ModPorterLargeMapScreenAccessor"
            )
        )
    }

    private fun config(packageName: String): String =
        """
        {
          "required": true,
          "package": "$packageName",
          "mixins": [],
          "client": []
        }
        """.trimIndent() + "\n"
}
