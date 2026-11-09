package com.modporter.core.transforms.structural

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LegacyPlantMushroomRotDifferentialTest {
    @TempDir
    lateinit var project: Path

    @Test
    fun `current helper matches unchanged mushroom rot bytecode at the old Forge boundary`() {
        val oracleBytes = resourceBytes(ORACLE_RESOURCE)
        assertEquals(4_932, oracleBytes.size)
        assertEquals(ORACLE_SHA256, sha256(oracleBytes))

        val manifest = resourceText(MANIFEST_RESOURCE)
        listOf(
            ORACLE_SHA256,
            SOURCE_GRAPH_ARTIFACT_SHA256,
            "com.ferreusveritas.dynamictrees:DynamicTrees-1.20.1:1.3.0-BETA13",
            "9b43a9261e11dbd7d84b7e37272cbbd9dcf23277",
            "MIT",
            "Copyright (c) 2016,2017 Ferreus Veritas",
            "Harley O'Connor",
        ).forEach { expected ->
            assertTrue(manifest.contains(expected), "Missing fixture provenance: $expected")
        }

        val sourceRoot = project.resolve("src")
        val classes = project.resolve("classes")
        sourceRoot.createDirectories()
        classes.createDirectories()
        writeOracleClass(classes, oracleBytes)
        writeStubSources()
        writeCurrentHelperTemplate()
        compileJava17(sourceRoot, classes)

        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val harness = loader.loadClass("probe.MushroomRotDifferentialHarness")
            val result = harness.getMethod("verifyAll").invoke(null)
            assertEquals("verified:14", result)
        }
    }

    private fun writeCurrentHelperTemplate() {
        val template = resourceText("/compat/LegacyPlantMushroomRotCompat.java.template")
        assertTrue(template.contains("package @PACKAGE@;"), template)
        write(
            "probe/LegacyPlantMushroomRotCompat.java",
            template.replace("@PACKAGE@", "probe"),
        )
    }

    private fun writeStubSources() {
        val sources = mapOf(
            "net/minecraft/resources/ResourceLocation.java" to
                """
                package net.minecraft.resources;

                public record ResourceLocation(String value) {
                }
                """.trimIndent(),
            "net/minecraft/core/BlockPos.java" to
                """
                package net.minecraft.core;

                public record BlockPos(int x, int y, int z) {
                }
                """.trimIndent(),
            "net/minecraft/core/Direction.java" to
                """
                package net.minecraft.core;

                public enum Direction {
                    UP
                }
                """.trimIndent(),
            "net/minecraft/util/RandomSource.java" to
                """
                package net.minecraft.util;

                public interface RandomSource {
                    float nextFloat();
                }
                """.trimIndent(),
            "net/minecraft/world/level/BlockGetter.java" to
                """
                package net.minecraft.world.level;

                public interface BlockGetter {
                }
                """.trimIndent(),
            "net/minecraft/world/level/LightLayer.java" to
                """
                package net.minecraft.world.level;

                public enum LightLayer {
                    SKY
                }
                """.trimIndent(),
            "net/minecraft/world/level/LevelAccessor.java" to
                """
                package net.minecraft.world.level;

                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;

                public interface LevelAccessor extends BlockGetter {
                    BlockState getBlockState(BlockPos pos);
                    int getBrightness(LightLayer layer, BlockPos pos);
                    boolean setBlock(BlockPos pos, BlockState state, int flags);
                }
                """.trimIndent(),
            "net/minecraftforge/common/IPlantable.java" to
                """
                package net.minecraftforge.common;

                public interface IPlantable {
                }
                """.trimIndent(),
            "probe/fixture/Trace.java" to
                """
                package probe.fixture;

                import java.util.ArrayList;
                import java.util.List;

                public final class Trace {
                    private final List<String> events = new ArrayList<>();

                    public void add(String event) {
                        events.add(event);
                    }

                    public List<String> snapshot() {
                        return List.copyOf(events);
                    }
                }
                """.trimIndent(),
            "probe/fixture/RunState.java" to
                """
                package probe.fixture;

                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.LevelAccessor;

                public final class RunState {
                    public final Trace trace = new Trace();
                    public BlockPos pos;
                    public LevelAccessor world;
                    public Object selectedBlock;
                }
                """.trimIndent(),
            "net/minecraft/world/level/block/state/BlockState.java" to
                """
                package net.minecraft.world.level.block.state;

                import net.minecraft.core.BlockPos;
                import net.minecraft.core.Direction;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraftforge.common.IPlantable;
                import probe.fixture.RunState;

                public final class BlockState {
                    private final String label;
                    private final RunState run;
                    private final boolean sustains;

                    public BlockState(String label, RunState run, boolean sustains) {
                        this.label = label;
                        this.run = run;
                        this.sustains = sustains;
                    }

                    public boolean canSustainPlant(
                        BlockGetter level,
                        BlockPos pos,
                        Direction facing,
                        IPlantable plantable
                    ) {
                        if (run == null) throw new AssertionError("unexpected sustain query");
                        if (level != run.world) throw new AssertionError("level identity");
                        if (pos != run.pos) throw new AssertionError("position identity");
                        if (facing != Direction.UP) throw new AssertionError("facing identity");
                        if (plantable != run.selectedBlock) {
                            throw new AssertionError("plant identity");
                        }
                        run.trace.add(
                            "soil.canSustainPlant(" +
                                ((net.minecraft.world.level.block.Block) plantable).label() +
                                ")"
                        );
                        return sustains;
                    }

                    public String label() {
                        return label;
                    }
                }
                """.trimIndent(),
            "net/minecraft/world/level/block/Block.java" to
                """
                package net.minecraft.world.level.block;

                import net.minecraft.world.level.block.state.BlockState;
                import probe.fixture.RunState;

                public class Block {
                    private final String label;
                    private final RunState run;
                    private final BlockState defaultState;

                    public Block(String label) {
                        this(label, null);
                    }

                    public Block(String label, RunState run) {
                        this.label = label;
                        this.run = run;
                        this.defaultState = new BlockState(label + "-state", run, false);
                    }

                    public BlockState defaultBlockState() {
                        if (run != null) run.trace.add("block.defaultBlockState(" + label + ")");
                        return defaultState;
                    }

                    public BlockState identityDefaultState() {
                        return defaultState;
                    }

                    public String label() {
                        return label;
                    }
                }
                """.trimIndent(),
            "net/minecraft/world/level/block/PlantBlock.java" to
                """
                package net.minecraft.world.level.block;

                import net.minecraftforge.common.IPlantable;
                import probe.fixture.RunState;

                public final class PlantBlock extends Block implements IPlantable {
                    public PlantBlock(String label) {
                        super(label);
                    }

                    public PlantBlock(String label, RunState run) {
                        super(label, run);
                    }
                }
                """.trimIndent(),
            "net/minecraft/world/level/block/Blocks.java" to
                """
                package net.minecraft.world.level.block;

                public final class Blocks {
                    public static final Block BROWN_MUSHROOM = new PlantBlock("brown");
                    public static final Block RED_MUSHROOM = new PlantBlock("red");

                    private Blocks() {
                    }
                }
                """.trimIndent(),
            "com/ferreusveritas/dynamictrees/api/configuration/ConfigurationProperty.java" to
                """
                package com.ferreusveritas.dynamictrees.api.configuration;

                public final class ConfigurationProperty {
                    private final String name;

                    private ConfigurationProperty(String name) {
                        this.name = name;
                    }

                    public static ConfigurationProperty block(String name) {
                        return new ConfigurationProperty(name);
                    }

                    public static ConfigurationProperty floatProperty(String name) {
                        return new ConfigurationProperty(name);
                    }

                    public String name() {
                        return name;
                    }
                }
                """.trimIndent(),
            "com/ferreusveritas/dynamictrees/api/configuration/Configuration.java" to
                """
                package com.ferreusveritas.dynamictrees.api.configuration;

                public interface Configuration {
                    Configuration with(ConfigurationProperty property, Object value);
                }
                """.trimIndent(),
            "com/ferreusveritas/dynamictrees/api/configuration/ConfigurableRegistryEntry.java" to
                """
                package com.ferreusveritas.dynamictrees.api.configuration;

                public class ConfigurableRegistryEntry {
                    protected ConfigurableRegistryEntry register(
                        ConfigurationProperty[] properties
                    ) {
                        return this;
                    }
                }
                """.trimIndent(),
            "com/ferreusveritas/dynamictrees/systems/genfeature/GenFeatureConfiguration.java" to
                """
                package com.ferreusveritas.dynamictrees.systems.genfeature;

                import com.ferreusveritas.dynamictrees.api.configuration.Configuration;
                import com.ferreusveritas.dynamictrees.api.configuration.ConfigurationProperty;
                import java.util.IdentityHashMap;
                import java.util.Map;
                import net.minecraft.world.level.block.Block;
                import probe.fixture.RunState;

                public final class GenFeatureConfiguration implements Configuration {
                    private final Map<ConfigurationProperty, Object> values =
                        new IdentityHashMap<>();
                    private final RunState run;

                    public GenFeatureConfiguration(RunState run) {
                        this.run = run;
                    }

                    public GenFeatureConfiguration put(
                        ConfigurationProperty property,
                        Object value
                    ) {
                        values.put(property, value);
                        return this;
                    }

                    public Object get(ConfigurationProperty property) {
                        Object value = values.get(property);
                        String event = "configuration.get(" + property.name() + ")";
                        if (!"alternate_mushroom_chance".equals(property.name())) {
                            run.selectedBlock = value;
                            event += "->" + ((Block) value).label();
                        }
                        run.trace.add(event);
                        return value;
                    }

                    @Override
                    public Configuration with(ConfigurationProperty property, Object value) {
                        values.put(property, value);
                        return this;
                    }
                }
                """.trimIndent(),
            "com/ferreusveritas/dynamictrees/systems/genfeature/GenFeature.java" to
                """
                package com.ferreusveritas.dynamictrees.systems.genfeature;

                import com.ferreusveritas.dynamictrees.api.configuration.ConfigurableRegistryEntry;
                import net.minecraft.resources.ResourceLocation;
                import probe.fixture.RunState;

                public class GenFeature extends ConfigurableRegistryEntry {
                    public GenFeature(ResourceLocation name) {
                    }

                    protected void registerProperties() {
                    }

                    protected GenFeatureConfiguration createDefaultConfiguration() {
                        return new GenFeatureConfiguration(new RunState());
                    }
                }
                """.trimIndent(),
            "com/ferreusveritas/dynamictrees/systems/genfeature/context/PostRotContext.java" to
                """
                package com.ferreusveritas.dynamictrees.systems.genfeature.context;

                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.LevelAccessor;
                import probe.fixture.RunState;

                public final class PostRotContext {
                    private final RunState run;
                    private final LevelAccessor level;
                    private final BlockPos pos;
                    private final RandomSource random;
                    private final int radius;

                    public PostRotContext(
                        RunState run,
                        LevelAccessor level,
                        BlockPos pos,
                        RandomSource random,
                        int radius
                    ) {
                        this.run = run;
                        this.level = level;
                        this.pos = pos;
                        this.random = random;
                        this.radius = radius;
                    }

                    public LevelAccessor level() {
                        run.trace.add("context.level");
                        return level;
                    }

                    public BlockPos pos() {
                        run.trace.add("context.pos");
                        return pos;
                    }

                    public RandomSource random() {
                        run.trace.add("context.random");
                        return random;
                    }

                    public int radius() {
                        run.trace.add("context.radius(" + radius + ")");
                        return radius;
                    }
                }
                """.trimIndent(),
            "probe/ProbeRandom.java" to
                """
                package probe;

                import net.minecraft.util.RandomSource;
                import probe.fixture.RunState;

                public final class ProbeRandom implements RandomSource {
                    private final RunState run;
                    private final float draw;
                    private int draws;

                    public ProbeRandom(RunState run, float draw) {
                        this.run = run;
                        this.draw = draw;
                    }

                    @Override
                    public float nextFloat() {
                        draws++;
                        if (draws > 1) throw new AssertionError("extra random draw");
                        run.trace.add("random.nextFloat(" + Float.toHexString(draw) + ")");
                        return draw;
                    }

                    public int draws() {
                        return draws;
                    }
                }
                """.trimIndent(),
            "probe/ProbeWorld.java" to
                """
                package probe;

                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.LevelAccessor;
                import net.minecraft.world.level.LightLayer;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.BlockState;
                import probe.fixture.RunState;

                public final class ProbeWorld implements LevelAccessor {
                    private final RunState run;
                    private final BlockState soil;
                    private final int brightness;
                    private final boolean setBlockResult;
                    private int setBlockCalls;
                    private String placedState;
                    private int flags = -1;

                    public ProbeWorld(
                        RunState run,
                        boolean sustains,
                        int brightness,
                        boolean setBlockResult
                    ) {
                        this.run = run;
                        this.soil = new BlockState("soil", run, sustains);
                        this.brightness = brightness;
                        this.setBlockResult = setBlockResult;
                    }

                    @Override
                    public BlockState getBlockState(BlockPos pos) {
                        requirePosition(pos);
                        run.trace.add("level.getBlockState");
                        return soil;
                    }

                    @Override
                    public int getBrightness(LightLayer layer, BlockPos pos) {
                        requirePosition(pos);
                        if (layer != LightLayer.SKY) throw new AssertionError("light layer");
                        run.trace.add("level.getBrightness(SKY)->" + brightness);
                        return brightness;
                    }

                    @Override
                    public boolean setBlock(BlockPos pos, BlockState state, int flags) {
                        requirePosition(pos);
                        Block selected = (Block) run.selectedBlock;
                        if (state != selected.identityDefaultState()) {
                            throw new AssertionError("state identity");
                        }
                        if (flags != 3) throw new AssertionError("flags " + flags);
                        setBlockCalls++;
                        placedState = state.label();
                        this.flags = flags;
                        run.trace.add(
                            "level.setBlock(" + placedState + ",3)->" + setBlockResult
                        );
                        return setBlockResult;
                    }

                    private void requirePosition(BlockPos pos) {
                        if (pos != run.pos) throw new AssertionError("position identity");
                    }

                    public int setBlockCalls() {
                        return setBlockCalls;
                    }

                    public String placedState() {
                        return placedState;
                    }

                    public int flags() {
                        return flags;
                    }
                }
                """.trimIndent(),
            "probe/LegacyPlantables.java" to
                """
                package probe;

                import net.minecraft.core.BlockPos;
                import net.minecraft.core.Direction;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraftforge.common.IPlantable;

                public final class LegacyPlantables {
                    private LegacyPlantables() {
                    }

                    public static boolean isPlantable(Object value) {
                        return value instanceof IPlantable;
                    }

                    public static boolean canSustainPlant(
                        BlockState soil,
                        BlockGetter level,
                        BlockPos pos,
                        Direction facing,
                        Object plantable
                    ) {
                        return soil.canSustainPlant(
                            level,
                            pos,
                            facing,
                            (IPlantable) plantable
                        );
                    }
                }
                """.trimIndent(),
            "probe/LegacyPlantAdapterProfile.java" to
                """
                package probe;

                import com.ferreusveritas.dynamictrees.api.configuration.ConfigurationProperty;
                import com.ferreusveritas.dynamictrees.systems.genfeature.GenFeatureConfiguration;
                import com.ferreusveritas.dynamictrees.systems.genfeature.context.PostRotContext;
                import java.lang.reflect.Field;
                import java.lang.reflect.Method;

                public final class LegacyPlantAdapterProfile {
                    private static final GameProfile PROFILE = createProfile();

                    private LegacyPlantAdapterProfile() {
                    }

                    public static GameProfile requireGameProfile() {
                        return PROFILE;
                    }

                    private static GameProfile createProfile() {
                        try {
                            Class<?> owner = Class.forName(
                                "com.ferreusveritas.dynamictrees.systems.genfeature." +
                                    "MushroomRotGenFeature"
                            );
                            return new GameProfile(
                                GenFeatureConfiguration.class.getMethod(
                                    "get",
                                    ConfigurationProperty.class
                                ),
                                owner.getField("MUSHROOM"),
                                owner.getField("ALTERNATE_MUSHROOM"),
                                owner.getField("ALTERNATE_MUSHROOM_CHANCE"),
                                PostRotContext.class.getMethod("level"),
                                PostRotContext.class.getMethod("pos"),
                                PostRotContext.class.getMethod("random"),
                                PostRotContext.class.getMethod("radius")
                            );
                        } catch (ReflectiveOperationException error) {
                            throw new ExceptionInInitializerError(error);
                        }
                    }

                    public static final class GameProfile {
                        private final Method configurationGet;
                        private final Field mushroom;
                        private final Field alternateMushroom;
                        private final Field alternateMushroomChance;
                        private final Method contextLevel;
                        private final Method contextPos;
                        private final Method contextRandom;
                        private final Method contextRadius;

                        private GameProfile(
                            Method configurationGet,
                            Field mushroom,
                            Field alternateMushroom,
                            Field alternateMushroomChance,
                            Method contextLevel,
                            Method contextPos,
                            Method contextRandom,
                            Method contextRadius
                        ) {
                            this.configurationGet = configurationGet;
                            this.mushroom = mushroom;
                            this.alternateMushroom = alternateMushroom;
                            this.alternateMushroomChance = alternateMushroomChance;
                            this.contextLevel = contextLevel;
                            this.contextPos = contextPos;
                            this.contextRandom = contextRandom;
                            this.contextRadius = contextRadius;
                        }

                        public Method configurationGet() { return configurationGet; }
                        public Field mushroom() { return mushroom; }
                        public Field alternateMushroom() { return alternateMushroom; }
                        public Field alternateMushroomChance() {
                            return alternateMushroomChance;
                        }
                        public Method contextLevel() { return contextLevel; }
                        public Method contextPos() { return contextPos; }
                        public Method contextRandom() { return contextRandom; }
                        public Method contextRadius() { return contextRadius; }
                    }
                }
                """.trimIndent(),
            "probe/MushroomRotDifferentialHarness.java" to
                """
                package probe;

                import com.ferreusveritas.dynamictrees.api.configuration.ConfigurationProperty;
                import com.ferreusveritas.dynamictrees.systems.genfeature.GenFeatureConfiguration;
                import com.ferreusveritas.dynamictrees.systems.genfeature.context.PostRotContext;
                import java.lang.reflect.InvocationTargetException;
                import java.lang.reflect.Method;
                import java.util.List;
                import net.minecraft.core.BlockPos;
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.PlantBlock;
                import probe.fixture.RunState;

                public final class MushroomRotDifferentialHarness {
                    private static final String ORACLE_NAME =
                        "com.ferreusveritas.dynamictrees.systems.genfeature." +
                            "MushroomRotGenFeature";

                    private MushroomRotDifferentialHarness() {
                    }

                    public static String verifyAll() throws Exception {
                        List<Scenario> scenarios = List.of(
                            new Scenario(
                                "chance-less-than-draw", 0.25F, 0.75F, 5,
                                true, true, true, 3, true
                            ),
                            new Scenario(
                                "chance-equal-to-draw", 0.5F, 0.5F, 5,
                                true, true, true, 3, true
                            ),
                            new Scenario(
                                "chance-greater-than-draw", 0.75F, 0.25F, 5,
                                true, true, true, 3, true
                            ),
                            new Scenario(
                                "chance-is-NaN", Float.NaN, 0.25F, 5,
                                true, true, true, 3, true
                            ),
                            new Scenario(
                                "draw-is-NaN", 0.75F, Float.NaN, 5,
                                true, true, true, 3, true
                            ),
                            new Scenario(
                                "negative-radius", 0.75F, 0.25F, -7,
                                true, true, true, 3, true
                            ),
                            new Scenario(
                                "zero-radius", 0.75F, 0.25F, 0,
                                true, true, true, 3, true
                            ),
                            new Scenario(
                                "radius-threshold", 0.75F, 0.25F, 4,
                                true, true, true, 3, true
                            ),
                            new Scenario(
                                "sky-threshold", 0.75F, 0.25F, 5,
                                true, true, true, 4, true
                            ),
                            new Scenario(
                                "sky-above-threshold", 0.75F, 0.25F, 5,
                                true, true, true, 9, true
                            ),
                            new Scenario(
                                "configured-primary-nonplant", 0.75F, 0.25F, 5,
                                false, true, true, 3, true
                            ),
                            new Scenario(
                                "configured-alternate-nonplant", 0.25F, 0.75F, 5,
                                true, false, true, 3, true
                            ),
                            new Scenario(
                                "nonsustaining-soil", 0.75F, 0.25F, 5,
                                true, true, false, 3, true
                            ),
                            new Scenario(
                                "ignored-false-setBlock", 0.75F, 0.25F, 5,
                                true, true, true, 3, false
                            )
                        );

                        for (Scenario scenario : scenarios) {
                            Observation oracle = runScenario(scenario, true);
                            Observation current = runScenario(scenario, false);
                            if (!oracle.equals(current)) {
                                throw new AssertionError(
                                    scenario.name() + " differential mismatch\nold=" + oracle +
                                        "\ncurrent=" + current
                                );
                            }
                            verifyOracleBoundary(scenario, oracle);
                        }
                        return "verified:" + scenarios.size();
                    }

                    private static Observation runScenario(
                        Scenario scenario,
                        boolean original
                    ) throws Exception {
                        RunState run = new RunState();
                        BlockPos pos = new BlockPos(11, 64, -3);
                        run.pos = pos;
                        ProbeRandom random = new ProbeRandom(run, scenario.draw());
                        ProbeWorld world = new ProbeWorld(
                            run,
                            scenario.sustains(),
                            scenario.brightness(),
                            scenario.setBlockResult()
                        );
                        run.world = world;

                        Block primary = scenario.primaryPlantable()
                            ? new PlantBlock("primary", run)
                            : new Block("primary", run);
                        Block alternate = scenario.alternatePlantable()
                            ? new PlantBlock("alternate", run)
                            : new Block("alternate", run);

                        Class<?> oracleClass = Class.forName(ORACLE_NAME);
                        ConfigurationProperty mushroom = property(
                            oracleClass,
                            "MUSHROOM"
                        );
                        ConfigurationProperty alternateMushroom = property(
                            oracleClass,
                            "ALTERNATE_MUSHROOM"
                        );
                        ConfigurationProperty chance = property(
                            oracleClass,
                            "ALTERNATE_MUSHROOM_CHANCE"
                        );
                        GenFeatureConfiguration configuration =
                            new GenFeatureConfiguration(run)
                                .put(mushroom, primary)
                                .put(alternateMushroom, alternate)
                                .put(chance, Float.valueOf(scenario.chance()));
                        PostRotContext context = new PostRotContext(
                            run,
                            world,
                            pos,
                            random,
                            scenario.radius()
                        );

                        boolean result;
                        if (original) {
                            Object oracle = oracleClass
                                .getConstructor(ResourceLocation.class)
                                .newInstance(new ResourceLocation("probe:mushroom_rot"));
                            Method postRot = oracleClass.getDeclaredMethod(
                                "postRot",
                                GenFeatureConfiguration.class,
                                PostRotContext.class
                            );
                            postRot.setAccessible(true);
                            result = invokeBoolean(postRot, oracle, configuration, context);
                        } else {
                            result = LegacyPlantMushroomRotCompat.postRot(
                                configuration,
                                context
                            );
                        }

                        Block selected = (Block) run.selectedBlock;
                        return new Observation(
                            result,
                            selected == null ? null : selected.label(),
                            random.draws(),
                            run.trace.snapshot(),
                            world.placedState(),
                            world.flags(),
                            world.setBlockCalls()
                        );
                    }

                    private static ConfigurationProperty property(
                        Class<?> owner,
                        String name
                    ) throws ReflectiveOperationException {
                        return (ConfigurationProperty) owner.getField(name).get(null);
                    }

                    private static boolean invokeBoolean(
                        Method method,
                        Object receiver,
                        Object... arguments
                    ) throws Exception {
                        try {
                            return (Boolean) method.invoke(receiver, arguments);
                        } catch (InvocationTargetException error) {
                            Throwable cause = error.getCause();
                            if (cause instanceof Exception checked) throw checked;
                            if (cause instanceof Error fatal) throw fatal;
                            throw error;
                        }
                    }

                    private static void verifyOracleBoundary(
                        Scenario scenario,
                        Observation observation
                    ) {
                        if (observation.draws() != 1) {
                            throw new AssertionError(
                                scenario.name() + " draw count " + observation.draws()
                            );
                        }
                        switch (scenario.name()) {
                            case "chance-greater-than-draw" ->
                                requireSelected(scenario, observation, "primary");
                            case "chance-less-than-draw", "chance-equal-to-draw",
                                    "chance-is-NaN", "draw-is-NaN" ->
                                requireSelected(scenario, observation, "alternate");
                            case "negative-radius", "zero-radius", "radius-threshold" -> {
                                if (observation.result()) {
                                    throw new AssertionError(scenario.name() + " accepted");
                                }
                                if (observation.trace().stream().anyMatch(
                                        event -> event.startsWith("level.getBlockState")
                                    )) {
                                    throw new AssertionError(
                                        scenario.name() + " queried soil after radius"
                                    );
                                }
                            }
                            case "sky-threshold", "sky-above-threshold" -> {
                                if (observation.result()) {
                                    throw new AssertionError(scenario.name() + " accepted");
                                }
                                if (observation.setBlockCalls() != 0) {
                                    throw new AssertionError(scenario.name() + " placed");
                                }
                            }
                            case "configured-primary-nonplant",
                                    "configured-alternate-nonplant" -> {
                                if (observation.result()) {
                                    throw new AssertionError(scenario.name() + " accepted");
                                }
                                if (observation.trace().stream().anyMatch(
                                        event -> event.startsWith("level.getBlockState")
                                    )) {
                                    throw new AssertionError(
                                        scenario.name() + " queried soil for nonplant"
                                    );
                                }
                            }
                            case "nonsustaining-soil" -> {
                                if (observation.result()) {
                                    throw new AssertionError("nonsustaining soil accepted");
                                }
                                if (observation.trace().stream().anyMatch(
                                        event -> event.startsWith("level.getBrightness")
                                    )) {
                                    throw new AssertionError("sky queried after failed sustain");
                                }
                            }
                            case "ignored-false-setBlock" -> {
                                if (!observation.result()) {
                                    throw new AssertionError("setBlock(false) changed result");
                                }
                                if (observation.setBlockCalls() != 1 ||
                                        observation.flags() != 3) {
                                    throw new AssertionError(
                                        "setBlock boundary " + observation
                                    );
                                }
                            }
                            default -> {
                            }
                        }
                    }

                    private static void requireSelected(
                        Scenario scenario,
                        Observation observation,
                        String expected
                    ) {
                        if (!expected.equals(observation.selected())) {
                            throw new AssertionError(
                                scenario.name() + " selected " + observation.selected() +
                                    " instead of " + expected
                            );
                        }
                    }

                    private record Scenario(
                        String name,
                        float chance,
                        float draw,
                        int radius,
                        boolean primaryPlantable,
                        boolean alternatePlantable,
                        boolean sustains,
                        int brightness,
                        boolean setBlockResult
                    ) {
                    }

                    private record Observation(
                        boolean result,
                        String selected,
                        int draws,
                        List<String> trace,
                        String placedState,
                        int flags,
                        int setBlockCalls
                    ) {
                    }
                }
                """.trimIndent(),
        )
        sources.forEach { (relative, source) -> write(relative, source) }
    }

    private fun compileJava17(sourceRoot: Path, output: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for mushroom-rot differential test")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use {
                fileManager ->
            val sources = Files.walk(sourceRoot).use { stream ->
                stream.filter { it.extension == "java" }.toList()
            }
            val units = fileManager.getJavaFileObjectsFromPaths(sources)
            val success = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf(
                    "--release",
                    "17",
                    "-proc:none",
                    "-classpath",
                    output.toString(),
                    "-d",
                    output.toString(),
                ),
                null,
                units,
            ).call()
            assertTrue(
                success,
                diagnostics.diagnostics.joinToString("\n") { it.toString() },
            )
        }
    }

    private fun writeOracleClass(output: Path, bytes: ByteArray) {
        val destination = output.resolve(ORACLE_ENTRY)
        destination.parent.createDirectories()
        Files.write(destination, bytes)
    }

    private fun write(relative: String, source: String) {
        val path = project.resolve("src").resolve(relative)
        path.parent.createDirectories()
        path.writeText(source)
    }

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing resource $path" }
            .use { it.readAllBytes() }

    private fun resourceText(path: String): String =
        String(resourceBytes(path), Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        const val ORACLE_RESOURCE =
            "/legacy-plant/mushroom-rot/MushroomRotGenFeature.class"
        const val MANIFEST_RESOURCE =
            "/legacy-plant/mushroom-rot/fixture-manifest.json"
        const val ORACLE_ENTRY =
            "com/ferreusveritas/dynamictrees/systems/genfeature/" +
                "MushroomRotGenFeature.class"
        const val ORACLE_SHA256 =
            "7e59b6e0692b1469d99a3d11762046e72458d75d626988d7c46e4f878f97871b"
        const val SOURCE_GRAPH_ARTIFACT_SHA256 =
            "b0eecc4dccc9e14f1738f2c77fa50fda2289a58dd4d2bf87aa4e4ba3f89281df"
    }
}
