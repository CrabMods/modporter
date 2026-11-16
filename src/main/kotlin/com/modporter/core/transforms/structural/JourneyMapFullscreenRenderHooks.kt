package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.LiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * JourneyMap 5 (Forge 1.20.1) had no fullscreen render hook in its client API, so mods mixed into its
 * internal `journeymap.client.ui.fullscreen.Fullscreen` and injected after `render` calls `drawMap`,
 * reading the map center from the private `gridRenderer` and `isScrolling`/`getMouseDrag()`.
 * JourneyMap 6 (NeoForge 1.21.1) renamed those internals and publishes the hook in its v2 API
 * (javap of journeymap-1.21.1-6.0.0-beta.54 and journeymap-api-neoforge-2.0.0):
 * - `Fullscreen.render` posts `FullscreenRenderEvent(this, graphics, mouseX, mouseY, partialTicks)`
 *   right after `drawMap`, through `FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT`;
 * - `IFullscreen.getCenterBlockX/Z(true)` is `center - (dragging ? mouseDrag.x/y : 0)` and
 *   `(false)` is the center alone, the values the legacy hooks computed;
 * - `IFullscreen.getScreen()` is the Fullscreen screen itself.
 *
 * A mixin admitted here holds only shadows of those members and such injections, each a run of
 * locals and static calls to project methods. The calls move to a generated v2 client plugin
 * (`@JourneyMapPlugin`) that subscribes to the render event, with the arguments rewritten to the
 * event's values. A project method's `Fullscreen` parameter that receives the screen becomes
 * `IFullscreen`, reading `Screen` members through `getScreen()`; the mixin and its config entries
 * are removed. A mixin into the legacy Fullscreen that does anything else is reported, not rewritten.
 */
internal object JourneyMapFullscreenRenderHooks {
    const val RULE_ID = "struct-journeymap-fullscreen-render-event"

    private const val LEGACY_FULLSCREEN = "journeymap.client.ui.fullscreen.Fullscreen"
    private const val FULLSCREEN_API = "journeymap.api.v2.client.fullscreen.IFullscreen"
    private const val SCREEN = "net.minecraft.client.gui.screens.Screen"
    private const val RENDER = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"
    private const val DRAW_MAP = "Ljourneymap/client/ui/fullscreen/Fullscreen;drawMap(Lnet/minecraft/client/gui/GuiGraphics;II)V"
    private const val PLUGIN = "ModPorterJourneyMapPlugin"

    // JourneyMap 5 Fullscreen members the legacy hooks read
    private const val GRID_RENDERER = "gridRenderer"
    private const val SCROLLING = "isScrolling"
    private const val MOUSE_DRAG = "getMouseDrag"

    // IFullscreen: every v1 method is kept in v2, which adds getMouseDrag and getCenterBlockX/Z
    private val fullscreenMethods = setOf(
        "updateMapType", "toggleMapType", "zoomIn", "zoomOut", "centerOn", "close",
        "getUiState", "getMinecraft", "getScreen", "getMouseDrag", "getCenterBlockX", "getCenterBlockZ"
    )

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    /** A legacy injection's static call, with its arguments as v2 expressions over `event` and `fullscreen`. */
    private class Call(val owner: String, val method: String, val arguments: List<String>, val screenArguments: Set<Int>)

    private class Hook(val mixin: String, val file: Path, val calls: List<Call>)

    private class Rejected(message: String) : RuntimeException(message)

    private fun reject(message: String): Nothing = throw Rejected(message)

