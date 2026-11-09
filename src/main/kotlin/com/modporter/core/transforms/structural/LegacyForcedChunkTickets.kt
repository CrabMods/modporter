package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forge 1.20.1 `ForgeChunkManager.forceChunk(level, modId, owner, chunkX, chunkZ, add, ticking)`
 * keyed forced-chunk tickets by mod id and a BlockPos, UUID or Entity owner. NeoForge 1.21.1 keys
 * them by a registered `TicketController` and the same owner kinds, through
 * `TicketController#forceChunk(level, owner, chunkX, chunkZ, add, ticking)`.
 *
 * A project whose every call passes its own mod id gets one generated controller for that id,
 * registered from `RegisterTicketControllersEvent`. Any other ForgeChunkManager use (another
 * mod id, the validation callback, or another member) is refused with a gate.
 */
internal object LegacyForcedChunkTickets {
    const val RULE_ID = "struct-legacy-forced-chunk-tickets"
    private const val CLASS_NAME = "LegacyForcedChunks"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val legacyName = Regex(
        """(?<![\w$.])(?:net\.(?:minecraftforge|neoforged\.neoforge)\.common\.world\.(?:chunk\.)?)?(?:ForgeChunkManager|ForcedChunkManager)(?![\w$])"""
    )
    private val legacyImport = Regex(
        """(?m)^[ \t]*import\s+net\.(?:minecraftforge|neoforged\.neoforge)\.common\.world\.(?:chunk\.)?(?:ForgeChunkManager|ForcedChunkManager)\s*;[ \t]*\r?\n"""
    )

    data class Result(val changes: List<Change>, val gates: List<String>)

    fun migrate(projectDir: Path, modId: String, compatPackageSupplier: () -> String, dryRun: Boolean): Result {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return Result(emptyList(), emptyList())
        if (!targetSurfaceExists()) return Result(emptyList(), emptyList())
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths ->
            paths.filter { it.extension == "java" }
                .filter { !it.invariantSeparatorsPathString.contains("/com/modporter/generated/") }
                .toList()
        }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
            .filterValues { legacyName.containsMatchIn(JavaSourceText.maskCommentsAndLiterals(it)) }
        if (sources.isEmpty()) return Result(emptyList(), emptyList())
        val modIdConstants = modIdConstants(files, modId)

        val gates = mutableListOf<String>()
        val migrated = linkedMapOf<Path, String>()
        lateinit var compatPackage: String
        for ((file, source) in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val calls = Regex("""${legacyName.pattern}\s*\.\s*forceChunk\s*\(""").findAll(masked).toList()
            val edits = mutableListOf<Pair<IntRange, String>>()
            for (call in calls) {
                val close = JavaSourceText.matchingParen(masked, call.range.last)
                if (close < 0) {
                    gates += "Legacy forced chunk call is not structural in ${projectDir.relativize(file)}"
                    continue
                }
                val arguments = JavaSourceText.splitTopLevelArgs(source.substring(call.range.last + 1, close))
                if (arguments.size != 7) {
                    gates += "Legacy forced chunk call in ${projectDir.relativize(file)} has ${arguments.size} arguments; only forceChunk(level, modId, owner, x, z, add, ticking) maps to a ticket controller"
                    continue
                }
                if (!isOwnModId(arguments[1], source, modId, modIdConstants)) {
                    gates += "Legacy forced chunk call in ${projectDir.relativize(file)} passes mod id '${arguments[1]}', which is not proven to be '$modId'"
                    continue
                }
                compatPackage = compatPackageSupplier()
                val controller = "$compatPackage.$CLASS_NAME.CONTROLLER"
                edits += (call.range.first..close) to
                    "$controller.forceChunk(${(listOf(arguments[0]) + arguments.drop(2)).joinToString(", ")})"
            }
            var result = edits.sortedByDescending { it.first.first }
                .fold(source) { text, (range, value) -> text.replaceRange(range, value) }
            val remaining = JavaSourceText.maskCommentsAndLiterals(legacyImport.replace(result, ""))
            if (legacyName.containsMatchIn(remaining)) {
                gates += "Legacy ForgeChunkManager use other than forceChunk(level, modId, owner, x, z, add, ticking) remains in ${projectDir.relativize(file)}"
                continue
            }
            result = legacyImport.replace(result, "")
            if (result != source) migrated[file] = result
        }
        if (gates.isNotEmpty() || migrated.isEmpty()) return Result(emptyList(), gates.distinct())

