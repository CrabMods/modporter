package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.mapping.TargetMembers

/**
 * Moves Create's structure-block test-function redirect from the removed 1.20.1 runTest path to
 * the exact 1.21.1 createGameTestInfo/findTestFunction Optional path.
 *
 * This is intentionally separate from CreateMixinSignatureFamilies while the native Mixin fixes
 * are being integrated in parallel. The caller should add:
 *
 *     result = migrateCreateTestCommandMixin(result)
 */
internal fun StructuralRefactorPass.migrateCreateTestCommandMixin(source: String): String {
    if (!source.contains("TestCommandMixin")) return source
    if (!tcNativeShapeAvailable()) return source
    val parsed = tcParse(source) ?: return source
    if (parsed.packageName != TC_MIXIN.substringBeforeLast('.')) return source
    val owner = parsed.unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
        ?: return source
    if (owner.nameAsString != TC_MIXIN.substringAfterLast('.') ||
        owner.isInterface ||
        !owner.isPublic ||
        owner.typeParameters.isNotEmpty() ||
        parsed.mixinOwner(TC_TEST_COMMAND) !== owner
    ) {
        return source
    }

    val handler = owner.methods.mapNotNull { method ->
        parsed.annotation(method.annotations, TC_REDIRECT)?.let { method to it }
    }.singleOrNull() ?: return source
    val method = handler.first
    val redirect = handler.second
    if (method.nameAsString != "create\$getCorrectTestFunction" ||
        !method.isPrivate ||
        !method.isStatic ||
        method.typeParameters.isNotEmpty() ||
        !parsed.typing.isClassType(method.type, TC_TEST_FUNCTION)
    ) {
        return source
    }

    val legacySelector = redirect.stringMember("method") as? StringLiteralExpr
        ?: return source
    if (legacySelector.value != TC_LEGACY_SELECTOR ||
        (redirect.member("require") as? IntegerLiteralExpr)?.asInt() != 0
    ) {
        return source
    }
    val at = redirect.member("at") as? AnnotationExpr ?: return source
    if (!parsed.typing.isTypeName(at.nameAsString, TC_AT) ||
        at.stringValue("value") != "INVOKE"
    ) {
        return source
    }
    val legacyTarget = at.stringMember("target") as? StringLiteralExpr
        ?: return source
    if (legacyTarget.value != TC_LEGACY_TARGET) return source

    if (method.parameters.size != 4) return source
    val testName = method.parameters[0]
    val level = method.parameters[1]
    val pos = method.parameters[2]
    val tracker = method.parameters[3]
    if (testName.nameAsString != "testName" ||
        !parsed.typing.isClassType(testName.type, "java.lang.String") ||
        level.nameAsString != "level" ||
        !parsed.typing.isClassType(level.type, TC_SERVER_LEVEL) ||
        pos.nameAsString != "pos" ||
        !parsed.typing.isClassType(pos.type, TC_BLOCK_POS) ||
        tracker.nameAsString != "tracker" ||
        !parsed.typing.isClassType(tracker.type, TC_MULTIPLE_TRACKER) ||
        tracker.annotations.size != 1 ||
        tracker.annotations.none {
            parsed.typing.isTypeName(it.nameAsString, "javax.annotation.Nullable") ||
                parsed.typing.isTypeName(it.nameAsString, "org.jetbrains.annotations.Nullable")
        }
    ) {
        return source
    }

    val plan = parsed.admitLegacyBody(method) ?: return source
    val parameterRange = parsed.range(level)?.first?.let { start ->
        parsed.range(tracker)?.last?.let { end -> start..end }
    } ?: return source
    val edits = listOfNotNull(
        parsed.replace(legacySelector, "\"$TC_NATIVE_SELECTOR\""),
        parsed.replace(legacyTarget, "\"$TC_NATIVE_TARGET\""),
        parsed.replace(
            method.type,
            "java.util.Optional<net.minecraft.gametest.framework.TestFunction>"
        ),
        TCEdit(
            parameterRange.first,
            parameterRange.last + 1,
            "net.minecraft.core.BlockPos pos, " +
                "net.minecraft.server.level.ServerLevel level, " +
                "net.minecraft.gametest.framework.RetryOptions retryOptions"
        ),
        parsed.replace(
            plan.fallback,
            "net.minecraft.gametest.framework.GameTestRegistry" +
                ".findTestFunction(${testName.nameAsString})"
        ),
        parsed.replace(
            plan.selected,
            "java.util.Optional.of(${plan.selected})"
        )
    )
    if (edits.size != 6) return source
    val migrated = tcApply(source, edits) ?: return source
    return removeUnusedSimpleImports(
        migrated,
        listOf("net.minecraft.gametest.framework.MultipleTestTracker")
    )
}

