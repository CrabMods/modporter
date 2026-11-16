package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateForgeOnlyStairStateSupplierWrites
import com.modporter.core.transforms.structural.migrateLegacySupplierStairs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacySupplierStairsTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `supplier stairs and reflective state supplier writes become lazy base stairs`() {
        val source = """
            package example;

            import java.util.function.Function;
            import java.util.function.Supplier;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.StairBlock;
            import net.minecraft.world.level.block.WeatheringCopper.WeatherState;
            import net.minecraft.world.level.block.WeatheringCopperStairBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

            public class StairSet {
                static class Variant {
                    Function<Properties, StairBlock> factory(Supplier<BlockState> base, WeatherState state, boolean waxed) {
                        Supplier<BlockState> defaultStateSupplier = () -> base.get();
                        if (waxed)
                            return p -> new StairBlock(defaultStateSupplier, p);
                        return p -> {
                            WeatheringCopperStairBlock block =
                                new WeatheringCopperStairBlock(state, Blocks.AIR.defaultBlockState(), p);
                            // the subclass has no supplier constructor
                            ObfuscationReflectionHelper.setPrivateValue(StairBlock.class, block, defaultStateSupplier,
                                "stateSupplier");
                            return block;
                        };
                    }
                }
            }
        """.trimIndent()

        val migrated = pass.migrateForgeOnlyStairStateSupplierWrites(pass.migrateLegacySupplierStairs(source))
        val text = migrated.replace(Regex("""\s+"""), " ")

        assertTrue(text.contains("return p -> new ModPorterSupplierStairBlock(defaultStateSupplier, p);"), migrated)
        assertTrue(text.contains("WeatheringCopperStairBlock block = new ModPorterSupplierWeatheringCopperStairBlock(state, defaultStateSupplier, p);"), migrated)
        assertFalse(migrated.contains("stateSupplier\""), migrated)
        assertTrue(migrated.contains("private static final class ModPorterSupplierStairBlock extends net.minecraft.world.level.block.StairBlock"), migrated)
        assertTrue(migrated.contains("private static final class ModPorterSupplierWeatheringCopperStairBlock extends net.minecraft.world.level.block.WeatheringCopperStairBlock"), migrated)
        assertEquals(2, Regex("""public float getExplosionResistance\(\)""").findAll(migrated).count(), migrated)
        assertEquals(migrated, pass.migrateLegacySupplierStairs(migrated))
    }

    @Test
    fun `a stair built from a ready base state stays native`() {
        val source = """
            package example;

            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.StairBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour.Properties;

            class Plain {
                StairBlock make(Properties p) { return new StairBlock(Blocks.STONE.defaultBlockState(), p); }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacySupplierStairs(source))
    }
}
