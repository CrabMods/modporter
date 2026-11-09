package com.modporter.core.transforms.build

import com.modporter.mapping.TargetMembers

/**
 * Checks access transformer entries against the real NeoForge 1.21.1 class table. ModDev validates
 * every entry when the game sources are transformed, so an entry whose class or member no longer
 * exists (PotionBrewing's static mix lists became instance fields, TitleScreen lost its panorama
 * field) stops the build before anything compiles; the source that used the member then surfaces
 * as ordinary compile errors for the source rules.
 */
internal object AccessTransformerTargets {
    /**
     * True when the entry's target exists, false when the class table knows the owner and the
     * member is missing (or the class itself is gone), null when the owner is not a platform
     * class and the table has no opinion.
     */
    fun exists(entry: String): Boolean? {
        val parts = entry.trim().split(Regex("""\s+"""))
        if (parts.size < 2) return null
        val owner = parts[1]
        if (!TargetMembers.isPlatformClass(owner)) return null
        if (!TargetMembers.hasClass(owner)) return false
        if (parts.size == 2) return true
        val member = parts[2]
        // `*` (all fields) and `*()` (all methods) are wildcard entries, not member names
        if (member == "*" || member == "*()") return true
        val descriptorStart = member.indexOf('(')
        // An SRG id that found no Mojang name is left in place so the validator reports it
        if (com.modporter.mapping.SrgMemberNames.isSrgName(if (descriptorStart < 0) member else member.substring(0, descriptorStart))) return null
        if (descriptorStart < 0) return TargetMembers.hasField(owner, member)
        val name = member.substring(0, descriptorStart)
        val descriptor = member.substring(descriptorStart)
        if (!TargetMembers.hasMethod(owner, name)) return false
        val descriptors = TargetMembers.methodDescriptors(owner, name)
        return descriptors.isEmpty() || descriptor in descriptors
    }

    /**
     * A method entry whose descriptor names platform classes that moved package in 1.21 (1.20.1's
     * `level.chunk.ChunkStatus` is `level.chunk.status.ChunkStatus`), with each such class replaced
     * by the only 1.21.1 class of the same simple name, when the owner then declares exactly that
     * descriptor. Null when nothing moved, a name is ambiguous, or the method still does not match.
     */
    fun relocated(entry: String): String? {
        val parts = entry.trim().split(Regex("""\s+"""))
        if (parts.size != 3 || !parts[2].contains('(')) return null
        val (modifier, owner, member) = parts
        val name = member.substringBefore('(')
        val descriptor = member.substring(member.indexOf('('))
        val moves = Regex("""L([\w/$]+);""").findAll(descriptor)
            .map { it.groupValues[1] }
            .filter { internal -> internal.replace('/', '.').let { TargetMembers.isPlatformClass(it) && !TargetMembers.hasClass(it) } }
            .distinct()
            .associateWith { internal -> TargetMembers.classesNamed(internal.substringAfterLast('/')).singleOrNull() }
        if (moves.isEmpty() || moves.values.any { it == null }) return null
        val relocated = moves.entries.fold(descriptor) { text, (from, to) -> text.replace("L$from;", "L${to!!.replace('.', '/')};") }
        if (relocated !in TargetMembers.methodDescriptors(owner, name)) return null
        return "$modifier $owner $name$relocated"
    }
}
