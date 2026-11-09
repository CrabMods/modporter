package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.core.pipeline.Change
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Comparator
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

private const val TRANSACTION_LEGACY_PORTAL_INFO =
    "net.minecraft.world.level.portal.PortalInfo"
private const val TRANSACTION_FORGE_TELEPORTER =
    "net.minecraftforge.common.util.ITeleporter"
private const val TRANSACTION_NEOFORGE_TELEPORTER =
    "net.neoforged.neoforge.common.util.ITeleporter"
private val TRANSACTION_GENERATED_HELPER = Regex(
    """^com\.modporter\.generated\.[a-z0-9_]+\.compat\.(?:LegacyPortalProbe|PortalInfo)$"""
)

internal enum class PortalTransactionFault {
    NONE,
    AFTER_FIRST_TEMP_MIGRATION,
    HELPER_LOAD,
    AFTER_FIRST_PRESTAGE,
    AFTER_FIRST_PUBLISH
}

private data class PortalCapturedFile(
    val bytes: ByteArray,
    val sha256: String
)

private data class PortalProjectSnapshot(
    val files: Map<Path, PortalCapturedFile>,
    val javaFiles: Set<Path>,
    val metadataFiles: Set<Path>
)

private data class PortalHelperTemplates(
    val probe: String,
    val portalInfo: String
)

private data class PortalPublishEntry(
    val relative: Path,
    val target: Path,
    val original: PortalCapturedFile?,
    val staged: PortalCapturedFile,
    val stagedNew: Path,
    val stagedRollback: Path?
)

private data class PortalSourceIdentity(
    val packageName: String,
    val declaredTypes: Set<String>,
    val referencedTypes: Set<String>,
    val explicitImports: Set<String>
)

private class PortalRollbackIncompleteException(
    message: String,
    cause: Throwable
) : RuntimeException(message, cause)

/**
 * Runs the complete legacy portal migration against an owned project snapshot. The transaction
 * activates only when an exact probe override or reflected MethodHandle family produces a real
 * non-generated source edit. Ordinary PortalInfo and ITeleporter sources remain outside this lane.
 * The original project is not written until the admitted family, helper loads, parsing,
 * canonical-use gates, metadata checks, and original-byte hash checks have succeeded.
 *
 * Root integration must call this function once before broad 1.21/common API migration and must
 * not call any of the three component migrations separately.
 */
