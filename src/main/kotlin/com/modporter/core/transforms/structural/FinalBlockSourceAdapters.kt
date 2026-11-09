package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val OLD_BLOCK_SOURCE = "net.minecraft.core.BlockSource"
private const val TARGET_BLOCK_SOURCE = "net.minecraft.core.dispenser.BlockSource"
private const val BLOCK_POS = "net.minecraft.core.BlockPos"
private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
private const val BLOCK_ENTITY = "net.minecraft.world.level.block.entity.BlockEntity"
private const val DISPENSER_BLOCK_ENTITY =
    "net.minecraft.world.level.block.entity.DispenserBlockEntity"
private const val SERVER_LEVEL = "net.minecraft.server.level.ServerLevel"
private const val VEC3 = "net.minecraft.world.phys.Vec3"

private const val TARGET_BLOCK_SOURCE_CONSTRUCTOR =
    "(Lnet/minecraft/server/level/ServerLevel;" +
        "Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/state/BlockState;" +
        "Lnet/minecraft/world/level/block/entity/DispenserBlockEntity;)V"
private const val TARGET_BLOCK_SOURCE_DESCRIPTOR =
    "Lnet/minecraft/core/dispenser/BlockSource;"

/**
 * Replaces a closed source implementation of the removed BlockSource
 * interface with a source wrapper that creates the final target record at an
 * explicit native boundary.
 *
 * The adapter is deliberately fresh rather than cached: source getLevel(),
 * getPos(), and getBlockState() are evaluated for every conversion. Admission
 * proves that x/y/z are the center of the same final BlockPos and that the old
 * generic getEntity() returns null.
 */
internal object FinalBlockSourceAdapters {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        if (!targetContractIsVerified()) return emptyList()

