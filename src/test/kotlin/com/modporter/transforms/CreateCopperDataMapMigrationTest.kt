package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateCopperDataMapMigration
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateCopperDataMapMigrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `migrates the closed copper graph to native data maps and is idempotent`() {
        val fixture = fixture(directory)

        val result = CreateCopperDataMapMigration.migrate(directory, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(5, result.changes.size)

        val registries = fixture.registries.readText()
        assertTrue(
            compact(registries).contains(
                "Map<Holder<Block>,Holder<Block>> WEATHERING=HashBiMap.create()"
            ),
            registries
        )
        assertTrue(registries.contains("Collections.unmodifiableMap(WEATHERING)"), registries)
        assertFalse(registries.contains("WeatheringCopper.NEXT_BY_BLOCK"), registries)
        assertFalse(registries.contains("HoneycombItem.WAXABLES"), registries)
        assertFalse(registries.contains("java.lang.reflect.Field"), registries)

        val blockSet = fixture.blockSet.readText()
        assertTrue(
            compact(blockSet).contains(
                "CopperRegistries.addWaxable(entries[getIndex(state,false)],entry)"
            ),
            blockSet
        )
        assertTrue(
            compact(blockSet).contains(
                "CopperRegistries.addWeathering(" +
                    "entries[getIndex(WEATHER_STATES[state.ordinal() - 1],false)],entry)"
            ),
            blockSet
        )
        assertFalse(blockSet.contains("() -> entries["), blockSet)
        assertFalse(blockSet.contains("() -> entry.get()"), blockSet)

        val create = fixture.create.readText()
        assertFalse(create.contains("CopperRegistries"), create)

        val datagen = fixture.datagen.readText()
        assertTrue(
            datagen.contains(
                "import com.simibubi.create.foundation.data.CreateDatamapProvider;"
            ),
            datagen
        )
        assertTrue(
            compact(datagen).contains(
                "generator.addProvider(event.includeServer()," +
                    "new CreateDatamapProvider(output,lookupProvider))"
            ),
            datagen
        )
        assertTrue(
            datagen.indexOf("CreateSequencedAssemblyRecipeGen") <
                datagen.indexOf("new CreateDatamapProvider"),
            datagen
        )

        val provider = fixture.provider.readText()
        assertTrue(provider.contains("NeoForgeDataMaps.OXIDIZABLES"), provider)
        assertTrue(provider.contains("NeoForgeDataMaps.WAXABLES"), provider)
        assertTrue(
            provider.contains(
                "CopperRegistries.getWeatheringView().forEach"
            ),
            provider
        )
        assertTrue(
            provider.contains("new Oxidizable(after.value())"),
            provider
        )
        assertTrue(provider.contains("b.add(now, after, false);"), provider)

        val second = CreateCopperDataMapMigration.migrate(directory, dryRun = false)
        assertTrue(second.gates.isEmpty(), second.gates.joinToString())
        assertTrue(second.changes.isEmpty(), second.changes.joinToString())
    }

    @Test
    fun `unknown copper registry reference blocks every family write`() {
        val fixture = fixture(directory)
        val extra = directory.resolve(
            "src/main/java/com/simibubi/create/UnexpectedCopperCaller.java"
        )
        extra.parent.createDirectories()
        extra.writeText(
            """
            package com.simibubi.create;

            import com.simibubi.create.foundation.block.CopperRegistries;

            public class UnexpectedCopperCaller {
                public void call() {
                    CopperRegistries.inject();
                }
            }
            """.trimIndent() + "\n"
        )
        val before = fixture.snapshots()

        val result = CreateCopperDataMapMigration.migrate(directory, dryRun = false)

        assertTrue(
            result.gates.any { it.contains("Unexpected CopperRegistries source reference") },
            result.gates.joinToString()
        )
        assertEquals(before, fixture.snapshots())
        assertFalse(fixture.provider.exists())
    }

    @Test
    fun `migrates the proved constructor body after it moves into onCtor`() {
        val fixture = fixture(directory, injectInOnCtor = true)

        val result = CreateCopperDataMapMigration.migrate(directory, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(5, result.changes.size)
        assertFalse(fixture.create.readText().contains("CopperRegistries"))
        val second = CreateCopperDataMapMigration.migrate(directory, dryRun = false)
        assertTrue(second.gates.isEmpty(), second.gates.joinToString())
        assertTrue(second.changes.isEmpty(), second.changes.joinToString())
    }

    @Test
    fun `incompatible provider ownership blocks every family write`() {
        val fixture = fixture(directory)
        fixture.provider.parent.createDirectories()
        fixture.provider.writeText(
            """
            package com.simibubi.create.foundation.data;
            public class ForeignProvider {}
            """.trimIndent() + "\n"
        )
        val before = fixture.snapshots()

        val result = CreateCopperDataMapMigration.migrate(directory, dryRun = false)

        assertTrue(
            result.gates.any { it.contains("owned by incompatible source") },
            result.gates.joinToString()
        )
        assertEquals(before, fixture.snapshots())
    }

    @Test
    fun `transaction rollback restores all preimages and removes the generated provider`() {
        val fixture = fixture(directory)
        val before = fixture.snapshots()

        val result = CreateCopperDataMapMigration.migrate(
            directory,
            dryRun = false
        ) { index, _ ->
            if (index == 2) throw IOException("forced copper data-map commit failure")
        }

        assertTrue(
            result.gates.any { it.contains("forced copper data-map commit failure") },
            result.gates.joinToString()
        )
        assertEquals(before, fixture.snapshots())
        assertFalse(fixture.provider.exists())
    }

    private fun fixture(root: Path, injectInOnCtor: Boolean = false): Fixture {
        val registries = root.resolve(
            "src/main/java/com/simibubi/create/foundation/block/CopperRegistries.java"
        )
        val blockSet = root.resolve(
            "src/main/java/com/simibubi/create/foundation/block/CopperBlockSet.java"
        )
        val create = root.resolve("src/main/java/com/simibubi/create/Create.java")
        val datagen = root.resolve(
            "src/main/java/com/simibubi/create/infrastructure/data/CreateDatagen.java"
        )
        val provider = root.resolve(
            "src/main/java/com/simibubi/create/foundation/data/CreateDatamapProvider.java"
        )
        registries.parent.createDirectories()
        blockSet.parent.createDirectories()
        create.parent.createDirectories()
        datagen.parent.createDirectories()

        registries.writeText(legacyRegistries())
        blockSet.writeText(
            """
            package com.simibubi.create.foundation.block;

            public class CopperBlockSet {
                private static final WeatherState[] WEATHER_STATES = WeatherState.values();

                private int getIndex(WeatherState state, boolean waxed) {
                    return state.ordinal() + (waxed ? 4 : 0);
                }

                void register(
                    boolean waxed,
                    WeatherState state,
                    BlockEntry<?>[] entries,
                    BlockEntry<?> entry
                ) {
                    if (waxed) {
                        CopperRegistries.addWaxable(
                            () -> entries[getIndex(state, false)].get(),
                            () -> entry.get()
                        );
                    } else if (state != WeatherState.UNAFFECTED) {
                        CopperRegistries.addWeathering(
                            () -> entries[getIndex(
                                WEATHER_STATES[state.ordinal() - 1],
                                false
                            )].get(),
                            () -> entry.get()
                        );
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        create.writeText(
            if (injectInOnCtor) {
                """
                package com.simibubi.create;

                import com.simibubi.create.foundation.block.CopperRegistries;

                public class Create {
                    public Create() {
                        before();
                        onCtor();
                        after();
                    }

                    public static void onCtor() {
                        CopperRegistries.inject();
                    }

                    private void before() {}
                    private void after() {}
                }
                """.trimIndent() + "\n"
            } else {
                """
                package com.simibubi.create;

                import com.simibubi.create.foundation.block.CopperRegistries;

                public class Create {
                    public Create() {
                        before();
                        CopperRegistries.inject();
                        after();
                    }

                    private void before() {}
                    private void after() {}
                }
                """.trimIndent() + "\n"
            }
        )
        datagen.writeText(
            """
            package com.simibubi.create.infrastructure.data;

            import com.simibubi.create.foundation.data.recipe.CreateSequencedAssemblyRecipeGen;

            public class CreateDatagen {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput output = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> lookupProvider =
                        event.getLookupProvider();
                    generator.addProvider(
                        event.includeServer(),
                        new CreateSequencedAssemblyRecipeGen(output, lookupProvider)
                    );
                    generator.addProvider(
                        event.includeServer(),
                        new VanillaHatOffsetGenerator(output)
                    );
                }
            }
            """.trimIndent() + "\n"
        )
        return Fixture(registries, blockSet, create, datagen, provider)
    }

    private fun legacyRegistries(): String = """
        package com.simibubi.create.foundation.block;

        import java.lang.reflect.Field;
        import java.util.function.BiConsumer;
        import java.util.function.Supplier;

        import org.jetbrains.annotations.Nullable;
        import org.slf4j.Logger;

        import com.google.common.base.Suppliers;
        import com.google.common.collect.BiMap;
        import com.google.common.collect.HashBiMap;
        import com.google.common.collect.ImmutableBiMap;
        import com.simibubi.create.Create;

        import net.minecraft.world.item.HoneycombItem;
        import net.minecraft.world.level.block.Block;
        import net.minecraft.world.level.block.WeatheringCopper;

        public class CopperRegistries {
            private static final BiMap<Supplier<Block>, Supplier<Block>> WEATHERING =
                HashBiMap.create();
            private static final BiMap<Supplier<Block>, Supplier<Block>> WAXABLE =
                HashBiMap.create();

            private static boolean injected;
            private static boolean weatheringMemoized;
            private static boolean waxableMemoized;

            public static synchronized void addWeathering(
                Supplier<Block> original,
                Supplier<Block> weathered
            ) {
                if (weatheringMemoized) {
                    throw new IllegalStateException(
                        "Cannot add weathering entry to CopperRegistries after memoization!"
                    );
                }
                WEATHERING.put(original, weathered);
            }

            public static synchronized void addWaxable(
                Supplier<Block> original,
                Supplier<Block> waxed
            ) {
                if (waxableMemoized) {
                    throw new IllegalStateException(
                        "Cannot add waxable entry to CopperRegistries after memoization!"
                    );
                }
                WAXABLE.put(original, waxed);
            }

            public static void inject() {
                if (injected) {
                    throw new IllegalStateException("Cannot inject CopperRegistries twice!");
                }
                injected = true;

                try {
                    Field delegateField =
                        WeatheringCopper.NEXT_BY_BLOCK.getClass().getDeclaredField("delegate");
                    delegateField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Supplier<BiMap<Block, Block>> originalWeatheringMapDelegate =
                        (Supplier<BiMap<Block, Block>>) delegateField.get(
                            WeatheringCopper.NEXT_BY_BLOCK
                        );
                    com.google.common.base.Supplier<BiMap<Block, Block>>
                        weatheringMapDelegate = () -> {
                            weatheringMemoized = true;
                            ImmutableBiMap.Builder<Block, Block> builder =
                                ImmutableBiMap.builder();
                            builder.putAll(originalWeatheringMapDelegate.get());
                            ErrorHandlingBiConsumer<Supplier<Block>, Supplier<Block>>
                                consumer = new ErrorHandlingBiConsumer<>(
                                     (original, weathered) -> {
                                         builder.put(
                                             original.get(),
                                             weathered.get()
                                         );
                                     }
                                );
                            WEATHERING.forEach(consumer);
                            consumer.reportExceptions(Create.LOGGER, "weathering");
                            return builder.build();
                        };
                    delegateField.set(
                        WeatheringCopper.NEXT_BY_BLOCK,
                        weatheringMapDelegate
                    );
                } catch (Exception e) {
                    Create.LOGGER.error(
                        "Failed to inject weathering copper from CopperRegistries",
                        e
                    );
                }

                Supplier<BiMap<Block, Block>> originalWaxableMapSupplier =
                    HoneycombItem.WAXABLES;
                Supplier<BiMap<Block, Block>> waxableMapSupplier =
                    Suppliers.memoize(() -> {
                        waxableMemoized = true;
                        ImmutableBiMap.Builder<Block, Block> builder =
                            ImmutableBiMap.builder();
                        builder.putAll(originalWaxableMapSupplier.get());
                        ErrorHandlingBiConsumer<Supplier<Block>, Supplier<Block>>
                            consumer = new ErrorHandlingBiConsumer<>(
                                     (original, waxed) -> {
                                         builder.put(
                                             original.get(),
                                             waxed.get()
                                         );
                                     }
                            );
                        WAXABLE.forEach(consumer);
                        consumer.reportExceptions(Create.LOGGER, "waxable");
                        return builder.build();
                    });
                HoneycombItem.WAXABLES = waxableMapSupplier;
            }

            private static class ErrorHandlingBiConsumer<T, U>
                implements BiConsumer<T, U> {
                private final BiConsumer<T, U> delegate;
                private int exceptionCount = 0;
                @Nullable
                private Throwable firstException;

                public ErrorHandlingBiConsumer(BiConsumer<T, U> delegate) {
                    this.delegate = delegate;
                }

                @Override
                public void accept(T t, U u) {
                    try {
                        delegate.accept(t, u);
                    } catch (Throwable throwable) {
                        exceptionCount++;
                        if (firstException == null) {
                            firstException = throwable;
                        }
                    }
                }

                public void reportExceptions(Logger logger, String type) {
                    if (exceptionCount != 0) {
                        logger.error(
                             "Adding " + type +
                                 " copper entries from CopperRegistries encountered " +
                                 exceptionCount + " exception(s)!"
                        );
                        if (firstException != null) {
                            logger.error(
                                "The first exception that was thrown is logged below.",
                                firstException
                            );
                        }
                    }
                }
            }
        }
    """.trimIndent() + "\n"

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*,\s*"""), ",")
            .replace(Regex("""\s*=\s*"""), "=")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()

    private data class Fixture(
        val registries: Path,
        val blockSet: Path,
        val create: Path,
        val datagen: Path,
        val provider: Path
    ) {
        fun snapshots(): Map<Path, String> = listOf(
            registries,
            blockSet,
            create,
            datagen
        ).associateWith { it.readText() } +
            (if (provider.exists()) mapOf(provider to provider.readText()) else emptyMap())
    }
}
