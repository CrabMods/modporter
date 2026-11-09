package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaSourceText

/**
 * Overrides a `Level` subclass declares for 1.20.1's abstract map-data and game-event members take
 * their 1.21.1 signatures (member table of 1.21.1): maps are keyed by `MapId` instead of a
 * `String`/`int`, and `gameEvent` receives a `Holder<GameEvent>`. A signature changes only when its
 * body cannot tell the difference: the changed parameter is unused or only handed on to the same
 * member, and `getFreeMapId` returns a literal (wrapped as `new MapId(n)`) or another level's id.
 */
internal object LevelSubclassOverrides {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val mapData = Regex("""\b(public\s+MapItemSavedData\s+getMapData\s*\(\s*)String(\s+($ID)\s*\))""")
    private val setMapData = Regex("""\b(public\s+void\s+setMapData\s*\(\s*)String(\s+($ID)\s*,\s*MapItemSavedData\s+$ID\s*\))""")
    private val freeMapId = Regex("""\bpublic\s+int(\s+getFreeMapId\s*\(\s*\))""")
    private val gameEvent = Regex("""\b(public\s+void\s+gameEvent\s*\(\s*)GameEvent(\s+($ID)\s*,\s*(?:Vec3|BlockPos)\s+$ID\s*,\s*(?:GameEvent\.)?Context\s+$ID\s*\))""")

    fun migrate(source: String, extendsLevel: (String) -> Boolean): String {
        if (!source.contains("getMapData") && !source.contains("getFreeMapId") && !source.contains("gameEvent")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val owner = Regex("""\bclass\s+$ID(?:<[^{]*?>)?\s+extends\s+($ID)""").find(masked)?.groupValues?.get(1) ?: return source
        if (Regex("""\bclass\s""").findAll(masked).count() != 1 || !extendsLevel(owner)) return source
        val edits = mutableListOf<Pair<IntRange, String>>()
        fun body(match: MatchResult): String? {
            val open = masked.indexOf('{', match.range.last)
            val close = if (open < 0) -1 else JavaSourceText.matchingBrace(masked, open)
            return if (close < 0) null else masked.substring(open + 1, close)
        }
        fun handsOn(match: MatchResult, member: String): Boolean {
            val body = body(match) ?: return false
            val name = Regex.escape(match.groupValues[3])
            val uses = Regex("""(?<![\w$.])$name(?![\w$])""").findAll(body).count()
            return uses == Regex("""\.$member\s*\(\s*$name\s*[,)]""").findAll(body).count()
        }
        for ((pattern, member, type) in listOf(Triple(mapData, "getMapData", "MapId"), Triple(setMapData, "setMapData", "MapId"), Triple(gameEvent, "gameEvent", "Holder<GameEvent>"))) {
            for (match in pattern.findAll(masked)) {
                if (!handsOn(match, member)) continue
                edits += match.range to "${match.groupValues[1]}$type${match.groupValues[2]}"
            }
        }
        for (match in freeMapId.findAll(masked)) {
            val body = body(match) ?: continue
            val returned = Regex("""^\s*return\s+(.+?)\s*;\s*$""", RegexOption.DOT_MATCHES_ALL).find(body)?.groups?.get(1) ?: continue
            val bodyStart = masked.indexOf('{', match.range.last) + 1
            when {
                Regex("""\d+""").matches(returned.value) ->
                    edits += (bodyStart + returned.range.first..bodyStart + returned.range.last) to "new MapId(${returned.value})"
                !Regex("""$ID(?:\.$ID)*(?:\(\))?\.getFreeMapId\(\)""").matches(returned.value) -> continue
            }
            edits += match.range to "public MapId${match.groupValues[1]}"
        }
        if (edits.isEmpty()) return source
        var result = edits.sortedBy { it.first.first }.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
        if (result.contains("MapId")) result = JavaSourceText.addImport(result, "net.minecraft.world.level.saveddata.maps.MapId")
        if (result.contains("Holder<GameEvent>")) result = JavaSourceText.addImport(result, "net.minecraft.core.Holder")
        return result
    }
}
