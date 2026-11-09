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
 * 1.20.5 replaced the `ParticleOptions` write/read contract (`writeToNetwork`, `writeToString`,
 * `ParticleOptions.Deserializer#fromCommand/fromNetwork`, `ParticleType(boolean, Deserializer)`
 * with an abstract `Codec<T> codec()`) by codecs: `ParticleType(boolean)` with abstract
 * `MapCodec<T> codec()` and `StreamCodec<? super RegistryFriendlyByteBuf, T> streamCodec()`.
 *
 * A mod's particle data keeps its own writer and deserializer behind two generated interfaces
 * (`LegacyParticleOptions`, `ParticleDeserializer`) and every `new ParticleType<>(flag, deserializer)`
 * gains the stream codec built from them (`LegacyParticleTypes.streamCodec(this, deserializer)`),
 * while its `Codec` becomes the `MapCodec` the type now requires.
 */
internal object ParticleOptionsCodecs {
    const val RULE_ID = "struct-particle-options-codecs"
    private const val ID = """[A-Za-z_$][\w$]*"""

    fun migrate(
        projectDir: Path,
        compatPackageSupplier: () -> String,
        dryRun: Boolean,
        excludedFiles: Set<Path> = emptySet()
    ): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val excluded = excludedFiles.mapTo(hashSetOf()) { it.toAbsolutePath().normalize() }
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths ->
            paths.filter {
                it.extension == "java" && it.toAbsolutePath().normalize() !in excluded
            }.toList()
        }
        val legacy = files.filter { file ->
            val source = runCatching { file.readText() }.getOrDefault("")
            val executable = JavaSourceText.maskCommentsAndLiterals(source)
            val alreadyClosedGraph =
                executable.contains("interface ParticleDeserializer") &&
                    executable.contains("getStreamCodec(") &&
                    executable.contains("RegistryFriendlyByteBuf")
            if (alreadyClosedGraph) return@filter false
            source.contains("ParticleOptions") && (
                source.contains("Deserializer") || source.contains("writeToNetwork(") || source.contains("writeToString(") ||
                    Regex("""new\s+ParticleType\s*<[^>]*>\s*\([^;{]*,[^;{]*\)\s*\{""").containsMatchIn(source)
                )
        }
        if (legacy.isEmpty()) return emptyList()
        val compatPackage = compatPackageSupplier()

        val changes = mutableListOf<Change>()
        changes += compatSources(srcDir, compatPackage, "FriendlyByteBuf", dryRun)
        for (file in legacy) {
            val source = file.readText()
            val migrated = migrateSource(source, compatPackage)
            if (migrated == source) continue
            if (!dryRun) file.writeText(migrated)
            changes += Change(
                file = file,
                line = 1,
                description = "Particle data keeps its legacy writer/deserializer behind compat interfaces and its ParticleType gains a stream codec",
                before = "ParticleOptions.Deserializer / ParticleType(boolean, Deserializer) / Codec codec()",
                after = "ParticleDeserializer / ParticleType(boolean) with MapCodec codec() and streamCodec()",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        return changes
    }

    /**
     * Particle stream codecs are only ever handed a `RegistryFriendlyByteBuf` (`ParticleType#streamCodec`
     * is a `StreamCodec<? super RegistryFriendlyByteBuf, T>`). Once a single-file rule declared one
     * implementation's writer or reader on that buffer (a fluid stack it writes, say), the generated
     * contract and bridge carry it, and every other implementation takes it too.
     */
    fun alignRegistryBuffers(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val contract = files.filter { it.fileName.toString() in setOf("LegacyParticleOptions.java", "ParticleDeserializer.java", "LegacyParticleTypes.java") }
            .filter { file -> file.readText().let { it.contains("The 1.20 ParticleOptions") || it.contains("through the 1.20 contract") } }
        if (contract.isEmpty()) return emptyList()
        val implementations = (files - contract.toSet()).associateWith { runCatching { it.readText() }.getOrDefault("") }
            .filterValues { it.contains("LegacyParticleOptions") || it.contains("ParticleDeserializer") }
        val registryBuffer = implementations.values.any { source ->
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            (writerBuffer.findAll(masked) + readerBuffer.findAll(masked)).any { it.groupValues[2].endsWith("RegistryFriendlyByteBuf") }
        }
        if (!registryBuffer) return emptyList()
        val changes = mutableListOf<Change>()
        for ((file, source) in implementations + contract.associateWith { it.readText() }) {
            val migrated = if (file in contract) source.replace(Regex("""(?<![\w$])FriendlyByteBuf\b"""), "RegistryFriendlyByteBuf") else widenBuffers(source)
            if (migrated == source) continue
            if (!dryRun) file.writeText(migrated)
            changes += Change(file, 1, "The legacy particle contract carries the RegistryFriendlyByteBuf its implementations read and write",
                "FriendlyByteBuf", "RegistryFriendlyByteBuf", Confidence.HIGH, RULE_ID)
        }
        return changes
    }

    fun migrateSource(source: String, compatPackage: String): String {
        var result = source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)

        // Deserializer type references
        if (Regex("""(?<![\w$])(?:ParticleOptions\.)?Deserializer\s*<""").containsMatchIn(masked)) {
            result = Regex("""(?m)^import\s+net\.minecraft\.core\.particles\.ParticleOptions\.Deserializer;[ \t]*\r?\n""").replace(result, "")
            result = replaceCode(result, Regex("""(?<![\w$])(?:ParticleOptions\.)?Deserializer(\s*<)""")) { "ParticleDeserializer${it.groupValues[1]}" }
            result = JavaSourceText.addImport(result, "$compatPackage.ParticleDeserializer")
        }

        // Classes that still write themselves implement the legacy contract
        val writes = Regex("""\b(?:public\s+)?(?:void\s+writeToNetwork|String\s+writeToString)\s*\(""").containsMatchIn(masked)
        if (writes) {
            val header = Regex("""(\b(?:class|record)\s+$ID[^{]*?\bimplements\b[^{]*?)(?<![\w$.])ParticleOptions(?![\w$<])""")
            val migrated = replaceCode(result, header) { "${it.groupValues[1]}LegacyParticleOptions" }
            if (migrated != result) {
                result = JavaSourceText.addImport(migrated, "$compatPackage.LegacyParticleOptions")
            }
        }

        // new ParticleType<>(flag, deserializer) { Codec<T> codec() {...} }
        result = migrateAnonymousTypes(result, compatPackage)
        return result
    }

    private fun migrateAnonymousTypes(source: String, compatPackage: String): String {
        var result = source
        val creation = Regex("""new\s+ParticleType\s*<([^>]*)>\s*\(""")
        var cursor = 0
        var changed = false
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val match = creation.find(masked, cursor) ?: break
            val openParen = match.range.last
            val closeParen = JavaSourceText.matchingParen(masked, openParen)
            if (closeParen < 0) break
            val args = JavaSourceText.splitTopLevelArgs(result.substring(openParen + 1, closeParen)).map { it.trim() }
            val bodyOpen = masked.indexOf('{', closeParen)
            if (args.size != 2 || bodyOpen < 0 || masked.substring(closeParen + 1, bodyOpen).isNotBlank()) {
                cursor = closeParen + 1
                continue
            }
            val bodyClose = JavaSourceText.matchingBrace(masked, bodyOpen)
            if (bodyClose < 0) break
            val body = result.substring(bodyOpen + 1, bodyClose)
            val codec = Regex("""(?s)(@Override\s*)?(public\s+)Codec\s*<(.+?)>\s+codec\s*\(\s*\)\s*\{""").find(body)
            if (codec == null) {
                cursor = closeParen + 1
                continue
            }
            val dataType = codec.groupValues[3].trim()
            val codecOpen = codec.range.last
            val codecClose = JavaSourceText.matchingBrace(JavaSourceText.maskCommentsAndLiterals(body), codecOpen)
            if (codecClose < 0) break
            val codecBody = body.substring(codecOpen + 1, codecClose)
            val wrappedBody = Regex("""\breturn\s+([^;]+);""").replace(codecBody) { "return MapCodec.assumeMapUnsafe(${it.groupValues[1].trim()});" }
            val indent = Regex("""(?m)^([ \t]*)@Override|^([ \t]*)public\s+Codec""").find(body)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: "\t\t\t"
            val newline = if (result.contains("\r\n")) "\r\n" else "\n"
            val innerIndent = Regex("""(?m)^([ \t]*)return\b""").find(codecBody)?.groupValues?.get(1) ?: (indent + "\t")
            val streamCodec = "$newline$newline$indent@Override$newline${indent}public StreamCodec<? super RegistryFriendlyByteBuf, $dataType> streamCodec() {" +
                "$newline${innerIndent}return LegacyParticleTypes.streamCodec(this, ${args[1]});$newline$indent}"
            val migratedBody = body.substring(0, codec.range.first) +
                "${codec.groupValues[1]}${codec.groupValues[2]}MapCodec<$dataType> codec() {" + wrappedBody + "}" + streamCodec +
                body.substring(codecClose + 1)
            result = result.substring(0, openParen + 1) + args[0] + ")" + result.substring(closeParen + 1, bodyOpen + 1) + migratedBody + result.substring(bodyClose)
            changed = true
            cursor = openParen + 1
        }
        if (!changed) return source
        result = JavaSourceText.addImport(result, "com.mojang.serialization.MapCodec")
        result = JavaSourceText.addImport(result, "net.minecraft.network.RegistryFriendlyByteBuf")
        result = JavaSourceText.addImport(result, "net.minecraft.network.codec.StreamCodec")
        result = JavaSourceText.addImport(result, "$compatPackage.LegacyParticleTypes")
        if (!Regex("""(?<![\w$.])Codec\s*<""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result).replace(Regex("""(?m)^import .*$"""), ""))) {
            result = JavaSourceText.removeImport(result, "com.mojang.serialization.Codec")
        }
        return result
    }

    private val writerBuffer = Regex("""(\bvoid\s+writeToNetwork\s*\(\s*(?:final\s+)?)((?:net\.minecraft\.network\.)?(?:Registry)?FriendlyByteBuf)(?=\s+$ID\s*\))""")
    private val readerBuffer = Regex("""(\bfromNetwork\s*\(\s*(?:final\s+)?(?:[\w.]+\.)?ParticleType\s*<(?:[^<>]|<[^<>]*>)*>\s+$ID\s*,\s*(?:final\s+)?)((?:net\.minecraft\.network\.)?(?:Registry)?FriendlyByteBuf)(?=\s+$ID\s*\))""")

    /** The legacy writer and reader take the RegistryFriendlyByteBuf the widened contract declares. */
    private fun widenBuffers(source: String): String {
        if (!source.contains("LegacyParticleOptions") && !source.contains("ParticleDeserializer")) return source
        var result = source
        for (pattern in listOf(writerBuffer, readerBuffer)) {
            result = replaceCode(result, pattern) { match ->
                if (match.groupValues[2].endsWith("RegistryFriendlyByteBuf")) match.value
                else "${match.groupValues[1]}net.minecraft.network.RegistryFriendlyByteBuf"
            }
        }
        return result
    }

    private fun replaceCode(source: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = pattern.findAll(masked).map { it.range to replacement(it) }.toList()
        return edits.asReversed().fold(source) { text, (range, text2) -> text.replaceRange(range, text2) }
    }

    private fun compatSources(srcDir: Path, compatPackage: String, buffer: String, dryRun: Boolean): List<Change> {
        val sources = mapOf(
            "ParticleDeserializer" to """
                |package $compatPackage;
                |
                |import com.mojang.brigadier.StringReader;
                |import com.mojang.brigadier.exceptions.CommandSyntaxException;
                |import net.minecraft.core.particles.ParticleOptions;
                |import net.minecraft.core.particles.ParticleType;
                |import net.minecraft.network.$buffer;
                |
                |/** The 1.20 ParticleOptions.Deserializer contract, kept for particle data that still reads itself. */
                |public interface ParticleDeserializer<T extends ParticleOptions> {
                |    T fromCommand(ParticleType<T> type, StringReader reader) throws CommandSyntaxException;
                |
                |    T fromNetwork(ParticleType<T> type, $buffer buffer);
                |}
                |""".trimMargin(),
            "LegacyParticleOptions" to """
                |package $compatPackage;
                |
                |import net.minecraft.core.particles.ParticleOptions;
                |import net.minecraft.network.$buffer;
                |
                |/** The 1.20 ParticleOptions write contract, kept for particle data that still writes itself. */
                |public interface LegacyParticleOptions extends ParticleOptions {
                |    void writeToNetwork($buffer buffer);
                |
                |    String writeToString();
                |}
                |""".trimMargin(),
            "LegacyParticleTypes" to """
                |package $compatPackage;
                |
                |import net.minecraft.core.particles.ParticleOptions;
                |import net.minecraft.core.particles.ParticleType;
                |import net.minecraft.network.$buffer;
                |import net.minecraft.network.codec.StreamCodec;
                |
                |/** Stream codecs for particle data that still writes and reads itself through the 1.20 contract. */
                |public final class LegacyParticleTypes {
                |    private LegacyParticleTypes() {
                |    }
                |
                |    public static <T extends ParticleOptions> StreamCodec<$buffer, T> streamCodec(ParticleType<T> type, ParticleDeserializer<T> deserializer) {
                |        return StreamCodec.of((buffer, data) -> ((LegacyParticleOptions) data).writeToNetwork(buffer), buffer -> deserializer.fromNetwork(type, buffer));
                |    }
                |}
                |""".trimMargin()
        )
        val changes = mutableListOf<Change>()
        for ((name, text) in sources) {
            val file = srcDir.resolve(compatPackage.replace('.', '/')).resolve("$name.java")
            if (file.exists() && file.readText() == text) continue
            if (!dryRun) {
                file.parent.createDirectories()
                file.writeText(text)
            }
            changes += Change(file, 0, "Generate $name for the legacy particle options contract", "(none)", "$compatPackage.$name", Confidence.HIGH, RULE_ID)
        }
        return changes
    }
}