internal fun StructuralRefactorPass.migrateLegacyPortalFamilyTransaction(
    project: Path,
    dryRun: Boolean,
    fault: PortalTransactionFault = PortalTransactionFault.NONE
): List<Change> {
    val originalProject = project.toRealPath()
    require(Files.isDirectory(originalProject)) {
        "Legacy portal transaction requires an existing project directory: $originalProject"
    }
    val transactionParent = originalProject.parent
        ?: error("Legacy portal transaction requires a project parent: $originalProject")
    val transactionRoot = Files.createTempDirectory(
        transactionParent,
        ".modporter-portal-transaction-"
    ).toAbsolutePath().normalize()
    require(transactionRoot.parent == transactionParent) {
        "Portal transaction directory escaped the project volume: $transactionRoot"
    }

    var preserveTransactionRoot = false
    try {
        val original = capturePortalProjectSnapshot(originalProject)
        val tempProject = transactionRoot.resolve("project")
        copyPortalSnapshot(original, tempProject)

        val tempChanges = mutableListOf<Change>()
        tempChanges += migrateLegacyPortalMethodHandleReturns(tempProject, dryRun = false)
        val afterMethodHandle = capturePortalProjectSnapshot(tempProject)
        val methodHandleChanged = changedPortalJavaPaths(original, afterMethodHandle)
        if (fault == PortalTransactionFault.AFTER_FIRST_TEMP_MIGRATION) {
            error("Injected portal transaction failure after first temporary migration")
        }
        tempChanges += migrateLegacyPortalProbeTransitions(tempProject, dryRun = false)
        val afterExactPlanners = capturePortalProjectSnapshot(tempProject)
        val probeChanged = changedPortalJavaPaths(afterMethodHandle, afterExactPlanners)
        val admittedFamily = selectActivatedPortalFamily(
            tempProject,
            afterExactPlanners,
            methodHandleChanged,
            probeChanged
        )
        if (admittedFamily.isEmpty()) {
            verifyPortalSnapshotUnchanged(originalProject, original)
            return emptyList()
        }

        restorePortalSourcesOutsideFamily(
            original,
            tempProject,
            afterExactPlanners,
            admittedFamily
        )
        val templates = loadPortalHelperTemplates(fault)
        tempChanges += migrateLegacyPortalInfoValues(tempProject, dryRun = false)
        restorePortalSourcesOutsideFamily(
            original,
            tempProject,
            capturePortalProjectSnapshot(tempProject),
            admittedFamily
        )

        val migrated = capturePortalProjectSnapshot(tempProject)
        validatePortalMetadataUnchanged(original, migrated)
        validateNoPortalSourceDeletion(original, migrated)
        val changedJava = changedPortalJavaPaths(original, migrated)
        validatePortalFamilyBoundary(original, migrated, admittedFamily, changedJava)
        validateChangedPortalJavaParses(tempProject, migrated, changedJava)
        validateGeneratedPortalHelpers(migrated, templates, admittedFamily + changedJava)
        validateLegacyPortalCanonicalUses(tempProject, migrated, admittedFamily)
        verifyPortalSnapshotUnchanged(originalProject, original)

        val remappedChanges = remapPortalChanges(
            tempChanges,
            tempProject,
            originalProject,
            changedJava
        )
        if (dryRun || changedJava.isEmpty()) {
            verifyPortalSnapshotUnchanged(originalProject, original)
            return remappedChanges
        }

        val publishEntries = prestagePortalPublish(
            originalProject,
            original,
            migrated,
            changedJava,
            transactionRoot.resolve("publish"),
            fault
        )
        verifyPortalSnapshotUnchanged(originalProject, original)
        publishPortalEntriesAtomically(
            originalProject,
            original,
            publishEntries,
            fault
        )
        return remappedChanges
    } catch (rollbackFailure: PortalRollbackIncompleteException) {
        preserveTransactionRoot = true
        throw IllegalStateException(
            "${rollbackFailure.message}; rollback evidence preserved at $transactionRoot",
            rollbackFailure
        )
    } finally {
        if (preserveTransactionRoot) {
            logger.warn {
                "Preserving portal transaction rollback evidence after incomplete rollback: " +
                    transactionRoot
            }
        } else {
            runCatching {
                deleteOwnedPortalTransactionTree(transactionRoot, transactionParent)
            }.onFailure { cleanupFailure ->
                logger.warn {
                    "Could not remove owned portal transaction directory $transactionRoot: " +
                        cleanupFailure.message
                }
            }
        }
    }
}

private fun StructuralRefactorPass.capturePortalProjectSnapshot(
    project: Path
): PortalProjectSnapshot {
    val files = linkedMapOf<Path, PortalCapturedFile>()
    val javaFiles = linkedSetOf<Path>()
    val metadataFiles = linkedSetOf<Path>()
    val javaRoot = project.resolve("src/main/java")
    if (Files.isDirectory(javaRoot)) {
        walkJavaSourceRoots(javaRoot).use { paths ->
            paths.filter { it.extension == "java" }
                .sorted()
                .forEach { file ->
                    require(!Files.isSymbolicLink(file)) {
                        "Portal transaction refuses a symbolic-link Java source: $file"
                    }
                    val relative = project.relativize(file.toAbsolutePath().normalize())
                    val captured = capturePortalFile(file)
                    check(files.put(relative, captured) == null) {
                        "Duplicate portal transaction path: $relative"
                    }
                    javaFiles.add(relative)
                }
        }
    }

    listOf(
        project.resolve("gradle.properties"),
        project.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
        project.resolve("src/main/resources/META-INF/mods.toml")
    ).filter { Files.isRegularFile(it) }
        .forEach { file ->
            require(!Files.isSymbolicLink(file)) {
                "Portal transaction refuses symbolic-link metadata: $file"
            }
            val relative = project.relativize(file.toAbsolutePath().normalize())
            check(files.put(relative, capturePortalFile(file)) == null) {
                "Duplicate portal transaction metadata path: $relative"
            }
            metadataFiles.add(relative)
        }
    return PortalProjectSnapshot(files, javaFiles, metadataFiles)
}

