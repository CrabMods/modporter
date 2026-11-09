package com.modporter.core.transforms.structural

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

/**
 * Immutable artifact records produced by a verified legacy-migration dependency run.
 *
 * The producer owns dependency discovery and durable copying. Consumers only accept the sealed
 * paths carried here; they never search Gradle caches, build directories, or temporary roots.
 */
internal sealed interface LegacyMigrationArtifactInventory {
    val platformIdentity: PlatformIdentity
    val platformArtifacts: PlatformArtifacts
    val closureArtifacts: List<Artifact>

    class Source(
        override val platformIdentity: PlatformIdentity,
        override val platformArtifacts: PlatformArtifacts,
        closureArtifacts: List<Artifact>,
        val classRuntimeFeature: Int,
        val compileClasspathConfiguration: String
    ) : LegacyMigrationArtifactInventory {
        override val closureArtifacts: List<Artifact> =
            Collections.unmodifiableList(closureArtifacts.toList())

        init {
            require(classRuntimeFeature in setOf(17, 21)) {
                "Unsupported sealed source class-runtime feature: $classRuntimeFeature"
            }
            require(
                compileClasspathConfiguration.substringAfterLast('|') == "compileClasspath" &&
                    '|' in compileClasspathConfiguration
            ) {
                "Sealed source compile classpath is not an exact project-qualified identity: " +
                    compileClasspathConfiguration
            }
            val active = this.closureArtifacts.filter {
                compileClasspathConfiguration in it.resolutionConfigurations
            }
            require(active.isNotEmpty()) {
                "Sealed source graph has no artifacts on $compileClasspathConfiguration"
            }
            val orders = active.map {
                it.configurationOrder.getValue(compileClasspathConfiguration)
            }
            require(
                orders.distinct().size == orders.size &&
                    orders.sorted() == (0 until orders.size).toList()
            ) {
                "Sealed source compile classpath order is incomplete or ambiguous for " +
                    compileClasspathConfiguration
            }
        }
    }

    class Target(
        override val platformIdentity: PlatformIdentity,
        override val platformArtifacts: PlatformArtifacts,
        closureArtifacts: List<Artifact>
    ) : LegacyMigrationArtifactInventory {
        override val closureArtifacts: List<Artifact> =
            Collections.unmodifiableList(closureArtifacts.toList())
    }

    data class PlatformIdentity(
        val family: String,
        val minecraftVersion: String,
        val loaderVersion: String,
        val resolvedLoaderCoordinate: String
    ) {
        init {
            require(family.isNotBlank())
            require(minecraftVersion.isNotBlank())
            require(loaderVersion.isNotBlank())
            require(resolvedLoaderCoordinate.isNotBlank())
        }
    }

    data class PlatformArtifacts(
        val named: Artifact,
        val merged: Artifact,
        val sources: Artifact
    )

    class Artifact(
        component: String,
        val componentKind: ComponentKind,
        displayName: String,
        configurations: Set<String>,
        file: Path,
        sha256: String,
        resolutionConfigurations: Set<String> = configurations,
        configurationOrder: Map<String, Int> =
            resolutionConfigurations.sorted().mapIndexed { index, configuration ->
                configuration to index
            }.toMap()
    ) {
        val component: String = component
        val displayName: String = displayName
        val configurations: Set<String> =
            Collections.unmodifiableSet(configurations.toSet())
        val resolutionConfigurations: Set<String> =
            Collections.unmodifiableSet(resolutionConfigurations.toSet())
        val configurationOrder: Map<String, Int> =
            Collections.unmodifiableMap(configurationOrder.toMap())
        val file: Path = file.toRealPath()
        val sha256: String = sha256

        init {
            require(this.component.isNotBlank())
            if (componentKind == ComponentKind.MODULE) {
                val parts = this.component.split(':')
                require(parts.size in 3..4 && parts.all(String::isNotBlank)) {
                    "Resolved module component must be group:artifact:version[:classifier]: " +
                        this.component
                }
            }
            require(this.displayName.isNotBlank())
            require(this.configurations.none(String::isBlank))
            require(this.resolutionConfigurations.none(String::isBlank))
            require(this.configurationOrder.keys == this.resolutionConfigurations) {
                "Inventory artifact configuration order does not cover its exact resolved " +
                    "configurations: ${this.component}"
            }
            require(this.configurationOrder.values.all { it >= 0 }) {
                "Inventory artifact configuration order is negative: ${this.component}"
            }
            require(this.sha256.matches(SHA_256_PATTERN)) {
                "Inventory artifact hash is not SHA-256: ${this.sha256}"
            }
            verifyCurrentBytes("inventory construction")
        }

        fun verifyCurrentBytes(phase: String) {
            require(Files.isRegularFile(file)) {
                "Inventory artifact is not a regular file during $phase: $file"
            }
            require(fileSha256(file) == sha256) {
                "Inventory artifact bytes changed after sealing during $phase: $file"
            }
        }

        fun sameBytesAs(other: Artifact): Boolean =
            file == other.file && sha256 == other.sha256
    }

