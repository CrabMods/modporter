package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.migrateFinalMapDecorationSubclassSource
import com.modporter.core.transforms.structural.migrateMapDecorationRecordSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyMapDecorationSubclassesTest {
    private fun createProjectPass(): StructuralRefactorPass =
        StructuralRefactorPass().apply {
            projectValueFacts = ProjectValueFacts(
                parents = mapOf(
                    "example.map.Markers.Decoration" to
                        "net.minecraft.world.level.saveddata.maps.MapDecoration"
                ),
                types = setOf(
                    "example.map.Markers.Decoration",
                    "example.map.SelfRenderedDecoration"
                )
            )
        }

    @Test
    fun `record declarations accessors tracking and nullable names migrate from proven values`() {
        val source = """
            package example;

            import java.util.Optional;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;

            final class DecorationValues {
                static final MapDecoration.Type TYPE = MapDecoration.Type.PLAYER_OFF_MAP;
                static final String DOC =
                    "MapDecoration.Type.PLAYER_OFF_MAP value.getName()";

                MapDecoration copy(MapDecoration value, Component nullableName) {
                    // value.getType().shouldTrackCount() value.getName()
                    if (value.getType().shouldTrackCount()) {
                        byte ignored = (byte) (value.getX() + value.getY() + value.getRot());
                    }
                    Component name = value.getName();
                    return new MapDecoration(TYPE, value.getX(), value.getY(), value.getRot(), nullableName);
                }

                MapDecoration copyModern(MapDecoration value, Optional<Component> optionalName) {
                    return new MapDecoration(TYPE, value.getX(), value.getY(), value.getRot(), optionalName);
                }

                MapDecoration copyLegacyName(MapDecoration value) {
                    return new MapDecoration(TYPE, value.getX(), value.getY(), value.getRot(), value.getName());
                }

                String unrelated(Other value) {
                    return value.getName();
                }
            }

            final class Other {
                String getName() {
                    return "other";
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateMapDecorationRecordSource(source)

        assertTrue(migrated.contains("static final Holder<MapDecorationType> TYPE"), migrated)
        assertTrue(migrated.contains("MapDecorationTypes.PLAYER_OFF_MAP"), migrated)
        assertTrue(migrated.contains("value.type().value().trackCount()"), migrated)
        assertTrue(migrated.contains("value.name().orElse(null)"), migrated)
        assertTrue(migrated.contains("value.x() + value.y() + value.rot()"), migrated)
        assertTrue(migrated.contains("java.util.Optional.ofNullable(nullableName)"), migrated)
        assertTrue(
            migrated.contains(
                "new MapDecoration(TYPE, value.x(), value.y(), value.rot(), optionalName)"
            ),
            migrated
        )
        assertFalse(migrated.contains("ofNullable(optionalName)"), migrated)
        assertTrue(
            migrated.contains(
                "java.util.Optional.ofNullable(value.name().orElse(null))"
            ),
            migrated
        )
        assertTrue(migrated.contains("return value.getName();"), migrated)
        assertTrue(
            migrated.contains(
                "\"MapDecoration.Type.PLAYER_OFF_MAP value.getName()\""
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "// value.getType().shouldTrackCount() value.getName()"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateMapDecorationRecordSource(migrated)
        )
    }

    @Test
    fun `a source MapDecoration type shadows the canonical record`() {
        val source = """
            package example;

            final class ShadowedDecoration {
                static final class MapDecoration {
                    static final class Type {
                        static final Type LOCAL = new Type();
                    }

                    int getX() {
                        return 1;
                    }
                }

                MapDecoration copy(MapDecoration value) {
                    int x = value.getX();
                    return new MapDecoration();
                }

                MapDecoration.Type type() {
                    return MapDecoration.Type.LOCAL;
                }
            }
        """.trimIndent()

        assertEquals(
            source,
            StructuralRefactorPass().migrateMapDecorationRecordSource(source)
        )
        assertEquals(
            source,
            StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(source)
        )
    }

    @Test
    fun `custom rendered subclass is rebuilt from its record and keeps its interfaces`() {
        val source = """
            package example.map;

            import com.mojang.blaze3d.vertex.PoseStack;
            import net.minecraft.client.renderer.MultiBufferSource;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

            final class Markers {
                static final MapDecoration.Type TYPE = MapDecoration.Type.RED_MARKER;

                public static class Decoration extends MapDecoration
                        implements SelfRenderedDecoration {
                    public Decoration(byte x, byte y, Component name) {
                        super(TYPE, x, y, (byte) 18, name);
                    }

                    @Override
                    public boolean renderOnFrame() {
                        return true;
                    }

                    @Override
                    public void render(PoseStack poseStack, MultiBufferSource bufferSource, boolean active,
                            int light, MapItemSavedData mapData, int index) {
                    }

                    @Override
                    public boolean render(int index) {
                        return true;
                    }
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(source)

        assertTrue(
            Regex("""class\s+Decoration\s+implements\s+SelfRenderedDecoration""")
                .containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains("private final Holder<MapDecorationType> modporter\$type;"), migrated)
        assertTrue(migrated.contains("private final Optional<Component> modporter\$name;"), migrated)
        assertTrue(
            migrated.contains(
                "new MapDecoration(TYPE, x, y, (byte) 18, Optional.ofNullable(name))"
            ),
            migrated
        )
        assertTrue(migrated.contains("this.modporter\$rot = modporter\$decoration.rot();"), migrated)
        // 1.21.1 hands a renderer the record: the class is rebuilt from it to run its render code
        assertTrue(migrated.contains("return new MapDecoration(type(), x(), y(), rot(), name());"), migrated)
        assertTrue(migrated.contains("private Decoration(MapDecoration modporter\$decoration) {"), migrated)
        assertTrue(
            migrated.contains(
                "public static Decoration modporter\$fromDecoration(MapDecoration decoration) {\n" +
                    "\t\t\treturn new Decoration(decoration);"
            ) || migrated.contains(
                "public static Decoration modporter\$fromDecoration(MapDecoration decoration) {\n" +
                    "            return new Decoration(decoration);"
            ),
            migrated
        )
        assertFalse(migrated.contains("associate("), migrated)
        // the type now says whether it shows in item frames and NeoForge asks its renderer: nothing overridden
        assertFalse(Regex("""@Override\s+public boolean renderOnFrame\(\)""").containsMatchIn(migrated), migrated)
        assertFalse(Regex("""@Override\s+public boolean render\(int index\)""").containsMatchIn(migrated), migrated)
        assertTrue(Regex("""@Override\s+public void render\(PoseStack poseStack""").containsMatchIn(migrated), migrated)
        assertFalse(migrated.contains("extends MapDecoration"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(migrated)
        )
    }

    @Test
    fun `custom rendered subclass with state beyond the record stays as it is`() {
        val source = """
            package example.map;

            import com.mojang.blaze3d.vertex.PoseStack;
            import net.minecraft.client.renderer.MultiBufferSource;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

            final class Markers {
                public static class Decoration extends MapDecoration {
                    private final int color;

                    public Decoration(byte x, byte y, Component name, int color) {
                        super(MapDecoration.Type.RED_MARKER, x, y, (byte) 0, name);
                        this.color = color;
                    }

                    public void render(PoseStack poseStack, MultiBufferSource bufferSource, boolean active,
                            int light, MapItemSavedData mapData, int index) {
                    }
                }
            }
        """.trimIndent()

        assertEquals(source, StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(source))
    }

    @Test
    fun `plain subclass composes without renderer association and unsupported behavior fails closed`() {
        val plain = """
            package example;

            import net.minecraft.world.level.saveddata.maps.MapDecoration;

            final class Markers {
                static class Plain extends MapDecoration {
                    Plain(byte x, byte y) {
                        super(MapDecoration.Type.TARGET_X, x, y, (byte) 0, null);
                    }

                    @Override
                    public boolean render(int index) {
                        return false;
                    }
                }
            }
        """.trimIndent()
        val migrated =
            StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(plain)
        assertTrue(migrated.contains("static class Plain {"), migrated)
        assertTrue(
            migrated.contains(
                "return new MapDecoration(type(), x(), y(), rot(), name());"
            ),
            migrated
        )
        assertFalse(migrated.contains("associate("), migrated)
        assertTrue(migrated.contains("public boolean render(int index)"), migrated)
        assertFalse(
            Regex("""@Override\s+public boolean render\(int index\)""")
                .containsMatchIn(migrated),
            migrated
        )

        val unsupported = """
            package example;

            import net.minecraft.world.level.saveddata.maps.MapDecoration;

            final class Markers {
                static class Unsupported extends MapDecoration {
                    Unsupported(byte x, byte y) {
                        super(MapDecoration.Type.TARGET_X, x, y, (byte) 0);
                    }

                    @Override
                    public boolean renderOnFrame() {
                        return true;
                    }
                }
            }
        """.trimIndent()
        assertEquals(
            unsupported,
            StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(unsupported)
        )
    }

    @Test
    fun `typed creations of a proven source subclass become records`() {
        val storage = """
            package example;

            import example.map.Markers;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;

            final class Storage {
                void put(byte x, byte y) {
                    MapDecoration decoration =
                        new Markers.Decoration(x, y, null);
                }
            }
        """.trimIndent()
        val migratedStorage =
            createProjectPass().migrateFinalMapDecorationSubclassSource(storage)
        assertTrue(
            migratedStorage.contains(
                "new Markers.Decoration(x, y, null).asMapDecoration()"
            ),
            migratedStorage
        )

        val guessedStorage = """
            package example;

            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import some.other.Markers;

            final class Storage {
                void put(byte x, byte y) {
                    MapDecoration decoration =
                        new Markers.Decoration(x, y, null);
                }
            }
        """.trimIndent()
        assertEquals(
            guessedStorage,
            createProjectPass()
                .migrateFinalMapDecorationSubclassSource(guessedStorage)
        )
    }
}