        val javaFiles = javaFiles(projectDir)
        if (javaFiles.none { it.readText().contains("BlockSource") }) return emptyList()

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val parsed = javaFiles.map { path ->
            val source = path.readText()
            val result = parser.parse(source)
            if (!result.isSuccessful || result.result.isEmpty) {
                val problems = result.problems.joinToString("; ") { it.verboseMessage }
                failBlockSource(
                    "Final BlockSource adapter preflight requires every Java source to parse; " +
                        "$path failed: ${problems.ifBlank { "unknown parse failure" }}"
                )
            }
            BlockSourceParsed(path, source, result.result.orElseThrow())
        }
        val index = BlockSourceTypeIndex(parsed)
        val candidates = parsed.flatMap { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull { owner ->
                if (owner.isInterface) return@mapNotNull null
                val implemented = owner.implementedTypes.filter {
                    index.resolve(it, file) in sourceBlockSourceTypes
                }
                when {
                    implemented.isEmpty() -> null
                    implemented.size == 1 -> Triple(owner, file, implemented.single())
                    else -> failBlockSource(
                        "${blockSourceCanonicalName(owner, file.unit) ?: owner.nameAsString} " +
                            "declares BlockSource more than once"
                    )
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val candidateNames = candidates.associate { (owner, file, implemented) ->
            val canonical = blockSourceCanonicalName(owner, file.unit)
                ?: failBlockSource(
                    "Unnamed or local BlockSource implementation is unsupported in ${file.path}"
                )
            canonical to Triple(owner, file, implemented)
        }
        rejectBlockSourceChildren(parsed, index, candidateNames.keys)
        val plans = candidateNames.mapValues { (canonical, triple) ->
            planBlockSourceCandidate(
                canonical,
                triple.first,
                triple.second,
                triple.third,
                index
            )
        }

        parsed.forEach { LexicalPreservingPrinter.setup(it.unit) }
        val localWrappers = rewriteBlockSourceBoundaries(parsed, index, plans)
        auditBlockSourceFlows(parsed, index, plans, localWrappers)
        plans.values.forEach(::applyBlockSourcePlan)
        normalizeLegacyBlockSourceTypes(parsed, index, plans)

        val changed = parsed.mapNotNull { file ->
            val migrated = LexicalPreservingPrinter.print(file.unit)
            if (migrated == file.source) null else BlockSourcePrepared(file, migrated)
        }
        if (!dryRun) {
            changed.forEach { it.file.path.writeText(it.migrated) }
        }
        return changed.map { prepared ->
            Change(
                file = prepared.file.path,
                line = 1,
                description =
                    "Replace a legacy BlockSource implementation with a fresh native-record adapter",
                before =
                    "Source class implements the removed mutable BlockSource interface",
                after =
                    "Source wrapper preserves getters and native boundaries receive BlockSource",
                confidence = Confidence.HIGH,
                ruleId = "struct-final-block-source-adapter"
            )
        }
    }

    private fun targetContractIsVerified(): Boolean {
        val accessors = mapOf(
            "level" to "()Lnet/minecraft/server/level/ServerLevel;",
            "pos" to "()Lnet/minecraft/core/BlockPos;",
            "state" to "()Lnet/minecraft/world/level/block/state/BlockState;",
            "blockEntity" to
                "()Lnet/minecraft/world/level/block/entity/DispenserBlockEntity;",
            "center" to "()Lnet/minecraft/world/phys/Vec3;"
        )
        return TargetMembers.inheritsFrom(TARGET_BLOCK_SOURCE, "java.lang.Record") &&
            TARGET_BLOCK_SOURCE_CONSTRUCTOR in
            TargetMembers.methodDescriptors(TARGET_BLOCK_SOURCE, "<init>") &&
            accessors.all { (name, descriptor) ->
                descriptor in TargetMembers.methodDescriptors(TARGET_BLOCK_SOURCE, name)
            } &&
            sourceBlockSourceMethods.none {
                TargetMembers.hasMethod(TARGET_BLOCK_SOURCE, it)
            }
    }

    private fun javaFiles(projectDir: Path): List<Path> =
        Files.walk(projectDir).use { stream ->
            stream.filter { it.extension == "java" }
                .filter { path ->
                    val relative = projectDir.relativize(path).toString().replace('\\', '/')
                    !relative.startsWith("build/") &&
                        !relative.contains("/build/") &&
                        !relative.startsWith("src/references/") &&
                        !relative.contains("/src/references/") &&
                        !relative.startsWith("src/main/java/com/modporter/generated/")
                }
                .toList()
        }

    private fun rejectBlockSourceChildren(
        parsed: List<BlockSourceParsed>,
        index: BlockSourceTypeIndex,
        candidates: Set<String>
    ) {
        parsed.forEach { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java).forEach { owner ->
                owner.extendedTypes.forEach { parent ->
                    val resolved = index.resolve(parent, file)
                    if (resolved in candidates) {
                        failBlockSource(
                            "Cannot close $resolved as a BlockSource wrapper while source " +
                                "${blockSourceCanonicalName(owner, file.unit) ?: owner.nameAsString} " +
                                "extends it"
                        )
                    }
                }
            }
            file.unit.findAll(ObjectCreationExpr::class.java).firstOrNull {
                it.anonymousClassBody.isPresent &&
                    index.resolve(it.type, file) in candidates
            }?.let {
                failBlockSource(
                    "Anonymous source subclass of ${index.resolve(it.type, file)} prevents " +
                        "the closed BlockSource adapter"
                )
            }
        }
    }

    private fun planBlockSourceCandidate(
        canonical: String,
        owner: ClassOrInterfaceDeclaration,
        file: BlockSourceParsed,
        implemented: ClassOrInterfaceType,
        index: BlockSourceTypeIndex
    ): BlockSourcePlan {
        if (owner.isAbstract) {
            failBlockSource("$canonical is abstract; a closed concrete wrapper is required")
        }
        if (owner.constructors.isEmpty()) {
            failBlockSource("$canonical has no explicit constructor")
        }
        if (owner.fields.any { !it.isStatic && !it.isFinal }) {
            failBlockSource(
                "$canonical must keep all instance fields final for a closed native capture"
            )
        }

        val x = requiredBlockSourceMethod(owner, file, index, canonical, "x", "double")
        val y = requiredBlockSourceMethod(owner, file, index, canonical, "y", "double")
        val z = requiredBlockSourceMethod(owner, file, index, canonical, "z", "double")
        val getPos = requiredBlockSourceMethod(
            owner,
            file,
            index,
            canonical,
            "getPos",
            BLOCK_POS
        )
        val getBlockState = requiredBlockSourceMethod(
            owner,
            file,
            index,
            canonical,
            "getBlockState",
            BLOCK_STATE
        )
        val getLevel = requiredBlockSourceMethod(
            owner,
            file,
            index,
            canonical,
            "getLevel",
            SERVER_LEVEL
        )
        val getEntity = requiredNullEntityMethod(owner, file, index, canonical)

        val positionField = stablePositionField(getPos, owner, file, index, canonical)
        validateCenteredCoordinate(x, positionField, "getX", canonical)
        validateCenteredCoordinate(y, positionField, "getY", canonical)
        validateCenteredCoordinate(z, positionField, "getZ", canonical)

        val used = owner.findAll(SimpleName::class.java)
            .mapTo(linkedSetOf()) { it.asString() }
        val accessor = uniqueBlockSourceName("modporter\$asBlockSource", used)
        return BlockSourcePlan(
            canonical = canonical,
            owner = owner,
            file = file,
            implemented = implemented,
            sourceMethods = listOf(
                x,
                y,
                z,
                getPos,
                getBlockState,
                getEntity,
                getLevel
            ),
            accessor = accessor
        )
    }

    private fun requiredBlockSourceMethod(
        owner: ClassOrInterfaceDeclaration,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex,
        canonical: String,
        name: String,
        returnType: String
    ): MethodDeclaration {
        val declarations = owner.methods.filter {
            it.nameAsString == name && it.parameters.isEmpty()
        }
        if (declarations.size != 1) {
            failBlockSource(
                "$canonical must declare exactly one no-argument $name() method"
            )
        }
        val method = declarations.single()
        val actualReturn = if (method.type.isPrimitiveType) {
            method.type.asString()
        } else {
            index.resolve(method.type, file)
        }
        if (
            !method.isPublic ||
            method.isStatic ||
            !method.body.isPresent ||
            actualReturn != returnType
        ) {
            failBlockSource(
                "$canonical.$name must be a public instance method returning $returnType"
            )
        }
        return method
    }

    private fun requiredNullEntityMethod(
        owner: ClassOrInterfaceDeclaration,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex,
        canonical: String
    ): MethodDeclaration {
        val declarations = owner.methods.filter {
            it.nameAsString == "getEntity" && it.parameters.isEmpty()
        }
        if (declarations.size != 1) {
            failBlockSource(
                "$canonical must declare exactly one no-argument getEntity() method"
            )
        }
        val method = declarations.single()
        val typeParameter = method.typeParameters.singleOrNull()
        val bound = typeParameter?.typeBound?.singleOrNull()
        val body = method.body.orElse(null)
        val returned = body?.statements?.singleOrNull() as? ReturnStmt
        if (
            !method.isPublic ||
            method.isStatic ||
            typeParameter == null ||
            method.type.asString() != typeParameter.nameAsString ||
            bound == null ||
            index.resolve(bound, file) != BLOCK_ENTITY ||
            returned?.expression?.orElse(null) !is NullLiteralExpr
        ) {
            failBlockSource(
                "$canonical.getEntity must retain the old generic BlockEntity bound and " +
                    "return null unconditionally"
            )
        }
        return method
    }

    private fun stablePositionField(
        method: MethodDeclaration,
        owner: ClassOrInterfaceDeclaration,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex,
        canonical: String
    ): String {
        val body = method.body.orElseThrow()
        val returned = (body.statements.singleOrNull() as? ReturnStmt)
            ?.expression?.orElse(null)
            ?: failBlockSource(
                "$canonical.getPos must return one final BlockPos field"
            )
        val fieldName = sourceFieldName(returned)
            ?: failBlockSource(
                "$canonical.getPos must return one final BlockPos field"
            )
        val fields = owner.fields.filter { field ->
            field.isFinal &&
                field.variables.any { variable ->
                    variable.nameAsString == fieldName &&
                        index.resolve(variable.type, file) == BLOCK_POS
                }
        }
        if (fields.size != 1) {
            failBlockSource(
                "$canonical.getPos field $fieldName is not one final BlockPos field"
            )
        }
        return fieldName
    }

    private fun validateCenteredCoordinate(
        method: MethodDeclaration,
        positionField: String,
        coordinateGetter: String,
        canonical: String
    ) {
        val returned = (method.body.orElseThrow().statements.singleOrNull() as? ReturnStmt)
            ?.expression?.orElse(null)
            ?: failBlockSource(
                "$canonical.${method.nameAsString} must return the BlockPos center coordinate"
            )
        val binary = unwrapBlockSourceEnclosed(returned) as? BinaryExpr
            ?: failBlockSource(
                "$canonical.${method.nameAsString} must return the BlockPos center coordinate"
            )
        if (binary.operator != BinaryExpr.Operator.PLUS) {
            failBlockSource(
                "$canonical.${method.nameAsString} must add exactly 0.5 to $coordinateGetter"
            )
        }
        val leftHalf = isHalfLiteral(binary.left)
        val rightHalf = isHalfLiteral(binary.right)
        val coordinate = when {
            leftHalf && !rightHalf -> binary.right
            rightHalf && !leftHalf -> binary.left
            else -> failBlockSource(
                "$canonical.${method.nameAsString} must add exactly one 0.5 literal"
            )
        }
        val call = unwrapDoubleCast(coordinate) as? MethodCallExpr
            ?: failBlockSource(
                "$canonical.${method.nameAsString} must read $coordinateGetter from getPos"
            )
        if (
            call.nameAsString != coordinateGetter ||
            call.arguments.isNotEmpty() ||
            sourceFieldName(call.scope.orElse(null)) != positionField
        ) {
            failBlockSource(
                "$canonical.${method.nameAsString} must read $coordinateGetter from the " +
                    "same final BlockPos returned by getPos"
            )
        }
    }

    private fun isHalfLiteral(expression: Expression): Boolean {
        val literal = unwrapBlockSourceEnclosed(expression) as? DoubleLiteralExpr
            ?: return false
        return runCatching { literal.asDouble() }.getOrNull() == 0.5
    }

    private fun unwrapDoubleCast(expression: Expression): Expression {
        var current = unwrapBlockSourceEnclosed(expression)
        if (current is CastExpr && current.type.asString() == "double") {
            current = unwrapBlockSourceEnclosed(current.expression)
        }
        return current
    }

    private fun unwrapBlockSourceEnclosed(expression: Expression): Expression {
        var current = expression
        while (current is EnclosedExpr) current = current.inner
        return current
    }

    private fun sourceFieldName(expression: Expression?): String? =
        when (val current = expression?.let(::unwrapBlockSourceEnclosed)) {
            is NameExpr -> current.nameAsString
            is FieldAccessExpr ->
                current.nameAsString.takeIf { current.scope is ThisExpr }
            else -> null
        }

    private fun normalizeLegacyBlockSourceTypes(
        parsed: List<BlockSourceParsed>,
        index: BlockSourceTypeIndex,
        plans: Map<String, BlockSourcePlan>
    ) {
        val candidatePaths = plans.values.mapTo(hashSetOf()) { it.file.path }
        val accessors = plans.values.mapTo(hashSetOf()) { it.accessor }
        parsed.filter { file ->
            file.path in candidatePaths ||
                file.unit.findAll(MethodCallExpr::class.java).any {
                    it.nameAsString in accessors
                }
        }.forEach { file ->
            file.unit.findAll(ClassOrInterfaceType::class.java)
                .filter { index.resolve(it, file) == OLD_BLOCK_SOURCE }
                .forEach { old ->
                    old.replace(
                        StaticJavaParser.parseClassOrInterfaceType(TARGET_BLOCK_SOURCE)
                    )
                }
            file.unit.imports.removeIf {
                !it.isStatic && !it.isAsterisk && it.nameAsString == OLD_BLOCK_SOURCE
            }
        }
    }

    private fun rewriteBlockSourceBoundaries(
        parsed: List<BlockSourceParsed>,
        index: BlockSourceTypeIndex,
        plans: Map<String, BlockSourcePlan>
    ): Set<VariableDeclarator> {
        val localWrappers = linkedSetOf<VariableDeclarator>()
        parsed.forEach { file ->
            /*
             * A source interface local is not itself the native boundary. Keep
             * construction in its original statement, narrow the local to the
             * concrete wrapper, and capture the record at its one proven
             * target use. This keeps source getters inside an original try
             * block such as DispenseItemBehavior#dispense(...).
             */
            file.unit.findAll(VariableDeclarator::class.java).toList()
                .forEach { variable ->
                    val initializer = variable.initializer.orElse(null)
                        ?: return@forEach
                    if (index.resolve(variable.type, file) !in sourceBlockSourceTypes) {
                        return@forEach
                    }
                    val wrapper = blockSourceWrapperTypeOf(
                        initializer,
                        file,
                        index,
                        plans
                    )
                    if (wrapper == null) {
                        rejectNestedBlockSourceWrapper(
                            initializer,
                            file,
                            index,
                            plans,
                            "native BlockSource initializer"
                        )
                        return@forEach
                    }
                    val creation = unwrapBlockSourceEnclosed(initializer)
                        as? ObjectCreationExpr
                        ?: failBlockSource(
                            "Native BlockSource local ${variable.nameAsString} must be " +
                                "initialized by direct construction of $wrapper in ${file.path}"
                        )
                    val declaration = variable.findAncestor(
                        com.github.javaparser.ast.expr.VariableDeclarationExpr::class.java
                    ).orElse(null)
                    if (
                        declaration == null ||
                        declaration.variables.size != 1 ||
                        declaration.parentNode.orElse(null) !is ExpressionStmt
                    ) {
                        failBlockSource(
                            "Native BlockSource local ${variable.nameAsString} must be one " +
                                "standalone declaration in ${file.path}"
                        )
                    }
                    val uses = file.unit.findAll(NameExpr::class.java).filter {
                        declaredBlockSourceVariableOfName(it) === variable
                    }
                    if (uses.size != 1) {
                        failBlockSource(
                            "Native BlockSource local ${variable.nameAsString} must have one " +
                                "closed native use, found ${uses.size} in ${file.path}"
                        )
                    }
                    val use = uses.single()
                    val call = use.parentNode.orElse(null) as? MethodCallExpr
                        ?: failBlockSource(
                            "Native BlockSource local ${variable.nameAsString} must flow " +
                                "directly to a target method argument in ${file.path}"
                        )
                    val argument = call.arguments.indexOfFirst { it === use }
                    if (
                        argument < 0 ||
                        !isNativeBlockSourceArgument(call, argument, file, index)
                    ) {
                        failBlockSource(
                            "Native BlockSource local ${variable.nameAsString} has no " +
                                "declaration-proven target BlockSource argument in ${file.path}"
                        )
                    }
                    variable.setType(creation.type.clone())
                    call.setArgument(
                        argument,
                        wrapBlockSourceNative(use, plans.getValue(wrapper))
                    )
                    localWrappers += variable
                }

            file.unit.findAll(MethodCallExpr::class.java).toList().forEach { call ->
                call.arguments.toList().forEachIndexed { argument, expression ->
                    val wrapper = blockSourceWrapperTypeOf(
                        expression,
                        file,
                        index,
                        plans
                    ) ?: return@forEachIndexed
                    if (!isNativeBlockSourceArgument(call, argument, file, index)) {
                        failBlockSource(
                            "Passing source BlockSource wrapper $wrapper to " +
                                "${call.nameAsString}(...) has no declaration-proven target " +
                                "BlockSource parameter in ${file.path}"
                        )
                    }
                    call.setArgument(
                        argument,
                        wrapBlockSourceNative(expression, plans.getValue(wrapper))
                    )
                }
            }

            file.unit.findAll(ReturnStmt::class.java).forEach { statement ->
                val expression = statement.expression.orElse(null) ?: return@forEach
                val method = nearestBlockSourceMethodWithoutLambda(statement)
                    ?: return@forEach
                if (index.resolve(method.type, file) !in sourceBlockSourceTypes) {
                    return@forEach
                }
                val wrapper = blockSourceWrapperTypeOf(expression, file, index, plans)
                if (wrapper == null) {
                    rejectNestedBlockSourceWrapper(
                        expression,
                        file,
                        index,
                        plans,
                        "native BlockSource return"
                    )
                    return@forEach
                }
                statement.setExpression(
                    wrapBlockSourceNative(expression, plans.getValue(wrapper))
                )
            }

            file.unit.findAll(AssignExpr::class.java).forEach { assignment ->
                if (
                    declaredBlockSourceTypeOf(assignment.target, file, index)
                        !in sourceBlockSourceTypes
                ) {
                    return@forEach
                }
                val wrapper = blockSourceWrapperTypeOf(
                    assignment.value,
                    file,
                    index,
                    plans
                )
                if (wrapper == null) {
                    rejectNestedBlockSourceWrapper(
                        assignment.value,
                        file,
                        index,
                        plans,
                        "native BlockSource assignment"
                    )
                    return@forEach
                }
                assignment.setValue(
                    wrapBlockSourceNative(assignment.value, plans.getValue(wrapper))
                )
            }
        }
        return localWrappers
    }

    private fun isNativeBlockSourceArgument(
        call: MethodCallExpr,
        argument: Int,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex
    ): Boolean {
        val receiver = call.scope.orElse(null) ?: return false
        val owner = declaredBlockSourceExpressionType(receiver, file, index)
            ?: return false
        val matching = targetMethodDescriptorsInHierarchy(owner, call.nameAsString)
            .map { TargetMembers.parameterTypes(it) }
            .filter { it.size == call.arguments.size }
        return matching.isNotEmpty() &&
            matching.all { parameters ->
                parameters.getOrNull(argument) == TARGET_BLOCK_SOURCE_DESCRIPTOR
            }
    }

    private fun targetMethodDescriptorsInHierarchy(
        owner: String,
        name: String
    ): Set<String> {
        val descriptors = linkedSetOf<String>()
        val seen = hashSetOf<String>()
        val frontier = ArrayDeque(listOf(owner))
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            if (!seen.add(current)) continue
            descriptors += TargetMembers.methodDescriptors(current, name)
            frontier.addAll(TargetMembers.supertypesOf(current))
        }
        return descriptors
    }

