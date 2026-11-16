package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.GradleModLayout
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Map decorations that drew themselves on 1.20.1 and are drawn by a renderer registered for their
 * type on 1.21.1.
 *
 * 1.20.1: a `MapDecoration` subclass created with a vanilla `MapDecoration.Type` carried its own
 * render code, which a mixin into `MapRenderer$MapInstance.draw` ran just before Forge's
 * `MapDecoration#render(int)` hook (and a `ClientboundMapItemDataPacket` mixin re-created the subclass
 * on the client, since the packet only carried the vanilla type). 1.21.1: `MapDecoration` is a final
 * record whose `Holder<MapDecorationType>` travels with it (saved and synced by registry id), and
 * NeoForge's `MapDecorationRendererManager.render` runs the `IMapDecorationRenderer` registered for
 * that type at the same point of `draw` (same `inItemFrame`/`renderOnFrame` filter, same index
 * increment when it returns true).
 *
 * After the per-file composition (which gives such a class [FROM_DECORATION] to rebuild it from the
 * record), each class gets its own `MapDecorationType`: the vanilla type it was created with, with
 * `showOnItemFrame` from its `renderOnFrame()` override. Its records are created with that type; a
 * renderer registered for it runs the class's render code and answers with its `render(int)`. The
 * renderer mixin (only dispatching to the render code) and the packet mixin (every injection point gone
 * in 1.21.1, its job carried by the type) are removed with their config entries.
 */
internal object LegacyCustomMapDecorations {
    const val RULE_ID = "struct-custom-map-decoration-type"

    private const val MAP_DECORATION = "net.minecraft.world.level.saveddata.maps.MapDecoration"
    private const val VANILLA_TYPES = "net.minecraft.world.level.saveddata.maps.MapDecorationTypes"
    private const val MAP_INSTANCE = "net.minecraft.client.gui.MapRenderer\$MapInstance"
    private const val MAP_PACKET = "net.minecraft.network.protocol.game.ClientboundMapItemDataPacket"
    private const val TYPES_CLASS = "ModPorterMapDecorationTypes"
    private const val RENDERERS_CLASS = "ModPorterMapDecorationRenderers"
    private val vanillaType = Regex("""^(?:net\.minecraft\.world\.level\.saveddata\.maps\.)?MapDecorationTypes\s*\.\s*([A-Z0-9_]+)$""")

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    private class Decoration(
        val file: Path,
        val qualifiedName: String,
        val vanilla: String,
        val typeArguments: List<IntRange>,
        val showOnItemFrame: String,
        val answersRender: Boolean,
        val interfaces: Set<String>
    ) {
        val simpleName: String = qualifiedName.substringAfterLast('.')
        val constant: String = qualifiedName.split('.').filter { it.first().isUpperCase() }
            .joinToString("_") { snake(it).uppercase() }
        val path: String = constant.lowercase()
    }

    private class Rejected(message: String) : RuntimeException(message)

    private fun reject(message: String): Nothing = throw Rejected(message)

