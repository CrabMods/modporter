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
    result = migrateCustomRenderedMapDecorationInterface(result)
    result = migrateMapDecorationTypedCreations(result)
    result = migrateCreateMapDecorationPacketAssociation(result)
    result = migrateCreateMapRendererMixin(result)
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
        val interfaces = match.groupValues[3]
        val customRendered = Regex("""\bCustomRenderedMapDecoration\b""")
            .containsMatchIn(interfaces)
        val body = result.substring(openBrace + 1, closeBrace)
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
    if (!customRendered) {
        rewritten = replaceExecutableRegex(
            rewritten,
            Regex(
                """(?m)^([ \t]*)@Override\s*\r?\n""" +
                    // other annotations (`@OnlyIn(Dist.CLIENT)`) may sit between
                    """((?:[ \t]*@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s*\r?\n)*""" +
                    """[ \t]*(?:(?:public|protected|private|final|synchronized)\s+)*""" +
                    """boolean\s+render\s*\(\s*int\s+[A-Za-z_$][\w$]*\s*\))"""
            )
        ) { match -> "${match.groupValues[1]}${match.groupValues[2].trimStart(' ', '\t')}" }
    }
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
    val decoration = "new MapDecoration(type(), x(), y(), rot(), name())"
    val returned = if (customRendered) {
        "CustomRenderedMapDecoration.associate($decoration, this)"
    } else {
        decoration
    }
    methods += """
        public MapDecoration asMapDecoration() {
            return $returned;
        }
    """.trimIndent()

    val methodBlock = methods.joinToString("$newline$newline") { method ->
        method.lineSequence().joinToString(newline) { "$memberIndent$it" }
    }
    rewritten = rewritten.trimEnd() + newline + newline + methodBlock + newline
    return LegacyMapDecorationSubclassBody(rewritten, needsMapDecorationTypes)
}

