package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.TryStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.TypeParameter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/*
 * Primary evidence for this deliberately narrow migration:
 *
 * Old Forge 1.20.1 mapped binary:
 * net.minecraftforge:forge:1.20.1-47.4.16_mapped_official_1.20.1
 *   net.minecraft.world.entity.Entity#findDimensionEntryPoint
 *   net.minecraft.world.entity.Entity#getExitPortal
 *   net.minecraft.world.level.portal.PortalForcer#findPortalAround
 *   net.minecraft.world.level.portal.PortalShape#createPortalInfo
 *
 * Old Forge contract source:
 * net.minecraftforge:forge:1.20.1-47.4.16:sources
 *   net/minecraftforge/common/util/ITeleporter.java
 *
 * Target 1.21.1 sources:
 * Minecraft 1.21.1 official-mapped sources:
 *   net/minecraft/world/level/block/NetherPortalBlock.java
 *   net/minecraft/world/level/portal/PortalForcer.java
 *   net/minecraft/world/level/portal/PortalShape.java
 *   net/minecraft/world/entity/Entity.java
 *
 * The target NetherPortalBlock calculations are the old PortalShape#createPortalInfo calculations
 * plus a post-transition callback. This probe-only lane returns the old four-field value and never
 * creates a portal or fabricates a transition callback.
 */

private const val LEGACY_PORTAL_INFO_FQN = "net.minecraft.world.level.portal.PortalInfo"
private const val ENTITY_FQN = "net.minecraft.world.entity.Entity"
private const val SERVER_LEVEL_FQN = "net.minecraft.server.level.ServerLevel"
private const val BLOCK_POS_FQN = "net.minecraft.core.BlockPos"
private const val FUNCTION_FQN = "java.util.function.Function"
private const val LEGACY_FORGE_TELEPORTER_FQN = "net.minecraftforge.common.util.ITeleporter"
private const val LEGACY_NEOFORGE_TELEPORTER_FQN = "net.neoforged.neoforge.common.util.ITeleporter"
private const val LEGACY_PORTAL_PROBE_RULE = "struct-legacy-portal-probe"
private val GENERATED_LEGACY_PORTAL_PROBE_IMPORT = Regex(
    """^com\.modporter\.generated\.[a-z0-9_]+\.compat\.LegacyPortalProbe$"""
)

private data class PortalDraftEdit(
    val start: Int,
    val endExclusive: Int,
    val replacement: String
)

private data class ProbePlan(val edits: List<PortalDraftEdit>)

private data class TeleporterPlan(
    val edits: List<PortalDraftEdit>,
    val dispatchSeen: Boolean
)

private data class PortalDispatchPlan(
    val call: MethodCallExpr,
    val initializer: MethodCallExpr
)

private data class ReflectedTeleporterShape(
    val cast: CastExpr,
    val fallbackCall: MethodCallExpr
)

internal enum class LegacyPortalProbeTemplateKind {
    FULL,
    NATIVE_ONLY
}

internal sealed interface LegacyPortalProbeTemplateDecision {
    data class Selected(
        val kind: LegacyPortalProbeTemplateKind,
        val content: String
    ) : LegacyPortalProbeTemplateDecision

    data class Refused(val reason: String) : LegacyPortalProbeTemplateDecision
}

private val LEGACY_PORTAL_PROBE_SIMPLE_TOKEN = Regex("""\bLegacyPortalProbe\b""")
private val LEGACY_PORTAL_PROBE_PACKAGE = Regex(
    """^com\.modporter\.generated\.[a-z0-9_]+\.compat$"""
)

private class PortalDraftOffsets(private val source: String) {
    private val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }

    fun start(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)

    fun endExclusive(node: Node): Int = node.range
        .map { lineStarts[it.end.line - 1] + it.end.column }
        .orElse(-1)

    fun text(node: Node): String {
        val start = start(node)
        val end = endExclusive(node)
        return if (start < 0 || end < start) "" else source.substring(start, end)
    }
}

internal fun loadLegacyPortalProbeTemplate(
    kind: LegacyPortalProbeTemplateKind,
    packageName: String
): String {
    require(LEGACY_PORTAL_PROBE_PACKAGE.matches(packageName)) {
        "Refusing an unowned generated portal helper package: $packageName"
    }
    val resourceName = when (kind) {
        LegacyPortalProbeTemplateKind.FULL -> "/compat/LegacyPortalProbe.java.template"
        LegacyPortalProbeTemplateKind.NATIVE_ONLY ->
            "/compat/LegacyPortalProbe.native.java.template"
    }
    val resource = LegacyPortalProbeTemplateDecision::class.java.getResource(resourceName)
        ?: error("Missing required portal helper resource: $resourceName")
    return resource.readText().replace("@PACKAGE@", packageName)
}

