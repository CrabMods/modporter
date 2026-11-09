package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * Forge's `Event#isCancelable()` let an event declare itself cancellable by overriding it. NeoForge's
 * bus (javap of bus-8.0.2) has no such method: a cancellable event implements `ICancellableEvent`,
 * which carries `setCanceled`/`isCanceled`. In a file declaring a single class that extends `Event`,
 * an override returning a constant becomes that marker (`true`) or is dropped (`false`, the default);
 * any other body is left as it is.
 */
internal object CancelableEventOverrides {
    private val override = Regex(
        """(?m)(?:^[ \t]*\r?\n)?^[ \t]*(?:@Override\s+)?public\s+boolean\s+isCancelable\s*\(\s*\)\s*\{\s*return\s+(true|false)\s*;\s*}[ \t]*(?:\r?\n|$)"""
    )
    private val eventClass = Regex("""\bclass\s+[A-Za-z_$][\w$]*(?:<[^{]*?>)?\s+extends\s+Event(\s+implements\s+[^{]+?)?\s*\{""")

    fun migrate(source: String): String {
        if (!source.contains("isCancelable") || !source.contains("import net.neoforged.bus.api.Event;")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        if (Regex("""\bclass\s""").findAll(masked).count() != 1) return source
        val header = eventClass.find(masked) ?: return source
        val method = override.findAll(masked).singleOrNull() ?: return source
        var result = source.removeRange(method.range)
        if (method.groupValues[1] == "false") return result
        val implements = header.groups[1]
        result = if (implements == null) {
            val brace = header.range.last
            result.substring(0, brace).trimEnd() + " implements ICancellableEvent " + result.substring(brace)
        } else {
            result.replaceRange(implements.range, implements.value.trimEnd() + ", ICancellableEvent")
        }
        return JavaSourceText.addImport(result, "net.neoforged.bus.api.ICancellableEvent")
    }
}
