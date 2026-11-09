package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyITeleporterDimensionTransitions
import com.modporter.core.transforms.structural.migrateLegacyPortalFamilyTransaction
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActualPortalFamilyAdmissionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `the source snapshot closes static forwarders direct callbacks and the reflected provider in one transaction`() {
        val prefix = "/fixtures/legacy-portal/create-source/"
        val manifest = Json.parseToJsonElement(javaClass.getResource(prefix + "source-manifest.json")!!.readText()).jsonArray
        for (entry in manifest) {
            val row = entry.jsonObject
            val bytes = javaClass.getResource(prefix + row.getValue("fixture").jsonPrimitive.content)!!.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(row.getValue("sha256").jsonPrimitive.content, hash)
            directory.resolve(row.getValue("sourceRelativePath").jsonPrimitive.content).also { file ->
                file.parent.createDirectories()
                file.writeBytes(bytes)
            }
        }
        directory.resolve("src/main/java/example/Entry.java").also {
            it.parent.createDirectories()
            it.writeText("""
                package example;
                import net.neoforged.fml.common.Mod;
                @Mod("create") class Entry {}
            """.trimIndent())
        }
        val pass = StructuralRefactorPass()
        val changes = pass.migrateLegacyPortalFamilyTransaction(directory, false)
        assertTrue(changes.isNotEmpty(), changes.toString())
        val provider = directory.resolve("src/main/java/com/simibubi/create/api/contraption/train/PortalTrackProvider.java").readText()
        val tracks = directory.resolve("src/main/java/com/simibubi/create/content/trains/track/AllPortalTracks.java").readText()
        val betterEnd = directory.resolve("src/main/java/com/simibubi/create/compat/betterend/BetterEndPortalCompat.java").readText()
        assertFalse(provider.contains("import net.minecraftforge.common.util.ITeleporter;"), provider)
        assertFalse(tracks.contains("import net.minecraftforge.common.util.ITeleporter;"), tracks)
        assertTrue(provider.contains("Function<ServerLevel, ?>"), provider)
        assertTrue(tracks.contains("LegacyPortalProbe.Teleporter"), tracks)
        assertTrue(tracks.contains("LegacyPortalProbe::vanilla"), tracks)
        assertTrue(tracks.contains("LegacyPortalProbe.reflective("), tracks)
        assertTrue(tracks.contains("BiFunction<ServerLevel, SuperGlueEntity, ?>"), tracks)
        assertTrue(
            tracks.contains(
                "PortalInfo portalInfo = LegacyPortalProbe.portalInfo(portalInfoProvider.apply(otherLevel, probe));"
            ),
            tracks
        )
        assertTrue(
            betterEnd.contains("import com.modporter.generated.create.compat.PortalInfo;"),
            betterEnd
        )
        assertTrue(
            betterEnd.contains("public static PortalInfo getBetterEndPortalInfo(ServerLevel targetLevel, Entity entity)"),
            betterEnd
        )
        assertTrue(
            betterEnd.contains(
                """LegacyPortalProbe.findVirtual(privateLookup, travelerStateClass, "findDimensionEntryPoint", ServerLevel.class)"""
            ),
            betterEnd
        )
        assertFalse(betterEnd.contains("PortalInfo.class"), betterEnd)
        assertFalse(betterEnd.contains("net.minecraft.world.level.portal.PortalInfo"), betterEnd)
        assertFalse(betterEnd.contains("DimensionTransition"), betterEnd)
        assertEquals(betterEnd, pass.migrateLegacyITeleporterDimensionTransitions(betterEnd))
        val helper = directory.resolve("src/main/java/com/modporter/generated/create/compat/LegacyPortalProbe.java")
        assertEquals(javaClass.getResource("/compat/LegacyPortalProbe.java.template")!!.readText()
            .replace("@PACKAGE@", "com.modporter.generated.create.compat"), helper.readText())
        assertTrue(pass.migrateLegacyPortalFamilyTransaction(directory, false).isEmpty())
    }
}
