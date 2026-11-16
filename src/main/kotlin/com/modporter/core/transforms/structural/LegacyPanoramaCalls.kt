package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val PANORAMA_OWNER = "net.minecraft.client.renderer.PanoramaRenderer"
private const val PANORAMA_NATIVE_RENDER =
    "(Lnet/minecraft/client/gui/GuiGraphics;IIFF)V"
private const val LEGACY_REFLECTIVE_RENDERER_TEMPLATE_SHA256 =
    "a9634f045c2a3bd1b621b54be00786c77dde994a726095569bd38ed942b08ac6"
private const val PANORAMA_RULE = "struct-legacy-panorama-render"

private data class PanoramaWrite(
    val file: Path,
    val before: String?,
    val after: String,
    val change: Change
)

/** The legacy panorama entry point drew only the cube map; 1.21's replacement also draws an overlay. */
internal fun StructuralRefactorPass.migrateLegacyPanoramaCalls(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths ->
        paths.filter { it.toString().endsWith(".java") }
            .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
            .sorted()
            .toList()
    }
    val originals = files.associateWith { it.readText() }
    projectValueFacts = ProjectValueFacts.collect(this, files)

    val writes = mutableListOf<PanoramaWrite>()
    var compat: String? = null
    fun helper(): String = compat
        ?: detectRequiredGeneratedCompatPackage(project, "legacy panorama rendering")
            .also { compat = it }

    for ((file, source) in originals) {
        if (!source.contains("PanoramaRenderer") && !source.contains(".render(")) continue
        val typing = SourceValueTypes(this, source)
        val namespace = packageNameOf(source)
        val result = rewriteExecutableJavaCallWithOffset(source, "render") { receiver, args, offset ->
            if (args.size != 2) return@rewriteExecutableJavaCallWithOffset null
            val own = enclosingJavaClassDeclaration(source, offset)?.name
            val ownCanonical = projectValueFacts.types
                .filter { it.substringAfterLast('.') == own && it.startsWith("$namespace.") }
                .singleOrNull()
            val directSubclass = ownCanonical != null && projectValueFacts.parents[ownCanonical] == PANORAMA_OWNER
            val baseCall = receiver.trim() == "super" && directSubclass
            val instanceCall = receiver.trim() == "this" && directSubclass
            if (!baseCall && !instanceCall && typing.typeOf(receiver, offset) != PANORAMA_OWNER) {
                return@rewriteExecutableJavaCallWithOffset null
            }
            val method = if (baseCall) "renderBase" else "render"
            val target = if (baseCall || instanceCall) "this" else receiver
            "${helper()}.LegacyPanoramaRenderer.$method($target, ${args.joinToString(", ")})"
        }
        if (result == source) continue
        writes += PanoramaWrite(
            file = file,
            before = source,
            after = result,
            change = Change(
                file,
                1,
                "Retain cube-map-only panorama rendering and the caller's overlay order",
                "PanoramaRenderer.render(float,float)",
                "typed legacy panorama bridge",
                Confidence.HIGH,
                PANORAMA_RULE
            )
        )
    }

    if (compat == null && originals.values.any { source ->
            Regex("""\bLegacyPanoramaRenderer\.(?:render|renderBase)\s*\(""")
                .containsMatchIn(source)
        }) {
        compat = detectRequiredGeneratedCompatPackage(project, "legacy panorama rendering")
    }
    val packageName = compat ?: return emptyList()
    verifyTargetPanoramaAbi()

    val generatedRoot = root.resolve(packageName.replace('.', '/'))
    val generatedTemplates = linkedMapOf(
        generatedRoot.resolve("LegacyPanoramaRenderer.java") to
            "/compat/LegacyPanoramaRenderer.java.template",
        generatedRoot.resolve("LegacyPanoramaRendererBridge.java") to
            "/compat/LegacyPanoramaRendererBridge.java.template",
        generatedRoot.resolve("mixin/LegacyPanoramaRendererMixin.java") to
            "/compat/LegacyPanoramaRendererMixin.java.template"
    )
    for ((file, resource) in generatedTemplates) {
        val content = StructuralRefactorPass::class.java.getResource(resource)!!
            .readText()
            .replace("@PACKAGE@", packageName)
        planPanoramaCanonicalWrite(
            writes,
            file,
            content,
            packageName,
            allowReflectivePredecessor = file.fileName.toString() == "LegacyPanoramaRenderer.java",
            description = "Generate the typed 1.20.1 panorama compatibility bridge"
        )
    }

    val resourceRoot = project.resolve("src/main/resources")
    val configName = panoramaMixinConfigName(packageName)
    val config = panoramaMixinConfig(packageName)
    checkPanoramaClientOnlyConfig(config)
    planPanoramaCanonicalWrite(
        writes,
        resourceRoot.resolve(configName),
        config,
        packageName,
        allowReflectivePredecessor = false,
        description = "Register the client-only panorama compatibility mixin"
    )

    val metadata = findPanoramaMetadata(resourceRoot)
    val metadataBefore = metadata.readText()
    val metadataAfter = ensurePanoramaMixinMetadata(metadataBefore, configName)
    if (metadataAfter != metadataBefore) {
        writes += PanoramaWrite(
            file = metadata,
            before = metadataBefore,
            after = metadataAfter,
            change = Change(
                metadata,
                1,
                "Register dedicated client panorama mixin config",
                "(missing [[mixins]] registration)",
                "config=\"$configName\"",
                Confidence.HIGH,
                PANORAMA_RULE
            )
        )
    }

    if (!dryRun && writes.isNotEmpty()) applyPanoramaTransaction(writes)
    return writes.map { it.change }
}

