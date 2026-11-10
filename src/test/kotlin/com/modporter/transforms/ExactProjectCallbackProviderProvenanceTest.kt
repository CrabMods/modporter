package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.modporter.core.transforms.structural.LegacyNbtProviderMethodMigration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExactProjectCallbackProviderProvenanceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `renamed callback with reordered parameters selects its second world origin`() {
        val file = writeSource(
            defaultRelay(),
            """
            void place(Level decoy, LivingEntity actor, Level owning, BlockPos pos, CompoundTag tag) {
                dispatchLocated(pos, unit -> unit.readInventory(tag), owning);
            }
            """.trimIndent()
        )
        val original = file.readText()

        val dryRun = LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = true)

        assertTrue(dryRun.isNotEmpty())
        assertEquals(original, file.readText())

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("unit.readInventory(tag, owning.registryAccess())"), migrated)
        assertFalse(migrated.contains("decoy.registryAccess()"), migrated)
        assertFalse(migrated.contains("actor.registryAccess()"), migrated)
        assertTrue(JavaParser().parse(migrated).isSuccessful, migrated)
        assertTrue(LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false).isEmpty())
        assertEquals(migrated, file.readText())
    }

    @Test
    fun `direct callback delivery selects the lookup argument without wrapper name rules`() {
        val relay = """
            interface Relay<T extends BlockEntity> {
                @SuppressWarnings("unchecked")
                default void route(BlockPos marker, BlockGetter lookup, Consumer<T> sink) {
                    sink.accept((T) lookup.getBlockEntity(marker));
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place(Level source, LivingEntity observer, BlockPos pos, CompoundTag tag) {
                route(pos, source, unit -> unit.readInventory(tag));
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("unit.readInventory(tag, source.registryAccess())"), migrated)
        assertFalse(migrated.contains("observer.registryAccess()"), migrated)
    }

    @Test
    fun `callback delivered from two worlds refuses without writing`() {
        val relay = """
            interface Relay<T extends BlockEntity> {
                @SuppressWarnings("unchecked")
                default void route(BlockGetter first, BlockGetter second, BlockPos pos, boolean chooseFirst,
                        Consumer<T> sink) {
                    sink.accept((T) (chooseFirst ? first.getBlockEntity(pos) : second.getBlockEntity(pos)));
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place(Level first, Level second, BlockPos pos, CompoundTag tag, boolean chooseFirst) {
                route(first, second, pos, chooseFirst, unit -> unit.readInventory(tag));
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("Unsafe receiver registry provider provenance"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `reassigned callback receiver refuses its original world`() {
        val file = writeSource(
            defaultRelay(),
            """
            void place(Level original, Level replacement, BlockPos pos, CompoundTag tag) {
                dispatchLocated(pos, unit -> {
                    unit = (DataEntity) replacement.getBlockEntity(pos);
                    unit.readInventory(tag);
                }, original);
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("reassigned"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `escaped callback refuses typed world fallback`() {
        val relay = """
            interface Relay<T extends BlockEntity> {
                Consumer<T> deferred();
                void deferred(Consumer<T> sink);
                default void route(BlockGetter lookup, BlockPos pos, Consumer<T> sink) {
                    deferred(sink);
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            private Consumer<DataEntity> deferred;
            public Consumer<DataEntity> deferred() { return deferred; }
            public void deferred(Consumer<DataEntity> sink) { deferred = sink; }
            void place(Level source, BlockPos pos, CompoundTag tag) {
                route(source, pos, unit -> unit.readInventory(tag));
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("escapes exact invocation"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `BlockGetter origin without registry access refuses`() {
        val file = writeSource(
            defaultRelay(),
            """
            void place(BlockGetter source, BlockPos pos, CompoundTag tag) {
                dispatchLocated(pos, unit -> unit.readInventory(tag), source);
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("no exact registry provider"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `two explicit providers remain ambiguous before callback provenance`() {
        val file = writeSource(
            defaultRelay(),
            """
            void place(Level source, BlockPos pos, CompoundTag tag,
                    HolderLookup.Provider first, RegistryAccess second) {
                dispatchLocated(pos, unit -> unit.readInventory(tag), source);
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("Ambiguous direct registry provider expressions"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `overridden callback that does not deliver a source value refuses`() {
        val file = writeSource(
            defaultRelay(),
            """
            private Consumer<DataEntity> deferred;
            @Override
            public void dispatchLocated(BlockPos marker, Consumer<DataEntity> receiver, BlockGetter lookup) {
                deferred = receiver;
            }
            void place(Level source, BlockPos pos, CompoundTag tag) {
                dispatchLocated(pos, unit -> unit.readInventory(tag), source);
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("escapes exact invocation"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `IBE shaped helper chain selects the exact world and ignores nullable entity candidates`() {
        val relay = """
            interface Relay<T extends BlockEntity> {
                Class<T> getBlockEntityClass();
                default void withBlockEntityDo(BlockGetter world, BlockPos pos, Consumer<T> action) {
                    getBlockEntityOptional(world, pos).ifPresent(action);
                }
                default Optional<T> getBlockEntityOptional(BlockGetter world, BlockPos pos) {
                    return Optional.ofNullable(getBlockEntity(world, pos));
                }
                @SuppressWarnings("unchecked")
                default T getBlockEntity(BlockGetter worldIn, BlockPos pos) {
                    BlockEntity blockEntity = worldIn.getBlockEntity(pos);
                    Class<T> expectedClass = getBlockEntityClass();
                    if (blockEntity == null)
                        return null;
                    if (!expectedClass.isInstance(blockEntity))
                        return null;
                    return (T) blockEntity;
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            public Class<DataEntity> getBlockEntityClass() { return DataEntity.class; }
            void place(Level worldIn, LivingEntity placer, BlockPos pos, CompoundTag tag) {
                withBlockEntityDo(worldIn, pos, unit -> unit.readInventory(tag));
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("unit.readInventory(tag, worldIn.registryAccess())"), migrated)
        assertFalse(migrated.contains("placer.registryAccess()"), migrated)
    }

    @Test
    fun `reassigned lookup formal refuses the original callback origin`() {
        val relay = """
            interface Relay<T extends BlockEntity> {
                @SuppressWarnings("unchecked")
                default void route(BlockGetter original, BlockGetter replacement, BlockPos pos, Consumer<T> sink) {
                    original = replacement;
                    sink.accept((T) original.getBlockEntity(pos));
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place(Level original, Level replacement, BlockPos pos, CompoundTag tag) {
                route(original, replacement, pos, unit -> unit.readInventory(tag));
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("origin formal 'original' is reassigned"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `callback invoked only from a nested lambda is treated as deferred`() {
        val relay = """
            interface Relay<T extends BlockEntity> {
                @SuppressWarnings("unchecked")
                default void route(BlockGetter lookup, BlockPos pos, Consumer<T> sink) {
                    Runnable deferred = () -> sink.accept((T) lookup.getBlockEntity(pos));
                    deferred.run();
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place(Level source, BlockPos pos, CompoundTag tag) {
                route(source, pos, unit -> unit.readInventory(tag));
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("deferred through a nested lambda"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `project Optional lookalike cannot impersonate the JDK delivery contract`() {
        val relay = """
            interface Relay<T extends BlockEntity> {
                final class Optional<U> {
                    private final U value;
                    private Optional(U value) { this.value = value; }
                    static <U> Optional<U> ofNullable(U value) { return new Optional<>(value); }
                    void ifPresent(Consumer<U> sink) { sink.accept(value); }
                }
                @SuppressWarnings("unchecked")
                default void route(BlockGetter lookup, BlockPos pos, Consumer<T> sink) {
                    Optional.ofNullable((T) lookup.getBlockEntity(pos)).ifPresent(sink);
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place(Level source, BlockPos pos, CompoundTag tag) {
                route(source, pos, unit -> unit.readInventory(tag));
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("not a typed Optional contract"), error.message)
        assertEquals(original, file.readText())
    }
    @Test
    fun `non BlockEntity collection lambda preserves unique outer world fallback`() {
        val relay = defaultRelay() + "\n\n" + """

            class CodecValue {
                void readInventory(CompoundTag tag, HolderLookup.Provider registries) {
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place(Level source, java.util.List<CodecValue> codecs, CompoundTag tag) {
                codecs.forEach(codec -> codec.readInventory(tag));
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(
            migrated.contains("codec.readInventory(tag, source.registryAccess())"),
            migrated
        )
    }

    @Test
    fun `unknown concrete superclass blocks interface callback dispatch without fallback`() {
        val relay = """
            class ReturnEntity extends BlockEntity {
                CompoundTag readInventory(HolderLookup.Provider registries) {
                    return new CompoundTag();
                }
            }
            interface PrimitiveRelay<T extends BlockEntity> {
                default void dispatchLocated(int marker, Consumer<T> receiver, int lookup) {
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place() {
                dispatchLocated(0, unit -> unit.readInventory(), 0);
            }
            """.trimIndent(),
            fixtureDeclaration = "class CallbackFixture extends MissingBase implements PrimitiveRelay<ReturnEntity>"
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("callback dispatch"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `competing inherited callback declarations refuse unique world fallback`() {
        val relay = defaultRelay() + "\n\n" + """

            interface AlternateRelay<T extends BlockEntity> {
                @SuppressWarnings("unchecked")
                default void dispatchLocated(BlockPos marker, Consumer<T> receiver, BlockGetter lookup) {
                    receiver.accept((T) lookup.getBlockEntity(marker));
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place(Level source, BlockPos pos, CompoundTag tag) {
                dispatchLocated(pos, unit -> unit.readInventory(tag), source);
            }
            """.trimIndent(),
            fixtureDeclaration =
                "class CallbackFixture implements Relay<DataEntity>, AlternateRelay<DataEntity>"
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("callback dispatch"), error.message)
        assertEquals(original, file.readText())
    }

    @Test
    fun `recursive callback value helper fails closed without overflowing`() {
        val relay = """
            interface Relay<T extends BlockEntity> {
                default void route(BlockGetter lookup, BlockPos pos, Consumer<T> sink) {
                    findLocated(lookup, pos).ifPresent(sink);
                }
                default Optional<T> findLocated(BlockGetter lookup, BlockPos pos) {
                    return findLocated(lookup, pos);
                }
            }
        """.trimIndent()
        val file = writeSource(
            relay,
            """
            void place(Level source, BlockPos pos, CompoundTag tag) {
                route(source, pos, unit -> unit.readInventory(tag));
            }
            """.trimIndent()
        )
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("helper cycle is unresolved"), error.message)
        assertEquals(original, file.readText())
    }
    private fun defaultRelay(): String = """
        interface Relay<T extends BlockEntity> {
            default void dispatchLocated(BlockPos marker, Consumer<T> receiver, BlockGetter lookup) {
                findLocated(marker, lookup).ifPresent(receiver);
            }
            default Optional<T> findLocated(BlockPos marker, BlockGetter lookup) {
                return Optional.ofNullable(readLocated(marker, lookup));
            }
            @SuppressWarnings("unchecked")
            default T readLocated(BlockPos marker, BlockGetter lookup) {
                BlockEntity found = lookup.getBlockEntity(marker);
                if (found == null)
                    return null;
                return (T) found;
            }
        }
    """.trimIndent()

    private fun writeSource(
        relay: String,
        fixtureBody: String,
        fixtureDeclaration: String = "class CallbackFixture implements Relay<DataEntity>"
    ): Path {
        val file = tempDir.resolve("src/main/java/sample/CallbackFixture.java")
        file.parent.createDirectories()
        file.writeText(
            """
            package sample;

            import java.util.Optional;
            import java.util.function.Consumer;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.core.RegistryAccess;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;

            $relay

            class DataEntity extends BlockEntity {
                void readInventory(CompoundTag tag, HolderLookup.Provider registries) {
                }
            }

            $fixtureDeclaration {
                $fixtureBody
            }
            """.trimIndent()
        )
        return file
    }
}
