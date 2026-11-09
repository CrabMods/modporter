package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The legacy panorama entry point drew only the cube map; 1.21's replacement also draws an overlay. */
internal fun StructuralRefactorPass.migrateLegacyPanoramaCalls(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val renderer = "net.minecraft.client.renderer.PanoramaRenderer"
    val files = walkJavaSourceRoots(root).use { paths -> paths.filter { it.toString().endsWith(".java") }
        .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }.toList() }
    projectValueFacts = ProjectValueFacts.collect(this, files)
    val changes = mutableListOf<Change>()
    var compat: String? = null
    fun helper(): String = compat ?: detectRequiredGeneratedCompatPackage(project, "legacy panorama rendering").also { compat = it }
    for (file in files) {
        val source = file.readText()
        if (!source.contains("PanoramaRenderer") && !source.contains(".render(")) continue
        val typing = SourceValueTypes(this, source)
        val namespace = packageNameOf(source)
        val result = rewriteExecutableJavaCallWithOffset(source, "render") { receiver, args, offset ->
            if (args.size != 2) return@rewriteExecutableJavaCallWithOffset null
            val own = enclosingJavaClassDeclaration(source, offset)?.name
            val ownCanonical = projectValueFacts.types.filter { it.substringAfterLast('.') == own && it.startsWith("$namespace.") }.singleOrNull()
            val directSubclass = ownCanonical != null && projectValueFacts.parents[ownCanonical] == renderer
            val baseCall = receiver.trim() == "super" && directSubclass
            val instanceCall = receiver.trim() == "this" && directSubclass
            if (!baseCall && !instanceCall && typing.typeOf(receiver, offset) != renderer) return@rewriteExecutableJavaCallWithOffset null
            check(TargetMembers.fieldDescriptor(renderer, "minecraft") == "Lnet/minecraft/client/Minecraft;" &&
                TargetMembers.fieldDescriptor(renderer, "cubeMap") == "Lnet/minecraft/client/renderer/CubeMap;" &&
                TargetMembers.fieldDescriptor(renderer, "spin") == "F" && TargetMembers.fieldDescriptor(renderer, "bob") == "F") {
                "The target panorama state does not match the verified legacy adapter contract"
            }
            "${helper()}.LegacyPanoramaRenderer.${if (baseCall) "renderBase" else "render"}(${if (baseCall || instanceCall) "this" else receiver}, ${args.joinToString(", ")})"
        }
        if (result == source) continue
        if (!dryRun) file.writeText(result)
        changes += Change(file, 1, "Retain cube-map-only panorama rendering and the caller's overlay order", "PanoramaRenderer.render(float,float)", "legacy cube-map entry point", Confidence.HIGH, "struct-legacy-panorama-render")
    }
    val pkg = compat ?: return changes
    val generated = root.resolve(pkg.replace('.', '/')).resolve("LegacyPanoramaRenderer.java")
    val content = javaClass.getResource("/compat/LegacyPanoramaRenderer.java.template")!!.readText().replace("@PACKAGE@", pkg)
    if (!java.nio.file.Files.exists(generated) || generated.readText() != content) {
        if (!dryRun) { generated.parent.createDirectories(); generated.writeText(content) }
        changes += Change(generated, 1, "Generate the 1.20.1 panorama algorithm on the verified target state fields", "", "LegacyPanoramaRenderer", Confidence.HIGH, "struct-legacy-panorama-render")
    }
    return changes
}
