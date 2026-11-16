package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forge 1.20.1's `AbstractProjectileDispenseBehavior.getProjectile(Level, Position, ItemStack)` took no
 * direction. 1.21.1's replacement, `ProjectileItem.asProjectile(Level, Position, ItemStack, Direction)`,
 * takes the Direction the dispenser faces: `ProjectileDispenseBehavior.execute` passes the dispenser's
 * `FACING`, the same direction its dispense position is offset along (javap of the 1.21.1 jar).
 *
 * [LegacyMixinMemberMoves] rewrites a call of a legacy `getProjectile` invoker when a Direction is in
 * scope. A call inside an override of a project method that has none gets one threaded in: that
 * project method family gains a trailing `Direction` parameter, and each of its callers passes the
 * nearest Direction of the one `Vec3` its projectile position arguments are offset along
 * (`Direction.getNearest(v)`, the facing a vanilla dispenser would pass for that offset). A family
 * whose callers have no such vector, or that is called through other receivers, is left alone.
 */
internal object LegacyProjectileDispenseDirections {
    const val RULE_ID = "struct-projectile-dispense-direction"

    private const val DIRECTION = "net.minecraft.core.Direction"
    private const val VEC3 = "net.minecraft.world.phys.Vec3"
    private val behaviors = setOf(
        "net.minecraft.core.dispenser.AbstractProjectileDispenseBehavior",
        "net.minecraft.core.dispenser.ProjectileDispenseBehavior"
    )

