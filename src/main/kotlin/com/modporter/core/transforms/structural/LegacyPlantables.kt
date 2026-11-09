package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.TypeDeclaration
import com.modporter.core.pipeline.Change
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Rejects the removed block-oriented IPlantable contract until a lossless target mechanism exists.
 *
 * NeoForge's SpecialPlantable is an item placement transaction. It cannot stand in for arbitrary
 * IPlantable#getPlant results, and an ItemStack parameter is not proof that the stack represents
 * the block being queried. The target BlockState sustain hook also cannot reproduce custom legacy
 * IPlantable implementations without their complete source and call graph. Leaving these sources
 * unchanged is safer than silently narrowing them to vanilla BushBlock behavior.
 */
internal object LegacyPlantables {
    private val legacyTypes = setOf(
        "net.minecraftforge.common.IPlantable",
        "net.neoforged.neoforge.common.IPlantable"
    )

    private data class SourceUnit(
        val file: Path,
        val code: String,
        val unit: CompilationUnit
    )

    @Suppress("UNUSED_PARAMETER")
    fun migrate(pass: StructuralRefactorPass, project: Path, dryRun: Boolean): List<Change> {
        val root = project.resolve("src/main/java")
        if (!java.nio.file.Files.isDirectory(root)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val units = pass.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.toString().endsWith(".java") }
                .toList().mapNotNull { file ->
                    val source = file.readText()
                    val code = pass.maskJavaCommentsAndLiterals(source)
                    if (!Regex("""(?<![\w$])IPlantable(?![\w$])""").containsMatchIn(code)) {
                        return@mapNotNull null
                    }
                    val unit = parser.parse(source).result.orElse(null)
                        ?: error("Cannot verify IPlantable ownership in $file; source left unchanged")
                    SourceUnit(file, code, unit)
                }
        }
        if (units.isEmpty()) return emptyList()

        val projectTypes = units.flatMap { source ->
            val packageName = source.unit.packageDeclaration.map { it.nameAsString }.orElse("")
            source.unit.types.map { type ->
                if (packageName.isBlank()) type.nameAsString else "$packageName.${type.nameAsString}"
            }
        }.toSet()
        val legacyFiles = units.filter { source ->
            resolvesToRemovedType(source, "IPlantable", legacyTypes, projectTypes)
        }.map { it.file }
        if (legacyFiles.isEmpty()) return emptyList()

        error(
            "Lossless IPlantable migration is unavailable for ${legacyFiles.joinToString()}: " +
                "SpecialPlantable is item-based and cannot preserve arbitrary getPlant, " +
                "canSustainPlant, or custom implementation behavior; source left unchanged"
        )
    }

    private fun resolvesToRemovedType(
        source: SourceUnit,
        simpleName: String,
        removedTypes: Set<String>,
        projectTypes: Set<String>
    ): Boolean {
        if (removedTypes.any { source.code.contains(it) }) return true
        val imports = source.unit.imports.filter { !it.isStatic }
        if (imports.any { !it.isAsterisk && it.nameAsString in removedTypes }) return true
        if (imports.any { it.isAsterisk && removedTypes.any { removed -> removed.substringBeforeLast('.') == it.nameAsString } }) {
            return true
        }
        if (imports.any { !it.isAsterisk && it.name.identifier == simpleName }) return false
        if (source.unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simpleName }) return false
        val packageName = source.unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val samePackageType = if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
        if (samePackageType in projectTypes) return false

        // Earlier text normalization may already have removed the now-invalid Forge import. An
        // unresolved legacy simple name is evidence to stop, never evidence for a guessed rewrite.
        return Regex("""(?<![\w$])${Regex.escape(simpleName)}(?![\w$])""").containsMatchIn(source.code)
    }
}
