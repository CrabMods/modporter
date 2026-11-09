package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A multi-project build that applies its loader plugin by id from convention code puts the plugin
 * on the `buildSrc` classpath instead of a `plugins { id(...) version ... }` block. There
 * ForgeGradle (`net.minecraftforge.gradle:ForgeGradle`) becomes ModDevGradle
 * (`net.neoforged:moddev-gradle`, the artifact behind plugin id `net.neoforged.moddev`), served by
 * the NeoForged maven; MixinGradle goes, because NeoForge reads mixin configs from the mod's
 * metadata and Mojang names need no refmap.
 */
internal object BuildSrcPlugins {
    const val MODDEV_VERSION = "2.0.140"
    private const val NEOFORGED_MAVEN = "https://maven.neoforged.net/releases"
    private val forgeGradle = Regex("""(["'])net\.minecraftforge\.gradle:ForgeGradle:[^"']+\1""")
    private val mixinGradle = Regex("""(?m)^[ \t]*(?:implementation|api|classpath)\s*\(?\s*["']org\.spongepowered:mixingradle:[^"']+["']\s*\)?[ \t]*\r?\n""")

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            val file = projectDir.resolve("buildSrc").resolve(name)
            if (!file.exists()) continue
            val original = file.readText()
            var text = forgeGradle.replace(original) { "${it.groupValues[1]}net.neoforged:moddev-gradle:$MODDEV_VERSION${it.groupValues[1]}" }
            text = mixinGradle.replace(text, "")
            if (text.contains("net.neoforged:moddev-gradle") && !text.contains(NEOFORGED_MAVEN)) {
                val repositories = Regex("""(?m)^repositories\s*\{[ \t]*\r?\n""").find(text)
                if (repositories != null) {
                    val newline = if (text.contains("\r\n")) "\r\n" else "\n"
                    val repository = if (name.endsWith(".kts")) {
                        "    maven {${newline}        name = \"NeoForged\"${newline}        url = uri(\"$NEOFORGED_MAVEN\")${newline}    }$newline"
                    } else {
                        "    maven {${newline}        name = 'NeoForged'${newline}        url = '$NEOFORGED_MAVEN'${newline}    }$newline"
                    }
                    text = text.substring(0, repositories.range.last + 1) + repository + text.substring(repositories.range.last + 1)
                }
            }
            if (text == original) continue
            changes += Change(file, 0, "buildSrc puts ModDevGradle instead of ForgeGradle/MixinGradle on the plugin classpath",
                "net.minecraftforge.gradle:ForgeGradle / org.spongepowered:mixingradle", "net.neoforged:moddev-gradle:$MODDEV_VERSION",
                Confidence.HIGH, "build-buildsrc-moddev")
            if (!dryRun) file.writeText(text)
        }
        return changes
    }
}