private fun capturePortalFile(file: Path): PortalCapturedFile {
    val bytes = file.readBytes()
    return PortalCapturedFile(bytes, portalSha256(bytes))
}

private fun portalSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun copyPortalSnapshot(snapshot: PortalProjectSnapshot, targetProject: Path) {
    for ((relative, captured) in snapshot.files) {
        val target = targetProject.resolve(relative).normalize()
        require(target.startsWith(targetProject)) {
            "Portal snapshot path escaped its temporary project: $relative"
        }
        target.parent.createDirectories()
        target.writeBytes(captured.bytes)
        val copied = capturePortalFile(target)
        require(
            copied.sha256 == captured.sha256 &&
                copied.bytes.contentEquals(captured.bytes)
        ) {
            "Portal snapshot copy did not preserve exact bytes: $relative"
        }
    }
}

private fun StructuralRefactorPass.loadPortalHelperTemplates(
    fault: PortalTransactionFault
): PortalHelperTemplates {
    if (fault == PortalTransactionFault.HELPER_LOAD) {
        error("Injected portal helper resource load failure")
    }
    fun load(name: String): String {
        val resource = javaClass.getResource(name)
            ?: error("Missing required portal helper resource: $name")
        return String(resource.readBytes(), StandardCharsets.UTF_8)
    }
    return PortalHelperTemplates(
        load("/compat/LegacyPortalProbe.java.template"),
        load("/compat/PortalInfo.java.template")
    )
}

private fun validatePortalMetadataUnchanged(
    original: PortalProjectSnapshot,
    migrated: PortalProjectSnapshot
) {
    require(migrated.metadataFiles == original.metadataFiles) {
        "Portal temporary migration changed the metadata file set"
    }
    for (relative in original.metadataFiles) {
        val before = original.files.getValue(relative)
        val after = migrated.files.getValue(relative)
        require(before.sha256 == after.sha256 && before.bytes.contentEquals(after.bytes)) {
            "Portal temporary migration changed project metadata: $relative"
        }
    }
}

private fun validateNoPortalSourceDeletion(
    original: PortalProjectSnapshot,
    migrated: PortalProjectSnapshot
) {
    val deleted = original.javaFiles - migrated.javaFiles
    require(deleted.isEmpty()) {
        "Portal temporary migration deleted Java sources: ${deleted.sorted()}"
    }
}

private fun changedPortalJavaPaths(
    original: PortalProjectSnapshot,
    migrated: PortalProjectSnapshot
): Set<Path> = migrated.javaFiles.filterTo(linkedSetOf()) { relative ->
    val before = original.files[relative]
    val after = migrated.files.getValue(relative)
    before == null ||
        before.sha256 != after.sha256 ||
        !before.bytes.contentEquals(after.bytes)
}

private fun selectActivatedPortalFamily(
    tempProject: Path,
    planned: PortalProjectSnapshot,
    methodHandleChanged: Set<Path>,
    probeChanged: Set<Path>
): Set<Path> {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    fun parsed(relative: Path): CompilationUnit? {
        if (isGeneratedPortalHelperPath(relative)) return null
        val source = String(planned.files.getValue(relative).bytes, StandardCharsets.UTF_8)
        val result = parser.parse(source)
        return result.result.orElse(null)
    }

    val admitted = linkedSetOf<Path>()
    val methodSources = methodHandleChanged.filterTo(linkedSetOf()) {
        !isGeneratedPortalHelperPath(it)
    }
    val methodAnchors = methodSources.filter { relative ->
        parsed(relative)?.findAll(MethodCallExpr::class.java)?.any { call ->
            call.nameAsString == "findVirtual" &&
                (call.scope.orElse(null) as? NameExpr)?.nameAsString == "LegacyPortalProbe"
        } == true
    }
    if (methodAnchors.isNotEmpty()) {
        require(methodAnchors.size == 1) {
            "MethodHandle portal planner produced more than one exact source anchor: " +
                methodAnchors.sorted()
        }
        admitted += methodSources
    }

    val probeSources = probeChanged.filterTo(linkedSetOf()) {
        !isGeneratedPortalHelperPath(it)
    }
    val probeAnchors = probeSources.filterTo(linkedSetOf()) { relative ->
        parsed(relative)?.findAll(MethodCallExpr::class.java)?.any { call ->
            call.nameAsString == "findDimensionEntryPoint" &&
                (call.scope.orElse(null) as? NameExpr)?.nameAsString == "LegacyPortalProbe" &&
                call.arguments.size == 3 &&
                call.arguments[0] is ThisExpr &&
                (call.arguments[1] as? NameExpr)?.nameAsString == "portalEntrancePos"
        } == true
    }
    if (probeAnchors.isNotEmpty()) {
        admitted += closePortalSourceFamily(tempProject, planned, probeSources, probeAnchors)
    }
    return admitted
}

