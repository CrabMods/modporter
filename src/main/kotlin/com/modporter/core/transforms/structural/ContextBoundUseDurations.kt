package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.mapping.TargetMembers

/**
 * ItemStack#getUseDuration gained a LivingEntity argument. Use the sole proven in-scope living
 * entity, including project subclasses such as fake players, and leave ambiguous contexts alone.
 */
internal fun StructuralRefactorPass.migrateContextBoundItemStackUseDurations(
    source: String,
    inheritance: JavaInheritanceIndex
): String {
    if (!source.contains(".getUseDuration()")) return source
    var result = source
    result = rewriteExecutableJavaCallWithOffset(result, "getUseDuration") { receiver, args, offset ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCallWithOffset null
        val executable = maskJavaCommentsAndLiterals(result)
        val receiverType = SourceValueTypes(this, result).typeOf(receiver, offset)
            ?: receiver.takeIf { Regex("""(?:this\.)?[A-Za-z_$][\w$]*""").matches(it.trim()) }
                ?.removePrefix("this.")
                ?.let { JavaScopedDeclarations.visibleAt(executable, it, offset)?.type }
        if (receiverType?.substringBefore('<')?.substringAfterLast('.') != "ItemStack") {
            return@rewriteExecutableJavaCallWithOffset null
        }
        val living = singleVisibleLivingEntityValue(executable, offset, inheritance)
            ?: return@rewriteExecutableJavaCallWithOffset null
        "$receiver.getUseDuration($living)"
    }
    return result
}

private fun StructuralRefactorPass.singleVisibleLivingEntityValue(
    executable: String,
    offset: Int,
    inheritance: JavaInheritanceIndex
): String? {
    val method = javaMethodRangesIncludingDefault(executable).firstOrNull { offset in it.range } ?: return null
    val prefix = executable.substring(method.range.first, offset)
    val declarations = Regex(
        """\b([A-Za-z_$][\w$.]*(?:\s*<[^;=(){}]+>)?)\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(prefix)
        .map { it.groupValues[2] }
        .distinct()
        .mapNotNull { name -> JavaScopedDeclarations.visibleAt(executable, name, offset)?.let { name to it.type } }
        .filter { (_, type) -> isLivingEntityType(type, inheritance) }
        .map { it.first }
        .distinct()
        .toList()
    return declarations.singleOrNull()
}

private fun isLivingEntityType(type: String, inheritance: JavaInheritanceIndex): Boolean {
    var current = type.substringBefore('<').trim().substringAfterLast('.')
    val seen = mutableSetOf<String>()
    while (current.isNotBlank() && seen.add(current)) {
        if (current == "LivingEntity") return true
        if (TargetMembers.classesNamed(current).any {
                TargetMembers.inheritsFrom(it, "net.minecraft.world.entity.LivingEntity")
            }) return true
        current = inheritance.directSuperOf(current) ?: return false
    }
    return false
}
