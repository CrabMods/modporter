package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

internal class CopperDataMapMigration {
    companion object {
        /** The change that generates the oxidizable/waxable data map provider (the data generation evidence keys on it). */
        const val PROVIDER_RULE_ID = "build-copper-datamap-provider"
    }

    private data class Candidate(
        val file: Path,
        val packageName: String,
        val className: String,
        val weatheringMap: String,
        val waxableMap: String,
        val addWeatheringMethod: String,
        val addWaxableMethod: String,
        val injectMethod: String
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val javaFiles = Files.walk(srcDir).filter { it.extension == "java" }.toList()
        val candidates = javaFiles.mapNotNull(::findCandidate)
        if (candidates.isEmpty()) return emptyList()
        val changes = mutableListOf<Change>()

        for (candidate in candidates) {
            val rewrittenRegistry = registrySource(candidate)
            if (candidate.file.readText() != rewrittenRegistry) {
                changes += Change(
                    file = candidate.file,
                    line = 1,
                    description = "Replace reflected vanilla copper map injection with source-preserving NeoForge data map views",
                    before = "WeatheringCopper.NEXT_BY_BLOCK/HoneycombItem.WAXABLES reflection injection",
                    after = "read-only weathering and waxable source maps consumed by DataMapProvider",
                    confidence = Confidence.HIGH,
                    ruleId = "build-copper-datamap-registry"
                )
                if (!dryRun) candidate.file.writeText(rewrittenRegistry)
            }

            changes += removeInjectionCalls(javaFiles, candidate, dryRun)
            val providerPackage = "${candidate.packageName}.modporter.datagen"
            val providerName = "ModPorter${candidate.className}DataMapProvider"
            val providerFile = srcDir.resolve(providerPackage.replace('.', '/')).resolve("$providerName.java")
            val providerSource = providerSource(candidate, providerPackage, providerName)
            if (!providerFile.exists() || providerFile.readText() != providerSource) {
                changes += Change(
                    file = providerFile,
                    line = 1,
                    description = "Generate NeoForge oxidizable and waxable block data map provider",
                    before = "no data map provider for source-declared copper transitions",
                    after = "$providerPackage.$providerName",
                    confidence = Confidence.HIGH,
                    ruleId = PROVIDER_RULE_ID
                )
                if (!dryRun) {
                    providerFile.parent.createDirectories()
                    providerFile.writeText(providerSource)
                }
            }
            changes += registerProvider(javaFiles, providerPackage, providerName, dryRun)
        }
        return changes
    }

