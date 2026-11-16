package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr

/**
 * Migrates the final-record MapDecoration surface without guessing the type of arbitrary
 * getX/getName/getType receivers. Each receiver must resolve to the canonical source type at
 * that exact call offset.
 */
internal fun StructuralRefactorPass.migrateLegacyMapDecorationRecordContracts(
    source: String
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("MapDecoration")) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    fun offset(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)
    fun range(node: Node): IntRange? = node.range.map {
        val start = lineStarts[it.begin.line - 1] + it.begin.column - 1
        val end = lineStarts[it.end.line - 1] + it.end.column - 1
        start..end
    }.orElse(null)
    fun isOptionalExpression(expression: Node): Boolean {
        val expressionOffset = offset(expression)
        if (expressionOffset < 0) return false
        if (typing.typeOf(expression.toString(), expressionOffset) == JAVA_OPTIONAL) {
            return true
        }
        val call = expression as? MethodCallExpr ?: return false
        val scope = call.scope.orElse(null)
        if (scope != null &&
            typing.isTypeName(scope.toString(), JAVA_OPTIONAL) &&
            call.nameAsString in setOf("empty", "of", "ofNullable")
        ) {
            return true
        }
        if (call.arguments.isNotEmpty() || call.nameAsString != "name") return false
        if (scope != null && scope !is ThisExpr) {
            return typing.typeOf(scope.toString(), offset(scope)) == MAP_DECORATION
        }
        val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: return false
        return owner.methods.any { method ->
            method.nameAsString == "name" &&
                method.parameters.isEmpty() &&
                typing.isTypeName(
                    method.typeAsString.substringBefore('<').trim(),
                    JAVA_OPTIONAL
                )
        }
    }

    val edits = unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
        if (call.arguments.isNotEmpty() || call.typeArguments.isPresent) return@mapNotNull null
        val receiver = when (call.nameAsString) {
            "shouldTrackCount" -> {
                val getType = call.scope.orElse(null) as? MethodCallExpr
                    ?: return@mapNotNull null
                if (getType.nameAsString != "getType" ||
                    getType.arguments.isNotEmpty() ||
                    getType.typeArguments.isPresent
                ) {
                    return@mapNotNull null
                }
                getType.scope.orElse(null) ?: return@mapNotNull null
            }
            "getX", "getY", "getRot", "getName", "getType" ->
                call.scope.orElse(null) ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        if (call.nameAsString == "getType") {
            val parent = call.parentNode.orElse(null) as? MethodCallExpr
            if (parent?.scope?.orElse(null) === call &&
                parent?.nameAsString == "shouldTrackCount"
            ) {
                return@mapNotNull null
            }
        }
        val receiverOffset = offset(receiver)
        if (receiverOffset < 0 ||
            typing.typeOf(receiver.toString(), receiverOffset) != MAP_DECORATION
        ) {
            return@mapNotNull null
        }
        val replacement = when (call.nameAsString) {
            "shouldTrackCount" -> "$receiver.type().value().trackCount()"
            "getX" -> "$receiver.x()"
            "getY" -> "$receiver.y()"
            "getRot" -> "$receiver.rot()"
            "getName" -> "$receiver.name().orElse(null)"
            "getType" -> "$receiver.type()"
            else -> return@mapNotNull null
        }
        range(call)?.let { it to replacement }
    }.toMutableList()

    val canonicalSimpleName = typing.isTypeName("MapDecoration", MAP_DECORATION)
    if (canonicalSimpleName) {
        unit.findAll(ObjectCreationExpr::class.java).forEach { creation ->
            if (!typing.isTypeName(creation.typeAsString, MAP_DECORATION) ||
                creation.arguments.size != 5
            ) {
                return@forEach
            }
            val legacyName = creation.arguments[4]
            if (isOptionalExpression(legacyName)) return@forEach
            val replacement = when {
                legacyName.isNullLiteralExpr -> "java.util.Optional.empty()"
                legacyName is MethodCallExpr &&
                    legacyName.nameAsString == "getName" &&
                    legacyName.arguments.isEmpty() &&
                    legacyName.scope.isPresent &&
                    typing.typeOf(
                        legacyName.scope.get().toString(),
                        offset(legacyName.scope.get())
                    ) == MAP_DECORATION ->
                    "java.util.Optional.ofNullable(" +
                        "${legacyName.scope.get()}.name().orElse(null))"
                legacyName is MethodCallExpr &&
                    legacyName.scope.isPresent &&
                    typing.isTypeName(
                        legacyName.scope.get().toString(),
                        MINECRAFT_COMPONENT
                    ) -> "java.util.Optional.of($legacyName)"
                typing.typeOf(legacyName.toString(), offset(legacyName)) ==
                    MINECRAFT_COMPONENT ->
                    "java.util.Optional.ofNullable($legacyName)"
                else -> return@forEach
            }
            range(legacyName)?.let { argumentRange ->
                edits.removeAll { (editRange, _) ->
                    editRange.first >= argumentRange.first &&
                        editRange.last <= argumentRange.last
                }
                edits += argumentRange to replacement
            }
        }
    }
    var result = applyStringEdits(source, edits.distinctBy { it.first })

    var needsMapDecorationTypes = false
    var needsHolder = false
    var needsMapDecorationType = false

    if (canonicalSimpleName) {
        val beforeConstants = result
        result = replaceExecutableRegex(
            result,
            Regex("""\bMapDecoration\.Type\.""")
        ) { "MapDecorationTypes." }
        needsMapDecorationTypes = result != beforeConstants

        val beforeDeclarations = result
        result = replaceExecutableRegex(
            result,
            Regex("""\bMapDecoration\.Type\b""")
        ) { "Holder<MapDecorationType>" }
        if (result != beforeDeclarations) {
            needsHolder = true
            needsMapDecorationType = true
        }
    }

    if (needsMapDecorationTypes) {
        result = addExecutableImportIfMissing(
            result,
            "net.minecraft.world.level.saveddata.maps.MapDecorationTypes"
        )
    }
    if (needsHolder) {
        result = addExecutableImportIfMissing(result, "net.minecraft.core.Holder")
    }
    if (needsMapDecorationType) {
        result = addExecutableImportIfMissing(
            result,
            "net.minecraft.world.level.saveddata.maps.MapDecorationType"
        )
    }
    return result
}

