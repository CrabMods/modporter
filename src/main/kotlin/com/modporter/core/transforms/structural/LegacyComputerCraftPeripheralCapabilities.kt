package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Bridges a legacy ComputerCraft peripheral identity key to CC:Tweaked's real
 * NeoForge block capability.
 *
 * The old `CapabilityManager.get(new CapabilityToken<>() {})` key remains an
 * internal comparison key used by the converted block entities. The generated
 * provider registers `PeripheralCapability.get()` and forwards the external
 * query into that legacy key, preserving ComputerCraft interoperability rather
 * than satisfying compilation with a token stub.
 */
internal object LegacyComputerCraftPeripheralCapabilities {
    const val RULE_ID = "struct-legacy-computercraft-peripheral-capability"
    private const val PERIPHERAL = "dan200.computercraft.api.peripheral.IPeripheral"
    private val ownedTokenImports = listOf("CapabilityManager", "CapabilityToken")

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(
        project: Path,
        modId: String,
        compatPackage: String,
        dryRun: Boolean
    ): Result {
        val root = project.resolve("src/main/java")
        if (!Files.isDirectory(root)) return Result.EMPTY
        val providerFile = root.resolve(compatPackage.replace('.', '/')).resolve("LegacyCapabilityProvider.java")
        if (!providerFile.exists()) return Result.EMPTY

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val files = com.modporter.core.transforms.util.JavaSourceText.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.toString().endsWith(".java") }
                .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
                .toList()
        }
        val candidates = mutableListOf<Pair<Path, String>>()
        val gates = mutableListOf<String>()
        for (file in files) {
            val source = file.readText()
            if (!source.contains("IPeripheral") ||
                !(ownedTokenImports.all(source::contains) ||
                    source.contains("LegacyComputerCraftCapabilities.PERIPHERAL"))
            ) continue
            val unit = parser.parse(source).result.orElse(null) ?: continue
            val peripheralImport = unit.imports.singleOrNull {
                !it.isStatic && !it.isAsterisk && it.nameAsString == PERIPHERAL
            } ?: continue
            val fields = unit.findAll(FieldDeclaration::class.java).flatMap { field ->
                field.variables.mapNotNull { variable ->
                    if (!field.isStatic || variable.type.asString().replace(" ", "") != "Capability<${peripheralImport.name.identifier}>") {
                        return@mapNotNull null
                    }
                    val initializer = variable.initializer.orElse(null) ?: return@mapNotNull null
                    val legacyToken = (initializer as? MethodCallExpr)?.let { call ->
                        if (call.nameAsString != "get" ||
                            call.scope.map { it.toString() }.orElse("") != "CapabilityManager" ||
                            call.arguments.size != 1
                        ) return@let false
                        val token = call.arguments.single() as? ObjectCreationExpr ?: return@let false
                        token.type.nameAsString == "CapabilityToken" && token.anonymousClassBody.isPresent
                    } == true
                    val migratedKey = initializer.toString() ==
                        "LegacyComputerCraftCapabilities.PERIPHERAL"
                    if (!legacyToken && !migratedKey) return@mapNotNull null
                    field to variable
                }
            }
            if (fields.size != 1) {
                if (fields.isNotEmpty()) gates += "ComputerCraft peripheral migration requires exactly one legacy peripheral capability field in $file"
                continue
            }
            val (field, variable) = fields.single()
            val identityMethod = unit.findAll(MethodDeclaration::class.java).singleOrNull { method ->
                method.parameters.size == 1 &&
                    method.body.orElse(null)?.findAll(BinaryExpr::class.java)?.any { comparison ->
                        comparison.operator == BinaryExpr.Operator.EQUALS &&
                            setOf(comparison.left.toString(), comparison.right.toString()) ==
                            setOf(method.parameters.single().nameAsString, variable.nameAsString)
                    } == true
            }
            if (identityMethod == null) {
                gates += "ComputerCraft peripheral migration cannot prove the legacy field is an identity key in $file"
                continue
            }

            LexicalPreservingPrinter.setup(unit)
            variable.setInitializer("LegacyComputerCraftCapabilities.PERIPHERAL")
            if (unit.imports.none {
                    !it.isStatic && !it.isAsterisk &&
                        it.nameAsString == "$compatPackage.LegacyComputerCraftCapabilities"
                }) {
                unit.addImport("$compatPackage.LegacyComputerCraftCapabilities")
            }
            var migrated = LexicalPreservingPrinter.print(unit)
            for (simpleName in ownedTokenImports) {
                migrated = removeUnusedOwnedTokenImport(
                    migrated,
                    "$compatPackage.$simpleName"
                )
            }
            candidates += file to migrated
        }
        if (candidates.isEmpty()) return Result(emptyList(), gates)
        if (candidates.size != 1) {
            return Result(
                emptyList(),
                gates + "ComputerCraft peripheral migration found ${candidates.size} legacy peripheral key owners; expected one"
            )
        }

        val changes = mutableListOf<Change>()
        val (ownerFile, migratedOwner) = candidates.single()
        if (migratedOwner != ownerFile.readText()) {
            if (!dryRun) ownerFile.writeText(migratedOwner)
            changes += Change(
                ownerFile,
                1,
                "Use the generated legacy peripheral key behind CC:Tweaked's NeoForge block capability",
                "CapabilityManager.get(new CapabilityToken<IPeripheral>())",
                "LegacyComputerCraftCapabilities.PERIPHERAL",
                Confidence.HIGH,
                RULE_ID
            )
        }

        val generated = generatedSources(compatPackage, modId)
        for ((name, text) in generated) {
            val file = root.resolve(compatPackage.replace('.', '/')).resolve("$name.java")
            if (file.exists() && file.readText() == text) continue
            if (!dryRun) {
                file.parent.createDirectories()
                file.writeText(text)
            }
            changes += Change(
                file,
                1,
                "Generate the CC:Tweaked peripheral block-capability boundary",
                "(absent)",
                "$compatPackage.$name",
                Confidence.HIGH,
                RULE_ID
            )
        }
        return Result(changes, gates)
    }

    private fun removeUnusedOwnedTokenImport(source: String, canonical: String): String {
        val import = Regex(
            """(?m)^[ \t]*import\s+${Regex.escape(canonical)}\s*;\s*\r?\n"""
        )
        val without = import.replace(source, "")
        if (without == source) return source
        val simpleName = canonical.substringAfterLast('.')
        return if (Regex("""\b${Regex.escape(simpleName)}\b""")
                .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(without))
        ) source else without
    }

    private fun generatedSources(compatPackage: String, modId: String): Map<String, String> = mapOf(
        "LegacyComputerCraftCapabilities" to """
            |package $compatPackage;
            |
            |/** Internal key used by converted legacy getCapability methods for CC:Tweaked peripherals. */
            |public final class LegacyComputerCraftCapabilities {
            |    public static final Capability<dan200.computercraft.api.peripheral.IPeripheral> PERIPHERAL = new Capability<>();
            |
            |    private LegacyComputerCraftCapabilities() {
            |    }
            |}
            |""".trimMargin(),
        "LegacyComputerCraftPeripheralCapabilities" to """
            |package $compatPackage;
            |
            |import net.minecraft.core.registries.BuiltInRegistries;
            |import net.minecraft.resources.ResourceLocation;
            |import net.minecraft.world.level.block.entity.BlockEntity;
            |import net.minecraft.world.level.block.entity.BlockEntityType;
            |import net.neoforged.bus.api.SubscribeEvent;
            |import net.neoforged.fml.ModList;
            |import net.neoforged.fml.common.EventBusSubscriber;
            |import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
            |
            |/** Exposes converted legacy providers through CC:Tweaked's canonical peripheral capability. */
            |@EventBusSubscriber(modid = "$modId")
            |public final class LegacyComputerCraftPeripheralCapabilities {
            |    private LegacyComputerCraftPeripheralCapabilities() {
            |    }
            |
            |    @SubscribeEvent
            |    public static void register(RegisterCapabilitiesEvent event) {
            |        if (!ModList.get().isLoaded("computercraft")) return;
            |        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            |            ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            |            if (key != null && key.getNamespace().equals("$modId")) {
            |                ComputerCraft.register(event, type);
            |            }
            |        }
            |    }
            |
            |    /**
            |     * Kept behind a nested class so a runtime without CC:Tweaked never links its API
            |     * classes while loading the always-present event subscriber.
            |     */
            |    private static final class ComputerCraft {
            |        private static <BE extends BlockEntity> void register(RegisterCapabilitiesEvent event, BlockEntityType<BE> type) {
            |            event.registerBlockEntity(
            |                dan200.computercraft.api.peripheral.PeripheralCapability.get(),
            |                type,
            |                (be, side) -> be instanceof LegacyCapabilityProvider provider
            |                    ? provider.getCapability(LegacyComputerCraftCapabilities.PERIPHERAL, side).orElse(null)
            |                    : null
            |            );
            |        }
            |    }
            |}
            |""".trimMargin()
    )
}
