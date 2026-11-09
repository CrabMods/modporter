package com.modporter.integration

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** Routes nested Minecraft tasks through this project's isolated runtime-directory session. */
internal object MinecraftRuntimeLease {
    private val repositoryRoot: Path = Path.of("").toAbsolutePath().normalize()
    private val runner: Path = repositoryRoot.resolve(
        "scripts/Invoke-ModporterRuntimeSession.ps1"
    )

    data class HarnessReceiptContract(
        val runtimeDirectory: Path,
        val receiptPath: Path,
        val harnessReceiptPath: Path,
        val expectedScenarioCount: Int,
        val expectedProcessStartCount: Int = 1,
        val expectedWorldLoadCount: Int = 1
    )

    data class SessionReceiptContract(
        val runtimeDirectory: Path,
        val receiptPath: Path
    )

    fun guard(
        projectRoot: Path,
        command: List<String>,
        runtimeKind: String,
        harnessReceipt: HarnessReceiptContract? = null,
        sessionReceipt: SessionReceiptContract? = null
    ): List<String> {
        require(command.isNotEmpty()) { "Minecraft runtime command is empty" }
        require(runtimeKind in setOf("client", "server", "gametest-server")) {
            "Unsupported Minecraft runtime kind: $runtimeKind"
        }
        require(harnessReceipt == null || sessionReceipt == null) {
            "A runtime command cannot request two session receipts"
        }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            error("Minecraft runtime validation currently requires the Windows hidden-desktop adapter")
        }
        require(runner.isRegularFile()) {
            "Project-owned runtime session runner is missing: $runner"
        }
        val launcher = launcher(projectRoot, command)
        val host = stagedScript(
            launcher.parent,
            "managed-runner-utf8-host",
            MANAGED_RUNNER_UTF8_HOST_RESOURCE
        )
        return buildList {
            addAll(listOf(
                "pwsh", "-NoProfile", "-File", host.toString(),
                "-ManagedRunner", runner.toString(),
                "-ProjectRoot", projectRoot.toAbsolutePath().normalize().toString(),
                "-Executable", launcher.toString(),
                "-RuntimeKind", runtimeKind
            ))
            val receipt = sessionReceipt ?: harnessReceipt?.let {
                SessionReceiptContract(it.runtimeDirectory, it.receiptPath)
            }
            val runtimeDirectory = (receipt?.runtimeDirectory ?: projectRoot.resolve("run"))
                .toAbsolutePath().normalize()
            addAll(listOf("-RuntimeDirectory", runtimeDirectory.toString()))
            if (receipt != null) {
                addAll(
                    listOf(
                        "-ReceiptPath",
                        receipt.receiptPath.toAbsolutePath().normalize().toString()
                    )
                )
            }
            if (harnessReceipt != null) {
                addAll(
                    listOf(
                        "-HarnessReceiptPath",
                        harnessReceipt.harnessReceiptPath.toAbsolutePath().normalize().toString(),
                        "-ExpectedScenarioCount",
                        harnessReceipt.expectedScenarioCount.toString(),
                        "-ExpectedProcessStartCount",
                        harnessReceipt.expectedProcessStartCount.toString(),
                        "-ExpectedWorldLoadCount",
                        harnessReceipt.expectedWorldLoadCount.toString()
                    )
                )
            }
        }
    }

    private fun launcher(projectRoot: Path, command: List<String>): Path {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val directory = normalizedRoot.resolve("build/minecraft-mod-testing/nested-launchers")
        directory.createDirectories()
        val guard = repositoryRoot.resolve("scripts/runtime_process_inventory.ps1")
        check(guard.isRegularFile()) { "Project-owned runtime inventory library is missing: $guard" }
        val guardHash = hash(guard.readBytes())
        val preflight = stagedScript(directory, "preflight", "/benchmark-process/runtime_preflight.ps1")
        val hiddenDesktop = stagedScript(directory, "hidden-desktop", HIDDEN_DESKTOP_RESOURCE)
        val arguments = command.drop(1).joinToString(",\n") { "    ${powerShellLiteral(it)}" }
        // The runtime (Gradle, then the game) runs on a private Windows desktop that is never
        // switched to: its windows stay invisible and cannot take focus or hardware input.
        val source = """
            ${'$'}ErrorActionPreference = 'Stop'
            & ${powerShellLiteral(preflight.toString())} -Guard ${powerShellLiteral(guard.toString())} -ExpectedGuardSha256 '$guardHash' -Scope ${powerShellLiteral(normalizedRoot.toString())}
            if (${'$'}LASTEXITCODE -ne 0) { exit ${'$'}LASTEXITCODE }
            ${'$'}modporterExecutable = ${powerShellLiteral(command.first())}
            ${'$'}modporterArguments = @(
            $arguments
            )
            & ${powerShellLiteral(hiddenDesktop.toString())} -Executable ${'$'}modporterExecutable -ArgumentList ${'$'}modporterArguments -DesktopName "modporter-hidden-${'$'}PID"
            if (${'$'}null -ne ${'$'}LASTEXITCODE) { exit ${'$'}LASTEXITCODE }
        """.trimIndent() + "\n"
        val launcher = directory.resolve("command-${hash(source.toByteArray(Charsets.UTF_8))}.ps1")
        if (launcher.exists()) {
            check(launcher.readText() == source) { "Nested runtime launcher hash collision: $launcher" }
        } else {
            launcher.writeText(source)
        }
        return launcher
    }

    /** Copies a classpath script into [directory] under a content-hashed name, refusing a changed copy. */
    private fun stagedScript(directory: Path, prefix: String, resource: String): Path {
        val source = checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing resource $resource" }.use { it.readBytes() }
        val staged = directory.resolve("$prefix-${hash(source)}.ps1")
        if (staged.exists()) check(staged.readBytes().contentEquals(source)) { "Staged runtime script source changed: $staged" }
        else staged.writeBytes(source)
        return staged
    }

    const val HIDDEN_DESKTOP_RESOURCE = "/benchmark-process/hidden_desktop.ps1"
    const val MANAGED_RUNNER_UTF8_HOST_RESOURCE =
        "/benchmark-process/managed_runner_utf8_host.ps1"

    private fun powerShellLiteral(value: String): String = "'${value.replace("'", "''")}'"

    private fun hash(bytes: ByteArray): String =
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun kindForTask(task: String): String = when {
        task.startsWith("runClient", ignoreCase = true) -> "client"
        task.startsWith("runGameTestServer", ignoreCase = true) -> "gametest-server"
        else -> "server"
    }
}