    private val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))

    private class Unit(val path: Path, val source: String, val cu: CompilationUnit) {
        private val starts: IntArray = buildList {
            add(0)
            source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
        }.toIntArray()

        fun at(position: Position): Int = starts[position.line - 1] + position.column - 1
        fun end(node: Node): Int = at(node.range.get().end) + 1

        fun imported(simple: String): String? =
            cu.imports.firstOrNull { !it.isStatic && !it.isAsterisk && it.nameAsString.substringAfterLast('.') == simple }?.nameAsString

        fun qualified(type: Type): String {
            val spelled = type.asString().substringBefore('<')
            if (spelled.contains('.') && spelled.first().isLowerCase()) return spelled
            val head = spelled.substringBefore('.')
            return imported(head)?.let { it + spelled.removePrefix(head) }
                ?: cu.packageDeclaration.map { "${it.nameAsString}.$spelled" }.orElse(spelled)
        }

        fun qualified(simple: String): String =
            imported(simple.substringBefore('.'))?.let { it + simple.removePrefix(simple.substringBefore('.')) }
                ?: cu.packageDeclaration.map { "${it.nameAsString}.$simple" }.orElse(simple)
    }

    private class Edit(val start: Int, val end: Int, val text: String)

    fun migrate(pass: StructuralRefactorPass, projectDir: Path, dryRun: Boolean): List<Change> {
        val javaRoot = GradleModLayout.modModule(projectDir).resolve("src/main/java")
        if (!javaRoot.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(javaRoot).use { paths ->
            paths.filter { it.extension == "java" && it.isRegularFile() }.sorted().toList()
        }
        val candidates = files.filter { it.readText().contains("getProjectile") }
        if (candidates.isEmpty()) return emptyList()
        if (!TargetMembers.methodDescriptors(DIRECTION, "getNearest").contains("(L${VEC3.replace('.', '/')};)L${DIRECTION.replace('.', '/')};")) {
            return emptyList()
        }
        val units = candidates.mapNotNull { path ->
            val source = path.readText()
            parser.parse(source).takeIf { it.isSuccessful }?.result?.orElse(null)?.let { Unit(path, source, it) }
        }
        val invokers = units.flatMap(::projectileInvokers).toSet()
        if (invokers.isEmpty()) return emptyList()

        val edits = mutableMapOf<Path, MutableList<Edit>>()
        val done = mutableSetOf<String>()
        for (unit in units) {
            for (call in unit.cu.findAll(MethodCallExpr::class.java)) {
                if (call.nameAsString !in invokers || call.arguments.size != 3) continue
                val override = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: continue
                if (directionInScope(unit, override)) continue
                val family = family(units, unit, override) ?: continue
                if (!done.add(family.key)) continue
                val familyEdits = familyEdits(units, family) ?: continue
                familyEdits.forEach { (path, list) -> edits.getOrPut(path) { mutableListOf() } += list }
            }
        }
        val changes = mutableListOf<Change>()
        for ((path, list) in edits) {
            val unit = units.first { it.path == path }
            val after = list.sortedByDescending { it.start }.fold(unit.source) { text, edit -> text.replaceRange(edit.start, edit.end, edit.text) }
            if (after == unit.source) continue
            if (!dryRun) path.writeText(after)
            changes += Change(
                path, 1,
                "Thread the dispensing Direction 1.21.1 ProjectileItem.asProjectile needs through the project's projectile factory",
                "getProjectile(..) without a direction", "getProjectile(.., Direction) fed Direction.getNearest(facing)",
                Confidence.HIGH, RULE_ID
            )
        }
        return changes
    }

    /** Names of `@Invoker` methods for a dispense behavior's legacy `getProjectile(Level, Position, ItemStack)`. */
    private fun projectileInvokers(unit: Unit): List<String> =
        unit.cu.findAll(ClassOrInterfaceDeclaration::class.java).flatMap { type ->
            val mixin = type.annotations.firstOrNull { it.nameAsString == "Mixin" || it.nameAsString.endsWith(".Mixin") }
            val targets = when (mixin) {
                is SingleMemberAnnotationExpr -> listOf(mixin.memberValue)
                is NormalAnnotationExpr -> mixin.pairs.filter { it.nameAsString == "value" }.map { it.value }
                else -> emptyList()
            }.mapNotNull { (it as? ClassExpr)?.type?.let(unit::qualified) }
            if (targets.none { it in behaviors }) return@flatMap emptyList()
            type.methods.filter { method ->
                val invoker = method.annotations.firstOrNull { it.nameAsString == "Invoker" || it.nameAsString.endsWith(".Invoker") }
                    ?: return@filter false
                val named = when (invoker) {
                    is SingleMemberAnnotationExpr -> (invoker.memberValue as? StringLiteralExpr)?.value
                    is NormalAnnotationExpr -> (invoker.pairs.firstOrNull { it.nameAsString == "value" }?.value as? StringLiteralExpr)?.value
                    else -> null
                } ?: method.nameAsString.substringAfterLast('$').let {
                    Regex("^(?:call|invoke)([A-Z].*)$").find(it)?.groupValues?.get(1)?.replaceFirstChar { c -> c.lowercase() }
                }
                named == "getProjectile" && method.parameters.size == 3
            }.map { it.nameAsString }
        }

    private fun isDirection(unit: Unit, type: Type): Boolean = unit.qualified(type) == DIRECTION

    private fun directionInScope(unit: Unit, method: MethodDeclaration): Boolean =
        method.parameters.any { isDirection(unit, it.type) } ||
            method.findAll(VariableDeclarator::class.java).any { isDirection(unit, it.type) }

    private class Family(
        val key: String,
        val owner: Unit,
        val name: String,
        val parameterTypes: List<String>,
        val declarations: List<Pair<Unit, MethodDeclaration>>
    )

    private fun parameterTypes(method: MethodDeclaration): List<String> = method.parameters.map { it.type.asString() }

    /** The project method [override] overrides (in its anonymous or named superclass), with every override. */
    private fun family(units: List<Unit>, unit: Unit, override: MethodDeclaration): Family? {
        val superName = when (val owner = override.parentNode.orElse(null)) {
            is ObjectCreationExpr -> owner.type.nameWithScope
            is ClassOrInterfaceDeclaration -> owner.extendedTypes.singleOrNull()?.nameWithScope
            else -> null
        } ?: return null
        val superQualified = unit.qualified(superName)
        val superType = units.firstNotNullOfOrNull { candidate ->
            candidate.cu.findAll(ClassOrInterfaceDeclaration::class.java)
                .firstOrNull { qualifiedName(candidate, it) == superQualified }
                ?.let { candidate to it }
        } ?: return null
        val (ownerUnit, ownerType) = superType
        val types = parameterTypes(override)
        val declared = ownerType.methods.singleOrNull { it.nameAsString == override.nameAsString && parameterTypes(it) == types } ?: return null
        val declarations = mutableListOf(ownerUnit to declared)
        for (candidate in units) {
            candidate.cu.findAll(MethodDeclaration::class.java)
                .filter { it !== declared && it.nameAsString == declared.nameAsString && parameterTypes(it) == types }
                .forEach { method ->
                    val parent = method.parentNode.orElse(null)
                    val extends = when (parent) {
                        is ObjectCreationExpr -> parent.type.nameWithScope
                        is ClassOrInterfaceDeclaration -> parent.extendedTypes.singleOrNull()?.nameWithScope
                        else -> null
                    }
                    if (extends != null && candidate.qualified(extends) == superQualified) declarations += candidate to method
                }
        }
        return Family("$superQualified#${declared.nameAsString}$types", ownerUnit, declared.nameAsString, types, declarations)
    }

    private fun qualifiedName(unit: Unit, type: ClassOrInterfaceDeclaration): String {
        val names = generateSequence(type as Node?) { it.parentNode.orElse(null) }
            .filterIsInstance<ClassOrInterfaceDeclaration>().map { it.nameAsString }.toList().asReversed()
        return unit.cu.packageDeclaration.map { "${it.nameAsString}." }.orElse("") + names.joinToString(".")
    }

    /** Parameter and argument edits for [family], or null when a caller has no single facing vector. */
    private fun familyEdits(units: List<Unit>, family: Family): Map<Path, List<Edit>>? {
        val edits = mutableMapOf<Path, MutableList<Edit>>()
        for ((unit, method) in family.declarations) {
            val name = if (method.findAll(NameExpr::class.java).any { it.nameAsString == "direction" } ||
                method.parameters.any { it.nameAsString == "direction" }) "modporterDirection" else "direction"
            val last = method.parameters.lastOrNull() ?: return null
            val at = unit.end(last)
            edits.getOrPut(unit.path) { mutableListOf() } += Edit(at, at, ", $DIRECTION $name")
        }
        // the classes declaring the family: its owner and the (anonymous) subclasses overriding it
        val familyTypes: Set<Node> = family.declarations.mapNotNull { it.second.parentNode.orElse(null) }.toSet()
        for (unit in units) {
            for (call in unit.cu.findAll(MethodCallExpr::class.java)) {
                if (call.nameAsString != family.name || call.arguments.size != family.parameterTypes.size) continue
                val scope = call.scope.orElse(null)
                if (scope != null && scope !is ThisExpr) {
                    // a call through another receiver may reach the family: its type is not proven here
                    return null
                }
                val enclosingType = generateSequence(call.parentNode.orElse(null)) { it.parentNode.orElse(null) }
                    .firstOrNull { it is ClassOrInterfaceDeclaration || (it is ObjectCreationExpr && it.anonymousClassBody.isPresent) }
                if (enclosingType !in familyTypes) continue
                val caller = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
                val facing = facingVector(unit, caller, call) ?: return null
                val at = unit.end(call.arguments.last())
                edits.getOrPut(unit.path) { mutableListOf() } += Edit(at, at, ", $DIRECTION.getNearest($facing)")
            }
        }
        return edits
    }

    /** The single Vec3 parameter or local of [caller] that [call]'s arguments are computed from (through locals of [caller]). */
    private fun facingVector(unit: Unit, caller: MethodDeclaration, call: MethodCallExpr): String? {
        val vectors = (caller.parameters.filter { unit.qualified(it.type) == VEC3 }.map { it.nameAsString } +
            caller.findAll(VariableDeclarator::class.java).filter { unit.qualified(it.type) == VEC3 }.map { it.nameAsString }).toSet()
        if (vectors.isEmpty()) return null
        val locals = caller.findAll(VariableDeclarator::class.java).associateBy { it.nameAsString }
        val reached = mutableSetOf<String>()
        fun visit(expression: Expression, depth: Int) {
            expression.findAll(NameExpr::class.java).forEach { name ->
                val identifier = name.nameAsString
                if (identifier in vectors) reached += identifier
                else if (depth < 2) locals[identifier]?.initializer?.orElse(null)?.let { visit(it, depth + 1) }
            }
        }
        call.arguments.forEach { visit(it, 0) }
        return reached.singleOrNull()
    }
}
