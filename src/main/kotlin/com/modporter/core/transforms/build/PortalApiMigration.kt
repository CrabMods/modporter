package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.structural.LegacyPortalProbeTemplateDecision
import com.modporter.core.transforms.structural.LegacyPortalProbeTemplateKind
import com.modporter.core.transforms.structural.decideLegacyPortalProbeTemplate
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.toList

internal enum class PortalApiMigrationFault {
    NONE,
    AFTER_FIRST_PUBLISH
}

internal class PortalApiMigration(
    private val fault: PortalApiMigrationFault = PortalApiMigrationFault.NONE
) {
    private data class ParsedMethod(
        val name: String,
        val start: Int,
        val openParen: Int,
        val closeParen: Int,
        val openBrace: Int,
        val closeBrace: Int,
        val parameters: List<String>
    )

    private data class MethodTarget(
        val ownerFqn: String,
        val ownerSimpleName: String,
        val methodName: String,
        val oldArity: Int,
        val sourceFile: Path
    )

    private data class CandidateRewrite(
        val content: String,
        val targets: List<MethodTarget>,
        val count: Int
    )

    private data class PortalApiPublishEntry(
        val target: Path,
        val original: ByteArray,
        val staged: ByteArray?,
        val stagedNew: Path?,
        val stagedRollback: Path,
        val deletionTombstone: Path?
    )

    private data class ProbeNormalization(
        val file: Path,
        val kind: LegacyPortalProbeTemplateKind
    )

    private sealed interface ProbeNormalizationResult {
        data object NoHelper : ProbeNormalizationResult
        data object Refused : ProbeNormalizationResult
        data class Selected(val normalization: ProbeNormalization) : ProbeNormalizationResult
    }

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val javaFiles = Files.walk(srcDir).use { paths ->
            paths.filter { it.extension == "java" }.toList()
        }
        val originalBytesByFile = javaFiles.associateWith { Files.readAllBytes(it) }
        val sourceByFile = originalBytesByFile
            .mapValues { (_, bytes) -> String(bytes, StandardCharsets.UTF_8) }
            .toMutableMap()
        val originalByFile = sourceByFile.toMap()
        val targets = linkedSetOf<MethodTarget>()
        var providerRewriteCount = 0

        for ((file, source) in sourceByFile.toMap()) {
            val rewrite = rewritePortalProviderMethods(file, source)
            sourceByFile[file] = rewrite.content
            targets += rewrite.targets
            providerRewriteCount += rewrite.count
        }

        if (targets.isNotEmpty()) {
            val processedTargets = linkedSetOf<MethodTarget>()
            while (true) {
                val pending = targets.filterNot { it in processedTargets }
                if (pending.isEmpty()) break
                for (target in pending) {
                    for ((file, source) in sourceByFile.toMap()) {
                        sourceByFile[file] = rewriteCallsRemovingLastArgument(file, source, target)
                    }
                    processedTargets += target
                }

                var discovered = false
                for ((file, source) in sourceByFile.toMap()) {
                    val owner = javaTopLevelOwner(source) ?: continue
                    var modified = source
                    val methods = parseStaticMethods(modified).asReversed()
                    for (method in methods) {
                        if (method.parameters.size < 2) continue
                        val lastParameter = method.parameters.last()
                        val lastName = javaParameterName(lastParameter) ?: continue
                        val body = modified.substring(method.openBrace + 1, method.closeBrace)
                        val executableBody = maskJavaCommentsAndLiterals(body)
                        if (Regex("""\b${Regex.escape(lastName)}\b""")
                                .containsMatchIn(executableBody)
                        ) continue
                        if (!processedTargets.any { target -> bodyCallsTarget(executableBody, target) }) {
                            continue
                        }

                        val oldArity = method.parameters.size
                        val newParameters = method.parameters.dropLast(1).joinToString(", ")
                        modified = modified.replaceRange(
                            method.openParen + 1 until method.closeParen,
                            newParameters
                        )
                        val target = MethodTarget(owner.first, owner.second, method.name, oldArity, file)
                        if (targets.add(target)) discovered = true
                    }
                    sourceByFile[file] = modified
                }
                if (!discovered && targets.all { it in processedTargets }) break
            }

            for ((file, source) in sourceByFile.toMap()) {
                sourceByFile[file] = removeUnusedExplicitJavaImports(
                    cleanupMigratedPortalJavadocs(source)
                )
            }
        }

        val removedReflectionFiles = if (targets.isEmpty()) {
            emptyList()
        } else {
            removeUnreferencedReflectionAdapters(projectDir, sourceByFile, originalByFile)
        }
        val probeNormalization = when (val result = normalizeLegacyPortalProbe(sourceByFile)) {
            ProbeNormalizationResult.NoHelper -> null
            ProbeNormalizationResult.Refused -> return emptyList()
            is ProbeNormalizationResult.Selected -> result.normalization
        }
        val changes = mutableListOf<Change>()
        for ((file, content) in sourceByFile) {
            val original = originalByFile[file] ?: continue
            if (content == original) continue
            val isProbeNormalization = probeNormalization?.file == file
            changes += if (isProbeNormalization) {
                Change(
                    file = file,
                    line = 1,
                    description = "Close the generated legacy portal helper onto its native probe feature",
                    before = "full reflection-capable LegacyPortalProbe template",
                    after = "native-only existing-portal probe template",
                    confidence = Confidence.HIGH,
                    ruleId = "build-portal-native-probe-closure"
                )
            } else {
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate source-structured portal provider pipeline to Minecraft 1.21 Portal and DimensionTransition APIs",
                    before = "PortalInfo/ITeleporter provider pipeline with callback parameters",
                    after = "Portal.getPortalDestination with statically pruned obsolete callback parameters",
                    confidence = Confidence.HIGH,
                    ruleId = "build-portal-dimension-transition-pipeline"
                )
            }
        }
        for (file in removedReflectionFiles) {
            changes += Change(
                file = file,
                line = 1,
                description = "Remove an unreferenced reflection-only portal adapter after its behavior moved to the Portal API",
                before = "reflection-only compatibility adapter",
                after = "portal behavior handled by Portal.getPortalDestination",
                confidence = Confidence.HIGH,
                ruleId = "build-portal-unreferenced-reflection-adapter"
            )
        }
        if (changes.isEmpty()) return emptyList()
        if (targets.isNotEmpty()) check(providerRewriteCount > 0)
        if (!dryRun) {
            publishPortalApiChangesAtomically(
                srcDir,
                originalBytesByFile,
                sourceByFile,
                removedReflectionFiles.toSet()
            )
        }
        return changes
    }

    private fun normalizeLegacyPortalProbe(
        sourceByFile: MutableMap<Path, String>
    ): ProbeNormalizationResult {
        val candidates = sourceByFile.mapNotNull { (file, source) ->
            if (file.fileName.toString() != "LegacyPortalProbe.java") return@mapNotNull null
            val owner = javaTopLevelOwner(source) ?: return@mapNotNull null
            val fqn = owner.first
            if (!Regex(
                    """^com\.modporter\.generated\.[a-z0-9_]+\.compat\.LegacyPortalProbe$"""
                ).matches(fqn)
            ) return@mapNotNull null
            Triple(file, fqn.substringBeforeLast('.'), source)
        }
        if (candidates.isEmpty()) return ProbeNormalizationResult.NoHelper
        if (candidates.size != 1) return ProbeNormalizationResult.Refused
        val (file, packageName, existing) = candidates.single()
        return when (val decision = decideLegacyPortalProbeTemplate(
            sourceByFile,
            file,
            packageName,
            existing
        )) {
            is LegacyPortalProbeTemplateDecision.Refused -> ProbeNormalizationResult.Refused
            is LegacyPortalProbeTemplateDecision.Selected -> {
                sourceByFile[file] = decision.content
                ProbeNormalizationResult.Selected(ProbeNormalization(file, decision.kind))
            }
        }
    }

    private fun rewritePortalProviderMethods(file: Path, source: String): CandidateRewrite {
        val owner = javaTopLevelOwner(source) ?: return CandidateRewrite(source, emptyList(), 0)
        var result = source
        val targets = mutableListOf<MethodTarget>()
        var count = 0

        for (method in parseStaticMethods(source).asReversed()) {
            val lastParameter = method.parameters.lastOrNull() ?: continue
            val provider = Regex(
                """(?:java\.util\.function\.)?BiFunction\s*<\s*ServerLevel\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*,\s*(PortalInfo|DimensionTransition|\?)\s*>\s*([A-Za-z_$][\w$]*)"""
            ).matchEntire(lastParameter.trim()) ?: continue
            val levelName = method.parameters.firstNotNullOfOrNull { parameter ->
                Regex("""(?:[A-Za-z_$][\w$]*\.)*ServerLevel\s+([A-Za-z_$][\w$]*)""")
                    .matchEntire(parameter.trim())?.groupValues?.get(1)
            } ?: continue
            val probeType = provider.groupValues[1]
            val providerResult = provider.groupValues[2]
            val providerName = provider.groupValues[3]
            val methodSource = source.substring(method.start, method.closeBrace + 1)
            val executableMethod = maskJavaCommentsAndLiterals(methodSource)
            val transitionPattern = if (providerResult == "?") {
                Regex(
                    """\bPortalInfo\s+([A-Za-z_$][\w$]*)\s*=\s*LegacyPortalProbe\.portalInfo\(\s*${Regex.escape(providerName)}\.apply\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*\)\s*;"""
                )
            } else {
                Regex(
                    """\b(?:PortalInfo|DimensionTransition)\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(providerName)}\.apply\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*;"""
                )
            }
            val transition = transitionPattern.find(executableMethod) ?: continue
            val transitionName = transition.groupValues[1]
            val probeName = transition.groupValues[3]
            val portalState = Regex(
                """\bBlockState\s+([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.getBlockState\([^;]+\)\s*;"""
            ).find(executableMethod)?.groupValues?.get(1) ?: continue
            if (!Regex("""\b${Regex.escape(probeType.substringAfterLast('.'))}\s+${Regex.escape(probeName)}\b""")
                    .containsMatchIn(executableMethod)) continue
            val serverName = Regex("""\bMinecraftServer\s+([A-Za-z_$][\w$]*)\s*=""")
                .find(executableMethod)?.groupValues?.get(1)

            var migratedMethod = methodSource
            val assignmentPattern = if (providerResult == "?") {
                Regex(
                    """\bPortalInfo\s+${Regex.escape(transitionName)}\s*=\s*LegacyPortalProbe\.portalInfo\(\s*${Regex.escape(providerName)}\.apply\(\s*[A-Za-z_$][\w$]*\s*,\s*${Regex.escape(probeName)}\s*\)\s*\)\s*;"""
                )
            } else {
                Regex(
                    """\b(?:PortalInfo|DimensionTransition)\s+${Regex.escape(transitionName)}\s*=\s*${Regex.escape(providerName)}\.apply\(\s*[A-Za-z_$][\w$]*\s*,\s*${Regex.escape(probeName)}\s*\)\s*;"""
                )
            }
            migratedMethod = replaceMaskedRegex(
                migratedMethod,
                assignmentPattern
            ) {
                "if (!($portalState.getBlock() instanceof Portal modporterPortal))\n" +
                    "\t\t\treturn null;\n\n" +
                    "\t\tDimensionTransition $transitionName = modporterPortal.getPortalDestination(" +
                    "$levelName, $probeName, $probeName.blockPosition());"
            }
            migratedMethod = replaceMaskedRegex(
                migratedMethod,
                Regex("""(?m)^[ \t]*${Regex.escape(probeName)}\.setPortalEntrancePos\(\)\s*;[ \t]*(?:\r?\n)?""")
            ) { "" }
            migratedMethod = replaceMaskedRegex(
                migratedMethod,
                Regex("""\b${Regex.escape(transitionName)}\.pos\b(?!\s*\()""")
            ) { "$transitionName.pos()" }
            if (serverName != null) {
                migratedMethod = replaceMaskedRegex(
                    migratedMethod,
                    Regex(
                        """if\s*\(\s*${Regex.escape(transitionName)}\s*==\s*null\s*\)\s*return\s+null\s*;"""
                    )
                ) {
                    "if ($transitionName == null)\n" +
                        "\t\t\treturn null;\n\n" +
                        "\t\tif (!$serverName.isLevelEnabled($transitionName.newLevel()))\n" +
                        "\t\t\treturn null;"
                }
            }

            val relativeOpenParen = method.openParen - method.start
            val relativeCloseParen = method.closeParen - method.start
            migratedMethod = migratedMethod.replaceRange(
                relativeOpenParen + 1 until relativeCloseParen,
                method.parameters.dropLast(1).joinToString(", ")
            )
            result = result.replaceRange(method.start..method.closeBrace, migratedMethod)
            targets += MethodTarget(owner.first, owner.second, method.name, method.parameters.size, file)
            count++
        }

        if (count > 0) {
            result = ensureJavaImport(result, "net.minecraft.world.level.block.Portal")
            result = ensureJavaImport(result, "net.minecraft.world.level.portal.DimensionTransition")
            result = removeJavaImport(result, "net.minecraft.world.level.portal.PortalInfo")
        }
        return CandidateRewrite(result, targets, count)
    }

    private fun rewriteCallsRemovingLastArgument(file: Path, source: String, target: MethodTarget): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val tokens = linkedSetOf(
            "${target.ownerFqn}.${target.methodName}",
            "${target.ownerSimpleName}.${target.methodName}"
        )
        if (file == target.sourceFile) tokens += target.methodName
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val occupied = mutableListOf<IntRange>()

        for (token in tokens.sortedByDescending { it.length }) {
            var searchFrom = 0
            while (true) {
                val callStart = executableCode.indexOf(token, searchFrom)
                if (callStart < 0) break
                val tokenEnd = callStart + token.length
                if ((callStart > 0 && executableCode[callStart - 1].let { it.isJavaIdentifierPart() || it == '.' }) ||
                    executableCode.getOrNull(tokenEnd)?.let { it.isJavaIdentifierPart() } == true ||
                    occupied.any { callStart in it }) {
                    searchFrom = tokenEnd
                    continue
                }
                val openParen = executableCode.indexOf('(', tokenEnd)
                if (openParen < 0 || executableCode.substring(tokenEnd, openParen).isNotBlank()) {
                    searchFrom = tokenEnd
                    continue
                }
                val closeParen = findMatching(executableCode, openParen, '(', ')')
                if (closeParen < 0) break
                val after = executableCode.substring(closeParen + 1).firstOrNull { !it.isWhitespace() }
                if (after == '{') {
                    searchFrom = closeParen + 1
                    continue
                }
                val args = splitTopLevel(source.substring(openParen + 1, closeParen))
                if (args.size == target.oldArity) {
                    val replacement = "$token(${args.dropLast(1).joinToString(", ") { it.trim() }})"
                    val range = callStart..closeParen
                    replacements += range to replacement
                    occupied += range
                }
                searchFrom = closeParen + 1
            }
        }
        var result = source
        replacements.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            result = result.replaceRange(range, replacement)
        }
        return result
    }

    private fun bodyCallsTarget(body: String, target: MethodTarget): Boolean {
        val qualified = listOf(
            "${target.ownerFqn}.${target.methodName}",
            "${target.ownerSimpleName}.${target.methodName}"
        ).any { body.contains("$it(") }
        return qualified || body.contains("${target.methodName}(")
    }

    private fun removeUnreferencedReflectionAdapters(
        projectDir: Path,
        sourceByFile: MutableMap<Path, String>,
        originalByFile: Map<Path, String>
    ): List<Path> {
        val removed = mutableListOf<Path>()
        val resources = listOf(projectDir.resolve("src/main/resources"), projectDir.resolve("src/generated/resources"))
            .filter { it.exists() }
            .flatMap { root -> Files.walk(root).filter { Files.isRegularFile(it) }.toList() }

        for ((file, source) in sourceByFile.toMap()) {
            val executable = maskJavaCommentsAndLiterals(source)
            if (!LegacyReflectionSyntax.containsForbiddenApi(executable)) continue
            val owner = javaTopLevelOwner(source) ?: continue
            val classHeader = Regex("""\bpublic\s+(?:final\s+)?class\s+${Regex.escape(owner.second)}\b""")
                .find(executable) ?: continue
            val annotationRegion = executable.substring(0, classHeader.range.first)
                .substringAfterLast('}')
                .substringAfterLast(';')
            if ('@' in annotationRegion) continue
            val referencedBeforeMigration = originalByFile.any { (otherFile, otherSource) ->
                otherFile != file && Regex("""\b${Regex.escape(owner.second)}\b""")
                    .containsMatchIn(maskJavaCommentsAndLiterals(otherSource))
            }
            if (!referencedBeforeMigration) continue
            val referencedBySource = sourceByFile.any { (otherFile, otherSource) ->
                otherFile != file && Regex("""\b${Regex.escape(owner.second)}\b""")
                    .containsMatchIn(maskJavaCommentsAndLiterals(otherSource))
            }
            if (referencedBySource) continue
            val referencedByResource = resources.any { resource ->
                runCatching {
                    val text = resource.readText()
                    text.contains(owner.first) || text.contains(owner.second)
                }.getOrDefault(false)
            }
            if (referencedByResource) continue

            sourceByFile.remove(file)
            removed.add(file)
        }
        return removed
    }

    private fun publishPortalApiChangesAtomically(
        sourceRoot: Path,
        originalBytesByFile: Map<Path, ByteArray>,
        sourceByFile: Map<Path, String>,
        removedFiles: Set<Path>
    ) {
        val modifiedFiles = sourceByFile.filter { (file, source) ->
            val original = originalBytesByFile[file] ?: return@filter false
            !source.toByteArray(StandardCharsets.UTF_8).contentEquals(original)
        }.keys
        val publishFiles = (modifiedFiles + removedFiles).sorted()
        val entries = mutableListOf<PortalApiPublishEntry>()
        verifyPortalApiSourceSnapshot(sourceRoot, originalBytesByFile, emptyList())
        try {
            for (target in publishFiles) {
                val original = originalBytesByFile[target]
                    ?: error("Portal API publication lost its original file: $target")
                require(Files.isRegularFile(target)) {
                    "Portal API publish target disappeared before staging: $target"
                }
                require(Files.readAllBytes(target).contentEquals(original)) {
                    "Portal API publish target changed before staging: $target"
                }
                val staged = if (target in removedFiles) {
                    null
                } else {
                    sourceByFile.getValue(target).toByteArray(StandardCharsets.UTF_8)
                }
                var stagedNew: Path? = null
                var rollback: Path? = null
                try {
                    stagedNew = staged?.let { bytes ->
                        Files.createTempFile(target.parent, ".modporter-portal-api-new-", ".tmp").also {
                            Files.write(it, bytes)
                            require(Files.readAllBytes(it).contentEquals(bytes)) {
                                "Portal API staged bytes changed: $target"
                            }
                        }
                    }
                    val rollbackPath = Files.createTempFile(
                        target.parent,
                        ".modporter-portal-api-rollback-",
                        ".tmp"
                    )
                    rollback = rollbackPath
                    val tombstone = if (staged == null) {
                        Files.delete(rollbackPath)
                        rollbackPath
                    } else {
                        Files.write(rollbackPath, original)
                        require(Files.readAllBytes(rollbackPath).contentEquals(original)) {
                            "Portal API rollback bytes changed while staging: $target"
                        }
                        null
                    }
                    entries += PortalApiPublishEntry(
                        target,
                        original,
                        staged,
                        stagedNew,
                        rollbackPath,
                        tombstone
                    )
                } catch (failure: Throwable) {
                    runCatching { stagedNew?.let { Files.deleteIfExists(it) } }
                    runCatching { rollback?.let { Files.deleteIfExists(it) } }
                    throw failure
                }
            }
            verifyPortalApiSourceSnapshot(sourceRoot, originalBytesByFile, emptyList())
        } catch (failure: Throwable) {
            cleanupPortalApiStage(entries)
            throw failure
        }

        val published = mutableListOf<PortalApiPublishEntry>()
        try {
            for (entry in entries) {
                verifyPortalApiSourceSnapshot(sourceRoot, originalBytesByFile, published)
                require(Files.isRegularFile(entry.target)) {
                    "Portal API publish target disappeared after staging: ${entry.target}"
                }
                require(Files.readAllBytes(entry.target).contentEquals(entry.original)) {
                    "Portal API publish target changed after staging: ${entry.target}"
                }
                if (entry.staged == null) {
                    atomicPortalApiMove(entry.target, entry.deletionTombstone!!)
                } else {
                    atomicPortalApiMove(entry.stagedNew!!, entry.target)
                }
                published += entry
                verifyPortalApiSourceSnapshot(sourceRoot, originalBytesByFile, published)
                if (published.size == 1 && fault == PortalApiMigrationFault.AFTER_FIRST_PUBLISH) {
                    error("Injected portal API publication failure after first atomic publish")
                }
            }
        } catch (failure: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            for (entry in published.asReversed()) {
                runCatching {
                    if (entry.staged == null) {
                        require(!Files.exists(entry.target)) {
                            "Deleted portal API target reappeared before rollback: ${entry.target}"
                        }
                        atomicPortalApiMove(entry.deletionTombstone!!, entry.target)
                    } else {
                        require(Files.readAllBytes(entry.target).contentEquals(entry.staged)) {
                            "Portal API target changed before rollback: ${entry.target}"
                        }
                        atomicPortalApiMove(entry.stagedRollback, entry.target)
                    }
                    require(Files.readAllBytes(entry.target).contentEquals(entry.original)) {
                        "Portal API rollback bytes changed: ${entry.target}"
                    }
                }.exceptionOrNull()?.let(rollbackFailures::add)
            }
            cleanupPortalApiStage(entries, preserveRecovery = rollbackFailures.isNotEmpty())
            val wrapped = IllegalStateException(
                if (rollbackFailures.isEmpty()) {
                    "Portal API publication failed; all published entries were rolled back"
                } else {
                    "Portal API publication failed and aggregate rollback was incomplete; " +
                        "rollback artifacts were preserved beside their targets"
                },
                failure
            )
            rollbackFailures.forEach(wrapped::addSuppressed)
            throw wrapped
        }
        cleanupPortalApiStage(entries)
    }

    private fun verifyPortalApiSourceSnapshot(
        sourceRoot: Path,
        originalBytesByFile: Map<Path, ByteArray>,
        published: List<PortalApiPublishEntry>
    ) {
        val publishedByTarget = published.associateBy { it.target }
        val expectedFiles = originalBytesByFile.keys.filterTo(linkedSetOf()) { file ->
            publishedByTarget[file]?.staged != null || file !in publishedByTarget
        }
        val currentFiles = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }.toList().toSet()
        }
        require(currentFiles == expectedFiles) {
            "Portal API Java source set changed during publication; added=" +
                "${(currentFiles - expectedFiles).sorted()}, removed=${(expectedFiles - currentFiles).sorted()}"
        }
        for ((file, original) in originalBytesByFile) {
            val publishedEntry = publishedByTarget[file]
            val expected = when {
                publishedEntry == null -> original
                publishedEntry.staged == null -> null
                else -> publishedEntry.staged
            }
            if (expected == null) {
                require(!Files.exists(file)) {
                    "Portal API deleted source reappeared during publication: $file"
                }
            } else {
                require(Files.isRegularFile(file)) {
                    "Portal API source disappeared during publication: $file"
                }
                require(Files.readAllBytes(file).contentEquals(expected)) {
                    "Portal API source bytes changed during publication: $file"
                }
            }
        }
    }

    private fun atomicPortalApiMove(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException(
                "Atomic portal API publication is unavailable for $target",
                unsupported
            )
        }
    }

    private fun cleanupPortalApiStage(
        entries: List<PortalApiPublishEntry>,
        preserveRecovery: Boolean = false
    ) {
        for (entry in entries) {
            runCatching { entry.stagedNew?.let { Files.deleteIfExists(it) } }
            if (!preserveRecovery) {
                runCatching { Files.deleteIfExists(entry.stagedRollback) }
                runCatching { entry.deletionTombstone?.let { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun parseStaticMethods(source: String): List<ParsedMethod> {
        val executable = maskJavaCommentsAndLiterals(source)
        val header = Regex(
            """(?m)^[ \t]*(?:(?:public|protected|private)\s+)?static\s+[^;={}\r\n]+?\s+([A-Za-z_$][\w$]*)\s*\("""
        )
        return header.findAll(executable).mapNotNull { match ->
            val openParen = executable.indexOf('(', match.range.first)
            val closeParen = if (openParen >= 0) findMatching(executable, openParen, '(', ')') else -1
            if (closeParen <= openParen) return@mapNotNull null
            val openBrace = executable.indexOf('{', closeParen + 1)
            if (openBrace < 0 || executable.substring(closeParen + 1, openBrace).isNotBlank()) return@mapNotNull null
            val closeBrace = findMatching(executable, openBrace, '{', '}')
            if (closeBrace <= openBrace) return@mapNotNull null
            ParsedMethod(
                name = match.groupValues[1],
                start = match.range.first,
                openParen = openParen,
                closeParen = closeParen,
                openBrace = openBrace,
                closeBrace = closeBrace,
                parameters = splitTopLevel(source.substring(openParen + 1, closeParen))
            )
        }.toList()
    }

    private fun javaTopLevelOwner(source: String): Pair<String, String>? {
        val executable = maskJavaCommentsAndLiterals(source)
        val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$.]*)\s*;""")
            .find(executable)?.groupValues?.get(1) ?: return null
        val simpleName = Regex("""\bpublic\s+(?:abstract\s+|final\s+)?(?:class|interface|record)\s+([A-Za-z_$][\w$]*)\b""")
            .find(executable)?.groupValues?.get(1) ?: return null
        return "$packageName.$simpleName" to simpleName
    }

    private fun javaParameterName(parameter: String): String? =
        Regex("""([A-Za-z_$][\w$]*)\s*$""").find(parameter.trim())?.groupValues?.get(1)

    private fun removeUnusedExplicitJavaImports(source: String): String {
        val importPattern = Regex("""(?m)^[ \t]*import\s+(?!static\b)([A-Za-z_$][\w$.]*)\s*;[ \t]*(?:\r?\n)?""")
        val imports = importPattern.findAll(source).toList()
        if (imports.isEmpty()) return source
        val withoutImports = importPattern.replace(source, "")
        val executableBody = maskJavaCommentsAndLiterals(withoutImports)
        var result = source
        imports.asReversed().forEach { match ->
            val simpleName = match.groupValues[1].substringAfterLast('.')
            if (!Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(executableBody)) {
                result = result.replaceRange(match.range, "")
            }
        }
        return result
    }

    private fun cleanupMigratedPortalJavadocs(source: String): String {
        val executable = maskJavaCommentsAndLiterals(source)
        var result = Regex("""(?m)^[ \t]*\*\s*@param\s+([A-Za-z_$][\w$]*)\b[^\r\n]*(?:\r?\n)?""")
            .replace(source) { match ->
                val parameterName = match.groupValues[1]
                if (Regex("""\b${Regex.escape(parameterName)}\b""").containsMatchIn(executable)) {
                    match.value
                } else {
                    ""
                }
            }
        result = result.replace(
            "{@link ITeleporter}",
            "{@link net.minecraft.world.level.block.Portal}"
        )
        result = result.replace(
            "{@link PortalInfo}",
            "{@link net.minecraft.world.level.portal.DimensionTransition}"
        )
        return result
    }

    private fun ensureJavaImport(source: String, importName: String): String {
        if (Regex("""(?m)^\s*import\s+${Regex.escape(importName)}\s*;""").containsMatchIn(source)) return source
        val packageMatch = Regex("""(?m)^\s*package\s+[^;]+;""").find(source) ?: return source
        val insertion = System.lineSeparator() + System.lineSeparator() + "import $importName;"
        return source.substring(0, packageMatch.range.last + 1) + insertion + source.substring(packageMatch.range.last + 1)
    }

    private fun removeJavaImport(source: String, importName: String): String =
        Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)}\s*;[ \t]*(?:\r?\n)?""").replace(source, "")

    private fun replaceMaskedRegex(source: String, pattern: Regex, replacement: () -> String): String {
        val matches = pattern.findAll(maskJavaCommentsAndLiterals(source)).toList()
        var result = source
        matches.asReversed().forEach { match -> result = result.replaceRange(match.range, replacement()) }
        return result
    }

    private fun splitTopLevel(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var angleDepth = 0
        var inString = false
        var escaped = false
        value.forEachIndexed { index, char ->
            if (inString) {
                if (char == '"' && !escaped) inString = false
                escaped = char == '\\' && !escaped
                if (char != '\\') escaped = false
                return@forEachIndexed
            }
            when (char) {
                '"' -> inString = true
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                ',' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 && angleDepth == 0) {
                    result += value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += value.substring(start).trim()
        return result.filter { it.isNotEmpty() }
    }

    private fun findMatching(source: String, openIndex: Int, open: Char, close: Char): Int {
        if (source.getOrNull(openIndex) != open) return -1
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        fun mask(start: Int, endExclusive: Int) {
            for (position in start until endExclusive.coerceAtMost(chars.size)) {
                if (chars[position] != '\n' && chars[position] != '\r') chars[position] = ' '
            }
        }
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                    mask(index, end)
                    index = end
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                    mask(index, end)
                    index = end
                }
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3).let { if (it < 0) source.length else it + 3 }
                    mask(index, end)
                    index = end
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    var end = index + 1
                    var escaped = false
                    while (end < source.length) {
                        val char = source[end]
                        if (char == quote && !escaped) {
                            end++
                            break
                        }
                        escaped = char == '\\' && !escaped
                        if (char != '\\') escaped = false
                        end++
                    }
                    mask(index, end)
                    index = end
                }
                else -> index++
            }
        }
        return String(chars)
    }
}