private fun closePortalSourceFamily(
    tempProject: Path,
    snapshot: PortalProjectSnapshot,
    candidates: Set<Path>,
    anchors: Set<Path>
): Set<Path> {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val identities = candidates.associateWith { relative ->
        val source = String(snapshot.files.getValue(relative).bytes, StandardCharsets.UTF_8)
        val result = parser.parse(source)
        require(result.isSuccessful && result.result.isPresent) {
            "Exact portal planner changed an unparsed source: ${tempProject.resolve(relative)}"
        }
        portalSourceIdentity(result.result.get())
    }
    val adjacency = candidates.associateWith { linkedSetOf<Path>() }
    val ordered = candidates.sorted()
    for (leftIndex in ordered.indices) {
        for (rightIndex in leftIndex + 1 until ordered.size) {
            val left = ordered[leftIndex]
            val right = ordered[rightIndex]
            val leftIdentity = identities.getValue(left)
            val rightIdentity = identities.getValue(right)
            if (
                portalSourceReferences(leftIdentity, rightIdentity) ||
                portalSourceReferences(rightIdentity, leftIdentity)
            ) {
                adjacency.getValue(left).add(right)
                adjacency.getValue(right).add(left)
            }
        }
    }

    val closed = linkedSetOf<Path>()
    val pending = ArrayDeque<Path>()
    anchors.sorted().forEach {
        closed.add(it)
        pending.addLast(it)
    }
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        for (next in adjacency.getValue(current).sorted()) {
            if (closed.add(next)) pending.addLast(next)
        }
    }
    return closed
}

private fun portalSourceIdentity(unit: CompilationUnit): PortalSourceIdentity {
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val declaredTypes = unit.types.mapTo(linkedSetOf()) { type ->
        listOf(packageName, type.nameAsString)
            .filter(String::isNotBlank)
            .joinToString(".")
    }
    val referencedTypes = unit.findAll(ClassOrInterfaceType::class.java)
        .mapTo(linkedSetOf()) { it.nameWithScope }
    val explicitImports = unit.imports.filter {
        !it.isStatic && !it.isAsterisk
    }.mapTo(linkedSetOf()) { it.nameAsString }
    return PortalSourceIdentity(
        packageName,
        declaredTypes,
        referencedTypes,
        explicitImports
    )
}

private fun portalSourceReferences(
    source: PortalSourceIdentity,
    target: PortalSourceIdentity
): Boolean = target.declaredTypes.any { targetFqn ->
    val targetSimple = targetFqn.substringAfterLast('.')
    targetFqn in source.referencedTypes ||
        (
            targetFqn in source.explicitImports &&
                targetSimple in source.referencedTypes
            ) ||
        (
            source.packageName == target.packageName &&
                targetSimple in source.referencedTypes
            )
}

private fun restorePortalSourcesOutsideFamily(
    original: PortalProjectSnapshot,
    tempProject: Path,
    current: PortalProjectSnapshot,
    admittedFamily: Set<Path>
) {
    for (relative in current.javaFiles) {
        if (relative in admittedFamily || isGeneratedPortalHelperPath(relative)) continue
        val target = tempProject.resolve(relative).normalize()
        val captured = original.files[relative]
        if (captured == null) {
            Files.deleteIfExists(target)
            prunePortalEmptyParents(target.parent, tempProject)
        } else {
            target.writeBytes(captured.bytes)
        }
    }
}

