package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Adapts source-proven Codec/MapCodec expressions to exact DFU 8 expected
 * shapes. `MapCodec.codec()` keeps the map's encoded JSON while presenting it
 * as a Codec. `MapCodec.assumeMapUnsafe(codec)` exposes the codec's existing
 * map entries without inventing a wrapper field.
 *
 * Every expected shape comes from a canonical declaration or a verified target
 * descriptor: method return types, Codec.list, Feature's constructor,
 * Codec.lazyInitialized, or StructureProcessorType's functional return reached
 * through DeferredRegister<StructureProcessorType<?>>. Codec-to-MapCodec
 * conversion additionally requires a source-proven map-backed construction
 * such as Codec.unit, RecordCodecBuilder.create, or MapCodec.codec(). Field
 * names never authorize a conversion.
 */
internal object ExpectedCodecShapes {
    const val RULE_ID = "struct-expected-codec-shapes"
    private const val CODEC = "com.mojang.serialization.Codec"
    private const val MAP_CODEC = "com.mojang.serialization.MapCodec"
    private const val RECORD_CODEC_BUILDER =
        "com.mojang.serialization.codecs.RecordCodecBuilder"
    private const val FEATURE = "net.minecraft.world.level.levelgen.feature.Feature"
    private const val STRUCTURE_PROCESSOR_TYPE =
        "net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType"
    private const val DEFERRED_REGISTER =
        "net.neoforged.neoforge.registries.DeferredRegister"

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private enum class Shape {
        CODEC,
        MAP_CODEC,
        UNKNOWN
    }

    fun migrate(
        pass: StructuralRefactorPass,
        source: String,
        mapBackedCodecFields: Set<String> = emptySet()
    ): String {
        if (!source.contains("Codec") &&
            !source.contains("StructureProcessorType") &&
            !source.contains("Feature")
        ) {
            return source
        }
        val unit = parser.parse(source).result.orElse(null) ?: return source
        val typing = SourceValueTypes(pass, source)
        val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }
        fun offset(node: Node): Int = node.range
            .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
            .orElse(-1)
        fun endOffset(node: Node): Int = node.range
            .map { lineStarts[it.end.line - 1] + it.end.column - 1 }
            .orElse(-1)

        LexicalPreservingPrinter.setup(unit)
        var changed = false
        fun adapt(expression: Expression, expected: Shape): Boolean {
            val actual = expressionShape(expression, typing, ::offset)
            if (actual == expected || actual == Shape.UNKNOWN) return false
            when {
                actual == Shape.MAP_CODEC && expected == Shape.CODEC ->
                    appendCodecAdapter(expression)
                actual == Shape.CODEC && expected == Shape.MAP_CODEC ->
                    wrapMapCodecAdapter(expression)
                else -> return false
            }
            return true
        }

        // DFU 8: Codec.lazyInitialized(Supplier<Codec<A>>).
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (call.nameAsString != "lazyInitialized" ||
                call.arguments.size != 1 ||
                call.scope.isEmpty ||
                !typing.isTypeName(call.scope.get().toString(), CODEC)
            ) {
                continue
            }
            val supplier = call.arguments.single() as? LambdaExpr ?: continue
            val returns = supplierReturns(supplier) ?: continue
            val shaped = returns.map { expression ->
                expression to expressionShape(expression, typing, ::offset)
            }
            if (shaped.any { it.second == Shape.UNKNOWN }) continue

