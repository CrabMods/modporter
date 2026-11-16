package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"

/**
 * Forge 1.20.1's empty ItemStack resolved through AIR, whose maximum stack size is 64; NeoForge
 * 1.21.1 reads the MAX_STACK_SIZE component, which an empty stack lacks, and answers 1. A call on
 * a stack that may be empty therefore keeps the 1.20.1 answer through an empty-aware helper that
 * evaluates the receiver once; non-empty stacks still read their native component.
 */
internal fun StructuralRefactorPass.migrateLegacyEmptyStackMaxSize(source: String): String {
    if (!source.contains(".getMaxStackSize(")) return source
    if ("()I" !in TargetMembers.methodDescriptors(ITEM_STACK, "getMaxStackSize")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = IntArray(source.count { it == '\n' } + 1).also { starts ->
        var line = 1
        source.forEachIndexed { index, character -> if (character == '\n') starts[line++] = index + 1 }
    }
    fun offset(line: Int, column: Int) = lineStarts[line - 1] + column - 1
    // top-level types whose body can own a private static helper (mixins merge members into
    // their target and annotations cannot declare methods, so neither hosts one)
    val owners = unit.types.filter { type ->
        type !is AnnotationDeclaration &&
            type.annotations.none { it.nameAsString == "Mixin" || it.nameAsString.endsWith(".Mixin") }
    }.mapNotNull { type ->
        type.range.orElse(null)?.let { offset(it.begin.line, it.begin.column)..offset(it.end.line, it.end.column) }?.let { type to it }
    }
    if (owners.isEmpty()) return source
    val typing = SourceValueTypes(this, source)
    val names = collectItemStackValueNames(source)
    val stackMethods = collectItemStackReturningMethodNames(source)
    val accessors = collectKnownItemStackAccessorExpressions(source)
    val helper = owners.associate { (type, _) -> type to uniqueHelperName(type) }
    // the helper's own native read stays native
    val helperBodies = unit.findAll(com.github.javaparser.ast.body.MethodDeclaration::class.java)
        .filter { it.nameAsString.startsWith(HELPER_BASE) }
        .mapNotNull { method -> method.range.orElse(null)?.let { offset(it.begin.line, it.begin.column)..offset(it.end.line, it.end.column) } }
    val used = linkedSetOf<TypeDeclaration<*>>()
    val migrated = rewriteJavaCallWithOffset(source, "getMaxStackSize") { receiver, args, callOffset ->
        val stack = receiver.trim()
        if (args.isNotEmpty() || stack.isEmpty() || stack == "this" || stack == "super") return@rewriteJavaCallWithOffset null
        if (helperBodies.any { callOffset in it }) return@rewriteJavaCallWithOffset null
        val owner = owners.firstOrNull { (_, range) -> callOffset in range }?.first ?: return@rewriteJavaCallWithOffset null
        val isStack = typing.typeOf(stack, callOffset) == ITEM_STACK ||
            isItemStackExpression(stack, names, stackMethods, accessors)
        if (!isStack) return@rewriteJavaCallWithOffset null
        used += owner
        "${helper.getValue(owner)}($stack)"
    }
    if (used.isEmpty()) return source
    // insert each helper before the closing brace of its owner, last owner first
    var result = migrated
    val reparsed = parser.parse(result).result.orElse(null) ?: return source
    val reparsedStarts = IntArray(result.count { it == '\n' } + 1).also { starts ->
        var line = 1
        result.forEachIndexed { index, character -> if (character == '\n') starts[line++] = index + 1 }
    }
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    reparsed.types.filter { type -> used.any { it.nameAsString == type.nameAsString } }
        .filter { type -> type.methods.none { it.nameAsString == helper.entries.first { entry -> entry.key.nameAsString == type.nameAsString }.value } }
        .mapNotNull { type -> type.range.orElse(null)?.let { type to (reparsedStarts[it.end.line - 1] + it.end.column - 1) } }
        .sortedByDescending { it.second }
        .forEach { (type, close) ->
            val name = helper.entries.first { it.key.nameAsString == type.nameAsString }.value
            val body = "$newline    private static int $name(net.minecraft.world.item.ItemStack stack) {$newline" +
                "        return stack.isEmpty() ? 64 : stack.getMaxStackSize();$newline    }$newline"
            result = result.substring(0, close) + body + result.substring(close)
        }
    return result
}

private const val HELPER_BASE = "modporterLegacyMaxStackSize"

/** The helper name for [type]: an existing helper of the same shape is reused, a clashing member is avoided. */
private fun uniqueHelperName(type: TypeDeclaration<*>): String {
    var name = HELPER_BASE
    while (true) {
        val existing = type.methods.filter { it.nameAsString == name }
        if (existing.isEmpty() && !Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(type.toString())) return name
        if (existing.size == 1 && existing.single().isStatic && existing.single().parameters.size == 1 &&
            existing.single().body.map { it.toString().contains("stack.isEmpty() ? 64 : stack.getMaxStackSize()") }.orElse(false)
        ) {
            return name
        }
        name += "_"
    }
}

/**
 * `Commands#performPrefixedCommand` and `performCommand` returned the command's result in 1.20.1
 * and return nothing in 1.21.1, where the result reaches the source's result callback instead. A
 * returned or int-assigned result is captured through `withCallback` on the same source; the
 * command, its source and their evaluation order are unchanged.
 */
internal fun StructuralRefactorPass.migrateLegacyCommandResults(source: String): String {
    if (!source.contains("performPrefixedCommand(") && !source.contains("performCommand(")) return source
    if ("(Lnet/minecraft/commands/CommandResultCallback;)Lnet/minecraft/commands/CommandSourceStack;" !in
        TargetMembers.methodDescriptors("net.minecraft.commands.CommandSourceStack", "withCallback") ||
        "(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V" !in
        TargetMembers.methodDescriptors("net.minecraft.commands.Commands", "performPrefixedCommand")
    ) {
        return source
    }
    val masked = maskJavaCommentsAndLiterals(source)
    val typing = SourceValueTypes(this, source)
    val statement = Regex(
        """(?m)^([ \t]*)(return\s+|int\s+([A-Za-z_$][\w$]*)\s*=\s*)((?:[^;{}]|\([^;]*\))*?)\.\s*performPrefixedCommand\s*\("""
    )
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (match in statement.findAll(masked)) {
        val open = match.range.last
        val close = JavaSourceText.matchingParen(masked, open)
        if (close < 0) continue
        val semicolon = masked.indexOf(';', close)
        if (semicolon < 0 || masked.substring(close + 1, semicolon).isNotBlank()) continue
        val receiverStart = match.groups[4]!!.range.first
        val receiver = source.substring(receiverStart, match.range.last - "performPrefixedCommand(".length).trim().removeSuffix(".").trim()
        // the receiver is a Commands value: typed in source, or a platform member chain the 1.21.1
        // class table resolves to Commands (`player.server.getCommands()`)
        val isCommands = typing.typeOf(receiver, receiverStart) == "net.minecraft.commands.Commands" ||
            platformChainType(receiver, receiverStart, typing) == "net.minecraft.commands.Commands"
        if (!isCommands) continue
        val args = JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close)).map { it.trim() }
        if (args.size != 2) continue
        val indent = match.groupValues[1]
        var result = "modporterCommandResult"
        while (Regex("""\b${Regex.escape(result)}\b""").containsMatchIn(masked)) result += "_"
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val capture = "${indent}int[] $result = {0};$newline" +
            "$indent$receiver.performPrefixedCommand((${args[0]}).withCallback((modporterSuccess, modporterValue) -> $result[0] = modporterValue), ${args[1]});$newline"
        val tail = if (match.groupValues[2].startsWith("return")) {
            "${indent}return $result[0];"
        } else {
            "${indent}int ${match.groupValues[3]} = $result[0];"
        }
        edits += match.range.first..semicolon to capture + tail
    }
    if (edits.isEmpty()) return source
    return edits.sortedByDescending { it.first.first }.fold(source) { text, (range, replacement) ->
        text.replaceRange(range, replacement)
    }
}

