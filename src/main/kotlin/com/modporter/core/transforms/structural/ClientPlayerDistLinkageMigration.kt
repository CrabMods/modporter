package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Path

/**
 * Repairs the eager client linkage introduced when a lazy Forge DistExecutor call such as
 *
 *     Player player = DistExecutor.unsafeCallWhenOn(
 *         Dist.CLIENT, () -> () -> Minecraft.getInstance().player);
 *
 * was flattened to a direct conditional:
 *
 *     Player player =
 *         FMLLoader.getDist() == Dist.CLIENT ? Minecraft.getInstance().player : null;
 *
 * The direct getfield has descriptor LocalPlayer and can make RuntimeDistCleaner load the client
 * class while defining the common owner on a dedicated server. The replacement keeps the exact
 * common Player variable, condition and null fallback, but moves only the client getfield into a
 * separately loadable nested class. The outer class then links only against a ()Player method.
 *
 * Integration, immediately after migrateDistExecutorDistGuards:
 *
 *     distSource = migrateClientPlayerDistLinkage(distSource, file, fileChanges)
 */
internal fun StructuralRefactorPass.migrateClientPlayerDistLinkage(
    source: String,
    file: Path,
    changes: MutableList<Change>
): String {
    if (!source.contains("Minecraft.getInstance().player") ||
        !source.contains("FMLLoader.getDist()") ||
        !source.contains("Dist.CLIENT")
    ) {
        return source
    }
    if (!cpNativeClientPlayerContract()) return source

    val parsed = cpParse(source) ?: return source
    val owner = parsed.unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
        ?: return source
    if (owner.isInterface || owner.nameAsString == CP_HOLDER) return source
    if (owner.members.any {
            it is ClassOrInterfaceDeclaration && it.nameAsString == CP_HOLDER
        }
    ) {
        return source
    }

    val minecraftImport = parsed.unit.imports.singleOrNull {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString == CP_MINECRAFT
    } ?: return source

    val syntacticReads = owner.findAll(FieldAccessExpr::class.java)
        .filter { read ->
            if (read.nameAsString != "player") return@filter false
            val call = read.scope.cpUnwrap() as? MethodCallExpr ?: return@filter false
            call.nameAsString == "getInstance" &&
                call.arguments.isEmpty() &&
                !call.typeArguments.isPresent
        }
    val reads = syntacticReads
        .mapNotNull { read -> parsed.cpClientPlayerRead(read)?.let { read } }
    if (reads.isEmpty() || reads.size != syntacticReads.size) return source

    // Start from each local initializer so a preserved `(condition ? value : null)`
    // EnclosedExpr cannot hide the VariableDeclarator from candidate discovery.
    val candidates = owner.findAll(VariableDeclarator::class.java)
        .mapNotNull { variable -> parsed.cpCandidate(variable) }
    val readRanges = reads.mapNotNull { it.range.orElse(null) }
    val candidateRanges = candidates.mapNotNull { it.read.range.orElse(null) }
    // This is transactional. A mixed class with one unproved client access is left untouched.
    if (candidates.size != reads.size ||
        readRanges.size != reads.size ||
        candidateRanges.size != candidates.size ||
        readRanges.toSet() != candidateRanges.toSet()
    ) {
        return source
    }

    val minecraftTypeNames = owner.findAll(NameExpr::class.java)
        .filter {
            it.nameAsString == "Minecraft" &&
                parsed.typing.isTypeName(it.nameAsString, CP_MINECRAFT)
        }
    val admittedMinecraftNames = reads.mapNotNull { read ->
        val call = read.scope.cpUnwrap() as? MethodCallExpr
        call?.scope?.orElse(null) as? NameExpr
    }
    val minecraftTypeRanges = minecraftTypeNames.mapNotNull {
        it.range.orElse(null)
    }
    val admittedMinecraftRanges = admittedMinecraftNames.mapNotNull {
        it.range.orElse(null)
    }
    if (minecraftTypeNames.size != reads.size ||
        admittedMinecraftNames.size != reads.size ||
        minecraftTypeRanges.size != minecraftTypeNames.size ||
        admittedMinecraftRanges.size != admittedMinecraftNames.size ||
        minecraftTypeRanges.toSet() != admittedMinecraftRanges.toSet()
    ) {
        return source
    }

    val edits = mutableListOf<CPLinkEdit>()
    for (candidate in candidates) {
        edits += parsed.replace(candidate.read, "$CP_HOLDER.get()")
    }
    edits += parsed.removeImportLine(minecraftImport)

    val ownerEnd = owner.range.orElse(null)?.end ?: return source
    val closeBrace = parsed.offset(ownerEnd)
    if (closeBrace !in source.indices || source[closeBrace] != '}') return source
    edits += CPLinkEdit(closeBrace, closeBrace, cpHolderSource())

    val migrated = cpApply(source, edits) ?: return source
    changes += Change(
        file = file,
        line = candidates.minOf { it.read.range.orElseThrow().begin.line },
        description =
            "Keep guarded Minecraft.player access behind a separately loaded client holder",
        before =
            "FMLLoader.getDist() == Dist.CLIENT ? Minecraft.getInstance().player : null",
        after =
            "FMLLoader.getDist() == Dist.CLIENT ? $CP_HOLDER.get() : null",
        confidence = Confidence.HIGH,
        ruleId = CP_RULE_ID
    )
    return migrated
}

