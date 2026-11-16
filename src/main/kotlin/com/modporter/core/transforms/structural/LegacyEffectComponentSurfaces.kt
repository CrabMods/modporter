package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.TextBlockLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.SwitchEntry
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.ast.type.WildcardType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forge 1.20.1 call surfaces whose NeoForge 1.21.1 replacement is decided by the receiver's
 * platform type (proven with the source typing) and by the target member that exists in 1.21.1
 * (proven with the target class table), never by the declaring mod:
 * - `FluidStack.removeChildTag(key)`: the Forge tag is the `CUSTOM_DATA` component (as for the
 *   other FluidStack tag accessors in [FluidStackTags]); a copy without the key is stored back, and
 *   a stack without custom data stays untouched, like the old null-tag no-op;
 * - `LivingEntity.curePotionEffects(stack)` with a provable milk bucket stack (inline or an unshared
 *   local) is `removeEffectsCuredBy(EffectCures.MILK)`; other curative stacks have no exact cure;
 * - `PotionContents.getAllEffects()` (an Iterable in 1.21.1) where a List/Collection is required is
 *   the fresh, ordered, mutable ArrayList that `PotionUtils.getMobEffects` returned;
 * - `ItemStack.hideTooltipPart(MODIFIERS | ENCHANTMENTS | ADDITIONAL)` sets the tooltip flag of the
 *   matching component; the other parts have no component-independent equivalent and stay;
 * - `ItemStack.isEdible()` is `has(DataComponents.FOOD)` unless a project item overrides
 *   `isEdible()`, whose dynamic dispatch the component check would drop;
 * - Catnip removed `NBTProcessors.textComponentHasClickEvent(String)` (javap: Ponder-Forge 1.20.1
 *   1.0.91 declares it, ponder-neoforge 1.0.87+mc1.21.1 keeps only the Component overload); a
 *   String argument keeps the strict 1.20.1 JSON click-event audit as private static helpers of
 *   the calling top-level class, without decoding rich text against an empty registry;
 * - the provider-less JSON write that stood in for 1.20.1 `Component.Serializer.toJson(component)`
 *   becomes `toJson(component, provider)` once the enclosing method declares a provider (this pass
 *   runs after the provider threading and the project families that consume the provider-less
 *   shape);
 * - 1.20.1 `Component.Serializer.fromJson`/`fromJsonLenient` returned null for blank input and JSON
 *   `null` and parsed strings with a strict (resp. lenient) reader; the provider overloads keep
 *   that contract through private helpers of the calling top-level class (javap: Forge 1.20.1
 *   `GsonHelper.fromNullableJson`, NeoForge 21.1 `JsonParser.parseString` + `deserialize`).
 */
internal object LegacyEffectComponentSurfaces {
    const val RULE_ID = "struct-legacy-effect-component-surfaces"

    private const val FLUID_STACK = "net.neoforged.neoforge.fluids.FluidStack"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val ITEM = "net.minecraft.world.item.Item"
    private const val ITEMS = "net.minecraft.world.item.Items"
    private const val LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity"
    private const val MOB_EFFECT_INSTANCE = "net.minecraft.world.effect.MobEffectInstance"
    private const val DATA_COMPONENTS = "net.minecraft.core.component.DataComponents"
    private const val DATA_COMPONENT_HOLDER = "net.minecraft.core.component.DataComponentHolder"
    private const val POTION_CONTENTS = "net.minecraft.world.item.alchemy.PotionContents"
    private const val CUSTOM_DATA = "net.minecraft.world.item.component.CustomData"
    private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    private const val ITEM_ATTRIBUTE_MODIFIERS =
        "net.minecraft.world.item.component.ItemAttributeModifiers"
    private const val ITEM_ENCHANTMENTS = "net.minecraft.world.item.enchantment.ItemEnchantments"
    private const val FOOD_PROPERTIES = "net.minecraft.world.food.FoodProperties"
    private const val UNIT = "net.minecraft.util.Unit"
    private const val EFFECT_CURES = "net.neoforged.neoforge.common.EffectCures"
    private const val TOOLTIP_PART = "net.minecraft.world.item.ItemStack.TooltipPart"
    private const val NBT_PROCESSORS = "net.createmod.catnip.nbt.NBTProcessors"
    private const val STRING = "java.lang.String"
    private const val COMPONENT_TYPE = "Lnet/minecraft/core/component/DataComponentType;"
    private const val MAX_ROUNDS = 8

    private const val CLICK_HELPER = "modporter\$legacyTextComponentHasClickEvent"
    private const val COMPONENT_NODE = "modporter\$LegacyTextComponent"
    private const val DECODE_HELPER = "modporter\$decodeLegacyTextComponent"
    private const val TOUCH_HELPER = "modporter\$touchLegacyTextComponent"
    private const val OBJECT_HELPER = "modporter\$legacyJsonObject"
    private const val ARRAY_HELPER = "modporter\$legacyJsonArray"
    private const val STRING_HELPER = "modporter\$legacyJsonString"
    private const val OPTIONAL_STRING_HELPER = "modporter\$legacyOptionalJsonString"
    private const val BOOLEAN_HELPER = "modporter\$legacyJsonBoolean"
    private const val INT_HELPER = "modporter\$legacyJsonInt"
    private const val RESOURCE_HELPER = "modporter\$validateLegacyResource"
    private const val STYLE_HELPER = "modporter\$validateLegacyStyle"
    private const val HOVER_HELPER = "modporter\$validateLegacyHover"
    private const val HOVER_CONTENTS_HELPER = "modporter\$validateLegacyHoverContents"
    private const val COMPONENT = "net.minecraft.network.chat.Component"
    private const val COMPONENT_SERIALIZER = "net.minecraft.network.chat.Component.Serializer"
    private const val COMPONENT_SERIALIZER_BINARY = "net.minecraft.network.chat.Component\$Serializer"
    private const val COMPONENT_SERIALIZATION = "net.minecraft.network.chat.ComponentSerialization"
    private const val JSON_OPS = "com.mojang.serialization.JsonOps"
    private const val HOLDER_PROVIDER_DESCRIPTOR = "Lnet/minecraft/core/HolderLookup\$Provider;"
    private const val LEGACY_JSON = "modporter\$legacyComponentJson"
    private const val LEGACY_JSON_FAILURE = "modporter\$legacyComponentJsonFailure"
    private val componentJsonHelperMembers = setOf(LEGACY_JSON, LEGACY_JSON_FAILURE)
    private val reservedHelperMembers = setOf(
        CLICK_HELPER, COMPONENT_NODE, DECODE_HELPER, TOUCH_HELPER, OBJECT_HELPER, ARRAY_HELPER,
        STRING_HELPER, OPTIONAL_STRING_HELPER, BOOLEAN_HELPER, INT_HELPER, RESOURCE_HELPER,
        STYLE_HELPER, HOVER_HELPER, HOVER_CONTENTS_HELPER
    )

