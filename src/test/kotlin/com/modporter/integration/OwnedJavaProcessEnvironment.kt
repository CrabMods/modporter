package com.modporter.integration

import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Configures only benchmark-owned child processes. The console encoding properties are
 * deliberately separate from file.encoding because Gradle may supply its own file encoding
 * while every Gradle daemon and Java/Minecraft descendant must still emit strict UTF-8 bytes.
 */
internal object OwnedJavaProcessEnvironment {
    private val utf8ToolOptions = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8"
    )

    fun configure(processBuilder: ProcessBuilder) {
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() } ?: return
        val javaBin = Path.of(javaHome, "bin")
        if (!javaBin.exists()) return

        val env = processBuilder.environment()
        env["JAVA_HOME"] = javaHome

        val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val existingPath = env[pathKey].orEmpty()
        env[pathKey] = if (existingPath.isBlank()) {
            javaBin.toString()
        } else {
            javaBin.toString() + File.pathSeparator + existingPath
        }

        val existingToolOptions = env["JAVA_TOOL_OPTIONS"].orEmpty().trim()
        val additions = utf8ToolOptions.filterNot { option ->
            existingToolOptions.split(Regex("""\s+""")).contains(option)
        }
        env["JAVA_TOOL_OPTIONS"] = (listOf(existingToolOptions) + additions)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