private const val CP_RULE_ID = "struct-client-player-dist-linkage"
private const val CP_HOLDER = "ModPorterClientPlayerAccess"
private const val CP_MINECRAFT = "net.minecraft.client.Minecraft"
private const val CP_LOCAL_PLAYER = "net.minecraft.client.player.LocalPlayer"
private const val CP_PLAYER = "net.minecraft.world.entity.player.Player"
private const val CP_FML_LOADER = "net.neoforged.fml.loading.FMLLoader"
private const val CP_DIST = "net.neoforged.api.distmarker.Dist"

private data class CPLinkEdit(
    val start: Int,
    val endExclusive: Int,
    val replacement: String
)

private data class CPLinkCandidate(
    val variable: VariableDeclarator,
    val conditional: ConditionalExpr,
    val read: FieldAccessExpr
)

private data class CPLinkParsed(
    val source: String,
    val unit: com.github.javaparser.ast.CompilationUnit,
    val typing: SourceValueTypes,
    val lineStarts: List<Int>
) {
    fun offset(position: Position): Int {
        val lineStart = lineStarts.getOrNull(position.line - 1) ?: return -1
        return lineStart + position.column - 1
    }

    fun replace(node: Node, replacement: String): CPLinkEdit {
        val range = node.range.orElseThrow()
        return CPLinkEdit(
            offset(range.begin),
            offset(range.end) + 1,
            replacement
        )
    }

    fun removeImportLine(node: Node): CPLinkEdit {
        val range = node.range.orElseThrow()
        val start = offset(range.begin)
        var end = offset(range.end) + 1
        if (source.startsWith("\r\n", end)) {
            end += 2
        } else if (end < source.length && source[end] == '\n') {
            end++
        }
        return CPLinkEdit(start, end, "")
    }

    fun cpClientPlayerRead(expression: Expression): FieldAccessExpr? {
        val read = expression.cpUnwrap() as? FieldAccessExpr ?: return null
        if (read.nameAsString != "player" ||
            TargetMembers.fieldDescriptor(CP_MINECRAFT, "player") !=
            "L${CP_LOCAL_PLAYER.replace('.', '/')};"
        ) {
            return null
        }
        val getInstance = read.scope.cpUnwrap() as? MethodCallExpr ?: return null
        if (getInstance.nameAsString != "getInstance" ||
            getInstance.arguments.isNotEmpty() ||
            getInstance.typeArguments.isPresent
        ) {
            return null
        }
        val owner = getInstance.scope.orElse(null) as? NameExpr ?: return null
        if (owner.nameAsString != "Minecraft" ||
            !typing.isTypeName(owner.nameAsString, CP_MINECRAFT)
        ) {
            return null
        }
        return read
    }

    fun cpCandidate(variable: VariableDeclarator): CPLinkCandidate? {
        if (variable.findAncestor(FieldDeclaration::class.java).isPresent ||
            variable.findAncestor(MethodDeclaration::class.java).isEmpty ||
            variable.type.isVarType ||
            !typing.isTypeName(variable.typeAsString, CP_PLAYER)
        ) {
            return null
        }
        val declaration = variable.parentNode.orElse(null) as? VariableDeclarationExpr
            ?: return null
        if (declaration.variables.size != 1) return null

        val conditional = variable.initializer.orElse(null)
            ?.cpUnwrap() as? ConditionalExpr ?: return null
        if (conditional.elseExpr.cpUnwrap() !is NullLiteralExpr ||
            !cpIsExactClientCondition(conditional.condition)
        ) {
            return null
        }
        val read = cpClientPlayerRead(conditional.thenExpr) ?: return null
        return CPLinkCandidate(variable, conditional, read)
    }

    private fun cpIsExactClientCondition(expression: Expression): Boolean {
        val comparison = expression.cpUnwrap() as? BinaryExpr ?: return false
        if (comparison.operator != BinaryExpr.Operator.EQUALS) return false
        return cpIsLoaderDist(comparison.left, comparison.right) ||
            cpIsLoaderDist(comparison.right, comparison.left)
    }

    private fun cpIsLoaderDist(loaderExpression: Expression, distExpression: Expression): Boolean {
        val loader = loaderExpression.cpUnwrap() as? MethodCallExpr ?: return false
        if (loader.nameAsString != "getDist" ||
            loader.arguments.isNotEmpty() ||
            loader.typeArguments.isPresent
        ) {
            return false
        }
        val loaderOwner = loader.scope.orElse(null) ?: return false
        if (!typing.isTypeName(loaderOwner.toString(), CP_FML_LOADER)) return false

        val client = distExpression.cpUnwrap() as? FieldAccessExpr ?: return false
        return client.nameAsString == "CLIENT" &&
            typing.isTypeName(client.scope.toString(), CP_DIST)
    }
}