internal fun decideLegacyPortalProbeTemplate(
    sources: Map<Path, String>,
    helperFile: Path,
    packageName: String,
    existingContent: String?
): LegacyPortalProbeTemplateDecision {
    val helperFqn = "$packageName.LegacyPortalProbe"
    if (!GENERATED_LEGACY_PORTAL_PROBE_IMPORT.matches(helperFqn)) {
        return LegacyPortalProbeTemplateDecision.Refused(
            "Portal helper is outside the owned generated namespace: $helperFqn"
        )
    }
    val fullTemplate = loadLegacyPortalProbeTemplate(LegacyPortalProbeTemplateKind.FULL, packageName)
    val nativeTemplate = loadLegacyPortalProbeTemplate(
        LegacyPortalProbeTemplateKind.NATIVE_ONLY,
        packageName
    )
    val existingKind = when (existingContent) {
        null -> null
        fullTemplate -> LegacyPortalProbeTemplateKind.FULL
        nativeTemplate -> LegacyPortalProbeTemplateKind.NATIVE_ONLY
        else -> return LegacyPortalProbeTemplateDecision.Refused(
            "Existing portal helper is not an exact owned template: $helperFile"
        )
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    var nativeUses = 0
    var fullUses = 0
    for ((file, source) in sources) {
        if (file.toAbsolutePath().normalize() == helperFile.toAbsolutePath().normalize()) continue
        if (!source.contains("LegacyPortalProbe") && !source.contains(helperFqn)) continue
        val parsed = parser.parse(source)
        if (!parsed.isSuccessful || !parsed.result.isPresent) {
            return LegacyPortalProbeTemplateDecision.Refused(
                "Portal helper consumer does not parse: $file"
            )
        }
        val unit = parsed.result.get()
        val exactImport = unit.imports.any {
            !it.isAsterisk && !it.isStatic && it.nameAsString == helperFqn
        }
        if (unit.imports.any { it.isStatic && it.nameAsString.startsWith("$helperFqn.") }) {
            return LegacyPortalProbeTemplateDecision.Refused(
                "Static portal helper import is outside the admitted feature closure: $file"
            )
        }
        val samePackage = unit.packageDeclaration
            .map { it.nameAsString == packageName }
            .orElse(false)
        val wildcardImport = unit.imports.any {
            it.isAsterisk && !it.isStatic && it.nameAsString == packageName
        }
        val simpleBinding = exactImport || samePackage
        val conflictingImport = unit.imports.any {
            !it.isAsterisk && !it.isStatic &&
                it.nameAsString.endsWith(".LegacyPortalProbe") &&
                it.nameAsString != helperFqn
        }
        val shadowedSimpleName = simpleBinding && (
            unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == "LegacyPortalProbe" } ||
                unit.findAll(VariableDeclarator::class.java)
                    .any { it.nameAsString == "LegacyPortalProbe" } ||
                unit.findAll(Parameter::class.java).any { it.nameAsString == "LegacyPortalProbe" } ||
                unit.findAll(TypeParameter::class.java).any { it.nameAsString == "LegacyPortalProbe" }
            )
        if (conflictingImport || shadowedSimpleName) {
            return LegacyPortalProbeTemplateDecision.Refused(
                "Portal helper simple name is shadowed or ambiguous in $file"
            )
        }
        val offsets = PortalDraftOffsets(source)
        val allowedRanges = mutableListOf<IntRange>()
        unit.imports.filter {
            !it.isAsterisk && !it.isStatic && it.nameAsString == helperFqn
        }.forEach { importDeclaration ->
            portalProbeNodeRange(offsets, importDeclaration)?.let(allowedRanges::add)
        }

        for (call in unit.findAll(MethodCallExpr::class.java)) {
            val scope = call.scope.orElse(null) ?: continue
            val scopeText = offsets.text(scope)
            val bound = scopeText == helperFqn ||
                (simpleBinding && scope is NameExpr && scope.nameAsString == "LegacyPortalProbe")
            if (!bound) continue
            val admitted = when {
                call.nameAsString == "findDimensionEntryPoint" &&
                    isExactNativePortalProbeCall(call) -> {
                    nativeUses++
                    true
                }
                call.nameAsString in setOf("vanilla", "adapt", "reflective", "portalInfo") &&
                    call.arguments.size == 1 -> {
                    fullUses++
                    true
                }
                call.nameAsString == "findVirtual" && call.arguments.size >= 3 -> {
                    fullUses++
                    true
                }
                else -> false
            }
            if (!admitted) {
                return LegacyPortalProbeTemplateDecision.Refused(
                    "Unsupported portal helper call ${call.nameAsString}/${call.arguments.size} in $file"
                )
            }
            portalProbeNodeRange(offsets, scope)?.let(allowedRanges::add)
        }

        for (reference in unit.findAll(MethodReferenceExpr::class.java)) {
            val scope = reference.scope
            val scopeText = offsets.text(scope)
            val bound = scopeText == helperFqn ||
                (simpleBinding && scopeText == "LegacyPortalProbe")
            if (!bound) continue
            if (reference.identifier != "vanilla") {
                return LegacyPortalProbeTemplateDecision.Refused(
                    "Unsupported portal helper method reference ${reference.identifier} in $file"
                )
            }
            fullUses++
            portalProbeNodeRange(offsets, scope)?.let(allowedRanges::add)
        }

        for (type in unit.findAll(ClassOrInterfaceType::class.java)) {
            val typeText = offsets.text(type)
            val boundTeleporter = typeText == "$helperFqn.Teleporter" ||
                (simpleBinding && typeText == "LegacyPortalProbe.Teleporter")
            if (!boundTeleporter) continue
            fullUses++
            portalProbeNodeRange(offsets, type)?.let(allowedRanges::add)
        }

        val remainder = maskLegacyPortalProbeRanges(source, allowedRanges)
        if (remainder.contains(helperFqn)) {
            return LegacyPortalProbeTemplateDecision.Refused(
                "Unsupported fully qualified portal helper consumer in $file"
            )
        }
        if ((simpleBinding || wildcardImport) &&
            LEGACY_PORTAL_PROBE_SIMPLE_TOKEN.containsMatchIn(remainder)
        ) {
            return LegacyPortalProbeTemplateDecision.Refused(
                "Unsupported portal helper consumer in $file"
            )
        }
    }

    if (nativeUses == 0 && fullUses == 0) {
        return LegacyPortalProbeTemplateDecision.Refused(
            "Portal helper has no exact admitted source consumer"
        )
    }
    val selectedKind = if (fullUses == 0) {
        LegacyPortalProbeTemplateKind.NATIVE_ONLY
    } else {
        LegacyPortalProbeTemplateKind.FULL
    }
    if (existingKind == LegacyPortalProbeTemplateKind.NATIVE_ONLY &&
        selectedKind == LegacyPortalProbeTemplateKind.FULL
    ) {
        return LegacyPortalProbeTemplateDecision.Refused(
            "Native-only portal helper cannot satisfy admitted full helper consumers"
        )
    }
    val content = if (selectedKind == LegacyPortalProbeTemplateKind.NATIVE_ONLY) {
        nativeTemplate
    } else {
        fullTemplate
    }
    return LegacyPortalProbeTemplateDecision.Selected(selectedKind, content)
}

