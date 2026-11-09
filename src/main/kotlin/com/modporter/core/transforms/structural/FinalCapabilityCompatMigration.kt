package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Re-runs the source-owned legacy capability normalization after BuildSystem has relocated
 * NeoForge's stale Capability type to the per-mod generated compatibility package.
 */
internal object FinalCapabilityCompatMigration {
    data class Result(
        val changes: List<Change>,
        val gates: List<String>
    ) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    private data class Ownership(
        val compatPackage: String,
        val roles: Set<String>
    )

    private val compatPackagePattern =
        Regex("""com\.modporter\.generated\.[a-z0-9_]+\.compat""")
    private val ownedRoles = setOf(
        "LegacyCapabilityProvider",
        "LegacyCapabilities",
        "LegacyBlockEntityCapabilityQueries",
        "LegacyComputerCraftCapabilities",
        "LegacyComputerCraftPeripheralCapabilities"
    )
    private val peripheralRoles = setOf(
        "LegacyComputerCraftCapabilities",
        "LegacyComputerCraftPeripheralCapabilities"
    )

    fun migrate(projectRoot: Path, dryRun: Boolean): Result {
        val leaf = GradleModLayout.modModule(projectRoot).toAbsolutePath().normalize()
        val sourceRoot = leaf.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return Result.EMPTY

        val ownerships = findOwnedFamilies(sourceRoot)
        if (ownerships.isEmpty()) return Result.EMPTY
        val compatPackages = ownerships.map(Ownership::compatPackage).toSet()
        if (compatPackages.size != 1) {
            return Result(
                emptyList(),
                listOf(
                    "Final capability compatibility migration found multiple generated packages " +
                        "for owned capability roles in $leaf: ${compatPackages.sorted()}"
                )
            )
        }
        val ownership = Ownership(
            compatPackage = compatPackages.single(),
            roles = ownerships.flatMap(Ownership::roles).toSet()
        )

        val changes = mutableListOf<Change>()
        val gates = mutableListOf<String>()
        val query = LegacyBlockEntityCapabilityQueries.migrate(
            leaf,
            { ownership.compatPackage },
            dryRun
        )
        changes += query.changes
        gates += query.gates
        if (query.gates.isNotEmpty()) return Result(changes, gates.distinct())

        if (ownership.roles.none { it in peripheralRoles }) {
            return Result(changes, gates.distinct())
        }

        val detector = StructuralRefactorPass()
        val modId = with(detector) {
            detectModId(leaf) ?: projectMetadataModId(leaf)
        }
        if (modId == null) {
            gates += "Final ComputerCraft capability migration cannot derive the mod id for $leaf"
            return Result(changes, gates.distinct())
        }
        val expectedCompat = runCatching {
            with(detector) {
                detectRequiredGeneratedCompatPackage(
                    leaf,
                    "final ComputerCraft peripheral capability migration"
                )
            }
        }.getOrElse { failure ->
            gates += failure.message ?: "Cannot derive the final generated capability package for $leaf"
            return Result(changes, gates.distinct())
        }
        if (expectedCompat != ownership.compatPackage) {
            gates += "Final ComputerCraft capability migration found owned package " +
                "${ownership.compatPackage}, but mod identity requires $expectedCompat"
            return Result(changes, gates.distinct())
        }

        val peripheral = LegacyComputerCraftPeripheralCapabilities.migrate(
            leaf,
            modId,
            ownership.compatPackage,
            dryRun
        )
        changes += peripheral.changes
        gates += peripheral.gates
        return Result(changes, gates.distinct())
    }

    private fun findOwnedFamilies(sourceRoot: Path): List<Ownership> =
        Files.walk(sourceRoot).use { paths ->
            paths.asSequence()
                .filter { it.extension == "java" && it.nameWithoutExtension in ownedRoles }
                .mapNotNull { file ->
                    val source = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                    val masked = JavaSourceText.maskCommentsAndLiterals(source)
                    val packageName = JavaSourceText.packageName(masked) ?: return@mapNotNull null
                    if (!compatPackagePattern.matches(packageName)) return@mapNotNull null
                    val role = file.nameWithoutExtension
                    val relative = sourceRoot.relativize(file).toString().replace('\\', '/')
                    if (relative != "${packageName.replace('.', '/')}/$role.java") return@mapNotNull null
                    val roleOwned = when (role) {
                        "LegacyCapabilityProvider" ->
                            masked.contains("public interface LegacyCapabilityProvider") &&
                                Regex(
                                    """<T>\s+LazyOptional<T>\s+getCapability\s*\(\s*Capability<T>\s+cap\s*,\s*Direction\s+side\s*\)\s*;"""
                                ).containsMatchIn(masked)
                        "LegacyCapabilities" ->
                            masked.contains("public final class LegacyCapabilities") &&
                                listOf("ITEM_HANDLER", "FLUID_HANDLER", "ENERGY").all(masked::contains)
                        "LegacyBlockEntityCapabilityQueries" ->
                            masked.contains("public final class LegacyBlockEntityCapabilityQueries") &&
                                masked.contains("nativeCapability(") &&
                                masked.contains("LegacyCapabilityProvider provider")
                        "LegacyComputerCraftCapabilities" ->
                            masked.contains("public final class LegacyComputerCraftCapabilities") &&
                                masked.contains("Capability<dan200.computercraft.api.peripheral.IPeripheral> PERIPHERAL")
                        "LegacyComputerCraftPeripheralCapabilities" ->
                            masked.contains("public final class LegacyComputerCraftPeripheralCapabilities") &&
                                masked.contains("PeripheralCapability.get()") &&
                                masked.contains("registerBlockEntity(")
                        else -> false
                    }
                    if (!roleOwned) return@mapNotNull null
                    Ownership(packageName, setOf(role))
                }
                .toList()
        }
}