    private fun auditBlockSourceFlows(
        parsed: List<BlockSourceParsed>,
        index: BlockSourceTypeIndex,
        plans: Map<String, BlockSourcePlan>,
        localWrappers: Set<VariableDeclarator>
    ) {
        parsed.forEach { file ->
            file.unit.findAll(ClassOrInterfaceType::class.java).forEach { type ->
                val wrapper = index.resolve(type, file)
                    ?.takeIf(plans::containsKey) ?: return@forEach
                val parent = type.parentNode.orElse(null)
                val directConstruction =
                    parent is ObjectCreationExpr && parent.type === type
                val admittedLocal =
                    parent is VariableDeclarator &&
                        parent.type === type &&
                        parent in localWrappers
                if (!directConstruction && !admittedLocal) {
                    failBlockSource(
                        "Source BlockSource wrapper type $wrapper escapes the closed " +
                            "construction boundary in ${file.path}"
                    )
                }
            }

            localWrappers.filter { variable ->
                variable.findCompilationUnit().orElse(null) === file.unit
            }.forEach { variable ->
                val wrapper = index.resolve(variable.type, file)
                    ?.takeIf(plans::containsKey)
                    ?: failBlockSource(
                        "Closed BlockSource local ${variable.nameAsString} lost its wrapper " +
                            "type in ${file.path}"
                    )
                val uses = file.unit.findAll(NameExpr::class.java).filter {
                    declaredBlockSourceVariableOfName(it) === variable
                }
                if (uses.size != 1) {
                    failBlockSource(
                        "Closed BlockSource local ${variable.nameAsString} must retain one " +
                            "native conversion, found ${uses.size} in ${file.path}"
                    )
                }
                val accessorCall = uses.single().parentNode.orElse(null)
                    as? MethodCallExpr
                    ?: failBlockSource(
                        "Closed BlockSource local ${variable.nameAsString} lost its native " +
                            "adapter call in ${file.path}"
                    )
                val nativeCall = accessorCall.parentNode.orElse(null)
                    as? MethodCallExpr
                    ?: failBlockSource(
                        "Closed BlockSource local ${variable.nameAsString} lost its target " +
                            "call boundary in ${file.path}"
                    )
                val argument = nativeCall.arguments.indexOfFirst {
                    it === accessorCall
                }
                if (
                    accessorCall.scope.orElse(null) !== uses.single() ||
                    accessorCall.nameAsString != plans.getValue(wrapper).accessor ||
                    argument < 0 ||
                    !isNativeBlockSourceArgument(nativeCall, argument, file, index)
                ) {
                    failBlockSource(
                        "Closed BlockSource local ${variable.nameAsString} is not converted " +
                            "at its declaration-proven native call boundary in ${file.path}"
                    )
                }
            }

            file.unit.findAll(ThisExpr::class.java).forEach { expression ->
                val wrapper = blockSourceWrapperTypeOf(
                    expression,
                    file,
                    index,
                    plans
                ) ?: return@forEach
                val parent = expression.parentNode.orElse(null)
                val internalMemberAccess =
                    parent is FieldAccessExpr && parent.scope === expression ||
                        parent is MethodCallExpr &&
                        parent.scope.orElse(null) === expression
                if (!internalMemberAccess) {
                    failBlockSource(
                        "Source BlockSource wrapper $wrapper escapes through this in " +
                            "${file.path}"
                    )
                }
            }

            file.unit.findAll(CastExpr::class.java).forEach { cast ->
                val target = index.resolve(cast.type, file)
                val sourceWrapper = blockSourceWrapperTypeOf(
                    cast.expression,
                    file,
                    index,
                    plans
                )
                if (target in sourceBlockSourceTypes && sourceWrapper != null) {
                    failBlockSource(
                        "A cast from source wrapper $sourceWrapper to native BlockSource " +
                            "cannot be preserved in ${file.path}"
                    )
                }
                if (target in plans && sourceWrapper != target) {
                    failBlockSource(
                        "A cast to source wrapper $target is only admitted when the operand " +
                            "is already proven to be that wrapper in ${file.path}"
                    )
                }
            }
            file.unit.findAll(InstanceOfExpr::class.java).forEach { expression ->
                val target = index.resolve(expression.type, file)
                val sourceWrapper = blockSourceWrapperTypeOf(
                    expression.expression,
                    file,
                    index,
                    plans
                )
                if (target in sourceBlockSourceTypes && sourceWrapper != null) {
                    failBlockSource(
                        "An instanceof BlockSource check against source wrapper " +
                            "$sourceWrapper cannot be preserved in ${file.path}"
                    )
                }
                if (target in plans && sourceWrapper != target) {
                    failBlockSource(
                        "An instanceof $target check is only admitted for that proven source " +
                            "wrapper in ${file.path}"
                    )
                }
            }
            file.unit.findAll(MethodReferenceExpr::class.java).forEach { reference ->
                if (
                    reference.identifier == "new" &&
                    index.resolve(
                        (reference.scope as? com.github.javaparser.ast.expr.TypeExpr)
                            ?.type ?: return@forEach,
                        file
                    ) in plans
                ) {
                    failBlockSource(
                        "Constructor reference ${reference.scope}::new has no explicit native " +
                            "BlockSource conversion boundary in ${file.path}"
                    )
                }
            }

            file.unit.findAll(MethodCallExpr::class.java).forEach { call ->
                call.arguments.forEach { argument ->
                    val wrapper = blockSourceWrapperTypeOf(
                        argument,
                        file,
                        index,
                        plans
                    ) ?: return@forEach
                    failBlockSource(
                        "Passing source BlockSource wrapper $wrapper to " +
                            "${call.nameAsString}(...) has no declaration-proven wrapper " +
                            "association in ${file.path}"
                    )
                }
            }
            file.unit.findAll(ObjectCreationExpr::class.java).forEach { creation ->
                creation.arguments.forEach { argument ->
                    val wrapper = blockSourceWrapperTypeOf(
                        argument,
                        file,
                        index,
                        plans
                    ) ?: return@forEach
                    failBlockSource(
                        "Passing source BlockSource wrapper $wrapper to a constructor has no " +
                            "declaration-proven wrapper association in ${file.path}"
                    )
                }
            }
            file.unit.findAll(ObjectCreationExpr::class.java).forEach { creation ->
                val wrapper = index.resolve(creation.type, file)
                    ?.takeIf(plans::containsKey) ?: return@forEach
                val parent = creation.parentNode.orElse(null)
                val converted = parent is MethodCallExpr &&
                    parent.scope.orElse(null) === creation &&
                    parent.nameAsString == plans.getValue(wrapper).accessor
                val wrapperVariable = parent is VariableDeclarator &&
                    parent.initializer.orElse(null) === creation &&
                    index.resolve(parent.type, file) == wrapper &&
                    parent in localWrappers
                if (!converted && !wrapperVariable) {
                    failBlockSource(
                        "Construction of source BlockSource wrapper $wrapper has no closed " +
                            "wrapper or native boundary in ${file.path}"
                    )
                }
            }
        }
    }

