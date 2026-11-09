package com.modporter.core.transforms.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicTreesDependencyPreparationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `real Dynamic Trees source graph produces a receipt-bound replacement`() {
        val stage = ownedStage()
        val source = BuildResolvedSourceDependency(
            component = SOURCE_COMPONENT,
            configurations = setOf("compileClasspath"),
            artifactSha256 = SOURCE_ARTIFACT_SHA256
        )
        val unknownSibling = BuildResolvedSourceDependency(
            component = "com.ferreusveritas.dynamictrees:DynamicTreesCompat-1.20.1:1.0.0",
            configurations = setOf("compileClasspath"),
            artifactSha256 = "0".repeat(64)
        )

        val receipt = BuildSystemPass().prepareDependencies(
            stage,
            listOf(source, unknownSibling)
        )

        assertTrue(receipt.result.errors.isEmpty(), receipt.result.errors.joinToString("\n"))
        val decision = receipt.decisions.single()
        assertEquals(source, decision.source)
        assertEquals(SOURCE_SELECTOR, decision.sourceSelector)
        assertEquals(BuildDependencyDecisionKind.MAPPED, decision.kind)
        assertEquals(
            listOf(
                "com.ferreusveritas.dynamictrees.",
                "com.dtteam.dynamictrees."
            ),
            decision.packagePrefixes
        )
        val target = decision.targetCoordinates.single()
        assertEquals("compileOnly", target.configuration)
        assertEquals(TARGET_COMPONENT, target.selector)
        assertTrue(target.transitive)
        assertFalse(receipt.decisions.any { it.source == unknownSibling })

        val resourceBytes = javaClass.getResourceAsStream(
            "/mappings/forge2neo/neoforge-deps.json"
        )!!.use { it.readBytes() }
        assertEquals(buildReceiptSha256(resourceBytes), receipt.mappingResourceSha256)
        val selectedEntry = Json.parseToJsonElement(resourceBytes.toString(Charsets.UTF_8))
            .jsonObject
            .getValue("dependencies")
            .jsonArray
            .single {
                it.jsonObject.getValue("forgePrefix").jsonPrimitive.content == SOURCE_SELECTOR
            }
        assertEquals(buildReceiptSha256(selectedEntry.toString()), decision.catalogEntrySha256)

        val rewrittenBuild = stage.resolve("build.gradle").readText()
        assertTrue(rewrittenBuild.contains("compileOnly \"$TARGET_COMPONENT\""), rewrittenBuild)
        assertFalse(rewrittenBuild.contains(SOURCE_COMPONENT), rewrittenBuild)
        assertTrue(receipt.changedBuildFiles.any { it.path == "build.gradle" })
        assertTrue(receipt.changedSourceFiles.isEmpty())
    }

    @Test
    fun `measured Dynamic Trees package roots resolve while sibling packages and coordinates stay unknown`() {
        val resolver = DependencyResolver(offlineMode = true)

        listOf(
            "com.ferreusveritas.dynamictrees.block.DynamicSaplingBlock",
            "com.ferreusveritas.dynamictrees.item.Seed",
            "com.dtteam.dynamictrees.mixin.MixinBushBlock"
        ).forEach { binaryName ->
            val resolution = resolver.resolveReferencedClass(binaryName)
            assertTrue(resolution is DepResolution.Resolved, "$binaryName -> $resolution")
            assertEquals(
                TARGET_COMPONENT,
                (resolution as DepResolution.Resolved).coords.single().coord
            )
        }

        listOf(
            "com.ferreusveritas.dynamictreesaddon.block.DynamicSaplingBlock",
            "com.dtteam.dynamictreesaddon.mixin.MixinBushBlock"
        ).forEach { binaryName ->
            assertEquals(DepResolution.Unknown, resolver.resolveReferencedClass(binaryName))
        }
        assertEquals(
            DepResolution.Unknown,
            resolver.resolve(
                "compileOnly(\"com.ferreusveritas.dynamictrees:" +
                    "DynamicTreesCompat-1.20.1:1.0.0\")"
            )
        )
        assertEquals(
            DepResolution.Unknown,
            resolver.resolve(
                "compileOnly(\"com.ferreusveritas.dynamictrees.addon:" +
                    "DynamicTrees-1.20.1:1.0.0\")"
            )
        )
    }

    private fun ownedStage(): Path {
        val stage = temporary.resolve(".modporter/tmp/dynamic-trees/target").createDirectories()
        stage.resolve("build.gradle").writeText(
            """
            repositories {
                maven { url "https://maven.minecraftforge.net/" }
            }

            dependencies {
                modCompileOnly("$SOURCE_COMPONENT")
            }
            """.trimIndent()
        )
        return stage
    }

    private companion object {
        const val SOURCE_SELECTOR = "com.ferreusveritas.dynamictrees:DynamicTrees"
        const val SOURCE_COMPONENT =
            "com.ferreusveritas.dynamictrees:DynamicTrees-1.20.1:1.3.0-BETA13"
        const val SOURCE_ARTIFACT_SHA256 =
            "76f00472f12983d76ec6c88e319e6eb837fb2f49f64d92da1b296c3f01f3ca46"
        const val TARGET_COMPONENT =
            "com.dtteam.dynamictrees:dynamictrees-neoforge-1.21.1:1.7.2"
    }
}