    private val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))

    fun migrate(pass: StructuralRefactorPass, projectDir: Path, dryRun: Boolean): Result {
        val moduleDir = GradleModLayout.modModule(projectDir)
        val javaRoot = moduleDir.resolve("src/main/java")
        if (!javaRoot.isDirectory()) return Result.EMPTY
        val generated = javaRoot.resolve("com/modporter/generated").toAbsolutePath().normalize()
        val files = JavaSourceText.walkJavaSourceRoots(javaRoot).use { paths ->
            paths.filter { it.extension == "java" && it.isRegularFile() && !it.toAbsolutePath().normalize().startsWith(generated) }
                .sorted()
                .toList()
        }
        val decorationFiles = files.filter { it.readText().contains(FROM_DECORATION) }
        if (decorationFiles.isEmpty()) return Result.EMPTY
        return try {
            val decorations = decorationFiles.flatMap(::decorations)
            if (decorations.isEmpty()) return Result.EMPTY
            Result(apply(pass, projectDir, moduleDir, javaRoot, files, decorations, dryRun), emptyList())
        } catch (rejected: Rejected) {
            Result(emptyList(), listOf("Custom-rendered map decorations were not given 1.21 map decoration types: ${rejected.message}"))
        }
    }

    private fun parse(file: Path): CompilationUnit =
        parser.parse(file.readText()).takeIf { it.isSuccessful }?.result?.orElse(null) ?: reject("$file does not parse")

    private fun offsets(source: String): IntArray {
        val starts = mutableListOf(0)
        source.forEachIndexed { index, character -> if (character == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun IntArray.at(position: Position): Int = this[position.line - 1] + position.column - 1

    private fun qualifiedName(unit: CompilationUnit, type: ClassOrInterfaceDeclaration): String {
        val names = generateSequence(type as com.github.javaparser.ast.Node?) { it.parentNode.orElse(null) }
            .filterIsInstance<ClassOrInterfaceDeclaration>()
            .map { it.nameAsString }
            .toList()
            .asReversed()
        return unit.packageDeclaration.map { "${it.nameAsString}." }.orElse("") + names.joinToString(".")
    }

    /** The custom-rendered decoration classes of [file] that still use the vanilla type they were written with. */
    private fun decorations(file: Path): List<Decoration> {
        val source = file.readText()
        val unit = parse(file)
        val lines = offsets(source)
        return unit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull { type ->
            val factory = type.methods.singleOrNull { it.nameAsString == FROM_DECORATION && it.isStatic && it.parameters.size == 1 }
                ?: return@mapNotNull null
            val name = qualifiedName(unit, type)
            val creations = type.constructors
                .filter { constructor -> constructor.parameters.none { it.type.asString().substringAfterLast('.') == "MapDecoration" } }
                .flatMap { it.findAll(ObjectCreationExpr::class.java) }
                .filter { it.type.asString().substringAfterLast('.') == "MapDecoration" && it.arguments.size == 5 }
            if (creations.isEmpty()) reject("$name creates no MapDecoration record")
            val arguments = creations.map { it.arguments.first() }
            if (arguments.any { it.toString().contains(TYPES_CLASS) }) return@mapNotNull null
            val spelled = arguments.map { it.toString().replace(Regex("""\s+"""), "") }.distinct().singleOrNull()
                ?: reject("$name is created with different types ${arguments.map { it.toString() }.distinct()}")
            val vanilla = resolveVanillaType(unit, spelled) ?: reject("$name is created with $spelled, not a vanilla MapDecorationTypes holder")
            if (!TargetMembers.hasField(VANILLA_TYPES, vanilla)) reject("MapDecorationTypes.$vanilla is not a 1.21.1 decoration type")
            val renderOnFrame = type.methods.filter { it.nameAsString == "renderOnFrame" && it.parameters.isEmpty() }
            val showOnItemFrame = when (renderOnFrame.size) {
                0 -> "legacy.showOnItemFrame()"
                1 -> constantBoolean(renderOnFrame.single()) ?: reject("$name.renderOnFrame() is not a constant")
                else -> reject("$name declares renderOnFrame() more than once")
            }
            val answersRender = type.methods.any { method ->
                method.nameAsString == "render" && method.parameters.size == 1 && method.parameters.single().type.asString() == "int"
            }
            Decoration(
                file, name, vanilla,
                arguments.map { lines.at(it.range.get().begin) until lines.at(it.range.get().end) + 1 },
                showOnItemFrame, answersRender,
                type.implementedTypes.map { it.nameWithScope.substringAfterLast('.') }.toSet()
            )
        }
    }

    private fun constantBoolean(method: MethodDeclaration): String? {
        val statement = method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt ?: return null
        return (statement.expression.orElse(null) as? BooleanLiteralExpr)?.value?.toString()
    }

    /** `MapDecorationTypes.X`, directly or through a static field of [unit] initialized with it. */
    private fun resolveVanillaType(unit: CompilationUnit, spelled: String): String? {
        vanillaType.find(spelled)?.let { return it.groupValues[1] }
        val field = spelled.substringAfterLast('.')
        val initializers = unit.findAll(FieldDeclaration::class.java)
            .filter { it.isStatic }
            .flatMap { it.variables }
            .filter { it.nameAsString == field }
            .mapNotNull { it.initializer.orElse(null)?.toString()?.replace(Regex("""\s+"""), "") }
        return initializers.singleOrNull()?.let { vanillaType.find(it)?.groupValues?.get(1) }
    }

    private fun annotation(annotations: List<AnnotationExpr>, name: String): AnnotationExpr? =
        annotations.firstOrNull { it.nameAsString == name || it.nameAsString.endsWith(".$name") }

    private fun mixinTargets(unit: CompilationUnit, type: ClassOrInterfaceDeclaration): Set<String> {
        val mixin = annotation(type.annotations, "Mixin") ?: return emptySet()
        val values: List<Expression> = when (mixin) {
            is SingleMemberAnnotationExpr -> listOf(mixin.memberValue)
            is NormalAnnotationExpr -> mixin.pairs.filter { it.nameAsString == "value" || it.nameAsString == "targets" }.map { it.value }
            else -> emptyList()
        }
        return values.flatMap { if (it is ArrayInitializerExpr) it.values else listOf(it) }.mapNotNull { value ->
            when (value) {
                is StringLiteralExpr -> value.value.replace('/', '.')
                is ClassExpr -> {
                    val spelled = value.type.asString()
                    val head = spelled.substringBefore('.')
                    val imported = unit.imports.firstOrNull { !it.isStatic && !it.isAsterisk && it.nameAsString.substringAfterLast('.') == head }?.nameAsString
                    when {
                        spelled.first().isLowerCase() -> spelled
                        imported != null -> imported + spelled.removePrefix(head)
                        else -> null
                    }?.let { name ->
                        // nested classes in binary form: net.minecraft.client.gui.MapRenderer.MapInstance -> ...MapRenderer$MapInstance
                        val parts = name.split('.')
                        val firstType = parts.indexOfFirst { it.first().isUpperCase() }
                        if (firstType < 0) name else parts.take(firstType + 1).joinToString(".") +
                            parts.drop(firstType + 1).joinToString("") { "$$it" }
                    }
                }
                else -> null
            }
        }.toSet()
    }

    /** Whether [statement] is `if (x instanceof R r) r.render(6 arguments);` for one of [interfaces]. */
    private fun dispatchesRender(statement: Statement, interfaces: Set<String>): Boolean {
        val branch = statement as? IfStmt ?: return false
        if (branch.elseStmt.isPresent) return false
        val test = branch.condition as? InstanceOfExpr ?: return false
        if (test.type.asString().substringAfterLast('.') !in interfaces) return false
        val variable = test.pattern.orElse(null)?.let { pattern ->
            (pattern as? com.github.javaparser.ast.expr.TypePatternExpr)?.nameAsString
        } ?: return false
        val body = when (val then = branch.thenStmt) {
            is BlockStmt -> then.statements.singleOrNull()
            else -> then
        } as? ExpressionStmt ?: return false
        val call = body.expression as? MethodCallExpr ?: return false
        return call.nameAsString == "render" && call.arguments.size == 6 &&
            (call.scope.orElse(null) as? NameExpr)?.nameAsString == variable
    }

    /** The legacy renderer mixins: they only run the classes' render code for decorations implementing [interfaces]. */
    private fun rendererMixins(files: List<Path>, interfaces: Set<String>): List<Pair<String, Path>> = files
        .filter { file -> file.readText().let { it.contains("@Mixin") && it.contains("MapInstance") } }
        .flatMap { file ->
            val unit = parse(file)
            unit.types.filterIsInstance<ClassOrInterfaceDeclaration>()
                .filter { MAP_INSTANCE in mixinTargets(unit, it) }
                .filter { type -> type.findAll(InstanceOfExpr::class.java).any { it.type.asString().substringAfterLast('.') in interfaces } }
                .map { type ->
                    val name = qualifiedName(unit, type)
                    for (member in type.members) {
                        val admitted = when {
                            member is FieldDeclaration -> annotation(member.annotations, "Shadow") != null
                            member is MethodDeclaration -> annotation(member.annotations, "Inject") != null &&
                                member.body.orElse(null)?.statements?.singleOrNull()?.let { dispatchesRender(it, interfaces) } == true
                            else -> false
                        }
                        if (!admitted) reject("$name does more on MapRenderer\$MapInstance than run the decorations' render code")
                    }
                    name to file
                }
        }

    /** The legacy packet mixins that re-created the decoration classes: every injection point is gone in 1.21.1. */
    private fun packetMixins(files: List<Path>, decorations: List<Decoration>): List<Pair<String, Path>> = files
        .filter { file -> file.readText().let { it.contains("@Mixin") && it.contains("ClientboundMapItemDataPacket") } }
        .flatMap { file ->
            val unit = parse(file)
            unit.types.filterIsInstance<ClassOrInterfaceDeclaration>()
                .filter { MAP_PACKET in mixinTargets(unit, it) }
                .filter { type ->
                    type.findAll(ClassOrInterfaceType::class.java).any { used -> decorations.any { it.simpleName == used.nameAsString } }
                }
                .map { type ->
                    val name = qualifiedName(unit, type)
                    val selectors = type.methods.mapNotNull { annotation(it.annotations, "Inject") }.map { inject ->
                        val method = (inject as? NormalAnnotationExpr)?.pairs?.firstOrNull { it.nameAsString == "method" }?.value
                        (method as? StringLiteralExpr)?.value
                            ?: ((method as? ArrayInitializerExpr)?.values?.singleOrNull() as? StringLiteralExpr)?.value
                            ?: reject("$name has an injection without one explicit method selector")
                    }
                    if (selectors.isEmpty()) reject("$name injects nothing")
                    val live = selectors.filter { selector ->
                        val open = selector.indexOf('(')
                        open > 0 && selector.substring(open) in TargetMembers.methodDescriptors(MAP_PACKET, selector.substring(0, open))
                    }
                    if (live.isNotEmpty()) reject("$name still injects into 1.21.1 members $live")
                    name to file
                }
        }

    private fun apply(
        pass: StructuralRefactorPass,
        projectDir: Path,
        moduleDir: Path,
        javaRoot: Path,
        files: List<Path>,
        decorations: List<Decoration>,
        dryRun: Boolean
    ): List<Change> {
        decorations.groupBy { it.constant }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { reject("two decoration classes map to $it") }
        val modId = with(pass) { detectModId(projectDir) } ?: reject("the project's mod id is not unique")
        val mainClass = with(pass) { detectModMainClass(projectDir) } ?: reject("the project has no single @Mod class")
        val mainPackage = JavaSourceText.packageName(mainClass.readText()) ?: reject("the @Mod class has no package")
        val packageDir = javaRoot.resolve(mainPackage.replace('.', '/'))
        val typesFile = packageDir.resolve("$TYPES_CLASS.java")
        val renderersFile = packageDir.resolve("$RENDERERS_CLASS.java")
        if (typesFile.exists() || renderersFile.exists()) reject("$mainPackage.$TYPES_CLASS or $RENDERERS_CLASS already exists")

        val removed = rendererMixins(files, decorations.flatMap { it.interfaces }.toSet()) + packetMixins(files, decorations)
        val configs = try {
            if (removed.isEmpty()) emptyMap() else MixinConfigEntries.without(moduleDir, removed.map { it.first }.toSet())
        } catch (unlisted: MixinConfigEntries.Unlisted) {
            reject(unlisted.message ?: "the mixin configs do not list the removed mixins")
        }

        val writes = linkedMapOf<Path, Pair<String, String>>()
        for ((file, inFile) in decorations.groupBy { it.file }) {
            val before = file.readText()
            val after = inFile.flatMap { decoration -> decoration.typeArguments.map { it to "$mainPackage.$TYPES_CLASS.${decoration.constant}" } }
                .sortedByDescending { it.first.first }
                .fold(before) { text, (range, replacement) -> text.replaceRange(range, replacement) }
            writes[file] = before to after
        }
        writes[typesFile] = "" to typesSource(mainPackage, modId, decorations)
        writes[renderersFile] = "" to renderersSource(mainPackage, modId, decorations)

        val mainBefore = writes[mainClass]?.second ?: mainClass.readText()
        val bus = with(pass) { requireModEventBusName(mainBefore, "map decoration type registration") }
        val mainAfter = with(pass) { insertModBusListener(mainBefore, bus, "        $TYPES_CLASS.register($bus);", TYPES_CLASS) }
        writes[mainClass] = (writes[mainClass]?.first ?: mainBefore) to mainAfter
        writes.putAll(configs)

        val changes = mutableListOf<Change>()
        for ((file, contents) in writes) {
            if (!dryRun) {
                Files.createDirectories(file.parent)
                file.writeText(contents.second)
            }
            changes += Change(
                file, 1,
                when (file) {
                    typesFile -> "Register a map decoration type for each decoration class that rendered itself on 1.20.1"
                    renderersFile -> "Render those decoration types with the classes' own render code through NeoForge's map decoration renderers"
                    mainClass -> "Register the map decoration types on the mod event bus"
                    else -> "Create custom-rendered map decorations with their registered types"
                },
                contents.first.take(180), contents.second.take(180), Confidence.HIGH, RULE_ID
            )
        }
        for ((name, file) in removed) {
            if (!dryRun) Files.delete(file)
            changes += Change(file, 1, "Remove $name: the registered decoration type and its renderer replace it on 1.21.1",
                "@Mixin", "(removed)", Confidence.HIGH, RULE_ID)
        }
        return changes
    }

    private fun typesSource(packageName: String, modId: String, decorations: List<Decoration>): String {
        val entries = decorations.flatMap { decoration ->
            listOf(
                "",
                "    /** ${decoration.qualifiedName}: MapDecorationTypes.${decoration.vanilla}'s properties, drawn by its own renderer. */",
                "    public static final DeferredHolder<MapDecorationType, MapDecorationType> ${decoration.constant} =",
                "        TYPES.register(\"${decoration.path}\", () -> {",
                "            MapDecorationType legacy = MapDecorationTypes.${decoration.vanilla}.value();",
                "            return new MapDecorationType(legacy.assetId(), ${decoration.showOnItemFrame}, legacy.mapColor(), legacy.explorationMapElement(), legacy.trackCount());",
                "        });"
            )
        }
        return (listOf(
            "package $packageName;",
            "",
            "import net.minecraft.core.registries.Registries;",
            "import net.minecraft.world.level.saveddata.maps.MapDecorationType;",
            "import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;",
            "import net.neoforged.bus.api.IEventBus;",
            "import net.neoforged.neoforge.registries.DeferredHolder;",
            "import net.neoforged.neoforge.registries.DeferredRegister;",
            "",
            "/** Map decoration types of the decorations that drew themselves on 1.20.1 (see $RENDERERS_CLASS). */",
            "public final class $TYPES_CLASS {",
            "    private static final DeferredRegister<MapDecorationType> TYPES = DeferredRegister.create(Registries.MAP_DECORATION_TYPE, \"$modId\");"
        ) + entries + listOf(
            "",
            "    private $TYPES_CLASS() {",
            "    }",
            "",
            "    public static void register(IEventBus bus) {",
            "        TYPES.register(bus);",
            "    }",
            "}",
            ""
        )).joinToString("\n")
    }

    private fun renderersSource(packageName: String, modId: String, decorations: List<Decoration>): String {
        val registrations = decorations.map { decoration ->
            "        event.register($TYPES_CLASS.${decoration.constant}.value(), new ${rendererName(decoration)}());"
        }
        val renderers = decorations.flatMap { decoration ->
            listOf(
                "",
                "    /** Runs ${decoration.qualifiedName}'s render code where 1.20.1 ran it, before Forge's MapDecoration#render(int) hook. */",
                "    private static final class ${rendererName(decoration)} implements IMapDecorationRenderer {",
                "        @Override",
                "        public boolean render(MapDecoration decoration, PoseStack poseStack, MultiBufferSource bufferSource, MapItemSavedData mapData,",
                "                MapDecorationTextureManager decorationTextures, boolean inItemFrame, int packedLight, int index) {",
                "            ${decoration.qualifiedName} legacy = ${decoration.qualifiedName}.$FROM_DECORATION(decoration);",
                "            legacy.render(poseStack, bufferSource, inItemFrame, packedLight, mapData, index);",
                "            return ${if (decoration.answersRender) "legacy.render(index)" else "false"};",
                "        }",
                "    }"
            )
        }
        return (listOf(
            "package $packageName;",
            "",
            "import com.mojang.blaze3d.vertex.PoseStack;",
            "import net.minecraft.client.renderer.MultiBufferSource;",
            "import net.minecraft.client.resources.MapDecorationTextureManager;",
            "import net.minecraft.world.level.saveddata.maps.MapDecoration;",
            "import net.minecraft.world.level.saveddata.maps.MapItemSavedData;",
            "import net.neoforged.api.distmarker.Dist;",
            "import net.neoforged.bus.api.SubscribeEvent;",
            "import net.neoforged.fml.common.EventBusSubscriber;",
            "import net.neoforged.neoforge.client.gui.map.IMapDecorationRenderer;",
            "import net.neoforged.neoforge.client.gui.map.RegisterMapDecorationRenderersEvent;",
            "",
            "/** Renderers of the map decoration types in $TYPES_CLASS. */",
            "@EventBusSubscriber(modid = \"$modId\", value = Dist.CLIENT)",
            "public final class $RENDERERS_CLASS {",
            "    private $RENDERERS_CLASS() {",
            "    }",
            "",
            "    @SubscribeEvent",
            "    public static void register(RegisterMapDecorationRenderersEvent event) {"
        ) + registrations + listOf("    }") + renderers + listOf(
            "}",
            ""
        )).joinToString("\n")
    }

    private fun rendererName(decoration: Decoration): String =
        decoration.constant.lowercase().split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } } + "Renderer"

    private fun snake(name: String): String = name.replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2").replace(Regex("""([A-Z]+)([A-Z][a-z])"""), "$1_$2").lowercase()
}