    private enum class Family(val trigger: String, val before: String, val after: String) {
        FLUID_CHILD_TAG(
            ".removeChildTag(",
            "FluidStack.removeChildTag(key)",
            "copy of CUSTOM_DATA without the key, only when custom data exists"
        ),
        MILK_CURE(
            "curePotionEffects(",
            "LivingEntity.curePotionEffects(milk bucket stack)",
            "LivingEntity.removeEffectsCuredBy(EffectCures.MILK)"
        ),
        EFFECT_LIST(
            ".getAllEffects()",
            "PotionContents.getAllEffects() Iterable where a List is required",
            "fresh ordered mutable ArrayList of the same effect instances"
        ),
        TOOLTIP_PART(
            ".hideTooltipPart(",
            "ItemStack.hideTooltipPart(MODIFIERS|ENCHANTMENTS|ADDITIONAL)",
            "component tooltip flag on the stack"
        ),
        FOOD(
            ".isEdible()",
            "ItemStack.isEdible()",
            "ItemStack.has(DataComponents.FOOD)"
        ),
        TEXT_AUDIT(
            "textComponentHasClickEvent(",
            "Catnip NBTProcessors.textComponentHasClickEvent(String)",
            "private strict JSON click-event audit of the calling class"
        ),
        COMPONENT_JSON_WRITE(
            "ComponentSerialization.CODEC.encodeStart(",
            "provider-less JSON write standing in for 1.20.1 Component.Serializer.toJson",
            "Component.Serializer.toJson(component, provider) with the method's provider"
        ),
        COMPONENT_JSON_READ(
            "Serializer.fromJson",
            "1.20.1 Component.Serializer.fromJson/fromJsonLenient (blank or JSON null -> null)",
            "private helpers keeping the 1.20.1 null contract before the 1.21.1 decoder"
        )
    }

    private data class Edit(val start: Int, val end: Int, val text: String)

    private class FilePlan(
        val file: Path,
        val original: String,
        val migrated: String,
        val families: Set<Family>
    )

    private class Parsed(
        val source: String,
        val unit: CompilationUnit,
        val typing: SourceValueTypes
    ) {
        private val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"

        fun offset(node: Node): Int = node.range.map {
            lineStarts[it.begin.line - 1] + it.begin.column - 1
        }.orElse(-1)

        fun endOffset(node: Node): Int = node.range.map {
            lineStarts[it.end.line - 1] + it.end.column
        }.orElse(-1)

        fun text(node: Node): String = source.substring(offset(node), endOffset(node))

        fun indentAt(offset: Int): String {
            val lineStart = source.lastIndexOf('\n', offset - 1) + 1
            return source.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        }

        fun typeOf(expression: Expression, at: Node): String? =
            typing.typeOf(expression.toString(), offset(at))
    }

