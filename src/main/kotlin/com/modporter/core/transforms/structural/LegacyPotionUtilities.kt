package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Retains the CompoundTag overloads used by non-item potion containers on the component platform. */
internal fun StructuralRefactorPass.migrateLegacyPotionUtilities(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths -> paths.filter { it.toString().endsWith(".java") }
        .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }.toList() }
    val sources = files.associateWith { it.readText() }
    val needed = sources.any { (_, text) -> maskJavaCommentsAndLiterals(text).contains("LegacyPotionUtils") ||
        text.contains("net.minecraft.world.item.alchemy.PotionUtils") && maskJavaCommentsAndLiterals(text).contains("PotionUtils.") }
    if (!needed) return emptyList()
    val compat = detectRequiredGeneratedCompatPackage(project, "legacy potion NBT utility")
    val changes = mutableListOf<Change>()
    for ((file, source) in sources) {
        var result = source
        if (source.contains("net.minecraft.world.item.alchemy.PotionUtils")) {
            result = result.replace("import net.minecraft.world.item.alchemy.PotionUtils;", "import $compat.LegacyPotionUtils;")
            result = replaceExecutableJavaRegex(result, Regex("""(?<![\w$.])(?:net\.minecraft\.world\.item\.alchemy\.)?PotionUtils\.(?=[A-Za-z_$])""")) { "LegacyPotionUtils." }
            // The stack-only rule may already have built PotionContents around the tag overload's raw Potion.
            for (type in listOf("PotionContents", "net.minecraft.world.item.alchemy.PotionContents")) {
                result = rewriteExecutableJavaNew(result, type) { args ->
                    if (args.size == 1 && args[0].trim().startsWith("LegacyPotionUtils.getPotion("))
                        "$type(net.minecraft.core.registries.BuiltInRegistries.POTION.wrapAsHolder(${args[0]}))".let { "new $it" }
                    else null
                }
            }
        }
        // getPotion's legacy return value was a raw Potion, including the unregistered empty sentinel.
        result = replaceExecutableJavaRegex(result, Regex("""\.potion\(\)\.orElse\((?:${Regex.escape(compat)}\.)?LegacyPotionUtils\.EMPTY\)""")) {
            ".potion().map(net.minecraft.core.Holder::value).orElse($compat.LegacyPotionUtils.EMPTY)"
        }
        val typing = SourceValueTypes(this, result)
        result = rewriteExecutableJavaCallWithOffset(result, "getName") { receiver, args, offset ->
            if (args.size != 1 || !(receiver.trim().startsWith("LegacyPotionUtils.getPotion(") ||
                    typing.typeOf(receiver, offset) == "net.minecraft.world.item.alchemy.Potion")) null
            else "$compat.LegacyPotionUtils.getName($receiver, ${args[0]})"
        }
        if (result != source) {
            if (!dryRun) file.writeText(result)
            changes += Change(file, 1, "Retain potion NBT utility overloads for non-item containers", "PotionUtils", "LegacyPotionUtils", Confidence.HIGH, "struct-legacy-potion-utilities")
        }
    }
    val generated = root.resolve(compat.replace('.', '/')).resolve("LegacyPotionUtils.java")
    val template = javaClass.getResource("/compat/LegacyPotionUtils.java.template")!!.readText().replace("@PACKAGE@", compat)
    if (!java.nio.file.Files.exists(generated) || generated.readText() != template) {
        if (!dryRun) { generated.parent.createDirectories(); generated.writeText(template) }
        changes += Change(generated, 1, "Generate legacy potion NBT adapters using target potion contents", "", "LegacyPotionUtils", Confidence.HIGH, "struct-legacy-potion-utilities")
    }
    return changes
}