private fun isExactNativePortalProbeCall(call: MethodCallExpr): Boolean {
    if (call.arguments.size != 3 || call.arguments[0] !is ThisExpr) return false
    val entrance = call.arguments[1]
    val exactEntrance = (entrance as? NameExpr)?.nameAsString == "portalEntrancePos" ||
        (entrance as? com.github.javaparser.ast.expr.FieldAccessExpr)?.let {
            it.nameAsString == "portalEntrancePos" && it.scope is ThisExpr
        } == true
    if (!exactEntrance) return false
    val destination = (call.arguments[2] as? NameExpr)?.nameAsString ?: return false
    val owner = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
    if (owner.nameAsString != "findDimensionEntryPoint" || owner.parameters.size != 1) return false
    val parameter = owner.parameters.single()
    return parameter.nameAsString == destination && parameter.type.asString().endsWith("ServerLevel")
}

private fun portalProbeNodeRange(
    offsets: PortalDraftOffsets,
    node: Node
): IntRange? {
    val start = offsets.start(node)
    val endExclusive = offsets.endExclusive(node)
    return if (start < 0 || endExclusive <= start) null else start until endExclusive
}

private fun maskLegacyPortalProbeRanges(source: String, ranges: List<IntRange>): String {
    val masked = source.toCharArray()
    for (range in ranges) {
        for (index in range) {
            if (index !in masked.indices) continue
            if (masked[index] != '\n' && masked[index] != '\r') masked[index] = ' '
        }
    }
    var index = 0
    while (index < source.length) {
        when {
            source.startsWith("//", index) -> {
                val end = source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                for (position in index until end) if (masked[position] != '\r') masked[position] = ' '
                index = end
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                for (position in index until end) {
                    if (masked[position] != '\n' && masked[position] != '\r') masked[position] = ' '
                }
                index = end
            }
            source.startsWith("\"\"\"", index) -> {
                val end = source.indexOf("\"\"\"", index + 3)
                    .let { if (it < 0) source.length else it + 3 }
                for (position in index until end) {
                    if (masked[position] != '\n' && masked[position] != '\r') masked[position] = ' '
                }
                index = end
            }
            source[index] == '"' || source[index] == '\'' -> {
                val quote = source[index]
                var end = index + 1
                var escaped = false
                while (end < source.length) {
                    val character = source[end]
                    if (character == quote && !escaped) {
                        end++
                        break
                    }
                    escaped = character == '\\' && !escaped
                    if (character != '\\') escaped = false
                    end++
                }
                for (position in index until end) {
                    if (masked[position] != '\n' && masked[position] != '\r') masked[position] = ' '
                }
                index = end
            }
            else -> index++
        }
    }
    return String(masked)
}

/**
 * Restores only the legacy probe calculation and the exact ITeleporter Function dispatch used to
 * ask that probe for a value. Custom ITeleporter implementations, placement hooks, sound hooks,
 * portal creation, and reflection without the original catch/log/fallback reject the provider
 * edits without suppressing an independently exact probe override.
 *
 * This component is invoked only inside [migrateLegacyPortalFamilyTransaction]. Root integration
 * must not call it independently. The broad legacy ITeleporter rule must fail closed for files
 * importing the generated PortalInfo compatibility class.
 */
