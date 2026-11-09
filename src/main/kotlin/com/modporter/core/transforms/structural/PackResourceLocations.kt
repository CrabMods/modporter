package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Gives legacy in-memory PackResources their 1.21 PackLocationInfo without fabricating a title,
 * source, or KnownPack.
 *
 * The migration admits a resource implementation only when every source construction flows into
 * a RepositorySource record whose migrated Pack.readMetaAndCreate call already owns the exact
 * PackLocationInfo. The registration binds that same object to the resources before Minecraft
 * opens them. Unregistered resources, ambiguous construction flows, and lookalike pack APIs are
 * left for the compiler instead of receiving metadata inferred only from the id.
 */
internal object PackResourceLocations {
    const val RULE_ID = "struct-pack-resource-location"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private const val LOCATION_AWARE = "LocationAwarePackResources"

    private data class Candidate(
        val name: String,
        val file: Path,
        val classOpen: Int,
        val classClose: Int
    )

    private data class Registration(
        val owner: String,
        val file: Path,
        val callStart: Int,
        val locationRange: IntRange,
        val locationExpression: String,
        val resourceName: String,
        val resourceIndex: Int,
        val compatPackage: String
    )

    private data class Construction(val file: Path, val offset: Int)
    private data class Flow(
        val registration: Registration,
        val candidate: Candidate,
        val construction: Construction
    )
    private data class Edit(val from: Int, val toExclusive: Int, val replacement: String)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }.toList()
        }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val candidates = collectCandidates(sources)
        if (candidates.isEmpty()) return emptyList()
        val registrations = collectRegistrations(sources)
        if (registrations.isEmpty()) return emptyList()

        val candidateByName = candidates.groupBy { it.name }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        val admittedConstructions = linkedMapOf<Candidate, MutableSet<Construction>>()
        val flows = mutableListOf<Flow>()

        for (registration in registrations) {
            for ((usageFile, source) in sources) {
                val masked = JavaSourceText.maskCommentsAndLiterals(source)
                val wrapperCall = Regex("""\bnew\s+${Regex.escape(registration.owner)}\s*\(""")
                for (match in wrapperCall.findAll(masked)) {
                    val open = masked.indexOf('(', match.range.first)
                    val close = if (open >= 0) JavaSourceText.matchingParen(masked, open) else -1
                    if (close < 0) continue
                    val arguments = argumentRanges(source, open, close) ?: continue
                    if (registration.resourceIndex !in arguments.indices) continue
                    val resource = source.substring(arguments[registration.resourceIndex]).trim()
                    val resolved = resolveConstruction(
                        sources,
                        usageFile,
                        match.range.first,
                        resource,
                        arguments[registration.resourceIndex].first,
                        candidateByName
                    ) ?: continue
                    admittedConstructions.getOrPut(resolved.first) { linkedSetOf() }.add(resolved.second)
                    flows += Flow(registration, resolved.first, resolved.second)
                }
            }
        }

        val admittedCandidates = admittedConstructions.filter { (candidate, admitted) ->
            val all = sources.flatMap { (file, source) ->
                val masked = JavaSourceText.maskCommentsAndLiterals(source)
                Regex("""\bnew\s+${Regex.escape(candidate.name)}\s*\(""")
                    .findAll(masked)
                    .map { Construction(file, it.range.first) }
                    .toList()
            }.toSet()
            all.isNotEmpty() && all == admitted
        }.keys
        if (admittedCandidates.isEmpty()) return emptyList()

        val activeFlows = flows.filter { it.candidate in admittedCandidates }
        val activeRegistrations = activeFlows.map { it.registration }.distinct()
        if (activeRegistrations.isEmpty()) return emptyList()

        val packagesByCandidate = activeFlows.groupBy { it.candidate }
            .mapNotNull { (candidate, candidateFlows) ->
                candidateFlows.map { it.registration.compatPackage }
                    .distinct()
                    .singleOrNull()
                    ?.let { candidate to it }
            }
            .toMap()
        if (packagesByCandidate.isEmpty()) return emptyList()

        val editsByFile = linkedMapOf<Path, MutableList<Edit>>()
        for ((candidate, compatPackage) in packagesByCandidate) {
            val source = sources.getValue(candidate.file)
            val classText = source.substring(candidate.classOpen, candidate.classClose + 1)
            val fieldName = uniqueName(classText, "modporterPackLocation")
            val interfaceName = "$compatPackage.$LOCATION_AWARE"
            editsByFile.getOrPut(candidate.file) { mutableListOf() } +=
                Edit(candidate.classOpen, candidate.classOpen, ", $interfaceName ")
            val indent = Regex("""(?m)^([ \t]+)(?:@|public\b|protected\b|private\b|final\b)""")
                .find(classText)
                ?.groupValues
                ?.get(1)
                ?: "    "
            val bodyIndent = if (indent.contains('\t')) "$indent\t" else "$indent    "
            val members = """

$indent private net.minecraft.server.packs.PackLocationInfo $fieldName;

$indent@Override
$indent public void bindLocation(net.minecraft.server.packs.PackLocationInfo location) {
$bodyIndent location = java.util.Objects.requireNonNull(location, "location");
$bodyIndent if (this.$fieldName != null && !this.$fieldName.equals(location)) {
$bodyIndent     throw new IllegalStateException("Pack resources cannot be rebound to a different location");
$bodyIndent }
$bodyIndent this.$fieldName = location;
$indent}

$indent@Override
$indent public net.minecraft.server.packs.PackLocationInfo location() {
$bodyIndent return java.util.Objects.requireNonNull(
$bodyIndent     this.$fieldName, "Pack resources were opened without their PackLocationInfo");
$indent}
""".trimEnd()
            editsByFile.getOrPut(candidate.file) { mutableListOf() } +=
                Edit(candidate.classClose, candidate.classClose, "$members\n")
        }

        for (registration in activeRegistrations.distinctBy { Triple(it.file, it.callStart, it.resourceName) }) {
            val source = sources.getValue(registration.file)
            if (source.substring(
                    maxOf(0, registration.callStart - 500),
                    registration.callStart
                ).contains("$LOCATION_AWARE")
            ) {
                continue
            }
            val lineStart = source.lastIndexOf('\n', registration.callStart).let { if (it < 0) 0 else it + 1 }
            val indent = source.substring(lineStart, registration.callStart).takeWhile { it == ' ' || it == '\t' }
            val methodPrefix = source.substring(maxOf(0, lineStart - 4000), registration.callStart)
            val locationName = uniqueName(methodPrefix, "modporterPackLocation")
            val awareName = uniqueName(methodPrefix, "modporterLocationAware")
            val binding =
                "$indent" +
                    "net.minecraft.server.packs.PackLocationInfo $locationName = " +
                    "${registration.locationExpression};\n" +
                    "$indent" +
                    "if (${registration.resourceName} instanceof " +
                    "${registration.compatPackage}.$LOCATION_AWARE $awareName) {\n" +
                    "$indent    $awareName.bindLocation($locationName);\n" +
                    "$indent}\n"
            editsByFile.getOrPut(registration.file) { mutableListOf() } +=
                Edit(lineStart, lineStart, binding)
            editsByFile.getOrPut(registration.file) { mutableListOf() } +=
                Edit(
                    registration.locationRange.first,
                    registration.locationRange.last + 1,
                    locationName
                )
        }

        val changes = mutableListOf<Change>()
        for ((file, edits) in editsByFile) {
            val original = sources.getValue(file)
            val normalized = edits.distinct().sortedWith(
                compareByDescending<Edit> { it.from }.thenByDescending { it.toExclusive }
            )
            if (normalized.zipWithNext().any { (later, earlier) -> earlier.toExclusive > later.from }) {
                continue
            }
            var modified = original
            for (edit in normalized) {
                modified = modified.substring(0, edit.from) + edit.replacement +
                    modified.substring(edit.toExclusive)
            }
            if (modified == original) continue
            if (!dryRun) file.writeText(modified)
            changes += Change(
                file = file,
                line = 1,
                description = "Bind the exact registered PackLocationInfo to legacy PackResources",
                before = "PackResources without location()",
                after = "location() backed by the Pack.readMetaAndCreate registration object",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }

        for (compatPackage in packagesByCandidate.values.distinct()) {
            val interfaceFile = sourceRoot
                .resolve(compatPackage.replace('.', '/'))
                .resolve("$LOCATION_AWARE.java")
            val interfaceSource = generatedInterface(compatPackage)
            if (interfaceFile.toFile().exists() && interfaceFile.readText() == interfaceSource) continue
            if (!dryRun) {
                interfaceFile.parent.createDirectories()
                interfaceFile.writeText(interfaceSource)
            }
            changes += Change(
                file = interfaceFile,
                line = 1,
                description = "Generate the exact-location binding contract for legacy PackResources",
                before = "(missing location binding contract)",
                after = "$compatPackage.$LOCATION_AWARE",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        return changes
    }

    private fun collectCandidates(sources: Map<Path, String>): List<Candidate> =
        sources.flatMap { (file, source) ->
            if (!isCanonicalPackResourcesSource(source)) return@flatMap emptyList()
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            Regex("""\bclass\s+($ID)\b([^{]*)\{""").findAll(masked).mapNotNull { declaration ->
                val name = declaration.groupValues[1]
                val header = declaration.groupValues[2]
                if (!Regex("""\bimplements\b[^{};]*\bPackResources\b""")
                        .containsMatchIn(header)
                ) {
                    return@mapNotNull null
                }
                val close = JavaSourceText.matchingBrace(masked, declaration.range.last)
                if (close < 0) return@mapNotNull null
                val classText = masked.substring(declaration.range.first, close + 1)
                if (Regex("""\bPackLocationInfo\s+location\s*\(""").containsMatchIn(classText) ||
                    Regex("""\blocation\s*\(\s*\)\s*\{""").containsMatchIn(classText)
                ) {
                    return@mapNotNull null
                }
                val packId = Regex(
                    """\bString\s+packId\s*\(\s*\)\s*\{\s*return\s+(?:this\s*\.\s*)?($ID)\s*;\s*\}"""
                ).find(classText) ?: return@mapNotNull null
                val idField = packId.groupValues[1]
                if (!Regex("""\bString\s+${Regex.escape(idField)}\b""").containsMatchIn(classText)) {
                    return@mapNotNull null
                }
                Candidate(name, file, declaration.range.last, close)
            }.toList()
        }

    private fun collectRegistrations(sources: Map<Path, String>): List<Registration> =
        sources.flatMap { (file, source) ->
            if (!source.contains("Pack.readMetaAndCreate") ||
                !source.contains("LegacyPackResourcesSupplier")
            ) {
                return@flatMap emptyList()
            }
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val records = collectRecords(source, masked)
            Regex("""\bPack\s*\.\s*readMetaAndCreate\s*\(""").findAll(masked).mapNotNull { call ->
                val open = masked.indexOf('(', call.range.first)
                val close = if (open >= 0) JavaSourceText.matchingParen(masked, open) else -1
                if (close < 0) return@mapNotNull null
                val arguments = argumentRanges(source, open, close)
                    ?: return@mapNotNull null
                if (arguments.size != 4) return@mapNotNull null
                val location = source.substring(arguments[0]).trim()
                if (!Regex("""^new\s+(?:[\w$.]+\.)?PackLocationInfo\s*\(""")
                        .containsMatchIn(location)
                ) {
                    return@mapNotNull null
                }
                val supplier = source.substring(arguments[1]).trim()
                val supplierMatch = Regex(
                    """^new\s+([\w$.]+\.LegacyPackResourcesSupplier)\s*\(\s*(?:\(\s*)?$ID(?:\s*\))?\s*->\s*($ID)\s*\)$"""
                ).matchEntire(supplier) ?: return@mapNotNull null
                val resourceName = supplierMatch.groupValues[2]
                val owner = records.singleOrNull { call.range.first in it.open..it.close }
                    ?: return@mapNotNull null
                val resourceIndex = owner.components.indexOfFirst { component ->
                    Regex("""\b${Regex.escape(resourceName)}\s*$""").containsMatchIn(component) &&
                        component.substringBeforeLast(resourceName).let {
                            it.contains("net.minecraft.server.packs.PackResources") ||
                                (source.contains("import net.minecraft.server.packs.PackResources;") &&
                                    Regex("""\bPackResources\b""").containsMatchIn(it))
                        }
                }
                if (resourceIndex < 0) return@mapNotNull null
                Registration(
                    owner = owner.name,
                    file = file,
                    callStart = call.range.first,
                    locationRange = arguments[0],
                    locationExpression = source.substring(arguments[0]).trim(),
                    resourceName = resourceName,
                    resourceIndex = resourceIndex,
                    compatPackage = supplierMatch.groupValues[1].substringBeforeLast('.')
                )
            }.toList()
        }

    private data class RecordDeclaration(
        val name: String,
        val components: List<String>,
        val open: Int,
        val close: Int
    )

    private fun collectRecords(source: String, masked: String): List<RecordDeclaration> =
        Regex("""\brecord\s+($ID)\s*\(""").findAll(masked).mapNotNull { declaration ->
            val parameterOpen = masked.indexOf('(', declaration.range.first)
            val parameterClose = if (parameterOpen >= 0) {
                JavaSourceText.matchingParen(masked, parameterOpen)
            } else {
                -1
            }
            if (parameterClose < 0) return@mapNotNull null
            val bodyOpen = masked.indexOf('{', parameterClose)
            if (bodyOpen < 0) return@mapNotNull null
            val bodyClose = JavaSourceText.matchingBrace(masked, bodyOpen)
            if (bodyClose < 0) return@mapNotNull null
            val components = argumentRanges(source, parameterOpen, parameterClose)
                ?.map { source.substring(it).trim() }
                ?: return@mapNotNull null
            RecordDeclaration(
                declaration.groupValues[1],
                components,
                bodyOpen,
                bodyClose
            )
        }.toList()

    private fun resolveConstruction(
        sources: Map<Path, String>,
        file: Path,
        before: Int,
        expression: String,
        expressionOffset: Int,
        candidates: Map<String, Candidate>
    ): Pair<Candidate, Construction>? {
        val direct = Regex("""^new\s+($ID)\s*\(""").find(expression)
        if (direct != null) {
            val candidate = candidates[direct.groupValues[1]] ?: return null
            val source = sources.getValue(file)
            val newOffset = if (expressionOffset in source.indices) {
                source.indexOf("new", expressionOffset)
            } else {
                -1
            }
            return if (newOffset >= 0) candidate to Construction(file, newOffset) else null
        }
        if (!Regex("""^$ID$""").matches(expression)) return null
        val source = sources.getValue(file)
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        return candidates.values.mapNotNull { candidate ->
            val declaration = Regex(
                """\b${Regex.escape(candidate.name)}\s+${Regex.escape(expression)}\s*=\s*new\s+${Regex.escape(candidate.name)}\s*\("""
            ).findAll(masked.substring(0, before)).lastOrNull() ?: return@mapNotNull null
            if (!sameLexicalBlock(masked, declaration.range.first, before)) {
                return@mapNotNull null
            }
            val newOffset = masked.indexOf("new", declaration.range.first)
            candidate to Construction(file, newOffset)
        }.singleOrNull()
    }

    private fun sameLexicalBlock(masked: String, from: Int, to: Int): Boolean {
        if (from !in 0 until to || to > masked.length) return false
        var depth = 0
        for (index in 0 until from) {
            when (masked[index]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        val startingDepth = depth
        for (index in from until to) {
            when (masked[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth < startingDepth) return false
                }
            }
        }
        return depth == startingDepth
    }

    private fun isCanonicalPackResourcesSource(source: String): Boolean =
        source.contains("net.minecraft.server.packs.PackResources") ||
            source.contains("import net.minecraft.server.packs.PackResources;")

    private fun argumentRanges(source: String, open: Int, close: Int): List<IntRange>? {
        if (open < 0 || close <= open || close > source.length) return null
        val body = source.substring(open + 1, close)
        if (body.isBlank()) return emptyList()
        val arguments = JavaSourceText.splitTopLevelArgs(body)
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (argument in arguments) {
            val trimmed = argument.trim()
            val relative = body.indexOf(trimmed, cursor)
            if (relative < 0) return null
            val start = open + 1 + relative
            ranges += start..(start + trimmed.length - 1)
            cursor = relative + trimmed.length
        }
        return ranges
    }

    private fun uniqueName(source: String, base: String): String {
        val names = Regex("""\b$ID\b""").findAll(JavaSourceText.maskCommentsAndLiterals(source))
            .map { it.value }
            .toSet()
        if (base !in names) return base
        var suffix = 2
        while ("$base$suffix" in names) suffix++
        return "$base$suffix"
    }

    private fun generatedInterface(packageName: String): String = """
package $packageName;

import net.minecraft.server.packs.PackLocationInfo;

/**
 * Generated bridge for PackResources whose exact location is supplied by a
 * RepositorySource only when the resources are opened.
 */
public interface $LOCATION_AWARE {
    void bindLocation(PackLocationInfo location);
}
""".trimStart()
}

internal fun StructuralRefactorPass.migratePackResourceLocations(
    projectDir: Path,
    dryRun: Boolean
): List<Change> = PackResourceLocations.migrate(projectDir, dryRun)