    private fun applyBlockSourcePlan(plan: BlockSourcePlan) {
        plan.owner.implementedTypes.remove(plan.implemented)
        plan.sourceMethods.forEach { it.removeBlockSourceOverrideAnnotation() }
        plan.owner.addMember(
            StaticJavaParser.parseBodyDeclaration(
                """
                public net.minecraft.core.dispenser.BlockSource ${plan.accessor}() {
                    return new net.minecraft.core.dispenser.BlockSource(
                        this.getLevel(),
                        this.getPos(),
                        this.getBlockState(),
                        null);
                }
                """.trimIndent()
            )
        )
    }

    private fun wrapBlockSourceNative(
        expression: Expression,
        plan: BlockSourcePlan
    ): MethodCallExpr =
        MethodCallExpr(expression.clone(), plan.accessor)

    private fun rejectNestedBlockSourceWrapper(
        expression: Expression,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex,
        plans: Map<String, BlockSourcePlan>,
        context: String
    ) {
        val nested = expression.findAll(Expression::class.java)
            .asSequence()
            .filter { it !== expression }
            .firstNotNullOfOrNull {
                blockSourceWrapperTypeOf(it, file, index, plans)
            }
        if (nested != null) {
            failBlockSource(
                "Unsupported nested source BlockSource wrapper flow in $context at " +
                    "${file.path}; $nested requires an explicit native adapter boundary"
            )
        }
    }