private fun StructuralRefactorPass.migrateCustomRenderedMapDecorationInterface(
    source: String
): String {
    val executable = maskJavaCommentsAndLiterals(source)
    if (!executable.contains("interface CustomRenderedMapDecoration") ||
        executable.contains("Map<MapDecoration, CustomRenderedMapDecoration> ASSOCIATED")
    ) {
        return source
    }
    val declaration = Regex(
        """\binterface\s+CustomRenderedMapDecoration(?:\s+extends\s+[^{]+)?\s*\{"""
    ).find(executable) ?: return source
    val openBrace = executable.indexOf('{', declaration.range.first)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(executable, openBrace) else -1
    if (closeBrace <= openBrace) return source
    val bodyExecutable = executable.substring(openBrace + 1, closeBrace)
    val render = Regex("""\bvoid\s+render\s*\(""").find(bodyExecutable) ?: return source
    val renderOpen = bodyExecutable.indexOf('(', render.range.first)
    val renderClose = if (renderOpen >= 0) findMatchingParen(bodyExecutable, renderOpen) else -1
    if (renderClose < 0 ||
        splitTopLevelJavaArgs(
            source.substring(openBrace + 1 + renderOpen + 1, openBrace + 1 + renderClose)
        ).size != 6
    ) {
        return source
    }

    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val indent = source.substring(openBrace + 1, closeBrace).mapDecorationMemberIndent()
    val additions = """
        Map<MapDecoration, CustomRenderedMapDecoration> ASSOCIATED =
            Collections.synchronizedMap(new WeakHashMap<>());

        boolean renderOnFrame();

        boolean render(int index);

        static MapDecoration associate(
                MapDecoration decoration,
                CustomRenderedMapDecoration renderer) {
            ASSOCIATED.put(decoration, renderer);
            return decoration;
        }

        static CustomRenderedMapDecoration find(MapDecoration decoration) {
            return ASSOCIATED.get(decoration);
        }
    """.trimIndent().lineSequence().joinToString(newline) { "$indent$it" }
    var result = source.substring(0, closeBrace) +
        newline + newline + additions + newline +
        source.substring(closeBrace)
    result = addExecutableImportIfMissing(result, "java.util.Collections")
    result = addExecutableImportIfMissing(result, "java.util.Map")
    result = addExecutableImportIfMissing(result, "java.util.WeakHashMap")
    result = addExecutableImportIfMissing(
        result,
        "net.minecraft.world.level.saveddata.maps.MapDecoration"
    )
    return result
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

private fun StructuralRefactorPass.migrateCreateMapDecorationPacketAssociation(
    source: String
): String {
    val executable = maskJavaCommentsAndLiterals(source)
    val typing = SourceValueTypes(this, source)
    if (!executable.contains("List<MapDecoration>") ||
        !typing.isTypeName("MapDecoration", MAP_DECORATION)
    ) {
        return source
    }
    val rendererType = projectValueFacts.types
        .filter { it.substringAfterLast('.') == "CustomRenderedMapDecoration" }
        .singleOrNull()
        ?: return source
    val wrapperTypes = mutableSetOf<String>()
    val edits = Regex(
        """\b([A-Za-z_$][\w$]*)\s+instanceof\s+""" +
            """((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)\b"""
    ).findAll(executable).mapNotNull { match ->
        val value = match.groupValues[1]
        if (typing.typeOf(value, match.range.first) != MAP_DECORATION) {
            return@mapNotNull null
        }
        val sourceType = match.groupValues[2]
        val canonicalType = typing.typeOf("new $sourceType()", match.range.first)
            ?: return@mapNotNull null
        if (!hasProjectParent(canonicalType, MAP_DECORATION)) return@mapNotNull null
        wrapperTypes += sourceType
        match.range to "CustomRenderedMapDecoration.find($value) != null"
    }.toList()
    if (edits.isEmpty()) return source

    var result = applyStringEdits(source, edits)
    wrapperTypes.forEach { wrapperType ->
        result = appendAsMapDecorationToFactoryCall(result, "$wrapperType.from")
    }
    return addExecutableImportIfMissing(
        result,
        rendererType
    )
}

private fun StructuralRefactorPass.appendAsMapDecorationToFactoryCall(
    source: String,
    factory: String
): String {
    var result = source
    var cursor = 0
    val token = "$factory("
    while (true) {
        val executable = maskJavaCommentsAndLiterals(result)
        val start = executable.indexOf(token, cursor)
        if (start < 0) break
        val openParen = start + token.length - 1
        val closeParen = findMatchingParen(executable, openParen)
        if (closeParen < 0) {
            cursor = start + token.length
            continue
        }
        if (executable.substring(closeParen + 1).trimStart()
                .startsWith(".asMapDecoration()")
        ) {
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

private fun StructuralRefactorPass.migrateCreateMapRendererMixin(source: String): String {
    val executable = maskJavaCommentsAndLiterals(source)
    if (!executable.contains("@Mixin") || executable.contains("@WrapOperation")) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    fun annotationStrings(node: Node, annotationName: String): Set<String> =
        node.findAll(com.github.javaparser.ast.expr.AnnotationExpr::class.java)
            .filter { it.nameAsString == annotationName }
            .flatMap { annotation ->
                annotation.findAll(StringLiteralExpr::class.java).map { it.value }
            }
            .toSet()
    fun lineRange(node: Node): IntRange? = node.range.map {
        val start = lineStarts[it.begin.line - 1]
        var endExclusive =
            lineStarts[it.end.line - 1] + it.end.column
        if (source.getOrNull(endExclusive) == '\r') endExclusive++
        if (source.getOrNull(endExclusive) == '\n') endExclusive++
        start until endExclusive
    }.orElse(null)
    val mixinClass = unit.findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { declaration ->
            declaration.annotations.any { annotation ->
                annotation.nameAsString == "Mixin" &&
                    annotation.findAll(StringLiteralExpr::class.java)
                        .any { it.value == CREATE_MAP_RENDERER_MIXIN_TARGET }
            }
        }
        .singleOrNull()
        ?: return source
    if (mixinClass.findAll(com.github.javaparser.ast.expr.AnnotationExpr::class.java)
            .any { it.nameAsString == "WrapOperation" }
    ) {
        return source
    }
    fun legacyMethod(target: String): MethodDeclaration? =
        mixinClass.methods.filter { method ->
            val groupStrings = annotationStrings(method, "Group")
            val injectStrings = annotationStrings(method, "Inject")
            val methodRange = lineRange(method) ?: return@filter false
            val methodExecutable = maskJavaCommentsAndLiterals(
                source.substring(methodRange.first, methodRange.last + 1)
            )
            CREATE_MAP_RENDERER_GROUP in groupStrings &&
                CREATE_MAP_RENDERER_DRAW_METHOD in injectStrings &&
                target in injectStrings &&
                methodExecutable.contains("instanceof CustomRenderedMapDecoration") &&
                methodExecutable.contains(".render(")
        }.singleOrNull()

    val vanillaMethod = legacyMethod(CREATE_LEGACY_RENDER_TARGET) ?: return source
    val optifineMethod = legacyMethod(CREATE_OPTIFINE_RENDER_TARGET) ?: return source
    val vanilla = lineRange(vanillaMethod) ?: return source
    val optifine = lineRange(optifineMethod) ?: return source
    if (vanilla.first <= optifine.last && optifine.first <= vanilla.last) return source

    var result = applyStringEdits(
        source,
        listOf(vanilla to "", optifine to "")
    )
    val reparsed = parser.parse(result).result.orElse(null) ?: return source
    val resultLineStarts = buildList {
        add(0)
        result.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }
    val migratedMixinClass = reparsed.findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { declaration ->
            declaration.annotations.any { annotation ->
                annotation.nameAsString == "Mixin" &&
                    annotation.findAll(StringLiteralExpr::class.java)
                        .any { it.value == CREATE_MAP_RENDERER_MIXIN_TARGET }
            }
        }
        .singleOrNull()
        ?: return source
    val classClose = migratedMixinClass.range.map {
        resultLineStarts[it.end.line - 1] + it.end.column - 1
    }.orElse(-1)
    if (classClose < 0 || result.getOrNull(classClose) != '}') return source
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val methods = """
        @WrapOperation(
            method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V",
            at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/level/saveddata/maps/MapDecoration;renderOnFrame()Z"
            )
        )
        private boolean create${'$'}renderOnFrame(
                MapDecoration decoration,
                Operation<Boolean> original) {
            CustomRenderedMapDecoration renderer =
                CustomRenderedMapDecoration.find(decoration);
            return renderer != null
                ? renderer.renderOnFrame()
                : original.call(decoration);
        }

        @WrapOperation(
            method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V",
            at = @At(
                value = "INVOKE",
                target = "Lnet/neoforged/neoforge/client/gui/map/MapDecorationRendererManager;render(Lnet/minecraft/world/level/saveddata/maps/MapDecoration;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;Lnet/minecraft/client/resources/MapDecorationTextureManager;ZII)Z"
            )
        )
        private boolean create${'$'}renderCustomDecoration(
                MapDecoration decoration,
                PoseStack poseStack,
                MultiBufferSource bufferSource,
                MapItemSavedData mapData,
                MapDecorationTextureManager decorationTextures,
                boolean active,
                int packedLight,
                int index,
                Operation<Boolean> original) {
            CustomRenderedMapDecoration renderer =
                CustomRenderedMapDecoration.find(decoration);
            if (renderer != null) {
                renderer.render(
                    poseStack,
                    bufferSource,
                    active,
                    packedLight,
                    mapData,
                    index);
                return renderer.render(index);
            }
            return original.call(
                decoration,
                poseStack,
                bufferSource,
                mapData,
                decorationTextures,
                active,
                packedLight,
                index);
        }
    """.trimIndent().lineSequence().joinToString(newline) { "\t$it" }
    result = result.substring(0, classClose) +
        newline + newline + methods + newline +
        result.substring(classClose)
    result = removeUnusedSimpleImports(
        result,
        listOf(
            "java.util.Iterator",
            "org.joml.Matrix4f",
            "org.objectweb.asm.Opcodes",
            "org.spongepowered.asm.mixin.injection.Group",
            "org.spongepowered.asm.mixin.injection.Inject",
            "org.spongepowered.asm.mixin.injection.callback.CallbackInfo",
            "org.spongepowered.asm.mixin.injection.callback.LocalCapture",
            "com.mojang.blaze3d.vertex.VertexConsumer"
        )
    )
    result = addExecutableImportIfMissing(
        result,
        "com.llamalad7.mixinextras.injector.wrapoperation.Operation"
    )
    result = addExecutableImportIfMissing(
        result,
        "com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation"
    )
    result = addExecutableImportIfMissing(
        result,
        "net.minecraft.client.resources.MapDecorationTextureManager"
    )
    return result
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
private const val CREATE_MAP_RENDERER_MIXIN_TARGET =
    "net.minecraft.client.gui.MapRenderer\$MapInstance"
private const val CREATE_MAP_RENDERER_GROUP =
    "custom_decoration_rendering"
private const val CREATE_MAP_RENDERER_DRAW_METHOD =
    "draw(Lcom/mojang/blaze3d/vertex/PoseStack;" +
        "Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V"
private const val CREATE_LEGACY_RENDER_TARGET =
    "Lnet/minecraft/world/level/saveddata/maps/MapDecoration;render(I)Z"
private const val CREATE_OPTIFINE_RENDER_TARGET =
    "net/optifine/reflect/Reflector.ForgeMapDecoration_render:" +
        "Lnet/optifine/reflect/ReflectorMethod;"
