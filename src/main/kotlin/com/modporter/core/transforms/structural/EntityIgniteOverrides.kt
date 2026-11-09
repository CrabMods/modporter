package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * 1.20.1's overridable `Entity.setSecondsOnFire(int seconds)` set `seconds * 20` fire ticks. On
 * 1.21.1 `igniteForSeconds(float)` is final and hands `floor(seconds * 20)` ticks to the
 * overridable `igniteForTicks(int)`, so an override moves there: a body that reads the old seconds
 * gets them back as `ticks / 20`, and `super.setSecondsOnFire(s)` becomes
 * `super.igniteForTicks(s * 20)`.
 */
internal object EntityIgniteOverrides {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val declaration = Regex("""(@Override\s+)((?:(?:public|protected|final)\s+)*)void\s+setSecondsOnFire\s*\(\s*(?:final\s+)?int\s+($ID)\s*\)\s*\{""")

    fun migrate(source: String): String {
        if (!source.contains("setSecondsOnFire") || !source.contains("extends")) return source
        var result = source
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val match = declaration.find(masked) ?: break
            val parameter = match.groupValues[3]
            val open = match.range.last
            val close = JavaSourceText.matchingBrace(masked, open)
            if (close < 0) break
            val readsParameter = Regex("""(?<![\w$.])${Regex.escape(parameter)}(?![\w$])""").containsMatchIn(masked.substring(open + 1, close))
            val ticks = if (readsParameter) "${parameter}Ticks" else parameter
            val header = "${match.groupValues[1]}${match.groupValues[2]}void igniteForTicks(int $ticks) {"
            val prologue = if (readsParameter) "\n        int $parameter = $ticks / 20;" else ""
            val body = result.substring(open + 1, close)
                .replace(Regex("""\bsuper\s*\.\s*setSecondsOnFire\s*\(([^;]*)\)\s*;""")) { "super.igniteForTicks((${it.groupValues[1]}) * 20);" }
            result = result.substring(0, match.range.first) + header + prologue + body + result.substring(close)
        }
        // an unqualified call the file declares nothing for reaches the inherited Entity method, as
        // the qualified `.setSecondsOnFire(` the text pass already maps to igniteForSeconds
        val masked = JavaSourceText.maskCommentsAndLiterals(result)
        if (!Regex("""\bsetSecondsOnFire\s*\([^()]*\)\s*(?:throws\s+[^{;]+)?\{""").containsMatchIn(masked)) {
            Regex("""(?<![\w$.])setSecondsOnFire(\s*\()""").findAll(masked).toList().asReversed().forEach { call ->
                result = result.substring(0, call.range.first) + "igniteForSeconds" + call.groupValues[1] + result.substring(call.range.last + 1)
            }
        }
        return result
    }
}
