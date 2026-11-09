package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 1.20.1's `AttributeInstance.hasModifier(AttributeModifier)` looked the modifier up by its id; on
 * 1.21.1 `hasModifier` takes that id (`ResourceLocation`, class table), which the modifier record
 * carries as `id()` (`removeModifier` still takes either). A call handing it a project
 * `AttributeModifier` field (declared anywhere in the project) or a name declared
 * `AttributeModifier` where the call stands passes the id.
 */
internal object AttributeModifierIds {
    const val RULE_ID = "struct-attribute-modifier-ids"
    private const val ID = """[A-Za-z_$][\w$]*"""

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        // Owner.FIELD and FIELD of the project's AttributeModifier fields
        val fields = sources.flatMap { (file, source) ->
            val owner = file.fileName.toString().removeSuffix(".java")
            Regex("""(?m)^[ \t]*(?:(?:public|protected|private|static|final)\s+)+(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?AttributeModifier\s+($ID)\s*[=;]""")
                .findAll(JavaSourceText.maskCommentsAndLiterals(source))
                .flatMap { sequenceOf(it.groupValues[1], "$owner.${it.groupValues[1]}") }
                .toList()
        }.toSet()
        val changes = mutableListOf<Change>()
        for ((file, source) in sources) {
            if (!source.contains("hasModifier(")) continue
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val calls = Regex("""\.\s*(hasModifier)\s*\(\s*((?:$ID\.)?$ID)\s*\)""").findAll(masked).filter { call ->
                val argument = call.groupValues[2]
                argument in fields || (!argument.contains('.') &&
                    com.modporter.core.transforms.util.JavaScopedDeclarations.visibleAt(masked, argument, call.range.first)?.type == "AttributeModifier")
            }.toList()
            if (calls.isEmpty()) continue
            var result = source
            for (call in calls.asReversed()) {
                val argument = call.groups[2]!!
                result = result.substring(0, argument.range.last + 1) + ".id()" + result.substring(argument.range.last + 1)
            }
            if (!dryRun) file.writeText(result)
            changes += Change(file, 1, "Attribute modifier lookups pass the modifier's id", "hasModifier(modifier)", "hasModifier(modifier.id())", Confidence.HIGH, RULE_ID)
        }
        return changes
    }
}