internal fun StructuralRefactorPass.migrateLegacyPortalProbeTransitions(
    project: Path,
    dryRun: Boolean
): List<Change> {
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
    val projectTypes = units.values.filterNotNull().flatMap { unit ->
        val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        unit.findAll(TypeDeclaration::class.java).map { pkg to it.nameAsString }
    }.toSet()

    val probePlans = linkedMapOf<Path, ProbePlan>()
    for ((file, source) in sources) {
        val unit = units[file] ?: continue
        planClosedLegacyPortalProbe(source, unit, projectTypes)?.let { probePlans[file] = it }
    }

    val exactTeleporterFiles = sources.keys.filter { file ->
        units[file]?.let(::legacyTeleporterImport) != null
    }.toSet()
    val hasUnparsedLegacyTeleporter = sources.any { (file, source) ->
        units[file] == null && (
            source.contains(LEGACY_FORGE_TELEPORTER_FQN) ||
                source.contains(LEGACY_NEOFORGE_TELEPORTER_FQN)
            )
    }
    val hasUnimportedLegacyTeleporter = sources.any { (file, source) ->
        file !in exactTeleporterFiles && (
            source.contains(LEGACY_FORGE_TELEPORTER_FQN) ||
                source.contains(LEGACY_NEOFORGE_TELEPORTER_FQN)
            )
    }

    val teleporterPlans = linkedMapOf<Path, TeleporterPlan>()
    val projectUnits = units.values.filterNotNull().toList()
    var teleporterFamilyRejected = hasUnparsedLegacyTeleporter || hasUnimportedLegacyTeleporter
    if (
        exactTeleporterFiles.isNotEmpty() &&
        !hasUnparsedLegacyTeleporter &&
        !hasUnimportedLegacyTeleporter
    ) {
        var closed = true
        for (file in exactTeleporterFiles) {
            val unit = units[file]
            val plan = if (unit == null) null else {
                planClosedLegacyTeleporterFunctionFile(
                    sources.getValue(file),
                    unit,
                    projectTypes,
                    projectUnits
                )
            }
            if (plan == null) {
                closed = false
                break
            }
            teleporterPlans[file] = plan
        }
        if (!closed || teleporterPlans.values.none { it.dispatchSeen }) {
            teleporterPlans.clear()
            teleporterFamilyRejected = true
        }
    }
    if (teleporterFamilyRejected) {
        // The exact probe override is independent of ordinary/custom ITeleporter families. Keep
        // its plan, but publish none of the Function-family edits unless that whole family closed.
        teleporterPlans.clear()
    }

    val plannedFiles = (probePlans.keys + teleporterPlans.keys).toSet()
    val existingProbePackages = units.values.asSequence()
        .filterNotNull()
        .flatMap { it.imports.asSequence() }
        .map { it.nameAsString }
        .filter { GENERATED_LEGACY_PORTAL_PROBE_IMPORT.matches(it) }
        .map { it.substringBeforeLast('.') }
        .toSet()
    if (existingProbePackages.size > 1) return emptyList()

    val compatPackage = if (plannedFiles.isNotEmpty()) {
        detectRequiredGeneratedCompatPackage(project, "legacy portal probe calculation")
    } else {
        existingProbePackages.singleOrNull() ?: return emptyList()
    }
    if (existingProbePackages.isNotEmpty() && existingProbePackages.single() != compatPackage) {
        return emptyList()
    }

    val changes = mutableListOf<Change>()
    val stagedSources = linkedMapOf<Path, String>()
    for (file in plannedFiles) {
        val source = sources.getValue(file)
        val probe = probePlans[file]
        val teleporter = teleporterPlans[file]
        val edits = buildList {
            probe?.let { addAll(it.edits) }
            teleporter?.let { addAll(it.edits) }
        }
        if (edits.isEmpty()) continue

        var migrated = applyPortalDraftEdits(source, edits)
        if (probe != null) migrated = addImportIfMissing(migrated, BLOCK_POS_FQN)
        if (teleporter != null) {
            migrated = removeLegacyPortalImportLine(migrated, LEGACY_FORGE_TELEPORTER_FQN)
            migrated = removeLegacyPortalImportLine(migrated, LEGACY_NEOFORGE_TELEPORTER_FQN)
        }
        migrated = addImportIfMissing(migrated, "$compatPackage.LegacyPortalProbe")
        if (migrated == source) continue
        stagedSources[file] = migrated

        val summary = when {
            probe != null && teleporter != null ->
                "Retain the source-owned portal probe and its erasure-preserving Function dispatch"
            probe != null ->
                "Retain the existing-portal probe calculation and its source-owned entrance position"
            else ->
                "Retain the portal probe Function dispatch through a checked runtime adapter"
        }
        changes += Change(
            file,
            1,
            summary,
            if (probe != null) "Entity.findDimensionEntryPoint(ServerLevel)" else "Function<ServerLevel, ITeleporter>",
            "$compatPackage.LegacyPortalProbe",
            Confidence.HIGH,
            LEGACY_PORTAL_PROBE_RULE
        )
    }

    val generated = root.resolve(compatPackage.replace('.', '/')).resolve("LegacyPortalProbe.java")
    val projectSources = walkJavaSourceRoots(root).use { paths ->
        paths.filter { it.toString().endsWith(".java") }
            .toList()
            .associateWith { it.readText() }
            .toMutableMap()
    }
    projectSources.putAll(stagedSources)
    val existingContent = projectSources[generated]
    val templateDecision = decideLegacyPortalProbeTemplate(
        projectSources,
        generated,
        compatPackage,
        existingContent
    )
    if (templateDecision is LegacyPortalProbeTemplateDecision.Refused) return emptyList()
    val selected = templateDecision as LegacyPortalProbeTemplateDecision.Selected
    val content = selected.content
    val helperChanged = existingContent != content
    if (helperChanged) {
        val helperDescription = when (selected.kind) {
            LegacyPortalProbeTemplateKind.FULL ->
                "Generate the exact old portal probe algorithm and checked value adapters"
            LegacyPortalProbeTemplateKind.NATIVE_ONLY ->
                "Generate the exact old native portal probe algorithm"
        }
        changes += Change(
            generated,
            1,
            helperDescription,
            "",
            "$compatPackage.LegacyPortalProbe",
            Confidence.HIGH,
            LEGACY_PORTAL_PROBE_RULE
        )
    }
    if (!dryRun) {
        stagedSources.forEach { (file, migrated) -> file.writeText(migrated) }
        if (helperChanged) {
            generated.parent.createDirectories()
            generated.writeText(content)
        }
    }
    return changes
}

private fun StructuralRefactorPass.removeLegacyPortalImportLine(
    source: String,
    importName: String
): String {
    val importLine = Regex(
        """(?m)^[ \t]*import[ \t]+${Regex.escape(importName)}[ \t]*;[ \t]*(?:\r?\n|$)"""
    )
    val match = importLine.find(maskJavaCommentsAndLiterals(source)) ?: return source
    return source.removeRange(match.range.first, match.range.last + 1)
}