    private val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))

    fun migrate(pass: StructuralRefactorPass, projectDir: Path, dryRun: Boolean): Result {
        val moduleDir = GradleModLayout.modModule(projectDir)
        val javaRoot = moduleDir.resolve("src/main/java")
        if (!javaRoot.isDirectory()) return Result.EMPTY
        val candidates = JavaSourceText.walkJavaSourceRoots(javaRoot).use { paths ->
            paths.filter { it.extension == "java" && it.isRegularFile() }
                .filter { path -> path.readText().let { it.contains("@Mixin") && it.contains("journeymap.client.ui.fullscreen") } }
                .sorted()
                .toList()
        }
        if (candidates.isEmpty()) return Result.EMPTY

        val hooks = mutableListOf<Hook>()
        val gates = mutableListOf<String>()
        for (file in candidates) {
            val unit = parse(file.readText()) ?: continue
            for (type in unit.types.filterIsInstance<ClassOrInterfaceDeclaration>()) {
                if (!targetsLegacyFullscreen(unit, type)) continue
                val name = unit.packageDeclaration.map { "${it.nameAsString}." }.orElse("") + type.nameAsString
                try {
                    hooks += Hook(name, file, admit(unit, type, javaRoot))
                } catch (rejected: Rejected) {
                    gates += "JourneyMap 5 Fullscreen mixin $name has no JourneyMap v2 equivalent: ${rejected.message}"
                }
            }
        }
        if (hooks.isEmpty()) return Result(emptyList(), gates)

        return try {
            Result(apply(pass, projectDir, moduleDir, javaRoot, hooks, dryRun), gates)
        } catch (rejected: Rejected) {
            Result(emptyList(), gates + "JourneyMap 5 Fullscreen render hooks were not migrated: ${rejected.message}")
        }
    }

    private fun parse(source: String): CompilationUnit? =
        parser.parse(source).takeIf { it.isSuccessful }?.result?.orElse(null)

    private fun imported(unit: CompilationUnit, simpleName: String): String? =
        unit.imports.firstOrNull { !it.isStatic && !it.isAsterisk && it.nameAsString.substringAfterLast('.') == simpleName }?.nameAsString

    /** The binary-free qualified name a type spelled in [unit] refers to, when the spelling proves it. */
    private fun qualified(unit: CompilationUnit, spelled: String): String? = when {
        '.' in spelled && spelled.first().isLowerCase() -> spelled
        else -> {
            val head = spelled.substringBefore('.')
            imported(unit, head)?.let { it + spelled.removePrefix(head) }
        }
    }

    private fun isLegacyFullscreen(unit: CompilationUnit, spelled: String): Boolean = qualified(unit, spelled) == LEGACY_FULLSCREEN

    private fun annotation(node: com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<*>, name: String): AnnotationExpr? =
        node.annotations.firstOrNull { it.nameAsString == name || it.nameAsString.endsWith(".$name") }

    private fun mixinTargets(type: ClassOrInterfaceDeclaration): List<Expression> {
        val mixin = annotation(type, "Mixin") ?: return emptyList()
        val values: List<Expression> = when (mixin) {
            is SingleMemberAnnotationExpr -> listOf(mixin.memberValue)
            is NormalAnnotationExpr -> mixin.pairs.filter { it.nameAsString == "value" || it.nameAsString == "targets" }.map { it.value }
            else -> emptyList()
        }
        return values.flatMap { if (it is ArrayInitializerExpr) it.values else listOf(it) }
    }

    private fun targetsLegacyFullscreen(unit: CompilationUnit, type: ClassOrInterfaceDeclaration): Boolean =
        mixinTargets(type).any { value ->
            (value is ClassExpr && isLegacyFullscreen(unit, value.type.asString())) ||
                (value is StringLiteralExpr && value.value.replace('/', '.') == LEGACY_FULLSCREEN)
        }

    private fun admit(unit: CompilationUnit, type: ClassOrInterfaceDeclaration, javaRoot: Path): List<Call> {
        if (mixinTargets(type).size != 1) reject("it targets more than the Fullscreen screen")
        val shadows = mutableSetOf<String>()
        val injections = mutableListOf<MethodDeclaration>()
        for (member in type.members) {
            when {
                member is FieldDeclaration && annotation(member, "Shadow") != null -> member.variables.forEach { variable ->
                    if (variable.nameAsString !in setOf(GRID_RENDERER, SCROLLING)) reject("it shadows ${variable.nameAsString}")
                    shadows += variable.nameAsString
                }
                member is MethodDeclaration && annotation(member, "Shadow") != null -> {
                    if (member.nameAsString != MOUSE_DRAG || member.parameters.isNotEmpty()) reject("it shadows ${member.nameAsString}")
                    shadows += MOUSE_DRAG
                }
                member is MethodDeclaration && annotation(member, "Inject") != null && !member.isStatic -> injections += member
                else -> reject("it declares ${describe(member)}")
            }
        }
        val calls = injections.flatMap { injection(unit, it, shadows, javaRoot) }
        if (calls.isEmpty()) reject("it injects nothing")
        return calls
    }

    private fun describe(member: BodyDeclaration<*>): String = when (member) {
        is MethodDeclaration -> "method ${member.nameAsString}"
        is FieldDeclaration -> "field ${member.variables.joinToString { it.nameAsString }}"
        else -> member.javaClass.simpleName
    }

    private fun stringValue(expression: Expression): String? = when (expression) {
        is StringLiteralExpr -> expression.value
        is ArrayInitializerExpr -> expression.values.singleOrNull()?.let(::stringValue)
        else -> null
    }

    private fun injection(unit: CompilationUnit, method: MethodDeclaration, shadows: Set<String>, javaRoot: Path): List<Call> {
        val inject = annotation(method, "Inject") as? NormalAnnotationExpr ?: reject("an @Inject without attributes")
        val attributes = inject.pairs.associate { it.nameAsString to it.value }
        if (attributes.keys.any { it !in setOf("method", "at", "remap") }) reject("@Inject ${method.nameAsString} uses ${attributes.keys - setOf("method", "at", "remap")}")
        val target = attributes["method"]?.let(::stringValue)
        if (target != RENDER && target != "render") reject("@Inject ${method.nameAsString} targets $target")
        val at = attributes["at"].let { it as? NormalAnnotationExpr ?: (it as? ArrayInitializerExpr)?.values?.singleOrNull() as? NormalAnnotationExpr }
            ?: reject("@Inject ${method.nameAsString} has no single @At")
        val point = at.pairs.associate { it.nameAsString to it.value }
        if (point.keys.any { it !in setOf("value", "target", "shift", "remap") } ||
            point["value"]?.let(::stringValue) != "INVOKE" ||
            point["target"]?.let(::stringValue) != DRAW_MAP ||
            point["shift"]?.toString()?.substringAfterLast('.') != "AFTER"
        ) reject("@Inject ${method.nameAsString} is not after drawMap in render")

        val parameters = method.parameters.map { it.type.asString().substringAfterLast('.') }
        if (parameters != listOf("GuiGraphics", "int", "int", "float", "CallbackInfo")) reject("@Inject ${method.nameAsString} has parameters $parameters")
        val names = method.parameters.map { it.nameAsString }
        val values = mutableMapOf<String, Value>(
            names[0] to Value.Code("event.getGraphics()"),
            names[1] to Value.Code("event.getMouseX()"),
            names[2] to Value.Code("event.getMouseY()"),
            names[3] to Value.Code("event.getPartialTicks()")
        )
        val body = method.body.orElse(null) ?: reject("@Inject ${method.nameAsString} has no body")
        val calls = mutableListOf<Call>()
        for (statement in body.statements) {
            val expression = (statement as? ExpressionStmt)?.expression ?: reject("${method.nameAsString} runs ${statement.javaClass.simpleName}")
            when (expression) {
                is VariableDeclarationExpr -> expression.variables.forEach { variable ->
                    val initializer = variable.initializer.orElse(null) ?: reject("${method.nameAsString} declares ${variable.nameAsString} without a value")
                    values[variable.nameAsString] = evaluate(initializer, values, shadows) ?: reject("${method.nameAsString} computes $initializer")
                }
                is MethodCallExpr -> calls += staticCall(unit, expression, values, shadows, javaRoot)
                else -> reject("${method.nameAsString} runs $expression")
            }
        }
        if (calls.isEmpty()) reject("${method.nameAsString} calls nothing")
        return calls
    }

    private sealed interface Value {
        data class Code(val text: String) : Value
        object Screen : Value
        object Dragging : Value
        object Drag : Value
        data class DragAxis(val axis: Char) : Value
        data class Center(val axis: Char) : Value
    }

    private fun unwrap(expression: Expression): Expression =
        if (expression is EnclosedExpr) unwrap(expression.inner) else expression

    private fun isZero(expression: Expression): Boolean = when (val value = unwrap(expression)) {
        is IntegerLiteralExpr -> value.asNumber().toLong() == 0L
        is DoubleLiteralExpr -> value.asDouble() == 0.0
        else -> false
    }

    private fun evaluate(expression: Expression, values: Map<String, Value>, shadows: Set<String>): Value? {
        val node = unwrap(expression)
        return when {
            node is NameExpr -> values[node.nameAsString] ?: if (node.nameAsString == SCROLLING && SCROLLING in shadows) Value.Dragging else null
            node is FieldAccessExpr && node.scope is ThisExpr && node.nameAsString == SCROLLING && SCROLLING in shadows -> Value.Dragging
            node is FieldAccessExpr -> when (evaluate(node.scope, values, shadows)) {
                Value.Drag -> if (node.nameAsString == "x" || node.nameAsString == "y") Value.DragAxis(node.nameAsString.single()) else null
                else -> null
            }
            node is MethodCallExpr && node.arguments.isEmpty() && node.nameAsString == MOUSE_DRAG && MOUSE_DRAG in shadows &&
                node.scope.map { it is ThisExpr }.orElse(true) -> Value.Drag
            node is MethodCallExpr && node.arguments.isEmpty() && GRID_RENDERER in shadows &&
                node.scope.map { unwrap(it).let { scope -> scope is NameExpr && scope.nameAsString == GRID_RENDERER } }.orElse(false) -> when (node.nameAsString) {
                "getCenterBlockX" -> Value.Center('x')
                "getCenterBlockZ" -> Value.Center('y')
                else -> null
            }
            // (Fullscreen) (Object) this
            node is CastExpr && unwrap(node.expression).let { inner ->
                inner is ThisExpr || (inner is CastExpr && inner.type.asString() in setOf("Object", "java.lang.Object") && unwrap(inner.expression) is ThisExpr)
            } -> Value.Screen
            node is BinaryExpr && node.operator == BinaryExpr.Operator.MINUS -> {
                val center = evaluate(node.left, values, shadows) as? Value.Center ?: return null
                val drag = unwrap(node.right) as? ConditionalExpr ?: return null
                if (evaluate(drag.condition, values, shadows) != Value.Dragging || !isZero(drag.elseExpr)) return null
                if (evaluate(drag.thenExpr, values, shadows) != Value.DragAxis(center.axis)) return null
                Value.Code("fullscreen.getCenterBlock${if (center.axis == 'x') "X" else "Z"}(true)")
            }
            node is LiteralExpr -> Value.Code(node.toString())
            else -> null
        }
    }

    private fun argumentCode(value: Value?): String? = when (value) {
        is Value.Code -> value.text
        Value.Screen -> "fullscreen"
        is Value.Center -> "fullscreen.getCenterBlock${if (value.axis == 'x') "X" else "Z"}(false)"
        else -> null
    }

    private fun staticCall(unit: CompilationUnit, call: MethodCallExpr, values: Map<String, Value>, shadows: Set<String>, javaRoot: Path): Call {
        val scope = call.scope.orElse(null)?.toString() ?: reject("${call.nameAsString}(..) is not a static call on a project class")
        val owner = qualified(unit, scope)
            ?: unit.packageDeclaration.map { "${it.nameAsString}.$scope" }.orElse(null)?.takeIf { sourceFile(javaRoot, it) != null }
            ?: reject("$scope is not a resolvable project class")
        if (sourceFile(javaRoot, owner) == null) reject("$owner is not a project class")
        val arguments = call.arguments.map { argument -> argumentCode(evaluate(argument, values, shadows)) ?: reject("the argument $argument has no JourneyMap v2 value") }
        val screens = call.arguments.indices.filter { evaluate(call.arguments[it], values, shadows) == Value.Screen }.toSet()
        return Call(owner, call.nameAsString, arguments, screens)
    }

    /** The source file declaring [qualifiedName] (a top-level type or a type nested in one) under [javaRoot]. */
    private fun sourceFile(javaRoot: Path, qualifiedName: String): Path? {
        val parts = qualifiedName.split('.')
        for (end in parts.size downTo 1) {
            if (!parts[end - 1].first().isUpperCase()) break
            val file = javaRoot.resolve(parts.take(end).joinToString("/") + ".java")
            if (file.isRegularFile()) return file
        }
        return null
    }

    private fun typeIn(unit: CompilationUnit, qualifiedName: String): TypeDeclaration<*>? {
        val packagePrefix = unit.packageDeclaration.map { "${it.nameAsString}." }.orElse("")
        val path = qualifiedName.removePrefix(packagePrefix).split('.')
        var types: List<TypeDeclaration<*>> = unit.types
        var found: TypeDeclaration<*>? = null
        for (name in path) {
            found = types.firstOrNull { it.nameAsString == name } ?: return null
            types = found.members.filterIsInstance<TypeDeclaration<*>>()
        }
        return found
    }

    private class Edit(val start: Int, val end: Int, val text: String)

    private fun offsets(source: String): IntArray {
        val starts = mutableListOf(0)
        source.forEachIndexed { index, character -> if (character == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun IntArray.at(position: Position): Int = this[position.line - 1] + position.column - 1

    private fun screenHierarchyHasField(name: String): Boolean {
        val seen = HashSet<String>()
        val frontier = ArrayDeque(listOf(SCREEN))
        while (frontier.isNotEmpty()) {
            val owner = frontier.removeFirst()
            if (!seen.add(owner)) continue
            if (TargetMembers.hasField(owner, name)) return true
            frontier.addAll(TargetMembers.supertypesOf(owner))
        }
        return false
    }

    /**
     * Rewrites [source] (declaring [owner]) so the `Fullscreen` parameters that receive the screen
     * are `IFullscreen`, following the screen into the owner's own methods.
     */
    private fun widenScreenParameters(source: String, owner: String, calls: List<Call>): String {
        val unit = parse(source) ?: reject("$owner does not parse")
        val type = typeIn(unit, owner) ?: reject("$owner is not declared in its source file")
        val lines = offsets(source)
        val edits = mutableListOf<Edit>()
        val done = mutableSetOf<Pair<MethodDeclaration, Int>>()
        val pending = ArrayDeque<Pair<MethodDeclaration, Int>>()
        for (call in calls) {
            val method = type.getMethodsByName(call.method).filter { it.isStatic && it.parameters.size == call.arguments.size }.singleOrNull()
                ?: reject("$owner.${call.method} with ${call.arguments.size} parameters is not one static method")
            call.screenArguments.forEach { pending += method to it }
        }
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!done.add(next)) continue
            val (method, index) = next
            val parameter = method.parameters[index]
            if (!isLegacyFullscreen(unit, parameter.type.asString())) reject("$owner.${method.nameAsString} takes ${parameter.type} for the screen")
            if (annotation(method, "Override") != null) reject("$owner.${method.nameAsString} overrides a method")
            val name = parameter.nameAsString
            val body = method.body.orElse(null) ?: reject("$owner.${method.nameAsString} has no body")
            val shadowed = body.findAll(com.github.javaparser.ast.body.VariableDeclarator::class.java).any { it.nameAsString == name } ||
                body.findAll(LambdaExpr::class.java).any { lambda -> lambda.parameters.any { it.nameAsString == name } }
            if (shadowed) reject("$owner.${method.nameAsString} redeclares $name")
            edits += Edit(lines.at(parameter.type.range.get().begin), lines.at(parameter.type.range.get().end) + 1, "IFullscreen")
            for (use in body.findAll(NameExpr::class.java).filter { it.nameAsString == name }) {
                val parent = use.parentNode.orElse(null)
                val after = lines.at(use.range.get().end) + 1
                when {
                    parent is MethodCallExpr && parent.scope.orElse(null) === use -> when {
                        parent.nameAsString in fullscreenMethods -> Unit
                        TargetMembers.declaresInHierarchy(SCREEN, parent.nameAsString, parent.arguments.size) -> edits += Edit(after, after, ".getScreen()")
                        else -> reject("$owner.${method.nameAsString} calls $name.${parent.nameAsString}")
                    }
                    parent is FieldAccessExpr && parent.scope === use -> {
                        if (!screenHierarchyHasField(parent.nameAsString)) reject("$owner.${method.nameAsString} reads $name.${parent.nameAsString}")
                        edits += Edit(after, after, ".getScreen()")
                    }
                    parent is MethodCallExpr && parent.arguments.any { it === use } -> {
                        val scope = parent.scope.orElse(null)
                        if (scope != null && scope !is ThisExpr && scope.toString() != type.nameAsString) reject("$owner.${method.nameAsString} hands $name to $parent")
                        val position = parent.arguments.indexOfFirst { it === use }
                        val callee = type.getMethodsByName(parent.nameAsString).filter { it.parameters.size == parent.arguments.size }.singleOrNull()
                            ?: reject("$owner.${method.nameAsString} hands $name to ${parent.nameAsString}, which is not one method of $owner")
                        val calleeType = callee.parameters[position].type.asString()
                        when {
                            isLegacyFullscreen(unit, calleeType) -> pending += callee to position
                            qualified(unit, calleeType) == SCREEN || calleeType == SCREEN -> edits += Edit(after, after, ".getScreen()")
                            else -> reject("$owner.${callee.nameAsString} takes $calleeType for the screen")
                        }
                    }
                    parent is BinaryExpr && (parent.operator == BinaryExpr.Operator.EQUALS || parent.operator == BinaryExpr.Operator.NOT_EQUALS) &&
                        listOf(parent.left, parent.right).any { it.isNullLiteralExpr } -> Unit
                    else -> reject("$owner.${method.nameAsString} uses $name as ${parent?.javaClass?.simpleName}")
                }
            }
        }
        var result = edits.sortedByDescending { it.start }.fold(source) { text, edit -> text.replaceRange(edit.start, edit.end, edit.text) }
        result = if (imported(unit, "IFullscreen").let { it == null || it == FULLSCREEN_API }) {
            JavaSourceText.addImport(result, FULLSCREEN_API)
        } else {
            result.replace(Regex("""(?<![\w$.])IFullscreen(?=\s+[A-Za-z_$])"""), FULLSCREEN_API)
        }
        // the legacy class stays importable from JourneyMap 6; the import goes only when unused
        val masked = JavaSourceText.maskCommentsAndLiterals(result).replace(Regex("""(?m)^import\s+[^;]+;"""), "")
        if (!Regex("""(?<![\w$.])Fullscreen(?![\w$])""").containsMatchIn(masked)) result = JavaSourceText.removeImport(result, LEGACY_FULLSCREEN)
        return result
    }

    private fun apply(pass: StructuralRefactorPass, projectDir: Path, moduleDir: Path, javaRoot: Path, hooks: List<Hook>, dryRun: Boolean): List<Change> {
        val modId = with(pass) { detectModId(projectDir) } ?: reject("the project's mod id is not unique, so the v2 plugin cannot name it")
        val writes = linkedMapOf<Path, Pair<String, String>>()
        val calls = hooks.flatMap { it.calls }
        for ((owner, ownerCalls) in calls.groupBy { it.owner }) {
            val file = sourceFile(javaRoot, owner)!!
            val before = writes[file]?.second ?: file.readText()
            val after = widenScreenParameters(before, owner, ownerCalls)
            writes[file] = (writes[file]?.first ?: file.readText()) to after
        }

        val pluginPackage = calls.map { it.owner }.minOrNull()!!.let { owner ->
            val file = sourceFile(javaRoot, owner)!!
            javaRoot.relativize(file.parent).toString().replace('\\', '/').replace('/', '.')
        }
        val pluginFile = javaRoot.resolve(pluginPackage.replace('.', '/')).resolve("$PLUGIN.java")
        if (pluginFile.exists()) reject("$pluginPackage.$PLUGIN already exists")
        writes[pluginFile] = "" to pluginSource(pluginPackage, modId, hooks)

        // mixin configs listing the hooks
        try {
            writes.putAll(MixinConfigEntries.without(moduleDir, hooks.map { it.mixin }.toSet()))
        } catch (unlisted: MixinConfigEntries.Unlisted) {
            reject(unlisted.message ?: "the mixin configs do not list the hooks")
        }

        val changes = mutableListOf<Change>()
        for ((file, contents) in writes) {
            val (before, after) = contents
            if (!dryRun) {
                file.parent.createDirectories()
                file.writeText(after)
            }
            changes += Change(
                file, 1,
                if (file == pluginFile) "Subscribe the JourneyMap 5 Fullscreen render hooks to the JourneyMap v2 fullscreen render event"
                else "Follow the JourneyMap 5 Fullscreen render hooks to JourneyMap v2",
                before.take(180), after.take(180), Confidence.HIGH, RULE_ID
            )
        }
        for (hook in hooks) {
            if (!dryRun) Files.delete(hook.file)
            changes += Change(hook.file, 1, "Remove the JourneyMap 5 Fullscreen mixin ${hook.mixin}; JourneyMap v2 posts its hook point as an event",
                "@Mixin(Fullscreen.class)", "(removed)", Confidence.HIGH, RULE_ID)
        }
        return changes
    }

    private fun pluginSource(packageName: String, modId: String, hooks: List<Hook>): String {
        val body = hooks.flatMap { hook ->
            listOf("        // after Fullscreen.drawMap, as ${hook.mixin} did on JourneyMap 5") +
                hook.calls.map { call -> "        ${call.owner}.${call.method}(${call.arguments.joinToString(", ")});" }
        }
        return (listOf(
            "package $packageName;",
            "",
            "import journeymap.api.v2.client.IClientAPI;",
            "import journeymap.api.v2.client.IClientPlugin;",
            "import journeymap.api.v2.client.JourneyMapPlugin;",
            "import journeymap.api.v2.client.event.FullscreenRenderEvent;",
            "import journeymap.api.v2.client.fullscreen.IFullscreen;",
            "import journeymap.api.v2.common.event.FullscreenEventRegistry;",
            "",
            "/** JourneyMap v2 client plugin running the fullscreen map overlays that JourneyMap 5 needed mixins for. */",
            "@JourneyMapPlugin(apiVersion = \"2.0.0\")",
            "public class $PLUGIN implements IClientPlugin {",
            "    @Override",
            "    public void initialize(IClientAPI api) {",
            "        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(\"$modId\", $PLUGIN::afterFullscreenMap);",
            "    }",
            "",
            "    @Override",
            "    public String getModId() {",
            "        return \"$modId\";",
            "    }",
            "",
            "    /** JourneyMap 6 posts this right after Fullscreen.render draws the map. */",
            "    private static void afterFullscreenMap(FullscreenRenderEvent event) {",
            "        IFullscreen fullscreen = event.getFullscreen();"
        ) + body + listOf(
            "    }",
            "}",
            ""
        )).joinToString("\n")
    }
}