            val mapResults = shaped.filter { it.second == Shape.MAP_CODEC }.map { it.first }
            if (mapResults.isEmpty()) continue
            mapResults.forEach(::appendCodecAdapter)
            changed = true
        }

        // A canonical method return type is an exact expected shape.
        for (statement in unit.findAll(ReturnStmt::class.java).toList()) {
            val expression = statement.expression.orElse(null) ?: continue
            val method = owningMethod(statement) ?: continue
            if (declaredShape(method.type.toString(), typing) != Shape.CODEC) continue
            changed = adapt(expression, Shape.CODEC) || changed
        }

        // DFU 8: Codec.list(Codec<E>[, minSize, maxSize]).
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (call.nameAsString != "list" ||
                call.arguments.size !in setOf(1, 3) ||
                call.scope.isEmpty ||
                !typing.isTypeName(call.scope.get().toString(), CODEC)
            ) {
                continue
            }
            changed = adapt(call.arguments[0], Shape.CODEC) || changed
        }

        // Target 1.21 Feature(Codec<FC>).
        for (invocation in unit.findAll(ExplicitConstructorInvocationStmt::class.java).toList()) {
            if (invocation.isThis || invocation.expression.isPresent || invocation.arguments.size != 1) {
                continue
            }
            val owner = invocation.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .orElse(null)
                ?: continue
            if (owner.extendedTypes.none { typing.isTypeName(it.nameWithScope, FEATURE) }) continue
            changed = adapt(invocation.arguments.single(), Shape.CODEC) || changed
        }

        // Target 1.21 StructureProcessorType<P>.codec() returns MapCodec<P>.
        // NeoForge DeferredRegister<T>.register(String, Supplier<? extends I>)
        // proves the nested zero-argument lambda's functional target when the
        // receiver is declared as DeferredRegister<StructureProcessorType<?>>.
        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            val expression = structureProcessorCodecResult(
                    call,
                    unit.findAll(VariableDeclarator::class.java),
                    typing,
                    ::offset,
                    ::endOffset
                )
                ?: continue
            if (!isMapBackedCodecExpression(
                    expression,
                    typing,
                    ::offset,
                    mapBackedCodecFields
                )
            ) continue
            changed = adapt(expression, Shape.MAP_CODEC) || changed
        }
        return if (changed) LexicalPreservingPrinter.print(unit) else source
    }

    internal fun collectMapBackedCodecFields(
        pass: StructuralRefactorPass,
        files: List<Path>
    ): Set<String> = buildSet {
        for (file in files) {
            val source = runCatching { file.readText() }.getOrNull() ?: continue
            if (!source.contains("Codec")) continue
            val unit = parser.parse(source).result.orElse(null) ?: continue
            val typing = SourceValueTypes(pass, source)
            val lineStarts = buildList {
                add(0)
                source.forEachIndexed { index, character ->
                    if (character == '\n') add(index + 1)
                }
            }
            fun offset(node: Node): Int = node.range
                .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
                .orElse(-1)

            for (variable in unit.findAll(VariableDeclarator::class.java)) {
                if (variable.parentNode.orElse(null) !is
                    com.github.javaparser.ast.body.FieldDeclaration
                ) continue
                if (declaredShape(variable.type.toString(), typing) != Shape.CODEC) continue
                val initializer = variable.initializer.orElse(null) ?: continue
                if (!isDirectMapBackedConstruction(initializer, typing, ::offset)) continue
                val owner = declaredOwner(variable, unit) ?: continue
                add(codecFieldKey(owner, variable.nameAsString))
            }
        }
    }

    private fun owningMethod(statement: ReturnStmt): MethodDeclaration? {
        var current: Node? = statement.parentNode.orElse(null)
        while (current != null) {
            when (current) {
                is LambdaExpr, is ConstructorDeclaration -> return null
                is MethodDeclaration -> return current
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun declaredShape(type: String, typing: SourceValueTypes): Shape {
        val raw = type.substringBefore('<').trim()
        return when {
            typing.isTypeName(raw, CODEC) -> Shape.CODEC
            typing.isTypeName(raw, MAP_CODEC) -> Shape.MAP_CODEC
            else -> Shape.UNKNOWN
        }
    }

    private fun structureProcessorCodecResult(
        call: MethodCallExpr,
        variables: List<VariableDeclarator>,
        typing: SourceValueTypes,
        offset: (Node) -> Int,
        endOffset: (Node) -> Int
    ): Expression? {
        if (call.nameAsString != "register" ||
            call.arguments.size != 2 ||
            call.scope.isEmpty
        ) {
            return null
        }
        val outer = unwrapExpression(call.arguments[1]) as? LambdaExpr ?: return null
        if (outer.parameters.isNotEmpty()) return null
        val inner = outer.expressionBody.orElse(null)
            ?.let(::unwrapExpression) as? LambdaExpr
            ?: return null
        if (inner.parameters.isNotEmpty()) return null
        val result = inner.expressionBody.orElse(null) ?: return null
        val scope = call.scope.get()
        val declaration = visibleVariable(scope, variables, offset, endOffset) ?: return null
        val type = declaration.type as? ClassOrInterfaceType ?: return null
        if (!typing.isTypeName(type.nameWithScope, DEFERRED_REGISTER)) return null
        val element = type.typeArguments.orElse(null)
            ?.singleOrNull()
            ?.takeIf { it.isClassOrInterfaceType }
            ?.asClassOrInterfaceType()
            ?: return null
        if (!typing.isTypeName(element.nameWithScope, STRUCTURE_PROCESSOR_TYPE)) return null
        return result
    }

    private fun unwrapExpression(expression: Expression): Expression = when (expression) {
        is EnclosedExpr -> unwrapExpression(expression.inner)
        is CastExpr -> unwrapExpression(expression.expression)
        else -> expression
    }

    private fun isMapBackedCodecExpression(
        expression: Expression,
        typing: SourceValueTypes,
        offset: (Node) -> Int,
        mapBackedCodecFields: Set<String>
    ): Boolean {
        if (isDirectMapBackedConstruction(expression, typing, offset)) return true
        return when (expression) {
            is EnclosedExpr -> isMapBackedCodecExpression(
                expression.inner,
                typing,
                offset,
                mapBackedCodecFields
            )
            is CastExpr -> isMapBackedCodecExpression(
                expression.expression,
                typing,
                offset,
                mapBackedCodecFields
            )
            is ConditionalExpr ->
                isMapBackedCodecExpression(
                    expression.thenExpr,
                    typing,
                    offset,
                    mapBackedCodecFields
                ) &&
                    isMapBackedCodecExpression(
                        expression.elseExpr,
                        typing,
                        offset,
                        mapBackedCodecFields
                    )
            is FieldAccessExpr -> mapBackedCodecFields.any { key ->
                val separator = key.lastIndexOf('#')
                separator > 0 &&
                    key.substring(separator + 1) == expression.nameAsString &&
                    typing.isTypeName(expression.scope.toString(), key.substring(0, separator))
            }
            is MethodCallExpr -> {
                val receiver = expression.scope.orElse(null) ?: return false
                preservesEncodedShape(expression) &&
                    isMapBackedCodecExpression(
                        receiver,
                        typing,
                        offset,
                        mapBackedCodecFields
                    )
            }
            else -> false
        }
    }

    private fun isDirectMapBackedConstruction(
        expression: Expression,
        typing: SourceValueTypes,
        offset: (Node) -> Int
    ): Boolean {
        return when (expression) {
            is EnclosedExpr -> isDirectMapBackedConstruction(expression.inner, typing, offset)
            is CastExpr -> isDirectMapBackedConstruction(expression.expression, typing, offset)
            is ConditionalExpr ->
                isDirectMapBackedConstruction(expression.thenExpr, typing, offset) &&
                    isDirectMapBackedConstruction(expression.elseExpr, typing, offset)
            is MethodCallExpr -> {
                val receiver = expression.scope.orElse(null) ?: return false
                when {
                    expression.nameAsString == "unit" &&
                        expression.arguments.size == 1 &&
                        typing.isTypeName(receiver.toString(), CODEC) -> true
                    expression.nameAsString == "create" &&
                        expression.arguments.size == 1 &&
                        typing.isTypeName(receiver.toString(), RECORD_CODEC_BUILDER) -> true
                    expression.nameAsString == "codec" &&
                        expression.arguments.isEmpty() &&
                        expressionShape(receiver, typing, offset) == Shape.MAP_CODEC -> true
                    preservesEncodedShape(expression) ->
                        isDirectMapBackedConstruction(receiver, typing, offset)
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun preservesEncodedShape(call: MethodCallExpr): Boolean = when {
        call.nameAsString in setOf("xmap", "flatXmap") && call.arguments.size == 2 -> true
        call.nameAsString in setOf("validate", "withLifecycle", "deprecated") &&
            call.arguments.size == 1 -> true
        call.nameAsString == "stable" && call.arguments.isEmpty() -> true
        else -> false
    }

    private fun declaredOwner(
        variable: VariableDeclarator,
        unit: CompilationUnit
    ): String? {
        val names = mutableListOf<String>()
        var current: TypeDeclaration<*>? =
            variable.findAncestor(TypeDeclaration::class.java).orElse(null)
        while (current != null) {
            names += current.nameAsString
            current = current.findAncestor(TypeDeclaration::class.java).orElse(null)
        }
        if (names.isEmpty()) return null
        val local = names.asReversed().joinToString(".")
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        return if (packageName.isBlank()) local else "$packageName.$local"
    }

    private fun codecFieldKey(owner: String, name: String): String = "$owner#$name"

    private fun visibleVariable(
        expression: Expression,
        variables: List<VariableDeclarator>,
        offset: (Node) -> Int,
        endOffset: (Node) -> Int
    ): VariableDeclarator? {
        val name = when (expression) {
            is NameExpr -> expression.nameAsString
            is FieldAccessExpr ->
                expression.nameAsString.takeIf { expression.scope is ThisExpr }
            else -> null
        } ?: return null
        val use = offset(expression)
        return variables.asSequence()
            .filter { it.nameAsString == name }
            .mapNotNull { variable ->
                val scope = bindingScope(variable) ?: return@mapNotNull null
                val field = variable.parentNode.orElse(null) is com.github.javaparser.ast.body.FieldDeclaration
                val start = offset(scope)
                val end = endOffset(scope)
                if (use !in start..end || !field && offset(variable) > use) return@mapNotNull null
                Triple(variable, end - start, offset(variable))
            }
            .sortedWith(compareBy<Triple<VariableDeclarator, Int, Int>> { it.second }
                .thenByDescending { it.third })
            .map { it.first }
            .firstOrNull()
    }

    private fun bindingScope(node: Node): Node? {
        var parent = node.parentNode.orElse(null)
        while (parent != null) {
            if (parent is BlockStmt ||
                parent is MethodDeclaration ||
                parent is ConstructorDeclaration ||
                parent is LambdaExpr ||
                parent is ForStmt ||
                parent is ForEachStmt ||
                parent is CatchClause ||
                parent is TypeDeclaration<*>
            ) {
                return parent
            }
            parent = parent.parentNode.orElse(null)
        }
        return null
    }

    private fun supplierReturns(lambda: LambdaExpr): List<Expression>? {
        lambda.expressionBody.orElse(null)?.let { return listOf(it) }
        val returns = lambda.body.findAll(ReturnStmt::class.java)
            .filter { returnBelongsTo(it, lambda) }
        if (returns.isEmpty() || returns.any { it.expression.isEmpty }) return null
        return returns.map { it.expression.get() }
    }

    private fun returnBelongsTo(statement: ReturnStmt, lambda: LambdaExpr): Boolean {
        var current: Node? = statement.parentNode.orElse(null)
        while (current != null) {
            when (current) {
                is LambdaExpr -> return current === lambda
                is MethodDeclaration, is ConstructorDeclaration -> return false
            }
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun expressionShape(
        expression: Expression,
        typing: SourceValueTypes,
        offset: (Node) -> Int
    ): Shape {
        when (typing.typeOf(expression.toString(), offset(expression))) {
            CODEC -> return Shape.CODEC
            MAP_CODEC -> return Shape.MAP_CODEC
        }
        return when (expression) {
            is EnclosedExpr -> expressionShape(expression.inner, typing, offset)
            is ConditionalExpr -> {
                val thenShape = expressionShape(expression.thenExpr, typing, offset)
                val elseShape = expressionShape(expression.elseExpr, typing, offset)
                if (thenShape == elseShape) thenShape else Shape.UNKNOWN
            }
            is MethodCallExpr -> methodShape(expression, typing, offset)
            else -> Shape.UNKNOWN
        }
    }

    private fun methodShape(
        call: MethodCallExpr,
        typing: SourceValueTypes,
        offset: (Node) -> Int
    ): Shape {
        val receiver = call.scope.orElse(null) ?: return Shape.UNKNOWN
        val receiverShape = expressionShape(receiver, typing, offset)
        return when {
            call.nameAsString == "fieldOf" &&
                call.arguments.size == 1 &&
                receiverShape == Shape.CODEC -> Shape.MAP_CODEC
            call.nameAsString == "optionalFieldOf" &&
                call.arguments.size in 1..4 &&
                receiverShape == Shape.CODEC -> Shape.MAP_CODEC
            call.nameAsString in setOf("xmap", "flatXmap") &&
                call.arguments.size == 2 &&
                receiverShape in setOf(Shape.CODEC, Shape.MAP_CODEC) -> receiverShape
            call.nameAsString == "validate" &&
                call.arguments.size == 1 &&
                receiverShape in setOf(Shape.CODEC, Shape.MAP_CODEC) -> receiverShape
            call.nameAsString == "codec" &&
                call.arguments.isEmpty() &&
                receiverShape == Shape.MAP_CODEC -> Shape.CODEC
            else -> Shape.UNKNOWN
        }
    }

    private fun appendCodecAdapter(expression: Expression) {
        val scope = when (expression) {
            is ConditionalExpr -> EnclosedExpr(expression.clone())
            else -> expression.clone()
        }
        expression.replace(MethodCallExpr(scope, "codec"))
    }

    private fun wrapMapCodecAdapter(expression: Expression) {
        val canonical = "com.mojang.serialization.MapCodec".split('.')
            .drop(1)
            .fold(NameExpr("com") as Expression) { scope, name ->
                FieldAccessExpr(scope, name)
            }
        expression.replace(
            MethodCallExpr(canonical, "assumeMapUnsafe")
                .addArgument(expression.clone())
        )
    }
}

/**
 * Late project hook. It refreshes source value facts after MapCodec-family
 * migrations have finished, then evaluates every current Java source against
 * those exact declarations.
 */
internal fun StructuralRefactorPass.migrateProjectExpectedCodecShapes(
    projectDir: Path,
    dryRun: Boolean
): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.isDirectory()) return emptyList()
    val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths ->
        paths.filter { it.extension == "java" }
            .filter {
                !it.toString().replace('\\', '/')
                    .contains("/com/modporter/generated/")
            }
            .toList()
    }
    if (files.isEmpty()) return emptyList()

    projectValueFacts = ProjectValueFacts.collect(this, files)
    val mapBackedCodecFields = ExpectedCodecShapes.collectMapBackedCodecFields(this, files)
    val changes = mutableListOf<Change>()
    for (file in files) {
        val source = runCatching { file.readText() }.getOrNull() ?: continue
        val migrated = ExpectedCodecShapes.migrate(this, source, mapBackedCodecFields)
        if (migrated == source) continue
        if (!dryRun) file.writeText(migrated)
        changes += Change(
            file = file,
            line = JavaSourceText.lineNumberAt(
                source,
                source.indexOf("Codec").coerceAtLeast(0)
            ),
            description = "Adapt source-proven Codec/MapCodec expressions to exact DFU 8 expected shapes",
            before = "Codec or MapCodec expression with the opposite expected shape",
            after = "Shape-preserving DFU 8 Codec/MapCodec adapter",
            confidence = Confidence.HIGH,
            ruleId = ExpectedCodecShapes.RULE_ID
        )
    }
    return changes
}