private fun StructuralRefactorPass.planClosedLegacyPortalProbe(
    source: String,
    unit: CompilationUnit,
    projectTypes: Set<Pair<String, String>>
): ProbePlan? {
    if (!hasExactSimpleBinding(unit, "PortalInfo", LEGACY_PORTAL_INFO_FQN, projectTypes)) return null
    if (!hasExactSimpleBinding(unit, "Entity", ENTITY_FQN, projectTypes)) return null
    if (!hasExactSimpleBinding(unit, "ServerLevel", SERVER_LEVEL_FQN, projectTypes)) return null
    if (!canAddOrReuseSimpleImport(unit, "BlockPos", BLOCK_POS_FQN, projectTypes)) return null

    val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration ?: return null
    if (owner.isInterface || owner.extendedTypes.size != 1) return null
    if (owner.extendedTypes.single().nameWithScope != "Entity") return null
    if (owner.fields.flatMap { it.variables }.any { it.nameAsString == "portalEntrancePos" }) return null

    val methods = owner.methods.filter { it.nameAsString == "findDimensionEntryPoint" }
    val method = methods.singleOrNull() ?: return null
    if (!method.isPublic || method.isStatic || method.type.asString() != "PortalInfo") return null
    if (method.annotations.size != 1 || method.annotations.single().nameAsString != "Override") return null
    if (method.parameters.size != 1 || method.parameters.single().type.asString() != "ServerLevel") return null
    val parameter = method.parameters.single().nameAsString
    val statement = method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt ?: return null
    val superCall = statement.expression.orElse(null) as? MethodCallExpr ?: return null
    if (superCall.nameAsString != "findDimensionEntryPoint" || superCall.arguments.size != 1) return null
    if (superCall.scope.orElse(null) !is SuperExpr) return null
    val superArgument = superCall.arguments.single() as? NameExpr ?: return null
    if (superArgument.nameAsString != parameter) return null

    val entranceUses = owner.findAll(NameExpr::class.java).filter {
        it.nameAsString == "portalEntrancePos" &&
            it.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) === owner
    }
    if (entranceUses.size != 1) return null
    val entranceUse = entranceUses.single()
    val assignment = entranceUse.parentNode.orElse(null) as? AssignExpr ?: return null
    if (assignment.target !== entranceUse || assignment.operator != AssignExpr.Operator.ASSIGN) return null

    val offsets = PortalDraftOffsets(source)
    val masked = maskJavaCommentsAndLiterals(source)
    val classStart = offsets.start(owner)
    val classEnd = offsets.endExclusive(owner)
    if (classStart < 0 || classEnd <= classStart) return null
    val openBrace = masked.indexOf('{', classStart)
    if (openBrace < classStart || openBrace >= classEnd) return null
    val methodStart = offsets.start(method)
    val annotationStart = offsets.start(method.annotations.single())
    if (methodStart < 0 || annotationStart < 0) return null
    val annotationLineStart = source.lastIndexOf('\n', annotationStart - 1).let { if (it < 0) 0 else it + 1 }
    val annotationLineEnd = source.indexOf('\n', annotationStart).let { if (it < 0) source.length else it + 1 }
    if (source.substring(annotationLineStart, annotationLineEnd).trim() != "@Override") return null
    val methodLineStart = source.lastIndexOf('\n', methodStart - 1).let { if (it < 0) 0 else it + 1 }
    val memberIndent = source.substring(methodLineStart, methodStart).takeWhile { it == ' ' || it == '\t' }
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val callStart = offsets.start(superCall)
    val callEnd = offsets.endExclusive(superCall)
    if (callStart < 0 || callEnd <= callStart) return null

    return ProbePlan(
        listOf(
            PortalDraftEdit(
                callStart,
                callEnd,
                "LegacyPortalProbe.findDimensionEntryPoint(this, portalEntrancePos, $parameter)"
            ),
            PortalDraftEdit(annotationLineStart, annotationLineEnd, ""),
            PortalDraftEdit(
                openBrace + 1,
                openBrace + 1,
                "$newline$memberIndent" + "protected BlockPos portalEntrancePos;$newline"
            )
        )
    )
}

/**
 * Returns an edit plan plus whether this file contains the exact three-argument probe dispatch.
 * A null result excludes the project teleporter provider edits before any source is written.
 */
private fun planClosedLegacyTeleporterFunctionFile(
    source: String,
    unit: CompilationUnit,
    projectTypes: Set<Pair<String, String>>,
    projectUnits: List<CompilationUnit>
): TeleporterPlan? {
    if (legacyTeleporterImport(unit) == null) return null
    if (!hasExactSimpleBinding(unit, "Function", FUNCTION_FQN, projectTypes)) return null
    if (!hasExactSimpleBinding(unit, "ServerLevel", SERVER_LEVEL_FQN, projectTypes)) return null
    if (hasTypeShadow(unit, "ITeleporter", projectTypes)) return null
    if (unit.findAll(ClassOrInterfaceDeclaration::class.java).any {
            it.implementedTypes.any { type -> type.nameWithScope == "ITeleporter" }
        }) return null
    if (unit.findAll(MethodDeclaration::class.java).any { it.nameAsString == "getPortalInfo" }) return null

    val offsets = PortalDraftOffsets(source)
    val edits = mutableListOf<PortalDraftEdit>()
    val supportedCasts = linkedMapOf<CastExpr, ReflectedTeleporterShape>()
    val allTeleporterCasts = unit.findAll(CastExpr::class.java).filter {
        it.type.asString() == "ITeleporter"
    }
    for (cast in allTeleporterCasts) {
        val reflected = closedReflectedTeleporterShape(cast) ?: return null
        supportedCasts[cast] = reflected
        val expression = offsets.text(cast.expression)
        if (expression.isBlank()) return null
        edits += PortalDraftEdit(
            offsets.start(cast),
            offsets.endExclusive(cast),
            "LegacyPortalProbe.reflective($expression)"
        )
        val fallback = reflected.fallbackCall
        val fallbackScope = fallback.scope.orElse(null) as? NameExpr ?: return null
        edits += PortalDraftEdit(
            offsets.start(fallback),
            offsets.endExclusive(fallback),
            "LegacyPortalProbe.vanilla(${fallbackScope.nameAsString})"
        )
    }

    val admittedTeleporterTypes = mutableListOf<ClassOrInterfaceType>()
    val functionTypes = unit.findAll(ClassOrInterfaceType::class.java)
        .filter(::isLegacyTeleporterFunctionType)
    for (functionType in functionTypes) {
        if (!validateFunctionValueSources(functionType, supportedCasts.keys, projectUnits)) {
            return null
        }
        val arguments = functionType.typeArguments.orElse(null) ?: return null
        if (arguments.size != 2) return null
        val teleporterType = arguments[1] as? ClassOrInterfaceType ?: return null
        admittedTeleporterTypes += teleporterType
        edits += PortalDraftEdit(
            offsets.start(teleporterType),
            offsets.endExclusive(teleporterType),
            "?"
        )
    }

    var dispatchSeen = false
    val teleporterVariables = unit.findAll(VariableDeclarator::class.java).filter { variable ->
        (variable.type as? ClassOrInterfaceType)?.nameWithScope == "ITeleporter"
    }
    for (variable in teleporterVariables) {
        val teleporterType = variable.type as ClassOrInterfaceType
        val dispatch = closedPortalInfoDispatchForVariable(variable) ?: return null
        dispatchSeen = true
        admittedTeleporterTypes += teleporterType
        edits += PortalDraftEdit(
            offsets.start(teleporterType),
            offsets.endExclusive(teleporterType),
            "LegacyPortalProbe.Teleporter"
        )
        if (!isClosedPortalInfoDispatch(dispatch.call)) return null
        val originalInitializer = offsets.text(dispatch.initializer)
        if (originalInitializer.isBlank()) return null
        edits += PortalDraftEdit(
            offsets.start(dispatch.initializer),
            offsets.endExclusive(dispatch.initializer),
            "LegacyPortalProbe.adapt($originalInitializer)"
        )
    }
    supportedCasts.keys.forEach { cast ->
        (cast.type as? ClassOrInterfaceType)?.let(admittedTeleporterTypes::add)
    }

    val teleporterTypes = unit.findAll(ClassOrInterfaceType::class.java)
        .filter { it.nameWithScope == "ITeleporter" }
    if (teleporterTypes.isEmpty()) return null
    if (teleporterTypes.any { type -> admittedTeleporterTypes.none { it === type } }) return null

    val allVanillaReferences = unit.findAll(MethodReferenceExpr::class.java).filter {
        it.identifier == "getPortalForcer" && it.scope.toString() == "ServerLevel"
    }
    for (reference in allVanillaReferences) {
        if (!flowsToLegacyTeleporterFunction(reference, projectUnits)) return null
        edits += PortalDraftEdit(
            offsets.start(reference),
            offsets.endExclusive(reference),
            "LegacyPortalProbe::vanilla"
        )
    }

    val admittedFallbacks = supportedCasts.values.map { it.fallbackCall }.toSet()
    val otherForcerCalls = unit.findAll(MethodCallExpr::class.java).filter {
        it.nameAsString == "getPortalForcer" &&
            it.arguments.isEmpty() &&
            it !in admittedFallbacks
    }
    if (otherForcerCalls.isNotEmpty()) return null

    if (edits.any { it.start < 0 || it.endExclusive <= it.start }) return null
    return TeleporterPlan(edits, dispatchSeen)
}