    enum class ComponentKind {
        MODULE,
        PROJECT,
        GENERATED_PLATFORM,
        PLATFORM_AUXILIARY
    }

    companion object {
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * A paired source/target inventory owned by one normalized project root and one explicit leaf.
 */
internal class LegacyMigrationArtifactPair(
    ownerProject: Path,
    leafProject: Path,
    sealedRunRoot: Path,
    val source: LegacyMigrationArtifactInventory.Source,
    val target: LegacyMigrationArtifactInventory.Target
) {
    val ownerProject: Path = ownerProject.toAbsolutePath().normalize()
    val leafProject: Path = leafProject.toAbsolutePath().normalize()
    val sealedRunRoot: Path = sealedRunRoot.toRealPath()

    init {
        require(this.leafProject.startsWith(this.ownerProject)) {
            "Legacy migration leaf project is outside its owning project: ${this.leafProject}"
        }
        require(Files.isDirectory(this.sealedRunRoot)) {
            "Legacy migration sealed run root is not a directory: ${this.sealedRunRoot}"
        }
        allArtifacts().forEach { artifact ->
            require(artifact.file.startsWith(this.sealedRunRoot)) {
                "Legacy migration artifact is outside the sealed run: ${artifact.file}"
            }
        }
        verifyCurrentBytes("paired inventory construction")
    }

    fun acceptsProject(project: Path): Boolean {
        val normalized = project.toAbsolutePath().normalize()
        return normalized == ownerProject || normalized == leafProject
    }

    fun verifyCurrentBytes(phase: String) {
        allArtifacts().forEach { it.verifyCurrentBytes(phase) }
    }

    private fun allArtifacts(): List<LegacyMigrationArtifactInventory.Artifact> =
        listOf(
            source.platformArtifacts.named,
            source.platformArtifacts.merged,
            source.platformArtifacts.sources
        ) + source.closureArtifacts + listOf(
            target.platformArtifacts.named,
            target.platformArtifacts.merged,
            target.platformArtifacts.sources
        ) + target.closureArtifacts
}

/**
 * Pipeline-scoped handoff keyed only by the exact owner root and explicit leaf-module alias.
 */
internal object LegacyMigrationArtifactInventoryHandoff {
    private val inventories = ThreadLocal.withInitial {
        mutableMapOf<Path, LegacyMigrationArtifactPair>()
    }

    /** Presence selects the owning migration; every consumer still revalidates with [require]. */
    fun isPublished(project: Path): Boolean =
        inventories.get().containsKey(project.toAbsolutePath().normalize())

    fun publish(inventory: LegacyMigrationArtifactPair) {
        inventory.verifyCurrentBytes("inventory publication")
        val aliases = setOf(inventory.ownerProject, inventory.leafProject)
        val pending = inventories.get()
        val existing = aliases.mapNotNull(pending::get).toSet()
        require(existing.all { it.ownerProject == inventory.ownerProject }) {
            "Legacy migration artifact inventory alias collides with another owning project"
        }
        existing.forEach { removeAliases(pending, it) }
        aliases.forEach { pending[it] = inventory }
    }

    fun require(project: Path): LegacyMigrationArtifactPair {
        val normalized = project.toAbsolutePath().normalize()
        val pending = inventories.get()
        val inventory = pending[normalized]
            ?: error("No sealed legacy migration artifact inventory was published for $normalized")
        inventory.verifyCurrentBytes("inventory lookup")
        return inventory
    }

    fun release(ownerProject: Path): Boolean {
        val normalized = ownerProject.toAbsolutePath().normalize()
        val pending = inventories.get()
        val inventory = pending[normalized] ?: return false
        require(normalized == inventory.ownerProject) {
            "Legacy migration artifact inventory must be released by its owning project"
        }
        removeAliases(pending, inventory)
        if (pending.isEmpty()) {
            inventories.remove()
        }
        return true
    }

    private fun removeAliases(
        pending: MutableMap<Path, LegacyMigrationArtifactPair>,
        inventory: LegacyMigrationArtifactPair
    ) {
        setOf(inventory.ownerProject, inventory.leafProject).forEach { alias ->
            if (pending[alias] === inventory) {
                pending.remove(alias)
            }
        }
    }
}

private fun fileSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