private fun validatePortalFamilyBoundary(
    original: PortalProjectSnapshot,
    migrated: PortalProjectSnapshot,
    admittedFamily: Set<Path>,
    changedJava: Set<Path>
) {
    val escaped = changedJava.filter {
        it !in admittedFamily && !isGeneratedPortalHelperPath(it)
    }
    require(escaped.isEmpty()) {
        "Portal transaction changed Java outside the admitted exact family: ${escaped.sorted()}"
    }
    for (relative in original.javaFiles - admittedFamily) {
        if (isGeneratedPortalHelperPath(relative)) continue
        val before = original.files.getValue(relative)
        val after = migrated.files.getValue(relative)
        require(before.sha256 == after.sha256 && before.bytes.contentEquals(after.bytes)) {
            "Portal transaction did not preserve an unrelated Java source: $relative"
        }
    }
}

private fun isGeneratedPortalHelperPath(relative: Path): Boolean {
    val normalized = relative.toString().replace('\\', '/')
    return normalized.contains("/com/modporter/generated/") &&
        relative.name in setOf("LegacyPortalProbe.java", "PortalInfo.java")
}

private fun validateChangedPortalJavaParses(
    tempProject: Path,
    migrated: PortalProjectSnapshot,
    changedJava: Set<Path>
) {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    for (relative in changedJava) {
        val source = String(
            migrated.files.getValue(relative).bytes,
            StandardCharsets.UTF_8
        )
        val result = parser.parse(source)
        require(result.isSuccessful && result.result.isPresent) {
            "Portal staged Java does not parse: ${tempProject.resolve(relative)}; " +
                result.problems.joinToString("; ") { it.verboseMessage }
        }
    }
}

private fun validateGeneratedPortalHelpers(
    migrated: PortalProjectSnapshot,
    templates: PortalHelperTemplates,
    scopedJava: Set<Path>
) {
    val importedHelpers = linkedMapOf<String, MutableSet<String>>()
    val importPattern = Regex(
        """(?m)^\s*import\s+(com\.modporter\.generated\.[a-z0-9_]+\.compat\.(LegacyPortalProbe|PortalInfo))\s*;"""
    )
    for (relative in scopedJava) {
        val source = String(
            migrated.files.getValue(relative).bytes,
            StandardCharsets.UTF_8
        )
        importPattern.findAll(source).forEach { match ->
            val fqn = match.groupValues[1]
            importedHelpers.getOrPut(fqn.substringBeforeLast('.')) { linkedSetOf() } +=
                "${match.groupValues[2]}.java"
        }
    }

    val helperPackages = linkedMapOf<String, MutableSet<String>>()
    for (relative in migrated.javaFiles.filter(::isGeneratedPortalHelperPath)) {
        val source = String(
            migrated.files.getValue(relative).bytes,
            StandardCharsets.UTF_8
        )
        val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: continue
        if (packageName !in importedHelpers) continue
        val helperFqn = "$packageName.${relative.name.removeSuffix(".java")}"
        if (!TRANSACTION_GENERATED_HELPER.matches(helperFqn)) continue
        val expected = when (relative.name) {
            "LegacyPortalProbe.java" -> templates.probe.replace("@PACKAGE@", packageName)
            "PortalInfo.java" -> templates.portalInfo.replace("@PACKAGE@", packageName)
            else -> error("Unreachable portal helper: $relative")
        }
        require(source == expected) {
            "Generated portal helper does not match the loaded resource: $helperFqn"
        }
        helperPackages.getOrPut(packageName) { linkedSetOf() } += relative.name
    }
    for ((packageName, imported) in importedHelpers) {
        val generated = helperPackages[packageName].orEmpty()
        require(generated.containsAll(imported)) {
            "Portal sources import helpers missing from the staged project: " +
                "${imported - generated.toSet()}"
        }
    }
    val ownedPackages = (helperPackages.keys + importedHelpers.keys).toSet()
    require(ownedPackages.size <= 1) {
        "Portal transaction resolved more than one generated compatibility package: " +
            ownedPackages.sorted()
    }
}