private fun closedPortalInfoDispatchForVariable(variable: VariableDeclarator): PortalDispatchPlan? {
    val method = variable.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
    if (!hasUniqueMethodValueBinding(method, variable.nameAsString, variable)) return null
    val initializer = variable.initializer.orElse(null) as? MethodCallExpr ?: return null
    if (initializer.nameAsString != "apply" || initializer.arguments.size != 1) return null
    val provider = initializer.scope.orElse(null) as? NameExpr ?: return null
    val destination = initializer.arguments.single() as? NameExpr ?: return null
    val providerParameters = method.parameters.filter {
        it.nameAsString == provider.nameAsString &&
            (it.type as? ClassOrInterfaceType)?.let(::isLegacyTeleporterFunctionType) == true
    }
    if (providerParameters.size != 1) return null
    if (!hasUniqueMethodValueBinding(method, provider.nameAsString, providerParameters.single())) {
        return null
    }
    if (!hasUniqueMethodValueName(method, destination.nameAsString)) return null

    val uses = method.findAll(NameExpr::class.java).filter { it.nameAsString == variable.nameAsString }
    if (uses.size != 1) return null
    val use = uses.single()
    val call = use.parentNode.orElse(null) as? MethodCallExpr ?: return null
    if (call.scope.orElse(null) !== use || !isClosedPortalInfoDispatch(call)) return null
    val dispatchDestination = call.arguments[1] as? NameExpr ?: return null
    if (dispatchDestination.nameAsString != destination.nameAsString) return null

    val providerUses = method.findAll(NameExpr::class.java).filter { it.nameAsString == provider.nameAsString }
    if (providerUses.size != 1 || providerUses.single() !== provider) return null
    return PortalDispatchPlan(call, initializer)
}

private fun isClosedPortalInfoDispatch(call: MethodCallExpr): Boolean {
    if (call.nameAsString != "getPortalInfo" || call.arguments.size != 3) return false
    val entity = call.arguments[0] as? NameExpr ?: return false
    val destination = call.arguments[1] as? NameExpr ?: return false
    val methodReference = call.arguments[2] as? MethodReferenceExpr ?: return false
    val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
    val referenceScopeName = when (val scope = methodReference.scope) {
        is NameExpr -> scope.nameAsString
        is TypeExpr -> {
            val type = scope.type as? ClassOrInterfaceType ?: return false
            if (
                type.scope.isPresent ||
                type.typeArguments.isPresent ||
                type.annotations.isNotEmpty()
            ) {
                return false
            }
            type.nameAsString
        }
        else -> return false
    }
    if (!hasUniqueMethodValueName(method, entity.nameAsString)) return false
    if (!hasUniqueMethodValueName(method, destination.nameAsString)) return false
    return methodReference.identifier == "findDimensionEntryPoint" &&
        referenceScopeName == entity.nameAsString
}

private fun isLegacyTeleporterFunctionType(type: ClassOrInterfaceType): Boolean {
    if (type.nameWithScope != "Function") return false
    val arguments = type.typeArguments.orElse(null) ?: return false
    return arguments.size == 2 &&
        arguments[0].asString() == "ServerLevel" &&
        arguments[1].asString() == "ITeleporter"
}

private fun validateMethodFunctionReturns(
    method: MethodDeclaration,
    supportedCasts: Set<CastExpr>
): Boolean {
    if (method.annotations.any { it.nameAsString == "Override" }) return false
    val returns = method.findAll(ReturnStmt::class.java).filter {
        it.findAncestor(MethodDeclaration::class.java).orElse(null) === method &&
            it.findAncestor(LambdaExpr::class.java).orElse(null) == null
    }
    if (returns.isEmpty()) return false
    return returns.all { statement ->
        when (val expression = statement.expression.orElse(null)) {
            is MethodReferenceExpr ->
                expression.identifier == "getPortalForcer" && expression.scope.toString() == "ServerLevel"
            is LambdaExpr -> expression.findAll(CastExpr::class.java).any(supportedCasts::contains)
            else -> false
        }
    }
}