private const val TC_MIXIN =
    "com.simibubi.create.foundation.mixin.TestCommandMixin"
private const val TC_TEST_COMMAND =
    "net.minecraft.gametest.framework.TestCommand"
private const val TC_TEST_FUNCTION =
    "net.minecraft.gametest.framework.TestFunction"
private const val TC_GAME_TEST_REGISTRY =
    "net.minecraft.gametest.framework.GameTestRegistry"
private const val TC_MULTIPLE_TRACKER =
    "net.minecraft.gametest.framework.MultipleTestTracker"
private const val TC_RETRY_OPTIONS =
    "net.minecraft.gametest.framework.RetryOptions"
private const val TC_GAME_TEST_INFO =
    "net.minecraft.gametest.framework.GameTestInfo"
private const val TC_SERVER_LEVEL =
    "net.minecraft.server.level.ServerLevel"
private const val TC_BLOCK_POS =
    "net.minecraft.core.BlockPos"
private const val TC_STRUCTURE_BLOCK_ENTITY =
    "net.minecraft.world.level.block.entity.StructureBlockEntity"
private const val TC_COMPOUND_TAG =
    "net.minecraft.nbt.CompoundTag"
private const val TC_TAG =
    "net.minecraft.nbt.Tag"
private const val TC_CREATE_TEST_FUNCTION =
    "com.simibubi.create.infrastructure.gametest.CreateTestFunction"
private const val TC_REDIRECT =
    "org.spongepowered.asm.mixin.injection.Redirect"
private const val TC_AT =
    "org.spongepowered.asm.mixin.injection.At"
private const val TC_MIXIN_ANNOTATION =
    "org.spongepowered.asm.mixin.Mixin"

private const val TC_LEGACY_SELECTOR =
    "runTest(Lnet/minecraft/server/level/ServerLevel;" +
        "Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/gametest/framework/MultipleTestTracker;)V"
private const val TC_NATIVE_SELECTOR =
    "createGameTestInfo(Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/server/level/ServerLevel;" +
        "Lnet/minecraft/gametest/framework/RetryOptions;)Ljava/util/Optional;"
private const val TC_LEGACY_TARGET =
    "Lnet/minecraft/gametest/framework/GameTestRegistry;" +
        "getTestFunction(Ljava/lang/String;)" +
        "Lnet/minecraft/gametest/framework/TestFunction;"
private const val TC_NATIVE_TARGET =
    "Lnet/minecraft/gametest/framework/GameTestRegistry;" +
        "findTestFunction(Ljava/lang/String;)Ljava/util/Optional;"

