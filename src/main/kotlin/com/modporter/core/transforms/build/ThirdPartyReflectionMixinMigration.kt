package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

internal class ThirdPartyReflectionMixinMigration {
    private data class AccessorField(val name: String, val returnType: String)
    private data class AccessorOwner(val fqn: String, val simpleName: String, val fields: MutableMap<String, AccessorField>)
    private data class Replacement(
        val range: IntRange,
        val ownerFqn: String,
        val receiver: String,
        val fieldName: String,
        val returnType: String
    )
    private data class MixinConfig(val file: Path, val packageName: String, val root: JsonObject)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val moduleDir = GradleModLayout.modModule(projectDir)
        val srcDir = moduleDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val javaFiles = Files.walk(srcDir).filter { it.extension == "java" }.toList()
        val rewrites = linkedMapOf<Path, Pair<String, List<Replacement>>>()
        val owners = linkedMapOf<String, AccessorOwner>()

        for (file in javaFiles) {
            val source = file.readText()
            val replacements = collectReplacements(source)
            if (replacements.isEmpty()) continue
            rewrites[file] = source to replacements
            for (replacement in replacements) {
                val owner = owners.getOrPut(replacement.ownerFqn) {
                    AccessorOwner(
                        replacement.ownerFqn,
                        replacement.ownerFqn.substringAfterLast('.').replace('$', '_'),
                        linkedMapOf()
                    )
                }
                val previous = owner.fields.putIfAbsent(
                    replacement.fieldName,
                    AccessorField(replacement.fieldName, replacement.returnType)
                )
                require(previous == null || previous.returnType == replacement.returnType) {
                    "Conflicting statically derived types for ${replacement.ownerFqn}.${replacement.fieldName}"
                }
            }
        }
        if (rewrites.isEmpty()) return emptyList()

        val config = findOwningMixinConfig(moduleDir, rewrites.values.map { it.first })
        val generatedPackage = "${config.packageName}.modporter"
        val changes = mutableListOf<Change>()
        for ((file, pair) in rewrites) {
            val (original, replacements) = pair
            var modified = original
            for (replacement in replacements.sortedByDescending { it.range.first }) {
                val owner = owners.getValue(replacement.ownerFqn)
                val accessorFqn = "$generatedPackage.ModPorter${owner.simpleName}Accessor"
                val method = accessorMethodName(replacement.fieldName)
                modified = modified.replaceRange(
                    replacement.range,
                    "(($accessorFqn) (Object) ${replacement.receiver}).$method()"
                )
            }
            if (!maskJavaCommentsAndLiterals(modified).contains("ObfuscationReflectionHelper.")) {
                modified = removeImport(modified, "net.neoforged.fml.util.ObfuscationReflectionHelper")
                modified = removeImport(modified, "net.minecraftforge.fml.util.ObfuscationReflectionHelper")
            }
            changes += Change(
                file = file,
                line = 1,
                description = "Replace optional third-party reflection fields with typed @Pseudo Mixin accessors",
                before = "ObfuscationReflectionHelper.getPrivateValue",
                after = "optional-target accessor interface calls",
                confidence = Confidence.HIGH,
                ruleId = "build-third-party-reflection-pseudo-accessor"
            )
            if (!dryRun) file.writeText(modified)
        }

        val generatedEntries = mutableListOf<String>()
        for (owner in owners.values) {
            val className = "ModPorter${owner.simpleName}Accessor"
            val file = srcDir.resolve(generatedPackage.replace('.', '/')).resolve("$className.java")
            val content = renderAccessor(generatedPackage, className, owner)
            generatedEntries += "modporter.$className"
            changes += Change(
                file = file,
                line = 1,
                description = "Generate optional-target Mixin accessor for explicit third-party private fields",
                before = owner.fields.keys.joinToString(),
                after = "$generatedPackage.$className",
                confidence = Confidence.HIGH,
                ruleId = "build-third-party-pseudo-accessor-source"
            )
            if (!dryRun) {
                file.parent.createDirectories()
                file.writeText(content)
            }
        }

