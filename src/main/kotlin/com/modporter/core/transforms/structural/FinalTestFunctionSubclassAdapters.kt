package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
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
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val TEST_FUNCTION = "net.minecraft.gametest.framework.TestFunction"
private const val GAME_TEST_INFO = "net.minecraft.gametest.framework.GameTestInfo"
private const val GAME_TEST_HELPER = "net.minecraft.gametest.framework.GameTestHelper"
private const val GAME_TEST_SERVER = "net.minecraft.gametest.framework.GameTestServer"
private const val RANDOM_SOURCE = "net.minecraft.util.RandomSource"
private const val ROTATION = "net.minecraft.world.level.block.Rotation"
private const val CONSUMER = "java.util.function.Consumer"
private const val STRING = "java.lang.String"
private const val LEGACY_CALLBACK =
    "com.modporter.generated.gametest.LegacyGameTestCallback"
private const val WARMUP_MIXIN =
    "com.modporter.generated.gametest.mixin.LegacyGameTestWarmupMixin"
private const val ORIGIN_MIXIN =
    "com.modporter.generated.gametest.mixin.LegacyGameTestOriginMixin"
private const val WARMUP_MIXIN_CONFIG = "modporter.gametest.mixins.json"

private const val TARGET_CANONICAL_CONSTRUCTOR =
    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;" +
        "Lnet/minecraft/world/level/block/Rotation;IJZZIIZ" +
        "Ljava/util/function/Consumer;)V"
private const val TARGET_ROTATION_DEFAULTS_CONSTRUCTOR =
    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;" +
        "Lnet/minecraft/world/level/block/Rotation;IJZ" +
        "Ljava/util/function/Consumer;)V"
private const val TARGET_SIMPLE_DEFAULTS_CONSTRUCTOR =
    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IJZ" +
        "Ljava/util/function/Consumer;)V"

// The verified 1.20.1 API has no state corresponding to these components.
// It did not encase GameTest structures, so the 1.21.1 sky-access component
// must remain true to preserve the source runtime environment.
private const val TARGET_DEFAULT_MANUAL_ONLY = false
private const val TARGET_DEFAULT_SKY_ACCESS = true

/**
 * Migrates a closed family of direct source subclasses of the old mutable
 * TestFunction class to wrappers around the final 1.21.1 TestFunction record.
 *
 * Admission is structural:
 *
 * - the superclass resolves canonically to Minecraft TestFunction;
 * - one constructor invokes the verified old ten-argument constructor;
 * - any other constructor delegates to that constructor;
 * - getTestName is absent or returns one final String field;
 * - run is absent or contains exactly one source super.run(helperExpression);
 * - no source-owned subclass extends the wrapper;
 * - no other inherited TestFunction method is overridden or reached through
 *   super.
 *
 * The source wrapper remains available to source-owned identity maps. Every
 * exact native return, variable initializer, or assignment that can be proven
 * from declarations is changed to return the cached native record.
 */
internal object FinalTestFunctionSubclassAdapters {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        if (!targetContractIsVerified()) return emptyList()

