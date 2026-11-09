package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * NeoForge has no `DistExecutor`. Statement-shaped uses become `FMLEnvironment.dist` guards earlier
 * in the pass; remaining typed call/run expressions use a generated `DistExecution` adapter on
 * `FMLLoader.getDist()`. Unsupported members fail before package generation rather than guessing.
 */
internal object DistExecutorCompat {
    const val RULE_ID = "struct-dist-executor-typed-adapter"
    const val SOURCE_RULE_ID = "struct-dist-executor-typed-adapter-source"
    private val imports = listOf("net.neoforged.fml.DistExecutor", "net.minecraftforge.fml.DistExecutor")
    private val supportedMembers = setOf(
        "unsafeCallWhenOn",
        "safeCallWhenOn",
        "unsafeRunWhenOn",
        "safeRunWhenOn"
    )

    fun migrate(projectDir: Path, compatPackageSupplier: () -> String, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sourceByFile = files.associateWith { file ->
            runCatching { file.readText() }.getOrDefault("")
        }
        val forgeImportByFile = sourceByFile.mapValues { (_, source) ->
            val executable = JavaSourceText.maskCommentsAndLiterals(source)
            imports.any { import ->
                Regex("""(?m)^import\s+${Regex.escape(import)};[ \t]*\r?$""").containsMatchIn(executable)
            }
        }
        val simpleMemberPattern = Regex("""(?<![\w$.])DistExecutor\.([A-Za-z_$][\w$]*)\s*\(""")
        val qualifiedMemberPattern = Regex(
            """(?<![\w$])net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor\.([A-Za-z_$][\w$]*)\s*\("""
        )
        val membersByFile = sourceByFile.mapValues { (file, source) ->
            val executable = JavaSourceText.maskCommentsAndLiterals(source)
            buildSet {
                if (forgeImportByFile.getValue(file)) {
                    simpleMemberPattern.findAll(executable).forEach { add(it.groupValues[1]) }
                }
                qualifiedMemberPattern.findAll(executable).forEach { add(it.groupValues[1]) }
            }
        }
        val unsupported = membersByFile.values.flatten().filter { it !in supportedMembers }.toSet()
        if (unsupported.isNotEmpty()) {
            throw IllegalStateException(
                "unsupported DistExecutor members: ${unsupported.sorted().joinToString()}"
            )
        }
        val leftovers = files.filter { file ->
            forgeImportByFile.getValue(file) || membersByFile.getValue(file).isNotEmpty()
        }
        if (leftovers.isEmpty()) return emptyList()
        val callFiles = leftovers.filter { file ->
            membersByFile.getValue(file).isNotEmpty()
        }
        val compatPackage = if (callFiles.isNotEmpty()) compatPackageSupplier() else null
        val changes = mutableListOf<Change>()
        if (compatPackage != null) {
            val compatFile = srcDir.resolve(compatPackage.replace('.', '/')).resolve("DistExecution.java")
            val compatSource = compatSource(compatPackage)
            if (!compatFile.exists() || compatFile.readText() != compatSource) {
                if (!dryRun) {
                    compatFile.parent.createDirectories()
                    compatFile.writeText(compatSource)
                }
                changes += Change(
                    compatFile,
                    0,
                    "Generate typed DistExecutor call/run adapter on FMLLoader.getDist()",
                    "(none)",
                    "$compatPackage.DistExecution",
                    Confidence.HIGH,
                    SOURCE_RULE_ID
                )
            }
        }
        for (file in leftovers) {
            val source = sourceByFile.getValue(file)
            var migrated = source
            for (import in imports) migrated = JavaSourceText.removeImport(migrated, import)
            if (compatPackage != null && file in callFiles) {
                if (forgeImportByFile.getValue(file)) {
                    migrated = replaceCode(
                        migrated,
                        Regex("""(?<![\w$.])DistExecutor\.(?:unsafe|safe)CallWhenOn(?=\s*\()""")
                    ) { "$compatPackage.DistExecution.callWhenOn" }
                    migrated = replaceCode(
                        migrated,
                        Regex("""(?<![\w$.])DistExecutor\.(?:unsafe|safe)RunWhenOn(?=\s*\()""")
                    ) { "$compatPackage.DistExecution.runWhenOn" }
                }
                migrated = replaceCode(
                    migrated,
                    Regex(
                        """(?<![\w$])net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor\.(?:unsafe|safe)CallWhenOn(?=\s*\()"""
                    )
                ) { "$compatPackage.DistExecution.callWhenOn" }
                migrated = replaceCode(
                    migrated,
                    Regex(
                        """(?<![\w$])net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor\.(?:unsafe|safe)RunWhenOn(?=\s*\()"""
                    )
                ) { "$compatPackage.DistExecution.runWhenOn" }
            }
            if (migrated == source) continue
            if (!dryRun) file.writeText(migrated)
            changes += Change(
                file,
                1,
                "Replace remaining typed DistExecutor calls with the generated adapter",
                "DistExecutor call/run expression",
                compatPackage?.let { "$it.DistExecution" } ?: "(removed unused import)",
                Confidence.HIGH,
                RULE_ID
            )
        }
        return changes
    }

    private fun replaceCode(source: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = pattern.findAll(masked).map { match -> match.range to replacement(match) }.toList()
        if (edits.isEmpty()) return source
        var result = source
        edits.asReversed().forEach { (range, text) ->
            result = result.replaceRange(range, text)
        }
        return result
    }

    private fun compatSource(compatPackage: String): String = """
        |package $compatPackage;
        |
        |import java.util.concurrent.Callable;
        |import java.util.function.Supplier;
        |import net.neoforged.api.distmarker.Dist;
        |import net.neoforged.fml.loading.FMLLoader;
        |
        |/** Typed replacement for the remaining Forge DistExecutor expression contracts. */
        |public final class DistExecution {
        |    private DistExecution() {
        |    }
        |
        |    public static void runWhenOn(Dist dist, Supplier<? extends Runnable> target) {
        |        if (FMLLoader.getDist() == dist) {
        |            target.get().run();
        |        }
        |    }
        |
        |    public static <T> T callWhenOn(Dist dist, Supplier<? extends Callable<T>> target) {
        |        if (FMLLoader.getDist() != dist) {
        |            return null;
        |        }
        |        try {
        |            return target.get().call();
        |        } catch (Exception e) {
        |            throw new RuntimeException(e);
        |        }
        |    }
        |}
        |""".trimMargin()
}