private data class TCParsed(
    val source: String,
    val unit: com.github.javaparser.ast.CompilationUnit,
    val typing: SourceValueTypes,
    val lineStarts: List<Int>
) {
    val packageName: String =
        unit.packageDeclaration.map { it.nameAsString }.orElse("")

    fun range(node: Node): IntRange? = node.range.map {
        val start = lineStarts[it.begin.line - 1] + it.begin.column - 1
        val end = lineStarts[it.end.line - 1] + it.end.column - 1
        start..end
    }.orElse(null)

    fun replace(node: Node, replacement: String): TCEdit? =
        range(node)?.let { TCEdit(it.first, it.last + 1, replacement) }

    fun annotation(
        annotations: List<AnnotationExpr>,
        canonical: String
    ): AnnotationExpr? = annotations.singleOrNull {
        typing.isTypeName(it.nameAsString, canonical)
    }

    fun mixinOwner(canonical: String): ClassOrInterfaceDeclaration? =
        unit.findAll(ClassOrInterfaceDeclaration::class.java).filter { owner ->
            val mixin = annotation(owner.annotations, TC_MIXIN_ANNOTATION)
                ?: return@filter false
            val target = mixin.member("value") as? ClassExpr ?: return@filter false
            typing.isTypeName(target.type.asString(), canonical)
        }.singleOrNull()
}

private data class TCEdit(
    val start: Int,
    val endExclusive: Int,
    val replacement: String
)

private data class TCBodyPlan(
    val fallback: Expression,
    val selected: Expression
)

private fun StructuralRefactorPass.tcParse(source: String): TCParsed? {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return null
    val starts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    return TCParsed(source, unit, SourceValueTypes(this, source), starts)
}

private fun tcNativeShapeAvailable(): Boolean {
    val nativeCommand =
        "(Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/server/level/ServerLevel;" +
            "Lnet/minecraft/gametest/framework/RetryOptions;)" +
            "Ljava/util/Optional;"
    val legacyCommand =
        "(Lnet/minecraft/server/level/ServerLevel;" +
            "Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/gametest/framework/MultipleTestTracker;)V"
    val nativeLookup = "(Ljava/lang/String;)Ljava/util/Optional;"
    return nativeCommand in TargetMembers.methodDescriptors(
        TC_TEST_COMMAND,
        "createGameTestInfo"
    ) &&
        legacyCommand !in TargetMembers.methodDescriptors(TC_TEST_COMMAND, "runTest") &&
        nativeLookup in TargetMembers.methodDescriptors(
            TC_GAME_TEST_REGISTRY,
            "findTestFunction"
        )
}

private fun tcApply(source: String, edits: List<TCEdit>): String? {
    val ordered = edits.sortedWith(compareBy<TCEdit> { it.start }.thenBy { it.endExclusive })
    if (ordered.any {
            it.start < 0 ||
                it.endExclusive < it.start ||
                it.endExclusive > source.length
        } ||
        ordered.zipWithNext().any { (left, right) ->
            left.endExclusive > right.start
        }
    ) {
        return null
    }
    var result = source
    ordered.asReversed().forEach { edit ->
        result = result.substring(0, edit.start) +
            edit.replacement +
            result.substring(edit.endExclusive)
    }
    return result
}

private fun SourceValueTypes.isClassType(
    type: com.github.javaparser.ast.type.Type,
    canonical: String
): Boolean {
    val classType = type as? ClassOrInterfaceType ?: return false
    return isTypeName(classType.nameWithScope, canonical)
}

private fun AnnotationExpr.member(name: String): Expression? = when {
    isSingleMemberAnnotationExpr && name == "value" ->
        asSingleMemberAnnotationExpr().memberValue
    isNormalAnnotationExpr ->
        asNormalAnnotationExpr().pairs.singleOrNull {
            it.nameAsString == name
        }?.value
    else -> null
}

private fun AnnotationExpr.stringMember(name: String): Expression? =
    member(name)

private fun AnnotationExpr.stringValue(name: String): String? =
    (member(name) as? StringLiteralExpr)?.value

private fun Expression.tcUnwrap(): Expression {
    var current = this
    while (current is EnclosedExpr) current = current.inner
    return current
}

private fun Statement.tcSingleReturn(): ReturnStmt? = when (this) {
    is ReturnStmt -> this
    is BlockStmt -> statements.singleOrNull() as? ReturnStmt
    else -> null
}

private fun Statement.tcSingleThrow(): ThrowStmt? = when (this) {
    is ThrowStmt -> this
    is BlockStmt -> statements.singleOrNull() as? ThrowStmt
    else -> null
}