private fun validateLegacyPortalCanonicalUses(
    tempProject: Path,
    migrated: PortalProjectSnapshot,
    admittedFamily: Set<Path>
) {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    for (relative in admittedFamily) {
        val source = String(
            migrated.files.getValue(relative).bytes,
            StandardCharsets.UTF_8
        )
        val result = parser.parse(source)
        require(result.isSuccessful && result.result.isPresent) {
            "Admitted portal-family Java does not parse: ${tempProject.resolve(relative)}"
        }
        validateLegacyPortalUnit(tempProject.resolve(relative), result.result.get())
    }
}

private fun validateLegacyPortalUnit(file: Path, unit: CompilationUnit) {
    val legacyImports = unit.imports.map { it.nameAsString }.filter {
        it == TRANSACTION_LEGACY_PORTAL_INFO ||
            it == TRANSACTION_FORGE_TELEPORTER ||
            it == TRANSACTION_NEOFORGE_TELEPORTER
    }
    require(legacyImports.isEmpty()) {
        "Legacy portal imports survived the combined transaction in $file: $legacyImports"
    }

    val legacyTypes = unit.findAll(ClassOrInterfaceType::class.java).filter { type ->
        val name = type.nameWithScope
        name == "ITeleporter" ||
            name == TRANSACTION_FORGE_TELEPORTER ||
            name == TRANSACTION_NEOFORGE_TELEPORTER ||
            name == TRANSACTION_LEGACY_PORTAL_INFO
    }
    require(legacyTypes.isEmpty()) {
        "Legacy portal types survived the combined transaction in $file: " +
            legacyTypes.map { it.asString() }.distinct()
    }

    val removedSuperCalls = unit.findAll(MethodCallExpr::class.java).filter {
        it.nameAsString == "findDimensionEntryPoint" &&
            it.scope.orElse(null) is SuperExpr
    }
    require(removedSuperCalls.isEmpty()) {
        "Removed super.findDimensionEntryPoint call survived the combined transaction in $file"
    }

    val legacyProbeMethods = unit.findAll(MethodDeclaration::class.java).filter { method ->
        method.nameAsString == "findDimensionEntryPoint" &&
            method.parameters.size == 1 &&
            method.parameters.single().type.asString().endsWith("ServerLevel")
    }
    for (method in legacyProbeMethods) {
        require(method.annotations.none { it.nameAsString == "Override" }) {
            "Removed findDimensionEntryPoint override survived the combined transaction in $file"
        }
        val helperCalls = method.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "findDimensionEntryPoint" &&
                (it.scope.orElse(null) as? NameExpr)?.nameAsString == "LegacyPortalProbe"
        }
        require(helperCalls.size == 1) {
            "Legacy findDimensionEntryPoint method is not gated by LegacyPortalProbe in $file"
        }
    }

    val entranceUses = buildList<Node> {
        addAll(unit.findAll(NameExpr::class.java).filter {
            it.nameAsString == "portalEntrancePos"
        })
        addAll(unit.findAll(FieldAccessExpr::class.java).filter {
            it.nameAsString == "portalEntrancePos"
        })
    }
    for (use in entranceUses) {
        val owner = use.findAncestor(TypeDeclaration::class.java).orElse(null)
            ?: error("portalEntrancePos use has no source owner in $file")
        val fields = owner.members.filterIsInstance<FieldDeclaration>()
            .filter { !it.isStatic }
            .flatMap { it.variables }
            .filter {
                it.nameAsString == "portalEntrancePos" &&
                    it.type.asString().endsWith("BlockPos")
            }
        require(fields.size == 1) {
            "portalEntrancePos use is not gated by one source-owned BlockPos field in $file"
        }
    }

    val portalClassLiterals = unit.findAll(ClassExpr::class.java).filter {
        it.type.asString() in setOf("PortalInfo", TRANSACTION_LEGACY_PORTAL_INFO)
    }
    if (portalClassLiterals.isNotEmpty()) {
        val hasVirtualLookup = unit.findAll(MethodCallExpr::class.java).any {
            it.nameAsString == "findVirtual"
        }
        require(!hasVirtualLookup) {
            "Unknown PortalInfo MethodHandle family survived the combined transaction in $file"
        }
    }
}

