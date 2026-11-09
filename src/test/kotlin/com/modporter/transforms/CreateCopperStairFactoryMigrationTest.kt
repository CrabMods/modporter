package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateCopperStairFactoryMigration
import com.modporter.core.transforms.structural.StructuralRefactorPass
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

class CreateCopperStairFactoryMigrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `migrates the closed lazy supplier family with base-state codecs and idempotence`() {
        val fixture = fixture(directory, includeReflection = true)

        val result = migrate(directory)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(3, result.changes.size)
        val copper = fixture.copper.readText()
        val compactCopper = compact(copper)
        assertTrue(
            compactCopper.contains(
                "Supplier<BlockState> defaultStateSupplier = " +
                    "() -> blocks.get(parent,state,waxed).getDefaultState();"
            ),
            copper
        )
        assertTrue(
            compactCopper.contains(
                "return p -> new CreateCopperStairBlock(defaultStateSupplier,p);"
            ),
            copper
        )
        assertTrue(
            compactCopper.contains(
                "return p -> new CreateWeatheringCopperStairBlock(" +
                    "state,defaultStateSupplier,p);"
            ),
            copper
        )
        assertFalse(copper.contains("defaultStateSupplier.get()"), copper)
        assertFalse(copper.contains("ObfuscationReflectionHelper"), copper)
        assertFalse(copper.contains("new WeatheringCopperStairBlock("), copper)
        assertTrue(copper.contains("BlockState"), copper)

        val stair = fixture.stair.readText()
        val compactStair = compact(stair)
        assertTrue(
            compactStair.contains(
                "MapCodec<CreateCopperStairBlock> CODEC = " +
                    "RecordCodecBuilder.mapCodec"
            ),
            stair
        )
        assertTrue(stair.contains("\"base_state\""), stair)
        assertTrue(
            compactStair.contains(
                "forGetter(CreateCopperStairBlock::getModelState)"
            ),
            stair
        )
        assertTrue(
            compactStair.contains(
                "new CreateCopperStairBlock(() -> baseState,properties)"
            ),
            stair
        )
        assertTrue(
            compactStair.contains(
                "Supplier<BlockState> modelStateSupplier"
            ),
            stair
        )
        assertTrue(
            compactStair.contains(
                "return getModelState().getBlock().getExplosionResistance();"
            ),
            stair
        )
        assertTrue(
            compactStair.contains(
                "MapCodec<? extends StairBlock> codec()"
            ),
            stair
        )
        assertFalse(stair.contains("return explosionResistance"), stair)

        val weathering = fixture.weathering.readText()
        val compactWeathering = compact(weathering)
        assertTrue(weathering.contains("\"weathering_state\""), weathering)
        assertTrue(weathering.contains("\"base_state\""), weathering)
        assertTrue(
            compactWeathering.contains(
                "new CreateWeatheringCopperStairBlock(" +
                    "weatherState,() -> baseState,properties)"
            ),
            weathering
        )
        assertTrue(
            compactWeathering.contains(
                "WeatherState weatherState,Supplier<BlockState> modelStateSupplier," +
                    "Properties properties"
            ),
            weathering
        )
        assertTrue(
            compactWeathering.contains(
                "return getModelState().getBlock().getExplosionResistance();"
            ),
            weathering
        )
        assertTrue(
            compactWeathering.contains(
                "MapCodec<WeatheringCopperStairBlock> codec()"
            ),
            weathering
        )
        assertTrue(
            compactWeathering.contains(
                "((CreateWeatheringCopperStairBlock) block).getModelState()"
            ),
            weathering
        )