private fun verifyTargetPanoramaAbi() {
    val expectedFields = linkedMapOf(
        "minecraft" to "Lnet/minecraft/client/Minecraft;",
        "cubeMap" to "Lnet/minecraft/client/renderer/CubeMap;",
        "spin" to "F",
        "bob" to "F"
    )
    val actualAccess = panoramaFieldAccessFromTargetInventory()
    for ((name, descriptor) in expectedFields) {
        check(TargetMembers.fieldDescriptor(PANORAMA_OWNER, name) == descriptor) {
            "The target panorama field $name does not match the verified descriptor $descriptor"
        }
        check(actualAccess[name] == (descriptor to "private")) {
            "The target panorama field $name no longer has its verified private ABI"
        }
    }
    check(TargetMembers.methodDescriptors(PANORAMA_OWNER, "render") == setOf(PANORAMA_NATIVE_RENDER)) {
        "The target PanoramaRenderer render ABI changed; refusing to introduce the legacy overload"
    }
    val nativeRender = TargetMembers.inheritedMethod(PANORAMA_OWNER, "render", PANORAMA_NATIVE_RENDER)
    check(nativeRender?.owner == PANORAMA_OWNER && nativeRender.access == "public" && nativeRender.signature.isEmpty()) {
        "The target PanoramaRenderer native render flags changed"
    }
    check("(FF)V" !in TargetMembers.methodDescriptors(PANORAMA_OWNER, "render")) {
        "The target already declares render(float,float); refusing a conflicting generated bridge"
    }
    check(TargetMembers.methodDescriptors(PANORAMA_OWNER, "wrap") == setOf("(FF)F")) {
        "The target PanoramaRenderer wrap ABI changed"
    }
    val wrap = TargetMembers.inheritedMethod(PANORAMA_OWNER, "wrap", "(FF)F")
    check(wrap?.owner == PANORAMA_OWNER && wrap.access == "private" && wrap.signature.isEmpty()) {
        "The target PanoramaRenderer wrap flags changed"
    }
}

private fun panoramaFieldAccessFromTargetInventory(): Map<String, Pair<String, String>> {
    val resource = "/mappings/forge2neo/target-members-1.21.1.tsv.gz"
    val stream = StructuralRefactorPass::class.java.getResourceAsStream(resource)
        ?: error("Missing target member table resource: $resource")
    val result = linkedMapOf<String, Pair<String, String>>()
    GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.forEach { line ->
            val columns = line.split('\t')
            if (columns.size >= 5 && columns[0] == "F" && columns[1] == PANORAMA_OWNER) {
                result[columns[2]] = columns[3] to columns[4]
            }
        }
    }
    return result
}

private fun panoramaMixinConfigName(packageName: String): String {
    val packageSegment = packageName.removePrefix("com.modporter.generated.").substringBefore(".compat")
    require(packageSegment.matches(Regex("[a-z][a-z0-9_]*"))) {
        "Generated panorama package has no stable resource prefix: $packageName; no files changed"
    }
    return "$packageSegment.modporter-legacy-panorama.mixins.json"
}

private fun panoramaMixinConfig(packageName: String): String = """
    {
      "required": true,
      "minVersion": "0.8",
      "package": "$packageName.mixin",
      "compatibilityLevel": "JAVA_21",
      "client": [
        "LegacyPanoramaRendererMixin"
      ],
      "injectors": {
        "defaultRequire": 1
      }
    }
""".trimIndent() + "\n"

