package com.modporter.mapping

import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Declared fields and methods of the NeoForge 1.21.1 game classes (Mojang names), dumped
 * from the real compiled game jar by `scripts/target_members.py`.
 *
 * Migration rules use it to check that a member a mod references by name (for example
 * through reflection) still exists on the target instead of guessing.
 */
object TargetMembers {
    private const val RESOURCE = "/mappings/forge2neo/target-members-1.21.1.tsv.gz"
    private val platformPrefixes = listOf("net.minecraft.", "com.mojang.", "net.neoforged.")

    private class Declared(
        val fields: MutableSet<String> = HashSet(),
        val methods: MutableMap<String, MutableSet<String>> = HashMap(),
        // the direct superclass and interfaces (`S` rows)
        val supertypes: MutableList<String> = ArrayList(),
        var superclass: String? = null,
        // "name descriptor" -> access word and generic signature
        val methodDetails: MutableMap<String, Pair<String, String>> = HashMap(),
        // field name -> generic signature, for generic fields only
        val fieldSignatures: MutableMap<String, String> = HashMap(),
        // field name -> JVM descriptor
        val fieldDescriptors: MutableMap<String, String> = HashMap()
    )

    /** A method a platform class declares: access word (public/protected/package/private) and generic signature ("" when not generic). */
    data class PlatformMethod(val owner: String, val access: String, val signature: String)

    private val classes: Map<String, Declared> by lazy { load() }

    /** True for classes that ship with Minecraft or NeoForge (and therefore appear in the table). */
    fun isPlatformClass(binaryName: String): Boolean = platformPrefixes.any { binaryName.startsWith(it) }

    fun hasClass(binaryName: String): Boolean = classes.containsKey(binaryName)

    fun hasField(owner: String, name: String): Boolean = classes[owner]?.fields?.contains(name) == true

    fun hasMethod(owner: String, name: String): Boolean = classes[owner]?.methods?.containsKey(name) == true

