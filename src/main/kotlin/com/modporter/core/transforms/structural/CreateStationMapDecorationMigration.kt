package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Owns Create's legacy station-map decoration family as one transaction.
 *
 * The 1.20.1 implementation encoded subclass identity as a trailing station-index array on
 * ClientboundMapItemDataPacket. Minecraft 1.21.1 makes MapDecoration a final record and carries
 * its registered MapDecorationType through MapDecoration.STREAM_CODEC. NeoForge dispatches
 * IMapDecorationRenderer by that exact registered type.
 *
 * Admission closes the source graph before any write. Unknown producers, consumers, renderer
 * hooks, packet hooks, config entries, or asset identities fail closed.
 */
internal object CreateStationMapDecorationMigration {
    const val RULE_ID = "struct-create-native-station-map-decoration"

    private const val STATION_MARKER =
        "src/main/java/com/simibubi/create/content/trains/station/StationMarker.java"
    private const val MAP_DATA_MIXIN =
        "src/main/java/com/simibubi/create/foundation/mixin/MapItemSavedDataMixin.java"
    private const val PACKET_MIXIN =
        "src/main/java/com/simibubi/create/foundation/mixin/ClientboundMapItemDataPacketMixin.java"
    private const val RENDER_MIXIN =
        "src/main/java/com/simibubi/create/foundation/mixin/client/MapRendererMapInstanceMixin.java"
    private const val CUSTOM_RENDERER =
        "src/main/java/com/simibubi/create/foundation/map/CustomRenderedMapDecoration.java"
    private const val CREATE_MAIN =
        "src/main/java/com/simibubi/create/Create.java"
    private const val MIXIN_CONFIG = "src/main/resources/create.mixins.json"
    private const val LEGACY_ASSET =
        "src/main/resources/assets/create/textures/gui/station_map_icon.png"

    private const val DECORATION_TYPES =
        "src/main/java/com/simibubi/create/AllMapDecorationTypes.java"
    private const val NATIVE_RENDERER =
        "src/main/java/com/simibubi/create/foundation/map/StationMapDecorationRenderer.java"
    private const val NATIVE_EVENTS =
        "src/main/java/com/simibubi/create/foundation/map/StationMapDecorationEvents.java"
    private const val NATIVE_ASSET =
        "src/main/resources/assets/create/textures/map/decorations/station.png"

    private const val LEGACY_ASSET_SHA256 =
        "14ff606c8a0cc07309a2b4dc4316f9d9a3bb51addfafa76865d4980624e68b87"
    private const val LEGACY_TYPE_FIELD_SHA256 =
        "e61f0b3f4a4f36d13b568bcf4da44f3698d649049149a582ca1dd79ffc08f013"
    private const val LEGACY_DECORATION_DECLARATION_SHA256 =
        "11ac460a316025f34c6d3658f7c708bf7a9c4369425516f66dc4b23279162bd5"
    // The exact same declaration after TextReplacementPass's uv/light renames and
    // AstTransformPass's EndVertexRemover. Positions, colors, UVs and call order are unchanged.
    private const val TEXT_AST_DECORATION_DECLARATION_SHA256 =
        "277629b0aa8036b278592ac98adf850b152600c27949a65e60c55ba5fb767398"
    private const val LEGACY_PACKET_DECLARATION_SHA256 =
        "ff5b8b31afc2de633b9d5ce55ebb4ba5a1f40c45775fb4ae485e71a55d8b59df"
    private const val LEGACY_PACKET_IMPORTS_SHA256 =
        "af182a76cc314a33a819de5feb2e2e861068f07c4421efa30f63f61d27c4ec09"
    private const val LEGACY_RENDER_DECLARATION_SHA256 =
        "f8be14af56d51db8afa03af93c090ae2c6d6ab7f3ef7ef64123a259156aeb460"
    private const val LEGACY_RENDER_IMPORTS_SHA256 =
        "93d1997fe90ba1250ebcbaab219dd5a91d3fb9b428c9e29d988cf90866efdca2"
    private const val LEGACY_CUSTOM_RENDERER_DECLARATION_SHA256 =
        "8e37ce8d2c8c2fc2c84547c7f2600bf8ebbc1885aa41370251677160b75b85ab"
    private const val LEGACY_CUSTOM_RENDERER_IMPORTS_SHA256 =
        "74a599a934775a75d50fad9bf95e56b8fcf4dc367ba834666d8cb7d69a26b0d4"