private fun Statement.tcVariable(): VariableDeclarator? {
    val declaration = (this as? ExpressionStmt)
        ?.expression as? VariableDeclarationExpr
        ?: return null
    return declaration.variables.singleOrNull()
}

private fun TCParsed.directCall(
    expression: Expression?,
    owner: String,
    method: String,
    argument: String
): MethodCallExpr? {
    val call = expression?.tcUnwrap() as? MethodCallExpr ?: return null
    if (call.nameAsString != method ||
        call.arguments.size != 1 ||
        (call.arguments.single().tcUnwrap() as? NameExpr)?.nameAsString != argument
    ) {
        return null
    }
    val scope = call.scope.orElse(null) ?: return null
    return call.takeIf { typing.isTypeName(scope.toString(), owner) }
}

private fun TCParsed.instanceCall(
    expression: Expression?,
    receiver: String,
    method: String,
    argument: String? = null
): MethodCallExpr? {
    val call = expression?.tcUnwrap() as? MethodCallExpr ?: return null
    if (call.nameAsString != method ||
        call.scope.orElse(null)?.tcUnwrap()?.toString() != receiver
    ) {
        return null
    }
    return when (argument) {
        null -> call.takeIf { call.arguments.isEmpty() }
        else -> call.takeIf {
            call.arguments.size == 1 &&
                (call.arguments.single().tcUnwrap() as? NameExpr)
                    ?.nameAsString == argument
        }
    }
}