    /**
     * Whether [owner] or a class or interface above it declares a method [name] taking
     * [parameterCount] parameters (only platform classes in the table are followed).
     */
    fun declaresInHierarchy(owner: String, name: String, parameterCount: Int): Boolean {
        val seen = HashSet<String>()
        val frontier = ArrayDeque(listOf(owner))
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            if (!seen.add(current)) continue
            val declared = classes[current] ?: continue
            if (declared.methods[name].orEmpty().any { parameterCount(it) == parameterCount }) return true
            frontier.addAll(declared.supertypes)
        }
        return false
    }

    /**
     * The nearest declaration of [name] with JVM [descriptor] at or above [owner]: the superclass
     * chain first, then interfaces (null when no platform class in the table declares it).
     */
    fun inheritedMethod(owner: String, name: String, descriptor: String): PlatformMethod? {
        val key = "$name $descriptor"
        var current: String? = owner
        while (current != null) {
            val declared = classes[current] ?: break
            declared.methodDetails[key]?.let { return PlatformMethod(current!!, it.first, it.second) }
            current = declared.superclass
        }
        val seen = HashSet<String>()
        val frontier = ArrayDeque(listOf(owner))
        while (frontier.isNotEmpty()) {
            val type = frontier.removeFirst()
            if (!seen.add(type)) continue
            val declared = classes[type] ?: continue
            declared.methodDetails[key]?.let { return PlatformMethod(type, it.first, it.second) }
            frontier.addAll(declared.supertypes)
        }
        return null
    }

    /** The direct superclass and interfaces of [owner] (empty when unknown). */
    fun supertypesOf(owner: String): List<String> = classes[owner]?.supertypes.orEmpty()

    /** Whether [owner] is [ancestor] or extends/implements it, directly or not. */
    fun inheritsFrom(owner: String, ancestor: String): Boolean {
        val seen = HashSet<String>()
        val frontier = ArrayDeque(listOf(owner))
        while (frontier.isNotEmpty()) {
            val type = frontier.removeFirst()
            if (type == ancestor) return true
            if (seen.add(type)) frontier.addAll(classes[type]?.supertypes.orEmpty())
        }
        return false
    }

    private fun parameterCount(descriptor: String): Int {
        var count = 0
        var index = descriptor.indexOf('(') + 1
        if (index <= 0) return -1
        while (index < descriptor.length && descriptor[index] != ')') {
            while (descriptor[index] == '[') index++
            index = if (descriptor[index] == 'L') descriptor.indexOf(';', index) + 1 else index + 1
            count++
        }
        return count
    }

    private val noArgumentReturns: Map<String, Set<String>> by lazy {
        val result = HashMap<String, MutableSet<String>>()
        for (declared in classes.values) {
            for ((name, descriptors) in declared.methods) {
                descriptors.filter { it.startsWith("()") }.forEach { result.getOrPut(name) { HashSet() } += it.substring(2) }
            }
        }
        result
    }

    private val methodsByName: Map<String, List<Pair<String, String>>> by lazy {
        val result = HashMap<String, MutableList<Pair<String, String>>>()
        for ((owner, declared) in classes) {
            for ((name, descriptors) in declared.methods) {
                descriptors.forEach { result.getOrPut(name) { ArrayList() } += owner to it }
            }
        }
        result
    }

    /** (owner, JVM descriptor) of every platform method named [name]. */
    fun methodsNamed(name: String): List<Pair<String, String>> = methodsByName[name].orEmpty()

    /** The parameter type descriptors of a JVM method [descriptor] (`(ILjava/lang/String;)V` -> `I`, `Ljava/lang/String;`). */
    fun parameterTypes(descriptor: String): List<String> {
        val types = mutableListOf<String>()
        var index = descriptor.indexOf('(') + 1
        if (index <= 0) return types
        while (index < descriptor.length && descriptor[index] != ')') {
            val start = index
            while (descriptor[index] == '[') index++
            index = if (descriptor[index] == 'L') descriptor.indexOf(';', index) + 1 else index + 1
            types += descriptor.substring(start, index)
        }
        return types
    }

    /** JVM return descriptors of every platform method [name] taking no parameters (empty when none). */
    fun noArgumentReturnTypes(name: String): Set<String> = noArgumentReturns[name].orEmpty()

    /** The JVM descriptor of field [name] declared by [owner] (null when unknown). */
    fun fieldDescriptor(owner: String, name: String): String? = classes[owner]?.fieldDescriptors?.get(name)

    /** Generic signatures of the generic fields [owner] declares, by field name (empty when unknown). */
    fun fieldSignatures(owner: String): Map<String, String> = classes[owner]?.fieldSignatures.orEmpty()

    /** JVM descriptors of the methods named [name] declared by [owner] (empty when unknown). */
    fun methodDescriptors(owner: String, name: String): Set<String> = classes[owner]?.methods?.get(name).orEmpty()

    private val classesBySimpleName: Map<String, List<String>> by lazy {
        classes.keys.groupBy { it.substringAfterLast('.').substringAfterLast('$') }
    }

    /** The platform classes whose simple (or nested) name is [simpleName]. */
    fun classesNamed(simpleName: String): List<String> = classesBySimpleName[simpleName].orEmpty()

    private fun load(): Map<String, Declared> {
        val stream = TargetMembers::class.java.getResourceAsStream(RESOURCE)
            ?: error("Missing target member table resource: $RESOURCE")
        val result = HashMap<String, Declared>()
        InputStreamReader(GZIPInputStream(stream), Charsets.UTF_8).buffered().useLines { lines ->
            lines.forEach { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val columns = line.split('\t')
                if (columns.size < 3) return@forEach
                val declared = result.getOrPut(columns[1]) { Declared() }
                if (columns[0] == "F") {
                    declared.fields.add(columns[2])
                    columns.getOrNull(3)?.let { declared.fieldDescriptors[columns[2]] = it }
                    columns.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let { declared.fieldSignatures[columns[2]] = it }
                } else if (columns[0] == "S") {
                    declared.supertypes.add(columns[2])
                    if (columns.getOrNull(3) == "extends") declared.superclass = columns[2]
                } else {
                    val descriptor = columns.getOrNull(3).orEmpty()
                    declared.methods.getOrPut(columns[2]) { HashSet() }.add(descriptor)
                    declared.methodDetails["${columns[2]} $descriptor"] =
                        columns.getOrNull(4).orEmpty() to columns.getOrNull(5).orEmpty()
                }
            }
        }
        return result
    }
}