    private val GENERATED_TYPES = mapOf(
        "com.simibubi.create.AllMapDecorationTypes" to DECORATION_TYPES,
        "com.simibubi.create.foundation.map.StationMapDecorationRenderer" to
            NATIVE_RENDERER,
        "com.simibubi.create.foundation.map.StationMapDecorationEvents" to
            NATIVE_EVENTS
    )

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
        Result(
            emptyList(),
            listOf(abort.message ?: "Create station-map family admission failed")
        )
    } catch (failure: Exception) {
        val rollbackFailures = failure.suppressed
            .map { it.message ?: it::class.java.simpleName }
        val suffix = if (rollbackFailures.isEmpty()) {
            ""
        } else {
            "; rollback failures: ${rollbackFailures.joinToString()}"
        }
        Result(
            emptyList(),
            listOf(
                "Create station-map family transaction failed: " +
                    (failure.message ?: failure::class.java.simpleName) +
                    suffix
            )
        )
    }

    private fun migrateChecked(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)?
    ): Result {
        val stationPath = projectDir.resolve(STATION_MARKER)
        val nativeTypesPath = projectDir.resolve(DECORATION_TYPES)
        if (!stationPath.isRegularFile() && !nativeTypesPath.isRegularFile()) {
            return Result.EMPTY
        }
        if (!targetContractIsVerified()) {
            abort("NeoForge 21.1.230 map decoration registry, codec, or renderer ABI changed")
        }

        val expectedTypes = renderDecorationTypes()
        val expectedRenderer = renderNativeRenderer()
        val expectedEvents = renderNativeEvents()
        if (isNativeFamily(projectDir, expectedTypes, expectedRenderer, expectedEvents)) {
            return Result.EMPTY
        }

        val required = listOf(
            STATION_MARKER,
            MAP_DATA_MIXIN,
            PACKET_MIXIN,
            RENDER_MIXIN,
            CUSTOM_RENDERER,
            CREATE_MAIN,
            MIXIN_CONFIG,
            LEGACY_ASSET
        )
        required.forEach { relative ->
            if (!projectDir.resolve(relative).isRegularFile()) {
                abort("Legacy Create station-map family is incomplete: missing $relative")
            }
        }

        closeLegacySourceGraph(projectDir)
        val asset = projectDir.resolve(LEGACY_ASSET).readBytes()
        if (sha256(asset) != LEGACY_ASSET_SHA256) {
            abort(
                "Legacy station map asset bytes changed; expected $LEGACY_ASSET_SHA256 " +
                    "at $LEGACY_ASSET"
            )
        }

        val stationBefore = projectDir.resolve(STATION_MARKER).readText()
        val mapDataBefore = projectDir.resolve(MAP_DATA_MIXIN).readText()
        val packetBefore = projectDir.resolve(PACKET_MIXIN).readText()
        val renderMixinBefore = projectDir.resolve(RENDER_MIXIN).readText()
        val customRendererBefore = projectDir.resolve(CUSTOM_RENDERER).readText()
        val createBefore = projectDir.resolve(CREATE_MAIN).readText()
        val mixinsBefore = projectDir.resolve(MIXIN_CONFIG).readText()

        admitLegacySemantics(
            stationBefore,
            mapDataBefore,
            packetBefore,
            renderMixinBefore,
            customRendererBefore,
            mixinsBefore
        )

        val stationAfter = migrateStationMarker(stationBefore)
        val mapDataAfter = migrateMapDataProducer(mapDataBefore)
        val createAfter = registerDecorationTypes(createBefore)
        val mixinsAfter = removeObsoleteMixins(mixinsBefore)

        requireGeneratedFqnOwnership(projectDir)
        requireGeneratedOwnership(
            projectDir.resolve(DECORATION_TYPES),
            expectedTypes,
            "AllMapDecorationTypes"
        )
        requireGeneratedOwnership(
            projectDir.resolve(NATIVE_RENDERER),
            expectedRenderer,
            "StationMapDecorationRenderer"
        )
        requireGeneratedOwnership(
            projectDir.resolve(NATIVE_EVENTS),
            expectedEvents,
            "StationMapDecorationEvents"
        )
        val nativeAssetPath = projectDir.resolve(NATIVE_ASSET)
        if (nativeAssetPath.exists() && !nativeAssetPath.readBytes().contentEquals(asset)) {
            abort("Native station map asset path is owned by different bytes: $NATIVE_ASSET")
        }

        val mutations = listOf(
            Mutation.text(
                projectDir.resolve(STATION_MARKER),
                stationBefore,
                stationAfter,
                "Replace the final MapDecoration subclass with a registered-type factory"
            ),
            Mutation.text(
                projectDir.resolve(MAP_DATA_MIXIN),
                mapDataBefore,
                mapDataAfter,
                "Create station decorations through the registered native type"
            ),
            Mutation.text(
                projectDir.resolve(CREATE_MAIN),
                createBefore,
                createAfter,
                "Register Create's station MapDecorationType on the mod event bus"
            ),
            Mutation.text(
                projectDir.resolve(MIXIN_CONFIG),
                mixinsBefore,
                mixinsAfter,
                "Remove obsolete packet and map-render mixins after native replacement"
            ),
            Mutation.delete(
                projectDir.resolve(PACKET_MIXIN),
                "Remove the obsolete trailing station-index packet side channel"
            ),
            Mutation.delete(
                projectDir.resolve(RENDER_MIXIN),
                "Remove the obsolete MapDecoration subclass renderer mixin"
            ),
            Mutation.delete(
                projectDir.resolve(CUSTOM_RENDERER),
                "Remove the obsolete MapDecoration-to-renderer association table"
            ),
            Mutation.text(
                projectDir.resolve(DECORATION_TYPES),
                null,
                expectedTypes,
                "Generate the source-owned create:station MapDecorationType registry"
            ),
            Mutation.text(
                projectDir.resolve(NATIVE_RENDERER),
                null,
                expectedRenderer,
                "Generate the NeoForge station map decoration renderer"
            ),
            Mutation.binary(
                nativeAssetPath,
                nativeAssetPath.takeIf { it.exists() }?.readBytes(),
                asset,
                "Copy the byte-identical station icon into the map decoration atlas path"
            ),
            Mutation.text(
                projectDir.resolve(NATIVE_EVENTS),
                null,
                expectedEvents,
                "Register the station renderer through the NeoForge mod-bus event"
            )
        ).filterNot { mutation ->
            when {
                mutation.before == null && mutation.after == null -> true
                mutation.before == null || mutation.after == null -> false
                else -> mutation.before.contentEquals(mutation.after)
            }
        }

        if (!dryRun) {
            commitFamily(mutations, commitProbe)
        }
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

    private fun isNativeFamily(
        projectDir: Path,
        expectedTypes: String,
        expectedRenderer: String,
        expectedEvents: String
    ): Boolean {
        val requiredSources = mapOf(
            DECORATION_TYPES to expectedTypes,
            NATIVE_RENDERER to expectedRenderer,
            NATIVE_EVENTS to expectedEvents
        )
        if (requiredSources.any { (relative, expected) ->
                val path = projectDir.resolve(relative)
                !path.isRegularFile() || !sameJava(path.readText(), expected)
            }
        ) {
            return false
        }
        if (!generatedFqnsAreUnique(projectDir)) return false
        if (listOf(PACKET_MIXIN, RENDER_MIXIN, CUSTOM_RENDERER)
                .any { projectDir.resolve(it).exists() }
        ) {
            return false
        }
        val station = projectDir.resolve(STATION_MARKER).takeIf { it.isRegularFile() }
            ?.readText() ?: return false
        val mapData = projectDir.resolve(MAP_DATA_MIXIN).takeIf { it.isRegularFile() }
            ?.readText() ?: return false
        val create = projectDir.resolve(CREATE_MAIN).takeIf { it.isRegularFile() }
            ?.readText() ?: return false
        val mixins = projectDir.resolve(MIXIN_CONFIG).takeIf { it.isRegularFile() }
            ?.readText() ?: return false
        val asset = projectDir.resolve(NATIVE_ASSET)
        return nativeStationFactoryIsExact(station) &&
            nativeMapDataProducerIsExact(mapData) &&
            nativeRegistrationIsExact(create) &&
            runCatching { Json.parseToJsonElement(mixins) }.isSuccess &&
            countToken(mixins, "\"ClientboundMapItemDataPacketMixin\"") == 0 &&
            countToken(mixins, "\"client.MapRendererMapInstanceMixin\"") == 0 &&
            asset.isRegularFile() &&
            sha256(asset.readBytes()) == LEGACY_ASSET_SHA256
    }

    private fun closeLegacySourceGraph(projectDir: Path) {
        val allowed = mapOf(
            "StationMarker.Decoration" to setOf(
                STATION_MARKER,
                MAP_DATA_MIXIN,
                PACKET_MIXIN
            ),
            "StationMarker.TYPE" to emptySet(),
            "StationMarker.*" to emptySet(),
            "CustomRenderedMapDecoration" to setOf(
                STATION_MARKER,
                PACKET_MIXIN,
                RENDER_MIXIN,
                CUSTOM_RENDERER
            ),
            "station_map_icon.png" to setOf(STATION_MARKER),
            "ClientboundMapItemDataPacketMixin" to setOf(PACKET_MIXIN, MIXIN_CONFIG),
            "MapRendererMapInstanceMixin" to setOf(RENDER_MIXIN, MIXIN_CONFIG),
            "create\$stationIndices" to setOf(PACKET_MIXIN)
        )
        sourceTextFiles(projectDir).forEach { path ->
            val relative = projectDir.relativize(path)
                .toString()
                .replace('\\', '/')
            val text = Files.readString(path)
            val code = if (path.fileName.toString().substringAfterLast('.', "") in
                setOf("java", "kt", "kts", "groovy", "scala")
            ) {
                maskCommentsAndLiterals(text)
            } else {
                text
            }
            val literalTokens = if (path.fileName.toString()
                    .substringAfterLast('.', "") in
                setOf("java", "kt", "kts", "groovy", "scala")
            ) {
                javaTokens(text).filter {
                    it.startsWith('"') || it.startsWith('\'')
                }.joinToString("\n")
            } else {
                text
            }
            allowed.forEach { (token, pathsAllowed) ->
                val searchable = when (token) {
                    "station_map_icon.png",
                    "ClientboundMapItemDataPacketMixin",
                    "MapRendererMapInstanceMixin" -> text
                    else -> code
                }
                if (token in searchable && relative !in pathsAllowed) {
                    abort(
                        "Unknown Create station-map family consumer for " +
                            "$token at $relative"
                    )
                }
            }
            if (relative !in setOf(STATION_MARKER, MAP_DATA_MIXIN, PACKET_MIXIN) &&
                listOf(
                    "StationMarker.Decoration",
                    "StationMarker\$Decoration",
                    "com.simibubi.create.content.trains.station.StationMarker.Decoration",
                    "com.simibubi.create.content.trains.station.StationMarker\$Decoration"
                ).any { it in literalTokens }
            ) {
                abort(
                    "Unknown reflective or serialized StationMarker decoration consumer at " +
                        relative
                )
            }
            if (relative !in setOf(STATION_MARKER, MAP_DATA_MIXIN, PACKET_MIXIN) &&
                Regex(
                    """(?m)^\s*import\s+(?:static\s+)?""" +
                        """com\.simibubi\.create\.content\.trains\.station\.""" +
                        """StationMarker\.(?:Decoration|TYPE|\*)\s*;"""
                ).containsMatchIn(code)
            ) {
                abort(
                    "Unknown aliased or static StationMarker decoration import at $relative"
                )
            }
            if (relative !in setOf(
                    STATION_MARKER,
                    PACKET_MIXIN,
                    RENDER_MIXIN,
                    CUSTOM_RENDERER
                ) &&
                Regex(
                    """(?m)^\s*import\s+(?:static\s+)?""" +
                        """com\.simibubi\.create\.foundation\.map\.""" +
                        """CustomRenderedMapDecoration(?:\.\*)?\s*;"""
                ).containsMatchIn(code)
            ) {
                abort(
                    "Unknown aliased or static CustomRenderedMapDecoration import at " +
                        relative
                )
            }
        }
    }

    private fun sourceTextFiles(projectDir: Path): List<Path> {
        val sourceRoot = projectDir.resolve("src")
        if (!sourceRoot.exists()) return emptyList()
        val textExtensions = setOf(
            "java",
            "kt",
            "kts",
            "groovy",
            "scala",
            "json",
            "toml",
            "properties",
            "mcmeta",
            "cfg",
            "txt",
            "accesswidener"
        )
        return Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .filter {
                    it.fileName.toString().substringAfterLast('.', "") in textExtensions
                }
                .toList()
        }
    }

    private fun admitLegacySemantics(
        station: String,
        mapData: String,
        packet: String,
        renderMixin: String,
        customRenderer: String,
        mixins: String
    ) {
        requireJavaPackage(
            station,
            "com.simibubi.create.content.trains.station",
            "StationMarker"
        )
        requireJavaPackage(
            mapData,
            "com.simibubi.create.foundation.mixin",
            "MapItemSavedDataMixin"
        )
        requireContains(station, "id = \"create:station-")
        requireExactFragment(
            station,
            Regex(
                """public\s+static\s+final\s+MapDecoration\.Type\s+TYPE\s*=\s*""" +
                    """MapDecoration\.Type\.RED_MARKER\s*;"""
            ),
            includeBody = false,
            annotationPrefix = null,
            expectedSha256 = LEGACY_TYPE_FIELD_SHA256,
            label = "StationMarker RED_MARKER type field"
        )
        requireExactFragment(
            station,
            Regex("""\bpublic\s+static\s+class\s+Decoration\b[^{]*\{"""),
            includeBody = true,
            annotationPrefix = null,
            expectedSha256 = LEGACY_DECORATION_DECLARATION_SHA256,
            label = "StationMarker.Decoration declaration",
            admittedPhaseSha256 = setOf(TEXT_AST_DECORATION_DECLARATION_SHA256)
        )
        requireExactCompilationBody(
            packet,
            Regex("""\bpublic\s+class\s+ClientboundMapItemDataPacketMixin\b[^{]*\{"""),
            annotationPrefix = "@Mixin",
            expectedDeclarationSha256 = LEGACY_PACKET_DECLARATION_SHA256,
            expectedImportsSha256 = LEGACY_PACKET_IMPORTS_SHA256,
            expectedPackage = "com.simibubi.create.foundation.mixin",
            label = "ClientboundMapItemDataPacketMixin"
        )
        requireExactCompilationBody(
            renderMixin,
            Regex("""\bpublic\s+class\s+MapRendererMapInstanceMixin\b[^{]*\{"""),
            annotationPrefix = "@Mixin",
            expectedDeclarationSha256 = LEGACY_RENDER_DECLARATION_SHA256,
            expectedImportsSha256 = LEGACY_RENDER_IMPORTS_SHA256,
            expectedPackage = "com.simibubi.create.foundation.mixin.client",
            label = "MapRendererMapInstanceMixin"
        )
        requireExactCompilationBody(
            customRenderer,
            Regex("""\bpublic\s+interface\s+CustomRenderedMapDecoration\b[^{]*\{"""),
            annotationPrefix = null,
            expectedDeclarationSha256 = LEGACY_CUSTOM_RENDERER_DECLARATION_SHA256,
            expectedImportsSha256 = LEGACY_CUSTOM_RENDERER_IMPORTS_SHA256,
            expectedPackage = "com.simibubi.create.foundation.map",
            label = "CustomRenderedMapDecoration"
        )

        val producer = Regex(
            """new\s+StationMarker\.Decoration\s*\(\s*localXByte\s*,\s*""" +
                """localZByte\s*,\s*marker\.getName\(\)\s*\)"""
        )
        if (producer.findAll(maskCommentsAndLiterals(mapData)).count() != 1 ||
            countExecutableToken(mapData, "StationMarker.Decoration") != 1
        ) {
            abort(
                "MapItemSavedDataMixin must own exactly one admitted station producer"
            )
        }
        if (countToken(mixins, "\"ClientboundMapItemDataPacketMixin\"") != 1 ||
            countToken(mixins, "\"client.MapRendererMapInstanceMixin\"") != 1
        ) {
            abort("create.mixins.json does not own exactly one legacy station packet/render pair")
        }
        runCatching { Json.parseToJsonElement(mixins) }
            .getOrElse { abort("create.mixins.json is invalid before migration: ${it.message}") }
    }

    private fun migrateStationMarker(source: String): String {
        var result = source
        val typeField = Regex(
            """(?m)^[ \t]*public\s+static\s+final\s+""" +
                """(?:MapDecoration\.Type|Holder<MapDecorationType>)\s+TYPE\s*=\s*""" +
                """(?:MapDecoration\.Type|MapDecorationTypes)\.RED_MARKER\s*;\s*\r?\n"""
        )
        if (typeField.findAll(result).count() != 1) {
            abort("StationMarker must own exactly one admitted RED_MARKER TYPE field")
        }
        result = typeField.replaceFirst(result, "")

        val executable = maskCommentsAndLiterals(result)
        val declaration = Regex(
            """\bpublic\s+static\s+class\s+Decoration\b[^{]*\{"""
        ).find(executable) ?: abort("StationMarker.Decoration declaration is missing")
        val open = executable.indexOf('{', declaration.range.first)
        val close = matchingBrace(executable, open)
        if (close < 0) abort("StationMarker.Decoration body is unbalanced")
        var start = declaration.range.first
        while (start > 0 && result[start - 1] in charArrayOf(' ', '\t')) start--
        if (start > 0 && result[start - 1] == '\n') start--
        result = result.removeRange(start, close + 1)

        val ownerClose = maskCommentsAndLiterals(result).lastIndexOf('}')
        if (ownerClose < 0) abort("StationMarker owner body is unbalanced")
        val newline = if (result.contains("\r\n")) "\r\n" else "\n"
        val factory = """
	public static MapDecoration createStationDecoration(
			byte x, byte y, Optional<Component> name) {
		return new MapDecoration(
			AllMapDecorationTypes.STATION_MAP_DECORATION,
			x,
			y,
			(byte) 0,
			name);
	}
""".trimEnd().replace("\n", newline)
        result = result.substring(0, ownerClose).trimEnd() +
            newline + newline + factory + newline +
            result.substring(ownerClose)

        listOf(
            "org.joml.Matrix4f",
            "com.mojang.blaze3d.vertex.PoseStack",
            "com.mojang.blaze3d.vertex.VertexConsumer",
            "com.simibubi.create.Create",
            "com.simibubi.create.foundation.map.CustomRenderedMapDecoration",
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.gui.Font",
            "net.minecraft.client.renderer.MultiBufferSource",
            "net.minecraft.client.renderer.RenderType",
            "net.minecraft.resources.ResourceLocation",
            "net.minecraft.world.level.saveddata.maps.MapItemSavedData",
            "net.minecraft.world.level.saveddata.maps.MapDecorationTypes",
            "net.minecraft.world.level.saveddata.maps.MapDecorationType",
            "net.minecraft.core.Holder",
            "net.minecraft.core.registries.BuiltInRegistries"
        ).forEach { result = removeImport(result, it) }
        result = addImport(result, "com.simibubi.create.AllMapDecorationTypes")
        result = addImport(result, "net.minecraft.world.level.saveddata.maps.MapDecoration")
        result = addImport(result, "java.util.Optional")
        if (result.contains("class Decoration") ||
            result.contains("CustomRenderedMapDecoration") ||
            result.contains("station_map_icon.png")
        ) {
            abort("StationMarker legacy renderer state survived native migration")
        }
        return result
    }

    private fun migrateMapDataProducer(source: String): String {
        val creation = Regex(
            """new\s+StationMarker\.Decoration\s*\(\s*localXByte\s*,\s*""" +
                """localZByte\s*,\s*marker\.getName\(\)\s*\)""" +
                """(?:\s*\.asMapDecoration\s*\(\s*\))?"""
        )
        if (creation.findAll(source).count() != 1) {
            abort("MapItemSavedDataMixin must own exactly one station decoration producer")
        }
        var result = creation.replaceFirst(
            source,
            "StationMarker.createStationDecoration(" +
                "localXByte, localZByte, Optional.of(marker.getName()))"
        )
        result = addImport(result, "java.util.Optional")
        return result
    }

    private fun registerDecorationTypes(source: String): String {
        requireJavaPackage(source, "com.simibubi.create", "Create")
        val existing = countExecutableToken(
            source,
            "AllMapDecorationTypes.register(modEventBus);"
        )
        if (existing == 1) return source
        if (existing > 1) {
            abort("Create owns multiple station map decoration registrations")
        }
        val executable = maskCommentsAndLiterals(source)
        val constructor = Regex(
            """\bpublic\s+Create\s*\([^)]*\bIEventBus\s+modEventBus\b[^)]*\)\s*\{"""
        ).findAll(executable).toList()
        if (constructor.size != 1) {
            abort("Create must own one constructor carrying IEventBus modEventBus")
        }
        val open = executable.indexOf('{', constructor.single().range.first)
        if (open < 0) abort("Create constructor body is unbalanced")
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val indent = source.lineIndentAt(constructor.single().range.first) + "    "
        return source.substring(0, open + 1) +
            newline + indent + "AllMapDecorationTypes.register(modEventBus);" +
            source.substring(open + 1)
    }

    private fun removeObsoleteMixins(source: String): String {
        var result = source
        listOf(
            "ClientboundMapItemDataPacketMixin",
            "client.MapRendererMapInstanceMixin"
        ).forEach { entry ->
            val pattern = Regex(
                """(?m)^[ \t]*"${Regex.escape(entry)}"\s*,?\s*\r?\n"""
            )
            if (pattern.findAll(result).count() != 1) {
                abort("create.mixins.json must contain exactly one $entry entry")
            }
            result = pattern.replaceFirst(result, "")
        }
        runCatching { Json.parseToJsonElement(result) }
            .getOrElse { abort("create.mixins.json became invalid: ${it.message}") }
        return result
    }

    private fun requireGeneratedOwnership(
        path: Path,
        expected: String,
        className: String
    ) {
        if (path.exists() && !sameJava(path.readText(), expected)) {
            abort("Generated $className path is owned by incompatible source: $path")
        }
    }

    private fun requireGeneratedFqnOwnership(projectDir: Path) {
        val declarations = declaredGeneratedFqns(projectDir)
        GENERATED_TYPES.forEach { (fqn, expectedRelative) ->
            val owners = declarations[fqn].orEmpty()
            if (owners.size > 1) {
                abort(
                    "Generated type $fqn is declared by multiple source files: " +
                        owners.sorted().joinToString()
                )
            }
            if (owners.singleOrNull()?.let { it != expectedRelative } == true) {
                abort(
                    "Generated type $fqn is already owned by " +
                        owners.single() + " instead of $expectedRelative"
                )
            }
        }
    }

    private fun generatedFqnsAreUnique(projectDir: Path): Boolean {
        val declarations = declaredGeneratedFqns(projectDir)
        return GENERATED_TYPES.all { (fqn, expectedRelative) ->
            declarations[fqn].orEmpty() == listOf(expectedRelative)
        }
    }

    private fun declaredGeneratedFqns(projectDir: Path): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        sourceTextFiles(projectDir)
            .filter { it.fileName.toString().endsWith(".java") }
            .forEach { path ->
                val source = Files.readString(path)
                val executable = maskCommentsAndLiterals(source)
                val packageName = Regex(
                    """(?m)^\s*package\s+([\w.]+)\s*;"""
                ).find(executable)?.groupValues?.get(1) ?: return@forEach
                val relative = projectDir.relativize(path)
                    .toString()
                    .replace('\\', '/')
                GENERATED_TYPES.keys
                    .filter { it.substringBeforeLast('.') == packageName }
                    .forEach { fqn ->
                        val simpleName = fqn.substringAfterLast('.')
                        val declarations = Regex(
                            """\b(?:class|interface|enum|record)\s+""" +
                                Regex.escape(simpleName) + """\b"""
                        ).findAll(executable)
                            .filter { braceDepthAt(executable, it.range.first) == 0 }
                            .toList()
                        repeat(declarations.size) {
                            result.getOrPut(fqn) { mutableListOf() } += relative
                        }
                    }
            }
        return result
    }

    private fun braceDepthAt(source: String, offset: Int): Int {
        var depth = 0
        for (index in 0 until offset.coerceAtMost(source.length)) {
            when (source[index]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth
    }

    private fun nativeStationFactoryIsExact(source: String): Boolean {
        if (countExecutableToken(source, "class Decoration") != 0 ||
            countExecutableToken(source, "CustomRenderedMapDecoration") != 0 ||
            countToken(source, "station_map_icon.png") != 0
        ) {
            return false
        }
        val factory = Regex(
            """public\s+static\s+MapDecoration\s+createStationDecoration\s*\(\s*""" +
                """byte\s+x\s*,\s*byte\s+y\s*,\s*Optional<Component>\s+name\s*\)""" +
                """\s*\{\s*return\s+new\s+MapDecoration\s*\(\s*""" +
                """AllMapDecorationTypes\.STATION_MAP_DECORATION\s*,\s*x\s*,\s*y\s*,""" +
                """\s*\(byte\)\s*0\s*,\s*name\s*\)\s*;\s*\}"""
        )
        return factory.findAll(maskCommentsAndLiterals(source)).count() == 1
    }

    private fun nativeMapDataProducerIsExact(source: String): Boolean {
        val producer = Regex(
            """StationMarker\.createStationDecoration\s*\(\s*localXByte\s*,\s*""" +
                """localZByte\s*,\s*Optional\.of\s*\(\s*marker\.getName\(\)\s*\)""" +
                """\s*\)"""
        )
        return producer.findAll(maskCommentsAndLiterals(source)).count() == 1 &&
            countExecutableToken(source, "StationMarker.Decoration") == 0
    }

    private fun nativeRegistrationIsExact(source: String): Boolean {
        if (countExecutableToken(
                source,
                "AllMapDecorationTypes.register(modEventBus);"
            ) != 1
        ) {
            return false
        }
        val executable = maskCommentsAndLiterals(source)
        val constructor = Regex(
            """\bpublic\s+Create\s*\([^)]*\bIEventBus\s+modEventBus\b[^)]*\)\s*\{"""
        ).findAll(executable).toList()
        if (constructor.size != 1) return false
        val open = executable.indexOf('{', constructor.single().range.first)
        val close = matchingBrace(executable, open)
        if (open < 0 || close < 0) return false
        return countExecutableToken(
            source.substring(open + 1, close),
            "AllMapDecorationTypes.register(modEventBus);"
        ) == 1
    }

    private fun targetContractIsVerified(): Boolean {
        val mapDecorationType =
            "net.minecraft.world.level.saveddata.maps.MapDecorationType"
        val mapDecoration =
            "net.minecraft.world.level.saveddata.maps.MapDecoration"
        val registerEvent =
            "net.neoforged.neoforge.client.gui.map.RegisterMapDecorationRenderersEvent"
        val renderer =
            "net.neoforged.neoforge.client.gui.map.IMapDecorationRenderer"
        val deferredRegister =
            "net.neoforged.neoforge.registries.DeferredRegister"
        val holder = "net.minecraft.core.Holder"
        val mapTextures =
            "net.minecraft.client.resources.MapDecorationTextureManager"
        val sprite =
            "net.minecraft.client.renderer.texture.TextureAtlasSprite"
        val vertexConsumer = "com.mojang.blaze3d.vertex.VertexConsumer"
        val poseStack = "com.mojang.blaze3d.vertex.PoseStack"
        val pose = "com.mojang.blaze3d.vertex.PoseStack\$Pose"
        val buffers = "net.minecraft.client.renderer.MultiBufferSource"
        val font = "net.minecraft.client.gui.Font"
        val minecraft = "net.minecraft.client.Minecraft"
        val renderType = "net.minecraft.client.renderer.RenderType"
        fun public(owner: String, name: String, descriptor: String): Boolean =
            TargetMembers.inheritedMethod(owner, name, descriptor)?.access == "public"
        return listOf(
            mapDecorationType,
            mapDecoration,
            registerEvent,
            renderer,
            deferredRegister,
            holder,
            mapTextures,
            sprite,
            vertexConsumer,
            poseStack,
            pose,
            buffers,
            font,
            minecraft,
            renderType
        ).all(TargetMembers::hasClass) &&
            TargetMembers.inheritedMethod(
            mapDecorationType,
            "<init>",
            "(Lnet/minecraft/resources/ResourceLocation;ZIZZ)V"
        )?.access == "public" &&
            TargetMembers.inheritedMethod(
                mapDecoration,
                "<init>",
                "(Lnet/minecraft/core/Holder;BBBLjava/util/Optional;)V"
            )?.access == "public" &&
            TargetMembers.fieldDescriptor(mapDecorationType, "STREAM_CODEC") ==
                "Lnet/minecraft/network/codec/StreamCodec;" &&
            TargetMembers.fieldDescriptor(mapDecoration, "STREAM_CODEC") ==
                "Lnet/minecraft/network/codec/StreamCodec;" &&
            TargetMembers.inheritedMethod(
                registerEvent,
                "register",
                "(Lnet/minecraft/world/level/saveddata/maps/MapDecorationType;" +
                    "Lnet/neoforged/neoforge/client/gui/map/IMapDecorationRenderer;)V"
            )?.access == "public" &&
            TargetMembers.inheritedMethod(
                renderer,
                "render",
                "(Lnet/minecraft/world/level/saveddata/maps/MapDecoration;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                    "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;" +
                    "Lnet/minecraft/client/resources/MapDecorationTextureManager;ZII)Z"
            )?.access == "public" &&
            TargetMembers.inheritedMethod(
                deferredRegister,
                "register",
                "(Lnet/neoforged/bus/api/IEventBus;)V"
            )?.access == "public" &&
            public(
                deferredRegister,
                "create",
                "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/String;)" +
                    "Lnet/neoforged/neoforge/registries/DeferredRegister;"
            ) &&
            public(
                deferredRegister,
                "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)" +
                    "Lnet/neoforged/neoforge/registries/DeferredHolder;"
            ) &&
            TargetMembers.inheritsFrom(
                "net.neoforged.neoforge.registries.DeferredHolder",
                holder
            ) &&
            public(holder, "value", "()Ljava/lang/Object;") &&
            TargetMembers.fieldDescriptor(
                "net.minecraft.core.registries.Registries",
                "MAP_DECORATION_TYPE"
            ) == "Lnet/minecraft/resources/ResourceKey;" &&
            public(mapDecoration, "x", "()B") &&
            public(mapDecoration, "y", "()B") &&
            public(mapDecoration, "name", "()Ljava/util/Optional;") &&
            public(
                mapTextures,
                "get",
                "(Lnet/minecraft/world/level/saveddata/maps/MapDecoration;)" +
                    "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"
            ) &&
            listOf("getU0", "getU1", "getV0", "getV1")
                .all { public(sprite, it, "()F") } &&
            public(
                sprite,
                "atlasLocation",
                "()Lnet/minecraft/resources/ResourceLocation;"
            ) &&
            public(
                vertexConsumer,
                "addVertex",
                "(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ) &&
            public(
                vertexConsumer,
                "setColor",
                "(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ) &&
            public(
                vertexConsumer,
                "setUv",
                "(FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ) &&
            public(
                vertexConsumer,
                "setLight",
                "(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ) &&
            public(poseStack, "pushPose", "()V") &&
            public(poseStack, "popPose", "()V") &&
            public(poseStack, "translate", "(DDD)V") &&
            public(poseStack, "scale", "(FFF)V") &&
            public(
                poseStack,
                "last",
                "()Lcom/mojang/blaze3d/vertex/PoseStack\$Pose;"
            ) &&
            public(pose, "pose", "()Lorg/joml/Matrix4f;") &&
            public(
                buffers,
                "getBuffer",
                "(Lnet/minecraft/client/renderer/RenderType;)" +
                    "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            ) &&
            public(minecraft, "getInstance", "()Lnet/minecraft/client/Minecraft;") &&
            TargetMembers.fieldDescriptor(minecraft, "font") ==
                "Lnet/minecraft/client/gui/Font;" &&
            TargetMembers.fieldDescriptor(
                "net.minecraft.client.gui.Font\$DisplayMode",
                "NORMAL"
            ) == "Lnet/minecraft/client/gui/Font\$DisplayMode;" &&
            public(font, "width", "(Lnet/minecraft/network/chat/FormattedText;)I") &&
            public(
                font,
                "drawInBatch",
                "(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;" +
                    "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                    "Lnet/minecraft/client/gui/Font\$DisplayMode;II)I"
            ) &&
            public(
                renderType,
                "text",
                "(Lnet/minecraft/resources/ResourceLocation;)" +
                    "Lnet/minecraft/client/renderer/RenderType;"
            )
    }

    private fun commitFamily(
        mutations: List<Mutation>,
        commitProbe: ((Int, Path) -> Unit)?
    ) {
        if (mutations.isEmpty()) return
        val staged = linkedMapOf<Mutation, Path>()
        val committed = mutableListOf<Mutation>()
        try {
            mutations.filter { it.after != null }.forEach { mutation ->
                mutation.path.parent.createDirectories()
                val temporary = Files.createTempFile(
                    mutation.path.parent,
                    ".modporter-station-map-",
                    ".tmp"
                )
                Files.write(temporary, mutation.after)
                staged[mutation] = temporary
            }
            mutations.forEachIndexed { index, mutation ->
                commitProbe?.invoke(index, mutation.path)
                val after = mutation.after
                if (after == null) {
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
                    val before = mutation.before
                    if (before == null) {
                        Files.deleteIfExists(mutation.path)
                    } else {
                        mutation.path.parent.createDirectories()
                        val rollback = Files.createTempFile(
                            mutation.path.parent,
                            ".modporter-station-map-rollback-",
                            ".tmp"
                        )
                        Files.write(rollback, before)
                        Files.move(
                            rollback,
                            mutation.path,
                            ATOMIC_MOVE,
                            REPLACE_EXISTING
                        )
                    }
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            staged.values.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun renderDecorationTypes(): String = """
package com.simibubi.create;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class AllMapDecorationTypes {
    private static final DeferredRegister<MapDecorationType> DECORATION_TYPES =
        DeferredRegister.create(Registries.MAP_DECORATION_TYPE, Create.ID);

    public static final Holder<MapDecorationType> STATION_MAP_DECORATION =
        DECORATION_TYPES.register(
            "station",
            () -> new MapDecorationType(
                Create.asResource("station"),
                true,
                -1,
                false,
                true));

    private AllMapDecorationTypes() {
    }

    public static void register(IEventBus modEventBus) {
        DECORATION_TYPES.register(modEventBus);
    }
}
""".trimIndent() + "\n"

    private fun renderNativeEvents(): String = """
package com.simibubi.create.foundation.map;

import com.simibubi.create.AllMapDecorationTypes;
import com.simibubi.create.Create;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.gui.map.RegisterMapDecorationRenderersEvent;

@EventBusSubscriber(value = Dist.CLIENT, modid = Create.ID)
public final class StationMapDecorationEvents {
    private StationMapDecorationEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(
            RegisterMapDecorationRenderersEvent event) {
        event.register(
            AllMapDecorationTypes.STATION_MAP_DECORATION.value(),
            new StationMapDecorationRenderer());
    }
}
""".trimIndent() + "\n"

    private fun renderNativeRenderer(): String = """
package com.simibubi.create.foundation.map;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.MapDecorationTextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.client.gui.map.IMapDecorationRenderer;

public final class StationMapDecorationRenderer
        implements IMapDecorationRenderer {
    @Override
    public boolean render(
            MapDecoration decoration,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            MapItemSavedData mapData,
            MapDecorationTextureManager decorationTextures,
            boolean inItemFrame,
            int packedLight,
            int index) {
        poseStack.pushPose();
        poseStack.translate(
            decoration.x() / 2D + 64.0,
            decoration.y() / 2D + 64.0,
            -0.02D);
        poseStack.pushPose();
        poseStack.translate(0.5f, 0f, 0);
        poseStack.scale(4.5F, 4.5F, 3.0F);

        TextureAtlasSprite sprite = decorationTextures.get(decoration);
        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float u1 = sprite.getU1();
        float v1 = sprite.getV1();
        VertexConsumer buffer =
            bufferSource.getBuffer(RenderType.text(sprite.atlasLocation()));
        Matrix4f matrix = poseStack.last().pose();
        float zOffset = -0.001f;
        buffer.addVertex(matrix, -1.0F, 1.0F, index * zOffset)
            .setColor(-1).setUv(u0, v0).setLight(packedLight);
        buffer.addVertex(matrix, 1.0F, 1.0F, index * zOffset)
            .setColor(-1).setUv(u1, v0).setLight(packedLight);
        buffer.addVertex(matrix, 1.0F, -1.0F, index * zOffset)
            .setColor(-1).setUv(u1, v1).setLight(packedLight);
        buffer.addVertex(matrix, -1.0F, -1.0F, index * zOffset)
            .setColor(-1).setUv(u0, v1).setLight(packedLight);
        poseStack.popPose();

        if (decoration.name().isPresent()) {
            Font font = Minecraft.getInstance().font;
            Component component = decoration.name().get();
            float width = font.width(component);
            poseStack.pushPose();
            poseStack.translate(0, 6.0D, -0.005F);
            poseStack.scale(0.8f, 0.8f, 1.0F);
            poseStack.translate(-width / 2f + .5f, 0, 0);
            font.drawInBatch(
                component,
                0.0F,
                0.0F,
                -1,
                false,
                poseStack.last().pose(),
                bufferSource,
                Font.DisplayMode.NORMAL,
                Integer.MIN_VALUE,
                packedLight);
            poseStack.popPose();
        }

        poseStack.popPose();
        return true;
    }
}
""".trimIndent() + "\n"

    private fun addImport(source: String, importName: String): String {
        if (Regex(
                """(?m)^\s*import\s+${Regex.escape(importName)}\s*;"""
            ).containsMatchIn(source)
        ) {
            return source
        }
        val packageLine = Regex("""(?m)^\s*package\s+[\w.]+\s*;\s*$""")
            .find(source) ?: abort("Cannot add import $importName without a package")
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        return source.substring(0, packageLine.range.last + 1) +
            newline + newline + "import $importName;" +
            source.substring(packageLine.range.last + 1)
                .removePrefix(newline)
    }

    private fun removeImport(source: String, importName: String): String =
        Regex(
            """(?m)^[ \t]*import\s+${Regex.escape(importName)}\s*;\s*\r?\n"""
        ).replace(source, "")

    private fun requireExactCompilationBody(
        source: String,
        declaration: Regex,
        annotationPrefix: String?,
        expectedDeclarationSha256: String,
        expectedImportsSha256: String,
        expectedPackage: String,
        label: String
    ) {
        requireExactFragment(
            source,
            declaration,
            includeBody = true,
            annotationPrefix = annotationPrefix,
            expectedSha256 = expectedDeclarationSha256,
            label = "$label declaration"
        )
        requireJavaPackage(source, expectedPackage, label)
        val imports = Regex(
            """(?m)^[ \t]*import\s+((?:static\s+)?[^;]+)\s*;\s*$"""
        ).findAll(source)
            .map { it.groupValues[1].trim() }
            .sorted()
            .joinToString("\n")
        if (sha256(imports.toByteArray(StandardCharsets.UTF_8)) !=
            expectedImportsSha256
        ) {
            abort("$label imports or static imports changed from the admitted source")
        }
        val compilationBody = source
            .replace(Regex("""(?m)^[ \t]*package\s+[\w.]+\s*;\s*$"""), "")
            .replace(
                Regex("""(?m)^[ \t]*import\s+(?:static\s+)?[^;]+\s*;\s*$"""),
                ""
            )
        if (sha256(
                normalizeJava(compilationBody).toByteArray(StandardCharsets.UTF_8)
            ) != expectedDeclarationSha256
        ) {
            abort(
                "$label contains an unadmitted top-level declaration, annotation, " +
                    "field, method, or initializer"
            )
        }
    }

    private fun requireJavaPackage(
        source: String,
        expectedPackage: String,
        label: String
    ) {
        val packages = Regex(
            """(?m)^[ \t]*package\s+([\w.]+)\s*;\s*$"""
        ).findAll(source).map { it.groupValues[1] }.toList()
        if (packages != listOf(expectedPackage)) {
            abort(
                "$label package changed from $expectedPackage: " +
                    packages.joinToString().ifEmpty { "(missing)" }
            )
        }
    }

    private fun requireExactFragment(
        source: String,
        declaration: Regex,
        includeBody: Boolean,
        annotationPrefix: String?,
        expectedSha256: String,
        label: String,
        admittedPhaseSha256: Set<String> = emptySet()
    ) {
        val executable = maskCommentsAndLiterals(source)
        val matches = declaration.findAll(executable).toList()
        if (matches.size != 1) {
            abort("$label must occur exactly once; found ${matches.size}")
        }
        val match = matches.single()
        val start = annotationPrefix?.let { prefix ->
            executable.lastIndexOf(prefix, match.range.first)
                .takeIf { it >= 0 }
                ?: abort("$label is missing admitted annotation $prefix")
        } ?: match.range.first
        val endExclusive = if (includeBody) {
            val open = executable.indexOf('{', match.range.first)
            val close = matchingBrace(executable, open)
            if (open < 0 || close < 0) abort("$label body is unbalanced")
            close + 1
        } else {
            match.range.last + 1
        }
        val fingerprint = sha256(
            normalizeJava(source.substring(start, endExclusive))
                .toByteArray(StandardCharsets.UTF_8)
        )
        if (fingerprint != expectedSha256 && fingerprint !in admittedPhaseSha256) {
            abort(
                "$label changed from the exact admitted source shape; " +
                    "expected $expectedSha256 but found $fingerprint"
            )
        }
    }

    private fun matchingBrace(source: String, open: Int): Int {
        if (open !in source.indices || source[open] != '{') return -1
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun maskCommentsAndLiterals(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        var state = 0
        while (index < chars.size) {
            val next = chars.getOrNull(index + 1)
            when (state) {
                0 -> when {
                    chars[index] == '/' && next == '/' -> {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                        state = 1
                        continue
                    }
                    chars[index] == '/' && next == '*' -> {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                        state = 2
                        continue
                    }
                    chars[index] == '"' -> {
                        chars[index] = ' '
                        state = 3
                    }
                    chars[index] == '\'' -> {
                        chars[index] = ' '
                        state = 4
                    }
                }
                1 -> if (chars[index] == '\n') {
                    state = 0
                } else {
                    chars[index] = ' '
                }
                2 -> if (chars[index] == '*' && next == '/') {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    state = 0
                    continue
                } else if (chars[index] !in charArrayOf('\r', '\n')) {
                    chars[index] = ' '
                }
                3, 4 -> {
                    val quote = if (state == 3) '"' else '\''
                    if (chars[index] == '\\') {
                        chars[index] = ' '
                        if (index + 1 < chars.size) {
                            chars[index + 1] = ' '
                            index += 2
                            continue
                        }
                    } else if (chars[index] == quote) {
                        chars[index] = ' '
                        state = 0
                    } else if (chars[index] !in charArrayOf('\r', '\n')) {
                        chars[index] = ' '
                    }
                }
            }
            index++
        }
        return String(chars)
    }

    private val javaOperators = listOf(
        ">>>=",
        "<<=",
        ">>=",
        "...",
        "::",
        "->",
        "==",
        "!=",
        "<=",
        ">=",
        "&&",
        "||",
        "++",
        "--",
        "+=",
        "-=",
        "*=",
        "/=",
        "%=",
        "&=",
        "|=",
        "^=",
        ">>>",
        "<<",
        ">>"
    )

    /**
     * Ignores formatting and comments while preserving token boundaries and the exact contents of
     * string, text-block, and character literals. In particular, "a b" and "ab" remain different.
     */
    private fun normalizeJava(source: String): String =
        javaTokens(source).joinToString("\u0000")

    private fun javaTokens(source: String): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            val current = source[index]
            when {
                current.isWhitespace() -> index++
                source.startsWith("//", index) -> {
                    index = source.indexOf('\n', index + 2)
                        .let { if (it < 0) source.length else it + 1 }
                }
                source.startsWith("/*", index) -> {
                    index = source.indexOf("*/", index + 2)
                        .let { if (it < 0) source.length else it + 2 }
                }
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    val exclusive = if (end < 0) source.length else end + 3
                    tokens += source.substring(index, exclusive)
                    index = exclusive
                }
                current == '"' || current == '\'' -> {
                    val quote = current
                    var cursor = index + 1
                    while (cursor < source.length) {
                        when {
                            source[cursor] == '\\' -> cursor += 2
                            source[cursor] == quote -> {
                                cursor++
                                break
                            }
                            else -> cursor++
                        }
                    }
                    val exclusive = cursor.coerceAtMost(source.length)
                    tokens += source.substring(index, exclusive)
                    index = exclusive
                }
                Character.isJavaIdentifierStart(current) -> {
                    var cursor = index + 1
                    while (cursor < source.length &&
                        Character.isJavaIdentifierPart(source[cursor])
                    ) {
                        cursor++
                    }
                    tokens += source.substring(index, cursor)
                    index = cursor
                }
                current.isDigit() -> {
                    var cursor = index + 1
                    while (cursor < source.length) {
                        val candidate = source[cursor]
                        when {
                            candidate.isLetterOrDigit() ||
                                candidate == '_' ||
                                candidate == '.' -> cursor++
                            candidate in charArrayOf('+', '-') &&
                                source[cursor - 1] in charArrayOf('e', 'E', 'p', 'P') ->
                                cursor++
                            else -> break
                        }
                    }
                    tokens += source.substring(index, cursor)
                    index = cursor
                }
                else -> {
                    val operator = javaOperators.firstOrNull {
                        source.startsWith(it, index)
                    }
                    if (operator != null) {
                        tokens += operator
                        index += operator.length
                    } else {
                        tokens += current.toString()
                        index++
                    }
                }
            }
        }
        return tokens
    }

    private fun sameJava(first: String, second: String): Boolean =
        normalizeJava(first) == normalizeJava(second)

    private fun countToken(source: String, token: String): Int =
        Regex(Regex.escape(token)).findAll(source).count()

    private fun countExecutableToken(source: String, token: String): Int {
        val normalizedSource = normalizeJava(source)
        val normalizedToken = normalizeJava(token)
        return Regex(Regex.escape(normalizedToken)).findAll(normalizedSource).count()
    }

    private fun requireContains(source: String, token: String) {
        if (!source.contains(token)) {
            abort("Admitted Create station-map source no longer contains $token")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun String.lineIndentAt(offset: Int): String {
        val start = lastIndexOf('\n', offset.coerceAtMost(length) - 1)
            .let { if (it < 0) 0 else it + 1 }
        return substring(start, offset).takeWhile { it == ' ' || it == '\t' }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private data class Mutation(
        val path: Path,
        val before: ByteArray?,
        val after: ByteArray?,
        val description: String,
        val text: Boolean
    ) {
        fun preview(bytes: ByteArray?): String = when {
            bytes == null -> "(missing file)"
            text -> bytes.toString(StandardCharsets.UTF_8).take(180)
            else ->
                "sha256=${CreateStationMapDecorationMigration.sha256(bytes)} " +
                    "bytes=${bytes.size}"
        }

        companion object {
            fun text(
                path: Path,
                before: String?,
                after: String,
                description: String
            ) = Mutation(
                path,
                before?.toByteArray(StandardCharsets.UTF_8),
                after.toByteArray(StandardCharsets.UTF_8),
                description,
                true
            )

            fun binary(
                path: Path,
                before: ByteArray?,
                after: ByteArray,
                description: String
            ) = Mutation(path, before, after, description, false)

            fun delete(path: Path, description: String): Mutation {
                val before = if (path.isRegularFile()) path.readBytes() else {
                    CreateStationMapDecorationMigration.abort(
                        "Cannot delete missing admitted family member: $path"
                    )
                }
                return Mutation(path, before, null, description, true)
            }
        }
    }
}