private fun StructuralRefactorPass.verifyPortalSnapshotUnchanged(
    project: Path,
    original: PortalProjectSnapshot
) {
    val current = capturePortalProjectSnapshot(project)
    require(current.javaFiles == original.javaFiles) {
        "Java source set changed during portal transaction preflight"
    }
    require(current.metadataFiles == original.metadataFiles) {
        "Project metadata set changed during portal transaction preflight"
    }
    for ((relative, before) in original.files) {
        val now = current.files[relative]
            ?: error("Project file disappeared during portal transaction: $relative")
        require(now.sha256 == before.sha256 && now.bytes.contentEquals(before.bytes)) {
            "Project file changed during portal transaction: $relative"
        }
    }
}

private fun remapPortalChanges(
    changes: List<Change>,
    tempProject: Path,
    originalProject: Path,
    changedJava: Set<Path>
): List<Change> = changes.mapNotNull { change ->
    val tempFile = change.file.toAbsolutePath().normalize()
    require(tempFile.startsWith(tempProject)) {
        "Portal migration reported a path outside its temporary project: $tempFile"
    }
    val relative = tempProject.relativize(tempFile)
    if (relative !in changedJava) {
        null
    } else {
        change.copy(file = originalProject.resolve(relative))
    }
}

private fun prestagePortalPublish(
    originalProject: Path,
    original: PortalProjectSnapshot,
    migrated: PortalProjectSnapshot,
    changedJava: Set<Path>,
    publishRoot: Path,
    fault: PortalTransactionFault
): List<PortalPublishEntry> {
    val entries = mutableListOf<PortalPublishEntry>()
    for ((index, relative) in changedJava.sorted().withIndex()) {
        val target = originalProject.resolve(relative).normalize()
        require(target.startsWith(originalProject)) {
            "Portal publish path escaped the project: $relative"
        }
        val staged = migrated.files.getValue(relative)
        val stagedNew = publishRoot.resolve("new").resolve(relative).normalize()
        stagedNew.parent.createDirectories()
        stagedNew.writeBytes(staged.bytes)
        val stagedCopy = capturePortalFile(stagedNew)
        require(
            stagedCopy.sha256 == staged.sha256 &&
                stagedCopy.bytes.contentEquals(staged.bytes)
        ) {
            "Portal staged publish bytes changed: $relative"
        }

        val before = original.files[relative]
        val rollback = before?.let {
            publishRoot.resolve("rollback").resolve(relative).normalize().also { rollbackFile ->
                rollbackFile.parent.createDirectories()
                rollbackFile.writeBytes(it.bytes)
                val rollbackCopy = capturePortalFile(rollbackFile)
                require(
                    rollbackCopy.sha256 == it.sha256 &&
                        rollbackCopy.bytes.contentEquals(it.bytes)
                ) {
                    "Portal rollback bytes changed while staging: $relative"
                }
            }
        }
        entries += PortalPublishEntry(
            relative,
            target,
            before,
            staged,
            stagedNew,
            rollback
        )
        if (index == 0 && fault == PortalTransactionFault.AFTER_FIRST_PRESTAGE) {
            error("Injected portal transaction failure after first publish staging entry")
        }
    }
    return entries
}

