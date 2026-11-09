package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations

/** Legacy EditBox cursor movements used the current Shift key state to extend selection. */
internal fun StructuralRefactorPass.migrateEditBoxCursorCalls(source: String, index: JavaInheritanceIndex): String {
    if (!source.contains("moveCursorToEnd") && !source.contains("moveCursorToStart")) return source
    fun isEditBox(type: String): Boolean {
        var current = type.substringBefore('<').trim()
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            if (current == "net.minecraft.client.gui.components.EditBox" ||
                current == "EditBox" && source.contains("import net.minecraft.client.gui.components.EditBox;")
            ) return true
            current = index.directSuperOf(current.substringAfterLast('.')) ?: return false
        }
        return false
    }
    var result = source
    for (method in listOf("moveCursorToEnd", "moveCursorToStart")) {
        val masked = maskJavaCommentsAndLiterals(result)
        result = rewriteExecutableJavaCallWithOffset(result, method) { receiver, args, offset ->
            if (args.isNotEmpty()) return@rewriteExecutableJavaCallWithOffset null
            val type = if (receiver.trim() in setOf("this", "super")) enclosingJavaClassDeclaration(result, offset)?.name
                else JavaScopedDeclarations.visibleAt(masked, receiver.trim().removePrefix("this."), offset)?.type
            if (type == null || !isEditBox(type)) null
            else "$receiver.$method(net.minecraft.client.gui.screens.Screen.hasShiftDown())"
        }
        val ownNames = javaMethodRangesIncludingDefault(result).filter { it.name == method }.map {
            enclosingJavaClassDeclaration(result, it.range.first)?.name
        }.toSet()
        result = replaceExecutableJavaRegex(result, Regex("""(?<![\w$.])$method\s*\(\s*\)""")) { match ->
            val owner = enclosingJavaClassDeclaration(result, match.range.first)?.name
            if (owner == null || owner in ownNames || !isEditBox(owner)) match.value
            else "$method(net.minecraft.client.gui.screens.Screen.hasShiftDown())"
        }
    }
    return result
}
