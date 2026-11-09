package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** `Owner.CODEC.codec()` loses `codec()` only when Owner really is a platform class with a plain codec. */
class CodecApiMovesOwnerTest {
    @Test
    fun `a project type sharing a platform simple name keeps its map codec call`() {
        // SetStewEffectFunction$EffectEntry is a platform class whose CODEC is a plain Codec.
        val source = """
            package example;
            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            public record Definition(java.util.List<EffectEntry> effects) {
                static final Codec<java.util.List<EffectEntry>> EFFECTS = EffectEntry.CODEC.codec().listOf();
                public record EffectEntry(int duration) {
                    public static final MapCodec<EffectEntry> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("duration").forGetter(EffectEntry::duration)).apply(i, EffectEntry::new));
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateCodecApiMoves(source)

        assertTrue(migrated.contains("EffectEntry.CODEC.codec().listOf()"), migrated)
    }

    @Test
    fun `an imported or qualified platform type with a plain codec drops codec`() {
        val source = """
            package example;
            import com.mojang.serialization.Codec;
            import net.minecraft.world.level.storage.loot.functions.SetStewEffectFunction;
            import net.minecraft.world.level.storage.loot.functions.SetStewEffectFunction.EffectEntry;
            final class Uses {
                Object imported = EffectEntry.CODEC.codec().listOf();
                Object outer = SetStewEffectFunction.EffectEntry.CODEC.codec().listOf();
                Object qualified = net.minecraft.world.level.storage.loot.functions.SetStewEffectFunction.EffectEntry.CODEC.codec();
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateCodecApiMoves(source)

        assertTrue(migrated.contains("imported = EffectEntry.CODEC.listOf()"), migrated)
        assertTrue(migrated.contains("outer = SetStewEffectFunction.EffectEntry.CODEC.listOf()"), migrated)
        assertTrue(
            migrated.contains(
                "qualified = net.minecraft.world.level.storage.loot.functions.SetStewEffectFunction.EffectEntry.CODEC;"
            ),
            migrated
        )
    }

    @Test
    fun `an unimported same-package name is the project type`() {
        val source = """
            package example;
            final class Uses {
                Object effects = EffectEntry.CODEC.codec().listOf();
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateCodecApiMoves(source)

        assertTrue(migrated.contains("EffectEntry.CODEC.codec().listOf()"), migrated)
    }
}
