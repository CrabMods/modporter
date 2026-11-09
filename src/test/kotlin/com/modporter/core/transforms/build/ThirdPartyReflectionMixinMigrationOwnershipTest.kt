package com.modporter.core.transforms.build

import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        val original = snapshot()
        val preview = ThirdPartyReflectionMixinMigration().migrate(directory, dryRun = true)
        assertTrue(preview.any { it.ruleId == "build-third-party-pseudo-accessor-config" })
        assertEquals(original, snapshot())
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
        val migrated = snapshot()
        assertTrue(ThirdPartyReflectionMixinMigration().migrate(directory, dryRun = false).isEmpty())
        assertEquals(migrated, snapshot())
    }

    @Test
    fun `all consumers must select the same config even if one ownership score is stronger`() {
        writeConsumer("com.simibubi.create.compat.trainmap", "First")
        writeConsumer("org.other.example.consumer", "Second")
        writeConfig("create", "com.simibubi.create.foundation.mixin")
        writeConfig("other", "org.other.mixin")

        assertRefusedWithoutWrites("different source-owning Mixin configs")
    }

    @Test
    fun `one ambiguous consumer refuses the batch even if another has unique stronger ownership`() {
        writeConsumer("com.simibubi.create.compat.trainmap", "First")
        writeConsumer("org.other.example.consumer", "Second")
        writeConfig("create", "com.simibubi.create.foundation.mixin")
        writeConfig("other", "org.other.mixin")
        writeConfig("otherClient", "org.other.client.mixin")

        assertRefusedWithoutWrites("for package org.other.example.consumer")
    }

    @Test
    fun `unowned consumer refuses the batch even if another has unique stronger ownership`() {
        writeConsumer("com.simibubi.create.compat.trainmap", "First")
        writeConsumer("org.unrelated.consumer", "Second")
        writeConfig("create", "com.simibubi.create.foundation.mixin")
        writeConfig("other", "org.other.mixin")

        assertRefusedWithoutWrites("for package org.unrelated.consumer")
    }

    private fun assertRefusedWithoutWrites(message: String) {
        val original = snapshot()
        for (dryRun in listOf(true, false)) {
            val error = assertFailsWith<IllegalArgumentException> {
                ThirdPartyReflectionMixinMigration().migrate(directory, dryRun)
            }
            assertTrue(error.message.orEmpty().contains(message), error.message)
            assertEquals(original, snapshot())
        }
    }

    private fun writeConsumer(packageName: String, className: String) {
        val source = directory.resolve("src/main/java/${packageName.replace('.', '/')}/$className.java")
        source.parent.createDirectories()
        source.writeText(
            """
            package $packageName;
            import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
            import dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;
            public class $className {
                void render(LargeMapScreen screen) {
                    RegionMapPanel panel = ObfuscationReflectionHelper.getPrivateValue(
                        LargeMapScreen.class, screen, "regionPanel");
                }
            }
            """.trimIndent() + "\n"
        )
    }

    private fun writeConfig(name: String, packageName: String) {
        val file = directory.resolve("src/main/resources/$name.mixins.json")
        file.parent.createDirectories()
        file.writeText(config(packageName))
    }

    private fun snapshot(): Map<String, List<Byte>> = Files.walk(directory).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            directory.relativize(path).toString() to Files.readAllBytes(path).toList()
        }
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
