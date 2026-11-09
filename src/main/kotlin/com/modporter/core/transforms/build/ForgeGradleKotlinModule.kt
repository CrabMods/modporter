package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A ForgeGradle 6 Kotlin-DSL build script of a mod module inside a multi-project build, migrated
 * statement by statement to ModDevGradle (the line rules for single-project MDK scripts would cut
 * multi-line statements apart):
 * - `plugins`: `net.minecraftforge.gradle` becomes `net.neoforged.moddev`; MixinGradle goes;
 * - ForgeGradle imports, the Forge maven repository block, the `"minecraft"(forge)` dependency and the
 *   mixin annotation processor go;
 * - `configure<UserDevExtension>` becomes a `neoForge { }` block with the same access transformer,
 *   the client/server runs plus the GameTest server, and the mod's source set;
 * - `configure<MixinExtension>` goes (NeoForge reads `[[mixins]]` from neoforge.mods.toml, which the
 *   resource migration writes) and so does everything that only exists for reobfuscation
 *   (`reobf*` configurations, variants and tasks, `RenameJarInPlace`), since 1.21 runs on Mojang names;
 * - the Minecraft and Forge version values become 1.21.1 and the NeoForge version, so the
 *   mods.toml expansion they feed keeps describing what the module builds against;
 * - a `javaRelease` below 21 becomes 21, the bytecode level of Minecraft 1.21, and the module (only
 *   the module: its sibling projects keep the build's convention) compiles with a Java 21 toolchain;
 * - resource expansion of `META-INF/mods.toml` follows its rename to `neoforge.mods.toml`;
 * - the configuration ForgeGradle's runs put on the game classpath through
 *   `lazyToken("minecraft_classpath")` (non-mod libraries such as the build's own core project)
 *   becomes ModDev's `additionalRuntimeClasspath`, extending what that configuration extended.
 */
internal object ForgeGradleKotlinModule {
    private const val MINECRAFT_VERSION = "1.21.1"

    fun applies(script: String): Boolean =
        script.contains("net.minecraftforge.gradle") && (script.contains("UserDevExtension") || script.contains("\"minecraft\"("))

    fun migrate(file: Path, modId: String, neoForgeVersion: String, dryRun: Boolean): List<Change> {
        val original = file.readText()
        if (!applies(original)) return emptyList()
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val statements = KotlinScriptStatements.split(original)
        // the default java variants are skipped only to publish the reobfuscated ones instead
        val reobfVariants = statements.any { Regex("""addVariantsFromConfiguration\s*\(\s*reobf""").containsMatchIn(it) }
        val skippedDefaults = Regex("""withVariantsFromConfiguration\s*\(\s*configurations\s*\[\s*"(?:apiElements|runtimeElements)"\s*]\s*\)\s*\{\s*skip\(\)\s*}""")
        val runClasspathParents = runClasspathParents(statements)
        val migrated = statements
            .filterNot { reobfVariants && skippedDefaults.containsMatchIn(it) }
            .mapNotNull { statement -> migrateStatement(statement, modId, neoForgeVersion, newline, runClasspathParents) }
            // the resource migration renames the mod metadata the build expands
            .map { it.replace("\"META-INF/mods.toml\"", "\"META-INF/neoforge.mods.toml\"") }
        val result = migrated.joinToString("").replace(Regex("""(\r?\n){3,}"""), "$newline$newline")
        if (result == original) return emptyList()
        if (!dryRun) file.writeText(result)
        return listOf(
            Change(file, 0, "ForgeGradle Kotlin-DSL module build becomes a ModDevGradle build",
                "net.minecraftforge.gradle + configure<UserDevExtension> + reobf", "net.neoforged.moddev + neoForge { }",
                Confidence.HIGH, "build-forgegradle-kotlin-module")
        )
    }

    /**
     * What the configuration resolved into `lazyToken("minecraft_classpath")` extended
     * (`extendsFrom(...)` arguments of its `configurations.create`), or empty when there is none.
     */
    private fun runClasspathParents(statements: List<String>): List<String> {
        val token = statements.firstNotNullOfOrNull {
            Regex("""lazyToken\s*\(\s*"minecraft_classpath"\s*\)\s*\{\s*(\w+)\s*\.\s*resolve\(\)""").find(it)?.groupValues?.get(1)
        } ?: return emptyList()
        val creation = statements.firstOrNull { Regex("""^\s*val\s+${Regex.escape(token)}\s*=\s*configurations\.create\(""").containsMatchIn(it) }
            ?: return emptyList()
        return Regex("""extendsFrom\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)""").findAll(creation).map { it.groupValues[1].trim() }.toList()
    }

    private fun migrateStatement(statement: String, modId: String, neoForgeVersion: String, newline: String, runClasspathParents: List<String>): String? {
        val code = statement.trim()
        return when {
            code.startsWith("import net.minecraftforge.gradle.") -> null
            code.startsWith("plugins") -> statement
                .replace(Regex("""id\s*\(\s*"net\.minecraftforge\.gradle"\s*\)"""), "id(\"net.neoforged.moddev\")")
                .replace(Regex("""(?m)^[ \t]*id\s*\(\s*"org\.spongepowered\.mixin"\s*\)[^\r\n]*\r?\n"""), "")
            Regex("""^configure\s*<\s*(?:[\w.]+\.)?UserDevExtension\s*>""").containsMatchIn(code) ->
                neoForgeBlock(statement, modId, neoForgeVersion, newline, runClasspathParents)
            Regex("""^configure\s*<\s*(?:[\w.]+\.)?MixinExtension\s*>""").containsMatchIn(code) -> null
            Regex("""\breobf|RenameJarInPlace""").containsMatchIn(code) -> null
            code.startsWith("repositories") && code.contains("maven.minecraftforge.net") && !hasOtherRepository(code) -> null
            code.startsWith("dependencies") -> statement
                .replace(Regex("""(?m)^[ \t]*"minecraft"\s*\(\s*"net\.minecraftforge:forge:[^"]*"\s*\)[^\r\n]*\r?\n"""), "")
                .replace(Regex("""(?m)^[ \t]*"annotationProcessor"\s*\(\s*"org\.spongepowered:mixin:[^"]*"\s*\)[^\r\n]*\r?\n"""), "")
            Regex("""^val\s+minecraftVersion\s*=\s*"[^"]*"""").containsMatchIn(code) ->
                Regex("""=\s*"[^"]*"""").replace(statement, "= \"$MINECRAFT_VERSION\"")
            Regex("""^val\s+forgeVersion\s*=\s*"[^"]*"""").containsMatchIn(code) ->
                Regex("""=\s*"[^"]*"""").replace(statement, "= \"$neoForgeVersion\"")
            Regex("""\bjavaRelease\s*=\s*(\d+)""").find(code)?.groupValues?.get(1)?.toIntOrNull()?.let { it < 21 } == true ->
                Regex("""\bjavaRelease\s*=\s*\d+""").replace(statement, "javaRelease = 21")
            Regex("""^val\s+(\w+)\s*=\s*configurations\.create\(""").find(code)?.let { usedOnlyByForgeGradle(it.groupValues[1], code) } == true -> null
            else -> statement
        }
    }

    /** The classpath ForgeGradle 6 handed its runs through `lazyToken("minecraft_classpath")`. */
    private fun usedOnlyByForgeGradle(name: String, code: String): Boolean = name == "apiClasspath" && code.contains("extendsFrom")

    private fun hasOtherRepository(code: String): Boolean =
        Regex("""\bmaven\s*\{""").findAll(code).count() > 1 || Regex("""\b(?:mavenCentral|mavenLocal|google)\s*\(""").containsMatchIn(code)

    private fun neoForgeBlock(statement: String, modId: String, neoForgeVersion: String, newline: String, runClasspathParents: List<String>): String {
        val accessTransformers = Regex("""accessTransformer\s*\(\s*(file\s*\(\s*"[^"]+"\s*\)|"[^"]+")\s*\)""").findAll(statement)
            .map { it.groupValues[1] }.toList()
        val runNames = Regex("""\bcreate\s*\(\s*"(\w+)"""").findAll(statement).map { it.groupValues[1] }.toSet()
        val lines = mutableListOf("neoForge {", "    version = \"$neoForgeVersion\"")
        if (accessTransformers.isNotEmpty()) {
            lines += ""
            accessTransformers.forEach { lines += "    accessTransformers.from($it)" }
        }
        lines += listOf("", "    runs {")
        if ("client" in runNames || runNames.isEmpty()) {
            lines += listOf("        register(\"client\") {", "            client()", "        }")
            lines += listOf(
                "        register(\"clientWorld\") {",
                "            client()",
                "            programArguments.addAll(\"--quickPlayPath\", \"quickplay/modporter_smoke_world.json\", \"--quickPlaySingleplayer\", \"modporter_smoke_world\")",
                "        }"
            )
        }
        if ("server" in runNames || runNames.isEmpty()) {
            lines += listOf("        register(\"server\") {", "            server()", "            programArgument(\"--nogui\")", "        }")
        }
        lines += listOf(
            "        register(\"gameTestServer\") {",
            "            type = \"gameTestServer\"",
            "            systemProperty(\"neoforge.enabledGameTestNamespaces\", \"$modId\")",
            "        }",
            "    }",
            "",
            "    mods {",
            "        register(\"$modId\") {",
            "            sourceSet(sourceSets[\"main\"])",
            "        }",
            "    }",
            "}",
            "",
            "configure<JavaPluginExtension> {",
            "    toolchain.languageVersion.set(JavaLanguageVersion.of(21))",
            "}"
        )
        if (runClasspathParents.isNotEmpty()) {
            lines += listOf("", "configurations.named(\"additionalRuntimeClasspath\") {")
            runClasspathParents.forEach { lines += "    extendsFrom($it)" }
            lines += "}"
        }
        val indent = statement.takeWhile { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        val trailing = statement.takeLastWhile { it == '\r' || it == '\n' }
        return indent + lines.joinToString(newline) + trailing.ifEmpty { newline }
    }
}

/**
 * The top-level statements of a Kotlin build script, each with the blank lines and comments before
 * it, so that joining them gives the script back. A statement ends at a line break where every
 * bracket opened in it is closed and the next code line does not continue it with a `.` call.
 */
internal object KotlinScriptStatements {
    fun split(script: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var index = 0
        var inString = false
        var rawString = false
        var lineComment = false
        var blockComment = false
        var hasCode = false
        while (index < script.length) {
            val char = script[index]
            val next = script.getOrNull(index + 1)
            current.append(char)
            when {
                lineComment -> if (char == '\n') lineComment = false
                blockComment -> if (char == '*' && next == '/') { current.append(next); index++; blockComment = false }
                rawString -> if (script.startsWith("\"\"\"", index)) { current.append("\"\""); index += 2; rawString = false }
                inString -> if (char == '\\') { next?.let { current.append(it) }; index++ } else if (char == '"') inString = false
                char == '/' && next == '/' -> lineComment = true
                char == '/' && next == '*' -> blockComment = true
                script.startsWith("\"\"\"", index) -> { current.append("\"\""); index += 2; rawString = true; hasCode = true }
                char == '"' -> { inString = true; hasCode = true }
                char == '{' || char == '(' || char == '[' -> { depth++; hasCode = true }
                char == '}' || char == ')' || char == ']' -> depth--
                !char.isWhitespace() -> hasCode = true
            }
            if (char == '\n' && depth <= 0 && hasCode && !inString && !rawString && !blockComment) {
                val rest = script.substring(index + 1).trimStart()
                if (!rest.startsWith(".") && !rest.startsWith("?.")) {
                    statements += current.toString()
                    current.clear()
                    hasCode = false
                    depth = 0
                }
            }
            index++
        }
        if (current.isNotEmpty()) statements += current.toString()
        return statements
    }
}
