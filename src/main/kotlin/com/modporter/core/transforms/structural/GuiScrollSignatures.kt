package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.mapping.TargetMembers

/** GuiEventListener adds horizontal scrolling before the existing vertical delta in 1.20.2. */
internal fun StructuralRefactorPass.migrateGuiScrollSignatures(source: String, index: JavaInheritanceIndex): String {
    if (!source.contains("mouseScrolled")) return source
    fun isGui(type: String): Boolean {
        var current = type.substringBefore('<').substringAfterLast('.')
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            if (TargetMembers.classesNamed(current).any {
                    TargetMembers.inheritsFrom(it, "net.minecraft.client.gui.components.events.GuiEventListener")
                }) return true
            // javap: Ponder 1.0.87's Catnip AbstractSimiScreen extends Screen.
            if (current == "AbstractSimiScreen" && source.contains("net.createmod.catnip.gui.AbstractSimiScreen")) return true
            current = index.directSuperOf(current) ?: return false
        }
        return false
    }
    var result = source
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(result)) {
        if (method.name != "mouseScrolled") continue
        val parameters = callableParameters(result, method.range)
        if (parameters.size != 3 || parameters.any { parseJavaParameter(it)?.first != "double" }) continue
        val owner = enclosingJavaClassDeclaration(result, method.range.first) ?: continue
        if (!isGui(owner.name)) continue
        val span = callableParameterSpan(result, method.range) ?: continue
        val names = parameters.mapNotNull { parseJavaParameter(it)?.second }.toSet()
        var horizontal = "scrollX"
        while (horizontal in names) horizontal += "Horizontal"
        edits += (span.first + 1 until span.second) to
            "${parameters[0].trim()}, ${parameters[1].trim()}, double $horizontal, ${parameters[2].trim()}"
    }
    for ((range, replacement) in edits.sortedByDescending { it.first.first }) result = result.replaceRange(range, replacement)
    result = rewriteExecutableJavaCallWithOffset(result, "mouseScrolled") { receiver, args, offset ->
        if (args.size != 3) return@rewriteExecutableJavaCallWithOffset null
        val masked = maskJavaCommentsAndLiterals(result)
        val type = if (receiver.trim() in setOf("this", "super")) {
            enclosingJavaClassDeclaration(result, offset)?.name
        } else {
            val name = receiver.trim().removePrefix("this.")
            if (!Regex("""[A-Za-z_$][\w$]*""").matches(name)) null
            else JavaScopedDeclarations.visibleAt(masked, name, offset)?.type
        }
        if (type == null || !isGui(type)) return@rewriteExecutableJavaCallWithOffset null
        "$receiver.mouseScrolled(${args[0].trim()}, ${args[1].trim()}, 0, ${args[2].trim()})"
    }
    return result
}
