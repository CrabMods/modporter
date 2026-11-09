package com.modporter.core.transforms.build

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path

/**
 * Finds the source-closed Holder<SoundEvent> producer behind a legacy ArmorMaterial sound
 * supplier. A proved project producer receives a holder accessor which returns its existing
 * Holder/DeferredHolder object without dereferencing it.
 *
 * The resulting expressions are Supplier<Holder<SoundEvent>> expressions. They are deliberately
 * per enum constant: an unknown producer is omitted and retains the legacy renderer's fallback.
 */
internal object LegacyArmorMaterialSoundHolderGraph {
    data class Plan(
        val holderSuppliers: Map<String, String>,
        val rewrittenSources: Map<Path, String>
    )

    private data class EnumSoundSlot(
        val declaration: EnumDeclaration,
        val argumentIndex: Int
    )

    private data class ProducerField(
        val ownerName: String,
        val fieldName: String
    )

    private data class SourceType(
        val path: Path,
        val source: String,
        val unit: CompilationUnit,
        val declaration: ClassOrInterfaceDeclaration
    )

    private data class Insertion(val offset: Int, val text: String)

    private val parser = JavaParser(
        ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    fun resolve(
        enumSource: String,
        projectSources: Map<Path, String>
    ): Plan {
        val enumUnit = parse(enumSource) ?: return Plan(emptyMap(), emptyMap())
        if (!enumUnit.exactImport("Supplier", "java.util.function.Supplier") ||
            !enumUnit.exactImport("SoundEvent", "net.minecraft.sounds.SoundEvent") ||
            enumUnit.declaresAny("Supplier", "SoundEvent", "SoundEvents")
        ) {
            return Plan(emptyMap(), emptyMap())
        }
        val slot = enumSoundSlot(enumUnit) ?: return Plan(emptyMap(), emptyMap())
        val currentSources = projectSources.toMutableMap()
        val suppliers = linkedMapOf<String, String>()

        for (constant in slot.declaration.entries) {
            if (constant.classBody.any {
                    it is MethodDeclaration &&
                        it.nameAsString == "getEquipSound"
                }
            ) {
                continue
            }
            val argument = constant.arguments.getOrNull(slot.argumentIndex) ?: continue
            val value = lambdaValue(argument) ?: continue
            val native = nativeHolderSupplier(enumUnit, value)
            if (native != null) {
                suppliers[constant.nameAsString] = native
                continue
            }

            val producer = projectProducer(enumUnit, value) ?: continue
            val ownerCanonical = enumUnit.qualifyType(producer.ownerName) ?: continue
            val sourceType = findSourceType(ownerCanonical, currentSources) ?: continue
            val rewritten = addHolderAccessors(
                sourceType,
                producer.fieldName,
                currentSources
            ) ?: continue
            currentSources[sourceType.path] = rewritten
            suppliers[constant.nameAsString] =
                "() -> ${value.scope.orElseThrow()}.getMainEventHolder()"
        }

        return Plan(
            holderSuppliers = suppliers,
            rewrittenSources = currentSources.filter { (path, source) ->
                projectSources[path] != source
            }
        )
    }

    private fun enumSoundSlot(unit: CompilationUnit): EnumSoundSlot? {
        val declaration = unit.types.singleOrNull() as? EnumDeclaration ?: return null
        if (declaration.implementedTypes.none {
                it.nameAsString == "ArmorMaterial" ||
                    it.nameWithScope == "net.minecraft.world.item.ArmorMaterial"
            }
        ) {
            return null
        }

        val getter = declaration.members.filterIsInstance<MethodDeclaration>()
            .singleOrNull {
                it.nameAsString == "getEquipSound" &&
                    it.parameters.isEmpty() &&
                    it.typeAsString == "SoundEvent"
            } ?: return null
        val soundField = getter.returnedSupplierField() ?: return null
        val field = declaration.fields
            .flatMap { it.variables }
            .singleOrNull { it.nameAsString == soundField } ?: return null
        if (field.type.normalized() != "Supplier<SoundEvent>") return null

        val constructor = declaration.members.filterIsInstance<ConstructorDeclaration>()
            .singleOrNull() ?: return null
        val parameter = constructor.assignedParameter(soundField) ?: return null
        val index = constructor.parameters.indexOfFirst { it.nameAsString == parameter }
        if (index < 0) return null
        return EnumSoundSlot(declaration, index)
    }

    private fun MethodDeclaration.returnedSupplierField(): String? {
        val returned = body.orElse(null)
            ?.statements
            ?.singleOrNull() as? ReturnStmt ?: return null
        val call = returned.expression.orElse(null) as? MethodCallExpr ?: return null
        if (call.nameAsString != "get" || call.arguments.isNotEmpty()) return null
        return when (val scope = call.scope.orElse(null)) {
            is NameExpr -> scope.nameAsString
            is FieldAccessExpr ->
                scope.nameAsString.takeIf { scope.scope is ThisExpr }
            else -> null
        }
    }

    private fun ConstructorDeclaration.assignedParameter(fieldName: String): String? {
        val assignments = body.findAll(AssignExpr::class.java).filter { assignment ->
            val target = assignment.target as? FieldAccessExpr ?: return@filter false
            target.scope is ThisExpr && target.nameAsString == fieldName
        }
        val assignment = assignments.singleOrNull() ?: return null
        val value = assignment.value as? NameExpr ?: return null
        return value.nameAsString.takeIf { name ->
            parameters.any { it.nameAsString == name }
        }
    }

    private fun lambdaValue(expression: Expression): MethodCallExpr? {
        val lambda = expression as? LambdaExpr ?: return null
        if (lambda.parameters.isNotEmpty()) return null
        val value = when (val body = lambda.body) {
            is ExpressionStmt -> body.expression
            is BlockStmt -> {
                val returned = body.statements.singleOrNull() as? ReturnStmt
                    ?: return null
                returned.expression.orElse(null)
            }
            else -> null
        }
        return value as? MethodCallExpr
    }

    private fun nativeHolderSupplier(
        unit: CompilationUnit,
        value: MethodCallExpr
    ): String? {
        if (value.nameAsString != "value" || value.arguments.isNotEmpty()) return null
        if (!unit.exactImport("SoundEvents", "net.minecraft.sounds.SoundEvents")) return null
        if (unit.declaresValue("SoundEvents")) return null
        val holder = value.scope.orElse(null) as? FieldAccessExpr ?: return null
        val owner = holder.scope as? NameExpr ?: return null
        if (owner.nameAsString != "SoundEvents") return null
        return "() -> $holder"
    }

    private fun projectProducer(
        unit: CompilationUnit,
        value: MethodCallExpr
    ): ProducerField? {
        if (value.nameAsString != "getMainEvent" || value.arguments.isNotEmpty()) return null
        val field = value.scope.orElse(null) as? FieldAccessExpr ?: return null
        val owner = field.scope.toString()
        if (!owner.contains('.') && unit.declaresValue(owner)) return null
        return ProducerField(owner, field.nameAsString)
    }

    private fun findSourceType(
        canonical: String,
        sources: Map<Path, String>
    ): SourceType? {
        val packageName = canonical.substringBeforeLast('.', "")
        val simpleName = canonical.substringAfterLast('.')
        val matches = sources.mapNotNull { (path, source) ->
            val unit = parse(source) ?: return@mapNotNull null
            if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != packageName) {
                return@mapNotNull null
            }
            val declaration = unit.types.singleOrNull {
                it.nameAsString == simpleName
            } as? ClassOrInterfaceDeclaration ?: return@mapNotNull null
            SourceType(path, source, unit, declaration)
        }
        return matches.singleOrNull()
    }