        val changes = mutableListOf<Change>()
        for ((file, text) in migrated) {
            if (!dryRun) file.writeText(text)
            changes += Change(
                file, 1,
                "Forge forced-chunk tickets keyed by the mod id use the mod's NeoForge ticket controller",
                "ForgeChunkManager.forceChunk(level, modId, owner, x, z, add, ticking)",
                "$CLASS_NAME.CONTROLLER.forceChunk(level, owner, x, z, add, ticking)",
                Confidence.HIGH, RULE_ID
            )
        }
        val generated = srcDir.resolve(compatPackage.replace('.', '/')).resolve("$CLASS_NAME.java")
        val text = controllerSource(compatPackage, modId)
        if (!generated.exists() || generated.readText() != text) {
            if (!dryRun) {
                generated.parent.createDirectories()
                generated.writeText(text)
            }
            changes += Change(
                generated, 1,
                "Generate the mod's forced-chunk ticket controller and register it on the mod bus",
                "(absent)", "$compatPackage.$CLASS_NAME", Confidence.HIGH, RULE_ID
            )
        }
        return Result(changes, emptyList())
    }

    private fun targetSurfaceExists(): Boolean {
        val controller = "net.neoforged.neoforge.common.world.chunk.TicketController"
        val event = "net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent"
        val owners = listOf("Lnet/minecraft/core/BlockPos;", "Ljava/util/UUID;", "Lnet/minecraft/world/entity/Entity;")
        return owners.all {
            "(Lnet/minecraft/server/level/ServerLevel;${it}IIZZ)Z" in TargetMembers.methodDescriptors(controller, "forceChunk")
        } &&
            "(Lnet/minecraft/resources/ResourceLocation;)V" in TargetMembers.methodDescriptors(controller, "<init>") &&
            "(L$controller;)V".replace('.', '/') in TargetMembers.methodDescriptors(event, "register") &&
            TargetMembers.inheritsFrom(event, "net.neoforged.fml.event.IModBusEvent")
    }

    /** `Owner.FIELD` names of project `static final String FIELD = "<modId>"` constants. */
    private fun modIdConstants(files: List<Path>, modId: String): Set<String> {
        val result = mutableSetOf<String>()
        val constant = Regex("""\bstatic\s+final\s+String\s+($ID)\s*=\s*"${Regex.escape(modId)}"\s*;""")
        for (file in files) {
            val source = runCatching { file.readText() }.getOrNull() ?: continue
            val owner = file.fileName.toString().removeSuffix(".java")
            val executable = JavaSourceText.maskCommentsAndLiterals(source)
            for (match in constant.findAll(source)) {
                // the declaration itself must be code, not a comment
                if (!executable.substring(match.range).contains("static")) continue
                result += "$owner.${match.groupValues[1]}"
            }
        }
        return result
    }

    private fun isOwnModId(argument: String, source: String, modId: String, constants: Set<String>): Boolean {
        val trimmed = argument.trim()
        if (trimmed == "\"$modId\"") return true
        if (trimmed in constants) {
            val owner = trimmed.substringBefore('.')
            // the owner simple name must not be shadowed by a different import
            val imported = Regex("""(?m)^\s*import\s+([\w.]+)\.${Regex.escape(owner)}\s*;""")
                .findAll(source).map { it.groupValues[1] }.toList()
            return imported.size <= 1
        }
        // an unqualified constant declared in this file
        return Regex("""\bstatic\s+final\s+String\s+${Regex.escape(trimmed)}\s*=\s*"${Regex.escape(modId)}"\s*;""")
            .containsMatchIn(source)
    }

    private fun controllerSource(compatPackage: String, modId: String): String = """
        |package $compatPackage;
        |
        |import net.minecraft.resources.ResourceLocation;
        |import net.neoforged.bus.api.SubscribeEvent;
        |import net.neoforged.fml.common.EventBusSubscriber;
        |import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
        |import net.neoforged.neoforge.common.world.chunk.TicketController;
        |
        |/** The forced-chunk tickets Forge keyed by the mod id "$modId", as one NeoForge ticket controller. */
        |@EventBusSubscriber(modid = "$modId", bus = EventBusSubscriber.Bus.MOD)
        |public final class $CLASS_NAME {
        |    public static final TicketController CONTROLLER =
        |        new TicketController(ResourceLocation.fromNamespaceAndPath("$modId", "forced_chunks"));
        |
        |    private $CLASS_NAME() {
        |    }
        |
        |    @SubscribeEvent
        |    public static void register(RegisterTicketControllersEvent event) {
        |        event.register(CONTROLLER);
        |    }
        |}
        |""".trimMargin()
}