        val second = migrate(directory)
        assertTrue(second.gates.isEmpty(), second.gates.joinToString())
        assertTrue(second.changes.isEmpty(), second.changes.joinToString())
    }

    @Test
    fun `repairs the exact intermediate after reflection cleanup without evaluating supplier`() {
        val root = directory.resolve("stripped")
        val fixture = fixture(root, includeReflection = false)

        val result = migrate(root)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(3, result.changes.size)
        val migrated = fixture.copper.readText()
        assertTrue(
            compact(migrated).contains(
                "new CreateCopperStairBlock(defaultStateSupplier,p)"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "new CreateWeatheringCopperStairBlock(" +
                    "state,defaultStateSupplier,p)"
            ),
            migrated
        )
        assertTrue(migrated.contains("defaultStateSupplier"), migrated)
        assertFalse(migrated.contains("defaultStateSupplier.get()"), migrated)
    }

    @Test
    fun `incompatible generated source blocks every family write`() {
        listOf("waxed", "weathering").forEach { conflict ->
            val root = directory.resolve("conflict-$conflict")
            val fixture = fixture(root, includeReflection = true)
            val conflictPath = if (conflict == "waxed") {
                fixture.stair
            } else {
                fixture.weathering
            }
            conflictPath.parent.createDirectories()
            conflictPath.writeText(
                """
                package com.simibubi.create.foundation.block;
                public class IncompatibleCopperStair {}
                """.trimIndent()
            )
            val originalCopper = fixture.copper.readText()
            val originalConflict = conflictPath.readText()

            val result = migrate(root)

            assertTrue(
                result.gates.any { it.contains("incompatible source") },
                result.gates.joinToString()
            )
            assertTrue(result.changes.isEmpty())
            assertEquals(originalCopper, fixture.copper.readText())
            assertEquals(originalConflict, conflictPath.readText())
            val other = if (conflict == "waxed") {
                fixture.weathering
            } else {
                fixture.stair
            }
            assertFalse(other.exists())
        }
    }

    @Test
    fun `unrelated local Variant implementation does not open the copper graph`() {
        val root = directory.resolve("unrelated-variant")
        val fixture = fixture(root, includeReflection = true)
        val unrelated = root.resolve("src/main/java/example/UnrelatedVariant.java")
        unrelated.parent.createDirectories()
        unrelated.writeText(
            """
            package example;
            interface Variant<T> {}
            class UnrelatedVariant implements Variant<String> {}
            """.trimIndent()
        )

        val result = migrate(root)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(3, result.changes.size)
        assertTrue(fixture.stair.exists())
        assertTrue(fixture.weathering.exists())
    }

    @Test
    fun `second commit failure rolls back the first family write`() {
        val root = directory.resolve("rollback")
        val fixture = fixture(root, includeReflection = true)
        val originalCopper = fixture.copper.readText()

        val result = CreateCopperStairFactoryMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        ) { index, _ ->
            if (index == 1) throw IOException("injected second commit failure")
        }

        assertTrue(
            result.gates.any { it.contains("injected second commit failure") },
            result.gates.joinToString()
        )
        assertTrue(result.changes.isEmpty())
        assertEquals(originalCopper, fixture.copper.readText())
        assertFalse(fixture.stair.exists())
        assertFalse(fixture.weathering.exists())
    }

    @Test
    fun `closed source admission rejects each changed semantic fact without writes`() {
        val rejections = listOf(
            Rejection("base-map") { fixture ->
                fixture.replaceCopper("Blocks.OXIDIZED_COPPER", "Blocks.IRON_BLOCK")
            },
            Rejection("default-variants") { fixture ->
                fixture.replaceCopper(
                    "BlockVariant.INSTANCE, SlabVariant.INSTANCE, StairVariant.INSTANCE",
                    "BlockVariant.INSTANCE, SlabVariant.INSTANCE"
                )
            },
            Rejection("stair-parent") { fixture ->
                fixture.replaceCopper(
                    "new StairVariant(BlockVariant.INSTANCE)",
                    "new StairVariant(SlabVariant.INSTANCE)"
                )
            },
            Rejection("initial-properties") { fixture ->
                fixture.replaceCopper(
                    ".initialProperties(() -> baseBlock.get())",
                    ".initialProperties(() -> Blocks.STONE)"
                )
            },
            Rejection("properties-callback") { fixture ->
                fixture.replaceCopper(
                    ".initialProperties(() -> baseBlock.get())",
                    ".initialProperties(() -> baseBlock.get())" +
                        "\n                .properties(p -> p.strength(1))"
                )
            },
            Rejection("parent-factory") { fixture ->
                fixture.replaceCopper(
                    "new WeatheringCopperFullBlock(state, p)",
                    "new Block(p)"
                )
            },
            Rejection("supplier") { fixture ->
                fixture.replaceCopper(
                    "() -> blocks.get(parent, state, waxed).getDefaultState()",
                    "() -> Blocks.AIR.defaultBlockState()"
                )
            },
            Rejection("constructor-order") { fixture ->
                fixture.replaceCopper(
                    "for (Variant<?> variant : this.variants)",
                    "for (Variant<?> variant : new Variant<?>[]{" +
                        "StairVariant.INSTANCE, BlockVariant.INSTANCE, SlabVariant.INSTANCE})"
                )
            },
            Rejection("constructor-string-literal") { fixture ->
                fixture.replaceCopper(
                    "\"copper/\", onRegister",
                    "\"copper /\", onRegister"
                )
            },
            Rejection("has-variant") { fixture ->
                fixture.replaceCopper(
                    "return ArrayUtils.contains(variants, variant);",
                    "return true;"
                )
            },
            Rejection("entry-lookup") { fixture ->
                fixture.replaceCopper(
                    "return entries[getIndex(state, waxed)];",
                    "return entries[0];"
                )
            },
            Rejection("base-map-alias") { fixture ->
                fixture.replaceCopper(
                    "Supplier<Block> baseBlock = BASE_BLOCKS.get(state);",
                    "Map<WeatherState, Supplier<Block>> alias = BASE_BLOCKS;\n" +
                        "        Supplier<Block> baseBlock = alias.get(state);"
                )
            },
            Rejection("registration-consumer") { fixture ->
                fixture.replaceAllBlocks(
                    "AllSpriteShifts.COPPER_TILES.get(ws)",
                    "AllSpriteShifts.COPPER_SHINGLES.get(ws)"
                )
            },
            Rejection("default-variants-external-mutation") { fixture ->
                val mutation = fixture.root.resolve(
                    "src/main/java/com/simibubi/create/MutateCopperVariants.java"
                )
                mutation.parent.createDirectories()
                mutation.writeText(
                    """
                    package com.simibubi.create;
                    import com.simibubi.create.foundation.block.CopperBlockSet;
                    class MutateCopperVariants {
                        static {
                            CopperBlockSet.DEFAULT_VARIANTS[0] =
                                CopperBlockSet.StairVariant.INSTANCE;
                        }
                    }
                    """.trimIndent()
                )
            },
            Rejection("instance-variants-external-mutation") { fixture ->
                val mutation = fixture.root.resolve(
                    "src/main/java/example/MutateCopperSet.java"
                )
                mutation.parent.createDirectories()
                mutation.writeText(
                    """
                    package example;
                    import com.simibubi.create.foundation.block.CopperBlockSet;
                    class MutateCopperSet {
                        void mutate(CopperBlockSet set) {
                            set.getVariants()[0] =
                                CopperBlockSet.StairVariant.INSTANCE;
                        }
                    }
                    """.trimIndent()
                )
            },
            Rejection("pickaxe-transform") { fixture ->
                fixture.replaceTagGen(
                    "return b -> b.tag(BlockTags.MINEABLE_WITH_PICKAXE);",
                    "return b -> b.properties(p -> p.strength(1));"
                )
            },
            Rejection("ct-registration") { fixture ->
                fixture.replaceCreateRegistrate(
                    "registerCTBehviour(entry, behavior)",
                    "mutate(entry)"
                )
            },
            Rejection("extra-subclass") { fixture ->
                val extra = fixture.root.resolve(
                    "src/main/java/example/CustomCopperBlockSet.java"
                )
                extra.parent.createDirectories()
                extra.writeText(
                    """
                    package example;
                    import com.simibubi.create.foundation.block.CopperBlockSet;
                    class CustomCopperBlockSet extends CopperBlockSet {}
                    """.trimIndent()
                )
            },
            Rejection("custom-variant") { fixture ->
                val extra = fixture.root.resolve(
                    "src/main/java/example/CustomCopperVariant.java"
                )
                extra.parent.createDirectories()
                extra.writeText(
                    """
                    package example;
                    import com.simibubi.create.foundation.block.CopperBlockSet.Variant;
                    import net.minecraft.world.level.block.Block;
                    class CustomCopperVariant implements Variant<Block> {}
                    """.trimIndent()
                )
            }
        )

        rejections.forEachIndexed { index, rejection ->
            val root = directory.resolve("reject-$index-${rejection.name}")
            val fixture = fixture(root, includeReflection = true)
            rejection.mutate(fixture)
            val original = fixture.copper.readText()

            val result = migrate(root)

            assertTrue(
                result.gates.isNotEmpty(),
                "${rejection.name} unexpectedly admitted"
            )
            assertTrue(
                result.changes.isEmpty(),
                "${rejection.name}: ${result.changes.joinToString()}"
            )
            assertEquals(original, fixture.copper.readText(), rejection.name)
            assertFalse(fixture.stair.exists(), rejection.name)
            assertFalse(fixture.weathering.exists(), rejection.name)
        }
    }

    private fun migrate(root: Path): CreateCopperStairFactoryMigration.Result =
        CreateCopperStairFactoryMigration.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

    private fun fixture(root: Path, includeReflection: Boolean): Fixture {
        val copper = root.resolve(
            "src/main/java/com/simibubi/create/foundation/block/CopperBlockSet.java"
        )
        val allBlocks = root.resolve(
            "src/main/java/com/simibubi/create/AllBlocks.java"
        )
        val tagGen = root.resolve(
            "src/main/java/com/simibubi/create/foundation/data/TagGen.java"
        )
        val createRegistrate = root.resolve(
            "src/main/java/com/simibubi/create/foundation/data/CreateRegistrate.java"
        )
        val deployingRecipeGen = root.resolve(
            "src/main/java/com/simibubi/create/api/data/recipe/DeployingRecipeGen.java"
        )
        copper.parent.createDirectories()
        allBlocks.parent.createDirectories()
        tagGen.parent.createDirectories()
        deployingRecipeGen.parent.createDirectories()
        val reflection = if (includeReflection) {
            """
                        ObfuscationReflectionHelper.setPrivateValue(
                            StairBlock.class, block, defaultStateSupplier, "stateSupplier");
            """.trimIndent()
        } else {
            """
                        // The generic reflection cleanup removed only the Forge field write.
            """.trimIndent()
        }
        copper.writeText(
            """
            package com.simibubi.create.foundation.block;

            import java.util.HashMap;
            import java.util.EnumMap;
            import java.util.Map;
            import java.util.function.Supplier;

            import org.apache.commons.lang3.ArrayUtils;

            import com.simibubi.create.foundation.data.TagGen;
            import com.tterrag.registrate.AbstractRegistrate;
            import com.tterrag.registrate.builders.BlockBuilder;
            import com.tterrag.registrate.providers.DataGenContext;
            import com.tterrag.registrate.providers.RegistrateRecipeProvider;
            import com.tterrag.registrate.util.entry.BlockEntry;
            import com.tterrag.registrate.util.nullness.NonNullBiConsumer;
            import com.tterrag.registrate.util.nullness.NonNullFunction;

            import net.createmod.catnip.data.Iterate;
            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.SlabBlock;
            import net.minecraft.world.level.block.StairBlock;
            import net.minecraft.world.level.block.WeatheringCopper.WeatherState;
            import net.minecraft.world.level.block.WeatheringCopperFullBlock;
            import net.minecraft.world.level.block.WeatheringCopperStairBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
            import net.minecraft.world.level.block.state.BlockState;

            import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

            public class CopperBlockSet {
                protected static final WeatherState[] WEATHER_STATES =
                    WeatherState.values();
                protected static final int WEATHER_STATE_COUNT =
                    WEATHER_STATES.length;
                protected static final Map<WeatherState, Supplier<Block>> BASE_BLOCKS =
                    new EnumMap<>(WeatherState.class);

                static {
                    BASE_BLOCKS.put(WeatherState.UNAFFECTED, () -> Blocks.COPPER_BLOCK);
                    BASE_BLOCKS.put(WeatherState.EXPOSED, () -> Blocks.EXPOSED_COPPER);
                    BASE_BLOCKS.put(WeatherState.WEATHERED, () -> Blocks.WEATHERED_COPPER);
                    BASE_BLOCKS.put(WeatherState.OXIDIZED, () -> Blocks.OXIDIZED_COPPER);
                }

                public static final Variant<?>[] DEFAULT_VARIANTS =
                    new Variant<?>[]{
                        BlockVariant.INSTANCE, SlabVariant.INSTANCE, StairVariant.INSTANCE
                    };

                protected final String name;
                protected final String generalDirectory;
                protected final Variant<?>[] variants;
                protected final Map<Variant<?>, BlockEntry<?>[]> entries =
                    new HashMap<>();
                protected final NonNullBiConsumer<
                    DataGenContext<Block, ?>, RegistrateRecipeProvider> mainBlockRecipe;
                protected final String endTextureName;
                protected final NonNullBiConsumer<WeatherState, Block> onRegister;

                public CopperBlockSet(
                    AbstractRegistrate<?> registrate,
                    String name,
                    String endTextureName,
                    Variant<?>[] variants,
                    NonNullBiConsumer<
                        DataGenContext<Block, ?>, RegistrateRecipeProvider> mainBlockRecipe,
                    NonNullBiConsumer<WeatherState, Block> onRegister
                ) {
                    this(
                        registrate, name, endTextureName, variants, mainBlockRecipe,
                        "copper/", onRegister);
                }

                public CopperBlockSet(
                    AbstractRegistrate<?> registrate,
                    String name,
                    String endTextureName,
                    Variant<?>[] variants,
                    NonNullBiConsumer<
                        DataGenContext<Block, ?>, RegistrateRecipeProvider> mainBlockRecipe,
                    String generalDirectory,
                    NonNullBiConsumer<WeatherState, Block> onRegister
                ) {
                    this.name = name;
                    this.generalDirectory = generalDirectory;
                    this.endTextureName = endTextureName;
                    this.variants = variants;
                    this.mainBlockRecipe = mainBlockRecipe;
                    this.onRegister = onRegister;

                    for (boolean waxed : Iterate.falseAndTrue) {
                        for (Variant<?> variant : this.variants) {
                            BlockEntry<?>[] entries =
                                waxed ? this.entries.get(variant) :
                                    new BlockEntry<?>[WEATHER_STATE_COUNT * 2];
                            for (WeatherState state : WEATHER_STATES) {
                                int index = getIndex(state, waxed);
                                BlockEntry<?> entry =
                                    createEntry(registrate, variant, state, waxed);
                                entries[index] = entry;

                                if (waxed) {
                                    CopperRegistries.addWaxable(
                                        () -> entries[getIndex(state, false)].get(),
                                        () -> entry.get());
                                } else if (state != WeatherState.UNAFFECTED) {
                                    CopperRegistries.addWeathering(
                                        () -> entries[getIndex(
                                            WEATHER_STATES[state.ordinal() - 1],
                                            false)].get(),
                                        () -> entry.get());
                                }
                            }
                            if (!waxed)
                                this.entries.put(variant, entries);
                        }
                    }
                }

                protected <T extends Block> BlockEntry<?> createEntry(
                    AbstractRegistrate<?> registrate,
                    Variant<T> variant,
                    WeatherState state,
                    boolean waxed
                ) {
                    String name = "fixture";
                    Supplier<Block> baseBlock = BASE_BLOCKS.get(state);
                    BlockBuilder<T, ?> builder =
                        registrate.block(name, variant.getFactory(this, state, waxed))
                            .initialProperties(() -> baseBlock.get())
                            .loot((lt, block) ->
                                variant.generateLootTable(lt, block, this, state, waxed))
                            .blockstate((ctx, prov) ->
                                variant.generateBlockState(ctx, prov, this, state, waxed))
                            .recipe((c, p) -> variant.generateRecipes(
                                entries.get(BlockVariant.INSTANCE)[state.ordinal()], c, p))
                            .transform(TagGen.pickaxeOnly())
                            .onRegister(block -> onRegister.accept(state, block))
                            .tag(BlockTags.NEEDS_STONE_TOOL)
                            .simpleItem();

                    if (variant == BlockVariant.INSTANCE &&
                        state == WeatherState.UNAFFECTED)
                        builder.recipe(mainBlockRecipe::accept);

                    if (variant == StairVariant.INSTANCE)
                        builder.tag(BlockTags.STAIRS);

                    if (variant == SlabVariant.INSTANCE)
                        builder.tag(BlockTags.SLABS);

                    if (waxed)
                        builder.recipe((ctx, prov) -> {});

                    return builder.register();
                }

                protected int getIndex(WeatherState state, boolean waxed) {
                    return state.ordinal() + (waxed ? WEATHER_STATE_COUNT : 0);
                }

                public boolean hasVariant(Variant<?> variant) {
                    return ArrayUtils.contains(variants, variant);
                }

                public Variant<?>[] getVariants() {
                    return variants;
                }

                public BlockEntry<?> get(
                    Variant<?> variant,
                    WeatherState state,
                    boolean waxed
                ) {
                    BlockEntry<?>[] entries = this.entries.get(variant);
                    if (entries != null) {
                        return entries[getIndex(state, waxed)];
                    }
                    return null;
                }

                public interface Variant<T extends Block> {
                    NonNullFunction<Properties, T> getFactory(
                        CopperBlockSet blocks, WeatherState state, boolean waxed);
                    void generateLootTable(
                        Object table, T block, CopperBlockSet blocks,
                        WeatherState state, boolean waxed);
                    void generateBlockState(
                        Object context, Object provider, CopperBlockSet blocks,
                        WeatherState state, boolean waxed);
                    void generateRecipes(Object block, Object context, Object provider);
                }

                public static class BlockVariant implements Variant<Block> {
                    public static final BlockVariant INSTANCE = new BlockVariant();

                    protected BlockVariant() {
                    }

                    public NonNullFunction<Properties, Block> getFactory(
                        CopperBlockSet blocks, WeatherState state, boolean waxed
                    ) {
                        if (waxed) {
                            return Block::new;
                        } else {
                            return p -> new WeatheringCopperFullBlock(state, p);
                        }
                    }
                }

                public static class SlabVariant implements Variant<SlabBlock> {
                    public static final SlabVariant INSTANCE = new SlabVariant();
                }

                public static class StairVariant implements Variant<StairBlock> {
                    public static final StairVariant INSTANCE =
                        new StairVariant(BlockVariant.INSTANCE);

                    protected final Variant<?> parent;

                    protected StairVariant(Variant<?> parent) {
                        this.parent = parent;
                    }

                    public NonNullFunction<Properties, StairBlock> getFactory(
                        CopperBlockSet blocks,
                        WeatherState state,
                        boolean waxed
                    ) {
                        if (!blocks.hasVariant(parent)) {
                            throw new IllegalStateException("missing parent");
                        }
                        Supplier<BlockState> defaultStateSupplier =
                            () -> blocks.get(parent, state, waxed).getDefaultState();
                        if (waxed) {
                            return p -> new StairBlock(defaultStateSupplier, p);
                        } else {
                            return p -> {
                                WeatheringCopperStairBlock block =
                                    new WeatheringCopperStairBlock(
                                        state, Blocks.AIR.defaultBlockState(), p);
            $reflection
                                return block;
                            };
                        }
                    }
                }

            }
            """.trimIndent() + "\n"
        )
        tagGen.writeText(
            """
            package com.simibubi.create.foundation.data;

            import com.tterrag.registrate.builders.BlockBuilder;
            import com.tterrag.registrate.util.nullness.NonNullFunction;

            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.level.block.Block;

            public class TagGen {
                public static <T extends Block, P>
                NonNullFunction<BlockBuilder<T, P>, BlockBuilder<T, P>>
                pickaxeOnly() {
                    return b -> b.tag(BlockTags.MINEABLE_WITH_PICKAXE);
                }
            }
            """.trimIndent() + "\n"
        )
        createRegistrate.writeText(
            """
            package com.simibubi.create.foundation.data;

            import java.util.function.Supplier;

            import com.simibubi.create.CreateClient;
            import com.simibubi.create.foundation.block.connected.CTModel;
            import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
            import com.tterrag.registrate.util.nullness.NonNullConsumer;

            import net.createmod.catnip.registry.RegisteredObjectsHelper;
            import net.minecraft.world.level.block.Block;

            import com.modporter.generated.create.compat.DistExecutor;
            import net.neoforged.api.distmarker.Dist;

            public class CreateRegistrate {
                public static <T extends Block> NonNullConsumer<? super T>
                connectedTextures(Supplier<ConnectedTextureBehaviour> behavior) {
                    return entry -> onClient(() -> () ->
                        registerCTBehviour(entry, behavior));
                }

                protected static void onClient(Supplier<Runnable> toRun) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, toRun);
                }

                private static void registerCTBehviour(
                    Block entry,
                    Supplier<ConnectedTextureBehaviour> behaviorSupplier
                ) {
                    ConnectedTextureBehaviour behavior = behaviorSupplier.get();
                    CreateClient.MODEL_SWAPPER.getCustomBlockModels().register(
                        RegisteredObjectsHelper.getKeyOrThrow(entry),
                        model -> new CTModel(model, behavior));
                }
            }
            """.trimIndent() + "\n"
        )
        deployingRecipeGen.writeText(
            """
            package com.simibubi.create.api.data.recipe;

            import com.simibubi.create.foundation.block.CopperBlockSet;
            import com.simibubi.create.foundation.block.CopperBlockSet.Variant;

            public class DeployingRecipeGen {
                void copperChain(CopperBlockSet set) {
                    for (Variant<?> variant : set.getVariants()) {
                        set.get(variant, null, false);
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        allBlocks.writeText(
            """
            package com.simibubi.create;

            import static com.simibubi.create.foundation.data.CreateRegistrate.connectedTextures;

            import com.simibubi.create.content.decoration.RoofBlockCTBehaviour;
            import com.simibubi.create.foundation.block.CopperBlockSet;

            public class AllBlocks {
                public static final CopperBlockSet COPPER_SHINGLES =
                    new CopperBlockSet(
                        REGISTRATE,
                        "copper_shingles",
                        "copper_roof_top",
                        CopperBlockSet.DEFAULT_VARIANTS,
                        (c, p) -> {},
                        (ws, block) -> connectedTextures(
                            () -> new RoofBlockCTBehaviour(
                                AllSpriteShifts.COPPER_SHINGLES.get(ws)))
                            .accept(block)
                    );

                public static final CopperBlockSet COPPER_TILES =
                    new
                    com.simibubi.create.foundation.block.CopperBlockSet(
                        REGISTRATE,
                        "copper_tiles",
                        "copper_roof_top",
                        CopperBlockSet.DEFAULT_VARIANTS,
                        (c, p) -> {},
                        (ws, block) -> connectedTextures(
                            () -> new RoofBlockCTBehaviour(
                                AllSpriteShifts.COPPER_TILES.get(ws)))
                            .accept(block)
                    );
            }
            """.trimIndent() + "\n"
        )
        return Fixture(
            root,
            copper,
            allBlocks,
            tagGen,
            createRegistrate,
            copper.parent.resolve("CreateCopperStairBlock.java"),
            copper.parent.resolve("CreateWeatheringCopperStairBlock.java")
        )
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*,\s*"""), ",")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()

    private data class Rejection(
        val name: String,
        val mutate: (Fixture) -> Unit
    )

    private data class Fixture(
        val root: Path,
        val copper: Path,
        val allBlocks: Path,
        val tagGen: Path,
        val createRegistrate: Path,
        val stair: Path,
        val weathering: Path
    ) {
        fun replaceCopper(old: String, new: String) {
            val source = copper.readText()
            assertTrue(source.contains(old), "Fixture does not contain mutation anchor: $old")
            copper.writeText(source.replace(old, new))
        }

        fun replaceAllBlocks(old: String, new: String) {
            replace(allBlocks, old, new)
        }

        fun replaceTagGen(old: String, new: String) {
            replace(tagGen, old, new)
        }

        fun replaceCreateRegistrate(old: String, new: String) {
            replace(createRegistrate, old, new)
        }

        private fun replace(path: Path, old: String, new: String) {
            val source = path.readText()
            assertTrue(source.contains(old), "Fixture does not contain mutation anchor: $old")
            path.writeText(source.replace(old, new))
        }
    }
}