/**
 * Owns the final MapDecoration composition seam after the ordinary record-access migration.
 *
 * Supported legacy subclasses have a direct five-argument super constructor and no other
 * superclass calls. Unsupported constructor and behavior shapes remain byte-identical.
 */
internal fun StructuralRefactorPass.migrateLegacyMapDecorationCompositionSource(
    source: String
): String {
    val executable = maskJavaCommentsAndLiterals(source)
    var result = migrateLegacyMapDecorationRecordContracts(source)
    if (executable.contains("extends MapDecoration")) {
        result = migrateMapDecorationSubclassComposition(result)
        if (maskJavaCommentsAndLiterals(result).contains("extends MapDecoration")) {
            return source
        }
    } else {
        result = migrateMapDecorationSubclassComposition(result)
    }
    result = migrateMapDecorationTypedCreations(result)
    return result
}

private data class LegacyMapDecorationSubclassBody(
    val source: String,
    val needsMapDecorationTypes: Boolean
)

private fun StructuralRefactorPass.migrateMapDecorationSubclassComposition(
    source: String
): String {
    val initialExecutable = maskJavaCommentsAndLiterals(source)
    val typing = SourceValueTypes(this, source)
    if (!initialExecutable.contains("extends MapDecoration") ||
        !typing.isTypeName("MapDecoration", MAP_DECORATION)
    ) {
        return source
    }
    val inheritedTypeSimpleNameIsUnambiguous =
        !Regex("""(?m)^\s*import\s+(?!static\b)[\w.]+\.Type\s*;""")
            .containsMatchIn(initialExecutable) &&
            !Regex("""\b(?:class|interface|enum|record)\s+Type\b""")
                .containsMatchIn(initialExecutable)

    var result = source
    var cursor = 0
    var changed = false
    var needsMapDecorationTypes = false
    val declaration = Regex(
        """(?m)^([ \t]*(?:(?:public|protected|private|static|final|abstract)\s+)*""" +
            """class\s+([A-Za-z_$][\w$]*))\s+extends\s+MapDecoration""" +
            """(\s+implements\s+[^{]+)?\s*\{"""
    )
    while (true) {
        val executable = maskJavaCommentsAndLiterals(result)
        val match = declaration.find(executable, cursor) ?: break
        val openBrace = executable.indexOf('{', match.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executable, openBrace) else -1
        if (closeBrace <= openBrace) {
            cursor = match.range.last + 1
            continue
        }

        val className = match.groupValues[2]
        val body = result.substring(openBrace + 1, closeBrace)
        val bodyExecutable = executable.substring(openBrace + 1, closeBrace)
        val customRendered = LEGACY_CUSTOM_RENDER.findAll(bodyExecutable)
            .any { mapDecorationBraceDepth(bodyExecutable, it.range.first) == 0 }
        val rewritten = rewriteLegacyMapDecorationSubclassBody(
            className,
            body,
            customRendered,
            inheritedTypeSimpleNameIsUnambiguous
        )
        if (rewritten == null) {
            cursor = closeBrace + 1
            continue
        }

        val oldHeader = result.substring(match.range.first, openBrace)
        val newHeader = Regex("""\s+extends\s+MapDecoration\b""")
            .replaceFirst(oldHeader, "")
            .trimEnd() + " "
        val replacement = newHeader + "{" + rewritten.source + "}"
        result = result.substring(0, match.range.first) +
            replacement +
            result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
        changed = true
        needsMapDecorationTypes = needsMapDecorationTypes || rewritten.needsMapDecorationTypes
    }
    if (!changed) return source

    result = addExecutableImportIfMissing(result, "net.minecraft.core.Holder")
    result = addExecutableImportIfMissing(result, "java.util.Optional")
    result = addExecutableImportIfMissing(result, "net.minecraft.network.chat.Component")
    result = addExecutableImportIfMissing(
        result,
        "net.minecraft.world.level.saveddata.maps.MapDecorationType"
    )
    if (needsMapDecorationTypes) {
        result = addExecutableImportIfMissing(
            result,
            "net.minecraft.world.level.saveddata.maps.MapDecorationTypes"
        )
    }
    return result
}

