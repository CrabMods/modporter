package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The source API of Forge EventBus 6.0.3's GenericEvent, on NeoForge's Event base. */
internal fun StructuralRefactorPass.migrateLegacyGenericEvents(projectDir: Path, dryRun: Boolean): List<Change> {
    val root = projectDir.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val owners = listOf("net.minecraftforge.eventbus.api", "net.neoforged.bus.api")
    val sources = walkJavaSourceRoots(root).use { files ->
        files.filter { it.toString().endsWith(".java") }.map { it to it.readText() }.toList()
    }
    val affected = sources.filter { (_, text) ->
        val code = maskJavaCommentsAndLiterals(text)
        owners.any { owner -> code.contains("$owner.GenericEvent") || code.contains("$owner.IGenericEvent") }
    }
    if (affected.isEmpty()) return emptyList()
    // The target bus has no generic-filter registration. Do not silently discard a filter.
    require(sources.none { (_, text) -> Regex("""\.\s*addGenericListener\s*\(""").containsMatchIn(maskJavaCommentsAndLiterals(text)) }) {
        "Generic event listener filters require explicit migration before adapting GenericEvent"
    }
    val compat = detectRequiredGeneratedCompatPackage(projectDir, "legacy generic event base")
    val changes = mutableListOf<Change>()
    for ((file, source) in affected) {
        var result = source
        for (owner in owners) {
            for (type in listOf("GenericEvent", "IGenericEvent")) {
                result = replaceExecutableJavaRegex(result, Regex("""(?<![\w$.])${Regex.escape(owner)}\.$type(?![\w$])""")) {
                    "$compat.$type"
                }
            }
        }
        if (result == source) continue
        if (!dryRun) file.writeText(result)
        changes += Change(file, 1, "Retain the legacy generic event API on NeoForge Event",
            "Forge GenericEvent", "generated GenericEvent extending NeoForge Event", Confidence.HIGH, "struct-generic-event-base")
    }
    val directory = root.resolve(compat.replace('.', '/'))
    val generated = mapOf(
        "IGenericEvent.java" to """
            package $compat;

            public interface IGenericEvent<T> {
                java.lang.reflect.Type getGenericType();
            }
        """.trimIndent(),
        "GenericEvent.java" to """
            package $compat;

            public class GenericEvent<T> extends net.neoforged.bus.api.Event implements IGenericEvent<T> {
                private Class<T> type;

                public GenericEvent() {
                }

                protected GenericEvent(Class<T> type) {
                    this.type = type;
                }

                @Override
                public java.lang.reflect.Type getGenericType() {
                    return type;
                }
            }
        """.trimIndent()
    )
    for ((name, text) in generated) {
        val target = directory.resolve(name)
        if (!dryRun) {
            directory.createDirectories()
            target.writeText(text + "\n")
        }
        changes += Change(target, 1, "Provide the source-compatible generic event type contract",
            "Forge EventBus GenericEvent and IGenericEvent", "NeoForge Event subclass and type accessor", Confidence.HIGH, "struct-generic-event-base")
    }
    return changes
}
