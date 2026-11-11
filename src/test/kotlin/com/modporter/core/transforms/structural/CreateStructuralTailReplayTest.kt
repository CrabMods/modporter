package com.modporter.core.transforms.structural

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Local projection of selected late rules; full pipeline and runtime acceptance remain separate. */
class CreateStructuralTailReplayTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `captured normalized project admits late Create gates and closes item data`() {
        val configured = System.getenv("MODPORTER_CREATE_STRUCTURAL_TAIL_REPLAY")
        assumeTrue(!configured.isNullOrBlank(), "Requires a local normalized Create project")
        val project = Path.of(checkNotNull(configured)).toRealPath()
        val captured = project.resolve("src/main")
        assertTrue(Files.isDirectory(captured, LinkOption.NOFOLLOW_LINKS))
        val before = hashes(captured)
        try {
            Files.walk(captured).use { paths ->
                paths.forEach { source ->
                    check(!Files.isSymbolicLink(source)) { "Linked replay input: $source" }
                    val target = directory.resolve("src/main").resolve(captured.relativize(source))
                    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target)
                    } else {
                        Files.copy(source, target)
                    }
                }
            }
            for (name in listOf("build.gradle", "build.gradle.kts", "gradle.properties")) {
                val source = project.resolve(name)
                if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    check(!Files.isSymbolicLink(source)) { "Linked replay input: $source" }
                    Files.copy(source, directory.resolve(name))
                }
            }
            val pass = StructuralRefactorPass()
            val javaFiles = Files.walk(directory.resolve("src/main/java")).use { paths ->
                paths.filter {
                    it.toString().endsWith(".java") &&
                        !it.toString().replace('\\', '/').contains("/com/modporter/generated/")
                }.toList()
            }
            val inheritance = pass.collectJavaInheritanceIndex(javaFiles)
            val providerMethods = pass.collectItemStackNbtMethodsNeedingHolderLookup(
                javaFiles, inheritance
            )
            for (file in javaFiles) {
                var source = Files.readString(file)
                repeat(2) {
                    source = pass.migrateProjectItemStackNbtMethods(source, providerMethods, inheritance)
                }
                if (source != Files.readString(file)) Files.writeString(file, source)
            }
            var changes = 0
            fun checked(name: String, count: Int, gates: List<String>) {
                assertTrue(gates.isEmpty(), "$name: ${gates.joinToString()}")
                changes += count
            }
            CreateCopperStairFactoryMigration.migrate(pass, directory, false).also {
                checked("copper stairs", it.changes.size, it.gates)
            }
            CreateRegistrateDatagenLifecycleMigration.migrate(directory, false).also {
                checked("Registrate datagen", it.changes.size, it.gates)
            }
            CreateCommonMetalTagNamespaceMigration.migrate(directory, false).also {
                checked("common metal tags", it.changes.size, it.gates)
            }
            CreateLegacyTagConventionMigration.migrate(directory, false).also {
                checked("legacy tags", it.changes.size, it.gates)
            }
            CreateEnchantmentDatagenBootstrapMigration.migrate(directory, false).also {
                checked("enchantment datagen", it.changes.size, it.gates)
            }
            CreateFlapDisplaySerializationMigration.migrate(directory, false).also {
                checked("flap display", it.changes.size, it.gates)
            }
            // This fixture was captured after Build System and Resource Migration. Those passes
            // have already removed CopperRegistries.inject and changed mixin metadata, so the
            // copper/lever structural admission checks require a fresh pipeline's earlier phase.
            assertTrue(changes > 0, "Captured project must exercise pending late migrations")
            CreateItemStackDataFamilyMigration.migrateHandoffs(directory, false).also {
                checked("item-data handoffs", it.changes.size, it.gates)
            }
            CreateZapperDataMigration.migrate(directory, false).also {
                checked("Zapper data", it.changes.size, it.gates)
            }
            CreateAttributeFilterDataMigration.migrate(directory, false).also {
                checked("attribute filter data", it.changes.size, it.gates)
            }
            CreateEffectComponentSurfaces.migratePotionData(pass, directory, false)
            ExactLegacyCustomDataMigration().migrate(directory, false)

            val javaRoot = directory.resolve("src/main/java")
            for (relative in listOf(
                "content/equipment/clipboard/ClipboardScreen.java",
                "content/equipment/zapper/ZapperItem.java",
                "content/equipment/zapper/terrainzapper/WorldshaperItem.java",
                "content/equipment/zapper/terrainzapper/WorldshaperRenderHandler.java",
                "content/equipment/zapper/terrainzapper/WorldshaperScreen.java",
                "content/contraptions/mounted/MinecartContraptionItem.java",
                "content/logistics/filter/AttributeFilterItem.java",
                "content/logistics/filter/AttributeFilterMenu.java",
                "content/kinetics/belt/item/BeltConnectorItem.java",
                "content/redstone/displayLink/ClickToLinkBlockItem.java",
                "foundation/fluid/FluidIngredient.java"
            )) {
                val source = Files.readString(javaRoot.resolve("com/simibubi/create/$relative"))
                assertTrue(
                    !Regex("\\.getOrCreateTag\\s*\\(").containsMatchIn(source),
                    "Unclosed legacy item-data call in $relative"
                )
            }
        } finally {
            assertEquals(before, hashes(captured), "Captured benchmark evidence was modified")
        }
    }

    private fun hashes(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .toList().associate { path ->
                check(!Files.isSymbolicLink(path)) { "Linked replay input: $path" }
                root.relativize(path).toString() to HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                )
            }
    }
}
