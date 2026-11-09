package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Client source boundaries whose actual provider signatures are known only after propagation. */
internal object LateClientBaseApiMigration {
    fun migrate(pass: StructuralRefactorPass, project: Path, dryRun: Boolean): List<Change> {
        val root = project.resolve("src/main/java")
        if (!root.isDirectory()) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val planned = pass.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.extension == "java" }.toList().mapNotNull { file ->
                val original = file.readText()
                val migrated = pass.migrateLegacyClientBaseApiCompat(original)
                if (original == migrated) return@mapNotNull null
                val parsed = parser.parse(migrated)
                require(parsed.isSuccessful) {
                    "Late client API migration emitted invalid source in $file: ${pass.parseProblems(parsed)}"
                }
                Triple(file, original, migrated)
            }
        }
        // Validate every proposed source before publishing any of this pass's edits.
        if (!dryRun) planned.forEach { (file, original, migrated) ->
            require(file.readText() == original) { "Client source changed during late API migration: $file" }
            file.writeText(migrated)
        }
        return planned.map { (file, original, migrated) ->
            Change(
                file = file,
                line = 1,
                description = "Migrate declaration-proven client API and provider boundaries",
                before = original.take(160),
                after = migrated.take(160),
                confidence = Confidence.HIGH,
                ruleId = "struct-late-client-base-api"
            )
        }
    }
}