        val updatedConfig = addMixinEntries(config.root, generatedEntries, client = owners.keys.all { ".client." in it })
        if (updatedConfig != config.root) {
            changes += Change(
                file = config.file,
                line = 1,
                description = "Register generated optional-target accessors in the existing Mixin config",
                before = "client Mixin list without generated accessors",
                after = generatedEntries.joinToString(),
                confidence = Confidence.HIGH,
                ruleId = "build-third-party-pseudo-accessor-config"
            )
            if (!dryRun) config.file.writeText(JSON.encodeToString(JsonObject.serializer(), updatedConfig) + "\n")
        }
        return changes
    }

    private fun collectReplacements(source: String): List<Replacement> {
        val executable = maskJavaCommentsAndLiterals(source)
        val replacements = mutableListOf<Replacement>()
        val token = "ObfuscationReflectionHelper.getPrivateValue"
        var searchFrom = 0
        while (true) {
            val callStart = executable.indexOf(token, searchFrom)
            if (callStart < 0) break
            val openParen = executable.indexOf('(', callStart + token.length)
            val closeParen = if (openParen >= 0) findMatching(executable, openParen, '(', ')') else -1
            require(closeParen > openParen) { "Unbalanced getPrivateValue call" }
            val args = splitTopLevel(source.substring(openParen + 1, closeParen))
            require(args.size == 3) { "Unsupported getPrivateValue arity" }
            val ownerReference = Regex("""^([A-Za-z_$][\w$.]*)\.class$""")
                .matchEntire(args[0].trim())?.groupValues?.get(1)
                ?: error("Third-party reflection owner is not a class literal")
            val ownerFqn = resolveType(ownerReference, source)
                ?: error("Cannot resolve reflection owner $ownerReference")
            if (ownerFqn.startsWith("net.minecraft.") ||
                ownerFqn.startsWith("net.neoforged.") ||
                ownerFqn.startsWith("net.minecraftforge.")) {
                searchFrom = closeParen + 1
                continue
            }
            val fieldName = Regex("""^\"([A-Za-z_$][\w$]*)\"$""")
                .matchEntire(args[2].trim())?.groupValues?.get(1)
                ?: error("Third-party reflection field is not an explicit identifier literal")
            val declaration = declarationBefore(executable, callStart)
                ?: error("Third-party reflection result lacks an explicit typed assignment")
            var returnType = resolveValueType(declaration.first, source)
                ?: error("Cannot resolve reflected assignment type ${declaration.first}")
            if (returnType == "java.lang.Object") {
                val inferred = Regex(
                    """\b${Regex.escape(declaration.second)}\s+instanceof\s+([A-Za-z_$][\w$.]*)\b"""
                ).findAll(executable.substring(closeParen + 1))
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()
                require(inferred.size == 1) {
                    "Object reflection result ${declaration.second} needs exactly one explicit instanceof type"
                }
                returnType = resolveValueType(inferred.single(), source)
                    ?: error("Cannot resolve instanceof type ${inferred.single()}")
            }
            replacements += Replacement(callStart..closeParen, ownerFqn, args[1].trim(), fieldName, returnType)
            searchFrom = closeParen + 1
        }
        return replacements
    }

    private fun declarationBefore(executable: String, callStart: Int): Pair<String, String>? {
        val prefix = executable.substring(0, callStart)
        val match = Regex(
            """(?:^|[;{}])\s*([A-Za-z_$][\w$.]*(?:\s*<[^;={}]+>)?)\s+([A-Za-z_$][\w$]*)\s*=\s*$"""
        ).findAll(prefix).lastOrNull() ?: return null
        return match.groupValues[1].trim() to match.groupValues[2]
    }

    private fun resolveType(reference: String, source: String): String? {
        if ('.' in reference) return reference
        return Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$.]*\.${Regex.escape(reference)})\s*;""")
            .find(maskJavaCommentsAndLiterals(source))?.groupValues?.get(1)
    }

    private fun resolveValueType(reference: String, source: String): String? {
        val trimmed = reference.trim()
        if (trimmed in setOf("boolean", "byte", "short", "int", "long", "float", "double", "char")) return trimmed
        if (trimmed == "Object") return "java.lang.Object"
        if ('.' in trimmed) return trimmed
        return resolveType(trimmed, source)
    }

    private fun findOwningMixinConfig(projectDir: Path, sources: Collection<String>): MixinConfig {
        val resources = projectDir.resolve("src/main/resources")
        require(resources.exists()) { "Optional-target accessors require an existing Mixin config" }
        val configs = Files.walk(resources)
            .filter { it.fileName.toString().endsWith("mixins.json") }
            .toList()
            .mapNotNull { file ->
                val root = runCatching { JSON.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val packageName = root["package"]?.jsonPrimitive?.content ?: return@mapNotNull null
                MixinConfig(file, packageName, root)
            }
        require(configs.isNotEmpty()) {
            "Optional-target accessors require an existing structured Mixin config"
        }
        if (configs.size == 1) return configs.single()

        val sourcePackages = sources.mapNotNull { source ->
            Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$.]*)\s*;""")
                .find(maskJavaCommentsAndLiterals(source))?.groupValues?.get(1)
        }.distinct()
        require(sourcePackages.isNotEmpty()) {
            "Optional-target accessor generation cannot derive source package ownership for ${configs.size} Mixin configs"
        }
        val scored = configs.map { config ->
            config to sourcePackages.maxOf { sourcePackage ->
                commonPackagePrefix(config.packageName, sourcePackage)
            }
        }
        val bestScore = scored.maxOf { it.second }
        val owners = scored.filter { it.second == bestScore }
        require(bestScore >= 2 && owners.size == 1) {
            val observed = scored.joinToString { (config, score) -> "${config.packageName}=$score" }
            "Optional-target accessor generation cannot identify one source-owning Mixin config " +
                "for packages ${sourcePackages.joinToString()}; observed $observed"
        }
        return owners.single().first
    }

    private fun commonPackagePrefix(left: String, right: String): Int =
        left.split('.').zip(right.split('.')).takeWhile { (a, b) -> a == b }.size

    private fun addMixinEntries(root: JsonObject, entries: List<String>, client: Boolean): JsonObject {
        val key = if (client) "client" else "mixins"
        val existing = (root[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val merged = (existing + entries).distinct()
        return JsonObject(root.toMutableMap().apply { put(key, JsonArray(merged.map(::JsonPrimitive))) })
    }

    private fun renderAccessor(packageName: String, className: String, owner: AccessorOwner): String = buildString {
        appendLine("package $packageName;")
        appendLine()
        appendLine("import org.spongepowered.asm.mixin.Mixin;")
        appendLine("import org.spongepowered.asm.mixin.Pseudo;")
        appendLine("import org.spongepowered.asm.mixin.gen.Accessor;")
        appendLine()
        appendLine("@Pseudo")
        appendLine("@Mixin(targets = \"${owner.fqn}\", remap = false)")
        appendLine("public interface $className {")
        owner.fields.values.forEach { field ->
            appendLine("\t@Accessor(value = \"${field.name}\", remap = false)")
            appendLine("\t${field.returnType} ${accessorMethodName(field.name)}();")
            appendLine()
        }
        appendLine("}")
    }

    private fun accessorMethodName(fieldName: String): String =
        "modporter\$get" + fieldName.replaceFirstChar { it.uppercase() }

    private fun removeImport(source: String, importName: String): String =
        Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)}\s*;[ \t]*(?:\r?\n)?""").replace(source, "")

    private fun splitTopLevel(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        var angle = 0
        var inString = false
        var escaped = false
        value.forEachIndexed { index, char ->
            if (inString) {
                if (char == '"' && !escaped) inString = false
                escaped = char == '\\' && !escaped
                if (char != '\\') escaped = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    ',' -> if (depth == 0 && angle == 0) {
                        result += value.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
        }
        result += value.substring(start).trim()
        return result
    }

    private fun findMatching(source: String, openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                open -> depth++
                close -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        fun mask(start: Int, end: Int) {
            for (position in start until end.coerceAtMost(chars.size)) {
                if (chars[position] != '\r' && chars[position] != '\n') chars[position] = ' '
            }
        }
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                    mask(index, end); index = end
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                    mask(index, end); index = end
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    var end = index + 1
                    var escaped = false
                    while (end < source.length) {
                        val char = source[end]
                        if (char == quote && !escaped) { end++; break }
                        escaped = char == '\\' && !escaped
                        if (char != '\\') escaped = false
                        end++
                    }
                    mask(index, end); index = end
                }
                else -> index++
            }
        }
        return String(chars)
    }

    companion object {
        private val JSON = Json { prettyPrint = true }
    }
}
