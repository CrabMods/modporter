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
                    "com.simibubi.create.content.trains.station.StationMarker.Decoration" to
                        "net.minecraft.world.level.saveddata.maps.MapDecoration"
                ),
                types = setOf(
                    "com.simibubi.create.content.trains.station.StationMarker.Decoration",
                    "com.simibubi.create.foundation.map.CustomRenderedMapDecoration"
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
    fun `custom rendered subclass becomes associated composition and preserves interfaces`() {
        val source = """
            package example;

            import net.minecraft.network.chat.Component;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;

            final class StationMarker {
                static final MapDecoration.Type TYPE = MapDecoration.Type.RED_MARKER;

                public static class Decoration extends MapDecoration
                        implements CustomRenderedMapDecoration {
                    public Decoration(byte x, byte y, Component name) {
                        super(TYPE, x, y, (byte) 18, name);
                    }

                    static Decoration from(MapDecoration decoration) {
                        return new Decoration(
                            decoration.getX(),
                            decoration.getY(),
                            decoration.getName());
                    }

                    @Override
                    public boolean renderOnFrame() {
                        return true;
                    }

                    @Override
                    public void render(Object pose, Object buffers, boolean active,
                            int light, Object mapData, int index) {
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
            Regex("""class\s+Decoration\s+implements\s+CustomRenderedMapDecoration""")
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
        assertTrue(migrated.contains("decoration.name().orElse(null)"), migrated)
        assertTrue(
            migrated.filterNot(Char::isWhitespace).contains(
                "CustomRenderedMapDecoration.associate(" +
                    "new MapDecoration(type(), x(), y(), rot(), name()), this)"
                        .filterNot(Char::isWhitespace)
            ),
            migrated
        )
        assertTrue(
            Regex("""@Override\s+public boolean renderOnFrame\(\)""")
                .containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            Regex("""@Override\s+public boolean render\(int index\)""")
                .containsMatchIn(migrated),
            migrated
        )
        assertFalse(migrated.contains("extends MapDecoration"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(migrated)
        )
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
        assertFalse(migrated.contains("CustomRenderedMapDecoration.associate"), migrated)
        assertTrue(migrated.contains("public boolean render(int index)"), migrated)
        assertFalse(
            Regex("""@Override\s+public boolean render\(int index\)""")
                .containsMatchIn(migrated),
            migrated
        )

        val unsupported = """
            package example;

            import com.simibubi.create.content.trains.station.StationMarker;
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
    fun `renderer interface and packet seams retain wrapper association`() {
        val rendererInterface = """
            package com.simibubi.create.foundation.map;

            interface CustomRenderedMapDecoration {
                void render(Object pose, Object buffers, boolean active,
                    int packedLight, Object mapData, int index);
            }
        """.trimIndent()
        val migratedInterface =
            createProjectPass().migrateFinalMapDecorationSubclassSource(rendererInterface)
        assertTrue(
            migratedInterface.contains(
                "Collections.synchronizedMap(new WeakHashMap<>())"
            ),
            migratedInterface
        )
        assertTrue(migratedInterface.contains("boolean renderOnFrame();"), migratedInterface)
        assertTrue(migratedInterface.contains("boolean render(int index);"), migratedInterface)
        assertTrue(
            migratedInterface.contains(
                "static MapDecoration associate("
            ),
            migratedInterface
        )
        assertTrue(
            migratedInterface.contains(
                "static CustomRenderedMapDecoration find(MapDecoration decoration)"
            ),
            migratedInterface
        )
        assertEquals(
            migratedInterface,
            createProjectPass()
                .migrateFinalMapDecorationSubclassSource(migratedInterface)
        )

        val packet = """
            package com.simibubi.create.foundation.mixin;

            import java.util.List;
            import com.simibubi.create.content.trains.station.StationMarker;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;

            final class ClientboundMapItemDataPacketMixin {
                private List<MapDecoration> decorations;

                void repair(MapDecoration decoration, int index) {
                    if (decoration instanceof StationMarker.Decoration) {
                        decorations.set(
                            index,
                            StationMarker.Decoration.from(decoration));
                    }
                }
            }
        """.trimIndent()
        val migratedPacket =
            createProjectPass().migrateFinalMapDecorationSubclassSource(packet)
        assertTrue(
            migratedPacket.contains(
                "CustomRenderedMapDecoration.find(decoration) != null"
            ),
            migratedPacket
        )
        assertTrue(
            migratedPacket.contains(
                "StationMarker.Decoration.from(decoration).asMapDecoration()"
            ),
            migratedPacket
        )
        assertTrue(
            migratedPacket.contains(
                "import com.simibubi.create.foundation.map.CustomRenderedMapDecoration;"
            ),
            migratedPacket
        )

        val storage = """
            package example;

            import com.simibubi.create.content.trains.station.StationMarker;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;

            final class Storage {
                void put(byte x, byte y) {
                    MapDecoration decoration =
                        new StationMarker.Decoration(x, y, null);
                }
            }
        """.trimIndent()
        val migratedStorage =
            createProjectPass().migrateFinalMapDecorationSubclassSource(storage)
        assertTrue(
            migratedStorage.contains(
                "new StationMarker.Decoration(x, y, null).asMapDecoration()"
            ),
            migratedStorage
        )

        val guessedStorage = """
            package example;

            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import some.other.StationMarker;

            final class Storage {
                void put(byte x, byte y) {
                    MapDecoration decoration =
                        new StationMarker.Decoration(x, y, null);
                }
            }
        """.trimIndent()
        assertEquals(
            guessedStorage,
            createProjectPass()
                .migrateFinalMapDecorationSubclassSource(guessedStorage)
        )
    }

    @Test
    fun `legacy map renderer injections become modern wrap operations`() {
        val source = """
            package com.simibubi.create.foundation.mixin.client;

            import java.util.Iterator;
            import org.joml.Matrix4f;
            import org.objectweb.asm.Opcodes;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.Shadow;
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Group;
            import org.spongepowered.asm.mixin.injection.Inject;
            import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
            import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import com.simibubi.create.foundation.map.CustomRenderedMapDecoration;
            import net.minecraft.client.renderer.MultiBufferSource;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

            @Mixin(targets = "net.minecraft.client.gui.MapRenderer${'$'}MapInstance")
            final class MapRendererMapInstanceMixin {
                @Shadow
                private MapItemSavedData data;

                @Group(name = "custom_decoration_rendering", min = 1, max = 1)
                @Inject(method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/maps/MapDecoration;render(I)Z", remap = false), locals = LocalCapture.CAPTURE_FAILHARD)
                private void create${'$'}onDraw(PoseStack poseStack, MultiBufferSource bufferSource, boolean active, int packedLight, CallbackInfo ci, int index, Iterator<MapDecoration> iterator, MapDecoration decoration) {
                    if (decoration instanceof CustomRenderedMapDecoration renderer) {
                        renderer.render(poseStack, bufferSource, active, packedLight, data, index);
                    }
                }

                @Group(name = "custom_decoration_rendering")
                @Inject(method = "draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ZI)V", at = @At(value = "FIELD", target = "net/optifine/reflect/Reflector.ForgeMapDecoration_render:Lnet/optifine/reflect/ReflectorMethod;", opcode = Opcodes.GETSTATIC, ordinal = 1, remap = false), locals = LocalCapture.CAPTURE_FAILHARD)
                private void create${'$'}onDrawOptifine(PoseStack poseStack, MultiBufferSource bufferSource, boolean active, int packedLight, CallbackInfo ci, int index, Iterator<MapDecoration> iterator, MapDecoration decoration) {
                    if (decoration instanceof CustomRenderedMapDecoration renderer) {
                        renderer.render(poseStack, bufferSource, active, packedLight, data, index);
                    }
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(source)

        assertTrue(migrated.contains("@WrapOperation("), migrated)
        assertTrue(
            migrated.contains("MapDecoration;renderOnFrame()Z"),
            migrated
        )
        assertTrue(
            migrated.contains("MapDecorationRendererManager;render("),
            migrated
        )
        assertTrue(
            migrated.contains(
                "CustomRenderedMapDecoration.find(decoration)"
            ),
            migrated
        )
        assertTrue(migrated.contains("return renderer.render(index);"), migrated)
        assertTrue(migrated.contains("original.call(decoration)"), migrated)
        assertFalse(migrated.contains("MapDecoration;render(I)Z"), migrated)
        assertFalse(migrated.contains("ForgeMapDecoration_render"), migrated)
        assertFalse(migrated.contains("LocalCapture"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateFinalMapDecorationSubclassSource(migrated)
        )
    }
}