    private fun blockSourceWrapperTypeOf(
        expression: Expression,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex,
        plans: Map<String, BlockSourcePlan>
    ): String? {
        val canonical = when (expression) {
            is EnclosedExpr ->
                return blockSourceWrapperTypeOf(expression.inner, file, index, plans)
            is ObjectCreationExpr -> index.resolve(expression.type, file)
            is NameExpr -> declaredBlockSourceTypeOf(expression, file, index)
            is FieldAccessExpr -> declaredBlockSourceTypeOf(expression, file, index)
            is ThisExpr -> expression.findAncestor(
                ClassOrInterfaceDeclaration::class.java
            ).orElse(null)?.let { blockSourceCanonicalName(it, file.unit) }
            else -> null
        }
        return canonical?.takeIf(plans::containsKey)
    }

    private fun declaredBlockSourceTypeOf(
        expression: Expression,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex
    ): String? = when (expression) {
        is EnclosedExpr -> declaredBlockSourceTypeOf(expression.inner, file, index)
        is NameExpr -> declaredBlockSourceTypeOfName(expression, file, index)
        is FieldAccessExpr -> {
            if (expression.scope is ThisExpr) {
                blockSourceFieldType(
                    expression.findAncestor(ClassOrInterfaceDeclaration::class.java)
                        .orElse(null),
                    expression.nameAsString,
                    file,
                    index
                )
            } else {
                null
            }
        }
        is ThisExpr -> expression.findAncestor(
            ClassOrInterfaceDeclaration::class.java
        ).orElse(null)?.let { blockSourceCanonicalName(it, file.unit) }
        else -> null
    }