    private fun addHolderAccessors(
        sourceType: SourceType,
        fieldName: String,
        projectSources: Map<Path, String>
    ): String? {
        if (!sourceType.unit.exactImport(
                "SoundEvent",
                "net.minecraft.sounds.SoundEvent"
            ) ||
            sourceType.unit.declaresAny(
                "Holder",
                "SoundEvent",
                "DeferredHolder"
            )
        ) {
            return null
        }
        val hasNativeHolderImport =
            sourceType.unit.exactImport("Holder", "net.minecraft.core.Holder") ||
                sourceType.unit.exactImport(
                    "DeferredHolder",
                    "net.neoforged.neoforge.registries.DeferredHolder"
                )
        if (!hasNativeHolderImport) return null

        val field = sourceType.declaration.fields
            .filter { it.hasModifier(Modifier.Keyword.STATIC) }
            .flatMap { it.variables }
            .singleOrNull { it.nameAsString == fieldName } ?: return null
        val producerTypeName = field.typeAsString.substringAfterLast('.')
        val producerType = sourceType.declaration.findAll(
            ClassOrInterfaceDeclaration::class.java
        ).singleOrNull { it.nameAsString == producerTypeName } ?: return null
        val main = producerType.methods.singleOrNull {
            it.nameAsString == "getMainEvent" &&
                it.parameters.isEmpty() &&
                it.typeAsString == "SoundEvent"
        } ?: return null
        if (!main.isAbstract) return null

        val allLocalSubclasses = sourceType.declaration.findAll(
            ClassOrInterfaceDeclaration::class.java
        ).filter { declaration ->
            declaration.extendedTypes.any {
                it.nameAsString == producerTypeName
            }
        }
        if (allLocalSubclasses.isEmpty() ||
            allLocalSubclasses.any { it.isAbstract }
        ) {
            return null
        }
        if (hasUnknownSubclasses(
                sourceType.path,
                sourceType.source,
                producerTypeName,
                projectSources
            )
        ) {
            return null
        }
        val concrete = allLocalSubclasses
        if (concrete.isEmpty()) return null

        val insertions = mutableListOf<Insertion>()
        val baseHolderMethods = producerType.methods.filter {
            it.nameAsString == "getMainEventHolder"
        }
        if (baseHolderMethods.isEmpty()) {
            insertions += sourceType.source.insertionBefore(
                main,
                "public abstract Holder<SoundEvent> getMainEventHolder();"
            ) ?: return null
        } else if (baseHolderMethods.size != 1 ||
            !baseHolderMethods.single().isAbstract ||
            baseHolderMethods.single().parameters.isNotEmpty() ||
            baseHolderMethods.single().type.normalized() !=
            "Holder<SoundEvent>"
        ) {
            return null
        }
        for (implementation in concrete) {
            val getMainEvent = implementation.methods.singleOrNull {
                it.nameAsString == "getMainEvent" &&
                    it.parameters.isEmpty() &&
                    it.typeAsString == "SoundEvent"
            } ?: return null
            val holder = holderExpression(implementation, getMainEvent) ?: return null
            val holderMethods = implementation.methods.filter {
                it.nameAsString == "getMainEventHolder"
            }
            if (holderMethods.isNotEmpty()) {
                val existing = holderMethods.singleOrNull() ?: return null
                if (existing.parameters.isNotEmpty() ||
                    existing.type.normalized() != "Holder<SoundEvent>" ||
                    existing.returnedExpression() != holder
                ) {
                    return null
                }
                continue
            }
            insertions += sourceType.source.insertionBefore(
                getMainEvent,
                """
                    @Override
                    public Holder<SoundEvent> getMainEventHolder() {
                        return $holder;
                    }
                """.trimIndent()
            ) ?: return null
        }

        var rewritten = sourceType.source
        for (insertion in insertions.sortedByDescending(Insertion::offset)) {
            rewritten = rewritten.substring(0, insertion.offset) +
                insertion.text +
                rewritten.substring(insertion.offset)
        }
        return JavaSourceText.addImport(rewritten, "net.minecraft.core.Holder")
    }

