package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Completes two 1.21 world contracts when the old implementation already proves the intended
 * behavior.
 *
 * A direct Level subclass receives the new time, brewing, and tick-rate methods only when it owns
 * one canonical Level field, assigns that field from a canonical constructor parameter, passes
 * that same parameter's WritableLevelData and RegistryAccess to super, and already forwards at
 * least two other methods to that field. The new methods therefore preserve the class's existing
 * shared time and registry state instead of inventing local clock or brewing state.
 *
 * A ChunkAccess/LevelChunk subclass's old one-argument getBlockEntityNbtForSaving override keeps
 * its body byte-for-byte and gains the registry provider now supplied by ChunkAccess. Existing
 * read-only null/throw behavior and real serialization implementations are both retained.
 */
internal fun StructuralRefactorPass.migrateForwardingWorldContracts(source: String): String {
    if (!source.contains("extends") ||
        (!source.contains("getBlockEntityNbtForSaving") &&
            !source.contains("net.minecraft.world.level.Level") &&
            !source.contains("import net.minecraft.world.level.Level;"))
    ) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun start(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    fun end(node: Node): Int =
        node.range.map { lineStarts[it.end.line - 1] + it.end.column - 1 }.orElse(-1)

    val typing = SourceValueTypes(this, source)
    val masked = JavaSourceText.maskCommentsAndLiterals(source)
    data class Edit(val from: Int, val toExclusive: Int, val replacement: String)
    val edits = mutableListOf<Edit>()
    var needsHolderLookup = false
    var needsPotionBrewing = false
    var needsTickRateManager = false

    fun ClassOrInterfaceDeclaration.extendsCanonical(vararg canonical: String): Boolean =
        extendedTypes.any { type -> canonical.any { typing.isTypeName(type.nameWithScope, it) } }

    fun memberIndent(declaration: ClassOrInterfaceDeclaration): String {
        val from = start(declaration)
        val to = end(declaration)
        if (from < 0 || to <= from) return "    "
        val bodyOpen = masked.indexOf('{', from).takeIf { it in from until to } ?: return "    "
        return Regex("""(?m)^([ \t]+)(?:@|public\b|protected\b|private\b|final\b)""")
            .find(masked.substring(bodyOpen + 1, to))
            ?.groupValues
            ?.get(1)
            ?: "    "
    }

    for (declaration in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
        if (declaration.isInterface) continue

        if (declaration.extendsCanonical("net.minecraft.world.level.Level")) {
            val levelFields = declaration.fields
                .flatMap { it.variables }
                .filter { typing.isTypeName(it.type.toString(), "net.minecraft.world.level.Level") }
            val delegate = levelFields.singleOrNull()?.nameAsString
            if (delegate != null) {
                val sharesLevelDataAndRegistries = declaration.constructors.any { constructor ->
                    val levelParameters = constructor.parameters
                        .filter {
                            typing.isTypeName(
                                it.type.toString(),
                                "net.minecraft.world.level.Level"
                            )
                        }
                        .map { it.nameAsString }
                        .toSet()
                    val invocation = constructor.body.statements.firstOrNull()
                        as? ExplicitConstructorInvocationStmt
                    if (invocation == null || invocation.isThis || invocation.expression.isPresent) {
                        false
                    } else {
                        levelParameters.any { parameter ->
                            val assigned = constructor.findAll(AssignExpr::class.java).any { assignment ->
                                val target = assignment.target as? FieldAccessExpr
                                val value = assignment.value as? NameExpr
                                target?.scope is ThisExpr &&
                                    target.nameAsString == delegate &&
                                    value?.nameAsString == parameter
                            }
                            val levelData = invocation.arguments.firstOrNull() as? CastExpr
                            val levelDataCall = levelData?.expression as? MethodCallExpr
                            val sharedLevelData =
                                levelData != null &&
                                    levelDataCall != null &&
                                    typing.isTypeName(
                                        levelData.type.toString(),
                                        "net.minecraft.world.level.storage.WritableLevelData"
                                    ) &&
                                    levelDataCall.nameAsString == "getLevelData" &&
                                    levelDataCall.arguments.isEmpty() &&
                                    (levelDataCall.scope.orElse(null) as? NameExpr)
                                        ?.nameAsString == parameter
                            val sharedRegistries = invocation.arguments.any { argument ->
                                val call = argument as? MethodCallExpr
                                call != null &&
                                    call.nameAsString == "registryAccess" &&
                                    call.arguments.isEmpty() &&
                                    (call.scope.orElse(null) as? NameExpr)
                                        ?.nameAsString == parameter
                            }
                            assigned && sharedLevelData && sharedRegistries
                        }
                    }
                }
                val classStart = start(declaration)
                val classEnd = end(declaration)
                val classText = if (classStart >= 0 && classEnd >= classStart) {
                    masked.substring(classStart, classEnd + 1)
                } else {
                    ""
                }
                val forwardingEvidence = Regex(
                    """(?m)\breturn\s+(?:this\s*\.\s*)?${Regex.escape(delegate)}\s*\.\s*[A-Za-z_$][\w$]*\s*\("""
                ).findAll(classText).count()
                if (sharesLevelDataAndRegistries && forwardingEvidence >= 2 && classEnd >= 0) {
                    val existing = declaration.methods.map { it.nameAsString }.toSet()
                    val indent = memberIndent(declaration)
                    val bodyIndent = if (indent.contains('\t')) "$indent\t" else "$indent    "
                    val methods = buildList {
                        if ("potionBrewing" !in existing) {
                            add(
                                "${indent}@Override\n" +
                                    "${indent}public PotionBrewing potionBrewing() {\n" +
                                    "${bodyIndent}return this.$delegate.potionBrewing();\n" +
                                    "$indent}"
                            )
                            needsPotionBrewing = true
                        }
                        if ("setDayTimeFraction" !in existing) {
                            add(
                                "${indent}@Override\n" +
                                    "${indent}public void setDayTimeFraction(float value) {\n" +
                                    "${bodyIndent}this.$delegate.setDayTimeFraction(value);\n" +
                                    "$indent}"
                            )
                        }
                        if ("setDayTimePerTick" !in existing) {
                            add(
                                "${indent}@Override\n" +
                                    "${indent}public void setDayTimePerTick(float value) {\n" +
                                    "${bodyIndent}this.$delegate.setDayTimePerTick(value);\n" +
                                    "$indent}"
                            )
                        }
                        if ("getDayTimeFraction" !in existing) {
                            add(
                                "${indent}@Override\n" +
                                    "${indent}public float getDayTimeFraction() {\n" +
                                    "${bodyIndent}return this.$delegate.getDayTimeFraction();\n" +
                                    "$indent}"
                            )
                        }
                        if ("getDayTimePerTick" !in existing) {
                            add(
                                "${indent}@Override\n" +
                                    "${indent}public float getDayTimePerTick() {\n" +
                                    "${bodyIndent}return this.$delegate.getDayTimePerTick();\n" +
                                    "$indent}"
                            )
                        }
                        if ("tickRateManager" !in existing) {
                            add(
                                "${indent}@Override\n" +
                                    "${indent}public TickRateManager tickRateManager() {\n" +
                                    "${bodyIndent}return this.$delegate.tickRateManager();\n" +
                                    "$indent}"
                            )
                            needsTickRateManager = true
                        }
                    }
                    if (methods.isNotEmpty()) {
                        edits += Edit(classEnd, classEnd, "\n\n${methods.joinToString("\n\n")}\n")
                    }
                }
            }
        }

        if (declaration.extendsCanonical(
                "net.minecraft.world.level.chunk.ChunkAccess",
                "net.minecraft.world.level.chunk.LevelChunk"
            )
        ) {
            val methods = declaration.methods.filter { it.nameAsString == "getBlockEntityNbtForSaving" }
            val alreadyModern = methods.any { method ->
                method.parameters.size == 2 &&
                    typing.isTypeName(
                        method.parameters[1].type.toString(),
                        "net.minecraft.core.HolderLookup.Provider"
                    )
            }
            if (!alreadyModern) {
                val legacy = methods.singleOrNull { method ->
                    method.parameters.size == 1 &&
                        typing.isTypeName(
                            method.parameters.single().type.toString(),
                            "net.minecraft.core.BlockPos"
                        ) &&
                        method.body.isPresent
                }
                if (legacy != null) {
                    val nameOffset = start(legacy.name)
                    val open = if (nameOffset >= 0) masked.indexOf('(', nameOffset) else -1
                    val close = if (open >= 0) JavaSourceText.matchingParen(masked, open) else -1
                    if (close > open) {
                        val occupied = Regex("""\b[A-Za-z_$][\w$]*\b""")
                            .findAll(masked.substring(start(legacy).coerceAtLeast(0), end(legacy) + 1))
                            .map { it.value }
                            .toSet()
                        var providerName = "registries"
                        var suffix = 2
                        while (providerName in occupied) {
                            providerName = "registries${suffix++}"
                        }
                        edits += Edit(
                            close,
                            close,
                            ", HolderLookup.Provider $providerName"
                        )
                        needsHolderLookup = true
                    }
                }
            }
        }
    }

    if (edits.isEmpty()) return source
    var result = source
    for (edit in edits.distinct().sortedWith(compareByDescending<Edit> { it.from }.thenByDescending { it.toExclusive })) {
        result = result.substring(0, edit.from) + edit.replacement + result.substring(edit.toExclusive)
    }
    if (needsHolderLookup) {
        result = addExecutableImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    if (needsPotionBrewing) {
        result = addExecutableImportIfMissing(result, "net.minecraft.world.item.alchemy.PotionBrewing")
    }
    if (needsTickRateManager) {
        result = addExecutableImportIfMissing(result, "net.minecraft.world.TickRateManager")
    }
    return result
}