    private fun findCandidate(file: Path): Candidate? {
        val source = file.readText()
        val codeWithStrings = maskJavaCommentsAndTextBlocks(source)
        if (!codeWithStrings.contains("WeatheringCopper.NEXT_BY_BLOCK") ||
            !codeWithStrings.contains("HoneycombItem.WAXABLES") ||
            !LegacyReflectionSyntax.containsDeclaredFieldLookup(codeWithStrings, "delegate") ||
            !LegacyReflectionSyntax.containsAccessibleEnable(codeWithStrings)) {
            return null
        }
        val executable = maskJavaCommentsAndLiterals(source)
        val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$.]*)\s*;""")
            .find(executable)?.groupValues?.get(1) ?: return null
        val className = Regex("""\bpublic\s+(?:final\s+)?class\s+([A-Za-z_$][\w$]*)\b""")
            .find(executable)?.groupValues?.get(1) ?: return null
        val maps = Regex(
            """private\s+static\s+final\s+BiMap\s*<\s*Supplier\s*<\s*Block\s*>\s*,\s*Supplier\s*<\s*Block\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*HashBiMap\.create\(\)\s*;"""
        ).findAll(executable).map { it.groupValues[1] }.toList()
        val weatheringMap = maps.singleOrNull { it.contains("WEATHER", ignoreCase = true) } ?: return null
        val waxableMap = maps.singleOrNull { it.contains("WAX", ignoreCase = true) } ?: return null
        val addWeathering = Regex(
            """public\s+static\s+synchronized\s+void\s+([A-Za-z_$][\w$]*Weather[A-Za-z_$][\w$]*)\s*\(""",
            RegexOption.IGNORE_CASE
        ).find(executable)?.groupValues?.get(1) ?: return null
        val addWaxable = Regex(
            """public\s+static\s+synchronized\s+void\s+([A-Za-z_$][\w$]*Wax[A-Za-z_$][\w$]*)\s*\(""",
            RegexOption.IGNORE_CASE
        ).find(executable)?.groupValues?.get(1) ?: return null
        val injectMethod = Regex("""public\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{""")
            .findAll(executable)
            .firstOrNull { method ->
                val openBrace = executable.indexOf('{', method.range.first)
                val closeBrace = if (openBrace >= 0) findMatching(executable, openBrace, '{', '}') else -1
                closeBrace > openBrace && executable.substring(openBrace, closeBrace + 1)
                    .contains("WeatheringCopper.NEXT_BY_BLOCK")
            }?.groupValues?.get(1) ?: return null
        return Candidate(
            file,
            packageName,
            className,
            weatheringMap,
            waxableMap,
            addWeathering,
            addWaxable,
            injectMethod
        )
    }

    private fun registrySource(candidate: Candidate): String = """
package ${candidate.packageName};

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.collect.HashBiMap;

import net.minecraft.world.level.block.Block;

public class ${candidate.className} {
    private static final Map<Supplier<Block>, Supplier<Block>> ${candidate.weatheringMap} = HashBiMap.create();
    private static final Map<Supplier<Block>, Supplier<Block>> ${candidate.waxableMap} = HashBiMap.create();

    public static Map<Supplier<Block>, Supplier<Block>> getWeatheringView() {
        return Collections.unmodifiableMap(${candidate.weatheringMap});
    }

    public static Map<Supplier<Block>, Supplier<Block>> getWaxableView() {
        return Collections.unmodifiableMap(${candidate.waxableMap});
    }

    public static synchronized void ${candidate.addWeatheringMethod}(Supplier<Block> original, Supplier<Block> weathered) {
        ${candidate.weatheringMap}.put(original, weathered);
    }

    public static synchronized void ${candidate.addWaxableMethod}(Supplier<Block> original, Supplier<Block> waxed) {
        ${candidate.waxableMap}.put(original, waxed);
    }
}
""".trimIndent() + System.lineSeparator()

    private fun providerSource(candidate: Candidate, providerPackage: String, providerName: String): String = """
package $providerPackage;

import java.util.concurrent.CompletableFuture;

import ${candidate.packageName}.${candidate.className};

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.DataMapProvider;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import net.neoforged.neoforge.registries.datamaps.builtin.Oxidizable;
import net.neoforged.neoforge.registries.datamaps.builtin.Waxable;

public class $providerName extends DataMapProvider {
    public $providerName(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider);
    }

    @Override
    protected void gather(HolderLookup.Provider provider) {
        Builder<Oxidizable, Block> oxidizables = builder(NeoForgeDataMaps.OXIDIZABLES);
        ${candidate.className}.getWeatheringView().forEach((now, after) ->
                oxidizables.add(BuiltInRegistries.BLOCK.wrapAsHolder(now.get()), new Oxidizable(after.get()), false));

        Builder<Waxable, Block> waxables = builder(NeoForgeDataMaps.WAXABLES);
        ${candidate.className}.getWaxableView().forEach((now, after) ->
                waxables.add(BuiltInRegistries.BLOCK.wrapAsHolder(now.get()), new Waxable(after.get()), false));
    }
}
""".trimIndent() + System.lineSeparator()

    private fun removeInjectionCalls(
        javaFiles: List<Path>,
        candidate: Candidate,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val call = Regex(
            """(?m)^[ \t]*(?:${Regex.escape(candidate.className)}|${Regex.escape(candidate.packageName)}\.${Regex.escape(candidate.className)})\.${Regex.escape(candidate.injectMethod)}\(\s*\)\s*;[ \t]*(?:\r?\n)?"""
        )
        for (file in javaFiles) {
            if (file == candidate.file) continue
            val original = file.readText()
            val matches = call.findAll(maskJavaCommentsAndLiterals(original)).toList()
            if (matches.isEmpty()) continue
            var modified = original
            matches.asReversed().forEach { modified = modified.replaceRange(it.range, "") }
            changes += Change(
                file = file,
                line = 1,
                description = "Remove obsolete copper map reflection injection call after data map migration",
                before = "${candidate.className}.${candidate.injectMethod}()",
                after = "data map provider registration",
                confidence = Confidence.HIGH,
                ruleId = "build-copper-datamap-remove-inject-call"
            )
            if (!dryRun) file.writeText(modified)
        }
        return changes
    }

    private fun registerProvider(
        javaFiles: List<Path>,
        providerPackage: String,
        providerName: String,
        dryRun: Boolean
    ): List<Change> {
        val providerFqn = "$providerPackage.$providerName"
        for (file in javaFiles) {
            val original = file.readText()
            val executable = maskJavaCommentsAndLiterals(original)
            if (!executable.contains("GatherDataEvent") || !executable.contains(".getLookupProvider()")) continue
            if (executable.contains("new $providerName(")) return emptyList()

            val eventName = Regex("""GatherDataEvent\s+([A-Za-z_$][\w$]*)""")
                .find(executable)?.groupValues?.get(1) ?: continue
            val generatorName = Regex(
                """DataGenerator\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(eventName)}\.getGenerator\(\)\s*;"""
            ).find(executable)?.groupValues?.get(1) ?: continue
            val outputName = Regex(
                """PackOutput\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(generatorName)}\.getPackOutput\(\)\s*;"""
            ).find(executable)?.groupValues?.get(1) ?: continue
            val lookupName = Regex(
                """CompletableFuture\s*<\s*HolderLookup\.Provider\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(eventName)}\.getLookupProvider\(\)\s*;"""
            ).find(executable)?.groupValues?.get(1) ?: continue
            val assignments = Regex("""(?m)^[ \t]*${Regex.escape(lookupName)}\s*=\s*[^;]+;[ \t]*$""")
                .findAll(executable).toList()
            val insertAfter = (assignments.lastOrNull()?.range?.last
                ?: Regex("""${Regex.escape(eventName)}\.getLookupProvider\(\)\s*;""")
                    .find(executable)?.range?.last
                ?: continue) + 1
            val lineEnd = original.indexOf('\n', insertAfter).let { if (it < 0) insertAfter else it + 1 }
            val currentLineStart = original.lastIndexOf('\n', (insertAfter - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
            val indent = original.substring(currentLineStart, insertAfter.coerceAtLeast(currentLineStart))
                .takeWhile { it == ' ' || it == '\t' }
            val registration = "$indent$generatorName.addProvider($eventName.includeServer(), new $providerName($outputName, $lookupName));" +
                System.lineSeparator()
            var modified = original.substring(0, lineEnd) + registration + original.substring(lineEnd)
            modified = ensureJavaImport(modified, providerFqn)
            if (!dryRun) file.writeText(modified)
            return listOf(Change(
                file = file,
                line = 1,
                description = "Register generated copper data map provider with the existing GatherDataEvent pipeline",
                before = "GatherDataEvent without copper data maps",
                after = "$generatorName.addProvider($eventName.includeServer(), new $providerName(...))",
                confidence = Confidence.HIGH,
                ruleId = "build-copper-datamap-register-provider"
            ))
        }
        error("Copper data map migration requires a statically identifiable GatherDataEvent provider pipeline")
    }

    private fun ensureJavaImport(source: String, importName: String): String {
        if (Regex("""(?m)^\s*import\s+${Regex.escape(importName)}\s*;""").containsMatchIn(source)) return source
        val packageMatch = Regex("""(?m)^\s*package\s+[^;]+;""").find(source) ?: return source
        val insertion = System.lineSeparator() + System.lineSeparator() + "import $importName;"
        return source.substring(0, packageMatch.range.last + 1) + insertion + source.substring(packageMatch.range.last + 1)
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

    private fun maskJavaCommentsAndTextBlocks(source: String): String = maskJava(source, maskStrings = false)

    private fun maskJavaCommentsAndLiterals(source: String): String = maskJava(source, maskStrings = true)

    private fun maskJava(source: String, maskStrings: Boolean): String {
        val chars = source.toCharArray()
        var index = 0
        fun mask(start: Int, endExclusive: Int) {
            for (position in start until endExclusive.coerceAtMost(chars.size)) {
                if (chars[position] != '\n' && chars[position] != '\r') chars[position] = ' '
            }
        }
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                    mask(index, end)
                    index = end
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                    mask(index, end)
                    index = end
                }
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3).let { if (it < 0) source.length else it + 3 }
                    mask(index, end)
                    index = end
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    var end = index + 1
                    var escaped = false
                    while (end < source.length) {
                        val char = source[end]
                        if (char == quote && !escaped) {
                            end++
                            break
                        }
                        escaped = char == '\\' && !escaped
                        if (char != '\\') escaped = false
                        end++
                    }
                    if (maskStrings) mask(index, end)
                    index = end
                }
                else -> index++
            }
        }
        return String(chars)
    }
}