    private fun declaredBlockSourceExpressionType(
        expression: Expression,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex
    ): String? = when (val current = unwrapBlockSourceEnclosed(expression)) {
        is ObjectCreationExpr -> index.resolve(current.type, file)
        is CastExpr -> index.resolve(current.type, file)
        else -> declaredBlockSourceTypeOf(current, file, index)
    }

    private fun declaredBlockSourceTypeOfName(
        expression: NameExpr,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex
    ): String? {
        declaredBlockSourceVariableOfName(expression)?.let {
            return index.resolve(it.type, file)
        }
        val callable = expression.findAncestor(CallableDeclaration::class.java)
            .orElse(null)
        if (callable != null) {
            callable.parameters.firstOrNull {
                it.nameAsString == expression.nameAsString
            }?.let { return index.resolve(it.type, file) }
        }
        val owner = expression.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .orElse(null)
        return blockSourceFieldType(owner, expression.nameAsString, file, index)
    }

    private fun declaredBlockSourceVariableOfName(
        expression: NameExpr
    ): VariableDeclarator? {
        val callable = expression.findAncestor(CallableDeclaration::class.java)
            .orElse(null) ?: return null
        return callable.findAll(VariableDeclarator::class.java)
            .filter {
                it.nameAsString == expression.nameAsString &&
                    blockSourceBeginsBefore(it, expression) &&
                    blockSourceLocalScopeContains(it, expression)
            }
            .maxByOrNull(::blockSourceBeginOffset)
    }