    private fun hasUnknownSubclasses(
        ownerPath: Path,
        ownerSource: String,
        producerTypeName: String,
        projectSources: Map<Path, String>
    ): Boolean {
        val escaped = Regex.escape(producerTypeName)
        val subclass = Regex(
            """\b(?:extends|new)\s+(?:[A-Za-z_$][\w$]*\s*\.\s*)*$escaped\b"""
        )
        for ((path, source) in projectSources) {
            if (path == ownerPath) continue
            if (subclass.containsMatchIn(
                    JavaSourceText.maskCommentsAndLiterals(source)
                )
            ) {
                return true
            }
        }
        val ownerMasked = JavaSourceText.maskCommentsAndLiterals(ownerSource)
        if (Regex(
                """\bnew\s+(?:[A-Za-z_$][\w$]*\s*\.\s*)*$escaped\b"""
            ).containsMatchIn(ownerMasked)
        ) {
            return true
        }
        return false
    }

    private fun holderExpression(
        implementation: ClassOrInterfaceDeclaration,
        method: MethodDeclaration
    ): String? {
        val returned = method.body.orElse(null)
            ?.statements
            ?.singleOrNull() as? ReturnStmt ?: return null
        val dereference = returned.expression.orElse(null) as? MethodCallExpr ?: return null
        if (dereference.nameAsString != "get" ||
            dereference.arguments.isNotEmpty()
        ) {
            return null
        }
        val holder = dereference.scope.orElse(null) ?: return null
        return holder.toString().takeIf {
            implementation.provesHolder(holder)
        }
    }