private fun StructuralRefactorPass.rewriteLegacyMapDecorationSubclassBody(
    className: String,
    body: String,
    customRendered: Boolean,
    inheritedTypeSimpleNameIsUnambiguous: Boolean
): LegacyMapDecorationSubclassBody? {
    val executable = maskJavaCommentsAndLiterals(body)
    val generatedNames = listOf(
        "modporter\$type",
        "modporter\$x",
        "modporter\$y",
        "modporter\$rot",
        "modporter\$name",
        "modporter\$decoration",
        "modporter\$legacyEquals",
        "modporter\$legacyHashCode"
    )
    if (generatedNames.any {
            Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(executable)
        }
    ) {
        return null
    }
    // Only the inherited value contract may be called through super: equals(Object) and
    // hashCode() are rewritten over the composed fields below.
    val superCalls = Regex("""\bsuper\s*\.\s*([A-Za-z_$][\w$]*)\s*\(""").findAll(executable).toList()
    if (superCalls.size != Regex("""\bsuper\s*\.""").findAll(executable).count()) return null
    for (call in superCalls) {
        val close = findMatchingParen(executable, call.range.last)
        if (close < call.range.last) return null
        val arguments = executable.substring(call.range.last + 1, close)
        val supported = when (call.groupValues[1]) {
            "equals" -> splitTopLevelJavaArgs(arguments).size == 1
            "hashCode" -> arguments.isBlank()
            else -> false
        }
        if (!supported) return null
    }
    val usesSuperEquals = superCalls.any { it.groupValues[1] == "equals" }
    val usesSuperHashCode = superCalls.any { it.groupValues[1] == "hashCode" }
    if (!customRendered && hasTopLevelMapDecorationMethod(executable, "renderOnFrame")) {
        return null
    }
    if (!customRendered && hasTopLevelMapDecorationMethod(executable, "render")) {
        val legacyRenderDeclarations = Regex(
            """(?m)^[ \t]*(?:@Override\s*\r?\n[ \t]*)?""" +
                """(?:(?:public|protected|private|final|synchronized)\s+)*""" +
                """boolean\s+render\s*\(\s*int\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
        ).findAll(executable).filter {
            mapDecorationBraceDepth(executable, it.range.first) == 0
        }.toList()
        val topLevelRenderCalls = Regex("""\brender\s*\(""")
            .findAll(executable)
            .filter { mapDecorationBraceDepth(executable, it.range.first) == 0 }
            .toList()
        if (legacyRenderDeclarations.isEmpty() ||
            topLevelRenderCalls.any { call ->
                legacyRenderDeclarations.none { call.range.first in it.range }
            }
        ) {
            return null
        }
    }
    if (listOf("type", "x", "y", "rot", "name", "asMapDecoration").any {
            hasTopLevelMapDecorationMethod(executable, it)
        }
    ) {
        return null
    }

    val constructorPattern = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private)\s+)?""" +
            """${Regex.escape(className)}\s*\("""
    )
    val constructors = constructorPattern.findAll(executable)
        .filter { mapDecorationBraceDepth(executable, it.range.first) == 0 }
        .toList()
    if (constructors.isEmpty()) return null
    if (customRendered && !rebuildableFromMapDecoration(className, body)) return null

    val edits = mutableListOf<Pair<IntRange, String>>()
    var needsMapDecorationTypes = false
    var superConstructors = 0
    for (constructor in constructors) {
        val parameterOpen = executable.indexOf('(', constructor.range.first)
        val parameterClose = if (parameterOpen >= 0) {
            findMatchingParen(executable, parameterOpen)
        } else {
            -1
        }
        val bodyOpen = if (parameterClose >= 0) {
            executable.indexOf('{', parameterClose)
        } else {
            -1
        }
        val bodyClose = if (bodyOpen >= 0) findMatchingBrace(executable, bodyOpen) else -1
        if (parameterClose < 0 || bodyOpen < 0 || bodyClose <= bodyOpen) return null
        if (executable.substring(parameterClose + 1, bodyOpen).contains(';')) return null

        val superStart = executable.indexOfFirstNonWhitespace(bodyOpen + 1)
        // A constructor delegating with this(...) reaches a super-calling constructor below.
        if (superStart >= 0 && executable.startsWith("this", superStart) &&
            executable.indexOfFirstNonWhitespace(superStart + "this".length).let {
                it >= 0 && executable[it] == '('
            }
        ) {
            continue
        }
        if (superStart < 0 || !executable.startsWith("super", superStart)) return null
        superConstructors++
        val superOpen = executable.indexOf('(', superStart + "super".length)
        val superClose = if (superOpen >= 0) findMatchingParen(executable, superOpen) else -1
        val semicolon = if (superClose >= 0) executable.indexOf(';', superClose) else -1
        if (superOpen < 0 || superClose < 0 || semicolon < 0 || semicolon >= bodyClose) {
            return null
        }
        if (executable.substring(superClose + 1, semicolon).isNotBlank()) return null

        val args = splitTopLevelJavaArgs(body.substring(superOpen + 1, superClose))
        if (args.size != 5) return null
        var type = args[0].trim()
        if (Regex("""^Type\.[A-Za-z_$][\w$]*$""").matches(type)) {
            if (!inheritedTypeSimpleNameIsUnambiguous) return null
            type = "MapDecorationTypes." + type.substringAfter("Type.")
            needsMapDecorationTypes = true
        }
        val legacyName = args[4].trim()
        val name = when {
            legacyName == "null" -> "Optional.empty()"
            legacyName.startsWith("java.util.Optional.") ||
                legacyName.startsWith("Optional.") -> legacyName
            legacyName.startsWith("Component.") -> "Optional.of($legacyName)"
            else -> "Optional.ofNullable($legacyName)"
        }
        val indent = body.lineIndentAt(superStart)
        val replacement = listOf(
            "MapDecoration modporter\$decoration = new MapDecoration(" +
                "$type, ${args[1].trim()}, ${args[2].trim()}, ${args[3].trim()}, $name);",
            "this.modporter\$type = modporter\$decoration.type();",
            "this.modporter\$x = modporter\$decoration.x();",
            "this.modporter\$y = modporter\$decoration.y();",
            "this.modporter\$rot = modporter\$decoration.rot();",
            "this.modporter\$name = modporter\$decoration.name();"
        ).joinToString("\n$indent")
        edits += (superStart..semicolon) to replacement
    }

    if (superConstructors == 0) return null
    var rewritten = applyStringEdits(body, edits)
    rewritten = replaceExecutableRegex(rewritten, Regex("""\bsuper\s*\.\s*equals\s*\(""")) {
        "this.modporter\$legacyEquals("
    }
    rewritten = replaceExecutableRegex(rewritten, Regex("""\bsuper\s*\.\s*hashCode\s*\(\s*\)""")) {
        "this.modporter\$legacyHashCode()"
    }
    rewritten = replaceExecutableRegex(
        rewritten,
        Regex(
            """(?m)^([ \t]*)@Override\s*\r?\n""" +
                // other annotations (`@OnlyIn(Dist.CLIENT)`) may sit between
                """((?:[ \t]*@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s*\r?\n)*""" +
                """[ \t]*(?:(?:public|protected|private|final|synchronized)\s+)*""" +
                """boolean\s+(?:render\s*\(\s*int\s+[A-Za-z_$][\w$]*\s*\)|renderOnFrame\s*\(\s*\)))"""
        )
    ) { match -> "${match.groupValues[1]}${match.groupValues[2].trimStart(' ', '\t')}" }
    val newline = if (body.contains("\r\n")) "\r\n" else "\n"
    val memberIndent = body.mapDecorationMemberIndent()
    val fields = listOf(
        "private final Holder<MapDecorationType> modporter\$type;",
        "private final byte modporter\$x;",
        "private final byte modporter\$y;",
        "private final byte modporter\$rot;",
        "private final Optional<Component> modporter\$name;"
    ).joinToString(newline) { "$memberIndent$it" }
    rewritten = newline + fields + newline +
        rewritten.trimStart('\r', '\n')

    val existing = maskJavaCommentsAndLiterals(rewritten)
    val methods = mutableListOf<String>()
    methods += """
        public Holder<MapDecorationType> type() {
            return this.modporter${'$'}type;
        }
    """.trimIndent()
    methods += """
        public byte x() {
            return this.modporter${'$'}x;
        }
    """.trimIndent()
    methods += """
        public byte y() {
            return this.modporter${'$'}y;
        }
    """.trimIndent()
    methods += """
        public byte rot() {
            return this.modporter${'$'}rot;
        }
    """.trimIndent()
    methods += """
        public Optional<Component> name() {
            return this.modporter${'$'}name;
        }
    """.trimIndent()
    if (!hasTopLevelMapDecorationMethod(existing, "getX")) {
        methods += """
            public byte getX() {
                return this.x();
            }
        """.trimIndent()
    }
    if (!hasTopLevelMapDecorationMethod(existing, "getY")) {
        methods += """
            public byte getY() {
                return this.y();
            }
        """.trimIndent()
    }
    if (!hasTopLevelMapDecorationMethod(existing, "getRot")) {
        methods += """
            public byte getRot() {
                return this.rot();
            }
        """.trimIndent()
    }
    if (!hasTopLevelMapDecorationMethod(existing, "getName")) {
        methods += """
            public Component getName() {
                return this.name().orElse(null);
            }
        """.trimIndent()
    }
    if (usesSuperEquals) {
        // 1.20.1 MapDecoration#equals: identity, then type, rot, x, y and Objects.equals(name).
        // Only instances of this class remain comparable: a 1.21 MapDecoration record is a
        // different class whose own equals would not answer symmetrically.
        methods += """
            private boolean modporter${'$'}legacyEquals(Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof $className decoration)) {
                    return false;
                }
                return this.modporter${'$'}type.equals(decoration.modporter${'$'}type)
                    && this.modporter${'$'}rot == decoration.modporter${'$'}rot
                    && this.modporter${'$'}x == decoration.modporter${'$'}x
                    && this.modporter${'$'}y == decoration.modporter${'$'}y
                    && this.modporter${'$'}name.equals(decoration.modporter${'$'}name);
            }
        """.trimIndent()
    }
    if (usesSuperHashCode) {
        // 1.20.1 folded the type's icon byte, which 1.21 MapDecorationType no longer has; the
        // holder's hash keeps the contract consistent with the equality above.
        methods += """
            private int modporter${'$'}legacyHashCode() {
                int hash = this.modporter${'$'}type.hashCode();
                hash = 31 * hash + this.modporter${'$'}x;
                hash = 31 * hash + this.modporter${'$'}y;
                hash = 31 * hash + this.modporter${'$'}rot;
                return 31 * hash + java.util.Objects.hashCode(this.modporter${'$'}name.orElse(null));
            }
        """.trimIndent()
    }
    methods += """
        public MapDecoration asMapDecoration() {
            return new MapDecoration(type(), x(), y(), rot(), name());
        }
    """.trimIndent()
    if (customRendered) {
        // 1.21.1 hands a renderer the MapDecoration record; the class's own render code runs on the
        // instance that record describes (it holds no state beyond the record's five values)
        methods += """
            private $className(MapDecoration modporter${'$'}decoration) {
                this.modporter${'$'}type = modporter${'$'}decoration.type();
                this.modporter${'$'}x = modporter${'$'}decoration.x();
                this.modporter${'$'}y = modporter${'$'}decoration.y();
                this.modporter${'$'}rot = modporter${'$'}decoration.rot();
                this.modporter${'$'}name = modporter${'$'}decoration.name();
            }
        """.trimIndent()
        methods += """
            public static $className $FROM_DECORATION(MapDecoration decoration) {
                return new $className(decoration);
            }
        """.trimIndent()
    }

    val methodBlock = methods.joinToString("$newline$newline") { method ->
        method.lineSequence().joinToString(newline) { "$memberIndent$it" }
    }
    rewritten = rewritten.trimEnd() + newline + newline + methodBlock + newline
    return LegacyMapDecorationSubclassBody(rewritten, needsMapDecorationTypes)
}

private fun StructuralRefactorPass.migrateMapDecorationTypedCreations(
    source: String
): String {
    val typing = SourceValueTypes(this, source)
    if (!typing.isTypeName("MapDecoration", MAP_DECORATION)) return source
    var result = source
    var cursor = 0
    val creation = Regex(
        """\bMapDecoration\s+[A-Za-z_$][\w$]*\s*=\s*new\s+""" +
            """((?!MapDecoration\b)[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\("""
    )
    while (true) {
        val executable = maskJavaCommentsAndLiterals(result)
        val match = creation.find(executable, cursor) ?: break
        val createdType = typing.typeOf(
            "new ${match.groupValues[1]}()",
            match.range.first
        )
        val sourceProvenSubclass = createdType != null &&
            hasProjectParent(createdType, MAP_DECORATION)
        if (!sourceProvenSubclass) {
            cursor = match.range.last + 1
            continue
        }
        val openParen = executable.indexOf('(', match.range.first)
        val closeParen = if (openParen >= 0) findMatchingParen(executable, openParen) else -1
        if (closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val suffix = executable.substring(closeParen + 1)
            .trimStart()
        if (suffix.startsWith(".asMapDecoration()")) {
            cursor = closeParen + 1
            continue
        }
        result = result.substring(0, closeParen + 1) +
            ".asMapDecoration()" +
            result.substring(closeParen + 1)
        cursor = closeParen + ".asMapDecoration()".length + 1
    }
    return result
}

private fun StructuralRefactorPass.hasProjectParent(
    type: String,
    expectedParent: String
): Boolean {
    var current = type
    val seen = mutableSetOf<String>()
    while (seen.add(current)) {
        current = projectValueFacts.parents[current] ?: return false
        if (current == expectedParent) return true
    }
    return false
}

private fun hasTopLevelMapDecorationMethod(executableBody: String, name: String): Boolean =
    Regex("""\b${Regex.escape(name)}\s*\(""")
        .findAll(executableBody)
        .any { mapDecorationBraceDepth(executableBody, it.range.first) == 0 }

private fun mapDecorationBraceDepth(source: String, offset: Int): Int {
    var depth = 0
    for (index in 0 until offset.coerceAtMost(source.length)) {
        when (source[index]) {
            '{' -> depth++
            '}' -> depth--
        }
    }
    return depth
}

private fun String.indexOfFirstNonWhitespace(start: Int): Int {
    for (index in start until length) {
        if (!this[index].isWhitespace()) return index
    }
    return -1
}

private fun String.lineIndentAt(offset: Int): String {
    val lineStart = lastIndexOf('\n', offset.coerceAtMost(length) - 1)
        .let { if (it < 0) 0 else it + 1 }
    return substring(lineStart, offset)
        .takeWhile { it == ' ' || it == '\t' }
}

private fun String.mapDecorationMemberIndent(): String =
    lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.takeWhile { it == ' ' || it == '\t' }
        ?.takeIf { it.isNotEmpty() }
        ?: "\t"

private const val MAP_DECORATION =
    "net.minecraft.world.level.saveddata.maps.MapDecoration"
private const val MINECRAFT_COMPONENT =
    "net.minecraft.network.chat.Component"
private const val JAVA_OPTIONAL =
    "java.util.Optional"

/** The factory a custom-rendered decoration class gains: the instance a 1.21.1 MapDecoration record describes. */
internal const val FROM_DECORATION = "modporter\$fromDecoration"

/**
 * 1.20.1 custom map decoration rendering: `void render(PoseStack, MultiBufferSource, boolean active,
 * int packedLight, MapItemSavedData, int index)`, the call a MapRenderer mixin made for each decoration.
 */
internal val LEGACY_CUSTOM_RENDER = Regex(
    """\bvoid\s+render\s*\(\s*(?:final\s+)?(?:com\.mojang\.blaze3d\.vertex\.)?PoseStack\s+[A-Za-z_$][\w$]*\s*,""" +
        """\s*(?:final\s+)?(?:net\.minecraft\.client\.renderer\.)?MultiBufferSource\s+[A-Za-z_$][\w$]*\s*,""" +
        """\s*(?:final\s+)?boolean\s+[A-Za-z_$][\w$]*\s*,\s*(?:final\s+)?int\s+[A-Za-z_$][\w$]*\s*,""" +
        """\s*(?:final\s+)?(?:net\.minecraft\.world\.level\.saveddata\.maps\.)?MapItemSavedData\s+[A-Za-z_$][\w$]*\s*,""" +
        """\s*(?:final\s+)?int\s+[A-Za-z_$][\w$]*\s*\)"""
)

/**
 * Whether an instance of [className] is fully described by the five MapDecoration values: it declares
 * no instance fields of its own, and no one-argument constructor the record constructor would overload.
 */
private fun rebuildableFromMapDecoration(className: String, body: String): Boolean {
    val parsed = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        .parse("class $className {\n$body\n}")
    val type = parsed.result.orElse(null)?.types?.singleOrNull() ?: return false
    if (!parsed.isSuccessful) return false
    if (type.fields.any { !it.isStatic }) return false
    return type.constructors.none { it.parameters.size == 1 }
}
