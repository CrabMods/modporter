package com.modporter.mapping

import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Forge 1.20.1 SRG member ids (`f_20937_`, `m_20291_`) resolved to the Mojang names that
 * NeoForge 1.21.1 uses at compile time and runtime.
 *
 * The table is derived from the two authoritative mapping artifacts ForgeGradle itself
 * consumes (MCPConfig `obf_to_srg.tsrg` and Mojang's `client_mappings.txt`), see
 * `scripts/srg_to_mojang.py`. Owners are the Mojang class names that Forge 1.20.1 access
 * transformers and `ObfuscationReflectionHelper` calls already spell out; method
 * descriptors use the same named form.
 */
object SrgMemberNames {
    data class Member(val kind: Char, val owner: String, val srg: String, val mojang: String, val descriptor: String)

    private const val RESOURCE = "/mappings/forge2neo/srg-mojang-1.20.1.tsv.gz"
    private val srgPattern = Regex("""^[fm]_\d+_$""")

    private val members: Map<Pair<String, String>, List<Member>> by lazy { load() }

    fun isSrgName(name: String): Boolean = srgPattern.matches(name)

    /** Mojang field name declared on [owner] for an SRG field id, or null when unknown. */
    fun field(owner: String, srg: String): String? =
        members[owner to srg]?.singleOrNull { it.kind == 'F' }?.mojang

    /**
     * Mojang method name declared on [owner] for an SRG method id. When [descriptor] is given
     * (named-class JVM descriptor) it must match the declaration; otherwise the name is only
     * returned when every overload with that SRG id agrees on it.
     */
    fun method(owner: String, srg: String, descriptor: String? = null): String? {
        val candidates = members[owner to srg]?.filter { it.kind == 'M' }.orEmpty()
        val matching = if (descriptor != null) candidates.filter { it.descriptor == descriptor } else candidates
        return matching.map { it.mojang }.distinct().singleOrNull()
    }

    /**
     * Rewrites one normalized access transformer entry (`modifier owner member[descriptor]`)
     * whose member is an SRG id. Returns null when the member is not an SRG id or has no
     * Mojang name on that owner, so the caller can leave the entry untouched.
     */
    fun migrateAccessTransformerEntry(entry: String): String? {
        val parts = entry.trim().split(Regex("""\s+"""))
        if (parts.size != 3) return null
        val (modifier, owner, member) = parts
        val descriptorStart = member.indexOf('(')
        val name = if (descriptorStart >= 0) member.substring(0, descriptorStart) else member
        if (!isSrgName(name)) return null
        val ownerName = owner.replace('/', '.')
        val mapped = if (descriptorStart >= 0) {
            method(ownerName, name, member.substring(descriptorStart))
        } else {
            field(ownerName, name)
        } ?: return null
        return "$modifier $owner $mapped${if (descriptorStart >= 0) member.substring(descriptorStart) else ""}"
    }

    private fun load(): Map<Pair<String, String>, List<Member>> {
        val stream = SrgMemberNames::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing SRG member table resource: $RESOURCE")
        val result = HashMap<Pair<String, String>, MutableList<Member>>()
        InputStreamReader(GZIPInputStream(stream), Charsets.UTF_8).buffered().useLines { lines ->
            lines.forEach { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val columns = line.split('\t')
                if (columns.size < 4) return@forEach
                val member = Member(columns[0][0], columns[1], columns[2], columns[3], columns.getOrElse(4) { "" })
                result.getOrPut(member.owner to member.srg) { mutableListOf() }.add(member)
            }
        }
        return result
    }
}