    private fun MethodDeclaration.returnedExpression(): String? {
        val returned = body.orElse(null)
            ?.statements
            ?.singleOrNull() as? ReturnStmt ?: return null
        return returned.expression.orElse(null)?.toString()
    }

    private fun ClassOrInterfaceDeclaration.provesHolder(expression: Expression): Boolean {
        val directName = when (expression) {
            is NameExpr -> expression.nameAsString
            is FieldAccessExpr ->
                expression.nameAsString.takeIf { expression.scope is ThisExpr }
            else -> null
        }
        if (directName != null) {
            return fields.flatMap { it.variables }.any {
                it.nameAsString == directName && it.type.isSoundHolder()
            }
        }

        val accessor = expression as? MethodCallExpr ?: return false
        if (accessor.arguments.isNotEmpty()) return false
        val record = findAll(RecordDeclaration::class.java).singleOrNull { declaration ->
            declaration.parameters.any {
                it.nameAsString == accessor.nameAsString &&
                    it.type.isSoundHolder()
            }
        } ?: return false
        val scope = accessor.scope.orElse(null) ?: return false
        val sourceNames = scope.findAll(NameExpr::class.java)
            .map { it.nameAsString }
            .toSet()
        return fields.flatMap { it.variables }.any {
            it.nameAsString in sourceNames &&
                it.typeAsString.contains(record.nameAsString)
        }
    }

    private fun com.github.javaparser.ast.type.Type.isSoundHolder(): Boolean {
        val normalized = normalized()
        return normalized == "Holder<SoundEvent>" ||
            normalized == "DeferredHolder<SoundEvent,SoundEvent>"
    }

    private fun String.insertionBefore(node: Node, body: String): Insertion? {
        val positions = buildList {
            node.range.orElse(null)?.begin?.let(::add)
            if (node is MethodDeclaration) {
                node.annotations.mapNotNull {
                    it.range.orElse(null)?.begin
                }.forEach(::add)
            }
        }
        val begin = positions.minWithOrNull(
            compareBy<Position> { it.line }.thenBy { it.column }
        ) ?: return null
        val offset = offset(begin)
        if (offset !in indices) return null
        val lineStart = lastIndexOf('\n', offset - 1) + 1
        val indent = substring(lineStart, offset)
            .takeWhile { it == ' ' || it == '\t' }
        val newline = if (contains("\r\n")) "\r\n" else "\n"
        val rendered = body.lineSequence()
            .joinToString(newline) { line ->
                if (line.isEmpty()) "" else indent + line
            }
        return Insertion(lineStart, rendered + newline + newline)
    }

    private fun String.offset(position: Position): Int {
        var line = 1
        var offset = 0
        while (line < position.line) {
            val next = indexOf('\n', offset)
            if (next < 0) return -1
            offset = next + 1
            line++
        }
        return offset + position.column - 1
    }

    private fun CompilationUnit.exactImport(
        simpleName: String,
        canonical: String
    ): Boolean = imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == simpleName
    }.singleOrNull()?.nameAsString == canonical

    private fun CompilationUnit.declaresAny(vararg names: String): Boolean =
        findAll(TypeDeclaration::class.java).any {
            it.nameAsString in names
        }

    private fun CompilationUnit.declaresValue(name: String): Boolean =
        findAll(VariableDeclarator::class.java).any {
            it.nameAsString == name
        } ||
            findAll(Parameter::class.java).any {
                it.nameAsString == name
            }

    private fun CompilationUnit.qualifyType(name: String): String? {
        if (name.contains('.')) return name
        val imported = imports.filter {
            !it.isStatic &&
                !it.isAsterisk &&
                it.nameAsString.substringAfterLast('.') == name
        }.singleOrNull()?.nameAsString
        if (imported != null) return imported
        val packageName = packageDeclaration.map { it.nameAsString }.orElse("")
        return if (packageName.isEmpty()) name else "$packageName.$name"
    }

    private fun com.github.javaparser.ast.type.Type.normalized(): String =
        toString().replace(Regex("""\s+"""), "")

    private fun parse(source: String): CompilationUnit? =
        parser.parse(source).result.orElse(null)
}