private fun cpNativeClientPlayerContract(): Boolean =
    "()Lnet/minecraft/client/Minecraft;" in
        TargetMembers.methodDescriptors(CP_MINECRAFT, "getInstance") &&
        TargetMembers.fieldDescriptor(CP_MINECRAFT, "player") ==
        "Lnet/minecraft/client/player/LocalPlayer;" &&
        TargetMembers.inheritsFrom(CP_LOCAL_PLAYER, CP_PLAYER)

private fun StructuralRefactorPass.cpParse(source: String): CPLinkParsed? {
    val parser = JavaParser(
        ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return null
    val starts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    return CPLinkParsed(source, unit, SourceValueTypes(this, source), starts)
}

private fun Expression.cpUnwrap(): Expression =
    if (this is EnclosedExpr) inner.cpUnwrap() else this

private fun cpHolderSource(): String = """

    private static final class $CP_HOLDER {
        private $CP_HOLDER() {
        }

        private static $CP_PLAYER get() {
            return $CP_MINECRAFT.getInstance().player;
        }
    }
""".trimEnd() + "\n"

private fun cpApply(source: String, edits: List<CPLinkEdit>): String? {
    val ordered = edits.sortedWith(compareBy<CPLinkEdit> { it.start }.thenBy { it.endExclusive })
    if (ordered.any {
            it.start < 0 ||
                it.endExclusive < it.start ||
                it.endExclusive > source.length
        } ||
        ordered.zipWithNext().any { (left, right) ->
            left.endExclusive > right.start ||
                (left.start == right.start && left.endExclusive == right.endExclusive)
        }
    ) {
        return null
    }
    var result = source
    for (edit in ordered.asReversed()) {
        result = result.replaceRange(edit.start, edit.endExclusive, edit.replacement)
    }
    return result
}