private fun validateFunctionValueSources(
    type: ClassOrInterfaceType,
    supportedCasts: Set<CastExpr>,
    projectUnits: List<CompilationUnit>
): Boolean {
    return when (val parent = type.parentNode.orElse(null)) {
        is Parameter -> parent.type === type &&
            isClosedFunctionParameter(parent, projectUnits, emptyList())
        is MethodDeclaration -> validateMethodFunctionReturns(parent, supportedCasts)
        else -> false
    }
}

private fun isClosedFunctionParameter(
    parameter: Parameter,
    projectUnits: List<CompilationUnit>,
    visited: List<Parameter>
): Boolean {
    if (visited.any { it === parameter }) return false
    val method = parameter.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
    if (method.annotations.any { it.nameAsString == "Override" }) return false
    if (!hasUniqueMethodValueBinding(method, parameter.nameAsString, parameter)) return false
    val uses = method.findAll(NameExpr::class.java).filter {
        it.nameAsString == parameter.nameAsString
    }
    if (uses.size != 1) return false
    val use = uses.single()
    val call = use.parentNode.orElse(null) as? MethodCallExpr ?: return false
    if (call.scope.orElse(null) === use) {
        if (call.nameAsString != "apply" || call.arguments.size != 1) return false
        val variable = call.findAncestor(VariableDeclarator::class.java).orElse(null) ?: return false
        if (variable.initializer.orElse(null) !== call || variable.type.asString() != "ITeleporter") {
            return false
        }
        return closedPortalInfoDispatchForVariable(variable) != null
    }
    val argumentIndex = call.arguments.indexOfFirst { it === use }
    if (argumentIndex < 0) return false
    val forwarded = resolveClosedFunctionForward(call, argumentIndex, projectUnits) ?: return false
    return isClosedFunctionParameter(forwarded, projectUnits, visited + parameter)
}

private fun resolveClosedFunctionForward(
    call: MethodCallExpr,
    argumentIndex: Int,
    projectUnits: List<CompilationUnit>
): Parameter? {
    val callerUnit = call.findCompilationUnit().orElse(null) ?: return null
    val callerPackage = callerUnit.packageDeclaration.map { it.nameAsString }.orElse("")
    val scope = call.scope.orElse(null)
    val typeScoped = scope is NameExpr
    val ownerName = when (scope) {
        null -> call.findAncestor(TypeDeclaration::class.java)
            .map { it.nameAsString }
            .orElse(null)
        is ObjectCreationExpr -> {
            if (scope.anonymousClassBody.isPresent) return null
            scope.type.nameWithScope
        }
        is NameExpr -> {
            // A class-name scope is admitted only when no source value can shadow it.
            val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
            if (methodValueDeclarations(method, scope.nameAsString).isNotEmpty()) return null
            if (callerUnit.findAll(com.github.javaparser.ast.body.FieldDeclaration::class.java).any { field ->
                    field.variables.any { it.nameAsString == scope.nameAsString }
                }) return null
            scope.nameAsString
        }
        else -> return null
    } ?: return null

    val declarations = projectUnits.flatMap { unit ->
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        unit.types.filter { type ->
            type.nameAsString == ownerName &&
                resolvesProjectType(
                    callerUnit,
                    unit,
                    type,
                    callerPackage,
                    packageName
                )
        }.flatMap { type ->
            type.members.filterIsInstance<MethodDeclaration>().filter {
                it.nameAsString == call.nameAsString &&
                    it.parameters.size == call.arguments.size &&
                    (!typeScoped || it.isStatic) &&
                    argumentIndex < it.parameters.size
            }
        }
    }
    val method = declarations.singleOrNull() ?: return null
    val forwarded = method.parameters[argumentIndex]
    return forwarded.takeIf {
        (it.type as? ClassOrInterfaceType)?.let(::isLegacyTeleporterFunctionType) == true
    }
}

private fun resolvesProjectType(
    callerUnit: CompilationUnit,
    targetUnit: CompilationUnit,
    targetType: TypeDeclaration<*>,
    callerPackage: String,
    targetPackage: String
): Boolean {
    val simpleName = targetType.nameAsString
    if (callerUnit.findAll(TypeParameter::class.java).any { it.nameAsString == simpleName }) {
        return false
    }
    val sourceTypes = callerUnit.findAll(TypeDeclaration::class.java).filter {
        it.nameAsString == simpleName
    }
    if (sourceTypes.isNotEmpty()) {
        return targetUnit === callerUnit &&
            sourceTypes.singleOrNull() === targetType &&
            targetType.parentNode.orElse(null) === callerUnit
    }
    if (callerPackage == targetPackage) return true
    val targetFqn = listOf(targetPackage, simpleName)
        .filter(String::isNotBlank)
        .joinToString(".")
    val imports = callerUnit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == simpleName
    }.map { it.nameAsString }
    return imports == listOf(targetFqn)
}

private fun flowsToLegacyTeleporterFunction(
    reference: MethodReferenceExpr,
    projectUnits: List<CompilationUnit>
): Boolean {
    val returned = reference.findAncestor(ReturnStmt::class.java).orElse(null)
    if (returned != null && returned.expression.orElse(null) === reference) {
        val method = returned.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
        return (method.type as? ClassOrInterfaceType)?.let(::isLegacyTeleporterFunctionType) == true
    }
    val invocation = reference.parentNode.orElse(null) as? MethodCallExpr ?: return false
    val position = invocation.arguments.indexOfFirst { it === reference }
    if (position < 0) return false
    val parameter = resolveClosedFunctionForward(invocation, position, projectUnits) ?: return false
    return isClosedFunctionParameter(parameter, projectUnits, emptyList())
}