private fun checkPanoramaClientOnlyConfig(source: String) {
    require(Regex(""""client"\s*:\s*\[""").containsMatchIn(source)) {
        "Generated panorama mixin is not in the client mixin array"
    }
    require(!Regex(""""mixins"\s*:""").containsMatchIn(source)) {
        "Generated panorama mixin leaked into the common mixin array"
    }
    require(!Regex(""""server"\s*:""").containsMatchIn(source)) {
        "Generated panorama mixin leaked into the server mixin array"
    }
}

private fun findPanoramaMetadata(resourceRoot: Path): Path {
    val candidates = listOf(
        resourceRoot.resolve("META-INF/neoforge.mods.toml"),
        resourceRoot.resolve("META-INF/mods.toml")
    ).filter { it.isRegularFile() }
    require(candidates.isNotEmpty()) {
        "Cannot register panorama mixin: no NeoForge metadata exists; no files changed"
    }
    return candidates.first()
}

private fun ensurePanoramaMixinMetadata(source: String, configName: String): String {
    var table: String? = null
    var registered = false
    source.lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        Regex("""^\[\[([A-Za-z0-9_.-]+)\]\]$""").matchEntire(line)?.let {
            table = it.groupValues[1]
            return@forEach
        }
        if (Regex("""^\[[^]]+]$""").matches(line)) {
            table = null
            return@forEach
        }
        val config = Regex("""^config\s*=\s*"([^"]+)"\s*$""").matchEntire(line)
            ?.groupValues
            ?.get(1)
            ?: return@forEach
        if (config == configName) {
            require(table == "mixins") {
                "Mixin config $configName is registered outside a [[mixins]] table; no files changed"
            }
            registered = true
        }
    }
    if (registered) return source
    val separator = if (source.endsWith('\n')) "" else "\n"
    return source + separator + "\n[[mixins]]\nconfig=\"$configName\"\n"
}

private fun planPanoramaCanonicalWrite(
    writes: MutableList<PanoramaWrite>,
    file: Path,
    content: String,
    packageName: String,
    allowReflectivePredecessor: Boolean,
    description: String
) {
    val before = if (file.exists()) {
        require(file.isRegularFile()) {
            "Panorama compatibility output is not a regular file: $file; no files changed"
        }
        file.readText()
    } else {
        null
    }
    if (before == content) return
    if (before != null) {
        require(allowReflectivePredecessor && isOwnedReflectivePanoramaRenderer(before, packageName)) {
            "Existing panorama compatibility output differs from the canonical source: $file; no files changed"
        }
    }
    writes += PanoramaWrite(
        file = file,
        before = before,
        after = content,
        change = Change(
            file,
            1,
            description,
            if (before == null) "(missing compatibility artifact)" else "owned reflective panorama adapter v1",
            file.fileName.toString(),
            Confidence.HIGH,
            PANORAMA_RULE
        )
    )
}

private fun isOwnedReflectivePanoramaRenderer(source: String, packageName: String): Boolean {
    val prefix = "package $packageName;\n"
    if (!source.startsWith(prefix)) return false
    val normalized = "package @PACKAGE@;\n" + source.substring(prefix.length)
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) } == LEGACY_REFLECTIVE_RENDERER_TEMPLATE_SHA256
}

private fun applyPanoramaTransaction(writes: List<PanoramaWrite>) {
    writes.forEach { write ->
        val current = if (write.file.exists()) write.file.readText() else null
        require(current == write.before) {
            "Project input changed after panorama preflight: ${write.file}; no files changed"
        }
    }
    val staged = linkedMapOf<PanoramaWrite, Path>()
    try {
        for (write in writes) {
            write.file.parent.createDirectories()
            val temporary = write.file.resolveSibling(
                ".${write.file.fileName}.modporter-${UUID.randomUUID()}.tmp"
            )
            temporary.writeText(write.after)
            require(temporary.readText() == write.after) {
                "Could not verify staged panorama output for ${write.file}"
            }
            staged[write] = temporary
        }
        val applied = mutableListOf<PanoramaWrite>()
        try {
            for ((write, temporary) in staged) {
                movePanoramaReplacing(temporary, write.file)
                applied += write
            }
        } catch (failure: Exception) {
            for (write in applied.asReversed()) {
                runCatching {
                    if (write.before == null) Files.deleteIfExists(write.file)
                    else write.file.writeText(write.before)
                }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
    } finally {
        staged.values.forEach { Files.deleteIfExists(it) }
    }
}

private fun movePanoramaReplacing(from: Path, to: Path) {
    try {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }
}
