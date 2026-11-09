package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Flywheel 1.21 folded ForgeBlockModelBuilder into BlockModelBuilder. Its renderer now asks the
 * BlockAndTintGetter for ModelData, so retain an explicit legacy lookup through a delegating view.
 */
internal fun StructuralRefactorPass.migrateLegacyFlywheelBlockModels(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths ->
        paths.filter { it.toString().endsWith(".java") }
            .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
            .toList()
    }
    val changes = mutableListOf<Change>()
    var compatPackage: String? = null
    for (file in files) {
        val source = file.readText()
        if (!source.contains("ForgeBlockModelBuilder") || !source.contains(".modelDataLookup(")) continue
        val probe = migrateLegacyFlywheelBlockModelSource(source, "com.modporter.generated")
        if (probe == source) continue
        val compat = compatPackage
            ?: detectRequiredGeneratedCompatPackage(project, "legacy Flywheel model data lookups").also { compatPackage = it }
        val result = migrateLegacyFlywheelBlockModelSource(source, compat)
        if (!dryRun) file.writeText(result)
        changes += Change(
            file,
            1,
            "Preserve explicit block model data through Flywheel's BlockAndTintGetter contract",
            "ForgeBlockModelBuilder.modelDataLookup",
            "BlockModelBuilder with model-data delegating world",
            Confidence.HIGH,
            "struct-flywheel-block-model-data"
        )
    }
    val compat = compatPackage ?: return changes
    val generated = root.resolve(compat.replace('.', '/')).resolve("LegacyModelDataBlockAndTintGetter.java")
    val content = javaClass.getResource("/compat/LegacyModelDataBlockAndTintGetter.java.template")!!
        .readText()
        .replace("@PACKAGE@", compat)
    if (!Files.exists(generated) || generated.readText() != content) {
        if (!dryRun) {
            generated.parent.createDirectories()
            generated.writeText(content)
        }
        changes += Change(
            generated,
            1,
            "Generate a BlockAndTintGetter view that preserves source model-data lookups",
            "",
            "LegacyModelDataBlockAndTintGetter",
            Confidence.HIGH,
            "struct-flywheel-block-model-data"
        )
    }
    return changes
}

internal fun StructuralRefactorPass.migrateLegacyFlywheelBlockModelSource(source: String, compatPackage: String): String {
    if (!source.contains("ForgeBlockModelBuilder") || !source.contains(".modelDataLookup(")) return source
    var result = rewriteExecutableJavaCallWithOffset(source, "modelDataLookup") { receiver, args, _ ->
        if (args.size != 1) return@rewriteExecutableJavaCallWithOffset null
        val executableReceiver = maskJavaCommentsAndLiterals(receiver)
        val constructor = Regex("""^new\s+(?:dev\.engine_room\.flywheel\.lib\.model\.baked\.)?ForgeBlockModelBuilder\s*\(""")
            .find(executableReceiver) ?: return@rewriteExecutableJavaCallWithOffset null
        val openParen = executableReceiver.indexOf('(', constructor.range.first)
        val closeParen = findMatchingParen(executableReceiver, openParen)
        if (closeParen != executableReceiver.lastIndex) return@rewriteExecutableJavaCallWithOffset null
        val constructorArgs = splitTopLevelJavaArgs(receiver.substring(openParen + 1, closeParen))
        if (constructorArgs.size != 2) return@rewriteExecutableJavaCallWithOffset null
        "new BlockModelBuilder(new $compatPackage.LegacyModelDataBlockAndTintGetter(" +
            "${constructorArgs[0].trim()}, ${args[0].trim()}), ${constructorArgs[1].trim()})"
    }
    if (result == source) return source
    result = removeExecutableImport(result, "dev.engine_room.flywheel.lib.model.baked.ForgeBlockModelBuilder")
    result = addExecutableImportIfMissing(result, "dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder")
    return result
}

/**
 * Flywheel 1.21 made BakedModelBuilder concrete and replaced its static create(BakedModel)
 * factory with the public BakedModelBuilder(BakedModel) constructor.
 */
internal fun StructuralRefactorPass.migrateFlywheelBakedModelBuilders(source: String): String {
    if (!source.contains("BakedModelBuilder") || !source.contains(".create(")) return source
    val importsFlywheelBuilder = Regex(
        """(?m)^\s*import\s+dev\.engine_room\.flywheel\.lib\.model\.baked\.BakedModelBuilder\s*;"""
    ).containsMatchIn(maskJavaCommentsAndLiterals(source))
    return rewriteExecutableJavaCallWithOffset(source, "create") { receiver, args, _ ->
        if (args.size != 1) return@rewriteExecutableJavaCallWithOffset null
        val owner = receiver.trim()
        val isFlywheelBuilder =
            owner == "dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder" ||
                importsFlywheelBuilder && owner == "BakedModelBuilder"
        if (!isFlywheelBuilder) return@rewriteExecutableJavaCallWithOffset null
        "new $owner(${args[0].trim()})"
    }
}