private fun hasUniqueMethodValueBinding(
    method: MethodDeclaration,
    name: String,
    expected: Node
): Boolean = methodValueDeclarations(method, name).singleOrNull() === expected

private fun hasUniqueMethodValueName(method: MethodDeclaration, name: String): Boolean =
    methodValueDeclarations(method, name).size == 1

private fun methodValueDeclarations(method: MethodDeclaration, name: String): List<Node> {
    val parameters = method.findAll(Parameter::class.java).filter {
        it.nameAsString == name &&
            it.findAncestor(MethodDeclaration::class.java).orElse(null) === method
    }
    val variables = method.findAll(VariableDeclarator::class.java).filter {
        it.nameAsString == name &&
            it.findAncestor(MethodDeclaration::class.java).orElse(null) === method
    }
    return parameters + variables
}

private fun closedReflectedTeleporterShape(cast: CastExpr): ReflectedTeleporterShape? {
    val returned = cast.parentNode.orElse(null) as? ReturnStmt ?: return null
    if (returned.expression.orElse(null) !== cast) return null
    val tryStatement = returned.findAncestor(TryStmt::class.java).orElse(null) ?: return null
    if (tryStatement.resources.isNotEmpty() || tryStatement.finallyBlock.isPresent) return null
    if (tryStatement.tryBlock.statements.size != 1 || tryStatement.tryBlock.statements.single() !== returned) return null

    val newInstance = cast.expression as? MethodCallExpr ?: return null
    if (newInstance.nameAsString != "newInstance" || newInstance.arguments.size != 2) return null
    val constructor = newInstance.scope.orElse(null) as? MethodCallExpr ?: return null
    if (constructor.nameAsString != "getDeclaredConstructor" || constructor.arguments.size != 2) return null
    val serverLevelClass = constructor.arguments[0] as? ClassExpr ?: return null
    val booleanClass = constructor.arguments[1] as? ClassExpr ?: return null
    if (serverLevelClass.type.asString() != "ServerLevel" || booleanClass.type.asString() != "boolean") return null
    val classLookup = constructor.scope.orElse(null) as? MethodCallExpr ?: return null
    if (classLookup.nameAsString != "forName" || classLookup.scope.orElse(null)?.toString() != "Class") return null
    if (classLookup.arguments.size != 1 || !classLookup.arguments.single().isStringLiteralExpr) return null

    val lambda = tryStatement.findAncestor(LambdaExpr::class.java).orElse(null) ?: return null
    val block = lambda.body as? BlockStmt ?: return null
    if (tryStatement.parentNode.orElse(null) !== block || block.statements.size != 2) return null
    val tryIndex = block.statements.indexOfFirst { it === tryStatement }
    if (tryIndex != 0) return null
    if (lambda.parameters.size != 1) return null
    val lambdaParameter = lambda.parameters.single().nameAsString
    val constructorLevel = newInstance.arguments[0] as? NameExpr ?: return null
    if (constructorLevel.nameAsString != lambdaParameter) return null
    if (!newInstance.arguments[1].isBooleanLiteralExpr || !newInstance.arguments[1].asBooleanLiteralExpr().value) {
        return null
    }

    val loggingCatch = tryStatement.catchClauses.singleOrNull() ?: return null
    if (loggingCatch.parameter.type.asString() != "Exception") return null
    val catchStatements = loggingCatch.body.statements
    if (catchStatements.size != 1) return null
    val logStatement = catchStatements.single() as? ExpressionStmt ?: return null
    val logCall = logStatement.expression as? MethodCallExpr ?: return null
    if (logCall.nameAsString != "error") return null
    val catchName = loggingCatch.parameter.nameAsString
    if (logCall.arguments.none { it is NameExpr && it.nameAsString == catchName }) return null

    val fallbackReturn = block.statements[1] as? ReturnStmt ?: return null
    val fallback = fallbackReturn.expression.orElse(null) as? MethodCallExpr ?: return null
    val fallbackScope = fallback.scope.orElse(null) as? NameExpr ?: return null
    if (
        fallback.nameAsString != "getPortalForcer" ||
        fallback.arguments.isNotEmpty() ||
        fallbackScope.nameAsString != lambdaParameter
    ) return null
    return ReflectedTeleporterShape(cast, fallback)
}

private fun legacyTeleporterImport(unit: CompilationUnit): String? {
    val imports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == "ITeleporter"
    }.map { it.nameAsString }
    return imports.singleOrNull()?.takeIf {
        it == LEGACY_FORGE_TELEPORTER_FQN || it == LEGACY_NEOFORGE_TELEPORTER_FQN
    }
}

private fun hasExactSimpleBinding(
    unit: CompilationUnit,
    simpleName: String,
    fqn: String,
    projectTypes: Set<Pair<String, String>>
): Boolean {
    val imports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == simpleName
    }.map { it.nameAsString }
    return imports == listOf(fqn) && !hasTypeShadow(unit, simpleName, projectTypes)
}

private fun canAddOrReuseSimpleImport(
    unit: CompilationUnit,
    simpleName: String,
    fqn: String,
    projectTypes: Set<Pair<String, String>>
): Boolean {
    val imports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == simpleName
    }.map { it.nameAsString }
    return (imports.isEmpty() || imports == listOf(fqn)) &&
        !hasTypeShadow(unit, simpleName, projectTypes)
}

private fun hasTypeShadow(
    unit: CompilationUnit,
    simpleName: String,
    projectTypes: Set<Pair<String, String>>
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simpleName }) return true
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simpleName }) return true
    val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return (pkg to simpleName) in projectTypes
}

private fun applyPortalDraftEdits(source: String, edits: List<PortalDraftEdit>): String {
    var result = source
    val ordered = edits.distinct().sortedByDescending { it.start }
    var previousStart = source.length + 1
    for (edit in ordered) {
        require(edit.endExclusive <= previousStart) { "Overlapping legacy portal edits" }
        result = result.replaceRange(edit.start, edit.endExclusive, edit.replacement)
        previousStart = edit.start
    }
    return result
}
