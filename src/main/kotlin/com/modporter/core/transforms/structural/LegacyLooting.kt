package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.TypeDeclaration
import com.modporter.core.pipeline.Change
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Rejects Forge's removed LootingLevelEvent policy until its two consumers can be preserved.
 *
 * The target Mob path applies equipment-drop enchantment effects before its random comparison.
 * Subtracting a legacy looting level from that random value can double-count attacker Looting and
 * can alter unrelated target equipment effects. LivingDropsEvent is later and combines unrelated
 * loot-table, custom and equipment drops, so it is not a lossless replacement either.
 */
internal object LegacyLooting {
    private val removedTypes = mapOf(
        "LootingLevelEvent" to setOf(
            "net.minecraftforge.event.entity.living.LootingLevelEvent",
            "net.neoforged.neoforge.event.entity.living.LootingLevelEvent"
        ),
        "LootingEnchantFunction" to setOf(
            "net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction"
        )
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
        val names = removedTypes.keys.joinToString("|") { Regex.escape(it) }
        val token = Regex("""(?<![\w$])(?:$names)(?![\w$])""")
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val units = pass.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.toString().endsWith(".java") }
                .toList().mapNotNull { file ->
                    val source = file.readText()
                    val code = pass.maskJavaCommentsAndLiterals(source)
                    if (!token.containsMatchIn(code)) return@mapNotNull null
                    val unit = parser.parse(source).result.orElse(null)
                        ?: error("Cannot verify legacy looting ownership in $file; source left unchanged")
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
            removedTypes.any { (simpleName, canonicalNames) ->
                resolvesToRemovedType(source, simpleName, canonicalNames, projectTypes)
            }
        }.map { it.file }
        if (legacyFiles.isEmpty()) return emptyList()

        error(
            "Lossless LootingLevelEvent/LootingEnchantFunction migration is unavailable for " +
                "${legacyFiles.joinToString()}: target equipment enchantment processing prevents " +
                "an exact generic looting-level override; source and mixin configuration left unchanged"
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

        // Text replacement can remove or rename an obsolete import before this structural gate.
        // An unresolved removed simple name still blocks the conversion, but never authorizes one.
        return Regex("""(?<![\w$])${Regex.escape(simpleName)}(?![\w$])""").containsMatchIn(source.code)
    }
}
