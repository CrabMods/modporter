package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.mapping.TargetMembers

/**
 * Forge 1.20.1's `IForgeItemStack.equals(ItemStack other, boolean limitTags)` is gone. With
 * `limitTags == false` it compared emptiness, count, item and the full tag, which is what 1.21.1's
 * `ItemStack.matches(a, b)` compares (count, item and components). With `true` it compared share
 * tags, which no longer exist on 1.21.1 (no `getShareTag` in the class table: clients receive all
 * components), so both forms become `ItemStack.matches`.
 *
 * Only receivers typed `ItemStack` are rewritten: a name declared `ItemStack` where the call is, or a
 * no-argument call whose every platform declaration returns `ItemStack` and that the file does not
 * declare itself. The flag must be a literal or a name, so dropping it drops no side effect.
 */
internal fun StructuralRefactorPass.migrateForgeItemStackEquality(source: String): String {
    if (!source.contains(".equals(")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    if (!Regex("""\.equals\s*\([^;]*,\s*(?:true|false|[A-Za-z_$][\w$]*)\s*\)""").containsMatchIn(masked)) return source
    return rewriteExecutableJavaCallWithOffset(source, "equals") { receiver, args, offset ->
        if (args.size != 2 || !Regex("""^\s*(?:true|false|[A-Za-z_$][\w$]*)\s*$""").matches(args[1])) return@rewriteExecutableJavaCallWithOffset null
        if (!isItemStackExpression(receiver, offset, source) && !isProjectStackFieldComparedWithStack(receiver, args[0], offset, source)) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        "net.minecraft.world.item.ItemStack.matches(${receiver.trim()}, ${args[0].trim()})"
    }
}

// a field read (`outputs.get(0).stack`) named like an ItemStack field of a project class, compared
// with a name declared ItemStack where the call is
private fun StructuralRefactorPass.isProjectStackFieldComparedWithStack(receiver: String, argument: String, offset: Int, source: String): Boolean {
    val field = Regex("""\.\s*([A-Za-z_$][\w$]*)$""").find(receiver.trim())?.groupValues?.get(1) ?: return false
    if (projectItemStackFields.values.none { field in it }) return false
    return isItemStackExpression(argument, offset, source)
}

private fun StructuralRefactorPass.isItemStackExpression(receiver: String, offset: Int, source: String): Boolean {
    val expression = receiver.trim()
    val masked = maskJavaCommentsAndLiterals(source)
    if (Regex("""^[A-Za-z_$][\w$]*$""").matches(expression)) {
        val type = JavaScopedDeclarations.visibleAt(masked, expression, offset)?.type ?: return false
        return type == "ItemStack" || type == "net.minecraft.world.item.ItemStack"
    }
    // a field read (`outputs.get(0).stack`) whose every declaration in the file is a stack
    Regex("""\.\s*([A-Za-z_$][\w$]*)$""").find(expression)?.let { field ->
        val types = Regex("""\b([A-Z][\w$.]*)(?:<[^;=(){}]*>)?\s+${Regex.escape(field.groupValues[1])}\s*[=;,)]""")
            .findAll(masked).map { it.groupValues[1].substringAfterLast('.') }.toSet()
        return types == setOf("ItemStack")
    }
    val call = Regex("""\.\s*([A-Za-z_$][\w$]*)\s*\(\s*\)$""").find(expression) ?: return false
    val name = call.groupValues[1]
    if (Regex("""\b${Regex.escape(name)}\s*\([^()]*\)\s*(?:throws\s+[^{;]+)?[{;]""").containsMatchIn(masked)) return false
    val returns = TargetMembers.noArgumentReturnTypes(name)
    return returns == setOf("Lnet/minecraft/world/item/ItemStack;")
}