        val javaFiles = javaFiles(projectDir)
        if (javaFiles.none { it.readText().contains("TestFunction") }) return emptyList()

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
                fail(
                    "Final TestFunction adapter preflight requires every Java source to parse; " +
                        "$path failed: ${problems.ifBlank { "unknown parse failure" }}"
                )
            }
            ParsedSource(path, source, result.result.orElseThrow())
        }
        val index = ProjectTypeIndex(parsed)
        val candidates = parsed.flatMap { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull { owner ->
                if (owner.isInterface || owner.extendedTypes.size != 1) {
                    null
                } else if (
                    index.resolve(owner.extendedTypes.single(), file) == TEST_FUNCTION
                ) {
                    owner to file
                } else {
                    null
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val candidateNames = candidates.associate { (owner, file) ->
            val canonical = canonicalName(owner, file.unit)
                ?: fail(
                    "Unnamed or local TestFunction subclass is unsupported in ${file.path}"
                )
            canonical to (owner to file)
        }
        rejectSourceOwnedChildren(parsed, index, candidateNames.keys)

        val plans = candidateNames.mapValues { (canonical, pair) ->
            planCandidate(canonical, pair.first, pair.second, index)
        }

        parsed.forEach { LexicalPreservingPrinter.setup(it.unit) }
        rewriteNativeBoundaries(parsed, index, plans)
        auditSourceWrapperReferences(parsed, index, plans)
        plans.values.forEach(::applyCandidatePlan)

        val support = planRuntimeSupport(projectDir, plans.values)
        val changed = parsed.mapNotNull { file ->
            val migrated = LexicalPreservingPrinter.print(file.unit)
            if (migrated == file.source) null else PreparedChange(file, migrated)
        }
        if (!dryRun) {
            changed.forEach { it.file.path.writeText(it.migrated) }
            support.filter { it.before == null }.forEach { generated ->
                generated.path.parent.createDirectories()
                generated.path.writeText(generated.source)
            }
        }
        return changed.map { prepared ->
            Change(
                file = prepared.file.path,
                line = 1,
                description =
                    "Replace a legacy TestFunction subclass with a cached native-record adapter",
                before =
                    "Source subclass or source boundary relies on TestFunction inheritance",
                after =
                    "Source wrapper preserves behavior and native boundaries receive TestFunction",
                confidence = Confidence.HIGH,
                ruleId = "struct-final-test-function-adapter"
            )
        } + support.filter { it.before == null }.map { generated ->
            Change(
                file = generated.path,
                line = 1,
                description = generated.description,
                before = "(missing generated compatibility source)",
                after = generated.source.lineSequence().first(),
                confidence = Confidence.HIGH,
                ruleId = "struct-final-test-function-initial-warmup"
            )
        }
    }

    private fun targetContractIsVerified(): Boolean {
        val accessors = mapOf(
            "batchName" to "()Ljava/lang/String;",
            "testName" to "()Ljava/lang/String;",
            "structureName" to "()Ljava/lang/String;",
            "rotation" to "()Lnet/minecraft/world/level/block/Rotation;",
            "maxTicks" to "()I",
            "setupTicks" to "()J",
            "required" to "()Z",
            "manualOnly" to "()Z",
            "maxAttempts" to "()I",
            "requiredSuccesses" to "()I",
            "skyAccess" to "()Z",
            "function" to "()Ljava/util/function/Consumer;",
            "run" to "(Lnet/minecraft/gametest/framework/GameTestHelper;)V"
        )
        return TargetMembers.inheritsFrom(TEST_FUNCTION, "java.lang.Record") &&
            TARGET_CANONICAL_CONSTRUCTOR in
            TargetMembers.methodDescriptors(TEST_FUNCTION, "<init>") &&
            TARGET_ROTATION_DEFAULTS_CONSTRUCTOR in
            TargetMembers.methodDescriptors(TEST_FUNCTION, "<init>") &&
            TARGET_SIMPLE_DEFAULTS_CONSTRUCTOR in
            TargetMembers.methodDescriptors(TEST_FUNCTION, "<init>") &&
            accessors.all { (name, descriptor) ->
                descriptor in TargetMembers.methodDescriptors(TEST_FUNCTION, name)
            } &&
            "()Z" in TargetMembers.methodDescriptors(
                GAME_TEST_INFO,
                "ensureStructureIsPlaced"
            ) &&
            "(I)Lnet/minecraft/gametest/framework/GameTestInfo;" in
            TargetMembers.methodDescriptors(GAME_TEST_INFO, "startExecution") &&
            "()Lnet/minecraft/gametest/framework/TestFunction;" in
            TargetMembers.methodDescriptors(GAME_TEST_INFO, "getTestFunction") &&
            TargetMembers.fieldDescriptor(GAME_TEST_SERVER, "testFunctions") ==
            "Ljava/util/List;" &&
            TargetMembers.fieldSignatures(GAME_TEST_SERVER)["testFunctions"] ==
            "Ljava/util/List<Lnet/minecraft/gametest/framework/TestFunction;>;" &&
            "(Lnet/minecraft/server/level/ServerLevel;)V" in
            TargetMembers.methodDescriptors(GAME_TEST_SERVER, "startTests") &&
            "(II)I" in
            TargetMembers.methodDescriptors(RANDOM_SOURCE, "nextIntBetweenInclusive") &&
            !TargetMembers.hasMethod(TEST_FUNCTION, "getTestName")
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

    private fun rejectSourceOwnedChildren(
        parsed: List<ParsedSource>,
        index: ProjectTypeIndex,
        candidates: Set<String>
    ) {
        parsed.forEach { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java).forEach { owner ->
                owner.extendedTypes.forEach { parent ->
                    val resolved = index.resolve(parent, file)
                    if (resolved in candidates) {
                        fail(
                            "Cannot replace $resolved with composition while source subclass " +
                                "${canonicalName(owner, file.unit) ?: owner.nameAsString} extends it"
                        )
                    }
                }
            }
            file.unit.findAll(ObjectCreationExpr::class.java).firstOrNull {
                it.anonymousClassBody.isPresent &&
                    index.resolve(it.type, file) in candidates
            }?.let {
                fail(
                    "Anonymous source subclass of " +
                        "${index.resolve(it.type, file)} prevents composition"
                )
            }
        }
    }

    private fun planCandidate(
        canonical: String,
        owner: ClassOrInterfaceDeclaration,
        file: ParsedSource,
        index: ProjectTypeIndex
    ): CandidatePlan {
        if (owner.isAbstract) {
            fail("$canonical is abstract; a closed concrete wrapper is required")
        }
        owner.fields.filter { !it.isStatic }.flatMap { it.variables }
            .firstOrNull { it.initializer.isPresent }?.let {
                fail(
                    "$canonical has an instance field initializer; moving old super " +
                        "argument evaluation after it would change initialization order"
                )
            }
        owner.members.filterIsInstance<InitializerDeclaration>()
            .firstOrNull { !it.isStatic }?.let {
                fail(
                    "$canonical has an instance initializer; old super argument timing " +
                        "cannot be preserved by the bounded adapter"
                )
            }
        val constructors = owner.constructors
        if (constructors.isEmpty()) {
            fail("$canonical has no explicit old TestFunction constructor")
        }
        val direct = constructors.mapNotNull { constructor ->
            val first = constructor.body.statements.firstOrNull()
            val invocation = first as? ExplicitConstructorInvocationStmt
            if (invocation != null && !invocation.isThis) constructor to invocation else null
        }
        if (direct.size != 1) {
            fail("$canonical must have exactly one constructor that directly invokes TestFunction")
        }
        constructors.filter { it !== direct.single().first }.forEach { constructor ->
            val first = constructor.body.statements.firstOrNull()
                as? ExplicitConstructorInvocationStmt
            if (first == null || !first.isThis) {
                fail(
                    "Every secondary constructor in $canonical must delegate with this(...)"
                )
            }
        }
        val primary = direct.single().first
        val sourceSuper = direct.single().second
        if (sourceSuper.arguments.size != 10) {
            fail(
                "$canonical invokes an unsupported TestFunction constructor with " +
                    "${sourceSuper.arguments.size} arguments; expected the verified old ten"
            )
        }

        val getTestNameDeclarations = owner.methods.filter {
            it.nameAsString == "getTestName" && it.parameters.isEmpty()
        }
        val getTestName = getTestNameDeclarations.filter {
            index.resolve(it.type, file) == STRING
        }.singleOrNullChecked("$canonical declares multiple getTestName() methods")
        if (getTestNameDeclarations.size != listOfNotNull(getTestName).size) {
            fail("$canonical declares an unsupported getTestName() shape")
        }
        if (getTestName != null) {
            stableTestNameField(getTestName, owner, file, index)
        }

        val runDeclarations = owner.methods.filter {
            it.nameAsString == "run" && it.parameters.size == 1
        }
        val run = runDeclarations.filter {
            it.type.isVoidType &&
                index.resolve(it.parameters.single().type, file) == GAME_TEST_HELPER
        }.singleOrNullChecked("$canonical declares multiple run(GameTestHelper) methods")
        if (runDeclarations.size != listOfNotNull(run).size) {
            fail("$canonical declares an unsupported one-argument run overload")
        }

        val oldInheritedNames = setOf(
            "getStructureName",
            "getMaxTicks",
            "isRequired",
            "getBatchName",
            "getSetupTicks",
            "getRotation",
            "isFlaky",
            "getMaxAttempts",
            "getRequiredSuccesses",
            "toString",
            "batchName",
            "testName",
            "structureName",
            "rotation",
            "maxTicks",
            "setupTicks",
            "required",
            "manualOnly",
            "maxAttempts",
            "requiredSuccesses",
            "skyAccess",
            "function"
        )
        owner.methods.firstOrNull { method ->
            method.nameAsString in oldInheritedNames
        }?.let { method ->
            fail(
                "$canonical declares ${method.nameAsString}; the bounded adapter only " +
                    "preserves source getTestName/run overrides"
            )
        }

        val sourceRunCalls = run?.findAll(MethodCallExpr::class.java).orEmpty().filter {
            it.nameAsString == "run" &&
                it.scope.orElse(null) is SuperExpr &&
                it.arguments.size == 1
        }
        if (run != null && sourceRunCalls.size != 1) {
            fail(
                "$canonical.run must dispatch to source super.run exactly once; found " +
                    sourceRunCalls.size
            )
        }
        val permittedSuperNodes = buildSet<Node> {
            add(sourceSuper)
            sourceRunCalls.forEach { add(it.scope.orElseThrow()) }
        }
        owner.findAll(SuperExpr::class.java).firstOrNull {
            it !in permittedSuperNodes
        }?.let {
            fail("$canonical reaches an unsupported inherited TestFunction member through super")
        }
        owner.constructors.flatMap {
            it.findAll(ExplicitConstructorInvocationStmt::class.java)
        }.filter { !it.isThis }.firstOrNull { it !== sourceSuper }?.let {
            fail("$canonical has an unsupported additional super constructor invocation")
        }

        val used = owner.findAll(SimpleName::class.java)
            .mapTo(linkedSetOf()) { it.asString() }
        val names = AdapterNames(
            batch = uniqueName("modporter\$batchName", used),
            sourceTest = uniqueName("modporter\$sourceTestName", used),
            structure = uniqueName("modporter\$structureName", used),
            rotation = uniqueName("modporter\$rotation", used),
            maxTicks = uniqueName("modporter\$maxTicks", used),
            setupTicks = uniqueName("modporter\$setupTicks", used),
            required = uniqueName("modporter\$required", used),
            requiredSuccesses = uniqueName("modporter\$requiredSuccesses", used),
            maxAttempts = uniqueName("modporter\$maxAttempts", used),
            sourceFunction = uniqueName("modporter\$sourceFunction", used),
            native = uniqueName("modporter\$testFunction", used),
            accessor = uniqueName("modporter\$asTestFunction", used)
        )
        return CandidatePlan(
            canonical,
            owner,
            file,
            primary,
            sourceSuper,
            getTestName,
            run,
            sourceRunCalls.singleOrNull(),
            names
        )
    }

    private fun stableTestNameField(
        method: MethodDeclaration,
        owner: ClassOrInterfaceDeclaration,
        file: ParsedSource,
        index: ProjectTypeIndex
    ): FieldDeclaration {
        val body = method.body.orElse(null)
            ?: fail("${owner.nameAsString}.getTestName must have a body")
        if (body.statements.size != 1) {
            fail(
                "${owner.nameAsString}.getTestName must be a stable single-field return"
            )
        }
        val returned = (body.statements.single() as? ReturnStmt)
            ?.expression?.orElse(null)
            ?: fail(
                "${owner.nameAsString}.getTestName must be a stable single-field return"
            )
        val fieldName = when (returned) {
            is NameExpr -> returned.nameAsString
            is FieldAccessExpr ->
                returned.nameAsString.takeIf { returned.scope is ThisExpr }
            else -> null
        } ?: fail(
            "${owner.nameAsString}.getTestName must return one final String field"
        )
        val fields = owner.fields.filter { field ->
            field.isFinal &&
                field.variables.any { it.nameAsString == fieldName } &&
                index.resolve(
                    field.variables.first { it.nameAsString == fieldName }.type,
                    file
                ) == STRING
        }
        if (fields.size != 1) {
            fail(
                "${owner.nameAsString}.getTestName field $fieldName is not one final String field"
            )
        }
        return fields.single()
    }

    private fun rewriteNativeBoundaries(
        parsed: List<ParsedSource>,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ) {
        parsed.forEach { file ->
            file.unit.findAll(CastExpr::class.java).forEach { cast ->
                if (
                    index.resolve(cast.type, file) == TEST_FUNCTION &&
                    wrapperTypeOf(cast.expression, file, index, plans) != null
                ) {
                    fail(
                        "A cast from a source TestFunction wrapper to native TestFunction " +
                            "cannot be preserved in ${file.path}"
                    )
                }
                val targetWrapper = index.resolve(cast.type, file)
                    ?.takeIf(plans::containsKey)
                if (
                    targetWrapper != null &&
                    wrapperTypeOf(cast.expression, file, index, plans) !=
                    targetWrapper
                ) {
                    fail(
                        "A cast to source wrapper $targetWrapper is only admitted when the " +
                            "operand is already proven to be that wrapper in ${file.path}"
                    )
                }
            }
            file.unit.findAll(InstanceOfExpr::class.java).forEach { expression ->
                if (
                    index.resolve(expression.type, file) == TEST_FUNCTION &&
                    wrapperTypeOf(expression.expression, file, index, plans) != null
                ) {
                    fail(
                        "An instanceof TestFunction check against a source wrapper cannot " +
                            "be preserved in ${file.path}"
                    )
                }
                val targetWrapper = index.resolve(expression.type, file)
                    ?.takeIf(plans::containsKey)
                if (
                    targetWrapper != null &&
                    wrapperTypeOf(expression.expression, file, index, plans) !=
                    targetWrapper
                ) {
                    fail(
                        "An instanceof $targetWrapper check is only admitted when the operand " +
                            "is already proven to be that source wrapper in ${file.path}"
                    )
                }
            }

            file.unit.findAll(MethodReferenceExpr::class.java).forEach { reference ->
                val scope = reference.scope
                val type = (scope as? TypeExpr)?.type
                if (
                    reference.identifier == "getTestName" &&
                    type != null &&
                    index.resolve(type, file) == TEST_FUNCTION
                ) {
                    reference.setIdentifier("testName")
                }
            }

            file.unit.findAll(ReturnStmt::class.java).forEach { statement ->
                val expression = statement.expression.orElse(null) ?: return@forEach
                val method = nearestMethodWithoutLambda(statement) ?: return@forEach
                if (index.resolve(method.type, file) != TEST_FUNCTION) return@forEach
                val wrapper = wrapperTypeOf(expression, file, index, plans)
                if (wrapper == null) {
                    rejectNestedWrapper(
                        expression,
                        file,
                        index,
                        plans,
                        "native TestFunction return"
                    )
                    return@forEach
                }
                statement.setExpression(wrapNative(expression, plans.getValue(wrapper)))
            }

            file.unit.findAll(VariableDeclarator::class.java).forEach { variable ->
                val initializer = variable.initializer.orElse(null) ?: return@forEach
                if (index.resolve(variable.type, file) != TEST_FUNCTION) return@forEach
                val wrapper = wrapperTypeOf(initializer, file, index, plans)
                if (wrapper == null) {
                    rejectNestedWrapper(
                        initializer,
                        file,
                        index,
                        plans,
                        "native TestFunction initializer"
                    )
                    return@forEach
                }
                variable.setInitializer(wrapNative(initializer, plans.getValue(wrapper)))
            }

            file.unit.findAll(AssignExpr::class.java).forEach { assignment ->
                if (
                    declaredTypeOf(assignment.target, file, index) != TEST_FUNCTION
                ) return@forEach
                val wrapper = wrapperTypeOf(assignment.value, file, index, plans)
                if (wrapper == null) {
                    rejectNestedWrapper(
                        assignment.value,
                        file,
                        index,
                        plans,
                        "native TestFunction assignment"
                    )
                    return@forEach
                }
                assignment.setValue(
                    wrapNative(assignment.value, plans.getValue(wrapper))
                )
            }
        }
    }

    private fun auditSourceWrapperReferences(
        parsed: List<ParsedSource>,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ) {
        parsed.forEach { file ->
            auditWrapperStaticReferences(file, index, plans)
            file.unit.findAll(MethodReferenceExpr::class.java).forEach { reference ->
                auditWrapperMethodReference(reference, file, index, plans)
            }
            file.unit.findAll(Expression::class.java).forEach { expression ->
                val wrapper = wrapperTypeOf(expression, file, index, plans)
                    ?: return@forEach
                auditDirectWrapperExpression(
                    expression,
                    wrapper,
                    file,
                    index,
                    plans
                )
            }
        }
    }

    private fun auditWrapperStaticReferences(
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ) {
        file.unit.findAll(MethodCallExpr::class.java).forEach { call ->
            val scope = call.scope.orElse(null) ?: return@forEach
            val wrapper = wrapperTypeNamedBy(scope, file, index, plans)
                ?: return@forEach
            val methods = plans.getValue(wrapper).owner.methods.filter {
                it.nameAsString == call.nameAsString && it.isStatic
            }
            if (methods.isEmpty()) {
                fail(
                    "$wrapper.${call.nameAsString} is not a source-declared static method; " +
                        "the call may depend on removed TestFunction inheritance in ${file.path}"
                )
            }
        }
        file.unit.findAll(FieldAccessExpr::class.java).forEach { access ->
            val wrapper = wrapperTypeNamedBy(access.scope, file, index, plans)
                ?: return@forEach
            val declared = plans.getValue(wrapper).owner.fields.any { field ->
                field.isStatic &&
                    field.variables.any { it.nameAsString == access.nameAsString }
            }
            if (!declared) {
                fail(
                    "$wrapper.${access.nameAsString} is not a source-declared static field; " +
                        "the access may depend on removed TestFunction inheritance in ${file.path}"
                )
            }
        }
    }

    private fun auditWrapperMethodReference(
        reference: MethodReferenceExpr,
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ) {
        val typeScope = (reference.scope as? TypeExpr)?.type
        val typeWrapper = typeScope?.let { index.resolve(it, file) }
            ?.takeIf(plans::containsKey)
        if (typeWrapper != null) {
            if (reference.identifier == "new") {
                fail(
                    "Constructor reference $typeWrapper::new has no declaration-proven native " +
                        "boundary in ${file.path}"
                )
            }
            val declarations = plans.getValue(typeWrapper).owner.methods.filter {
                it.nameAsString == reference.identifier
            }
            if (declarations.isEmpty()) {
                if (
                    reference.identifier !in preservedWrapperInstanceMethods &&
                    reference.identifier !in objectInstanceMethods
                ) {
                    fail(
                        "$typeWrapper::${reference.identifier} is not preserved by the source " +
                            "wrapper in ${file.path}"
                    )
                }
                return
            }
            val staticDeclarations = declarations.filter { it.isStatic }
            if (staticDeclarations.isEmpty()) return
            val associatedNativeFactory = staticDeclarations.size == declarations.size &&
                declarations.all { index.resolve(it.type, file) == TEST_FUNCTION }
            if (!associatedNativeFactory) {
                fail(
                    "Static method reference $typeWrapper::${reference.identifier} is admitted " +
                        "only for a source-declared factory whose return type is native " +
                        "TestFunction in ${file.path}"
                )
            }
            return
        }

        val valueWrapper = wrapperTypeOf(reference.scope, file, index, plans)
            ?: return
        auditWrapperMethodReceiver(
            valueWrapper,
            reference.identifier,
            file,
            plans,
            "method reference"
        )
    }

    private fun auditDirectWrapperExpression(
        expression: Expression,
        wrapper: String,
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ) {
        val parent = expression.parentNode.orElse(null)
            ?: fail("Detached source wrapper expression in ${file.path}")
        when (parent) {
            is EnclosedExpr -> {
                if (parent.inner !== expression) unsupportedWrapperFlow(expression, file)
            }
            is FieldAccessExpr -> {
                if (parent.scope !== expression) unsupportedWrapperFlow(expression, file)
                val declared = plans.getValue(wrapper).owner.fields.any { field ->
                    field.variables.any { it.nameAsString == parent.nameAsString }
                }
                if (!declared) {
                    fail(
                        "$wrapper.${parent.nameAsString} is not a source-declared field; " +
                            "the access may depend on removed TestFunction inheritance in " +
                            "${file.path}"
                    )
                }
            }
            is MethodCallExpr -> {
                if (parent.scope.orElse(null) === expression) {
                    auditWrapperMethodReceiver(
                        wrapper,
                        parent.nameAsString,
                        file,
                        plans,
                        "method call"
                    )
                } else {
                    val argument = parent.arguments.indexOfFirst { it === expression }
                    if (
                        argument < 0 ||
                        !wrapperStorageArgumentIsDeclared(
                            parent,
                            argument,
                            wrapper,
                            file,
                            index,
                            plans
                        )
                    ) {
                        fail(
                            "Passing source wrapper $wrapper to ${parent.nameAsString}(...) has " +
                                "no declaration-proven wrapper association in ${file.path}"
                        )
                    }
                }
            }
            is MethodReferenceExpr -> {
                if (parent.scope !== expression) unsupportedWrapperFlow(expression, file)
            }
            is ReturnStmt -> {
                val method = nearestMethodWithoutLambda(parent)
                if (method == null || index.resolve(method.type, file) != wrapper) {
                    unsupportedWrapperFlow(expression, file)
                }
            }
            is VariableDeclarator -> {
                if (
                    parent.initializer.orElse(null) !== expression ||
                    index.resolve(parent.type, file) != wrapper
                ) {
                    unsupportedWrapperFlow(expression, file)
                }
            }
            is AssignExpr -> {
                if (parent.target === expression) return
                if (
                    parent.value !== expression ||
                    declaredTypeOf(parent.target, file, index) != wrapper
                ) {
                    unsupportedWrapperFlow(expression, file)
                }
            }
            is BinaryExpr -> {
                if (
                    parent.operator != BinaryExpr.Operator.EQUALS &&
                    parent.operator != BinaryExpr.Operator.NOT_EQUALS
                ) {
                    unsupportedWrapperFlow(expression, file)
                }
            }
            is CastExpr -> {
                if (index.resolve(parent.type, file) != wrapper) {
                    unsupportedWrapperFlow(expression, file)
                }
            }
            is InstanceOfExpr -> {
                if (index.resolve(parent.type, file) != wrapper) {
                    unsupportedWrapperFlow(expression, file)
                }
            }
            else -> unsupportedWrapperFlow(expression, file)
        }
    }

    private fun auditWrapperMethodReceiver(
        wrapper: String,
        method: String,
        file: ParsedSource,
        plans: Map<String, CandidatePlan>,
        context: String
    ) {
        val plan = plans.getValue(wrapper)
        val declared = plan.owner.methods.any { it.nameAsString == method }
        if (
            method != plan.names.accessor &&
            method !in preservedWrapperInstanceMethods &&
            method !in objectInstanceMethods &&
            !declared
        ) {
            fail(
                "$context $wrapper.$method is not source-declared or preserved; it may depend " +
                    "on removed TestFunction inheritance in ${file.path}"
            )
        }
    }

    private fun wrapperStorageArgumentIsDeclared(
        call: MethodCallExpr,
        argument: Int,
        wrapper: String,
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ): Boolean {
        if (call.nameAsString != "put" || call.arguments.size != 2) return false
        val scope = call.scope.orElse(null) ?: return false
        val scopeType = associatedTypeNodeOf(scope, file, index, plans)
            as? ClassOrInterfaceType
            ?: return false
        val arguments = scopeType.typeArguments.orElse(null) ?: return false
        if (arguments.size != 2 || argument !in 0..1) return false
        return index.resolve(arguments[argument], file) == wrapper
    }

    private fun declaredTypeNodeOf(
        expression: Expression,
        file: ParsedSource
    ): Type? = when (expression) {
        is EnclosedExpr -> declaredTypeNodeOf(expression.inner, file)
        is NameExpr -> {
            val callable = expression.findAncestor(CallableDeclaration::class.java)
                .orElse(null)
            val local = callable?.findAll(VariableDeclarator::class.java).orEmpty()
                .filter {
                    it.nameAsString == expression.nameAsString &&
                        beginsBefore(it, expression) &&
                        localScopeContains(it, expression)
                }
                .maxByOrNull(::beginOffset)
            local?.type ?: callable?.parameters?.firstOrNull {
                it.nameAsString == expression.nameAsString
            }?.type ?: expression.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .orElse(null)?.fields.orEmpty().flatMap { it.variables }
                .singleOrNull { it.nameAsString == expression.nameAsString }?.type
        }
        is FieldAccessExpr -> {
            if (expression.scope is ThisExpr) {
                expression.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null)?.fields.orEmpty().flatMap { it.variables }
                    .singleOrNull { it.nameAsString == expression.nameAsString }?.type
            } else {
                null
            }
        }
        else -> null
    }

    private fun wrapperTypeNamedBy(
        expression: Expression,
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ): String? {
        if (declaredTypeOf(expression, file, index) != null) return null
        val spelling = when (expression) {
            is NameExpr -> expression.nameAsString
            is FieldAccessExpr -> expression.toString()
            else -> return null
        }
        val type = runCatching {
            StaticJavaParser.parseClassOrInterfaceType(spelling)
        }.getOrNull() ?: return null
        return index.resolve(type, file)?.takeIf(plans::containsKey)
    }

    private fun unsupportedWrapperFlow(
        expression: Expression,
        file: ParsedSource
    ): Nothing = fail(
        "Unsupported source wrapper flow at ${expression.range.orElse(null) ?: "unknown range"} " +
            "in ${file.path}; an explicit wrapper or native TestFunction association is required"
    )

    private fun applyCandidatePlan(plan: CandidatePlan) {
        val owner = plan.owner
        owner.extendedTypes.clear()

        val fields = generatedFields(plan.names)
        fields.reversed().forEach { field -> owner.members.add(0, field) }

        val arguments = plan.sourceSuper.arguments.map { it.clone() }
        plan.primary.body.statements.remove(plan.sourceSuper)
        val assignments = listOf(
            plan.names.batch to arguments[0],
            plan.names.sourceTest to arguments[1],
            plan.names.structure to arguments[2],
            plan.names.rotation to arguments[3],
            plan.names.maxTicks to arguments[4],
            plan.names.setupTicks to arguments[5],
            plan.names.required to arguments[6],
            plan.names.requiredSuccesses to arguments[7],
            plan.names.maxAttempts to arguments[8],
            plan.names.sourceFunction to arguments[9]
        ).map { (name, expression) ->
            StaticJavaParser.parseStatement("this.$name = $expression;")
        }
        assignments.reversed().forEach {
            plan.primary.body.statements.add(0, it)
        }

        plan.getTestName?.removeOverrideAnnotation()
        if (plan.getTestName == null) {
            owner.addMember(
                StaticJavaParser.parseBodyDeclaration(
                    """
                    public java.lang.String getTestName() {
                        return this.${plan.names.sourceTest};
                    }
                    """.trimIndent()
                )
            )
        }

        plan.run?.removeOverrideAnnotation()
        if (plan.run == null) {
            owner.addMember(
                StaticJavaParser.parseBodyDeclaration(
                    """
                    public void run(
                            net.minecraft.gametest.framework.GameTestHelper helper) {
                        this.${plan.names.sourceFunction}.accept(helper);
                    }
                    """.trimIndent()
                )
            )
        } else {
            val sourceCall = plan.sourceRunCall
                ?: fail("${plan.canonical}.run lost its source callback during planning")
            sourceCall.setName("accept")
            sourceCall.setScope(
                StaticJavaParser.parseExpression("this.${plan.names.sourceFunction}")
            )
        }

        generatedMethods(plan.names).forEach(owner::addMember)
    }

    private fun generatedFields(names: AdapterNames): List<BodyDeclaration<*>> =
        listOf(
            "private final java.lang.String ${names.batch};",
            "private final java.lang.String ${names.sourceTest};",
            "private final java.lang.String ${names.structure};",
            "private final net.minecraft.world.level.block.Rotation ${names.rotation};",
            "private final int ${names.maxTicks};",
            "private final long ${names.setupTicks};",
            "private final boolean ${names.required};",
            "private final int ${names.requiredSuccesses};",
            "private final int ${names.maxAttempts};",
            "private final java.util.function.Consumer<" +
                "net.minecraft.gametest.framework.GameTestHelper> ${names.sourceFunction};",
            "private net.minecraft.gametest.framework.TestFunction ${names.native};"
        ).map(StaticJavaParser::parseBodyDeclaration)

    private fun generatedMethods(names: AdapterNames): List<BodyDeclaration<*>> {
        val source = """
            public synchronized net.minecraft.gametest.framework.TestFunction ${names.accessor}() {
                if (this.${names.native} == null) {
                    this.${names.native} =
                        new net.minecraft.gametest.framework.TestFunction(
                            this.${names.batch},
                            this.getTestName(),
                            this.${names.structure},
                            this.${names.rotation},
                            this.${names.maxTicks},
                            this.${names.setupTicks},
                            this.${names.required},
                            $TARGET_DEFAULT_MANUAL_ONLY,
                            this.${names.maxAttempts},
                            this.${names.requiredSuccesses},
                            $TARGET_DEFAULT_SKY_ACCESS,
                            new $LEGACY_CALLBACK(this::run));
                }
                return this.${names.native};
            }

            public java.lang.String getStructureName() {
                return this.${names.structure};
            }

            public int getMaxTicks() {
                return this.${names.maxTicks};
            }

            public boolean isRequired() {
                return this.${names.required};
            }

            public java.lang.String getBatchName() {
                return this.${names.batch};
            }

            public long getSetupTicks() {
                return this.${names.setupTicks};
            }

            public net.minecraft.world.level.block.Rotation getRotation() {
                return this.${names.rotation};
            }

            public boolean isFlaky() {
                return this.${names.maxAttempts} > 1;
            }

            public int getMaxAttempts() {
                return this.${names.maxAttempts};
            }

            public int getRequiredSuccesses() {
                return this.${names.requiredSuccesses};
            }

            public java.lang.String batchName() {
                return this.${names.batch};
            }

            public java.lang.String testName() {
                return this.getTestName();
            }

            public java.lang.String structureName() {
                return this.${names.structure};
            }

            public net.minecraft.world.level.block.Rotation rotation() {
                return this.${names.rotation};
            }

            public int maxTicks() {
                return this.${names.maxTicks};
            }

            public long setupTicks() {
                return this.${names.setupTicks};
            }

            public boolean required() {
                return this.${names.required};
            }

            public boolean manualOnly() {
                return $TARGET_DEFAULT_MANUAL_ONLY;
            }

            public int maxAttempts() {
                return this.${names.maxAttempts};
            }

            public int requiredSuccesses() {
                return this.${names.requiredSuccesses};
            }

            public boolean skyAccess() {
                return $TARGET_DEFAULT_SKY_ACCESS;
            }

            public java.util.function.Consumer<
                    net.minecraft.gametest.framework.GameTestHelper> function() {
                return this.${names.accessor}().function();
            }

            @Override
            public java.lang.String toString() {
                return this.${names.sourceTest};
            }
        """.trimIndent()
        val holder = StaticJavaParser.parse(
            "class ModporterGeneratedTestFunctionMethods {\n$source\n}"
        ).getClassByName("ModporterGeneratedTestFunctionMethods").orElseThrow()
        return holder.members.map { it.clone() }
    }

    /**
     * 1.20.1 placed the structure before scheduling a test and calculated
     * startTick as gameTime + 1 + setupTicks. Its callback therefore observed
     * tick zero after one complete world tick. 1.21.1 places a newly prepared
     * structure inside GameTestInfo.tick and immediately calls
     * startExecution(0), so setup zero starts in the same post-world-tick
     * phase. Retry/in-place execution already calls startExecution(1).
     *
     * The generated callback marks only records adapted from the legacy
     * subclass profile. The mixin changes the unique initial-placement zero
     * argument for those records, while leaving retry delay one and native
     * 1.21 TestFunctions untouched.
     */
    private fun planRuntimeSupport(
        projectDir: Path,
        plans: Collection<CandidatePlan>
    ): List<GeneratedSupport> {
        val sourceRoots = plans.map { sourceRoot(it.file) }.distinct()
        if (sourceRoots.size != 1) {
            fail(
                "Final TestFunction initial-warmup compatibility requires one " +
                    "production Java source root; found $sourceRoots"
            )
        }
        val sourceRoot = sourceRoots.single()
        if (!sourceRoot.startsWith(projectDir)) {
            fail(
                "Final TestFunction Java source root escapes the project: $sourceRoot"
            )
        }
        if (
            sourceRoot.fileName.toString() != "java" ||
            sourceRoot.parent?.fileName?.toString() != "main" ||
            sourceRoot.parent?.parent?.fileName?.toString() != "src"
        ) {
            fail(
                "Final TestFunction initial-warmup compatibility requires a " +
                    "src/main/java source root, found $sourceRoot"
            )
        }

        val callback = sourceRoot.resolve(
            LEGACY_CALLBACK.replace('.', '/') + ".java"
        )
        val mixin = sourceRoot.resolve(
            WARMUP_MIXIN.replace('.', '/') + ".java"
        )
        val originMixin = sourceRoot.resolve(
            ORIGIN_MIXIN.replace('.', '/') + ".java"
        )
        val config = sourceRoot.parent.resolve("resources").resolve(
            WARMUP_MIXIN_CONFIG
        )
        return listOf(
            generatedSupport(
                callback,
                legacyCallbackSource(),
                "Generate a legacy GameTest callback marker and delay selector"
            ),
            generatedSupport(
                mixin,
                warmupMixinSource(),
                "Generate a path-specific legacy GameTest initial-warmup mixin"
            ),
            generatedSupport(
                originMixin,
                originMixinSource(),
                "Generate a legacy-only GameTest server-origin compatibility mixin"
            ),
            generatedSupport(
                config,
                warmupMixinConfigSource(),
                "Register the path-specific legacy GameTest compatibility mixins"
            )
        )
    }

    private fun sourceRoot(file: ParsedSource): Path {
        val packageSegments = file.unit.packageDeclaration
            .map { declaration ->
                declaration.nameAsString.split('.').filter(String::isNotBlank)
            }
            .orElse(emptyList())
        var root = file.path.parent
        repeat(packageSegments.size) {
            root = root.parent ?: fail(
                "Cannot derive Java source root for ${file.path}"
            )
        }
        return root
    }

    private fun generatedSupport(
        path: Path,
        source: String,
        description: String
    ): GeneratedSupport {
        val before = if (path.exists()) path.readText() else null
        if (before != null && before != source) {
            fail(
                "Generated GameTest compatibility path is already owned by " +
                    "different content: $path"
            )
        }
        return GeneratedSupport(path, source, before, description)
    }

    private fun legacyCallbackSource(): String =
        """
        package com.modporter.generated.gametest;

        public final class LegacyGameTestCallback
                implements java.util.function.Consumer<
                    net.minecraft.gametest.framework.GameTestHelper> {
            private final java.util.function.Consumer<
                net.minecraft.gametest.framework.GameTestHelper> delegate;

            public LegacyGameTestCallback(
                    java.util.function.Consumer<
                        net.minecraft.gametest.framework.GameTestHelper> delegate) {
                this.delegate = java.util.Objects.requireNonNull(delegate);
            }

            @Override
            public void accept(
                    net.minecraft.gametest.framework.GameTestHelper helper) {
                this.delegate.accept(helper);
            }

            public static int initialPlacementDelay(
                    net.minecraft.gametest.framework.GameTestInfo info,
                    int requestedDelay) {
                if (requestedDelay != 0) {
                    return requestedDelay;
                }
                return info.getTestFunction().function()
                        instanceof LegacyGameTestCallback ? 1 : 0;
            }

            public static int serverOriginX(
                    java.util.Collection<
                        net.minecraft.gametest.framework.TestFunction> testFunctions,
                    net.minecraft.util.RandomSource random,
                    int minimum,
                    int maximum) {
                java.util.Objects.requireNonNull(random);
                if (allCallbacksAreLegacy(testFunctions)) {
                    System.out.println(
                        "[ModPorter] Legacy GameTests use source-compatible " +
                            "fixture origin 0, -60, 0 (1.21.1 anchor 0, -59, 0)"
                    );
                    return 0;
                }
                return random.nextIntBetweenInclusive(minimum, maximum);
            }

            public static int serverOriginZ(
                    java.util.Collection<
                        net.minecraft.gametest.framework.TestFunction> testFunctions,
                    net.minecraft.util.RandomSource random,
                    int minimum,
                    int maximum) {
                java.util.Objects.requireNonNull(random);
                if (allCallbacksAreLegacy(testFunctions)) {
                    return 0;
                }
                return random.nextIntBetweenInclusive(minimum, maximum);
            }

            private static boolean allCallbacksAreLegacy(
                    java.util.Collection<
                        net.minecraft.gametest.framework.TestFunction> testFunctions) {
                java.util.Objects.requireNonNull(testFunctions);
                if (testFunctions.isEmpty()) {
                    return false;
                }
                for (net.minecraft.gametest.framework.TestFunction testFunction
                        : testFunctions) {
                    if (!(java.util.Objects.requireNonNull(testFunction).function()
                            instanceof LegacyGameTestCallback)) {
                        return false;
                    }
                }
                return true;
            }
        }
        """.trimIndent() + "\n"

    private fun warmupMixinSource(): String =
        """
        package com.modporter.generated.gametest.mixin;

        import com.modporter.generated.gametest.LegacyGameTestCallback;
        import net.minecraft.gametest.framework.GameTestInfo;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.ModifyArg;

        @Mixin(GameTestInfo.class)
        public abstract class LegacyGameTestWarmupMixin {
            @ModifyArg(
                method = "ensureStructureIsPlaced",
                at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/gametest/framework/GameTestInfo;" +
                        "startExecution(I)Lnet/minecraft/gametest/framework/GameTestInfo;"
                ),
                index = 0,
                require = 1
            )
            private int modporter${'$'}preserveLegacyInitialWarmup(
                    int requestedDelay) {
                return LegacyGameTestCallback.initialPlacementDelay(
                    (GameTestInfo) (Object) this,
                    requestedDelay
                );
            }
        }
        """.trimIndent() + "\n"

    private fun originMixinSource(): String =
        """
        package com.modporter.generated.gametest.mixin;

        import com.modporter.generated.gametest.LegacyGameTestCallback;
        import java.util.List;
        import net.minecraft.gametest.framework.GameTestServer;
        import net.minecraft.gametest.framework.TestFunction;
        import net.minecraft.util.RandomSource;
        import org.spongepowered.asm.mixin.Final;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.Shadow;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.ModifyArg;
        import org.spongepowered.asm.mixin.injection.Redirect;

        @Mixin(GameTestServer.class)
        public abstract class LegacyGameTestOriginMixin {
            @Shadow
            @Final
            private List<TestFunction> testFunctions;

            @Redirect(
                method = "startTests(Lnet/minecraft/server/level/ServerLevel;)V",
                at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;" +
                        "nextIntBetweenInclusive(II)I",
                    ordinal = 0
                ),
                require = 1
            )
            private int modporter${'$'}preserveLegacyOriginX(
                    RandomSource random,
                    int minimum,
                    int maximum) {
                return LegacyGameTestCallback.serverOriginX(
                    this.testFunctions,
                    random,
                    minimum,
                    maximum
                );
            }

            @Redirect(
                method = "startTests(Lnet/minecraft/server/level/ServerLevel;)V",
                at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;" +
                        "nextIntBetweenInclusive(II)I",
                    ordinal = 1
                ),
                require = 1
            )
            private int modporter${'$'}preserveLegacyOriginZ(
                    RandomSource random,
                    int minimum,
                    int maximum) {
                return LegacyGameTestCallback.serverOriginZ(
                    this.testFunctions,
                    random,
                    minimum,
                    maximum
                );
            }
        }
        """.trimIndent() + "\n"

    private fun warmupMixinConfigSource(): String =
        """
        {
          "required": true,
          "minVersion": "0.8",
          "package": "com.modporter.generated.gametest.mixin",
          "compatibilityLevel": "JAVA_21",
          "mixins": [
            "LegacyGameTestWarmupMixin",
            "LegacyGameTestOriginMixin"
          ],
          "injectors": {
            "defaultRequire": 1
          }
        }
        """.trimIndent() + "\n"

    private fun wrapNative(
        expression: Expression,
        plan: CandidatePlan
    ): MethodCallExpr =
        MethodCallExpr(expression.clone(), plan.names.accessor)

    private fun rejectNestedWrapper(
        expression: Expression,
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>,
        context: String
    ) {
        val nested = expression.childNodes
            .filterIsInstance<Expression>()
            .firstNotNullOfOrNull { child ->
                wrapperTypeOf(child, file, index, plans)
            }
        if (nested != null) {
            fail(
                "Unsupported nested source wrapper flow in $context at ${file.path}; " +
                    "$nested requires an explicit native adapter boundary"
            )
        }
    }

    private fun wrapperTypeOf(
        expression: Expression,
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ): String? {
        val canonical = when (expression) {
            is EnclosedExpr ->
                return wrapperTypeOf(expression.inner, file, index, plans)
            is ObjectCreationExpr -> index.resolve(expression.type, file)
            is NameExpr -> declaredTypeOf(expression, file, index)
            is FieldAccessExpr -> declaredTypeOf(expression, file, index)
            is MethodCallExpr -> wrapperTypeReturnedByCall(
                expression,
                file,
                index,
                plans
            )
            is ThisExpr -> expression.findAncestor(
                ClassOrInterfaceDeclaration::class.java
            ).orElse(null)?.let { owner ->
                canonicalName(owner, file.unit)
            }
            else -> null
        }
        return canonical?.takeIf(plans::containsKey)
    }

    private fun wrapperTypeReturnedByCall(
        call: MethodCallExpr,
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ): String? {
        val sourceOwner = call.scope.orElse(null)?.let { scope ->
            wrapperTypeNamedBy(scope, file, index, plans)
                ?: wrapperTypeOf(scope, file, index, plans)
        } ?: call.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .orElse(null)?.let { canonicalName(it, file.unit) }
            ?.takeIf(plans::containsKey)
        if (sourceOwner != null) {
            val declarations = plans.getValue(sourceOwner).owner.methods.filter {
                it.nameAsString == call.nameAsString
            }
            val returnTypes = declarations.mapNotNull {
                index.resolve(it.type, file)
            }.distinct()
            if (declarations.isNotEmpty() && returnTypes == listOf(sourceOwner)) {
                return sourceOwner
            }
        }

        if (call.nameAsString != "get" || call.arguments.size != 1) return null
        val scope = call.scope.orElse(null) ?: return null
        val scopeType = associatedTypeNodeOf(scope, file, index, plans)
            as? ClassOrInterfaceType
            ?: return null
        val arguments = scopeType.typeArguments.orElse(null) ?: return null
        if (arguments.size != 2) return null
        return index.resolve(arguments[1], file)?.takeIf(plans::containsKey)
    }

    private fun associatedTypeNodeOf(
        expression: Expression,
        file: ParsedSource,
        index: ProjectTypeIndex,
        plans: Map<String, CandidatePlan>
    ): Type? {
        declaredTypeNodeOf(expression, file)?.let { return it }
        if (expression !is FieldAccessExpr) return null
        val owner = wrapperTypeNamedBy(expression.scope, file, index, plans)
            ?: return null
        return plans.getValue(owner).owner.fields.flatMap { it.variables }
            .singleOrNull { it.nameAsString == expression.nameAsString }?.type
    }

    private fun declaredTypeOf(
        expression: Expression,
        file: ParsedSource,
        index: ProjectTypeIndex
    ): String? = when (expression) {
        is EnclosedExpr -> declaredTypeOf(expression.inner, file, index)
        is NameExpr -> declaredTypeOfName(expression, file, index)
        is FieldAccessExpr -> {
            if (expression.scope is ThisExpr) {
                fieldType(
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
        ).orElse(null)?.let { canonicalName(it, file.unit) }
        else -> null
    }

    private fun declaredTypeOfName(
        expression: NameExpr,
        file: ParsedSource,
        index: ProjectTypeIndex
    ): String? {
        val callable = expression.findAncestor(CallableDeclaration::class.java)
            .orElse(null)
        if (callable != null) {
            val localCandidates = callable.findAll(VariableDeclarator::class.java)
                .filter {
                    it.nameAsString == expression.nameAsString &&
                        beginsBefore(it, expression) &&
                        localScopeContains(it, expression)
                }
                .sortedByDescending(::beginOffset)
            if (localCandidates.isNotEmpty()) {
                return index.resolve(localCandidates.first().type, file)
            }
            callable.parameters.firstOrNull {
                it.nameAsString == expression.nameAsString
            }?.let { return index.resolve(it.type, file) }
        }
        val owner = expression.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .orElse(null)
        return fieldType(owner, expression.nameAsString, file, index)
    }

    private fun fieldType(
        owner: ClassOrInterfaceDeclaration?,
        name: String,
        file: ParsedSource,
        index: ProjectTypeIndex
    ): String? {
        val fields = owner?.fields.orEmpty().flatMap { it.variables }
            .filter { it.nameAsString == name }
        return fields.singleOrNull()?.let { index.resolve(it.type, file) }
    }

    private fun nearestMethodWithoutLambda(node: Node): MethodDeclaration? {
        var current = node.parentNode.orElse(null)
        while (current != null) {
            if (current is com.github.javaparser.ast.expr.LambdaExpr) return null
            if (current is MethodDeclaration) return current
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun MethodDeclaration.removeOverrideAnnotation() {
        annotations.removeIf {
            it.nameAsString == "Override" ||
                it.nameAsString == "java.lang.Override"
        }
    }

    private fun <T> List<T>.singleOrNullChecked(message: String): T? {
        if (size > 1) fail(message)
        return singleOrNull()
    }

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        var candidate = base
        while (candidate in used) candidate += "_"
        used += candidate
        return candidate
    }

    private fun beginsBefore(first: Node, second: Node): Boolean {
        val a = first.range.orElse(null)?.begin ?: return false
        val b = second.range.orElse(null)?.begin ?: return false
        return a.line < b.line || a.line == b.line && a.column <= b.column
    }

    private fun localScopeContains(
        variable: VariableDeclarator,
        expression: Expression
    ): Boolean {
        val declaration = variable.findAncestor(
            VariableDeclarationExpr::class.java
        ).orElse(null) ?: return false
        val parent = declaration.parentNode.orElse(null) ?: return false
        val scope = if (parent is ExpressionStmt) {
            parent.parentNode.orElse(null) ?: return false
        } else {
            parent
        }
        return scope === expression || scope.isAncestorOf(expression)
    }

    private fun beginOffset(node: Node): Long {
        val begin = node.range.orElse(null)?.begin ?: return Long.MIN_VALUE
        return begin.line.toLong() * 1_000_000L + begin.column
    }

    private fun fail(message: String): Nothing =
        throw IllegalStateException(message)
}

private val preservedWrapperInstanceMethods = setOf(
    "getTestName",
    "run",
    "getStructureName",
    "getMaxTicks",
    "isRequired",
    "getBatchName",
    "getSetupTicks",
    "getRotation",
    "isFlaky",
    "getMaxAttempts",
    "getRequiredSuccesses",
    "batchName",
    "testName",
    "structureName",
    "rotation",
    "maxTicks",
    "setupTicks",
    "required",
    "manualOnly",
    "maxAttempts",
    "requiredSuccesses",
    "skyAccess",
    "function",
    "toString"
)

private val objectInstanceMethods = setOf(
    "equals",
    "hashCode",
    "getClass",
    "clone",
    "finalize",
    "notify",
    "notifyAll",
    "wait"
)

private data class ParsedSource(
    val path: Path,
    val source: String,
    val unit: CompilationUnit
)

private data class PreparedChange(
    val file: ParsedSource,
    val migrated: String
)

private data class GeneratedSupport(
    val path: Path,
    val source: String,
    val before: String?,
    val description: String
)

private data class CandidatePlan(
    val canonical: String,
    val owner: ClassOrInterfaceDeclaration,
    val file: ParsedSource,
    val primary: ConstructorDeclaration,
    val sourceSuper: ExplicitConstructorInvocationStmt,
    val getTestName: MethodDeclaration?,
    val run: MethodDeclaration?,
    val sourceRunCall: MethodCallExpr?,
    val names: AdapterNames
)

private data class AdapterNames(
    val batch: String,
    val sourceTest: String,
    val structure: String,
    val rotation: String,
    val maxTicks: String,
    val setupTicks: String,
    val required: String,
    val requiredSuccesses: String,
    val maxAttempts: String,
    val sourceFunction: String,
    val native: String,
    val accessor: String
)

private class ProjectTypeIndex(files: List<ParsedSource>) {
    private val types = files.flatMap { file ->
        file.unit.findAll(TypeDeclaration::class.java).mapNotNull { declaration ->
            canonicalName(declaration, file.unit)
        }
    }.toSet()

    fun resolve(type: Type, file: ParsedSource): String? {
        if (type.isVoidType) return "void"
        if (type.isPrimitiveType) return type.asString()
        if (type.isArrayType) {
            return resolve(type.asArrayType().componentType, file)?.plus("[]")
        }
        val classType = type as? ClassOrInterfaceType ?: return null
        return resolve(classType, file)
    }

    fun resolve(type: ClassOrInterfaceType, file: ParsedSource): String? {
        val spelling = type.nameWithScope
        if (spelling == "String") {
            val shadow = samePackageCandidates("String", file)
            val explicit = explicitImports("String", file)
            return when {
                shadow.isNotEmpty() -> shadow.singleOrNull()
                explicit.isNotEmpty() -> explicit.singleOrNull()
                else -> STRING
            }
        }
        if (spelling.firstOrNull()?.isLowerCase() == true) return spelling

        val first = spelling.substringBefore('.')
        val suffix = spelling.removePrefix(first)
        val local = file.unit.findAll(TypeDeclaration::class.java)
            .mapNotNull { declaration ->
                canonicalName(declaration, file.unit)
                    ?.takeIf {
                        it.substringAfterLast('.').substringAfterLast('$') == first
                    }
            }.distinct()
        if (local.isNotEmpty()) {
            return local.singleOrNull()?.plus(suffix)
        }

        val explicit = explicitImports(first, file)
        if (explicit.isNotEmpty()) {
            return explicit.singleOrNull()?.plus(suffix)
        }

        val samePackage = samePackageCandidates(first, file)
        if (samePackage.isNotEmpty()) {
            return samePackage.singleOrNull()?.plus(suffix)
        }

        val wildcard = file.unit.imports.filter {
            !it.isStatic && it.isAsterisk
        }.map { "${it.nameAsString}.$spelling" }
            .filter { candidate ->
                candidate in types ||
                    candidate == TEST_FUNCTION ||
                    candidate == GAME_TEST_HELPER ||
                    candidate == ROTATION ||
                    candidate == CONSUMER
            }.distinct()
        return wildcard.singleOrNull()
    }

    private fun explicitImports(simple: String, file: ParsedSource): List<String> =
        file.unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.name.identifier == simple
        }.map { it.nameAsString }.distinct()

    private fun samePackageCandidates(
        simple: String,
        file: ParsedSource
    ): List<String> {
        val packageName = file.unit.packageDeclaration
            .map { it.nameAsString }
            .orElse("")
        return types.filter {
            it.substringAfterLast('.').substringAfterLast('$') == simple &&
                it.substringBeforeLast('.', "") == packageName
        }
    }
}

private fun canonicalName(
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