private fun StructuralRefactorPass.publishPortalEntriesAtomically(
    project: Path,
    original: PortalProjectSnapshot,
    entries: List<PortalPublishEntry>,
    fault: PortalTransactionFault
) {
    val published = mutableListOf<PortalPublishEntry>()
    try {
        for ((index, entry) in entries.withIndex()) {
            verifyPortalTargetStillOriginal(entry)
            entry.target.parent.createDirectories()
            atomicPortalMove(entry.stagedNew, entry.target)
            published += entry
            if (index == 0 && fault == PortalTransactionFault.AFTER_FIRST_PUBLISH) {
                error("Injected portal transaction failure after first atomic publish")
            }
        }
        verifyPublishedPortalProject(project, original, entries)
    } catch (failure: Throwable) {
        val rollbackFailures = rollbackPublishedPortalEntries(project, published).toMutableList()
        for (entry in entries.filter { it.original == null }) {
            runCatching {
                prunePortalEmptyParents(entry.target.parent, project)
            }.exceptionOrNull()?.let(rollbackFailures::add)
        }
        val transactionFailure = if (rollbackFailures.isEmpty()) {
            IllegalStateException(
                "Legacy portal transaction publish failed; all published entries were rolled back",
                failure
            )
        } else {
            PortalRollbackIncompleteException(
                "Legacy portal transaction publish failed and aggregate rollback was incomplete",
                failure
            )
        }
        rollbackFailures.forEach(transactionFailure::addSuppressed)
        throw transactionFailure
    }
}

private fun StructuralRefactorPass.verifyPublishedPortalProject(
    project: Path,
    original: PortalProjectSnapshot,
    entries: List<PortalPublishEntry>
) {
    val stagedByPath = entries.associateBy { it.relative }
    val current = capturePortalProjectSnapshot(project)
    val expectedJava = original.javaFiles + stagedByPath.keys
    require(current.javaFiles == expectedJava) {
        "Java source set changed during portal publication"
    }
    require(current.metadataFiles == original.metadataFiles) {
        "Project metadata set changed during portal publication"
    }
    for (relative in expectedJava + original.metadataFiles) {
        val expected = stagedByPath[relative]?.staged ?: original.files.getValue(relative)
        val actual = current.files[relative]
            ?: error("Portal publication lost an expected project file: $relative")
        require(
            actual.sha256 == expected.sha256 &&
                actual.bytes.contentEquals(expected.bytes)
        ) {
            "Project file changed during portal publication: $relative"
        }
    }
}

private fun verifyPortalTargetStillOriginal(entry: PortalPublishEntry) {
    val before = entry.original
    if (before == null) {
        require(!entry.target.exists()) {
            "A new portal publish target appeared after preflight: ${entry.relative}"
        }
        return
    }
    require(Files.isRegularFile(entry.target)) {
        "Portal publish target disappeared after preflight: ${entry.relative}"
    }
    val current = capturePortalFile(entry.target)
    require(current.sha256 == before.sha256 && current.bytes.contentEquals(before.bytes)) {
        "Portal publish target changed after preflight: ${entry.relative}"
    }
}

private fun atomicPortalMove(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IllegalStateException(
            "Atomic portal publish is unavailable for $target",
            unsupported
        )
    }
}

private fun rollbackPublishedPortalEntries(
    project: Path,
    published: List<PortalPublishEntry>
): List<Throwable> {
    val failures = mutableListOf<Throwable>()
    for (entry in published.asReversed()) {
        try {
            val current = capturePortalFile(entry.target)
            require(
                current.sha256 == entry.staged.sha256 &&
                    current.bytes.contentEquals(entry.staged.bytes)
            ) {
                "Published portal target changed before rollback: ${entry.relative}"
            }
            val rollback = entry.stagedRollback
            if (rollback != null) {
                atomicPortalMove(rollback, entry.target)
            } else {
                Files.delete(entry.target)
                prunePortalEmptyParents(entry.target.parent, project)
            }
        } catch (rollbackFailure: Throwable) {
            failures += rollbackFailure
        }
    }
    return failures
}

private fun prunePortalEmptyParents(start: Path?, project: Path) {
    var current = start
    while (current != null && current != project && current.startsWith(project)) {
        val empty = Files.list(current).use { children -> !children.findAny().isPresent }
        if (!empty) return
        Files.deleteIfExists(current)
        current = current.parent
    }
}

private fun deleteOwnedPortalTransactionTree(root: Path, expectedParent: Path) {
    val normalized = root.toAbsolutePath().normalize()
    require(normalized.parent == expectedParent && normalized.name.startsWith(".modporter-portal-transaction-")) {
        "Refusing to delete a non-owned portal transaction directory: $normalized"
    }
    if (!Files.exists(normalized)) return
    Files.walk(normalized).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
