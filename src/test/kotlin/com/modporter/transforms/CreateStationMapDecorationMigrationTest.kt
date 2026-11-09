package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateStationMapDecorationMigration
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateStationMapDecorationMigrationTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `station family admits the actual preceding text and AST phases`() {
        val fixture = LegacyFixture(root).write()
        val mappings = MappingDatabase.loadDefault()
        val text = TextReplacementPass(mappings).apply(root)
        val ast = AstTransformPass(mappings).apply(root)
        assertTrue(text.errors.isEmpty(), text.errors.toString())
        assertTrue(ast.errors.isEmpty(), ast.errors.toString())
        val before = root.resolve(fixture.stationMarker).readText()
        assertTrue(before.contains(".setUv("), before)
        assertFalse(before.contains(".endVertex()"), before)

        val result = CreateStationMapDecorationMigration.migrate(StructuralRefactorPass(), root, false)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertTrue(result.changes.isNotEmpty())
        assertTrue(root.resolve(fixture.nativeRenderer).exists())
        assertFalse(root.resolve(fixture.packetMixin).exists())
    }

    @Test
    fun `changed geometry remains rejected after text and AST preprocessing`() {
        val fixture = LegacyFixture(root).write()
        val mappings = MappingDatabase.loadDefault()
        TextReplacementPass(mappings).apply(root)
        AstTransformPass(mappings).apply(root)
        val station = root.resolve(fixture.stationMarker)
        val original = station.readText()
        assertTrue(original.contains("zOffset * index"), original)
        station.writeText(original.replace("zOffset * index", "zOffset * (index + 1)"))
        val snapshot = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(StructuralRefactorPass(), root, false)
        assertTrue(result.gates.isNotEmpty())
        assertTrue(result.changes.isEmpty())
        fixture.assertSnapshot(snapshot)
    }

    @Test
    fun `legacy station packet side channel becomes native registered decoration family`() {
        val fixture = LegacyFixture(root).write()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertTrue(result.changes.isNotEmpty())
        assertFalse(root.resolve(fixture.packetMixin).exists())
        assertFalse(root.resolve(fixture.renderMixin).exists())
        assertFalse(root.resolve(fixture.customRenderer).exists())

        val station = root.resolve(fixture.stationMarker).readText()
        assertTrue(station.contains("createStationDecoration("), station)
        assertTrue(
            station.contains("AllMapDecorationTypes.STATION_MAP_DECORATION"),
            station
        )
        assertTrue(station.contains("id = \"create:station-"), station)
        assertFalse(station.contains("class Decoration"), station)
        assertFalse(station.contains("station_map_icon.png"), station)

        val mapData = root.resolve(fixture.mapDataMixin).readText()
        assertTrue(
            mapData.contains(
                "StationMarker.createStationDecoration(" +
                    "localXByte, localZByte, Optional.of(marker.getName()))"
            ),
            mapData
        )
        val types = root.resolve(fixture.decorationTypes).readText()
        assertTrue(types.contains("DECORATION_TYPES.register("), types)
        assertTrue(types.contains("\"station\""), types)
        assertTrue(types.contains("Create.asResource(\"station\")"), types)
        assertTrue(
            types.filterNot(Char::isWhitespace)
                .contains("true,-1,false,true"),
            types
        )
        assertFalse(types.contains("STREAM_CODEC"), types)
        val renderer = root.resolve(fixture.nativeRenderer).readText()
        assertTrue(renderer.contains("decoration.x() / 2D + 64.0"), renderer)
        assertTrue(renderer.contains("decoration.y() / 2D + 64.0"), renderer)
        assertTrue(renderer.contains("decoration.name().isPresent()"), renderer)
        assertTrue(renderer.contains("index * zOffset"), renderer)
        assertTrue(renderer.contains("poseStack.scale(4.5F, 4.5F, 3.0F)"), renderer)
        assertTrue(renderer.contains("return true;"), renderer)
        assertFalse(renderer.contains("STREAM_CODEC"), renderer)
        val vertices = listOf(
            "addVertex(matrix, -1.0F, 1.0F, index * zOffset)",
            "addVertex(matrix, 1.0F, 1.0F, index * zOffset)",
            "addVertex(matrix, 1.0F, -1.0F, index * zOffset)",
            "addVertex(matrix, -1.0F, -1.0F, index * zOffset)"
        ).map(renderer::indexOf)
        assertTrue(vertices.all { it >= 0 }, renderer)
        assertEquals(vertices.sorted(), vertices, renderer)

        val events = root.resolve(fixture.nativeEvents).readText()
        assertTrue(
            events.contains(
                "AllMapDecorationTypes.STATION_MAP_DECORATION.value()"
            ),
            events
        )
        assertTrue(events.contains("new StationMapDecorationRenderer()"), events)

        val create = root.resolve(fixture.createMain).readText()
        assertTrue(
            create.contains("AllMapDecorationTypes.register(modEventBus);"),
            create
        )
        val mixins = root.resolve(fixture.mixinConfig).readText()
        assertFalse(mixins.contains("ClientboundMapItemDataPacketMixin"), mixins)
        assertFalse(mixins.contains("MapRendererMapInstanceMixin"), mixins)
        assertTrue(mixins.contains("MapItemSavedDataMixin"), mixins)
        assertContentEquals(
            fixture.stationIcon,
            root.resolve(fixture.nativeAsset).readBytes()
        )
        assertTrue(
            station.contains("public static StationMarker load(CompoundTag tag)"),
            station
        )
        assertTrue(station.contains("public CompoundTag save()"), station)
        assertTrue(
            mapData.contains(
                "method = \"load(Lnet/minecraft/nbt/CompoundTag;)" +
                    "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;\""
            ),
            mapData
        )
    }

    @Test
    fun `unknown custom decoration consumer fails closed before any write`() {
        val fixture = LegacyFixture(root).write()
        val extra = root.resolve(
            "src/main/java/com/example/UnexpectedStationConsumer.java"
        )
        extra.parent.createDirectories()
        extra.writeText(
            """
            package com.example;
            final class UnexpectedStationConsumer {
                Object value = StationMarker.Decoration.class;
            }
            """.trimIndent()
        )
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains("Unknown Create station-map family consumer"),
            result.gates.joinToString()
        )
        fixture.assertSnapshot(before)
    }

    @Test
    fun `extra behavior in a deleted packet mixin fails exact admission`() {
        val fixture = LegacyFixture(root).write()
        appendBeforeLastBrace(
            root.resolve(fixture.packetMixin),
            """

                private void unexpectedPacketBehavior() {
                    System.out.println("must survive");
                }
            """.trimIndent()
        )
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains(
                "ClientboundMapItemDataPacketMixin declaration changed"
            ),
            result.gates.joinToString()
        )
        fixture.assertSnapshot(before)
    }

    @Test
    fun `second producer inside the known map data file fails closed`() {
        val fixture = LegacyFixture(root).write()
        val mapDataPath = root.resolve(fixture.mapDataMixin)
        val source = mapDataPath.readText()
        val anchor =
            "MapDecoration oldDecoration = decorations.put(marker.getId(), decoration);"
        mapDataPath.writeText(
            source.replace(
                anchor,
                """
                MapDecoration duplicate = new StationMarker.Decoration(
                    localXByte, localZByte, marker.getName());
                $anchor
                """.trimIndent()
            )
        )
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains(
                "MapItemSavedDataMixin must own exactly one admitted station producer"
            ),
            result.gates.joinToString()
        )
        fixture.assertSnapshot(before)
    }

    @Test
    fun `kotlin alias consumer in another source set fails closed`() {
        val fixture = LegacyFixture(root).write()
        val extra = root.resolve(
            "src/client/kotlin/com/example/AliasedDecoration.kt"
        )
        extra.parent.createDirectories()
        extra.writeText(
            """
            package com.example
            import com.simibubi.create.content.trains.station.StationMarker.Decoration as StationDecoration
            val decorationClass = StationDecoration::class
            """.trimIndent()
        )
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains("Unknown Create station-map family consumer"),
            result.gates.joinToString()
        )
        fixture.assertSnapshot(before)
    }

    @Test
    fun `static nested import consumer fails closed`() {
        val fixture = LegacyFixture(root).write()
        val extra = root.resolve(
            "src/api/java/com/example/StaticDecorationImport.java"
        )
        extra.parent.createDirectories()
        extra.writeText(
            """
            package com.example;
            import static com.simibubi.create.content.trains.station.StationMarker.Decoration;
            final class StaticDecorationImport {
                Class<?> type = Decoration.class;
            }
            """.trimIndent()
        )
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains("Unknown Create station-map family consumer"),
            result.gates.joinToString()
        )
        fixture.assertSnapshot(before)
    }

    @Test
    fun `duplicate generated fqn at another path fails before any write`() {
        val fixture = LegacyFixture(root).write()
        val duplicate = root.resolve(
            "src/client/java/com/simibubi/create/AllMapDecorationTypes.java"
        )
        duplicate.parent.createDirectories()
        duplicate.writeText(
            """
            package com.simibubi.create;
            public final class AllMapDecorationTypes {
            }
            """.trimIndent()
        )
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains(
                "com.simibubi.create.AllMapDecorationTypes is already owned"
            ),
            result.gates.joinToString()
        )
        fixture.assertSnapshot(before)
    }

    @Test
    fun `incompatible generated path with changed string literal fails closed`() {
        val fixture = LegacyFixture(root).write()
        val conflict = root.resolve(fixture.decorationTypes)
        conflict.parent.createDirectories()
        conflict.writeText(
            """
            package com.simibubi.create;
            public final class AllMapDecorationTypes {
                private static final String ID = "sta tion";
            }
            """.trimIndent()
        )
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains(
                "Generated AllMapDecorationTypes path is owned by incompatible source"
            ),
            result.gates.joinToString()
        )
        fixture.assertSnapshot(before)
    }

    @Test
    fun `transaction rollback restores deleted written and binary family members byte exact`() {
        val fixture = LegacyFixture(root).write()
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false,
            commitProbe = { index, _ ->
                if (index == 10) error("injected family commit failure")
            }
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.single().contains("injected family commit failure"),
            result.gates.joinToString()
        )
        fixture.assertSnapshot(before)
        assertFalse(root.resolve(fixture.decorationTypes).exists())
        assertFalse(root.resolve(fixture.nativeRenderer).exists())
        assertFalse(root.resolve(fixture.nativeEvents).exists())
        assertFalse(root.resolve(fixture.nativeAsset).exists())
    }

    @Test
    fun `dry run plans the complete family and writes nothing`() {
        val fixture = LegacyFixture(root).write()
        val before = fixture.snapshot()

        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = true
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(11, result.changes.size)
        fixture.assertSnapshot(before)
        assertFalse(root.resolve(fixture.decorationTypes).exists())
        assertFalse(root.resolve(fixture.nativeAsset).exists())
    }

    @Test
    fun `second application is idempotent and emits no packet codec wrapper`() {
        val fixture = LegacyFixture(root).write()
        val first = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )
        assertTrue(first.gates.isEmpty(), first.gates.joinToString())

        val second = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertEquals(CreateStationMapDecorationMigration.Result.EMPTY, second)
        Files.walk(root).use { paths ->
            val joined = paths.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".java") }
                .map { it.readText() }
                .toList()
                .joinToString("\n")
            assertFalse(joined.contains("ClientboundMapItemDataPacket.STREAM_CODEC"))
            assertFalse(joined.contains("writeVarIntArray"))
            assertFalse(joined.contains("readVarIntArray"))
        }
    }

    @Test
    fun `native semantic drift is not accepted as idempotent`() {
        val fixture = LegacyFixture(root).write()
        val first = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )
        assertTrue(first.gates.isEmpty(), first.gates.joinToString())

        val typesPath = root.resolve(fixture.decorationTypes)
        typesPath.writeText(
            typesPath.readText().replace(
                "Create.asResource(\"station\")",
                "Create.asResource(\"sta tion\")"
            )
        )
        val result = CreateStationMapDecorationMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.isNotEmpty())
        assertTrue(
            result.gates.single().contains("Legacy Create station-map family is incomplete"),
            result.gates.joinToString()
        )
    }

    private fun appendBeforeLastBrace(path: Path, addition: String) {
        val source = path.readText()
        val close = source.lastIndexOf('}')
        require(close >= 0)
        path.writeText(
            source.substring(0, close) + addition + "\n" + source.substring(close)
        )
    }

    private class LegacyFixture(private val root: Path) {
        val stationMarker =
            "src/main/java/com/simibubi/create/content/trains/station/StationMarker.java"
        val mapDataMixin =
            "src/main/java/com/simibubi/create/foundation/mixin/MapItemSavedDataMixin.java"
        val packetMixin =
            "src/main/java/com/simibubi/create/foundation/mixin/ClientboundMapItemDataPacketMixin.java"
        val renderMixin =
            "src/main/java/com/simibubi/create/foundation/mixin/client/MapRendererMapInstanceMixin.java"
        val customRenderer =
            "src/main/java/com/simibubi/create/foundation/map/CustomRenderedMapDecoration.java"
        val createMain = "src/main/java/com/simibubi/create/Create.java"
        val mixinConfig = "src/main/resources/create.mixins.json"
        // Stock admission bytes are fetched into a test-only cache, not redistributed.
        val legacyAsset =
            "src/main/resources/assets/create/textures/gui/station_map_icon.png"
        val decorationTypes =
            "src/main/java/com/simibubi/create/AllMapDecorationTypes.java"
        val nativeRenderer =
            "src/main/java/com/simibubi/create/foundation/map/StationMapDecorationRenderer.java"
        val nativeEvents =
            "src/main/java/com/simibubi/create/foundation/map/StationMapDecorationEvents.java"
        val nativeAsset =
            "src/main/resources/assets/create/textures/map/decorations/station.png"

        lateinit var stationIcon: ByteArray

        fun write(): LegacyFixture {
            val template = Path.of(
                "src",
                "test",
                "resources",
                "fixtures",
                "create-station-map"
            ).toAbsolutePath().normalize()
            require(Files.isDirectory(template)) {
                "Missing real-source fixture tree at $template"
            }
            Files.walk(template).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { source ->
                    val relative = template.relativize(source)
                    val target = root.resolve(relative.toString())
                    target.parent.createDirectories()
                    target.writeBytes(source.readBytes())
                }
            }
            val asset = root.resolve(legacyAsset)
            asset.parent.createDirectories()
            asset.writeBytes(stockStationIcon())
            stationIcon = asset.readBytes()
            return this
        }

        fun snapshot(): Map<String, ByteArray> {
            val result = linkedMapOf<String, ByteArray>()
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { path ->
                    result[root.relativize(path).toString().replace('\\', '/')] =
                        path.readBytes()
                }
            }
            return result
        }

        fun assertSnapshot(expected: Map<String, ByteArray>) {
            val actual = snapshot()
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (path, bytes) ->
                assertContentEquals(bytes, actual.getValue(path), path)
            }
        }

    }

    private companion object {
        const val STOCK_ICON_SHA256 = "14ff606c8a0cc07309a2b4dc4316f9d9a3bb51addfafa76865d4980624e68b87"
        const val STOCK_ICON_URL = "https://raw.githubusercontent.com/Creators-of-Create/Create/" +
            "ac0c444d9828da3453ae8cc65338e8de063286fb/" +
            "src/main/resources/assets/create/textures/map/decorations/station.png"

        @Synchronized
        fun stockStationIcon(): ByteArray {
            val cache = Path.of("build", "test-fixtures", "create-station-map", "$STOCK_ICON_SHA256.png")
            fun verify(bytes: ByteArray): ByteArray {
                val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                check(actual == STOCK_ICON_SHA256) { "Pinned upstream station icon hash differs: $actual" }
                return bytes
            }
            if (cache.exists()) return verify(cache.readBytes())
            val connection = URI(STOCK_ICON_URL).toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            val bytes = verify(connection.getInputStream().use { it.readBytes() })
            cache.parent.createDirectories()
            cache.writeBytes(bytes)
            return bytes
        }
    }
}
