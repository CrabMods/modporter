package com.modporter.core.transforms.structural

import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.mapping.TargetMembers

/**
 * Forge 1.20.1 declared `setCancellationResult` on every `PlayerInteractEvent` but only read it back
 * for `RightClickBlock`, `RightClickItem`, `EntityInteract` and `EntityInteractSpecific`
 * (`ForgeHooks` and the `ServerPlayerGameMode`/`MultiPlayerGameMode` patches): exactly the events
 * that keep it on NeoForge 1.21.1. On any other interaction event (a `LeftClickBlock`, say) the call
 * never had an effect, so the statement goes; its argument must be a constant so nothing else goes
 * with it.
 */
internal fun StructuralRefactorPass.removeUnreadInteractionCancellationResults(source: String): String {
    if (!source.contains("setCancellationResult(") || !source.contains("PlayerInteractEvent")) return source
    val masked = maskJavaCommentsAndLiterals(source)
    val statement = Regex("""(?m)^[ \t]*([A-Za-z_$][\w$]*)\s*\.\s*setCancellationResult\s*\(\s*(?:[\w$]+\.)*[A-Z][A-Z0-9_]*\s*\)\s*;[ \t]*\r?\n""")
    val removals = statement.findAll(masked).filter { match ->
        // the declared type's last segment; it is an interaction event when spelled
        // PlayerInteractEvent.X at the declaration or imported as such
        val declaration = JavaScopedDeclarations.visibleAt(masked, match.groupValues[1], match.range.first) ?: return@filter false
        val nested = declaration.type
        val qualified = masked.substring(0, declaration.offset).endsWith("PlayerInteractEvent.") ||
            Regex("""(?m)^\s*import\s+net\.neoforged\.neoforge\.event\.entity\.player\.PlayerInteractEvent\.${Regex.escape(nested)}\s*;""").containsMatchIn(source)
        if (!qualified) return@filter false
        val owner = "net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$$nested"
        TargetMembers.hasClass(owner) && !TargetMembers.declaresInHierarchy(owner, "setCancellationResult", 1)
    }.toList()
    var result = source
    for (match in removals.asReversed()) {
        result = result.removeRange(match.range)
    }
    return result
}
