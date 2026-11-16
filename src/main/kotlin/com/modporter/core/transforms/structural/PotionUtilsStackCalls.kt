package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.core.transforms.util.JavaSourceText

/**
 * 1.20.1's `PotionUtils` read and wrote a potion stack's NBT; 1.21.1 keeps that state in the
 * stack's `DataComponents.POTION_CONTENTS`. Several `PotionUtils` methods are overloaded for
 * `CompoundTag`, `Potion` or effect collections as well (javap of the mapped 1.20.1 client), so a
 * call is rewritten only when its stack argument is not proven to be something else: a name
 * declared with another type in scope, or another `PotionUtils` call (which returns a potion or a
 * list) is refused and left for review.
 */
internal object PotionUtilsStackCalls {
    private val call = Regex("""(?<![\w$.])(?:net\.minecraft\.world\.item\.alchemy\.)?PotionUtils\.(setPotion|getPotion|getColor|getMobEffects|addPotionTooltip)\s*\(""")
    private const val CONTENTS = "getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)"

    /** The migrated source, or [source] itself when no call was proven to take a stack. */
    fun migrate(source: String): String {
        if (!source.contains("PotionUtils.")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = call.findAll(masked).mapNotNull { match ->
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) return@mapNotNull null
            val args = JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close)).map { it.trim() }
            if (args.isEmpty() || !mayBeStack(masked, args[0], match.range.first)) return@mapNotNull null
            val stack = args[0]
            var end = close
            val replacement = when (match.groupValues[1]) {
                "setPotion" -> if (args.size != 2) null else {
                    val set = "set(DataComponents.POTION_CONTENTS, new PotionContents(${potionHolder(masked, args[1], match.range.first)}))"
                    // setPotion returned the stack
                    if (isStatement(masked, match.range.first, close)) "$stack.$set"
                    else "net.minecraft.Util.make($stack, modporterStack -> modporterStack.$set)"
                }
                "getPotion" -> if (args.size != 1) null else "$stack.$CONTENTS.potion().orElse(Potions.EMPTY)"
                "getColor" -> if (args.size != 1) null else "$stack.$CONTENTS.getColor()"
                "getMobEffects" -> if (args.size != 1) null else {
                    val isEmpty = Regex("""^\s*\.\s*isEmpty\s*\(\s*\)""").find(masked.substring(close + 1))
                    if (isEmpty != null) {
                        end = close + isEmpty.range.last + 1
                        "!$stack.$CONTENTS.getAllEffects().iterator().hasNext()"
                    } else "$stack.$CONTENTS.getAllEffects()"
                }
                "addPotionTooltip" -> if (args.size != 3) null else
                    "$stack.$CONTENTS.addPotionTooltip(${args[1]}::add, ${args[2]}, 20.0F)"
                else -> null
            }
            replacement?.let { (match.range.first..end) to it }
        }.toList()
        if (edits.isEmpty()) return source
        return edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    }

    private fun mayBeStack(masked: String, argument: String, at: Int): Boolean {
        if (argument.contains("PotionUtils.")) return false
        if (!Regex("""[A-Za-z_$][\w$]*""").matches(argument)) return true
        val declared = JavaScopedDeclarations.visibleAt(masked, argument, at)?.type ?: return true
        return declared == "ItemStack"
    }

    /** `PotionContents` takes a `Holder<Potion>`; a name declared as a plain `Potion` is wrapped by its registry. */
    private fun potionHolder(masked: String, argument: String, at: Int): String {
        if (!Regex("""[A-Za-z_$][\w$]*""").matches(argument)) return argument
        val declared = JavaScopedDeclarations.visibleAt(masked, argument, at)?.type
        return if (declared == "Potion") "net.minecraft.core.registries.BuiltInRegistries.POTION.wrapAsHolder($argument)" else argument
    }

    private fun isStatement(masked: String, start: Int, close: Int): Boolean =
        Regex("""[;{}]\s*$""").containsMatchIn(masked.substring(maxOf(0, start - 200), start)) &&
            Regex("""^\s*;""").containsMatchIn(masked.substring(close + 1))
}
