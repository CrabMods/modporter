package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Restores the old constructor-only factory instead of guessing new stack/player inputs. */
internal fun StructuralRefactorPass.migrateLegacyMinecartFactories(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths -> paths.filter { it.toString().endsWith(".java") }.toList() }
    val variants = linkedMapOf("CHEST" to "MinecartChest", "FURNACE" to "MinecartFurnace", "TNT" to "MinecartTNT", "SPAWNER" to "MinecartSpawner", "HOPPER" to "MinecartHopper", "COMMAND_BLOCK" to "MinecartCommandBlock")
    val changes = mutableListOf<Change>()
    var compat: String? = null
    for (file in files) {
        val source = file.readText()
        if (!source.contains("createMinecart")) continue
        val typing = SourceValueTypes(this, source)
        var result = source
        for (owner in listOf("AbstractMinecart", "net.minecraft.world.entity.vehicle.AbstractMinecart")) {
            if (!typing.isTypeName(owner, "net.minecraft.world.entity.vehicle.AbstractMinecart")) continue
            result = rewriteJavaInvocation(result, "$owner.createMinecart") { args ->
                if (args.size != 5) null else {
                    check((variants.values + "Minecart").all { name -> "(Lnet/minecraft/world/level/Level;DDD)V" in TargetMembers.methodDescriptors("net.minecraft.world.entity.vehicle.$name", "<init>") }) {
                        "Target minecart constructors do not match the verified legacy factory"
                    }
                    val pkg = compat ?: detectRequiredGeneratedCompatPackage(project, "legacy minecart factory").also { compat = it }
                    "$pkg.LegacyMinecartFactories.create(${args.joinToString(", ")})"
                }
            }
        }
        if (result == source) continue
        if (!dryRun) file.writeText(result)
        changes += Change(file, 1, "Retain the legacy minecart factory's exact constructor selection", "createMinecart(Level,x,y,z,type)", "legacy constructor-only factory", Confidence.HIGH, "struct-legacy-minecart-factory")
    }
    val pkg = compat ?: return changes
    val generated = root.resolve(pkg.replace('.', '/')).resolve("LegacyMinecartFactories.java")
    val branches = variants.entries.joinToString("\n") { (type, entity) -> "        if (type == net.minecraft.world.entity.vehicle.AbstractMinecart.Type.$type) return new net.minecraft.world.entity.vehicle.$entity(level, x, y, z);" }
    val content = """
package $pkg;
public final class LegacyMinecartFactories {
    private LegacyMinecartFactories() {}
    public static net.minecraft.world.entity.vehicle.AbstractMinecart create(net.minecraft.world.level.Level level, double x, double y, double z, net.minecraft.world.entity.vehicle.AbstractMinecart.Type type) {
$branches
        return new net.minecraft.world.entity.vehicle.Minecart(level, x, y, z);
    }
}
""".trimIndent()
    if (!java.nio.file.Files.exists(generated) || generated.readText() != content) {
        if (!dryRun) { generated.parent.createDirectories(); generated.writeText(content) }
        changes += Change(generated, 1, "Generate the 1.20.1 minecart factory on verified public constructors", "", "LegacyMinecartFactories", Confidence.HIGH, "struct-legacy-minecart-factory")
    }
    return changes
}