    private fun blockSourceFieldType(
        owner: ClassOrInterfaceDeclaration?,
        name: String,
        file: BlockSourceParsed,
        index: BlockSourceTypeIndex
    ): String? {
        val fields = owner?.fields.orEmpty().flatMap { it.variables }
            .filter { it.nameAsString == name }
        return fields.singleOrNull()?.let { index.resolve(it.type, file) }
    }

    private fun nearestBlockSourceMethodWithoutLambda(node: Node): MethodDeclaration? {
        var current = node.parentNode.orElse(null)
        while (current != null) {
            if (current is com.github.javaparser.ast.expr.LambdaExpr) return null
            if (current is MethodDeclaration) return current
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun MethodDeclaration.removeBlockSourceOverrideAnnotation() {
        annotations.removeIf {
            it.nameAsString == "Override" ||
                it.nameAsString == "java.lang.Override"
        }
    }

    private fun uniqueBlockSourceName(
        base: String,
        used: MutableSet<String>
    ): String {
        var candidate = base
        while (candidate in used) candidate += "_"
        used += candidate
        return candidate
    }

    private fun blockSourceBeginsBefore(first: Node, second: Node): Boolean {
        val a = first.range.orElse(null)?.begin ?: return false
        val b = second.range.orElse(null)?.begin ?: return false
        return a.line < b.line || a.line == b.line && a.column <= b.column
    }

    private fun blockSourceLocalScopeContains(
        variable: VariableDeclarator,
        expression: Expression
    ): Boolean {
        val declaration = variable.findAncestor(
            com.github.javaparser.ast.expr.VariableDeclarationExpr::class.java
        ).orElse(null) ?: return false
        val parent = declaration.parentNode.orElse(null) ?: return false
        val scope = if (parent is ExpressionStmt) {
            parent.parentNode.orElse(null) ?: return false
        } else {
            parent
        }
        return scope === expression || scope.isAncestorOf(expression)
    }

    private fun blockSourceBeginOffset(node: Node): Long {
        val begin = node.range.orElse(null)?.begin ?: return Long.MIN_VALUE
        return begin.line.toLong() * 1_000_000L + begin.column
    }
}

private val sourceBlockSourceTypes = setOf(
    OLD_BLOCK_SOURCE,
    TARGET_BLOCK_SOURCE
)

private val sourceBlockSourceMethods = setOf(
    "x",
    "y",
    "z",
    "getPos",
    "getBlockState",
    "getEntity",
    "getLevel"
)

private data class BlockSourceParsed(
    val path: Path,
    val source: String,
    val unit: CompilationUnit
)

private data class BlockSourcePrepared(
    val file: BlockSourceParsed,
    val migrated: String
)

private data class BlockSourcePlan(
    val canonical: String,
    val owner: ClassOrInterfaceDeclaration,
    val file: BlockSourceParsed,
    val implemented: ClassOrInterfaceType,
    val sourceMethods: List<MethodDeclaration>,
    val accessor: String
)

private class BlockSourceTypeIndex(files: List<BlockSourceParsed>) {
    private val types = files.flatMap { file ->
        file.unit.findAll(TypeDeclaration::class.java).mapNotNull { declaration ->
            blockSourceCanonicalName(declaration, file.unit)
        }
    }.toSet()

    fun resolve(type: Type, file: BlockSourceParsed): String? {
        if (type.isVoidType) return "void"
        if (type.isPrimitiveType) return type.asString()
        if (type.isArrayType) {
            return resolve(type.asArrayType().componentType, file)?.plus("[]")
        }
        return resolve(type as? ClassOrInterfaceType ?: return null, file)
    }

    fun resolve(
        type: ClassOrInterfaceType,
        file: BlockSourceParsed
    ): String? {
        val spelling = type.nameWithScope
        if (spelling.firstOrNull()?.isLowerCase() == true) return spelling

        val first = spelling.substringBefore('.')
        val suffix = spelling.removePrefix(first)
        val local = file.unit.findAll(TypeDeclaration::class.java)
            .mapNotNull { declaration ->
                blockSourceCanonicalName(declaration, file.unit)
                    ?.takeIf {
                        it.substringAfterLast('.').substringAfterLast('$') == first
                    }
            }.distinct()
        if (local.isNotEmpty()) return local.singleOrNull()?.plus(suffix)

        val explicit = file.unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.name.identifier == first
        }.map { it.nameAsString }.distinct()
        if (explicit.isNotEmpty()) return explicit.singleOrNull()?.plus(suffix)

        val packageName = file.unit.packageDeclaration
            .map { it.nameAsString }
            .orElse("")
        val samePackage = types.filter {
            it.substringAfterLast('.').substringAfterLast('$') == first &&
                it.substringBeforeLast('.', "") == packageName
        }
        if (samePackage.isNotEmpty()) {
            return samePackage.singleOrNull()?.plus(suffix)
        }

        val wildcard = file.unit.imports.filter {
            !it.isStatic && it.isAsterisk
        }.map { "${it.nameAsString}.$spelling" }
            .filter {
                it in types ||
                    it in sourceBlockSourceTypes ||
                    it == BLOCK_POS ||
                    it == BLOCK_STATE ||
                    it == BLOCK_ENTITY ||
                    it == DISPENSER_BLOCK_ENTITY ||
                    it == SERVER_LEVEL ||
                    it == VEC3
            }.distinct()
        return wildcard.singleOrNull()
    }
}

private fun blockSourceCanonicalName(
    declaration: TypeDeclaration<*>,
    unit: CompilationUnit
): String? {
    val names = mutableListOf<String>()
    var current: Node = declaration
    while (current is TypeDeclaration<*>) {
        names += current.nameAsString
        current = current.parentNode.orElse(null) ?: return null
    }
    if (current !== unit) return null
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val nested = names.asReversed().joinToString(".")
    return if (packageName.isBlank()) nested else "$packageName.$nested"
}

private fun failBlockSource(message: String): Nothing =
    throw IllegalStateException(message)