    /**
     * Simple names for the target types a rewrite introduces; the fully qualified name is used
     * whenever the simple name could already mean something else in the file.
     */
    private class TypeRefs(
        private val pass: StructuralRefactorPass,
        private val parsed: Parsed,
        private val planned: Set<String>
    ) {
        val imports = linkedSetOf<String>()
        private val masked by lazy { with(pass) { maskJavaCommentsAndLiterals(parsed.source) } }

        fun ref(fqn: String): String {
            val simple = fqn.substringAfterLast('.')
            val owner = fqn.substringBeforeLast('.')
            val unit = parsed.unit
            val singles = unit.imports.filter { !it.isStatic && !it.isAsterisk }.map { it.nameAsString }
            if (fqn in singles || fqn in planned || fqn in imports) return simple
            val ownPackage = unit.packageDeclaration.map { it.nameAsString }.orElse("")
            val wildcards = unit.imports.filter { !it.isStatic && it.isAsterisk }.map { it.nameAsString }
            val samePackage = if (ownPackage.isEmpty()) simple else "$ownPackage.$simple"
            val conflict = singles.any { it.substringAfterLast('.') == simple } ||
                unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple } ||
                unit.findAll(TypeParameter::class.java).any { it.nameAsString == simple } ||
                unit.findAll(VariableDeclarator::class.java).any { it.nameAsString == simple } ||
                unit.findAll(Parameter::class.java).any { it.nameAsString == simple } ||
                (samePackage != fqn && samePackage in pass.projectValueFacts.types) ||
                wildcards.any {
                    it != owner && ("$it.$simple" in pass.projectValueFacts.types ||
                        TargetMembers.hasClass("$it.$simple"))
                }
            if (conflict) return fqn
            val visible = owner in wildcards || owner == ownPackage
            if (!visible) {
                // An unimported use of the simple name already resolves to another type.
                if (Regex("""(?<![\w$.])${Regex.escape(simple)}(?![\w$])""").containsMatchIn(masked)) {
                    return fqn
                }
                imports += fqn
            }
            return simple
        }
    }

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): List<Change> {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        val files = pass.walkJavaSourceRoots(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .sorted()
                .toList()
        }
        val candidates = files.mapNotNull { file ->
            val text = file.readText()
            if (Family.entries.any { text.contains(it.trigger) }) file to text else null
        }
        if (candidates.isEmpty()) return emptyList()
        val previousFacts = pass.projectValueFacts
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        try {
            val blocked = buildSet {
                // A project override would no longer be reached through the replacement call.
                if (declaredByProjectSubtype(pass, LIVING_ENTITY, "curePotionEffects", 1)) {
                    add(Family.MILK_CURE)
                }
                if (declaredByProjectSubtype(pass, ITEM, "isEdible", 0)) add(Family.FOOD)
            }
            val parser = JavaParser(
                ParserConfiguration().setLanguageLevel(
                    ParserConfiguration.LanguageLevel.BLEEDING_EDGE
                )
            )
            val plans = candidates.mapNotNull { (file, original) ->
                planFile(pass, parser, file, original, blocked)
            }
            plans.forEach { plan ->
                val reparsed = parser.parse(plan.migrated)
                check(reparsed.isSuccessful) {
                    "Legacy effect/component migration emitted invalid Java in " +
                        "${plan.file}: ${pass.parseProblems(reparsed)}"
                }
            }
            if (!dryRun) plans.forEach { it.file.writeText(it.migrated) }
            return plans.map { plan ->
                Change(
                    file = plan.file,
                    line = 1,
                    description = "Port legacy effect, fluid-tag, tooltip, food and text-audit " +
                        "calls by their proven receiver API",
                    before = plan.families.joinToString("; ") { it.before },
                    after = plan.families.joinToString("; ") { it.after },
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            }
        } finally {
            pass.projectValueFacts = previousFacts
        }
    }

    private fun planFile(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        file: Path,
        original: String,
        blocked: Set<Family>
    ): FilePlan? {
        var text = original
        val applied = linkedSetOf<Family>()
        val imports = linkedSetOf<String>()
        val removable = linkedSetOf<String>()
        for (family in Family.entries) {
            if (family in blocked || family !in verifiedFamilies) continue
            var round = 0
            while (round++ < MAX_ROUNDS && text.contains(family.trigger)) {
                val parsed = parse(pass, parser, text) ?: break
                val refs = TypeRefs(pass, parsed, imports)
                val edits = nonOverlapping(
                    when (family) {
                        Family.FLUID_CHILD_TAG -> fluidChildTagEdits(parsed)
                        Family.MILK_CURE -> milkCureEdits(pass, parsed, refs)
                        Family.EFFECT_LIST -> effectListEdits(pass, parsed)
                        Family.TOOLTIP_PART -> tooltipPartEdits(parsed, refs)
                        Family.FOOD -> foodEdits(parsed, refs)
                        Family.TEXT_AUDIT -> textAuditEdits(pass, parsed)
                        Family.COMPONENT_JSON_WRITE -> componentJsonWriteEdits(pass, parsed, refs)
                        Family.COMPONENT_JSON_READ -> componentJsonReadEdits(parsed)
                    }
                )
                if (edits.isEmpty()) break
                text = edits.sortedByDescending { it.start }.fold(text) { current, edit ->
                    current.replaceRange(edit.start, edit.end, edit.text)
                }
                imports += refs.imports
                removable += when (family) {
                    Family.MILK_CURE -> listOf(ITEM_STACK, ITEMS)
                    Family.TOOLTIP_PART -> listOf(TOOLTIP_PART)
                    Family.TEXT_AUDIT -> listOf(NBT_PROCESSORS)
                    else -> emptyList()
                }
                applied += family
            }
        }
        if (applied.isEmpty()) return null
        imports.forEach { text = with(pass) { addImportIfMissing(text, it) } }
        text = with(pass) { removeUnusedSimpleImports(text, removable.toList()) }
        val loneLineFeed = Regex("(?<!\r)\n")
        if (original.contains("\r\n") && !loneLineFeed.containsMatchIn(original)) {
            text = text.replace(loneLineFeed, "\r\n")
        }
        return FilePlan(file, original, text, applied)
    }

    private fun parse(pass: StructuralRefactorPass, parser: JavaParser, source: String): Parsed? {
        val result = parser.parse(source)
        if (!result.isSuccessful) return null
        val unit = result.result.orElse(null) ?: return null
        return Parsed(source, unit, SourceValueTypes(pass, source))
    }

    private fun nonOverlapping(edits: List<Edit>): List<Edit> {
        var end = -1
        return edits.sortedWith(compareBy<Edit> { it.start }.thenBy { it.end }).filter { edit ->
            (edit.start >= end).also { if (it) end = maxOf(end, edit.end) }
        }
    }

    // ---- FluidStack.removeChildTag ------------------------------------------------------

    private fun fluidChildTagEdits(parsed: Parsed): List<Edit> = buildList {
        for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != "removeChildTag" || call.arguments.size != 1) continue
            val receiver = call.scope.orElse(null) ?: continue
            val key = call.arguments.single()
            // Both are read again inside the replacement block, so they must be plain reads.
            if (!isStableReference(receiver) || !isStableValue(key)) continue
            if (parsed.typeOf(receiver, call) != FLUID_STACK) continue
            val statement = call.parentNode.orElse(null) as? ExpressionStmt ?: continue
            if (statement.expression !== call) continue
            val start = parsed.offset(statement)
            val indent = parsed.indentAt(start)
            val step = if (indent.endsWith("\t")) "\t" else "    "
            val data = uniqueName(parsed.source, "modporterChildData")
            val tag = uniqueName(parsed.source, "modporterChildTag")
            val stack = parsed.text(receiver)
            val component = "$DATA_COMPONENTS.CUSTOM_DATA"
            add(
                Edit(
                    start,
                    parsed.endOffset(statement),
                    listOf(
                        "{",
                        "$indent$step$CUSTOM_DATA $data = $stack.get($component);",
                        "$indent${step}if ($data != null) {",
                        "$indent$step$step$COMPOUND_TAG $tag = $data.copyTag();",
                        "$indent$step$step$tag.remove(${parsed.text(key)});",
                        "$indent$step$step$stack.set($component, $CUSTOM_DATA.of($tag));",
                        "$indent$step}",
                        "$indent}"
                    ).joinToString(parsed.newline)
                )
            )
        }
    }

    // ---- LivingEntity.curePotionEffects(milk) ---------------------------------------------

    private fun milkCureEdits(
        pass: StructuralRefactorPass,
        parsed: Parsed,
        refs: TypeRefs
    ): List<Edit> {
        val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "curePotionEffects" &&
                call.arguments.size == 1 &&
                isLivingEntityReceiver(pass, parsed, call)
        }
        if (calls.isEmpty()) return emptyList()
        val edits = mutableListOf<Edit>()
        fun cure(call: MethodCallExpr): Edit {
            val receiver = call.scope.orElse(null)?.let { parsed.text(it) + "." }.orEmpty()
            return Edit(
                parsed.offset(call),
                parsed.endOffset(call),
                "${receiver}removeEffectsCuredBy(${refs.ref(EFFECT_CURES)}.MILK)"
            )
        }
        val byLocal = linkedMapOf<VariableDeclarator, MutableList<MethodCallExpr>>()
        for (call in calls) {
            when (val argument = unwrapParentheses(call.arguments.single())) {
                is ObjectCreationExpr -> if (isMilkBucketStack(parsed, argument)) edits += cure(call)
                is NameExpr -> localDeclaration(argument)?.let {
                    byLocal.getOrPut(it) { mutableListOf() } += call
                }
                else -> Unit
            }
        }
        for ((local, uses) in byLocal) {
            val removal = unsharedMilkLocalRemoval(parsed, local, uses) ?: continue
            uses.forEach { edits += cure(it) }
            edits += removal
        }
        return edits
    }

    private fun isLivingEntityReceiver(
        pass: StructuralRefactorPass,
        parsed: Parsed,
        call: MethodCallExpr
    ): Boolean {
        val type = when (val scope = call.scope.orElse(null)) {
            null -> implicitReceiverType(call)
            is ThisExpr -> if (scope.typeName.isPresent) null else implicitReceiverType(call)
            else -> parsed.typeOf(scope, call)
        } ?: return false
        return inherits(pass, type, LIVING_ENTITY)
    }

    /** The named class whose instance an unqualified call runs on; null inside anonymous or local classes. */
    private fun implicitReceiverType(node: Node): String? {
        var child: Node = node
        var parent = node.parentNode.orElse(null)
        while (parent != null) {
            if (parent is ObjectCreationExpr && parent.anonymousClassBody.map { body ->
                    body.any { it === child }
                }.orElse(false)
            ) {
                return null
            }
            if (parent is LocalClassDeclarationStmt || parent is LocalRecordDeclarationStmt) return null
            if (parent is TypeDeclaration<*>) return parent.fullyQualifiedName.orElse(null)
            child = parent
            parent = parent.parentNode.orElse(null)
        }
        return null
    }

    private fun isMilkBucketStack(parsed: Parsed, creation: ObjectCreationExpr): Boolean {
        if (creation.scope.isPresent ||
            creation.anonymousClassBody.isPresent ||
            creation.arguments.size != 1 ||
            !parsed.typing.isTypeName(creation.type.nameWithScope, ITEM_STACK)
        ) {
            return false
        }
        val item = unwrapParentheses(creation.arguments.single()) as? FieldAccessExpr ?: return false
        return item.nameAsString == "MILK_BUCKET" &&
            parsed.typing.isTypeName(item.scope.toString(), ITEMS)
    }

    /** The block-local declaration a name refers to; null for fields, parameters and loop/catch/lambda variables. */
    private fun localDeclaration(name: NameExpr): VariableDeclarator? {
        val identifier = name.nameAsString
        var child: Node = name
        var parent = name.parentNode.orElse(null)
        while (parent != null) {
            val statements: List<Statement>? = when (parent) {
                is BlockStmt -> parent.statements
                is SwitchEntry -> parent.statements
                else -> null
            }
            if (statements != null) {
                val index = statements.indexOfFirst { it === child }
                for (previous in index - 1 downTo 0) {
                    val declaration = (statements[previous] as? ExpressionStmt)?.expression
                        as? VariableDeclarationExpr ?: continue
                    declaration.variables.firstOrNull { it.nameAsString == identifier }
                        ?.let { return it }
                }
            }
            when (parent) {
                is ForEachStmt -> if (parent.variable.variables.any { it.nameAsString == identifier }) return null
                is ForStmt -> if (parent.initialization.any { initialization ->
                        initialization is VariableDeclarationExpr &&
                            initialization.variables.any { it.nameAsString == identifier }
                    }
                ) {
                    return null
                }
                is CatchClause -> if (parent.parameter.nameAsString == identifier) return null
                is LambdaExpr -> if (parent.parameters.any { it.nameAsString == identifier }) return null
                is ObjectCreationExpr -> if (parent.anonymousClassBody.isPresent) return null
                is BodyDeclaration<*> -> return null
                else -> Unit
            }
            child = parent
            parent = parent.parentNode.orElse(null)
        }
        return null
    }

    /**
     * Removes `ItemStack x = new ItemStack(Items.MILK_BUCKET);` when every use of `x` is a
     * migrated cure argument; any other use could change the stack the old cure inspected.
     */
    private fun unsharedMilkLocalRemoval(
        parsed: Parsed,
        local: VariableDeclarator,
        uses: List<MethodCallExpr>
    ): Edit? {
        val declaration = local.parentNode.orElse(null) as? VariableDeclarationExpr ?: return null
        val statement = declaration.parentNode.orElse(null) as? ExpressionStmt ?: return null
        val block = statement.parentNode.orElse(null) ?: return null
        if (declaration.variables.size != 1) return null
        val type = local.type
        if (!type.isVarType &&
            !(type is ClassOrInterfaceType && parsed.typing.isTypeName(type.nameWithScope, ITEM_STACK))
        ) {
            return null
        }
        val creation = local.initializer.orElse(null)?.let(::unwrapParentheses)
            as? ObjectCreationExpr ?: return null
        if (!isMilkBucketStack(parsed, creation)) return null
        val name = local.nameAsString
        val shadowed = block.findAll(VariableDeclarator::class.java).any {
            it !== local && it.nameAsString == name
        } ||
            block.findAll(Parameter::class.java).any { it.nameAsString == name } ||
            block.findAll(TypePatternExpr::class.java).any { it.nameAsString == name }
        if (shadowed) return null
        val references = block.findAll(NameExpr::class.java).filter { it.nameAsString == name }
        val arguments = uses.map { unwrapParentheses(it.arguments.single()) }
        if (references.size != arguments.size ||
            references.any { reference -> arguments.none { it === reference } }
        ) {
            return null
        }
        val start = parsed.offset(statement)
        val end = parsed.endOffset(statement)
        val lineStart = parsed.source.lastIndexOf('\n', start - 1) + 1
        val lineEnd = parsed.source.indexOf('\n', end).let { if (it < 0) parsed.source.length else it + 1 }
        return if (parsed.source.substring(lineStart, start).isBlank() &&
            parsed.source.substring(end, lineEnd).isBlank()
        ) {
            Edit(lineStart, lineEnd, "")
        } else {
            Edit(start, end, "")
        }
    }

    // ---- PotionContents.getAllEffects() as List ------------------------------------------

    private fun effectListEdits(pass: StructuralRefactorPass, parsed: Parsed): List<Edit> = buildList {
        for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != "getAllEffects" || call.arguments.isNotEmpty()) continue
            val contents = call.scope.orElse(null) ?: continue
            if (!isPotionContents(pass, parsed, contents, call) || !requiresEffectList(parsed, call)) {
                continue
            }
            add(
                Edit(
                    parsed.offset(call),
                    parsed.endOffset(call),
                    "java.util.stream.StreamSupport.stream(${parsed.text(call)}.spliterator(), false)" +
                        ".collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new))"
                )
            )
        }
    }

    private fun isPotionContents(
        pass: StructuralRefactorPass,
        parsed: Parsed,
        expression: Expression,
        at: Node
    ): Boolean {
        val value = unwrapParentheses(expression)
        if (parsed.typeOf(value, at) == POTION_CONTENTS) return true
        // DataComponentHolder.get(type) / getOrDefault(type, fallback) return the type's value class.
        val access = value as? MethodCallExpr ?: return false
        val arity = when (access.nameAsString) {
            "get" -> 1
            "getOrDefault" -> 2
            else -> return false
        }
        if (access.arguments.size != arity) return false
        val component = unwrapParentheses(access.arguments.first()) as? FieldAccessExpr ?: return false
        if (!parsed.typing.isTypeName(component.scope.toString(), DATA_COMPONENTS) ||
            componentValueType(component.nameAsString) != POTION_CONTENTS
        ) {
            return false
        }
        val holder = access.scope.orElse(null) ?: return false
        val holderType = parsed.typeOf(holder, at) ?: return false
        return inherits(pass, holderType, DATA_COMPONENT_HOLDER)
    }

    private fun requiresEffectList(parsed: Parsed, call: MethodCallExpr): Boolean {
        var node: Node = call
        var parent = call.parentNode.orElse(null)
        while (parent is EnclosedExpr) {
            node = parent
            parent = parent.parentNode.orElse(null)
        }
        return when (parent) {
            is ReturnStmt -> {
                var callable = parent.parentNode.orElse(null)
                while (callable != null && callable !is MethodDeclaration && callable !is LambdaExpr &&
                    callable !is BodyDeclaration<*>
                ) {
                    callable = callable.parentNode.orElse(null)
                }
                (callable as? MethodDeclaration)?.let { isEffectListType(parsed, it.type) } == true
            }
            is VariableDeclarator ->
                parent.initializer.orElse(null) === node && isEffectListType(parsed, parent.type)
            is AssignExpr ->
                parent.value === node &&
                    parent.operator == AssignExpr.Operator.ASSIGN &&
                    parsed.typeOf(parent.target, parent) in setOf("java.util.List", "java.util.Collection")
            else -> false
        }
    }

    private fun isEffectListType(parsed: Parsed, type: com.github.javaparser.ast.type.Type): Boolean {
        val declared = type as? ClassOrInterfaceType ?: return false
        if (!parsed.typing.isTypeName(declared.nameWithScope, "java.util.List") &&
            !parsed.typing.isTypeName(declared.nameWithScope, "java.util.Collection")
        ) {
            return false
        }
        val arguments = declared.typeArguments.orElse(null) ?: return true
        val element = arguments.singleOrNull() ?: return false
        return when (element) {
            is ClassOrInterfaceType -> parsed.typing.isTypeName(element.nameWithScope, MOB_EFFECT_INSTANCE)
            is WildcardType -> element.superType.isEmpty &&
                element.extendedType.map { bound ->
                    bound is ClassOrInterfaceType &&
                        parsed.typing.isTypeName(bound.nameWithScope, MOB_EFFECT_INSTANCE)
                }.orElse(true)
            else -> false
        }
    }

    // ---- ItemStack.hideTooltipPart ---------------------------------------------------------

    private fun tooltipPartEdits(parsed: Parsed, refs: TypeRefs): List<Edit> = buildList {
        for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != "hideTooltipPart" || call.arguments.size != 1) continue
            val receiver = call.scope.orElse(null) ?: continue
            val part = unwrapParentheses(call.arguments.single()) as? FieldAccessExpr ?: continue
            if (!parsed.typing.isTypeName(part.scope.toString(), TOOLTIP_PART) ||
                parsed.typeOf(receiver, call) != ITEM_STACK
            ) {
                continue
            }
            val stack = parsed.text(receiver)
            val replacement = when (part.nameAsString) {
                "MODIFIERS" -> if (isStableReference(receiver)) {
                    hiddenComponentTooltip(stack, refs, "ATTRIBUTE_MODIFIERS", ITEM_ATTRIBUTE_MODIFIERS)
                } else null
                "ENCHANTMENTS" -> if (isStableReference(receiver)) {
                    hiddenComponentTooltip(stack, refs, "ENCHANTMENTS", ITEM_ENCHANTMENTS)
                } else null
                "ADDITIONAL" ->
                    "$stack.set(${refs.ref(DATA_COMPONENTS)}.HIDE_ADDITIONAL_TOOLTIP, " +
                        "${refs.ref(UNIT)}.INSTANCE)"
                else -> null
            } ?: continue
            add(Edit(parsed.offset(call), parsed.endOffset(call), replacement))
        }
    }

    private fun hiddenComponentTooltip(
        stack: String,
        refs: TypeRefs,
        component: String,
        valueType: String
    ): String {
        val type = "${refs.ref(DATA_COMPONENTS)}.$component"
        return "$stack.set($type, $stack.getOrDefault($type, ${refs.ref(valueType)}.EMPTY)" +
            ".withTooltip(false))"
    }

    // ---- ItemStack.isEdible ------------------------------------------------------------------

    private fun foodEdits(parsed: Parsed, refs: TypeRefs): List<Edit> = buildList {
        for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != "isEdible" || call.arguments.isNotEmpty()) continue
            val receiver = call.scope.orElse(null) ?: continue
            if (parsed.typeOf(receiver, call) != ITEM_STACK) continue
            add(
                Edit(
                    parsed.offset(call),
                    parsed.endOffset(call),
                    "${parsed.text(receiver)}.has(${refs.ref(DATA_COMPONENTS)}.FOOD)"
                )
            )
        }
    }

    // ---- Catnip NBTProcessors.textComponentHasClickEvent(String) ----------------------------

    private fun textAuditEdits(pass: StructuralRefactorPass, parsed: Parsed): List<Edit> {
        val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "textComponentHasClickEvent" &&
                call.arguments.size == 1 &&
                isCatnipTextAudit(pass, parsed, call) &&
                isProvablyString(parsed, call.arguments.single())
        }
        return helperOwnerEdits(parsed, calls, CLICK_HELPER, reservedHelperMembers, clickAuditHelpers) { call ->
            "$CLICK_HELPER(${parsed.text(call.arguments.single())})"
        }
    }

    /**
     * Replaces [calls] with calls of private static helpers declared once per calling top-level
     * class (a class, record or enum with a body; interfaces cannot hold the private helpers).
     */
    private fun helperOwnerEdits(
        parsed: Parsed,
        calls: List<MethodCallExpr>,
        marker: String,
        reserved: Set<String>,
        helpers: String,
        replacement: (MethodCallExpr) -> String
    ): List<Edit> {
        if (calls.isEmpty()) return emptyList()
        val edits = mutableListOf<Edit>()
        calls.groupBy { topLevelType(it) }.forEach { (owner, ownerCalls) ->
            if (owner == null ||
                owner is AnnotationDeclaration ||
                owner is ClassOrInterfaceDeclaration && owner.isInterface ||
                owner is EnumDeclaration && owner.members.isEmpty()
            ) {
                return@forEach
            }
            val declared = owner.members.mapNotNull { member ->
                when (member) {
                    is MethodDeclaration -> member.nameAsString
                    is TypeDeclaration<*> -> member.nameAsString
                    else -> null
                }
            }.toSet()
            val present = owner.methods.any { it.nameAsString == marker }
            if (!present && declared.any { it in reserved }) return@forEach
            val insertion = if (present) null else (helperInsertion(parsed, owner, helpers) ?: return@forEach)
            ownerCalls.forEach { call ->
                edits += Edit(parsed.offset(call), parsed.endOffset(call), replacement(call))
            }
            insertion?.let(edits::add)
        }
        return edits
    }

    // ---- Component.Serializer JSON --------------------------------------------------------

    /**
     * The 1.20.1 `Component.Serializer.toJson(component)` became a provider-less codec write where no
     * provider was in scope yet. Once the enclosing method declares a `HolderLookup.Provider` (later
     * provider threading), the registry-aware 1.21.1 overload writes the same JSON for everything
     * 1.20.1 could write and also encodes registry-backed contents, which the plain ops reject.
     */
    private fun componentJsonWriteEdits(
        pass: StructuralRefactorPass,
        parsed: Parsed,
        refs: TypeRefs
    ): List<Edit> = buildList {
        for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != "toString" || call.arguments.isNotEmpty()) continue
            val orThrow = call.scope.orElse(null) as? MethodCallExpr ?: continue
            if (orThrow.nameAsString != "getOrThrow" || orThrow.arguments.isNotEmpty()) continue
            val encode = orThrow.scope.orElse(null) as? MethodCallExpr ?: continue
            if (encode.nameAsString != "encodeStart" || encode.arguments.size != 2) continue
            val codec = encode.scope.orElse(null) as? FieldAccessExpr ?: continue
            val ops = encode.arguments.first() as? FieldAccessExpr ?: continue
            if (codec.nameAsString != "CODEC" ||
                !parsed.typing.isTypeName(codec.scope.toString(), COMPONENT_SERIALIZATION) ||
                ops.nameAsString != "INSTANCE" ||
                !parsed.typing.isTypeName(ops.scope.toString(), JSON_OPS)
            ) {
                continue
            }
            val provider = with(pass) {
                holderLookupProviderNamesAt(parsed.source, parsed.offset(call))
            }.singleOrNull() ?: continue
            add(
                Edit(
                    parsed.offset(call),
                    parsed.endOffset(call),
                    "${refs.ref(COMPONENT)}.Serializer.toJson(${parsed.text(encode.arguments[1])}, $provider)"
                )
            )
        }
    }

    /**
     * 1.20.1 `Component.Serializer.fromJson(String)` / `fromJsonLenient(String)` read one value with a
     * Gson reader (strict / lenient) and returned null for an empty or whitespace-only document and
     * for JSON `null`; `fromJson(JsonElement)` returned null for null and JSON `null`. The 1.21.1
     * provider overloads throw for those inputs and parse strings leniently, so every call keeps the
     * 1.20.1 reader and null contract and hands the parsed tree to the 1.21.1 decoder.
     */
    private fun componentJsonReadEdits(parsed: Parsed): List<Edit> {
        val calls = parsed.unit.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString in setOf("fromJson", "fromJsonLenient") &&
                call.arguments.size == 2 &&
                call.scope.map { parsed.typing.isTypeName(it.toString(), COMPONENT_SERIALIZER) }
                    .orElse(false) &&
                (call.arguments[0] as? MethodCallExpr)
                    ?.let { it.nameAsString == LEGACY_JSON && it.scope.isEmpty } != true
        }
        return helperOwnerEdits(
            parsed,
            calls,
            LEGACY_JSON,
            componentJsonHelperMembers,
            componentJsonHelpers
        ) { call ->
            val lenient = call.nameAsString == "fromJsonLenient"
            "${parsed.text(call.scope.get())}.fromJson(" +
                "$LEGACY_JSON(${parsed.text(call.arguments[0])}, $lenient), ${parsed.text(call.arguments[1])})"
        }
    }

    private fun isCatnipTextAudit(
        pass: StructuralRefactorPass,
        parsed: Parsed,
        call: MethodCallExpr
    ): Boolean {
        val scope = call.scope.orElse(null)
        if (scope != null) return parsed.typing.isTypeName(scope.toString(), NBT_PROCESSORS)
        val imported = parsed.unit.imports.any {
            it.isStatic && (
                !it.isAsterisk && it.nameAsString == "$NBT_PROCESSORS.textComponentHasClickEvent" ||
                    it.isAsterisk && it.nameAsString == NBT_PROCESSORS
                )
        }
        return imported &&
            parsed.unit.findAll(MethodDeclaration::class.java).none {
                it.nameAsString == "textComponentHasClickEvent"
            } &&
            pass.projectValueFacts.methods.values.none { it.containsKey("textComponentHasClickEvent/1") }
    }

    /** The argument's static type is String: a typed value, or a platform call whose overloads all return String. */
    private fun isProvablyString(parsed: Parsed, argument: Expression): Boolean {
        val value = unwrapParentheses(argument)
        if (parsed.typing.typeOf(value.toString(), parsed.offset(value)) == STRING) return true
        val call = value as? MethodCallExpr ?: return false
        val receiver = call.scope.orElse(null) ?: return false
        val owner = parsed.typeOf(receiver, call) ?: return false
        if (!TargetMembers.hasClass(owner)) return false
        val returns = platformMethodDescriptors(owner, call.nameAsString, call.arguments.size)
        return returns.isNotEmpty() && returns.all { it.endsWith(")Ljava/lang/String;") }
    }

    private fun platformMethodDescriptors(owner: String, name: String, arity: Int): Set<String> {
        val result = linkedSetOf<String>()
        val seen = HashSet<String>()
        val frontier = ArrayDeque(listOf(owner))
        while (frontier.isNotEmpty()) {
            val type = frontier.removeFirst()
            if (!seen.add(type) || !TargetMembers.hasClass(type)) continue
            TargetMembers.methodDescriptors(type, name)
                .filter { TargetMembers.parameterTypes(it).size == arity }
                .filter { TargetMembers.inheritedMethod(type, name, it)?.access == "public" }
                .forEach(result::add)
            frontier.addAll(TargetMembers.supertypesOf(type))
        }
        return result
    }

    private fun topLevelType(node: Node): TypeDeclaration<*>? {
        var current: Node? = node
        var result: TypeDeclaration<*>? = null
        while (current != null) {
            if (current is TypeDeclaration<*>) result = current
            current = current.parentNode.orElse(null)
        }
        return result
    }

    private fun helperInsertion(parsed: Parsed, owner: TypeDeclaration<*>, template: String): Edit? {
        val close = parsed.endOffset(owner) - 1
        if (close <= 0 || parsed.source[close] != '}') return null
        val ownerIndent = parsed.indentAt(parsed.offset(owner))
        val memberIndent = owner.members.firstOrNull()
            ?.let { parsed.indentAt(parsed.offset(it)) }
            ?.takeIf { it.length > ownerIndent.length }
            ?: (ownerIndent + if (ownerIndent.startsWith("\t")) "\t" else "    ")
        val unit = if (memberIndent.endsWith("\t")) "\t" else "    "
        val lines = template.lines().map { line ->
            if (line.isBlank()) "" else {
                val spaces = line.takeWhile { it == ' ' }.length
                memberIndent + unit.repeat(spaces / 4) + " ".repeat(spaces % 4) + line.substring(spaces)
            }
        }
        val helpers = lines.joinToString(parsed.newline)
        val lineStart = parsed.source.lastIndexOf('\n', close - 1) + 1
        return if (parsed.source.substring(lineStart, close).isBlank()) {
            Edit(lineStart, lineStart, parsed.newline + helpers + parsed.newline)
        } else {
            Edit(close, close, parsed.newline + helpers + parsed.newline + ownerIndent)
        }
    }

    // ---- shared proofs -----------------------------------------------------------------------

    private fun inherits(pass: StructuralRefactorPass, type: String, ancestor: String): Boolean {
        var current = type
        val seen = HashSet<String>()
        while (seen.add(current)) {
            if (current == ancestor) return true
            if (TargetMembers.hasClass(current)) return TargetMembers.inheritsFrom(current, ancestor)
            current = pass.projectValueFacts.parents[current] ?: return false
        }
        return false
    }

    private fun declaredByProjectSubtype(
        pass: StructuralRefactorPass,
        ancestor: String,
        name: String,
        arity: Int
    ): Boolean {
        val facts = pass.projectValueFacts
        val owners = facts.methods.filterValues { it.containsKey("$name/$arity") }.keys +
            if (arity == 0) facts.getters.filterValues { it.containsKey(name) }.keys else emptySet()
        return owners.any { it != ancestor && inherits(pass, it, ancestor) }
    }

    private fun isStableReference(expression: Expression): Boolean = when (expression) {
        is NameExpr -> true
        is ThisExpr -> expression.typeName.isEmpty
        is FieldAccessExpr -> isStableReference(expression.scope)
        is EnclosedExpr -> isStableReference(expression.inner)
        else -> false
    }

    private fun isStableValue(expression: Expression): Boolean =
        expression is StringLiteralExpr || expression is TextBlockLiteralExpr ||
            isStableReference(expression)

    private fun unwrapParentheses(expression: Expression): Expression =
        if (expression is EnclosedExpr) unwrapParentheses(expression.inner) else expression

    private fun uniqueName(source: String, base: String): String {
        var index = 1
        var result = base
        while (Regex("""(?<![\w$])${Regex.escape(result)}(?![\w$])""").containsMatchIn(source)) {
            result = base + ++index
        }
        return result
    }

    /** The value class of a `DataComponents` field, from its 1.21.1 generic signature. */
    private fun componentValueType(field: String): String? {
        if (TargetMembers.fieldDescriptor(DATA_COMPONENTS, field) != COMPONENT_TYPE) return null
        val signature = TargetMembers.fieldSignatures(DATA_COMPONENTS)[field] ?: return null
        val value = Regex("""^Lnet/minecraft/core/component/DataComponentType<L([\w/$]+);>;$""")
            .find(signature)?.groupValues?.get(1) ?: return null
        return value.replace('/', '.').replace('$', '.')
    }

    private fun publicMethod(
        owner: String,
        name: String,
        descriptor: String,
        declaredBy: String? = null
    ): Boolean {
        val method = TargetMembers.inheritedMethod(owner, name, descriptor) ?: return false
        return method.access == "public" && (declaredBy == null || method.owner == declaredBy)
    }

    private val verifiedFamilies: Set<Family> by lazy {
        Family.entries.filter { family ->
            when (family) {
                Family.FLUID_CHILD_TAG ->
                    publicMethod(FLUID_STACK, "get", "(${COMPONENT_TYPE})Ljava/lang/Object;") &&
                        publicMethod(
                            FLUID_STACK, "set",
                            "(${COMPONENT_TYPE}Ljava/lang/Object;)Ljava/lang/Object;", FLUID_STACK
                        ) &&
                        publicMethod(
                            CUSTOM_DATA, "of",
                            "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/item/component/CustomData;",
                            CUSTOM_DATA
                        ) &&
                        publicMethod(CUSTOM_DATA, "copyTag", "()Lnet/minecraft/nbt/CompoundTag;", CUSTOM_DATA) &&
                        publicMethod(COMPOUND_TAG, "remove", "(Ljava/lang/String;)V", COMPOUND_TAG) &&
                        componentValueType("CUSTOM_DATA") == CUSTOM_DATA
                Family.MILK_CURE ->
                    publicMethod(
                        LIVING_ENTITY, "removeEffectsCuredBy",
                        "(Lnet/neoforged/neoforge/common/EffectCure;)Z"
                    ) &&
                        TargetMembers.fieldDescriptor(EFFECT_CURES, "MILK") ==
                        "Lnet/neoforged/neoforge/common/EffectCure;"
                Family.EFFECT_LIST ->
                    TargetMembers.inheritedMethod(POTION_CONTENTS, "getAllEffects", "()Ljava/lang/Iterable;")
                        ?.let {
                            it.owner == POTION_CONTENTS && it.access == "public" &&
                                it.signature ==
                                "()Ljava/lang/Iterable<Lnet/minecraft/world/effect/MobEffectInstance;>;"
                        } == true &&
                        componentValueType("POTION_CONTENTS") == POTION_CONTENTS &&
                        publicMethod(ITEM_STACK, "get", "(${COMPONENT_TYPE})Ljava/lang/Object;") &&
                        publicMethod(
                            ITEM_STACK, "getOrDefault",
                            "(${COMPONENT_TYPE}Ljava/lang/Object;)Ljava/lang/Object;"
                        )
                Family.TOOLTIP_PART ->
                    publicMethod(ITEM_STACK, "set", "(${COMPONENT_TYPE}Ljava/lang/Object;)Ljava/lang/Object;") &&
                        publicMethod(
                            ITEM_STACK, "getOrDefault",
                            "(${COMPONENT_TYPE}Ljava/lang/Object;)Ljava/lang/Object;"
                        ) &&
                        publicMethod(
                            ITEM_ATTRIBUTE_MODIFIERS, "withTooltip",
                            "(Z)L${ITEM_ATTRIBUTE_MODIFIERS.replace('.', '/')};", ITEM_ATTRIBUTE_MODIFIERS
                        ) &&
                        publicMethod(
                            ITEM_ENCHANTMENTS, "withTooltip",
                            "(Z)L${ITEM_ENCHANTMENTS.replace('.', '/')};", ITEM_ENCHANTMENTS
                        ) &&
                        TargetMembers.fieldDescriptor(ITEM_ATTRIBUTE_MODIFIERS, "EMPTY") ==
                        "L${ITEM_ATTRIBUTE_MODIFIERS.replace('.', '/')};" &&
                        TargetMembers.fieldDescriptor(ITEM_ENCHANTMENTS, "EMPTY") ==
                        "L${ITEM_ENCHANTMENTS.replace('.', '/')};" &&
                        TargetMembers.fieldDescriptor(UNIT, "INSTANCE") == "L${UNIT.replace('.', '/')};" &&
                        componentValueType("ATTRIBUTE_MODIFIERS") == ITEM_ATTRIBUTE_MODIFIERS &&
                        componentValueType("ENCHANTMENTS") == ITEM_ENCHANTMENTS &&
                        componentValueType("HIDE_ADDITIONAL_TOOLTIP") == UNIT
                Family.FOOD ->
                    publicMethod(ITEM_STACK, "has", "(${COMPONENT_TYPE})Z") &&
                        componentValueType("FOOD") == FOOD_PROPERTIES
                // The audit helpers use only the JDK and Gson, which the game ships.
                Family.TEXT_AUDIT -> true
                Family.COMPONENT_JSON_WRITE ->
                    TargetMembers.fieldDescriptor(COMPONENT_SERIALIZATION, "CODEC") ==
                        "Lcom/mojang/serialization/Codec;" &&
                        publicMethod(
                            COMPONENT_SERIALIZER_BINARY, "toJson",
                            "(Lnet/minecraft/network/chat/Component;$HOLDER_PROVIDER_DESCRIPTOR)" +
                                "Ljava/lang/String;",
                            COMPONENT_SERIALIZER_BINARY
                        )
                Family.COMPONENT_JSON_READ ->
                    listOf(
                        "(Ljava/lang/String;$HOLDER_PROVIDER_DESCRIPTOR)",
                        "(Lcom/google/gson/JsonElement;$HOLDER_PROVIDER_DESCRIPTOR)"
                    ).all {
                        publicMethod(
                            COMPONENT_SERIALIZER_BINARY, "fromJson",
                            "${it}Lnet/minecraft/network/chat/MutableComponent;",
                            COMPONENT_SERIALIZER_BINARY
                        )
                    } &&
                        publicMethod(
                            COMPONENT_SERIALIZER_BINARY, "fromJsonLenient",
                            "(Ljava/lang/String;$HOLDER_PROVIDER_DESCRIPTOR)" +
                                "Lnet/minecraft/network/chat/MutableComponent;",
                            COMPONENT_SERIALIZER_BINARY
                        )
            }
        }.toSet()
    }

    /** 1.20.1 `Component.Serializer.fromJson` acceptance and click-event walk, without registry decoding. */
    private val clickAuditHelpers = """
private static final class $COMPONENT_NODE {
    private final java.util.List<$COMPONENT_NODE> siblings =
        new java.util.ArrayList<>();
    private boolean click;
}

private static boolean $CLICK_HELPER(String json) {
    String normalized = json.isEmpty() ? "\"\"" : json;
    try {
        com.google.gson.stream.JsonReader reader =
            new com.google.gson.stream.JsonReader(
                new java.io.StringReader(normalized));
        reader.setLenient(false);
        com.google.gson.JsonElement element =
            new com.google.gson.Gson()
                .getAdapter(com.google.gson.JsonElement.class)
                .read(reader);
        return $CLICK_HELPER(
            $DECODE_HELPER(element, true));
    } catch (java.io.IOException exception) {
        throw new com.google.gson.JsonSyntaxException(exception);
    }
}

private static boolean $CLICK_HELPER(
        $COMPONENT_NODE component) {
    for ($COMPONENT_NODE sibling : component.siblings)
        if ($CLICK_HELPER(sibling))
            return true;
    return component.click;
}

private static void $TOUCH_HELPER(
        $COMPONENT_NODE component) {
    for ($COMPONENT_NODE sibling : component.siblings)
        $TOUCH_HELPER(sibling);
}

private static $COMPONENT_NODE $DECODE_HELPER(
        com.google.gson.JsonElement element,
        boolean nullableNull) {
    if (element == null || element.isJsonNull()) {
        if (nullableNull)
            return null;
        throw new com.google.gson.JsonParseException(
            "null is not a text component");
    }
    if (element.isJsonPrimitive())
        return new $COMPONENT_NODE();
    if (element.isJsonArray()) {
        com.google.gson.JsonArray components =
            element.getAsJsonArray();
        $COMPONENT_NODE component = null;
        for (com.google.gson.JsonElement child : components) {
            $COMPONENT_NODE decoded =
                $DECODE_HELPER(child, false);
            if (component == null)
                component = decoded;
            else
                component.siblings.add(decoded);
        }
        return component;
    }
    if (!element.isJsonObject())
        throw new com.google.gson.JsonParseException(
            "unsupported text component JSON");
    com.google.gson.JsonObject object = element.getAsJsonObject();
    $COMPONENT_NODE component = new $COMPONENT_NODE();
    if (object.has("text")) {
        $STRING_HELPER(object.get("text"), "text");
    } else if (object.has("translate")) {
        $STRING_HELPER(object.get("translate"), "translate");
        $OPTIONAL_STRING_HELPER(object, "fallback");
        if (object.has("with"))
            for (com.google.gson.JsonElement argument :
                    $ARRAY_HELPER(
                        object.get("with"), "with"))
                $DECODE_HELPER(argument, false);
    } else if (object.has("score")) {
        com.google.gson.JsonObject score = $OBJECT_HELPER(
            object.get("score"), "score");
        $STRING_HELPER(score.get("name"), "name");
        $STRING_HELPER(
            score.get("objective"), "objective");
    } else if (object.has("selector")) {
        if (object.has("separator"))
            java.util.Objects.requireNonNull(
                $DECODE_HELPER(
                    object.get("separator"), false));
        $STRING_HELPER(
            object.get("selector"), "selector");
    } else if (object.has("keybind")) {
        $STRING_HELPER(
            object.get("keybind"), "keybind");
    } else if (object.has("nbt")) {
        $STRING_HELPER(object.get("nbt"), "nbt");
        if (object.has("separator"))
            java.util.Objects.requireNonNull(
                $DECODE_HELPER(
                    object.get("separator"), false));
        if (object.has("interpret"))
            $BOOLEAN_HELPER(
                object.get("interpret"), "interpret");
        if (object.has("block"))
            $STRING_HELPER(
                object.get("block"), "block");
        else if (object.has("entity"))
            $STRING_HELPER(
                object.get("entity"), "entity");
        else if (object.has("storage"))
            $RESOURCE_HELPER(
                $STRING_HELPER(
                    object.get("storage"), "storage"));
        else
            throw new com.google.gson.JsonParseException(
                "missing nbt data source");
    } else {
        throw new com.google.gson.JsonParseException(
            "missing text component contents");
    }
    if (object.has("extra")) {
        com.google.gson.JsonArray siblings =
            $ARRAY_HELPER(
                object.get("extra"), "extra");
        if (siblings.isEmpty())
            throw new com.google.gson.JsonParseException(
                "empty text component siblings");
        for (com.google.gson.JsonElement sibling : siblings)
            component.siblings.add(
                $DECODE_HELPER(sibling, false));
    }
    component.click = $STYLE_HELPER(object);
    return component;
}

private static com.google.gson.JsonObject $OBJECT_HELPER(
        com.google.gson.JsonElement element, String name) {
    if (element == null || !element.isJsonObject())
        throw new com.google.gson.JsonSyntaxException(
            name + " must be an object");
    return element.getAsJsonObject();
}

private static com.google.gson.JsonArray $ARRAY_HELPER(
        com.google.gson.JsonElement element, String name) {
    if (element == null || !element.isJsonArray())
        throw new com.google.gson.JsonSyntaxException(
            name + " must be an array");
    return element.getAsJsonArray();
}

private static String $STRING_HELPER(
        com.google.gson.JsonElement element, String name) {
    if (element == null || !element.isJsonPrimitive())
        throw new com.google.gson.JsonSyntaxException(
            name + " must be a primitive");
    return element.getAsString();
}

private static String $OPTIONAL_STRING_HELPER(
        com.google.gson.JsonObject object, String name) {
    return object.has(name)
        ? $STRING_HELPER(object.get(name), name)
        : null;
}

private static boolean $BOOLEAN_HELPER(
        com.google.gson.JsonElement element, String name) {
    if (element == null || !element.isJsonPrimitive())
        throw new com.google.gson.JsonSyntaxException(
            name + " must be a primitive");
    return element.getAsBoolean();
}

private static int $INT_HELPER(
        com.google.gson.JsonElement element, String name) {
    if (element == null || !element.isJsonPrimitive() ||
            !element.getAsJsonPrimitive().isNumber())
        throw new com.google.gson.JsonSyntaxException(
            name + " must be a number");
    return element.getAsInt();
}

private static void $RESOURCE_HELPER(String value) {
    int separator = value.indexOf(':');
    String namespace = "minecraft";
    String path = value;
    if (separator >= 0) {
        path = value.substring(separator + 1);
        if (separator >= 1)
            namespace = value.substring(0, separator);
    }
    for (int index = 0; index < namespace.length(); index++) {
        char character = namespace.charAt(index);
        if (!(character == '_' || character == '-' ||
                character == '.' ||
                character >= 'a' && character <= 'z' ||
                character >= '0' && character <= '9'))
            throw new com.google.gson.JsonSyntaxException(
                "invalid resource namespace: " + value);
    }
    for (int index = 0; index < path.length(); index++) {
        char character = path.charAt(index);
        if (!(character == '_' || character == '-' ||
                character == '.' || character == '/' ||
                character >= 'a' && character <= 'z' ||
                character >= '0' && character <= '9'))
            throw new com.google.gson.JsonSyntaxException(
                "invalid resource path: " + value);
    }
}

private static boolean $STYLE_HELPER(
        com.google.gson.JsonObject object) {
    String[] flags = {
        "bold", "italic", "underlined",
        "strikethrough", "obfuscated"
    };
    for (String flag : flags)
        if (object.has(flag))
            object.get(flag).getAsBoolean();
    if (object.has("color"))
        $STRING_HELPER(object.get("color"), "color");
    $OPTIONAL_STRING_HELPER(object, "insertion");

    boolean clickFound = false;
    if (object.has("clickEvent")) {
        com.google.gson.JsonObject click = $OBJECT_HELPER(
            object.get("clickEvent"), "clickEvent");
        String action =
            $OPTIONAL_STRING_HELPER(click, "action");
        String value =
            $OPTIONAL_STRING_HELPER(click, "value");
        if (action != null && value != null)
            clickFound = switch (action) {
                case "open_url", "run_command",
                        "suggest_command", "change_page",
                        "copy_to_clipboard" -> true;
                default -> false;
            };
    }
    if (object.has("hoverEvent"))
        $HOVER_HELPER($OBJECT_HELPER(
            object.get("hoverEvent"), "hoverEvent"));
    if (object.has("font"))
        $RESOURCE_HELPER(
            $STRING_HELPER(object.get("font"), "font"));
    return clickFound;
}

private static void $HOVER_HELPER(
        com.google.gson.JsonObject hover) {
    String action =
        $OPTIONAL_STRING_HELPER(hover, "action");
    if (!"show_text".equals(action) &&
            !"show_item".equals(action) &&
            !"show_entity".equals(action))
        return;
    com.google.gson.JsonElement contents =
        hover.get("contents");
    if (contents != null) {
        $HOVER_CONTENTS_HELPER(action, contents);
        return;
    }
    // Legacy value components are decoded first. Item SNBT
    // errors and all legacy entity payload errors were caught.
    $COMPONENT_NODE legacy =
        $DECODE_HELPER(hover.get("value"), true);
    if ("show_item".equals(action) && legacy != null)
        $TOUCH_HELPER(legacy);
}

private static void $HOVER_CONTENTS_HELPER(
        String action,
        com.google.gson.JsonElement contents) {
    if ("show_text".equals(action)) {
        $DECODE_HELPER(contents, true);
        return;
    }
    if ("show_item".equals(action)) {
        if (contents.isJsonPrimitive()) {
            $RESOURCE_HELPER(contents.getAsString());
            return;
        }
        com.google.gson.JsonObject item =
            $OBJECT_HELPER(contents, "item");
        $RESOURCE_HELPER(
            $STRING_HELPER(item.get("id"), "id"));
        if (item.has("count"))
            $INT_HELPER(item.get("count"), "count");
        if (item.has("tag"))
            $STRING_HELPER(item.get("tag"), "tag");
        return;
    }
    if (!contents.isJsonObject())
        return;
    com.google.gson.JsonObject entity =
        contents.getAsJsonObject();
    $RESOURCE_HELPER(
        $STRING_HELPER(entity.get("type"), "type"));
    java.util.UUID.fromString(
        $STRING_HELPER(entity.get("id"), "id"));
    $DECODE_HELPER(entity.get("name"), true);
}
""".trim('\n')

    /**
     * 1.20.1 `GsonHelper.fromNullableJson` reading and the `TreeTypeAdapter` null contract: the
     * document 1.20.1 decoded, or null where 1.20.1 returned no component. The 1.21.1
     * `fromJson(JsonElement, Provider)` returns null for null and decodes everything else.
     */
    private val componentJsonHelpers = """
private static com.google.gson.JsonElement $LEGACY_JSON(String json, boolean lenient) {
    com.google.gson.stream.JsonReader reader =
        new com.google.gson.stream.JsonReader(new java.io.StringReader(json));
    reader.setLenient(lenient);
    try {
        reader.peek();
    } catch (java.io.EOFException empty) {
        return null;
    } catch (java.io.IOException failure) {
        throw $LEGACY_JSON_FAILURE(failure);
    }
    com.google.gson.JsonElement element;
    try {
        element = new com.google.gson.Gson()
            .getAdapter(com.google.gson.JsonElement.class)
            .read(reader);
    } catch (java.io.IOException failure) {
        throw $LEGACY_JSON_FAILURE(failure);
    }
    return element.isJsonNull() ? null : element;
}

private static com.google.gson.JsonElement $LEGACY_JSON(
        com.google.gson.JsonElement json, boolean lenient) {
    return json == null || json.isJsonNull() ? null : json;
}

private static com.google.gson.JsonParseException $LEGACY_JSON_FAILURE(
        java.io.IOException failure) {
    return failure instanceof java.io.EOFException ||
            failure instanceof com.google.gson.stream.MalformedJsonException
        ? new com.google.gson.JsonSyntaxException(failure)
        : new com.google.gson.JsonIOException(failure);
}
""".trim('\n')
}
