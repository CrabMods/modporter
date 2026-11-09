package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.type.TypeParameter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val LEGACY_PORTAL_INFO = "net.minecraft.world.level.portal.PortalInfo"
private const val PORTAL_INFO_SIMPLE_NAME = "PortalInfo"
private const val PORTAL_INFO_RULE = "struct-legacy-portal-info-value"
private val LEGACY_PORTAL_INFO_IMPORT = Regex(
    """(?m)(^[ \t]*import[ \t]+)${Regex.escape(LEGACY_PORTAL_INFO)}(?=[ \t]*;)"""
)
private val GENERATED_PORTAL_INFO_IMPORT = Regex(
    """^com\.modporter\.generated\.[a-z0-9_]+\.compat\.PortalInfo$"""
)

/**
 * Retains the removed 1.20.1 PortalInfo value contract without changing portal dispatch or
 * fabricating the destination and post-transition behavior required by DimensionTransition.
 */
internal fun StructuralRefactorPass.migrateLegacyPortalInfoValues(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths ->
        paths.filter { it.toString().endsWith(".java") }
            .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
            .toList()
    }
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val sources = files.associateWith { it.readText() }
    val units = sources.mapValues { (_, source) -> parser.parse(source).result.orElse(null) }
    val projectPortalInfoPackages = units.values.mapNotNull { unit ->
        if (unit == null || unit.types.none { it.nameAsString == PORTAL_INFO_SIMPLE_NAME }) null
        else unit.packageDeclaration.map { it.nameAsString }.orElse("")
    }.toSet()

    val changes = mutableListOf<Change>()
    var compatPackage: String? = null
    for ((file, source) in sources) {
        if (!source.contains(LEGACY_PORTAL_INFO)) continue
        val unit = units[file] ?: continue
        val portalInfoImports = unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.nameAsString.substringAfterLast('.') == PORTAL_INFO_SIMPLE_NAME
        }
        if (portalInfoImports.map { it.nameAsString } != listOf(LEGACY_PORTAL_INFO)) continue
        if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == PORTAL_INFO_SIMPLE_NAME }) continue
        if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == PORTAL_INFO_SIMPLE_NAME }) continue
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        if (packageName in projectPortalInfoPackages) continue

        // A generated value class is not evidence that an external reflected method changed its
        // return type. Leave those files for a separately proven reflection/API migration.
        if (unit.findAll(ClassExpr::class.java).any {
                it.type.asString() in setOf(PORTAL_INFO_SIMPLE_NAME, LEGACY_PORTAL_INFO)
            }) continue

        val masked = maskJavaCommentsAndLiterals(source)
        val importMatch = LEGACY_PORTAL_INFO_IMPORT.find(masked) ?: continue
        val legacyNameStart = importMatch.range.first + importMatch.groupValues[1].length
        val pkg = compatPackage
            ?: detectRequiredGeneratedCompatPackage(project, "legacy PortalInfo value contract")
                .also { compatPackage = it }
        val replacement = "$pkg.$PORTAL_INFO_SIMPLE_NAME"
        val migrated = source.replaceRange(
            legacyNameStart,
            legacyNameStart + LEGACY_PORTAL_INFO.length,
            replacement
        )
        if (!dryRun) file.writeText(migrated)
        changes += Change(
            file,
            1,
            "Retain the 1.20.1 PortalInfo four-field value contract without changing portal dispatch",
            LEGACY_PORTAL_INFO,
            replacement,
            Confidence.HIGH,
            PORTAL_INFO_RULE
        )
    }

    if (compatPackage == null) {
        compatPackage = units.values.asSequence()
            .filterNotNull()
            .flatMap { it.imports.asSequence() }
            .map { it.nameAsString }
            .firstOrNull { GENERATED_PORTAL_INFO_IMPORT.matches(it) }
            ?.substringBeforeLast('.')
    }
    val pkg = compatPackage ?: return changes
    val generated = root.resolve(pkg.replace('.', '/')).resolve("$PORTAL_INFO_SIMPLE_NAME.java")
    val content = javaClass.getResource("/compat/PortalInfo.java.template")!!
        .readText()
        .replace("@PACKAGE@", pkg)
    if (!java.nio.file.Files.exists(generated) || generated.readText() != content) {
        if (!dryRun) {
            generated.parent.createDirectories()
            generated.writeText(content)
        }
        changes += Change(
            generated,
            1,
            "Generate the verified 1.20.1 PortalInfo value shape",
            "",
            "$pkg.$PORTAL_INFO_SIMPLE_NAME",
            Confidence.HIGH,
            PORTAL_INFO_RULE
        )
    }
    return changes
}
