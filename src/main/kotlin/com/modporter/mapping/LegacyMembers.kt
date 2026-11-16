package com.modporter.mapping

import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Methods the Minecraft 1.20.1 classes declare, by Mojang name and named-class JVM descriptor.
 *
 * Read from the same SRG/Mojang table as [SrgMemberNames] (`scripts/srg_to_mojang.py`). That table
 * only lists obfuscated game members, so a missing entry means "not proven to exist", never
 * "proven absent": rules use it to admit a 1.20.1 member, not to reject one.
 */
object LegacyMembers {
    private const val RESOURCE = "/mappings/forge2neo/srg-mojang-1.20.1.tsv.gz"

    private val methods: Map<Pair<String, String>, Set<String>> by lazy { load() }

    /** Named-class JVM descriptors of the 1.20.1 methods [name] that [owner] declares (empty when unknown). */
    fun methodDescriptors(owner: String, name: String): Set<String> = methods[owner to name].orEmpty()

    fun declaresMethod(owner: String, name: String, descriptor: String): Boolean =
        descriptor in methodDescriptors(owner, name)

    private fun load(): Map<Pair<String, String>, Set<String>> {
        val stream = LegacyMembers::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing SRG member table resource: $RESOURCE")
        val result = HashMap<Pair<String, String>, MutableSet<String>>()
        InputStreamReader(GZIPInputStream(stream), Charsets.UTF_8).buffered().useLines { lines ->
            lines.forEach { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val columns = line.split('\t')
                if (columns.size < 5 || columns[0] != "M") return@forEach
                result.getOrPut(columns[1] to columns[3]) { HashSet() }.add(columns[4])
            }
        }
        return result
    }
}
