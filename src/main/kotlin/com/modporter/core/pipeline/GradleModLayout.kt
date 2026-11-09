package com.modporter.core.pipeline

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Where the Forge mod lives in a Gradle build.
 *
 * A single-project mod keeps its build and sources at the root. A multi-project build (a
 * platform-independent core library beside the loader module, as EngineHub's WorldEdit is laid
 * out) has exactly one subproject whose build applies ForgeGradle or depends on Forge (or, once
 * converted, applies NeoForge's ModDev or NeoGradle, so the same module is found on both sides of
 * the migration): that module is the mod, so the source and resource migrations work on it, while the root keeps the settings,
 * `buildSrc` and wrapper the build migration updates. Anything ambiguous (no such subproject, or
 * several) keeps the root, the single-project behaviour.
 */
object GradleModLayout {
    private val buildFiles = listOf("build.gradle", "build.gradle.kts")
    private val loaderBuild = Regex("""net\.minecraftforge\.gradle|net\.minecraftforge:forge:|net\.neoforged\.moddev|net\.neoforged\.gradle|net\.neoforged:neoforge:""")
    private val skippedDirs = setOf("build", "buildSrc", "gradle", ".gradle", ".git", "src", "run", "out")

    /** The directory of the Forge mod module of the build rooted at [projectDir]. */
    fun modModule(projectDir: Path): Path {
        if (projectDir.resolve("src/main/java").isDirectory() || isLoaderBuild(projectDir)) return projectDir
        val candidates = subprojectDirs(projectDir).filter { isLoaderBuild(it) && it.resolve("src/main/java").isDirectory() }
        return candidates.singleOrNull() ?: projectDir
    }

    /** Whether the mod is a subproject of a multi-project build rather than the build root itself. */
    fun isMultiProject(projectDir: Path): Boolean = modModule(projectDir) != projectDir

    private fun isLoaderBuild(dir: Path): Boolean = buildFiles.map { dir.resolve(it) }.filter { it.exists() }.any { file ->
        val active = file.readText().lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        loaderBuild.containsMatchIn(active)
    }

    /** Directories up to two levels below the root that carry their own build script. */
    private fun subprojectDirs(projectDir: Path): List<Path> {
        fun children(dir: Path): List<Path> = runCatching {
            Files.list(dir).use { entries -> entries.filter { it.isDirectory() && it.name !in skippedDirs && !it.name.startsWith(".") }.toList() }
        }.getOrDefault(emptyList())
        val firstLevel = children(projectDir)
        return (firstLevel + firstLevel.flatMap { children(it) })
            .filter { dir -> buildFiles.any { dir.resolve(it).exists() } }
            .sorted()
    }
}
