package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Migrates Create's retained source-owned FluidIngredient without replacing its JSON or network
 * schema with NeoForge's SizedFluidIngredient family.
 *
 * Forge wrote a stack ingredient as registry-name ResourceLocation, registry numeric VarInt, then
 * NBT. The target registry codec writes only the numeric ID, so this rule spells out the original
 * frame. The old three-argument FluidStack constructor copied its NBT; CustomData.of provides the
 * same copy boundary. The old fromFluidStack method instead retained the source stack's live tag,
 * so snapshotting is admitted only after every source call is proven to consume a fresh value that
 * is dead after capture, including private helper parameters traced to all of their callers.
 */
internal object SourceOwnedFluidIngredient {
    const val RULE_ID = "struct-source-owned-fluid-ingredient"

    private const val OWNER =
        "com.simibubi.create.foundation.fluid.FluidIngredient"
    private const val FLUID_STACK =
        "net.neoforged.neoforge.fluids.FluidStack"
    private const val BUFFER =
        "net.minecraft.network.RegistryFriendlyByteBuf"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): Result = try {
        migrateChecked(pass, projectDir, dryRun)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "FluidIngredient admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "FluidIngredient migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): Result {
        val path = projectDir.resolve(
            "src/main/java/com/simibubi/create/foundation/fluid/FluidIngredient.java"
        )
        if (!Files.isRegularFile(path)) return Result.EMPTY
        val source = path.readText()
        val residuals = listOf(
            source.contains(".getTag()"),
            source.contains(".readRegistryId()"),
            source.contains(".writeRegistryId("),
            Regex(
                """new\s+FluidStack\s*\(\s*fluid\s*,\s*amountRequired\s*,\s*tagToMatch\s*\)"""
            ).containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source))
        )
        val legacyWireFamily = residuals.any { it }
        if (!legacyWireFamily && !source.contains(".getOrCreateTag(")) return Result.EMPTY
        if (legacyWireFamily && !residuals.all { it }) {
            abort("$OWNER has only part of the four-member legacy family")
        }
        if (!targetContractIsVerified()) {
            abort("FluidIngredient target registry and CustomData contracts are not verified")
        }

        val files = javaFiles(projectDir)
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val parsed = files.map { file ->
            val text = file.readText()
            val result = parser.parse(text)
            if (!result.isSuccessful || result.result.isEmpty) {
                abort("FluidIngredient usage proof could not parse $file")
            }
            ParsedSource(file, text, result.result.orElseThrow())
        }
        val candidate = parsed.singleOrNull { it.path == path }
            ?: abort("Cannot locate the parsed $OWNER source")
        val owner = candidate.unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .singleOrNull { canonicalName(it, candidate) == OWNER }
            ?: abort("$OWNER must be one exact source-owned class")
        val stackIngredient = owner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
            .singleOrNull { it.nameAsString == "FluidStackIngredient" }
            ?: abort("$OWNER has no exact FluidStackIngredient member")
        if (!owner.isPublic || !owner.isAbstract || !stackIngredient.isPublic ||
            !stackIngredient.isStatic
        ) {
            abort("$OWNER is outside the admitted public source-owned family")
        }

        if (!legacyWireFamily) {
            val index = SourceIndex(parsed)
            val jsonCapture = freshJsonTagCapture(candidate, stackIngredient, index)
                ?: return Result.EMPTY
            val factory = owner.methods.singleOrNull {
                it.nameAsString == "fromFluidStack" && it.isPublic && it.isStatic &&
                    it.parameters.size == 1
            } ?: abort("$OWNER has no unique source-owned factory")
            admitClosedFamily(candidate, owner, factory, index)
            LexicalPreservingPrinter.setup(candidate.unit)
            replaceFreshJsonTagCapture(jsonCapture)
            return publish(candidate, parser, dryRun)
        }

        val fromFluidStack = owner.methods.singleOrNull {
            it.nameAsString == "fromFluidStack" &&
                it.isPublic && it.isStatic &&
                it.parameters.size == 1 &&
                it.parameters.single().typeAsString.substringAfterLast('.') == "FluidStack"
        } ?: abort("$OWNER has no exact fromFluidStack(FluidStack) factory")
        val getTag = fromFluidStack.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "getTag" &&
                it.arguments.isEmpty() &&
                it.scope.map(Node::toString).orElse("") ==
                    fromFluidStack.parameters.single().nameAsString
        } ?: abort("$OWNER.fromFluidStack has no exact live-tag capture")
        val tagAssignment = getTag.findAncestor(AssignExpr::class.java).orElse(null)
        if (tagAssignment?.target?.toString()?.substringAfterLast('.') != "tagToMatch") {
            abort("$OWNER.fromFluidStack live tag does not initialize tagToMatch")
        }

        val readBuffer = bufferMethod(stackIngredient, "readInternal")
        val writeBuffer = bufferMethod(stackIngredient, "writeInternal")
        val readRegistry = readBuffer.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "readRegistryId" && it.arguments.isEmpty()
        } ?: abort("$OWNER has no exact legacy registry read")
        val writeRegistry = writeBuffer.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "writeRegistryId" && it.arguments.size == 2
        } ?: abort("$OWNER has no exact legacy registry write")
        if (readRegistry.scope.map(Node::toString).orElse("") !=
                readBuffer.parameters.single().nameAsString ||
            writeRegistry.scope.map(Node::toString).orElse("") !=
                writeBuffer.parameters.single().nameAsString ||
            readBuffer.body.orElseThrow().statements.size != 2 ||
            writeBuffer.body.orElseThrow().statements.size != 2
        ) {
            abort("$OWNER registry methods are outside the exact two-field wire shape")
        }

        val matching = stackIngredient.methods.singleOrNull {
            it.nameAsString == "determineMatchingFluidStacks" &&
                it.parameters.isEmpty()
        } ?: abort("$OWNER has no exact matching-stack constructor")
        val taggedConstruction = matching.findAll(ObjectCreationExpr::class.java)
            .singleOrNull {
                it.type.nameAsString == "FluidStack" &&
                    it.arguments.map(Node::toString) ==
                    listOf("fluid", "amountRequired", "tagToMatch")
            } ?: abort("$OWNER has no exact copying three-argument FluidStack construction")

        val index = SourceIndex(parsed)
        admitClosedFamily(candidate, owner, fromFluidStack, index)
        val jsonCapture = freshJsonTagCapture(candidate, stackIngredient, index)

        LexicalPreservingPrinter.setup(candidate.unit)
        getTag.replace(
            StaticJavaParser.parseExpression<Expression>(
                "fluidStack.getOrDefault(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
            )
        )
        readBuffer.setBody(StaticJavaParser.parseBlock("""
            {
                ResourceLocation registryName = buffer.readResourceLocation();
                if (!registryName.equals(Registries.FLUID.location()))
                    throw new IllegalArgumentException("Expected fluid registry, got " + registryName);
                int fluidId = buffer.readVarInt();
                fluid = buffer.registryAccess().registryOrThrow(Registries.FLUID).byId(fluidId);
                if (fluid == null || buffer.registryAccess().registryOrThrow(Registries.FLUID)
                    .getIdOrThrow(fluid) != fluidId)
                    throw new IllegalArgumentException("Unknown fluid id " + fluidId);
                tagToMatch = buffer.readNbt();
            }
        """.trimIndent()))
        writeBuffer.setBody(StaticJavaParser.parseBlock("""
            {
                buffer.writeResourceLocation(Registries.FLUID.location());
                buffer.writeVarInt(buffer.registryAccess().registryOrThrow(Registries.FLUID)
                    .getIdOrThrow(fluid));
                buffer.writeNbt(tagToMatch);
            }
        """.trimIndent()))
        matching.setBody(StaticJavaParser.parseBlock("""
            {
                if (tagToMatch.isEmpty())
                    return ImmutableList.of(new FluidStack(fluid, amountRequired));
                FluidStack stack = new FluidStack(fluid, amountRequired);
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tagToMatch));
                return ImmutableList.of(stack);
            }
        """.trimIndent()))
        jsonCapture?.let(::replaceFreshJsonTagCapture)
        return publish(candidate, parser, dryRun)
    }

    private fun freshJsonTagCapture(
        candidate: ParsedSource,
        stackIngredient: ClassOrInterfaceDeclaration,
        index: SourceIndex
    ): MethodCallExpr? {
        val methods = stackIngredient.methods.filter { method ->
            method.nameAsString == "readInternal" && method.parameters.size == 1 &&
                method.parameters.single().typeAsString == "JsonObject"
        }
        val calls = methods.flatMap { it.findAll(MethodCallExpr::class.java) }
            .filter { it.nameAsString == "getOrCreateTag" && it.arguments.isEmpty() }
        if (calls.isEmpty()) return null
        val method = methods.singleOrNull()
            ?: abort("$OWNER JSON tag capture has ambiguous readers")
        val call = calls.singleOrNull()
            ?: abort("$OWNER JSON tag capture is not unique")
        val expected = StaticJavaParser.parseBlock("""
            {
                FluidStack stack = FluidHelper.deserializeFluidStack(json);
                fluid = stack.getFluid();
                tagToMatch = stack.getOrCreateTag();
            }
        """.trimIndent())
        if (method.parameters.single().nameAsString != "json" ||
            method.body.orElse(null) != expected
        ) abort("$OWNER JSON tag capture is outside the exact fresh-local shape")
        val receiver = call.scope.orElse(null) as? NameExpr
            ?: abort("$OWNER JSON tag capture has no local receiver")
        val use = CallRef(candidate, "$OWNER.FluidStackIngredient", method, call)
        if (!index.freshAndDead(receiver, use, mutableSetOf())) {
            abort("$OWNER JSON tag capture may retain mutable tag aliasing")
        }
        return call
    }

    private fun replaceFreshJsonTagCapture(call: MethodCallExpr) {
        call.replace(StaticJavaParser.parseExpression<Expression>(
            "stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                "net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
        ))
    }

    private fun publish(candidate: ParsedSource, parser: JavaParser, dryRun: Boolean): Result {
        val migrated = LexicalPreservingPrinter.print(candidate.unit)
        if (!parser.parse(migrated).isSuccessful) {
            abort("FluidIngredient migration emitted invalid Java")
        }
        if (!dryRun) candidate.path.writeText(migrated)
        return Result(
            listOf(
                Change(
                    file = candidate.path,
                    line = 1,
                    description =
                        "Preserve the source-owned FluidIngredient data and registry wire family",
                    before =
                        "live Forge tag, registry-name/VarInt helpers, and three-argument stack",
                    after =
                        "proven snapshot, explicit registry-name/VarInt frame, and CustomData copy",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            ),
            emptyList()
        )
    }

    private fun bufferMethod(
        owner: ClassOrInterfaceDeclaration,
        name: String
    ): MethodDeclaration = owner.methods.singleOrNull {
        it.nameAsString == name &&
            it.parameters.size == 1 &&
            it.parameters.single().typeAsString.substringAfterLast('.') ==
            "RegistryFriendlyByteBuf"
    } ?: abort(
        "$OWNER requires registry-buffer propagation before its $name migration"
    )

    private fun admitClosedFamily(
        candidate: ParsedSource,
        owner: ClassOrInterfaceDeclaration,
        fromFluidStack: MethodDeclaration,
        index: SourceIndex
    ) {
        index.files.filter { it !== candidate }.forEach { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java).forEach { type ->
                if (type.extendedTypes.any {
                        it.nameAsString == "FluidIngredient" ||
                            it.nameWithScope == OWNER
                    }
                ) {
                    abort("$OWNER has a source-owned subclass in ${file.path}")
                }
            }
            val code = JavaSourceText.maskCommentsAndLiterals(file.source)
            if (Regex("""\b(?:FluidStackIngredient|FluidTagIngredient)\b""")
                    .containsMatchIn(code) ||
                Regex("""\.(?:tagToMatch|matchingFluidStacks)\b""")
                    .containsMatchIn(code)
            ) {
                abort("$OWNER nested state escapes into ${file.path}")
            }
        }

        val calls = index.calls.filter {
            it.call.nameAsString == "fromFluidStack"
        }
        val occurrences = index.files.sumOf {
            Regex("""\bfromFluidStack\b""")
                .findAll(JavaSourceText.maskCommentsAndLiterals(it.source)).count()
        }
        if (calls.isEmpty() || occurrences != calls.size + 1) {
            abort("$OWNER.fromFluidStack has unresolved references")
        }
        val factoryOwner = canonicalName(owner, candidate)
            ?: abort("Cannot resolve the canonical $OWNER declaration")
        calls.forEach { call ->
            if (index.resolve(call) != index.method(fromFluidStack, factoryOwner)) {
                abort("A fromFluidStack call in ${call.file.path} is not source-resolved")
            }
            val argument = call.call.arguments.singleOrNull()
                ?: abort("A fromFluidStack call in ${call.file.path} has wrong arity")
            if (!index.freshAndDead(argument, call, mutableSetOf())) {
                abort(
                    "A fromFluidStack call in ${call.file.path} may retain mutable tag aliasing"
                )
            }
        }
    }

    private fun targetContractIsVerified(): Boolean {
        fun public(owner: String, name: String, descriptor: String): Boolean =
            TargetMembers.inheritedMethod(owner, name, descriptor)?.access == "public"
        return public(
            BUFFER,
            "registryAccess",
            "()Lnet/minecraft/core/RegistryAccess;"
        ) && public(
            "net.minecraft.core.RegistryAccess",
            "registryOrThrow",
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Registry;"
        ) && public(
            "net.minecraft.core.IdMap",
            "byId",
            "(I)Ljava/lang/Object;"
        ) && public(
            "net.minecraft.core.IdMap",
            "getIdOrThrow",
            "(Ljava/lang/Object;)I"
        ) && public(
            "net.minecraft.world.item.component.CustomData",
            "of",
            "(Lnet/minecraft/nbt/CompoundTag;)" +
                "Lnet/minecraft/world/item/component/CustomData;"
        ) && public(
            FLUID_STACK,
            "set",
            "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)" +
                "Ljava/lang/Object;"
        )
    }

    private class SourceIndex(val files: List<ParsedSource>) {
        private val types = files.flatMap { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull {
                canonicalName(it, file)?.let { name -> TypeRef(file, name, it) }
            }
        }
        private val methods = types.flatMap { type ->
            type.type.methods.map { MethodRef(type.file, type.name, it) }
        }
        val calls = files.flatMap { file ->
            file.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
                val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
                    ?: return@mapNotNull null
                val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .map { canonicalName(it, file) }.orElse(null)
                    ?: return@mapNotNull null
                CallRef(file, owner, method, call)
            }
        }

        fun method(method: MethodDeclaration, owner: String): MethodRef =
            methods.single { it.method === method && it.owner == owner }

        fun resolve(call: CallRef): MethodRef? {
            val targetOwner = call.call.scope.map { scope ->
                resolveType(scope.toString(), call.file)
            }.orElse(call.owner) ?: return null
            return methods.singleOrNull {
                it.owner == targetOwner &&
                    it.method.nameAsString == call.call.nameAsString &&
                    it.method.parameters.size == call.call.arguments.size
            }
        }

        fun freshAndDead(
            expression: Expression,
            use: CallRef,
            seenParameters: MutableSet<String>
        ): Boolean {
            val direct = expression.unwrap()
            if (direct !is NameExpr) {
                return freshExpression(direct, method(use), mutableSetOf())
            }
            val local = localBinding(direct.nameAsString, direct, method(use))
            if (local != null) {
                return freshLocal(local, direct, method(use), mutableSetOf()) &&
                    deadAfter(direct.nameAsString, use.call, local.scope, use.file)
            }
            val parameter = use.method.parameters.singleOrNull {
                it.nameAsString == direct.nameAsString
            } ?: return false
            val target = method(use)
            if (!target.method.isPrivate || !target.method.isStatic ||
                !deadAfter(parameter.nameAsString, use.call, target.method.body.orElseThrow(), use.file)
            ) return false
            val index = target.method.parameters.indexOf(parameter)
            val key = "${target.owner}#${target.method.nameAsString}/${target.method.parameters.size}:$index"
            if (!seenParameters.add(key)) return false
            val callers = calls.filter { resolve(it) == target }
            return callers.isNotEmpty() && callers.all { caller ->
                freshAndDead(caller.call.arguments[index], caller, seenParameters)
            }
        }

        private fun method(call: CallRef): MethodRef =
            methods.single { it.method === call.method && it.owner == call.owner }

        private fun freshExpression(
            expression: Expression,
            context: MethodRef,
            seenMethods: MutableSet<String>
        ): Boolean {
            return when (val direct = expression.unwrap()) {
                is ObjectCreationExpr ->
                    direct.type.nameAsString == "FluidStack" ||
                        direct.type.nameWithScope == FLUID_STACK
                is FieldAccessExpr ->
                    direct.toString() == "FluidStack.EMPTY" ||
                        direct.toString() == "$FLUID_STACK.EMPTY"
                is ConditionalExpr ->
                    freshExpression(direct.thenExpr, context, seenMethods) &&
                        freshExpression(direct.elseExpr, context, seenMethods)
                is MethodCallExpr -> {
                    if (direct.nameAsString == "copy" && direct.arguments.isEmpty()) {
                        true
                    } else {
                        val call = CallRef(context.file, context.owner, context.method, direct)
                        val target = resolve(call) ?: return false
                        freshMethod(target, seenMethods)
                    }
                }
                is NameExpr -> {
                    val binding = localBinding(direct.nameAsString, direct, context)
                        ?: return false
                    freshLocal(binding, direct, context, seenMethods)
                }
                else -> false
            }
        }

        private fun freshMethod(
            method: MethodRef,
            seenMethods: MutableSet<String>
        ): Boolean {
            val key = "${method.owner}#${method.method.nameAsString}/${method.method.parameters.size}"
            if (!seenMethods.add(key)) return false
            return try {
                val returns = method.method.findAll(ReturnStmt::class.java).filter {
                    it.findAncestor(MethodDeclaration::class.java).orElse(null) === method.method
                }
                returns.isNotEmpty() && returns.all {
                    it.expression.map { value ->
                        freshExpression(value, method, seenMethods)
                    }.orElse(false)
                }
            } finally {
                seenMethods.remove(key)
            }
        }

        private fun freshLocal(
            binding: LocalBinding,
            use: Node,
            context: MethodRef,
            seenMethods: MutableSet<String>
        ): Boolean {
            val values = mutableListOf<Expression>()
            binding.declaration.initializer.ifPresent(values::add)
            binding.scope.findAll(AssignExpr::class.java).filter { assignment ->
                assignment.target.unwrap().let {
                    it is NameExpr && it.nameAsString == binding.declaration.nameAsString
                } &&
                    binding.file.offset(assignment) > binding.file.offset(binding.declaration) &&
                    binding.file.offset(assignment) < binding.file.offset(use)
            }.forEach { values += it.value }
            return values.isNotEmpty() && values.all {
                freshExpression(it, context, seenMethods)
            }
        }

        private fun localBinding(
            name: String,
            use: Node,
            context: MethodRef
        ): LocalBinding? {
            val candidates = context.method.findAll(VariableDeclarator::class.java)
                .filter { it.nameAsString == name }
                .filter { context.file.offset(it) < context.file.offset(use) }
                .mapNotNull { declaration ->
                    val scope = declaration.findAncestor(BlockStmt::class.java)
                        .orElse(null) ?: return@mapNotNull null
                    if (contains(scope, use, context.file)) {
                        LocalBinding(context.file, declaration, scope)
                    } else {
                        null
                    }
                }
            return candidates.maxByOrNull {
                context.file.offset(it.declaration)
            }
        }

        private fun deadAfter(
            name: String,
            use: Node,
            scope: Node,
            file: ParsedSource
        ): Boolean = scope.findAll(NameExpr::class.java).none {
            it.nameAsString == name && file.offset(it) > file.endOffset(use)
        }

        private fun resolveType(source: String, file: ParsedSource): String? {
            val plain = source.replace(" ", "")
            if (plain.firstOrNull()?.isLowerCase() == true && '.' in plain) return plain
            val first = plain.substringBefore('.')
            val explicit = file.unit.imports.filter {
                !it.isStatic && !it.isAsterisk && it.name.identifier == first
            }.map { it.nameAsString }.distinct()
            if (explicit.size == 1) return explicit.single() + plain.removePrefix(first)
            val packageName = file.unit.packageDeclaration.map {
                it.nameAsString
            }.orElse("")
            val samePackage = types.filter {
                it.name.substringAfterLast('.') == first &&
                    it.name.substringBeforeLast('.', "") == packageName
            }
            if (samePackage.size == 1) {
                return samePackage.single().name + plain.removePrefix(first)
            }
            val unique = types.filter { it.name.substringAfterLast('.') == first }
            return unique.singleOrNull()?.name?.plus(plain.removePrefix(first))
        }
    }

    private fun canonicalName(
        declaration: ClassOrInterfaceDeclaration,
        file: ParsedSource
    ): String? {
        val names = mutableListOf<String>()
        var current: Node = declaration
        while (current is ClassOrInterfaceDeclaration) {
            names += current.nameAsString
            current = current.parentNode.orElse(null) ?: return null
        }
        if (current !== file.unit) return null
        val packageName = file.unit.packageDeclaration.map {
            it.nameAsString
        }.orElse("")
        val nested = names.asReversed().joinToString(".")
        return if (packageName.isBlank()) nested else "$packageName.$nested"
    }

    private fun contains(scope: Node, child: Node, file: ParsedSource): Boolean =
        file.offset(scope) <= file.offset(child) &&
            file.endOffset(scope) >= file.endOffset(child)

    private fun Expression.unwrap(): Expression {
        var value = this
        while (true) {
            value = when (value) {
                is EnclosedExpr -> value.inner
                is CastExpr -> value.expression
                else -> return value
            }
        }
    }

    private fun javaFiles(projectDir: Path): List<Path> {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        return JavaSourceText.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "java" }
                .sorted()
                .toList()
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private data class ParsedSource(
        val path: Path,
        val source: String,
        val unit: com.github.javaparser.ast.CompilationUnit
    ) {
        private val starts = buildList {
            add(0)
            source.forEachIndexed { index, char ->
                if (char == '\n') add(index + 1)
            }
        }

        fun offset(node: Node): Int = node.range.map {
            starts[it.begin.line - 1] + it.begin.column - 1
        }.orElse(-1)

        fun endOffset(node: Node): Int = node.range.map {
            starts[it.end.line - 1] + it.end.column - 1
        }.orElse(-1)
    }

    private data class TypeRef(
        val file: ParsedSource,
        val name: String,
        val type: ClassOrInterfaceDeclaration
    )

    private data class MethodRef(
        val file: ParsedSource,
        val owner: String,
        val method: MethodDeclaration
    )

    private data class CallRef(
        val file: ParsedSource,
        val owner: String,
        val method: MethodDeclaration,
        val call: MethodCallExpr
    )

    private data class LocalBinding(
        val file: ParsedSource,
        val declaration: VariableDeclarator,
        val scope: BlockStmt
    )
}
