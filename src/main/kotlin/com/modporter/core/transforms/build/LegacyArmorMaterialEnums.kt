package com.modporter.core.transforms.build

import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * A Forge 1.20.1 `enum X implements ArmorMaterial` keeps computing its values the way it always did:
 * the enum stays, nested as `X.Legacy` with its own constants, fields, constructor and getters, and
 * every constant gets a registered 1.21.1 `ArmorMaterial` holder under its old name (so `X.COPPER`
 * call sites stay) whose values are read from those getters when the registry fills:
 *
 * - defense: `getDefenseForType` for the four 1.20.1 armor types (1.20.1 had no BODY type);
 * - enchantability, toughness, knockback resistance, repair ingredient: their getters;
 * - equip sound: the registered sound event's holder;
 * - texture: one layer named by `getName()`, which 1.20.1's armor layer resolved the same way
 *   (`[namespace:]name` -> `textures/models/armor/<name>_layer_<n>.png`), dyeable with an
 *   `_overlay` layer when a project armor item made of it implemented `DyeableLeatherItem`.
 *
 * 1.20.1's `ArmorItem` took its durability from `getDurabilityForType`; 1.21.1's does not, so
 * `X.withDurability(properties, material, type)` puts it back on the item properties.
 *
 * NeoForge fires `RegisterEvent` for `minecraft:armor_material` before every other registry
 * (`GameData.getRegistrationOrder`: an `ArmorItem` needs its material at construction), and the mod
 * constructor's `X.ARMOR_MATERIALS.register(bus)` initializes `X` and `X.Legacy` before any
 * registration at all. Nothing the constants or the registered record evaluate may therefore
 * dereference a mod registry entry, which 1.20.1 only got away with because the enum was first
 * touched while items registered:
 *
 * - a `SoundEvent` (or `Supplier<SoundEvent>`) constructor parameter whose every constant passes a
 *   holder (a 1.21.1 `SoundEvents` holder, or a project `DeferredHolder<SoundEvent, SoundEvent>` /
 *   `Holder<SoundEvent>` field, with or without `.get()`) becomes `Holder<SoundEvent>`, its getter
 *   returns `value()`, and the record receives that holder itself (1.21.1's `equipSound()` is one);
 * - an `Ingredient` constructor parameter becomes `Supplier<Ingredient>` with every argument
 *   deferred (`() -> Ingredient.of(...)`), the type 1.21.1's record stores.
 *
 * A sound the project resolves through its own producer (LegacyArmorMaterialSoundHolderGraph) keeps
 * that supplier; a sound that is neither wraps the getter's value when the registry fills, as before.
 */
internal object LegacyArmorMaterialEnums {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val legacyGetters = listOf(
        "getDurabilityForType", "getDefenseForType", "getEnchantmentValue", "getEquipSound",
        "getRepairIngredient", "getName", "getToughness", "getKnockbackResistance"
    )
    private val plainSoundTypes = setOf("SoundEvent", "net.minecraft.sounds.SoundEvent")
    private val soundTypes = plainSoundTypes + setOf(
        "Supplier<SoundEvent>", "java.util.function.Supplier<SoundEvent>",
        "Supplier<net.minecraft.sounds.SoundEvent>", "java.util.function.Supplier<net.minecraft.sounds.SoundEvent>"
    )
    private val ingredientTypes = setOf("Ingredient", "net.minecraft.world.item.crafting.Ingredient")

    data class Rendered(val enumName: String, val packageName: String, val constants: List<String>, val source: String)

    /** The replacement source for an `enum implements ArmorMaterial` declaring every 1.20.1 getter, or null. */
    fun render(
        source: String,
        modIdExpr: String,
        dyeableConstants: Set<String>
    ): Rendered? = render(source, modIdExpr, dyeableConstants, emptyMap(), emptySet())

    /**
     * Renders a legacy armor enum while carrying any source-proved sound holders into the
     * 1.21 ArmorMaterial record without dereferencing those holders during registry creation.
     */
    fun render(
        source: String,
        modIdExpr: String,
        dyeableConstants: Set<String>,
        soundHolderSuppliers: Map<String, String>
    ): Rendered? = render(source, modIdExpr, dyeableConstants, soundHolderSuppliers, emptySet())

    /**
     * [projectSoundHolderFields] names the project's `Owner.FIELD` sound holders (fields declared
     * `DeferredHolder<SoundEvent, SoundEvent>` or `Holder<SoundEvent>`), which constants may pass
     * without ever dereferencing them.
     */
    fun render(
        source: String,
        modIdExpr: String,
        dyeableConstants: Set<String>,
        soundHolderSuppliers: Map<String, String>,
        projectSoundHolderFields: Set<String>
    ): Rendered? {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val header = Regex("""\b(public\s+)?enum\s+($ID)\s+implements\s+([^{]+)\{""").find(masked) ?: return null
        val interfaces = header.groupValues[3].split(',').map { it.trim() }
        if ("ArmorMaterial" !in interfaces && "net.minecraft.world.item.ArmorMaterial" !in interfaces) return null
        val enumName = header.groupValues[2]
        val open = header.range.last
        val close = JavaSourceText.matchingBrace(masked, open)
        if (close < 0) return null
        val bodyMasked = masked.substring(open + 1, close)
        if (legacyGetters.any { !Regex("""\b$it\s*\(""").containsMatchIn(bodyMasked) }) return null
        val packageName = JavaSourceText.packageName(source) ?: return null
        val constantsEnd = constantsEnd(bodyMasked)
        if (constantsEnd < 0) return null
        val constants = topLevelSegments(bodyMasked.substring(0, constantsEnd))
            .mapNotNull { Regex("""^\s*($ID)\b""").find(it)?.groupValues?.get(1) }
        if (constants.isEmpty()) return null

        // the legacy body: registry-backed values become lazy, getters lose @Override, the
        // constructor takes the nested name
        val bodyText = source.substring(open + 1, close)
        val plan = EnumShape.parse(bodyMasked, enumName, constantsEnd)
            ?.let { lazyValuePlan(it, bodyText, bodyMasked, projectSoundHolderFields) }
            ?: LazyValuePlan(emptyList(), emptyMap())
        var body = plan.edits.sortedByDescending { it.first.first }
            .fold(bodyText) { text, (range, value) -> text.replaceRange(range, value) }
        for (getter in legacyGetters) {
            body = body.replace(Regex("""@Override(\s+(?:@[\w.]+\s+)*(?:public\s+)?[\w.<>\[\]?, ]+\s+$getter\s*\()"""), "$1")
        }
        body = body.replace(Regex("""\b(private\s+|protected\s+|public\s+)?${Regex.escape(enumName)}\s*\("""), "$1Legacy(")
        val otherInterfaces = interfaces.filter { it != "ArmorMaterial" && it != "net.minecraft.world.item.ArmorMaterial" }
        val legacyImplements = if (otherInterfaces.isEmpty()) "" else " implements ${otherInterfaces.joinToString(", ")}"

        val before = source.substring(0, header.range.first)
        val imports = listOf(
            "java.util.EnumMap", "java.util.List", "java.util.Optional",
            "java.util.function.Supplier", "net.minecraft.core.Holder",
            "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.registries.Registries",
            "net.minecraft.resources.ResourceLocation", "net.minecraft.sounds.SoundEvent",
            "net.minecraft.world.item.ArmorItem",
            "net.minecraft.world.item.ArmorMaterial", "net.minecraft.world.item.Item",
            "net.neoforged.neoforge.registries.DeferredHolder", "net.neoforged.neoforge.registries.DeferredRegister"
        ).fold(before) { text, import -> JavaSourceText.addImport(text, import) }
        // the enum's own public methods stay reachable from a material holder (`((X) getMaterial()).m()`
        // becomes `X.m(getMaterial())`)
        val delegates = Regex("""(?m)^[ \t]*public\s+(?!static\b)(?:final\s+)?([\w.<>\[\]?, ]+?)\s+($ID)\s*\(([^()]*)\)\s*\{""").findAll(bodyMasked)
            .filter { it.groupValues[2] !in legacyGetters && it.groupValues[2] != enumName && !it.groupValues[1].startsWith("<") }
            .joinToString("") { method ->
                val (returnType, name, _) = method.destructured
                val parameterText = bodyMasked.substring(method.groups[3]!!.range).trim()
                val names = if (parameterText.isEmpty()) emptyList() else JavaSourceText.splitTopLevelArgs(parameterText).map { it.trim().substringAfterLast(' ') }
                val declared = (listOf("Holder<ArmorMaterial> material") + if (parameterText.isEmpty()) emptyList() else listOf(parameterText)).joinToString(", ")
                val call = "legacy(material).orElseThrow().$name(${names.joinToString(", ")})"
                val statement = if (returnType.trim() == "void") "$call;" else "return $call;"
                "\n    public static ${returnType.trim()} $name($declared) {\n        $statement\n    }\n"
            }
        val holders = constants.joinToString("\n") { constant ->
            val soundHolder = soundHolderSuppliers[constant]
                ?: plan.soundHolders[constant]
                ?: "() -> BuiltInRegistries.SOUND_EVENT.wrapAsHolder(Legacy.$constant.getEquipSound())"
            "    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> $constant = " +
                "register(Legacy.$constant, $soundHolder, ${constant in dyeableConstants});"
        }
        val rendered = """${imports.trimEnd()}

${header.groupValues[1]}final class $enumName {
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, $modIdExpr);

$holders

    private static DeferredHolder<ArmorMaterial, ArmorMaterial> register(
        Legacy legacy,
        Supplier<Holder<SoundEvent>> equipSound,
        boolean dyeable
    ) {
        ResourceLocation texture = ResourceLocation.parse(legacy.getName());
        List<ArmorMaterial.Layer> layers = dyeable
            ? List.of(new ArmorMaterial.Layer(texture, "", true), new ArmorMaterial.Layer(texture, "_overlay", false))
            : List.of(new ArmorMaterial.Layer(texture));
        return ARMOR_MATERIALS.register(texture.getPath(), () -> {
            EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
            for (ArmorItem.Type type : new ArmorItem.Type[] { ArmorItem.Type.HELMET, ArmorItem.Type.CHESTPLATE, ArmorItem.Type.LEGGINGS, ArmorItem.Type.BOOTS }) {
                defense.put(type, legacy.getDefenseForType(type));
            }
            return new ArmorMaterial(defense, legacy.getEnchantmentValue(), equipSound.get(),
                legacy::getRepairIngredient, layers, legacy.getToughness(), legacy.getKnockbackResistance());
        });
    }

    private static Optional<Legacy> legacy(Holder<ArmorMaterial> material) {
${constants.joinToString("\n") { "        if (material.is($it.getKey())) return Optional.of(Legacy.$it);" }}
        return Optional.empty();
    }

    /** 1.20.1's ArmorItem took its durability from the material; 1.21.1's reads it from the item properties. */
    public static Item.Properties withDurability(Item.Properties properties, Holder<ArmorMaterial> material, ArmorItem.Type type) {
        return legacy(material).map(legacy -> properties.durability(legacy.getDurabilityForType(type))).orElse(properties);
    }

$delegates
    private $enumName() {
    }

    enum Legacy$legacyImplements {$body}
}
"""
        return Rendered(enumName, packageName, constants, rendered + source.substring(close + 1).trimStart().let { if (it.isEmpty()) "" else "\n$it" })
    }

    private class Parameter(val typeRange: IntRange, val type: String, val name: String)

    /** The single constructor of the legacy enum, the fields it assigns, and every constant's arguments. */
    private class EnumShape(
        val parameters: List<Parameter>,
        val fieldByParameter: Map<Int, String>,
        val constantArguments: Map<String, List<IntRange>>
    ) {
        /** The index of the one parameter typed in [types], or null when there is none or several. */
        fun single(types: Set<String>): Int? =
            parameters.withIndex().filter { it.value.type in types }.map { it.index }.singleOrNull()

        companion object {
            fun parse(bodyMasked: String, enumName: String, constantsEnd: Int): EnumShape? {
                val constructor = Regex("""\b${Regex.escape(enumName)}\s*\(([^()]*)\)\s*\{""").findAll(bodyMasked)
                    .singleOrNull { it.range.first > constantsEnd } ?: return null
                val open = bodyMasked.indexOf('(', constructor.range.first)
                val close = open + 1 + constructor.groupValues[1].length
                val declaration = Regex(
                    """^(\s*(?:(?:@$ID(?:\.$ID)*(?:\([^()]*\))?|final)\s+)*)(.+?)\s+($ID)\s*$""",
                    RegexOption.DOT_MATCHES_ALL
                )
                val parameters = parameterRanges(bodyMasked, open + 1, close).map { range ->
                    val match = declaration.find(bodyMasked.substring(range)) ?: return null
                    val type = match.groups[2]!!
                    Parameter(
                        (range.first + type.range.first)..(range.first + type.range.last),
                        type.value.replace(Regex("""\s+"""), ""),
                        match.groupValues[3]
                    )
                }
                val bodyEnd = JavaSourceText.matchingBrace(bodyMasked, constructor.range.last)
                if (bodyEnd < 0) return null
                val names = parameters.map { it.name }
                val fieldByParameter = Regex("""\bthis\.($ID)\s*=\s*($ID)\s*;""")
                    .findAll(bodyMasked.substring(constructor.range.last + 1, bodyEnd))
                    .mapNotNull { match ->
                        names.indexOf(match.groupValues[2]).takeIf { it >= 0 }?.let { it to match.groupValues[1] }
                    }
                    .toMap()
                val constantArguments = linkedMapOf<String, List<IntRange>>()
                for (segment in topLevelSegmentRanges(bodyMasked, constantsEnd)) {
                    val name = Regex("""^\s*($ID)\b""").find(bodyMasked.substring(segment))?.groupValues?.get(1) ?: continue
                    val paren = firstTopLevel(bodyMasked, segment, '(')
                    constantArguments[name] = if (paren < 0) emptyList() else {
                        val end = JavaSourceText.matchingParen(bodyMasked, paren)
                        if (end < 0 || end > segment.last) return null
                        argumentRanges(bodyMasked, paren + 1, end)
                    }
                }
                return EnumShape(parameters, fieldByParameter, constantArguments)
            }
        }
    }

    private class Getter(val range: IntRange, val receiver: String)

    private class LazyValuePlan(val edits: List<Pair<IntRange, String>>, val soundHolders: Map<String, String>)

    /** Body edits that keep registry-backed constant values lazy, and each constant's sound holder supplier. */
    private fun lazyValuePlan(
        shape: EnumShape,
        body: String,
        bodyMasked: String,
        projectSoundHolderFields: Set<String>
    ): LazyValuePlan {
        val edits = mutableListOf<Pair<IntRange, String>>()
        val soundHolders = linkedMapOf<String, String>()
        shape.single(soundTypes)?.let { index ->
            val parameter = shape.parameters[index]
            val holders = shape.constantArguments.mapNotNull { (constant, arguments) ->
                val range = arguments.getOrNull(index) ?: return@mapNotNull null
                soundHolderExpression(body.substring(range), projectSoundHolderFields)?.let { constant to (range to it) }
            }.toMap()
            holders.forEach { (constant, holder) -> soundHolders[constant] = "() -> ${holder.second}" }
            val field = shape.fieldByParameter[index]
            val declaration = field?.let { fieldTypeRange(bodyMasked, it, soundTypes) }
            val getter = field?.let { simpleGetter(bodyMasked, "getEquipSound", it, dereferenced = true) }
            val everyConstant = shape.constantArguments.all { (constant, arguments) -> arguments.isEmpty() || constant in holders }
            if (holders.isNotEmpty() && everyConstant && declaration != null && getter != null) {
                edits += parameter.typeRange to "Holder<SoundEvent>"
                edits += declaration to "Holder<SoundEvent>"
                edits += getter.range to "return ${getter.receiver}.value();"
                holders.values.forEach { (range, holder) -> edits += range to keepingWhitespace(body.substring(range), holder) }
            } else if (parameter.type in plainSoundTypes) {
                // the parameter keeps its SoundEvent type; a vanilla sound the 1.21.1 class table
                // types as a holder passes its value(), the same registered SoundEvent 1.20.1 held
                shape.constantArguments.values.forEach { arguments ->
                    val range = arguments.getOrNull(index) ?: return@forEach
                    vanillaSoundHolder(body.substring(range))?.let {
                        edits += range to keepingWhitespace(body.substring(range), "$it.value()")
                    }
                }
            }
        }
        shape.single(ingredientTypes)?.let { index ->
            val parameter = shape.parameters[index]
            val field = shape.fieldByParameter[index]
            val declaration = field?.let { fieldTypeRange(bodyMasked, it, ingredientTypes) }
            val getter = field?.let { simpleGetter(bodyMasked, "getRepairIngredient", it, dereferenced = false) }
            val everyConstant = shape.constantArguments.values.all { it.isEmpty() || it.size > index }
            if (declaration != null && getter != null && everyConstant) {
                edits += parameter.typeRange to "Supplier<Ingredient>"
                edits += declaration to "Supplier<Ingredient>"
                edits += getter.range to "return ${getter.receiver}.get();"
                shape.constantArguments.values.forEach { arguments ->
                    val range = arguments.getOrNull(index) ?: return@forEach
                    edits += range to keepingWhitespace(body.substring(range), "() -> ${body.substring(range).trim()}")
                }
            }
        }
        return LazyValuePlan(edits, soundHolders)
    }

    /** A `SoundEvents` constant the 1.21.1 class table types as `Holder<SoundEvent>`, trimmed, or null. */
    private fun vanillaSoundHolder(argument: String): String? {
        val sound = Regex("""^\s*((?:net\.minecraft\.sounds\.)?SoundEvents\.([A-Z0-9_]+))\s*$""").find(argument) ?: return null
        return sound.groupValues[1].takeIf {
            TargetMembers.fieldDescriptor("net.minecraft.sounds.SoundEvents", sound.groupValues[2]) == "Lnet/minecraft/core/Holder;"
        }
    }

    /** The holder a constant's sound argument names (vanilla or a project holder field), or null. */
    private fun soundHolderExpression(argument: String, projectSoundHolderFields: Set<String>): String? {
        vanillaSoundHolder(argument)?.let { return it }
        val field = Regex("""^\s*((?:$ID\.)*($ID))\.($ID)(?:\s*\.\s*get\s*\(\s*\))?\s*$""").find(argument) ?: return null
        val (qualifier, owner, name) = field.destructured
        return if ("$owner.$name" in projectSoundHolderFields) "$qualifier.$name" else null
    }

    /** The type range of the enum's own field [field] when its type is one of [types]. */
    private fun fieldTypeRange(bodyMasked: String, field: String, types: Set<String>): IntRange? =
        Regex("""(?m)^[ \t]*(?:(?:private|protected|public|static|final|transient|volatile)\s+)*([\w.]+(?:\s*<[^<>;=]*>)?)\s+${Regex.escape(field)}\s*[;=]""")
            .findAll(bodyMasked)
            .firstOrNull { depthAt(bodyMasked, it.range.first) == 0 && it.groupValues[1].replace(Regex("""\s+"""), "") in types }
            ?.groups?.get(1)?.range

    /**
     * A getter whose body is only `return field;`, `return this.field;` or, when [dereferenced],
     * `return field.get();`: its return statement and receiver, or null.
     */
    private fun simpleGetter(bodyMasked: String, name: String, field: String, dereferenced: Boolean): Getter? {
        val match = Regex("""\b$name\s*\(\s*\)\s*\{\s*(return\s+((?:this\.)?${Regex.escape(field)})(\s*\.\s*get\s*\(\s*\))?\s*;)\s*\}""")
            .find(bodyMasked) ?: return null
        if (match.groupValues[3].isNotEmpty() && !dereferenced) return null
        return Getter(match.groups[1]!!.range, match.groupValues[2])
    }

    private fun keepingWhitespace(original: String, replacement: String): String =
        original.takeWhile { it.isWhitespace() } + replacement + original.takeLastWhile { it.isWhitespace() }

    private fun depthAt(masked: String, index: Int): Int {
        var depth = 0
        for (position in 0 until index) {
            when (masked[position]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth
    }

    /** The first [char] at bracket depth zero inside [segment], or -1. */
    private fun firstTopLevel(masked: String, segment: IntRange, char: Char): Int {
        var depth = 0
        for (index in segment) {
            val current = masked[index]
            if (current == char && depth == 0) return index
            when (current) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
            }
        }
        return -1
    }

    /** The non-blank comma-separated parts of [masked] before [end] at bracket depth zero, as ranges. */
    private fun topLevelSegmentRanges(masked: String, end: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var depth = 0
        var start = 0
        for (index in 0 until end) {
            when (masked[index]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    ranges += start until index
                    start = index + 1
                }
            }
        }
        ranges += start until end
        return ranges.filter { masked.substring(it).isNotBlank() }
    }

    /**
     * Like [argumentRanges] for a parameter list, where `<`/`>` only ever delimit generic arguments
     * (`EnumMap<ArmorItem.Type, Integer> protection` is one parameter).
     */
    private fun parameterRanges(masked: String, start: Int, end: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var depth = 0
        var parameterStart = start
        for (index in start until end) {
            when (masked[index]) {
                '(', '{', '[', '<' -> depth++
                ')', '}', ']', '>' -> depth--
                ',' -> if (depth == 0) {
                    ranges += parameterStart until index
                    parameterStart = index + 1
                }
            }
        }
        if (parameterStart < end) ranges += parameterStart until end
        return ranges
    }

    /** The untrimmed ranges of the top-level arguments between [start] and the closing paren at [end]. */
    private fun argumentRanges(masked: String, start: Int, end: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var depth = 0
        var argumentStart = start
        for (index in start until end) {
            when (masked[index]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    ranges += argumentStart until index
                    argumentStart = index + 1
                }
            }
        }
        if (argumentStart < end) ranges += argumentStart until end
        return ranges
    }

    /** The comma-separated parts of [text] at bracket depth zero. */
    private fun topLevelSegments(text: String): List<String> {
        val segments = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((index, char) in text.withIndex()) {
            when (char) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    segments += text.substring(start, index)
                    start = index + 1
                }
            }
        }
        segments += text.substring(start)
        return segments.filter { it.isNotBlank() }
    }

    /** The end of an enum body's constant list (its `;`, or the body end when there is none). */
    private fun constantsEnd(body: String): Int {
        var depth = 0
        for ((index, char) in body.withIndex()) {
            when (char) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ';' -> if (depth == 0) return index
            }
        }
        return body.length
    }

    /**
     * Consumers of the migrated materials: a direct `ArmorItem` subclass's `super(material, type,
     * properties)` (or a `new ArmorItem(...)`) takes the material's durability back, and an
     * identity comparison with a material becomes a holder comparison.
     */
    fun rewriteConsumer(source: String, enums: List<String>, directArmorItemSubclass: Boolean): String {
        if (enums.isEmpty()) return source
        var result = source
        fun withDurability(args: List<String>): List<String>? {
            if (args.size != 3) return null
            val (material, type, properties) = args.map { it.trim() }
            if (!Regex("""^$ID(?:\.$ID)*$""").matches(material) || !Regex("""^$ID(?:\.$ID)*$""").matches(type)) return null
            if (enums.any { properties.contains("$it.withDurability(") }) return null
            val wrapped = enums.fold(properties) { inner, enum -> "$enum.withDurability($inner, $material, $type)" }
            return listOf(material, type, wrapped)
        }
        fun rewriteCalls(text: String, call: Regex): String {
            var updated = text
            var cursor = 0
            while (true) {
                val masked = JavaSourceText.maskCommentsAndLiterals(updated)
                val match = call.find(masked, cursor) ?: break
                val close = JavaSourceText.matchingParen(masked, match.range.last)
                if (close < 0) break
                val args = withDurability(JavaSourceText.splitTopLevelArgs(updated.substring(match.range.last + 1, close)))
                if (args == null) {
                    cursor = close
                    continue
                }
                val replacement = args.joinToString(", ")
                updated = updated.substring(0, match.range.last + 1) + replacement + updated.substring(close)
                cursor = match.range.last + 1 + replacement.length
            }
            return updated
        }
        if (directArmorItemSubclass) result = rewriteCalls(result, Regex("""\bsuper\s*\("""))
        result = rewriteCalls(result, Regex("""\bnew\s+(?:net\.minecraft\.world\.item\.)?ArmorItem\s*\("""))
        // only a side known to be a holder (ArmorItem.getMaterial(), or a name declared
        // Holder<ArmorMaterial>) compares by key; anything else is left for the compiler
        val holderNames = Regex("""\bHolder\s*<\s*ArmorMaterial\s*>\s+($ID)\b""").findAll(result).map { it.groupValues[1] }.toSet()
        fun isHolder(expression: String): Boolean =
            expression == "getMaterial()" || expression.endsWith(".getMaterial()") || expression in holderNames
        for (enum in enums) {
            // a cast of a material to the old enum calls the enum's static delegate instead
            result = result.replace(Regex("""\(\(\s*${Regex.escape(enum)}\s*\)\s*([A-Za-z_$][\w$.]*(?:\(\))?)\s*\)\s*\.\s*($ID)\s*\(\s*(\)?)""")) { match ->
                val (material, method, closed) = match.destructured
                if (closed.isNotEmpty()) "$enum.$method($material)" else "$enum.$method($material, "
            }
            val constant = """${Regex.escape(enum)}\.$ID"""
            result = result.replace(Regex("""([A-Za-z_$][\w$.]*(?:\(\))?)\s*(==|!=)\s*($constant)\b""")) { match ->
                if (!isHolder(match.groupValues[1])) match.value
                else (if (match.groupValues[2] == "!=") "!" else "") + "${match.groupValues[1]}.is(${match.groupValues[3]}.getKey())"
            }
            result = result.replace(Regex("""\b($constant)\s*(==|!=)\s*([A-Za-z_$][\w$.]*(?:\(\))?)""")) { match ->
                if (!isHolder(match.groupValues[3])) match.value
                else (if (match.groupValues[2] == "!=") "!" else "") + "${match.groupValues[3]}.is(${match.groupValues[1]}.getKey())"
            }
        }
        return result
    }
}
