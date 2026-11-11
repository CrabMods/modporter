package com.modporter.core.transforms.structural

/** Source-only receiver facts, owned by one provider graph collection. */
internal class ProviderReceiverOwnerIndex(private val pass: StructuralRefactorPass) {
    private data class SourceKey(val source: String, val executableCode: String)
    private data class Values(val variables: Map<String, String>, val getters: Map<String, String>)

    private val blocks = mutableMapOf<SourceKey, List<JavaTypeBlock>>()
    private val values = mutableMapOf<SourceKey, Values>()
    private var ownerSnapshot: List<String>? = null
    private var variablePattern: Regex? = null
    private var getterPattern: Regex? = null

    fun ownerAt(
        source: String,
        executableCode: String,
        receiver: String,
        offset: Int,
        candidateOwners: Collection<String>
    ): String? {
        val normalized = receiver.trim()
        val key = SourceKey(source, executableCode)
        val typeBlocks = blocks.getOrPut(key) { with(pass) { javaTypeBlocks(source, executableCode) } }
        if (normalized == "this") {
            return typeBlocks.lastOrNull { offset in it.bodyStart..it.end }?.name
        }
        if (normalized in candidateOwners) return normalized
        updateOwners(candidateOwners)
        if (variablePattern == null) return null
        val index = values.getOrPut(key) {
            Values(
                checkNotNull(variablePattern).findAll(executableCode)
                    .associate { it.groupValues[2] to it.groupValues[1] },
                checkNotNull(getterPattern).findAll(executableCode)
                    .associate { "${it.groupValues[2]}()" to it.groupValues[1] }
            )
        }
        index.variables[normalized]?.let { return it }
        index.variables[normalized.substringAfterLast('.')]?.let { return it }
        index.getters[normalized]?.let { return it }
        index.getters[normalized.substringAfterLast('.')]?.let { return it }
        return null
    }

    private fun updateOwners(candidateOwners: Collection<String>) {
        val previous = ownerSnapshot
        if (previous != null && previous.size == candidateOwners.size) {
            val iterator = candidateOwners.iterator()
            if (previous.all { it == iterator.next() }) return
        }
        ownerSnapshot = candidateOwners.toList()
        values.clear()
        val alternatives = candidateOwners.joinToString("|") { Regex.escape(it) }
        if (alternatives.isBlank()) {
            variablePattern = null
            getterPattern = null
        } else {
            variablePattern = Regex("""\b($alternatives)\s+([A-Za-z_$][\w$]*)\b""")
            getterPattern = Regex("""(?m)\b(?:public|protected|private)\s+($alternatives)\s+([A-Za-z_$][\w$]*)\s*\(\s*\)""")
        }
    }
}
