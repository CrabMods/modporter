package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Migrates Create's complete JourneyMap 5.x fullscreen hook family to JourneyMap v2.
 *
 * Admission is source-family based: both known pre-pass source shapes normalize to the same
 * JavaParser AST contract. Unknown members, method bodies, consumers, mixin entries, or partial
 * native output fail closed before any write.
 */
internal object CreateJourneyMapMigration {
    const val RULE_ID = "struct-create-native-journeymap-v2"

    private const val TRAIN_MAP =
        "src/main/java/com/simibubi/create/compat/trainmap/JourneyTrainMap.java"
    private const val FULLSCREEN_MIXIN =
        "src/main/java/com/simibubi/create/foundation/mixin/compat/journeymap/" +
            "JourneyFullscreenMapMixin.java"
    private const val MIXIN_CONFIG = "src/main/resources/create.mixins.json"
    private const val MIXIN_ENTRY = "compat.journeymap.JourneyFullscreenMapMixin"
    private const val MIXIN_PACKAGE = "com.simibubi.create.foundation.mixin"

    const val API_COORDINATE =
        "info.journeymap:journeymap-api-neoforge:" +
            "2.0.0-1.21.1-20260205.200232-15"
    const val API_REPOSITORY = "https://jm.gserv.me/repository/maven-public"
    const val API_JAR_SHA256 =
        "6a0261a3f26dee9a6b3876eef891478d96ea3df730d31accc0ef414251d43e33"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)? = null
    ): Result = try {
        migrateChecked(pass, projectDir, dryRun, commitProbe)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create JourneyMap family admission failed"))
    } catch (failure: Exception) {
        val rollback = failure.suppressed.joinToString { it.message ?: it::class.java.simpleName }
        Result(
            emptyList(),
            listOf(
                "Create JourneyMap family transaction failed: " +
                    (failure.message ?: failure::class.java.simpleName) +
                    if (rollback.isBlank()) "" else "; rollback failures: $rollback"
            )
        )
    }

    private fun migrateChecked(
        @Suppress("UNUSED_PARAMETER") pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)?
    ): Result {
        val trainPath = projectDir.resolve(TRAIN_MAP)
        val mixinPath = projectDir.resolve(FULLSCREEN_MIXIN)
        val configPath = projectDir.resolve(MIXIN_CONFIG)
        if (!trainPath.isRegularFile() && !mixinPath.isRegularFile()) return Result.EMPTY
        if (!trainPath.isRegularFile()) abort("JourneyMap family is incomplete: missing $TRAIN_MAP")

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val trainBefore = trainPath.readText()
        val nativeExpected = migrateSource(LEGACY_TRAIN_MAP)
        val actualAst = canonicalUnit(parser, trainBefore, TRAIN_MAP)
        val nativeAst = canonicalUnit(parser, nativeExpected, "expected native JourneyTrainMap")

        if (actualAst == nativeAst) {
            if (mixinPath.exists()) {
                abort("Native JourneyTrainMap is paired with the deleted legacy mixin source")
            }
            if (!configPath.isRegularFile()) {
                abort("Native JourneyMap family is incomplete: missing $MIXIN_CONFIG")
            }
            val entries = journeyMixinEntries(configPath.readText())
            if (entries.isNotEmpty()) {
                abort("Native JourneyTrainMap still has legacy mixin config entries: $entries")
            }
            closeSourceGraph(projectDir, parser)
            return Result.EMPTY
        }

        val legacyAst = canonicalLegacyUnit(parser, LEGACY_TRAIN_MAP, "expected legacy JourneyTrainMap")
        if (canonicalLegacy(actualAst) != legacyAst) {
            abort(
                "JourneyTrainMap has unknown members or method bodies; normalizedAstSha256=" +
                    sha256(actualAst.toByteArray(StandardCharsets.UTF_8))
            )
        }
        if (!mixinPath.isRegularFile()) {
            abort("Legacy JourneyMap family is incomplete: missing $FULLSCREEN_MIXIN")
        }
        if (!configPath.isRegularFile()) {
            abort("Legacy JourneyMap family is incomplete: missing $MIXIN_CONFIG")
        }
        val mixinBefore = mixinPath.readText()
        val expectedMixin = canonicalUnit(parser, LEGACY_MIXIN, "expected legacy JourneyMap mixin")
        val actualMixin = canonicalUnit(parser, mixinBefore, FULLSCREEN_MIXIN)
        if (actualMixin != expectedMixin) {
            abort(
                "JourneyFullscreenMapMixin has unknown members or method bodies; " +
                    "normalizedAstSha256=" +
                    sha256(actualMixin.toByteArray(StandardCharsets.UTF_8))
            )
        }

        val configBefore = configPath.readText()
        val entries = journeyMixinEntries(configBefore)
        if (entries != listOf("client:$MIXIN_ENTRY")) {
            abort("Expected one JourneyMap mixin client entry, found ${entries.ifEmpty { listOf("(none)") }}")
        }
        closeSourceGraph(projectDir, parser)

        val trainAfter = migrateSource(trainBefore)
        if (canonicalUnit(parser, trainAfter, "migrated JourneyTrainMap") != nativeAst) {
            abort("JourneyTrainMap rewrite did not produce the admitted native AST")
        }
        val configAfter = removeMixinEntry(configBefore)
        if (journeyMixinEntries(configAfter).isNotEmpty()) {
            abort("JourneyMap mixin entry remained after config rewrite")
        }

        val mutations = listOf(
            Mutation.text(
                trainPath,
                trainBefore,
                trainAfter,
                "Migrate Create's JourneyMap overlay to the v2 fullscreen render event"
            ),
            Mutation.delete(
                mixinPath,
                "Delete the obsolete JourneyMap internal-field fullscreen mixin"
            ),
            Mutation.text(
                configPath,
                configBefore,
                configAfter,
                "Remove the obsolete JourneyMap fullscreen mixin config entry"
            )
        )
        if (!dryRun) commitFamily(mutations, commitProbe)
        return Result(
            mutations.map { mutation ->
                Change(
                    file = mutation.path,
                    line = 1,
                    description = mutation.description,
                    before = mutation.preview(mutation.before),
                    after = mutation.preview(mutation.after),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    private fun closeSourceGraph(projectDir: Path, parser: JavaParser) {
        val sourceRoot = projectDir.resolve("src/main")
        if (!sourceRoot.exists()) return
        val foreign = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.toString().endsWith(".java") }
                .map { path -> projectDir.relativize(path).toString().replace('\\', '/') to path }
                .filter { (_, path) -> path.readText().contains("journeymap.") }
                .map { (relative, path) ->
                    Triple(relative, path, parsedJourneyMapReferences(parser, path))
                }
                .filter { (relative, _, references) ->
                    relative != TRAIN_MAP &&
                        relative != FULLSCREEN_MIXIN &&
                        references.isNotEmpty()
                }
                .map { (relative, _, references) ->
                    "$relative references ${references.joinToString()}"
                }
                .toList()
        }
        if (foreign.isNotEmpty()) {
            abort("Unrecognized JourneyMap source consumers: ${foreign.sorted().joinToString()}")
        }
    }

    private fun parsedJourneyMapReferences(parser: JavaParser, path: Path): List<String> {
        val parsed = parser.parse(path)
        if (!parsed.isSuccessful) {
            abort(
                "Cannot close JourneyMap source graph because ${path.fileName} does not parse: " +
                    parsed.problems.joinToString { it.verboseMessage }
            )
        }
        val unit = parsed.result.orElseThrow()
        val references = linkedSetOf<String>()
        unit.imports.mapTo(references) { it.nameAsString }
        unit.findAll(ClassOrInterfaceType::class.java)
            .mapTo(references) { it.asString() }
        unit.findAll(AnnotationExpr::class.java)
            .mapTo(references) { it.nameAsString }
        unit.findAll(FieldAccessExpr::class.java)
            .mapTo(references) { it.toString() }
        unit.findAll(MethodCallExpr::class.java)
            .mapNotNullTo(references) { call ->
                call.scope.map { scope -> scope.toString() }.orElse(null)
            }
        return references.filter { it.startsWith("journeymap.") }
    }

    private fun journeyMixinEntries(source: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(source) }
            .getOrElse { abort("Cannot parse $MIXIN_CONFIG: ${it.message}") }
        val objectRoot = root as? JsonObject ?: abort("$MIXIN_CONFIG root is not an object")
        val packageName = objectRoot["package"] as? JsonPrimitive
        if (packageName == null || !packageName.isString || packageName.content != MIXIN_PACKAGE) {
            abort(
                "$MIXIN_CONFIG has unexpected mixin package; expected $MIXIN_PACKAGE, " +
                    "found ${packageName?.content ?: "(missing)"}"
            )
        }
        val found = mutableListOf<String>()
        objectRoot.forEach { (key, value) ->
            val array = value as? JsonArray ?: return@forEach
            array.forEach { element ->
                val primitive = element as? JsonPrimitive ?: return@forEach
                if (primitive.isString && primitive.content.contains("JourneyFullscreenMapMixin")) {
                    found += "$key:${primitive.content}"
                }
            }
        }
        return found
    }

    private fun removeMixinEntry(source: String): String {
        val line = Regex(
            """(?m)^[ \t]*"${Regex.escape(MIXIN_ENTRY)}",[ \t]*(?:\r?\n|$)"""
        )
        val matches = line.findAll(source).toList()
        if (matches.size != 1) abort("Could not isolate the admitted JourneyMap mixin config line")
        return source.removeRange(matches.single().range)
    }

    private fun migrateSource(source: String): String {
        val crlf = source.contains("\r\n")
        var result = source.replace("\r\n", "\n")
        result = replaceOne(
            result,
            "import com.mojang.blaze3d.vertex.PoseStack;\n" +
                "import com.simibubi.create.foundation.gui.RemovedGuiUtils;",
            "import com.mojang.blaze3d.vertex.PoseStack;\n" +
                "import com.simibubi.create.Create;\n" +
                "import com.simibubi.create.foundation.gui.RemovedGuiUtils;"
        )
        result = replaceOne(
            result,
            "import journeymap.client.api.display.Context.UI;\n" +
                "import journeymap.client.api.util.UIState;\n" +
                "import journeymap.client.ui.fullscreen.Fullscreen;",
            "import journeymap.api.v2.client.IClientAPI;\n" +
                "import journeymap.api.v2.client.IClientPlugin;\n" +
                "import journeymap.api.v2.client.JourneyMapPlugin;\n" +
                "import journeymap.api.v2.client.display.Context.UI;\n" +
                "import journeymap.api.v2.client.event.FullscreenRenderEvent;\n" +
                "import journeymap.api.v2.client.fullscreen.IFullscreen;\n" +
                "import journeymap.api.v2.client.util.UIState;\n" +
                "import journeymap.api.v2.common.event.FullscreenEventRegistry;\n" +
                "import journeymap.client.ui.fullscreen.Fullscreen;"
        )
        result = replaceOne(
            result,
            "import net.minecraft.client.gui.GuiGraphics;\n" +
                "import net.minecraft.client.renderer.Rect2i;",
            "import net.minecraft.client.gui.GuiGraphics;\n" +
                "import net.minecraft.client.gui.screens.Screen;\n" +
                "import net.minecraft.client.renderer.Rect2i;"
        )
        result = result.replace(
            "import net.minecraftforge.client.event.InputEvent;",
            "import net.neoforged.neoforge.client.event.InputEvent;"
        )
        result = replaceOne(
            result,
            "public class JourneyTrainMap {",
            "@JourneyMapPlugin(apiVersion = \"2.0.0\")\n" +
                "public class JourneyTrainMap implements IClientPlugin {"
        )
        result = replaceOne(
            result,
            "\tprivate static boolean requesting;\n",
            "\tprivate static boolean requesting;\n\n" +
                "\t@Override\n" +
                "\tpublic void initialize(IClientAPI jmClientApi) {\n" +
                "\t\tFullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(" +
                "Create.ID, JourneyTrainMap::onFullscreenRender);\n" +
                "\t}\n\n" +
                "\t@Override\n" +
                "\tpublic String getModId() {\n" +
                "\t\treturn Create.ID;\n" +
                "\t}\n\n" +
                "\tprivate static void onFullscreenRender(FullscreenRenderEvent event) {\n" +
                "\t\tIFullscreen screen = event.getFullscreen();\n" +
                "\t\tonRender(event.getGraphics(), screen, screen.getCenterBlockX(true), " +
                "screen.getCenterBlockZ(true), event.getMouseX(), event.getMouseY(), " +
                "event.getPartialTicks());\n" +
                "\t}\n"
        )
        result = replaceOne(
            result,
            "public static void onRender(GuiGraphics graphics, Fullscreen screen, double x, " +
                "double z, int mX, int mY, float pt) {",
            "public static void onRender(GuiGraphics graphics, IFullscreen screen, double x, " +
                "double z, int mX, int mY, float pt) {"
        )
        result = result.replace(
            "// Called by JourneyFullscreenMapMixin",
            "// Called by JourneyMap's v2 fullscreen render event"
        )
        result = replaceOne(
            result,
            "\t\tif (!state.active)\n\t\t\treturn;\n",
            "\t\tif (!state.active)\n\t\t\treturn;\n" +
                "\t\tScreen uiScreen = screen.getScreen();\n" +
                "\t\tif (uiScreen == null)\n\t\t\treturn;\n"
        )
        val methodStart = result.indexOf("public static void onRender(GuiGraphics")
        val helperStart = result.indexOf("private static boolean renderToggleWidgetAndTooltip")
        if (methodStart < 0 || helperStart <= methodStart) abort("Cannot isolate JourneyTrainMap render body")
        var render = result.substring(methodStart, helperStart)
        render = render.replace(
            "renderToggleWidgetAndTooltip(graphics, screen,",
            "renderToggleWidgetAndTooltip(graphics, uiScreen,"
        )
        render = render.replace("screen.width", "uiScreen.width")
            .replace("screen.height", "uiScreen.height")
            .replace("mouseX /= scale;", "mouseX /= (float) (scale);")
            .replace("mouseY /= scale;", "mouseY /= (float) (scale);")
            .replace("mouseX += x;", "mouseX += (float) (x);")
            .replace("mouseY += z;", "mouseY += (float) (z);")
        result = result.substring(0, methodStart) + render + result.substring(helperStart)
        result = replaceOne(
            result,
            "private static boolean renderToggleWidgetAndTooltip(GuiGraphics graphics, " +
                "Fullscreen screen, int mouseX,",
            "private static boolean renderToggleWidgetAndTooltip(GuiGraphics graphics, " +
                "Screen screen, int mouseX,"
        )
        return if (crlf) result.replace("\n", "\r\n") else result
    }

    private fun replaceOne(source: String, before: String, after: String): String {
        val first = source.indexOf(before)
        if (first < 0 || source.indexOf(before, first + before.length) >= 0) {
            abort("Expected exactly one admitted source anchor: ${before.take(80)}")
        }
        return source.replaceRange(first, first + before.length, after)
    }

    private fun canonicalLegacyUnit(parser: JavaParser, source: String, label: String): String =
        canonicalLegacy(canonicalUnit(parser, source, label))

    private fun canonicalLegacy(canonical: String): String = canonical
        .replace(
            "importnet.neoforged.neoforge.client.event.InputEvent;",
            "importnet.minecraftforge.client.event.InputEvent;"
        )
        .replace("mouseX/=(float)(scale);", "mouseX/=scale;")
        .replace("mouseY/=(float)(scale);", "mouseY/=scale;")
        .replace("mouseX+=(float)(x);", "mouseX+=x;")
        .replace("mouseY+=(float)(z);", "mouseY+=z;")

    private fun canonicalUnit(parser: JavaParser, source: String, label: String): String {
        val parsed = parser.parse(source)
        if (!parsed.isSuccessful) {
            abort("Cannot parse $label: ${parsed.problems.joinToString { it.verboseMessage }}")
        }
        return canonical(parsed.result.orElseThrow())
    }

    private fun canonical(node: Node): String {
        val copy = node.clone()
        copy.allContainedComments.toList().forEach { it.remove() }
        copy.removeComment()
        val source = copy.toString()
        val normalized = StringBuilder(source.length)
        var quoted = false
        var character = false
        var escaped = false
        source.forEach { ch ->
            if (quoted || character) {
                normalized.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    quoted && ch == '"' -> quoted = false
                    character && ch == '\'' -> character = false
                }
            } else {
                when {
                    ch == '"' -> {
                        quoted = true
                        normalized.append(ch)
                    }
                    ch == '\'' -> {
                        character = true
                        normalized.append(ch)
                    }
                    !ch.isWhitespace() -> normalized.append(ch)
                }
            }
        }
        return normalized.toString()
    }

    private fun commitFamily(
        mutations: List<Mutation>,
        commitProbe: ((Int, Path) -> Unit)?
    ) {
        val staged = linkedMapOf<Mutation, Path>()
        val committed = mutableListOf<Mutation>()
        try {
            mutations.filter { it.after != null }.forEach { mutation ->
                mutation.path.parent.createDirectories()
                val temporary = Files.createTempFile(
                    mutation.path.parent,
                    ".modporter-journeymap-",
                    ".tmp"
                )
                Files.write(temporary, mutation.after)
                staged[mutation] = temporary
            }
            mutations.forEachIndexed { index, mutation ->
                commitProbe?.invoke(index, mutation.path)
                if (mutation.after == null) {
                    Files.deleteIfExists(mutation.path)
                } else {
                    Files.move(
                        staged.getValue(mutation),
                        mutation.path,
                        ATOMIC_MOVE,
                        REPLACE_EXISTING
                    )
                }
                committed += mutation
            }
        } catch (failure: Exception) {
            committed.asReversed().forEach { mutation ->
                runCatching {
                    if (mutation.before == null) {
                        Files.deleteIfExists(mutation.path)
                    } else {
                        mutation.path.parent.createDirectories()
                        val rollback = Files.createTempFile(
                            mutation.path.parent,
                            ".modporter-journeymap-rollback-",
                            ".tmp"
                        )
                        Files.write(rollback, mutation.before)
                        Files.move(rollback, mutation.path, ATOMIC_MOVE, REPLACE_EXISTING)
                    }
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            staged.values.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private data class Mutation(
        val path: Path,
        val before: ByteArray?,
        val after: ByteArray?,
        val description: String
    ) {
        fun preview(bytes: ByteArray?): String =
            bytes?.toString(StandardCharsets.UTF_8)?.take(180) ?: "(missing file)"

        companion object {
            fun text(path: Path, before: String, after: String, description: String) =
                Mutation(
                    path,
                    before.toByteArray(StandardCharsets.UTF_8),
                    after.toByteArray(StandardCharsets.UTF_8),
                    description
                )

            fun delete(path: Path, description: String) =
                Mutation(path, path.readBytes(), null, description)
        }
    }

    private val LEGACY_TRAIN_MAP = """
package com.simibubi.create.compat.trainmap;

import java.util.List;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.RemovedGuiUtils;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.infrastructure.config.AllConfigs;

import journeymap.client.api.display.Context.UI;
import journeymap.client.api.util.UIState;
import journeymap.client.ui.fullscreen.Fullscreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.InputEvent;

public class JourneyTrainMap {

	private static boolean requesting;

	public static void tick() {
		if (!AllConfigs.client().showTrainMapOverlay.get() || !(Minecraft.getInstance().screen instanceof Fullscreen)) {
			if (requesting)
				TrainMapSyncClient.stopRequesting();
			requesting = false;
			return;
		}
		TrainMapManager.tick();
		requesting = true;
		TrainMapSyncClient.requestData();
	}

	public static void mouseClick(InputEvent.MouseButton.Pre event) {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.screen instanceof Fullscreen screen))
			return;

		Window window = mc.getWindow();
		double mX = mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
		double mY = mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();

		if (TrainMapManager.handleToggleWidgetClick(Mth.floor(mX), Mth.floor(mY), 3, 30))
			event.setCanceled(true);
	}

	// Called by JourneyFullscreenMapMixin
	public static void onRender(GuiGraphics graphics, Fullscreen screen, double x, double z, int mX, int mY, float pt) {
		UIState state = screen.getUiState();
		if (state == null)
			return;
		if (state.ui != UI.Fullscreen)
			return;
		if (!state.active)
			return;
		if (!AllConfigs.client().showTrainMapOverlay.get()) {
			renderToggleWidgetAndTooltip(graphics, screen, mX, mY);
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();

		double guiScale = (double) window.getScreenWidth() / window.getGuiScaledWidth();
		double scale = state.blockSize / guiScale;

		PoseStack pose = graphics.pose();
		pose.pushPose();

		pose.translate(screen.width / 2.0f, screen.height / 2.0f, 0);
		pose.scale((float) scale, (float) scale, 1);
		pose.translate(-x, -z, 0);

		float mouseX = mX - screen.width / 2.0f;
		float mouseY = mY - screen.height / 2.0f;
		mouseX /= scale;
		mouseY /= scale;
		mouseX += x;
		mouseY += z;

		Rect2i bounds =
			new Rect2i(Mth.floor(-screen.width / 2.0f / scale + x), Mth.floor(-screen.height / 2.0f / scale + z),
				Mth.floor(screen.width / scale), Mth.floor(screen.height / scale));

		List<FormattedText> tooltip =
			TrainMapManager.renderAndPick(graphics, Mth.floor(mouseX), Mth.floor(mouseY), pt, false, bounds);

		pose.popPose();

		if (!renderToggleWidgetAndTooltip(graphics, screen, mX, mY) && tooltip != null)
			RemovedGuiUtils.drawHoveringText(graphics, tooltip, mX, mY, screen.width, screen.height, 256, mc.font);
	}

	private static boolean renderToggleWidgetAndTooltip(GuiGraphics graphics, Fullscreen screen, int mouseX,
		int mouseY) {
		TrainMapManager.renderToggleWidget(graphics, 3, 30);
		if (!TrainMapManager.isToggleWidgetHovered(mouseX, mouseY, 3, 30))
			return false;

		RemovedGuiUtils.drawHoveringText(graphics, List.of(CreateLang.translate("train_map.toggle")
			.component()), mouseX, mouseY + 20, screen.width, screen.height, 256, Minecraft.getInstance().font);
		return true;
	}

}
""".trimIndent()

    private val LEGACY_MIXIN = """
package com.simibubi.create.foundation.mixin.compat.journeymap;

import java.awt.geom.Point2D;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.compat.trainmap.JourneyTrainMap;

import journeymap.client.render.map.GridRenderer;
import journeymap.client.ui.fullscreen.Fullscreen;
import net.minecraft.client.gui.GuiGraphics;

@Mixin(Fullscreen.class)
public abstract class JourneyFullscreenMapMixin {
	@Final
	@Shadow(remap = false)
	private static GridRenderer gridRenderer;

	@Shadow(remap = false)
	private Boolean isScrolling;

	@Shadow(remap = false)
	protected abstract Point2D.Double getMouseDrag();

	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At(target = "Ljourneymap/client/ui/fullscreen/Fullscreen;drawMap(Lnet/minecraft/client/gui/GuiGraphics;II)V", value = "INVOKE", shift = Shift.AFTER))
	public void create${'$'}journeyMapFullscreenRender(GuiGraphics graphics, int mouseX, int mouseY, float pt,
												  CallbackInfo ci) {
		boolean dragging = isScrolling;
		Point2D.Double mouseDrag = getMouseDrag();
		double x = gridRenderer.getCenterBlockX() - (dragging ? mouseDrag.x : 0);
		double z = gridRenderer.getCenterBlockZ() - (dragging ? mouseDrag.y : 0);
		JourneyTrainMap.onRender(graphics, (Fullscreen) (Object) this, x, z, mouseX, mouseY, pt);
	}
}
""".trimIndent()
}
