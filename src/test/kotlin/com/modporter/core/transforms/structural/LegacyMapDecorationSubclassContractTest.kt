package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A MapDecoration subclass that delegates constructors and extends the inherited value contract. */
class LegacyMapDecorationSubclassContractTest {
    private val source = """
        package example;

        import net.minecraft.network.chat.Component;
        import net.minecraft.world.level.saveddata.maps.MapDecoration;
        import net.neoforged.api.distmarker.Dist;
        import net.neoforged.api.distmarker.OnlyIn;

        public class MagicMapData {
            public static class FeatureDecoration extends MapDecoration {
                final int featureId;

                public FeatureDecoration(Landmark landmark, byte x, byte y, byte rotation) {
                    this(landmark.id(), x, y, rotation);
                }

                public FeatureDecoration(int featureId, byte x, byte y, byte rotation) {
                    super(Type.TARGET_X, x, y, rotation, Component.translatable("map.magic.text"));
                    this.featureId = featureId;
                }

                @Override
                @OnlyIn(Dist.CLIENT)
                public boolean render(int index) {
                    return getX() > 0 && getRot() >= 0;
                }

                @Override
                public boolean equals(Object o) {
                    if (super.equals(o) && o instanceof FeatureDecoration other) {
                        return this.featureId == other.featureId;
                    }
                    return false;
                }

                @Override
                public int hashCode() {
                    return super.hashCode() * 31 + featureId;
                }
            }
        }
    """.trimIndent()

    @Test
    fun `delegating constructors and the inherited equals and hashCode compose exactly`() {
        val migrated = StructuralRefactorPass().migrateLegacyMapDecorationCompositionSource(source)

        assertFalse(migrated.contains("extends MapDecoration"), migrated)
        assertFalse(migrated.contains("super."), migrated)
        assertTrue(migrated.contains("this(landmark.id(), x, y, rotation);"), migrated)
        assertTrue(
            migrated.contains(
                "new MapDecoration(MapDecorationTypes.TARGET_X, x, y, rotation, " +
                    "Optional.of(Component.translatable(\"map.magic.text\")));"
            ),
            migrated
        )
        assertTrue(migrated.contains("this.modporter\$legacyEquals(o) && o instanceof FeatureDecoration other"), migrated)
        assertTrue(migrated.contains("return this.modporter\$legacyHashCode() * 31 + featureId;"), migrated)
        assertTrue(migrated.contains("private boolean modporter\$legacyEquals(Object other)"), migrated)
        assertTrue(migrated.contains("&& this.modporter\$name.equals(decoration.modporter\$name);"), migrated)
        assertTrue(migrated.contains("private int modporter\$legacyHashCode()"), migrated)
        // render(int) no longer overrides anything; its other annotations stay
        assertTrue(migrated.contains("@OnlyIn(Dist.CLIENT)\n"), migrated)
        assertFalse(Regex("""@Override\s+(?:@\w+(?:\([^)]*\))?\s+)*public boolean render""").containsMatchIn(migrated), migrated)
        assertTrue(migrated.contains("public byte getX()"), migrated)
        assertTrue(migrated.contains("public MapDecoration asMapDecoration()"), migrated)
        assertTrue(
            JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
                .parse(migrated).isSuccessful,
            migrated
        )
    }

    @Test
    fun `other super calls stay unsupported`() {
        val other = source.replace("return super.hashCode() * 31 + featureId;", "return super.toString().length();")
        assertTrue(
            StructuralRefactorPass().migrateLegacyMapDecorationCompositionSource(other).contains("extends MapDecoration")
        )
    }
}