/**
 * The 1.21.1 type of a `name.member.member()` chain: the first name from the source typing, then
 * each field or no-argument method from the target class table, searched through supertypes.
 */
internal fun platformChainType(expression: String, offset: Int, typing: SourceValueTypes): String? {
    val segments = expression.split('.').map { it.trim() }
    if (segments.size < 2 || segments.any { it.isEmpty() }) return null
    var owner = typing.typeOf(segments.first(), offset) ?: return null
    for (segment in segments.drop(1)) {
        val method = Regex("""^([A-Za-z_$][\w$]*)\(\s*\)$""").find(segment)?.groupValues?.get(1)
        val name = method ?: segment.takeIf { Regex("""^[A-Za-z_$][\w$]*$""").matches(it) } ?: return null
        val descriptor = platformMemberDescriptor(owner, name, isMethod = method != null) ?: return null
        val returned = if (method != null) descriptor.substringAfterLast(')') else descriptor
        owner = Regex("""^L([\w/$]+);$""").find(returned)?.groupValues?.get(1)?.replace('/', '.') ?: return null
    }
    return owner
}

private fun platformMemberDescriptor(owner: String, name: String, isMethod: Boolean): String? {
    val seen = HashSet<String>()
    val frontier = ArrayDeque(listOf(owner))
    while (frontier.isNotEmpty()) {
        val current = frontier.removeFirst()
        if (!seen.add(current)) continue
        val found = if (isMethod) {
            TargetMembers.methodDescriptors(current, name).singleOrNull { it.startsWith("()") }
        } else {
            TargetMembers.fieldDescriptor(current, name)
        }
        if (found != null) return found
        frontier += TargetMembers.supertypesOf(current)
    }
    return null
}
