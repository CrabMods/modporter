package com.modporter.core.transforms.build

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A dependency with no NeoForge 1.21.1 artifact leaves its bridging classes behind: the sources
 * that import its packages. The rest of the mod reaches them behind a mod-loaded check, so they
 * keep their public surface; every member that touched the dependency loses its body (returning
 * the type's default) and a member whose signature names a removed type is dropped. Create 6 did
 * exactly this in its own sources for its Dynamic Trees bridge.
 */
internal object RemovedDependencyStubs {
    const val RULE_ID = "build-removed-dependency-stub"

    fun stub(projectDir: Path, removedPackagePrefixes: Set<String>, dryRun: Boolean): List<Change> {
        if (removedPackagePrefixes.isEmpty()) return emptyList()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val prefixes = removedPackagePrefixes.map { it.trimEnd('.') }
        val changes = mutableListOf<Change>()
        Files.walk(srcDir).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.forEach { file ->
                val source = file.readText()
                if (prefixes.none { source.contains("import $it") }) return@forEach
                val cu = parser.parse(source).result.orElse(null) ?: return@forEach
                val removedImports = cu.imports.filter { import ->
                    prefixes.any { prefix -> import.nameAsString == prefix || import.nameAsString.startsWith("$prefix.") }
                }
                if (removedImports.isEmpty()) return@forEach
                val removedNames = removedImports.filterNot { it.isAsterisk }.map { it.nameAsString.substringAfterLast('.') }.toSet()
                removedImports.forEach { it.remove() }
                val touched = mutableListOf<String>()
                cu.findAll(TypeDeclaration::class.java).toList().forEach { type -> stubType(type, removedNames, touched) }
                if (!dryRun) file.writeText(cu.toString())
                changes += Change(
                    file = file,
                    line = 1,
                    description = "Keep the bridging class of a removed dependency with inert members",
                    before = removedImports.joinToString(", ") { it.nameAsString },
                    after = touched.joinToString(", ").ifEmpty { "imports only" },
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            }
        }
        return changes
    }

    /**
     * Strict preparation-lane variant. It refuses imports whose simple names cannot be bound
     * without a symbol solver, records each changed member before and after the AST rewrite, and
     * proves the rendered source contains no remaining reference to a removed imported type.
     */
    fun stubWithReceipts(
        projectDir: Path,
        removedPackagePrefixes: Set<String>,
        dryRun: Boolean
    ): RemovedDependencyStubResult {
        if (removedPackagePrefixes.isEmpty()) {
            return RemovedDependencyStubResult(emptyList(), emptyList())
        }
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) {
            return RemovedDependencyStubResult(emptyList(), emptyList())
        }
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val prefixes = removedPackagePrefixes.map { it.trimEnd('.') }.toSortedSet()
        val changes = mutableListOf<Change>()
        val receipts = mutableListOf<RemovedDependencySourceReceipt>()
        Files.walk(srcDir).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.forEach { file ->
                val source = file.readText()
                if (prefixes.none { source.contains("import $it") }) return@forEach
                val parsed = parser.parse(source)
                require(parsed.isSuccessful && parsed.result.isPresent) {
                    "Removed-dependency source does not parse for receipt admission: $file"
                }
                val cu = parsed.result.get()
                val removedImports = cu.imports.filter { imported ->
                    prefixes.any { prefix ->
                        imported.nameAsString == prefix ||
                            imported.nameAsString.startsWith("$prefix.")
                    }
                }
                if (removedImports.isEmpty()) return@forEach
                require(removedImports.none(ImportDeclaration::isStatic)) {
                    "Static removed-dependency imports are ambiguous in $file: " +
                        removedImports.filter(ImportDeclaration::isStatic)
                            .joinToString { it.toString().trim() }
                }
                require(removedImports.none(ImportDeclaration::isAsterisk)) {
                    "Wildcard removed-dependency imports are ambiguous in $file: " +
                        removedImports.filter(ImportDeclaration::isAsterisk)
                            .joinToString { it.toString().trim() }
                }

                val importsBySimpleName = removedImports.groupBy {
                    it.nameAsString.substringAfterLast('.')
                }
                require(importsBySimpleName.values.none { it.size != 1 }) {
                    "Removed-dependency imports have colliding simple names in $file"
                }
                val removedTypes = importsBySimpleName.mapValues { it.value.single().nameAsString }
                rejectShadowedRemovedNames(cu, removedTypes.keys, file)

                val importPrefixes = removedImports.associate { imported ->
                    val matches = prefixes.filter { prefix ->
                        imported.nameAsString == prefix ||
                            imported.nameAsString.startsWith("$prefix.")
                    }
                    require(matches.size == 1) {
                        "Removed import has overlapping package-prefix ownership in $file: " +
                            imported.nameAsString
                    }
                    imported.nameAsString to matches.single()
                }
                val beforeSourceSha256 = buildReceiptSha256(file.readBytes())
                removedImports.forEach { it.remove() }
                val pending = mutableListOf<PendingRewrite>()
                cu.types.forEach { type ->
                    strictStubType(type, removedTypes, file, pending)
                }
                require(pending.isNotEmpty()) {
                    "Removed imports have no exact inert member rewrite in $file"
                }
                val remaining = cu.findAll(SimpleName::class.java)
                    .filter { it.identifier in removedTypes.keys }
                require(remaining.isEmpty()) {
                    "Removed imported type remains after inert rewrite in $file: " +
                        remaining.joinToString { it.identifier }
                }

                val rendered = cu.toString()
                val renderedParse = parser.parse(rendered)
                require(renderedParse.isSuccessful && renderedParse.result.isPresent) {
                    "Rendered removed-dependency stub does not parse: $file"
                }
                val relative = file.relativeTo(projectDir).toString().replace('\\', '/')
                val inertRewrites = pending.map { rewrite ->
                    BuildInertRewriteReceipt(
                        path = relative,
                        owner = rewrite.ownerName,
                        memberSignature = rewrite.memberSignature,
                        removedTypes = rewrite.removedTypes.sorted(),
                        beforeNodeSha256 = rewrite.beforeNodeSha256,
                        afterNodeSha256 = rewrite.afterNodeSha256,
                        afterOwnerSha256 = astSha256(rewrite.owner),
                        verifiedPostcondition = rewrite.verifiedPostcondition
                    )
                }
                val afterSourceSha256 = buildReceiptSha256(rendered.toByteArray(Charsets.UTF_8))
                if (!dryRun) file.writeText(rendered)
                val removedImportNames = removedImports.map(ImportDeclaration::getNameAsString)
                val ownedPrefixes = removedImportNames.map(importPrefixes::getValue).distinct()
                receipts += RemovedDependencySourceReceipt(
                    path = relative,
                    beforeSourceSha256 = beforeSourceSha256,
                    afterSourceSha256 = afterSourceSha256,
                    removedImports = removedImportNames,
                    packagePrefixes = ownedPrefixes,
                    inertRewrites = inertRewrites
                )
                changes += Change(
                    file = file,
                    line = 1,
                    description =
                        "Keep the bridging class of a removed dependency with receipt-bound inert members",
                    before = removedImportNames.joinToString(", "),
                    after = inertRewrites.joinToString(", ") { it.memberSignature },
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            }
        }
        return RemovedDependencyStubResult(changes, receipts)
    }

    private data class PendingRewrite(
        val owner: TypeDeclaration<*>,
        val ownerName: String,
        val memberSignature: String,
        val removedTypes: Set<String>,
        val beforeNodeSha256: String,
        val afterNodeSha256: String?,
        val verifiedPostcondition: String
    )

    private fun strictStubType(
        type: TypeDeclaration<*>,
        removedTypes: Map<String, String>,
        file: Path,
        pending: MutableList<PendingRewrite>
    ) {
        type.members.toList().forEach { member ->
            val ownerName = ownerName(type)
            when (member) {
                is FieldDeclaration -> {
                    val mentioned = mentionedRemovedTypes(member.commonType, removedTypes)
                    if (mentioned.isNotEmpty()) {
                        val beforeHash = astSha256(member)
                        val signature = "field " +
                            member.variables.joinToString(",") { it.nameAsString }
                        member.remove()
                        pending += PendingRewrite(
                            type, ownerName, signature, mentioned, beforeHash, null,
                            "removed-signature-member"
                        )
                    }
                }
                is MethodDeclaration -> {
                    val signatureTypes = buildSet {
                        addAll(mentionedRemovedTypes(member.type, removedTypes))
                        member.parameters.forEach {
                            addAll(mentionedRemovedTypes(it, removedTypes))
                        }
                        member.typeParameters.forEach {
                            addAll(mentionedRemovedTypes(it, removedTypes))
                        }
                    }
                    val signature = methodSignature(member)
                    if (signatureTypes.isNotEmpty()) {
                        val beforeHash = astSha256(member)
                        member.remove()
                        pending += PendingRewrite(
                            type, ownerName, signature, signatureTypes, beforeHash, null,
                            "removed-signature-member"
                        )
                    } else {
                        val body = member.body.orElse(null) ?: return@forEach
                        val bodyTypes = mentionedRemovedTypes(body, removedTypes)
                        if (bodyTypes.isNotEmpty()) {
                            val first = body.statements.firstOrNull()
                            require(first != null &&
                                mentionedRemovedTypes(first, removedTypes).isNotEmpty()
                            ) {
                                "Method has an unanchored side effect before its first removed-type " +
                                    "operation in $file: $ownerName#$signature"
                            }
                            val beforeHash = astSha256(member)
                            member.setBody(defaultBody(member.type))
                            pending += PendingRewrite(
                                type, ownerName, signature, bodyTypes, beforeHash,
                                astSha256(member), "default-return-body"
                            )
                        }
                    }
                }
                is ConstructorDeclaration -> {
                    val signature = constructorSignature(member)
                    val signatureTypes = member.parameters.flatMap {
                        mentionedRemovedTypes(it, removedTypes)
                    }.toSet()
                    if (signatureTypes.isNotEmpty()) {
                        val beforeHash = astSha256(member)
                        member.remove()
                        pending += PendingRewrite(
                            type, ownerName, signature, signatureTypes, beforeHash, null,
                            "removed-signature-member"
                        )
                    } else {
                        val dropped = member.body.statements.filter {
                            mentionedRemovedTypes(it, removedTypes).isNotEmpty()
                        }
                        if (dropped.isNotEmpty()) {
                            val mentioned = dropped.flatMap {
                                mentionedRemovedTypes(it, removedTypes)
                            }.toSet()
                            val beforeHash = astSha256(member)
                            dropped.forEach { it.remove() }
                            pending += PendingRewrite(
                                type, ownerName, signature, mentioned, beforeHash,
                                astSha256(member), "removed-constructor-statements"
                            )
                        }
                    }
                }
                is InitializerDeclaration -> {
                    val mentioned = mentionedRemovedTypes(member.body, removedTypes)
                    if (mentioned.isNotEmpty()) {
                        require(member.body.statements.all {
                            mentionedRemovedTypes(it, removedTypes).isNotEmpty()
                        }) {
                            "Initializer mixes removed dependency work with unrelated side effects " +
                                "in $file: $ownerName"
                        }
                        val beforeHash = astSha256(member)
                        member.setBody(BlockStmt())
                        pending += PendingRewrite(
                            type, ownerName, "initializer", mentioned, beforeHash,
                            astSha256(member), "empty-initializer"
                        )
                    }
                }
                is TypeDeclaration<*> ->
                    strictStubType(member, removedTypes, file, pending)
                else -> Unit
            }
        }
    }

    private fun rejectShadowedRemovedNames(
        compilationUnit: com.github.javaparser.ast.CompilationUnit,
        names: Set<String>,
        file: Path
    ) {
        val shadow = compilationUnit.findAll(SimpleName::class.java).firstOrNull { name ->
            if (name.identifier !in names) return@firstOrNull false
            when (name.parentNode.orElse(null)) {
                is ImportDeclaration -> false
                is TypeDeclaration<*>,
                is TypeParameter,
                is VariableDeclarator,
                is Parameter,
                is MethodDeclaration -> true
                else -> false
            }
        }
        require(shadow == null) {
            "Removed dependency simple name is shadowed in $file: ${shadow?.identifier}"
        }
    }

    private fun mentionedRemovedTypes(
        node: Node,
        removedTypes: Map<String, String>
    ): Set<String> =
        node.findAll(SimpleName::class.java)
            .mapNotNull { removedTypes[it.identifier] }
            .toSet()

    private fun ownerName(type: TypeDeclaration<*>): String {
        val names = generateSequence(type as Node?) {
            it.parentNode.orElse(null)
        }.filterIsInstance<TypeDeclaration<*>>()
            .map { it.nameAsString }
            .toList()
            .asReversed()
        val packageName = type.findCompilationUnit()
            .flatMap { it.packageDeclaration }
            .map { it.nameAsString }
            .orElse("")
        return (listOf(packageName) + names).filter(String::isNotBlank).joinToString(".")
    }

    private fun methodSignature(method: MethodDeclaration): String =
        "${method.type} ${method.nameAsString}(" +
            method.parameters.joinToString(",") { it.type.toString() } + ")"

    private fun constructorSignature(constructor: ConstructorDeclaration): String =
        "${constructor.nameAsString}(" +
            constructor.parameters.joinToString(",") { it.type.toString() } + ")"

    private fun astSha256(node: Node): String =
        buildReceiptSha256(
            "${node.metaModel.typeName}\n${node}".toByteArray(Charsets.UTF_8)
        )

    private fun stubType(type: TypeDeclaration<*>, removedNames: Set<String>, touched: MutableList<String>) {
        if (removedNames.isEmpty()) return
        type.members.toList().forEach { member ->
            when (member) {
                is FieldDeclaration -> if (mentions(member.commonType, removedNames)) {
                    touched += "field ${member.variables.joinToString { it.nameAsString }} removed"
                    member.remove()
                }
                is MethodDeclaration -> {
                    val signatureMentions = mentions(member.type, removedNames) ||
                        member.parameters.any { mentions(it, removedNames) } ||
                        member.typeParameters.any { mentions(it, removedNames) }
                    if (signatureMentions) {
                        touched += "method ${member.nameAsString} removed"
                        member.remove()
                    } else if (member.body.map { mentions(it, removedNames) }.orElse(false)) {
                        member.setBody(defaultBody(member.type))
                        touched += "method ${member.nameAsString} made inert"
                    }
                }
                is ConstructorDeclaration -> {
                    if (member.parameters.any { mentions(it, removedNames) }) {
                        touched += "constructor removed"
                        member.remove()
                    } else {
                        val dropped = member.body.statements.filter { mentions(it, removedNames) }
                        if (dropped.isNotEmpty()) {
                            dropped.forEach { it.remove() }
                            touched += "constructor statements removed"
                        }
                    }
                }
                is InitializerDeclaration -> if (mentions(member.body, removedNames)) {
                    member.setBody(BlockStmt())
                    touched += "initializer made inert"
                }
                is TypeDeclaration<*> -> if (mentions(member, removedNames)) stubType(member, removedNames, touched)
                else -> Unit
            }
        }
    }

    private fun mentions(node: Node, names: Set<String>): Boolean =
        node.findAll(SimpleName::class.java).any { it.identifier in names }

    private fun defaultBody(returnType: Type): BlockStmt {
        val body = BlockStmt()
        val value: Expression? = when {
            returnType.isVoidType -> null
            returnType is PrimitiveType && returnType.type == PrimitiveType.Primitive.BOOLEAN -> BooleanLiteralExpr(false)
            returnType is PrimitiveType -> IntegerLiteralExpr("0")
            else -> NullLiteralExpr()
        }
        if (value != null) body.addStatement(ReturnStmt(value))
        return body
    }
}