private fun TCParsed.admitLegacyBody(method: MethodDeclaration): TCBodyPlan? {
    val statements = method.body.orElse(null)?.statements ?: return null
    if (statements.size != 7) return null

    val be = statements[0].tcVariable() ?: return null
    if (be.nameAsString != "be" ||
        !typing.isClassType(be.type, TC_STRUCTURE_BLOCK_ENTITY)
    ) {
        return null
    }
    val beCast = be.initializer.orElse(null)?.tcUnwrap() as? CastExpr
        ?: return null
    if (!typing.isClassType(beCast.type, TC_STRUCTURE_BLOCK_ENTITY) ||
        instanceCall(beCast.expression, "level", "getBlockEntity", "pos") == null
    ) {
        return null
    }

    val data = statements[1].tcVariable() ?: return null
    if (data.nameAsString != "data" ||
        !typing.isClassType(data.type, TC_COMPOUND_TAG) ||
        instanceCall(data.initializer.orElse(null), "be", "getPersistentData") == null
    ) {
        return null
    }

    val missing = statements[2] as? IfStmt ?: return null
    if (missing.elseStmt.isPresent) return null
    val missingCondition = missing.condition.tcUnwrap() as? UnaryExpr ?: return null
    if (missingCondition.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return null
    val contains = missingCondition.expression.tcUnwrap() as? MethodCallExpr
        ?: return null
    if (contains.nameAsString != "contains" ||
        contains.scope.orElse(null)?.tcUnwrap()?.toString() != "data" ||
        contains.arguments.size != 2 ||
        (contains.arguments[0].tcUnwrap() as? StringLiteralExpr)?.value !=
        "CreateTestFunction"
    ) {
        return null
    }
    val tagString = contains.arguments[1].tcUnwrap() as? FieldAccessExpr
        ?: return null
    if (tagString.nameAsString != "TAG_STRING" ||
        !typing.isTypeName(tagString.scope.toString(), TC_TAG)
    ) {
        return null
    }
    val fallback = missing.thenStmt.tcSingleReturn()
        ?.expression
        ?.orElse(null)
        ?: return null
    if (!admitFallback(fallback)) return null

    val name = statements[3].tcVariable() ?: return null
    if (name.nameAsString != "name" ||
        !typing.isClassType(name.type, "java.lang.String")
    ) {
        return null
    }
    val getString = name.initializer.orElse(null)?.tcUnwrap() as? MethodCallExpr
        ?: return null
    if (getString.nameAsString != "getString" ||
        getString.scope.orElse(null)?.tcUnwrap()?.toString() != "data" ||
        getString.arguments.size != 1 ||
        (getString.arguments.single().tcUnwrap() as? StringLiteralExpr)?.value !=
        "CreateTestFunction"
    ) {
        return null
    }

    val function = statements[4].tcVariable() ?: return null
    if (function.nameAsString != "function" ||
        !typing.isClassType(function.type, TC_CREATE_TEST_FUNCTION)
    ) {
        return null
    }
    val mapGet = function.initializer.orElse(null)?.tcUnwrap() as? MethodCallExpr
        ?: return null
    val names = mapGet.scope.orElse(null)?.tcUnwrap() as? FieldAccessExpr
        ?: return null
    if (mapGet.nameAsString != "get" ||
        mapGet.arguments.size != 1 ||
        (mapGet.arguments.single().tcUnwrap() as? NameExpr)?.nameAsString != "name" ||
        names.nameAsString != "NAMES_TO_FUNCTIONS" ||
        !typing.isTypeName(names.scope.toString(), TC_CREATE_TEST_FUNCTION)
    ) {
        return null
    }

    val absentFunction = statements[5] as? IfStmt ?: return null
    if (absentFunction.elseStmt.isPresent) return null
    val nullTest = absentFunction.condition.tcUnwrap() as? BinaryExpr
        ?: return null
    if (nullTest.operator != BinaryExpr.Operator.EQUALS ||
        (nullTest.left.tcUnwrap() as? NameExpr)?.nameAsString != "function" ||
        nullTest.right.tcUnwrap() !is NullLiteralExpr
    ) {
        return null
    }
    val thrown = absentFunction.thenStmt.tcSingleThrow()
        ?.expression
        ?.tcUnwrap() as? ObjectCreationExpr
        ?: return null
    val thrownType = thrown.type.nameWithScope
    val exactIllegalState = typing.isTypeName(
        thrownType,
        "java.lang.IllegalStateException"
    ) || (
        thrownType == "IllegalStateException" &&
            unit.imports.none {
                it.name.identifier == "IllegalStateException"
            } &&
            unit.findAll(ClassOrInterfaceDeclaration::class.java).none {
                it.nameAsString == "IllegalStateException"
            }
        )
    if (!exactIllegalState ||
        thrown.arguments.size != 1 ||
        thrown.arguments.single().toString().replace(Regex("""\s+"""), "") !=
        """"Structure block has CreateTestFunction attached, but test ["+name+"] doesn't exist""""
            .replace(Regex("""\s+"""), "")
    ) {
        return null
    }

    val selected = (statements[6] as? ReturnStmt)
        ?.expression
        ?.orElse(null)
        ?: return null
    val selectedValue = selected.tcUnwrap()
    val raw = (selectedValue as? NameExpr)?.nameAsString == "function"
    val adapted = (selectedValue as? MethodCallExpr)?.let { call ->
        call.nameAsString == "modporter\$asTestFunction" &&
            call.arguments.isEmpty() &&
            call.scope.orElse(null)?.tcUnwrap()?.toString() == "function"
    } == true
    if (!raw && !adapted) return null

    return TCBodyPlan(fallback, selected)
}

private fun TCParsed.admitFallback(expression: Expression): Boolean {
    if (directCall(
            expression,
            TC_GAME_TEST_REGISTRY,
            "getTestFunction",
            "testName"
        ) != null
    ) {
        return true
    }
    val unwrap = expression.tcUnwrap() as? MethodCallExpr ?: return false
    if (unwrap.nameAsString != "orElseThrow" ||
        unwrap.arguments.isNotEmpty() ||
        unwrap.typeArguments.isPresent
    ) {
        return false
    }
    return directCall(
        unwrap.scope.orElse(null),
        TC_GAME_TEST_REGISTRY,
        "findTestFunction",
        "testName"
    ) != null
}
