package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateForwardingWorldContracts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForwardingWorldContractsTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `forwarding level and read only chunk keep their established behavior`() {
        val source = """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.crafting.RecipeManager;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.chunk.ChunkAccess;
            import net.minecraft.world.level.storage.WritableLevelData;
            import net.minecraft.world.scores.Scoreboard;

            abstract class ForwardingLevel extends Level {
                private final Level delegate;

                ForwardingLevel(Level delegate) {
                    super(
                        (WritableLevelData) delegate.getLevelData(),
                        delegate.dimension(),
                        delegate.registryAccess(),
                        delegate.dimensionTypeRegistration(),
                        delegate.getProfilerSupplier(),
                        false,
                        false,
                        0,
                        0);
                    this.delegate = delegate;
                }

                @Override
                public Scoreboard getScoreboard() {
                    return delegate.getScoreboard();
                }

                @Override
                public RecipeManager getRecipeManager() {
                    return delegate.getRecipeManager();
                }
            }

            abstract class ReadOnlyChunk extends ChunkAccess {
                @Override
                public CompoundTag getBlockEntityNbtForSaving(BlockPos pos) {
                    return null;
                }
            }
        """.trimIndent()

        val result = pass.migrateForwardingWorldContracts(source)

        assertTrue(result.contains("import net.minecraft.core.HolderLookup;"), result)
        assertTrue(result.contains("import net.minecraft.world.TickRateManager;"), result)
        assertTrue(result.contains("import net.minecraft.world.item.alchemy.PotionBrewing;"), result)
        assertTrue(result.contains("return this.delegate.potionBrewing();"), result)
        assertTrue(result.contains("this.delegate.setDayTimeFraction(value);"), result)
        assertTrue(result.contains("this.delegate.setDayTimePerTick(value);"), result)
        assertTrue(result.contains("return this.delegate.getDayTimeFraction();"), result)
        assertTrue(result.contains("return this.delegate.getDayTimePerTick();"), result)
        assertTrue(result.contains("return this.delegate.tickRateManager();"), result)
        assertTrue(
            result.replace(Regex("""\s+"""), " ").contains(
                "getBlockEntityNbtForSaving(BlockPos pos, HolderLookup.Provider registries) { return null; }"
            ),
            result
        )
        assertEquals(result, pass.migrateForwardingWorldContracts(result))
    }

    @Test
    fun `ambiguous delegates lookalikes and modern chunk overrides stay unchanged`() {
        val source = """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.chunk.ChunkAccess;
            import net.minecraft.world.level.storage.WritableLevelData;

            abstract class AmbiguousLevel extends Level {
                private final Level first;
                private final Level second;

                AmbiguousLevel(Level first, Level second) {
                    super(null, null, null, null, null, false, false, 0, 0);
                    this.first = first;
                    this.second = second;
                }

                Object one() { return first.getScoreboard(); }
                Object two() { return first.getRecipeManager(); }
            }

            abstract class IndependentDataLevel extends Level {
                private final Level delegate;

                IndependentDataLevel(Level delegate, WritableLevelData independentData) {
                    super(
                        independentData,
                        delegate.dimension(),
                        delegate.registryAccess(),
                        delegate.dimensionTypeRegistration(),
                        delegate.getProfilerSupplier(),
                        false,
                        false,
                        0,
                        0);
                    this.delegate = delegate;
                }

                Object one() { return delegate.getScoreboard(); }
                Object two() { return delegate.getRecipeManager(); }
            }

            abstract class IndependentRegistryLevel extends Level {
                private final Level delegate;

                IndependentRegistryLevel(Level delegate, Level registryOwner) {
                    super(
                        (WritableLevelData) delegate.getLevelData(),
                        delegate.dimension(),
                        registryOwner.registryAccess(),
                        delegate.dimensionTypeRegistration(),
                        delegate.getProfilerSupplier(),
                        false,
                        false,
                        0,
                        0);
                    this.delegate = delegate;
                }

                Object one() { return delegate.getScoreboard(); }
                Object two() { return delegate.getRecipeManager(); }
            }

            abstract class ModernChunk extends ChunkAccess {
                @Override
                public CompoundTag getBlockEntityNbtForSaving(
                        BlockPos pos, HolderLookup.Provider registries) {
                    return save(pos, registries);
                }

                abstract CompoundTag save(BlockPos pos, HolderLookup.Provider registries);
            }

            class ChunkAccess {
                CompoundTag getBlockEntityNbtForSaving(BlockPos pos) {
                    return new CompoundTag();
                }
            }
        """.trimIndent()

        val result = pass.migrateForwardingWorldContracts(source)

        assertEquals(source, result)
        assertFalse(result.contains("PotionBrewing potionBrewing()"), result)
    }
}
