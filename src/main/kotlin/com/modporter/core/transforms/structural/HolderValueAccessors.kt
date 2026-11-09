package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations

/** Registry delegates migrated to Holder use value(), including bound method references. */
internal fun StructuralRefactorPass.migrateHolderValueAccessors(source: String): String {
    val masked = maskJavaCommentsAndLiterals(source)
    if (!Regex("""\bnet\.minecraft\.core\.Holder\b""").containsMatchIn(masked)) return source
    val access = Regex("""(?<![\w$.])((?:this\.)?([A-Za-z_$][\w$]*))\s*(\.\s*get\s*\(\s*\)|::get(?![\w$]))""")
    return replaceExecutableJavaRegex(source, access) { match ->
        val type = JavaScopedDeclarations.visibleAt(masked, match.groupValues[2], match.range.first)?.type
            ?.substringBefore('<')?.trim()
        val holder = type == "net.minecraft.core.Holder" ||
            type == "Holder" && source.contains("import net.minecraft.core.Holder;")
        if (!holder) match.value
        else if (match.groupValues[3].startsWith("::")) "${match.groupValues[1]}::value"
        else "${match.groupValues[1]}.value()"
    }
}
