package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyClientRenderSoundCalls
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyClientRenderSoundCallsTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `model data manager null branch is not equated with empty model data`() {
        val source = """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockAndTintGetter;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.client.model.data.ModelDataManager;

            final class CopycatBlock {
                BlockState appearance(
                        BlockState state,
                        BlockAndTintGetter level,
                        BlockPos pos) {
                    ModelDataManager modelDataManager = level.getModelDataManager();
                    if (modelDataManager == null)
                        return fallback(level, pos);
                    return CopycatModel.getMaterial(modelDataManager.getAt(pos));
                }

                BlockState fallback(BlockAndTintGetter level, BlockPos pos) {
                    return null;
                }
            }

            final class CopycatModel {
                static BlockState getMaterial(Object data) {
                    return null;
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyClientRenderSoundCalls(source))
    }

    @Test
    fun `filtered manager forwarding remains unchanged pending provider closure`() {
        val source = """
            package example;

            import java.util.function.Predicate;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockAndTintGetter;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.client.model.data.ModelDataManager;
            import org.jetbrains.annotations.Nullable;

            final class FilteredBlockAndTintGetter implements BlockAndTintGetter {
                private final BlockAndTintGetter wrapped;
                private final Predicate<BlockPos> filter;

                FilteredBlockAndTintGetter(
                        BlockAndTintGetter wrapped,
                        Predicate<BlockPos> filter) {
                    this.wrapped = wrapped;
                    this.filter = filter;
                }

                @Override
                public BlockEntity getBlockEntity(BlockPos pPos) {
                    return filter.test(pPos) ? wrapped.getBlockEntity(pPos) : null;
                }

                @Override
                public @Nullable ModelDataManager getModelDataManager() {
                    return wrapped.getModelDataManager();
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyClientRenderSoundCalls(source))
    }

    @Test
    fun `holder sound and repeated subtitle use their exact source values`() {
        val source = """
            package example;

            import net.minecraft.client.gui.components.SubtitleOverlay;
            import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
            import net.minecraft.client.resources.sounds.SoundInstance;
            import net.minecraft.client.sounds.WeighedSoundEvents;
            import net.minecraft.core.Holder;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.sounds.SoundSource;
            import java.util.function.Supplier;

            final class Sounds {
                static final SoundEntryBuilder EXPLOSION =
                    create("explosion").subtitle("Explosion")
                        .playExisting(SoundEvents.GENERIC_EXPLODE, .1f, 1.1f);
                static final SoundEntryBuilder EAT =
                    create("eat").playExisting(SoundEvents.GENERIC_EAT, .1f, 1.1f);
                static final SoundEntryBuilder SINGLE =
                    create("single").playExisting(SoundEvents.GENERIC_EXPLODE);

                static SoundEntryBuilder create(String name) {
                    return new SoundEntryBuilder();
                }

                static final class SoundEntryBuilder {
                    SoundEntryBuilder subtitle(String text) {
                        return this;
                    }

                    SoundEntryBuilder playExisting(
                            Supplier<SoundEvent> sound,
                            float volume,
                            float pitch) {
                        return this;
                    }

                    SoundEntryBuilder playExisting(
                            SoundEvent sound,
                            float volume,
                            float pitch) {
                        return this;
                    }

                    SoundEntryBuilder playExisting(Holder<SoundEvent> sound) {
                        return this;
                    }
                }

                static final class LoopingSound
                        extends AbstractTickableSoundInstance {
                    private static final SubtitleOverlay OVERLAY = null;
                    private final WeighedSoundEvents weighedSoundEvents = null;

                    LoopingSound(SoundEvent sound, SoundSource source) {
                        super(sound, source, SoundInstance.createUnseededRandom());
                    }

                    @Override
                    public void tick() {
                        OVERLAY.onPlaySound(this, weighedSoundEvents);
                    }

                    void shadowed(
                            SubtitleOverlay OVERLAY,
                            WeighedSoundEvents weighedSoundEvents) {
                        OVERLAY.onPlaySound(this, weighedSoundEvents);
                    }
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyClientRenderSoundCalls(source)
        val compact = migrated.replace(Regex("""\s+"""), " ")

        assertTrue(
            compact.contains(
                "playExisting(SoundEvents.GENERIC_EXPLODE.value(), .1f, 1.1f)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "playExisting(SoundEvents.GENERIC_EAT, .1f, 1.1f)"
            ),
            migrated
        )
        assertTrue(
            compact.contains("playExisting(SoundEvents.GENERIC_EXPLODE);"),
            migrated
        )
        assertTrue(
            compact.contains(
                "OVERLAY.onPlaySound(this, weighedSoundEvents, " +
                    "java.lang.Float.POSITIVE_INFINITY);"
            ),
            migrated
        )
        assertEquals(
            1,
            Regex("""OVERLAY\.onPlaySound\(this, weighedSoundEvents\);""")
                .findAll(compact)
                .count(),
            migrated
        )
        assertEquals(migrated, pass.migrateLegacyClientRenderSoundCalls(migrated))
    }

    @Test
    fun `subtitle replay keeps unlimited range without resolving sound volume or relativity`() {
        val source = """
            package example;

            import java.util.Objects;
            import net.minecraft.client.gui.components.SubtitleOverlay;
            import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
            import net.minecraft.client.resources.sounds.SoundInstance;
            import net.minecraft.client.sounds.WeighedSoundEvents;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundSource;

            final class NullSoundLoop extends AbstractTickableSoundInstance {
                private static final SubtitleOverlay OVERLAY = null;
                private final WeighedSoundEvents weighedSoundEvents;
                private final Object sound = null;

                NullSoundLoop(
                        SoundEvent soundEvent,
                        SoundSource source,
                        WeighedSoundEvents weighedSoundEvents) {
                    super(soundEvent, source, SoundInstance.createUnseededRandom());
                    this.weighedSoundEvents =
                        Objects.requireNonNull(weighedSoundEvents);
                }

                void replayMutedRelative() {
                    this.volume = 0.0f;
                    this.relative = true;
                    OVERLAY.onPlaySound(this, weighedSoundEvents);
                }

                void replayLoudAbsolute() {
                    this.volume = 8.0f;
                    this.relative = false;
                    OVERLAY.onPlaySound(this, weighedSoundEvents);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyClientRenderSoundCalls(source)
        val compact = migrated.replace(Regex("""\s+"""), " ")

        assertEquals(
            2,
            Regex(
                """OVERLAY\.onPlaySound\(this, weighedSoundEvents, """ +
                    """java\.lang\.Float\.POSITIVE_INFINITY\);"""
            ).findAll(compact).count(),
            migrated
        )
        assertEquals(false, compact.contains("this.sound."))
        assertEquals(false, compact.contains("this.volume,"))
        assertEquals(false, compact.contains("this.relative,"))
        assertEquals(migrated, pass.migrateLegacyClientRenderSoundCalls(migrated))
    }

    @Test
    fun `lookalike receivers overloads and subtitle owners remain unchanged`() {
        val source = """
            package example;

            import net.minecraft.client.gui.components.SubtitleOverlay;
            import net.minecraft.client.sounds.WeighedSoundEvents;
            import net.minecraft.core.BlockPos;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.neoforged.neoforge.client.model.data.ModelDataManager;
            import java.util.function.Supplier;

            final class LookalikeModel {
                void read(Helper level, BlockPos pos) {
                    ModelDataManager manager = level.getModelDataManager();
                    if (manager == null)
                        return;
                    use(manager.getAt(pos));
                }

                void use(Object value) {
                }
            }

            final class Helper {
                ModelDataManager getModelDataManager() {
                    return null;
                }
            }

            final class LookalikeBuilder {
                void build() {
                    playExisting(SoundEvents.GENERIC_EXPLODE, .1f, 1.1f);
                }

                void playExisting(Object sound, float volume, float pitch) {
                }
            }

            final class ForeignOwner {
                static final ForeignBuilder VALUE =
                    foreign().playExisting(
                        SoundEvents.GENERIC_EXPLODE, .1f, 1.1f);

                static ForeignBuilder foreign() {
                    return new ForeignBuilder();
                }

                static final class ForeignBuilder {
                    ForeignBuilder playExisting(
                            Supplier<SoundEvent> sound,
                            float volume,
                            float pitch) {
                        return this;
                    }

                    ForeignBuilder playExisting(
                            SoundEvent sound,
                            float volume,
                            float pitch) {
                        return this;
                    }
                }
            }

            final class ScopedFactoryOwner {
                static final External.SoundEntryBuilder VALUE =
                    External.create("explosion").playExisting(
                        SoundEvents.GENERIC_EXPLODE, .1f, 1.1f);
            }

            final class External {
                static SoundEntryBuilder create(String name) {
                    return new SoundEntryBuilder();
                }

                static final class SoundEntryBuilder {
                    SoundEntryBuilder playExisting(
                            Supplier<SoundEvent> sound,
                            float volume,
                            float pitch) {
                        return this;
                    }

                    SoundEntryBuilder playExisting(
                            SoundEvent sound,
                            float volume,
                            float pitch) {
                        return this;
                    }
                }
            }

            final class AmbiguousSounds {
                static final SoundEntryBuilder VALUE =
                    create("explosion").playExisting(
                        SoundEvents.GENERIC_EXPLODE, .1f, 1.1f);

                static SoundEntryBuilder create(String name) {
                    return new SoundEntryBuilder();
                }

                static final class SoundEntryBuilder {
                    SoundEntryBuilder playExisting(
                            Supplier<SoundEvent> sound,
                            float volume,
                            float pitch) {
                        return this;
                    }

                    SoundEntryBuilder playExisting(
                            SoundEvent sound,
                            float volume,
                            float pitch) {
                        return this;
                    }

                    SoundEntryBuilder playExisting(
                            Object sound,
                            float volume,
                            float pitch) {
                        return this;
                    }
                }
            }

            final class LookalikeLoop {
                private static final SubtitleOverlay OVERLAY = null;
                private final WeighedSoundEvents weighedSoundEvents = null;

                void tick() {
                    OVERLAY.onPlaySound(this, weighedSoundEvents);
                }
            }

            final class LambdaShadowLoop
                    extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
                private static final SubtitleOverlay OVERLAY = null;
                private final WeighedSoundEvents weighedSoundEvents = null;

                LambdaShadowLoop(
                        SoundEvent sound,
                        net.minecraft.sounds.SoundSource source) {
                    super(
                        sound,
                        source,
                        net.minecraft.client.resources.sounds.SoundInstance
                            .createUnseededRandom());
                }

                public void tick() {
                    java.util.function.BiConsumer<
                            SubtitleOverlay,
                            WeighedSoundEvents> replay =
                        (OVERLAY, weighedSoundEvents) ->
                            OVERLAY.onPlaySound(this, weighedSoundEvents);
                }
            }

            final class QualifiedOuter
                    extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
                QualifiedOuter(
                        SoundEvent sound,
                        net.minecraft.sounds.SoundSource source) {
                    super(
                        sound,
                        source,
                        net.minecraft.client.resources.sounds.SoundInstance
                            .createUnseededRandom());
                }

                public void tick() {
                }

                final class InnerLoop
                        extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
                    private final SubtitleOverlay OVERLAY = null;
                    private final WeighedSoundEvents weighedSoundEvents = null;

                    InnerLoop(
                            SoundEvent sound,
                            net.minecraft.sounds.SoundSource source) {
                        super(
                            sound,
                            source,
                            net.minecraft.client.resources.sounds.SoundInstance
                                .createUnseededRandom());
                    }

                    public void tick() {
                        OVERLAY.onPlaySound(
                            QualifiedOuter.this,
                            weighedSoundEvents);
                    }
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyClientRenderSoundCalls(source))
    }
}
