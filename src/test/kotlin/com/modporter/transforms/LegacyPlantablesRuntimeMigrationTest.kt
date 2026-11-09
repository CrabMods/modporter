package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.modporter.core.transforms.structural.LegacyPlantablesRuntimeMigration
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyPlantablesRuntimeMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `source-owned plant and soil behavior plus vanilla adapters compile and execute`() {
        writeMetadata()
        writeMinecraftStubs()
        val nativeBush = directory.resolve(
            "src/main/java/net/minecraft/world/level/block/BushBlock.java"
        )
        val nativeBushOriginal = nativeBush.readText()
        writeExternalCoverage()
        val fixture = java("example/Fixture.java", """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.BushBlock;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.common.IPlantable;
            import net.minecraftforge.common.PlantType;

            public final class Fixture {
                public static final Block MARKER = new Block();
                public static int plantCalls;
                public static int typeCalls;
                public static final CustomPlant CUSTOM = new CustomPlant();
                public static final ImplicitPlant IMPLICIT = new ImplicitPlant();
                public static final PlacementPlant PLACEMENT = new PlacementPlant();
                public static final CustomSoil CUSTOM_SOIL = new CustomSoil();

                public static final class CustomPlant extends Block implements IPlantable {
                    @Override
                    public BlockState getPlant(BlockGetter level, BlockPos pos) {
                        plantCalls++;
                        return MARKER.defaultBlockState();
                    }

                    @Override
                    public PlantType getPlantType(BlockGetter level, BlockPos pos) {
                        typeCalls++;
                        return PlantType.get("machine");
                    }
                }

                public static final class ImplicitPlant extends BushBlock {
                    @Override
                    public PlantType getPlantType(BlockGetter level, BlockPos pos) {
                        return PlantType.get("implicit");
                    }
                }

                public static final class PlacementPlant extends BushBlock {
                    @Override
                    protected boolean mayPlaceOn(
                        BlockState state,
                        BlockGetter level,
                        BlockPos pos
                    ) {
                        return state.getBlock() == MARKER || super.mayPlaceOn(state, level, pos);
                    }

                    public boolean targetMayPlaceOn(
                        BlockState state,
                        BlockGetter level,
                        BlockPos pos
                    ) {
                        return mayPlaceOn(state, level, pos);
                    }
                }

                public static final class CustomSoil extends Block {
                    @Override
                    public boolean canSustainPlant(
                        BlockState state,
                        BlockGetter level,
                        BlockPos pos,
                        Direction facing,
                        IPlantable plantable
                    ) {
                        if (plantable != CUSTOM) {
                            return super.canSustainPlant(
                                state, level, pos, facing, plantable
                            );
                        }
                        return plantable.getPlant(level, pos.relative(facing)).getBlock() == MARKER
                            && plantable.getPlantType(level, pos) == PlantType.get("machine");
                    }
                }

                public static BlockState selected(
                    Object block,
                    BlockGetter level,
                    BlockPos pos
                ) {
                    if (!(block instanceof IPlantable)) {
                        return null;
                    }
                    return ((IPlantable) block).getPlant(level, pos);
                }

                public static boolean sustain(
                    BlockState soil,
                    BlockGetter level,
                    BlockPos pos,
                    IPlantable plantable
                ) {
                    return soil.canSustainPlant(
                        level, pos, Direction.UP, plantable
                    );
                }
            }
        """)
        java("consumerA/Types.java", """
            package consumerA;
            import com.modporter.compat.plant.v1.PlantType;
            public final class Types {
                public static PlantType machine() {
                    return PlantType.get("machine");
                }
            }
        """)
        java("consumerB/Types.java", """
            package consumerB;
            import com.modporter.compat.plant.v1.PlantType;
            public final class Types {
                public static PlantType machine() {
                    return PlantType.get("machine");
                }
            }
        """)
        java("example/Main.java", """
            package example;

            import com.modporter.compat.plant.v1.LegacyPlantableAdapter;
            import com.modporter.compat.plant.v1.LegacyPlantables;
            import com.modporter.compat.plant.v1.PlantType;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.TestWorld;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.common.util.TriState;

            public final class Main {
                private static final class ExternalPlant extends Block {
                }

                private static final class ExternalSoil extends Block {
                }

                private static final class UnadaptedBush
                    extends net.minecraft.world.level.block.BushBlock {
                }

                private static final class ExternalPlacement
                    extends net.minecraft.world.level.block.BushBlock {
                }

                private static final class ExternalPlantAdapter
                    implements LegacyPlantableAdapter<ExternalPlant> {
                    private final PlantType type;

                    private ExternalPlantAdapter(PlantType type) {
                        this.type = type;
                    }

                    @Override
                    public BlockState getPlant(
                        ExternalPlant value,
                        BlockGetter level,
                        BlockPos pos
                    ) {
                        return value.defaultBlockState();
                    }

                    @Override
                    public PlantType getPlantType(
                        ExternalPlant value,
                        BlockGetter level,
                        BlockPos pos
                    ) {
                        return this.type;
                    }
                }

                public static void main(String[] args) {
                    BlockPos origin = new BlockPos(0, 0, 0);
                    TestWorld world = new TestWorld();

                    BlockState pumpkinHere = Blocks.PUMPKIN.defaultBlockState();
                    world.put(origin, pumpkinHere);
                    check(Fixture.selected(Blocks.PUMPKIN, world, origin) == pumpkinHere);

                    BlockState melonHere = Blocks.MELON.defaultBlockState();
                    world.put(origin, melonHere);
                    check(Fixture.selected(Blocks.MELON, world, origin) == melonHere);

                    check(Fixture.selected(Fixture.CUSTOM, world, origin).getBlock() == Fixture.MARKER);

                    BlockState implicitHere = Fixture.IMPLICIT.defaultBlockState();
                    world.put(origin, implicitHere);
                    check(Fixture.selected(Fixture.IMPLICIT, world, origin) == implicitHere);
                    check(LegacyPlantables.getPlantType(
                        Fixture.IMPLICIT, world, origin
                    ) == PlantType.get("implicit"));

                    BlockState exactFarmland = Blocks.FARMLAND.defaultBlockState();
                    BlockState farmlandSubclass =
                        new net.minecraft.world.level.block.ModdedFarmBlock().defaultBlockState();
                    check(LegacyPlantables.oldMayPlaceOn(
                        new net.minecraft.world.level.block.BushBlock(),
                        exactFarmland,
                        world,
                        origin
                    ));
                    check(!LegacyPlantables.oldMayPlaceOn(
                        new net.minecraft.world.level.block.BushBlock(),
                        farmlandSubclass,
                        world,
                        origin
                    ));
                    check(Fixture.PLACEMENT.targetMayPlaceOn(
                        farmlandSubclass,
                        world,
                        origin
                    ));
                    check(!LegacyPlantables.oldMayPlaceOn(
                        Fixture.PLACEMENT,
                        farmlandSubclass,
                        world,
                        origin
                    ));
                    Object[] exactFarmlandPlants = {
                        new net.minecraft.world.level.block.CropBlock(),
                        new net.minecraft.world.level.block.StemBlock(),
                        new net.minecraft.world.level.block.AttachedStemBlock(),
                        new net.minecraft.world.level.block.PitcherCropBlock()
                    };
                    for (Object plant : exactFarmlandPlants) {
                        check(LegacyPlantables.oldMayPlaceOn(
                            plant, exactFarmland, world, origin
                        ));
                        check(!LegacyPlantables.oldMayPlaceOn(
                            plant, farmlandSubclass, world, origin
                        ));
                    }

                    check(Fixture.sustain(
                        Fixture.CUSTOM_SOIL.defaultBlockState(),
                        world,
                        origin,
                        Fixture.CUSTOM
                    ));

                    Fixture.plantCalls = 0;
                    Fixture.typeCalls = 0;
                    BlockState nativeTrue = new BlockState(new net.minecraft.world.level.block.Block())
                        .withDecision(TriState.TRUE);
                    check(LegacyPlantables.canSustainPlant(
                        nativeTrue, world, origin, Direction.UP, Fixture.CUSTOM
                    ));
                    check(Fixture.plantCalls == 1);
                    check(Fixture.typeCalls == 1);

                    Fixture.plantCalls = 0;
                    Fixture.typeCalls = 0;
                    BlockState nativeFalse = new BlockState(new net.minecraft.world.level.block.Block())
                        .withDecision(TriState.FALSE);
                    check(!LegacyPlantables.canSustainPlant(
                        nativeFalse, world, origin, Direction.UP, Fixture.CUSTOM
                    ));
                    check(Fixture.plantCalls == 1);
                    check(Fixture.typeCalls == 1);

                    BlockState sand = new BlockState(new net.minecraft.world.level.block.Block())
                        .withTag(BlockTags.SAND);
                    check(LegacyPlantables.canSustainPlant(
                        sand,
                        world,
                        origin,
                        Direction.UP,
                        Blocks.CACTUS
                    ));

                    check(PlantType.get("machine") == PlantType.get("machine"));
                    check(consumerA.Types.machine() == consumerB.Types.machine());
                    check(!LegacyPlantables.isPlantable(new UnadaptedBush()));
                    ExternalPlant externalPlant = new ExternalPlant();
                    ExternalPlantAdapter sharedAdapter =
                        new ExternalPlantAdapter(PlantType.get("external"));
                    LegacyPlantables.registerPlantClass(
                        "consumerA", ExternalPlant.class, sharedAdapter
                    );
                    LegacyPlantables.registerPlantClass(
                        "consumerA", ExternalPlant.class, sharedAdapter
                    );
                    check(LegacyPlantables.getPlantType(
                        externalPlant, world, origin
                    ) == PlantType.get("external"));
                    boolean conflictingRegistrationRejected = false;
                    try {
                        LegacyPlantables.registerPlantClass(
                            "consumerA",
                            ExternalPlant.class,
                            new ExternalPlantAdapter(PlantType.get("other"))
                        );
                    } catch (IllegalStateException expected) {
                        conflictingRegistrationRejected = true;
                    }
                    check(conflictingRegistrationRejected);

                    ExternalSoil externalSoil = new ExternalSoil();
                    int[] soilAdapterCalls = {0};
                    LegacyPlantables.registerExactSoil(
                        "consumerB",
                        externalSoil,
                        (value, state, currentLevel, soilPos, facing, plantable) -> {
                            soilAdapterCalls[0]++;
                            return true;
                        }
                    );
                    check(LegacyPlantables.canSustainPlant(
                        externalSoil,
                        externalSoil.defaultBlockState(),
                        world,
                        origin,
                        Direction.UP,
                        Fixture.CUSTOM
                    ));
                    check(soilAdapterCalls[0] == 1);

                    ExternalPlacement externalPlacement = new ExternalPlacement();
                    LegacyPlantables.registerExactPlacement(
                        "consumerB",
                        externalPlacement,
                        (value, state, currentLevel, soilPos) -> state == farmlandSubclass
                    );
                    check(LegacyPlantables.oldMayPlaceOn(
                        externalPlacement,
                        farmlandSubclass,
                        world,
                        origin
                    ));
                    boolean invalidRejected = false;
                    try {
                        PlantType.get("Machine-Plant");
                    } catch (IllegalArgumentException expected) {
                        invalidRejected = true;
                    }
                    check(invalidRejected);
                }

                private static void check(boolean condition) {
                    if (!condition) throw new AssertionError();
                }
            }
        """)

        val changes = LegacyPlantablesRuntimeMigration.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        val transformed = fixture.readText()
        val transformedUnit = JavaParser().parse(transformed).result.orElseThrow()
        val nativeBushTransformed = nativeBush.readText()
        writeSharedRuntimeFixture()
        assertTrue(changes.isNotEmpty(), transformed)
        assertEquals(nativeBushOriginal, nativeBushTransformed)
        assertFalse(
            nativeBushTransformed.contains("LegacyPlantPlacement"),
            nativeBushTransformed
        )
        assertTrue(transformed.contains("implements IPlantable"), transformed)
        assertTrue(
            transformed.contains("implements com.modporter.compat.plant.v1.LegacyPlantSustainer"),
            transformed
        )
        assertTrue(
            transformed.contains("implements com.modporter.compat.plant.v1.LegacyPlantPlacement"),
            transformed
        )
        val placementPlant = transformedUnit.findAll(ClassOrInterfaceDeclaration::class.java)
            .single { it.nameAsString == "PlacementPlant" }
        assertTrue(
            placementPlant.implementedTypes.any {
                it.nameWithScope ==
                    "com.modporter.compat.plant.v1.LegacyPlantPlacement"
            },
            transformed
        )
        assertTrue(
            placementPlant.methods.any {
                it.nameAsString == "modporter${'$'}legacyMayPlaceOn"
            },
            transformed
        )
        assertTrue(transformed.contains("modporter${'$'}legacyCanSustainPlant"), transformed)
        assertTrue(transformed.contains("modporter${'$'}legacyMayPlaceOn"), transformed)
        assertTrue(transformed.contains("oldMayPlaceOnFrom("), transformed)
        assertTrue(transformed.contains("LegacyPlantables.isPlantable(block)"), transformed)
        val selected = transformedUnit.findAll(MethodDeclaration::class.java)
            .single { it.nameAsString == "selected" }
        val migratedGetPlant = selected.findAll(MethodCallExpr::class.java)
            .single {
                it.nameAsString == "getPlant" &&
                    it.scope.orElse(null)?.toString() ==
                    "com.modporter.compat.plant.v1.LegacyPlantables"
            }
        val guardedPlantable = migratedGetPlant.arguments[0].let {
            if (it is EnclosedExpr) it.inner else it
        } as? MethodCallExpr
        assertTrue(
            guardedPlantable != null &&
                guardedPlantable.nameAsString == "requirePlantable" &&
                guardedPlantable.scope.orElse(null)?.toString() ==
                "com.modporter.compat.plant.v1.LegacyPlantables" &&
                guardedPlantable.arguments.singleOrNull() is NameExpr &&
                guardedPlantable.arguments.single().toString() == "block" &&
                migratedGetPlant.arguments.drop(1).map { it.toString() } ==
                listOf("level", "pos"),
            transformed
        )
        assertFalse(transformed.contains("net.minecraftforge.common"), transformed)
        assertTrue(directory.resolve(".modporter/legacy-plant-compat.required").exists())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())

        compileAndRun("example.Main")
    }

    @Test
    fun `pattern binding gates before any write`() {
        writeMetadata()
        writeExternalCoverage()
        val file = java("example/PatternPlant.java", """
            package example;
            import net.minecraftforge.common.IPlantable;
            final class PatternPlant {
                boolean test(Object value) {
                    return value instanceof IPlantable plantable
                        && plantable != null;
                }
            }
        """)
        val original = file.readText()

        assertFailsWith<IllegalStateException> {
            LegacyPlantablesRuntimeMigration.migrate(
                StructuralRefactorPass(),
                directory,
                dryRun = false
            )
        }

        assertEquals(original, file.readText())
        assertFalse(
            directory.resolve(".modporter/legacy-plant-compat.required").exists()
        )
    }

    @Test
    fun `unresolved simple name gates and project-owned same name is exempt`() {
        writeMetadata()
        val unresolved = java("other/Unresolved.java", """
            package other;
            final class Unresolved {
                Object use(IPlantable plantable) {
                    return plantable;
                }
            }
        """)
        val original = unresolved.readText()

        assertFailsWith<IllegalStateException> {
            LegacyPlantablesRuntimeMigration.migrate(
                StructuralRefactorPass(),
                directory,
                dryRun = false
            )
        }
        assertEquals(original, unresolved.readText())

        Files.delete(unresolved)
        java("example/IPlantable.java", """
            package example;
            interface IPlantable {
                Object local();
            }
        """)
        val localUse = java("example/LocalUse.java", """
            package example;
            final class LocalUse {
                Object use(IPlantable plantable) {
                    return plantable.local();
                }
            }
        """)
        val localOriginal = localUse.readText()

        val changes = LegacyPlantablesRuntimeMigration.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        assertTrue(changes.isEmpty())
        assertEquals(localOriginal, localUse.readText())
    }

    @Test
    fun `nested interface heritage and wrong overload still receive exact bridge`() {
        writeMetadata()
        writeMinecraftStubs()
        val file = java("example/AliasedPlant.java", """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.BushBlock;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.common.IPlantable;

            final class AliasedPlant {
                interface MyPlant extends IPlantable {}

                abstract static class Base extends BushBlock implements MyPlant {
                    BlockState getPlant(int left, int right) {
                        return defaultBlockState();
                    }
                }

                static final class Child extends Base {}
            }
        """)

        LegacyPlantablesRuntimeMigration.migrate(StructuralRefactorPass(), directory, dryRun = false)

        val transformed = file.readText()
        assertTrue(transformed.contains("interface MyPlant extends IPlantable"))
        assertTrue(transformed.contains("BlockState getPlant(int left, int right)"))
        assertTrue(
            Regex("""public\s+net\.minecraft\.world\.level\.block\.state\.BlockState\s+getPlant\(""")
                .findAll(transformed)
                .count() >= 1
        )
    }

    @Test
    fun `explicit and same-package foreign types beat legacy wildcard`() {
        writeMetadata()
        java("foreign/IPlantable.java", """
            package foreign;
            public interface IPlantable {
                Object local();
            }
        """)
        val explicit = java("example/Explicit.java", """
            package example;
            import net.minecraftforge.common.*;
            import foreign.IPlantable;
            final class Explicit {
                Object use(IPlantable value) {
                    return value.local();
                }
            }
        """)
        java("same/IPlantable.java", """
            package same;
            interface IPlantable {
                Object local();
            }
        """)
        val samePackage = java("same/SamePackage.java", """
            package same;
            import net.minecraftforge.common.*;
            final class SamePackage {
                Object use(IPlantable value) {
                    return value.local();
                }
            }
        """)
        val explicitOriginal = explicit.readText()
        val samePackageOriginal = samePackage.readText()

        val changes = LegacyPlantablesRuntimeMigration.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        assertTrue(changes.isEmpty())
        assertEquals(explicitOriginal, explicit.readText())
        assertEquals(samePackageOriginal, samePackage.readText())
    }

    @Test
    fun `cross-file alias heritage selects token-free descendants`() {
        writeMetadata()
        writeBushBlockHeritageStubs()
        java("api/PlantAlias.java", """
            package api;
            import net.minecraftforge.common.IPlantable;
            public interface PlantAlias extends IPlantable {}
        """)
        java("impl/BasePlant.java", """
            package impl;
            import api.PlantAlias;
            import net.minecraft.world.level.block.BushBlock;
            public abstract class BasePlant extends BushBlock implements PlantAlias {}
        """)
        val child = java("impl/ChildPlant.java", """
            package impl;
            public final class ChildPlant extends BasePlant {}
        """)

        LegacyPlantablesRuntimeMigration.migrate(StructuralRefactorPass(), directory, dryRun = false)

        val transformed = child.readText()
        assertTrue(
            transformed.contains(
                "public net.minecraft.world.level.block.state.BlockState getPlant"
            )
        )
        assertTrue(
            directory.resolve(".modporter/legacy-plant-compat.required").exists()
        )
    }

    @Test
    fun `abstract exact getter does not suppress concrete descendant bridge`() {
        writeMetadata()
        writeBushBlockHeritageStubs()
        val base = java("impl/AbstractPlant.java", """
            package impl;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.BushBlock;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.common.IPlantable;
            public abstract class AbstractPlant extends BushBlock implements IPlantable {
                @Override
                public abstract BlockState getPlant(BlockGetter level, BlockPos pos);
            }
        """)
        val child = java("impl/ConcretePlant.java", """
            package impl;
            public final class ConcretePlant extends AbstractPlant {}
        """)

        LegacyPlantablesRuntimeMigration.migrate(StructuralRefactorPass(), directory, dryRun = false)

        assertTrue(
            base.readText().contains(
                "public abstract BlockState getPlant(BlockGetter level, BlockPos pos)"
            )
        )
        assertTrue(
            child.readText().contains(
                "public net.minecraft.world.level.block.state.BlockState getPlant"
            )
        )
    }

    @Test
    fun `explicit legacy import plus nested same-simple type gates atomically`() {
        writeMetadata()
        val file = java("example/MixedOwnership.java", """
            package example;
            import net.minecraftforge.common.IPlantable;
            final class MixedOwnership {
                interface IPlantable {}
                net.minecraftforge.common.IPlantable legacy;
                IPlantable local;
            }
        """)
        val original = file.readText()

        assertFailsWith<IllegalArgumentException> {
            LegacyPlantablesRuntimeMigration.migrate(
                StructuralRefactorPass(),
                directory,
                dryRun = false
            )
        }

        assertEquals(original, file.readText())
        assertFalse(directory.resolve(".modporter/legacy-plant-compat.required").exists())
    }

    @Test
    fun `nearest nonlegacy binding shadows outer legacy binding`() {
        writeMetadata()
        writeExternalCoverage()
        java("foreign/ForeignPlant.java", """
            package foreign;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.state.BlockState;
            public final class ForeignPlant {
                public BlockState getPlant(BlockGetter level, BlockPos pos) {
                    return null;
                }
            }
        """)
        val file = java("example/Shadow.java", """
            package example;
            import foreign.ForeignPlant;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.common.IPlantable;
            final class Shadow {
                private IPlantable value;

                BlockState use(
                    ForeignPlant external,
                    BlockGetter level,
                    BlockPos pos
                ) {
                    {
                        ForeignPlant value = external;
                        return value.getPlant(level, pos);
                    }
                }
            }
        """)

        LegacyPlantablesRuntimeMigration.migrate(StructuralRefactorPass(), directory, dryRun = false)

        val transformed = file.readText()
        assertTrue(transformed.contains("ForeignPlant value = external"))
        assertTrue(transformed.contains("return value.getPlant(level, pos)"))
        assertFalse(transformed.contains("LegacyPlantables.getPlant(value, level, pos)"))
    }

    @Test
    fun `anonymous client extension method does not duplicate its named enclosing method`() {
        writeMetadata()
        writeBushBlockHeritageStubs()
        val unrelated = java("example/AllFluids.java", """
            package example;

            import java.util.function.Consumer;

            final class AllFluids {
                interface IClientFluidTypeExtensions {
                    int getTintColor(FluidStack stack);
                }

                static final class FluidStack {}

                abstract static class TintedFluidType {
                    abstract int getTintColor(FluidStack stack);

                    void initializeClient(
                        Consumer<IClientFluidTypeExtensions> consumer
                    ) {
                        consumer.accept(new IClientFluidTypeExtensions() {
                            @Override
                            public int getTintColor(FluidStack stack) {
                                return TintedFluidType.this.getTintColor(stack);
                            }
                        });
                    }
                }
            }
        """)
        val unrelatedOriginal = unrelated.readText()
        val plant = java("example/Plant.java", """
            package example;

            import net.minecraft.world.level.block.BushBlock;
            import net.minecraftforge.common.IPlantable;

            abstract class Plant extends BushBlock implements IPlantable {}
        """)

        LegacyPlantablesRuntimeMigration.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        assertEquals(unrelatedOriginal, unrelated.readText())
        assertTrue(
            plant.readText().contains(
                "public net.minecraft.world.level.block.state.BlockState getPlant"
            )
        )
    }

    @Test
    fun `anonymous legacy plant family gates before any write`() {
        writeMetadata()
        val file = java("example/AnonymousPlant.java", """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.common.IPlantable;
            import net.minecraftforge.common.PlantType;

            final class AnonymousPlant {
                private final IPlantable value = new IPlantable() {
                    @Override
                    public BlockState getPlant(BlockGetter level, BlockPos pos) {
                        return null;
                    }

                    @Override
                    public PlantType getPlantType(BlockGetter level, BlockPos pos) {
                        return PlantType.PLAINS;
                    }
                };
            }
        """)
        val original = file.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPlantablesRuntimeMigration.migrate(
                StructuralRefactorPass(),
                directory,
                dryRun = false
            )
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "Local or anonymous legacy plant heritage"
            )
        )
        assertEquals(original, file.readText())
        assertFalse(directory.resolve(".modporter/legacy-plant-compat.required").exists())
    }

    @Test
    fun `generic scalar and varargs overloads keep distinct method identities`() {
        writeMetadata()
        writeBushBlockHeritageStubs()
        val unrelated = java("example/TagGen.java", """
            package example;

            final class TagGen {
                static class CreateTagAppender<T> {
                    CreateTagAppender<T> add(T entry) {
                        return this;
                    }

                    @SafeVarargs
                    final CreateTagAppender<T> add(T... entries) {
                        return this;
                    }
                }

                static class Bounded<T extends Number> {
                    void add(T value) {}

                    <T extends CharSequence> void add(T value) {}
                }
            }
        """)
        val unrelatedOriginal = unrelated.readText()
        val plant = java("example/GenericPlant.java", """
            package example;

            import net.minecraft.world.level.block.BushBlock;
            import net.minecraftforge.common.IPlantable;

            abstract class GenericPlant extends BushBlock implements IPlantable {}
        """)

        LegacyPlantablesRuntimeMigration.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        assertEquals(unrelatedOriginal, unrelated.readText())
        assertTrue(
            plant.readText().contains(
                "public net.minecraft.world.level.block.state.BlockState getPlant"
            )
        )
    }

    @Test
    fun `inherited implicit public interface member type resolves in nested record`() {
        writeMetadata()
        writeBushBlockHeritageStubs()
        java("com/simibubi/create/api/registry/SimpleRegistry.java", """
            package com.simibubi.create.api.registry;

            import java.util.List;

            public interface SimpleRegistry<K, V> {
                @FunctionalInterface
                interface Provider<K, V> {
                    V get(K object);

                    default void onRegister(Runnable invalidate) {}
                }

                interface Multi<K, V> extends SimpleRegistry<K, List<V>> {
                    void addProvider(Provider<K, V> provider);
                }
            }
        """)
        val registry = java("com/simibubi/create/impl/registry/SimpleRegistryImpl.java", """
            package com.simibubi.create.impl.registry;

            import com.simibubi.create.api.registry.SimpleRegistry;
            import java.util.ArrayList;
            import java.util.List;

            public abstract sealed class SimpleRegistryImpl<K, V>
                implements SimpleRegistry<K, V>
                permits SimpleRegistryImpl.SingleImpl, SimpleRegistryImpl.MultiImpl {

                protected final List<Provider<K, V>> providers = new ArrayList<>();

                static final class SingleImpl<K, V>
                    extends SimpleRegistryImpl<K, V> {}

                static final class MultiImpl<K, V>
                    extends SimpleRegistryImpl<K, List<V>>
                    implements SimpleRegistry.Multi<K, V> {

                    @Override
                    public void addProvider(Provider<K, V> provider) {
                        providers.add(new ProviderWrapper<>(provider));
                    }

                    private record ProviderWrapper<K, V>(Provider<K, V> wrapped)
                        implements Provider<K, List<V>> {

                        @Override
                        public List<V> get(K object) {
                            return List.of(wrapped.get(object));
                        }

                        @Override
                        public void onRegister(Runnable invalidate) {
                            wrapped.onRegister(invalidate);
                        }
                    }
                }
            }
        """)
        val registryOriginal = registry.readText()
        val plant = java("example/RegistryPlant.java", """
            package example;

            import net.minecraft.world.level.block.BushBlock;
            import net.minecraftforge.common.IPlantable;

            abstract class RegistryPlant extends BushBlock implements IPlantable {}
        """)

        LegacyPlantablesRuntimeMigration.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        assertEquals(registryOriginal, registry.readText())
        assertTrue(
            plant.readText().contains(
                "public net.minecraft.world.level.block.state.BlockState getPlant"
            )
        )
    }

    @Test
    fun `nested consumer record keeps components accessors and method bodies`() {
        writeMetadata()
        writeExternalCoverage()
        java(
            "com/simibubi/create/api/equipment/potatoCannon/" +
                "PotatoProjectileBlockHitAction.java",
            """
            package com.simibubi.create.api.equipment.potatoCannon;
            public interface PotatoProjectileBlockHitAction {}
            """
        )
        val file = java(
            "com/simibubi/create/content/equipment/potatoCannon/" +
                "AllPotatoProjectileBlockHitActions.java",
            """
            package com.simibubi.create.content.equipment.potatoCannon;

            import com.simibubi.create.api.equipment.potatoCannon.PotatoProjectileBlockHitAction;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.core.Holder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.LevelAccessor;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;
            import net.minecraftforge.common.IPlantable;
            import net.minecraftforge.registries.ForgeRegistries;

            public class AllPotatoProjectileBlockHitActions {
                public record PlantCrop(Holder<Block> cropBlock)
                    implements PotatoProjectileBlockHitAction {

                    public static final Object CODEC =
                        codec(PlantCrop::cropBlock, PlantCrop::new);

                    public PlantCrop(Block cropBlock) {
                        this(ForgeRegistries.BLOCKS.getDelegateOrThrow(cropBlock));
                    }

                    public boolean execute(
                        LevelAccessor level,
                        ItemStack projectile,
                        BlockHitResult ray
                    ) {
                        Direction face = ray.getDirection();
                        BlockPos hitPos = ray.getBlockPos();
                        if (!(cropBlock.value() instanceof IPlantable))
                            return false;
                        BlockState blockState = level.getBlockState(hitPos);
                        if (!blockState.canSustainPlant(
                                level,
                                hitPos,
                                face,
                                (IPlantable) cropBlock.value()
                            ))
                            return false;
                        level.setBlock(
                            hitPos.relative(face),
                            cropBlock.value().defaultBlockState(),
                            3
                        );
                        return true;
                    }
                }
            }
            """
        )

        LegacyPlantablesRuntimeMigration.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = false
        )

        val transformed = file.readText()
        assertTrue(
            transformed.contains(
                "public record PlantCrop(Holder<Block> cropBlock)"
            )
        )
        assertTrue(
            transformed.contains(
                "implements PotatoProjectileBlockHitAction"
            )
        )
        assertTrue(
            transformed.contains(
                "codec(PlantCrop::cropBlock, PlantCrop::new)"
            )
        )
        assertTrue(transformed.contains("public PlantCrop(Block cropBlock)"))
        assertTrue(
            transformed.contains(
                "this(ForgeRegistries.BLOCKS.getDelegateOrThrow(cropBlock));"
            )
        )
        assertTrue(
            transformed.contains(
                "com.modporter.compat.plant.v1.LegacyPlantables.isPlantable(" +
                    "cropBlock.value())"
            )
        )
        assertTrue(
            transformed.contains(
                "com.modporter.compat.plant.v1.LegacyPlantables.canSustainPlant("
            )
        )
        assertTrue(
            transformed.contains(
                "com.modporter.compat.plant.v1.LegacyPlantables.requirePlantable(" +
                    "cropBlock.value())"
            )
        )
        assertTrue(
            transformed.contains(
                "cropBlock.value().defaultBlockState()"
            )
        )
        assertFalse(transformed.contains("net.minecraftforge.common.IPlantable"))
    }

    @Test
    fun `legacy record component gates without changing generated value shape`() {
        writeMetadata()
        val file = java("example/PlantEnvelope.java", """
            package example;

            import net.minecraftforge.common.IPlantable;

            record PlantEnvelope(IPlantable plantable) {}
        """)
        val original = file.readText()

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyPlantablesRuntimeMigration.migrate(
                StructuralRefactorPass(),
                directory,
                dryRun = false
            )
        }

        assertTrue(failure.message.orEmpty().contains("generated accessor and value shape"))
        assertEquals(original, file.readText())
        assertFalse(directory.resolve(".modporter/legacy-plant-compat.required").exists())
    }

    @Test
    fun `record inherited plant contract gates without synthesized defaults`() {
        writeMetadata()
        java("example/PlantAlias.java", """
            package example;

            import net.minecraftforge.common.IPlantable;

            interface PlantAlias extends IPlantable {}
        """)
        val file = java("example/InheritedPlant.java", """
            package example;

            record InheritedPlant(Object value) implements PlantAlias {}
        """)
        val original = file.readText()

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyPlantablesRuntimeMigration.migrate(
                StructuralRefactorPass(),
                directory,
                dryRun = false
            )
        }

        assertTrue(failure.message.orEmpty().contains("does not synthesize getPlant"))
        assertEquals(original, file.readText())
        assertFalse(directory.resolve(".modporter/legacy-plant-compat.required").exists())
    }

    @Test
    fun `record overload collapse gates atomically`() {
        writeMetadata()
        writeExternalCoverage()
        val file = java("example/AmbiguousRecord.java", """
            package example;

            import net.minecraftforge.common.IPlantable;

            record AmbiguousRecord(Object value) {
                void accept(IPlantable plantable) {}
                void accept(Object plantable) {}
            }
        """)
        val original = file.readText()

        val failure = assertFailsWith<IllegalArgumentException> {
            LegacyPlantablesRuntimeMigration.migrate(
                StructuralRefactorPass(),
                directory,
                dryRun = false
            )
        }

        assertTrue(failure.message.orEmpty().contains("collapses overload accept(java.lang.Object)"))
        assertEquals(original, file.readText())
        assertFalse(directory.resolve(".modporter/legacy-plant-compat.required").exists())
    }

    @Test
    fun `bound legacy method reference gates atomically`() {
        writeMetadata()
        writeExternalCoverage()
        val file = java("example/BoundReference.java", """
            package example;
            import java.util.function.BiFunction;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraftforge.common.IPlantable;
            final class BoundReference {
                BiFunction<BlockGetter, BlockPos, BlockState> getter(IPlantable plantable) {
                    return plantable::getPlant;
                }
            }
        """)
        val original = file.readText()

        assertFailsWith<IllegalStateException> {
            LegacyPlantablesRuntimeMigration.migrate(
                StructuralRefactorPass(),
                directory,
                dryRun = false
            )
        }

        assertEquals(original, file.readText())
        assertFalse(directory.resolve(".modporter/legacy-plant-compat.required").exists())
    }

    private fun writeExternalCoverage() {
        val templates = runtimeTemplates()
        val sourceHash = runtimeSourceHash(templates)
        val jarHash = checkNotNull(
            javaClass.getResource("/compat/legacy-plant-compat-v1.jar.sha256")
        ).readText().trim()
        val file = directory.resolve(
            ".modporter/legacy-plant-external-coverage.properties"
        )
        file.parent.createDirectories()
        val artifact = directory.resolve(
            ".modporter/coverage-fixtures/verified-target.bin"
        )
        artifact.parent.createDirectories()
        artifact.writeText("verified target dependency fixture")
        val entryValues = sortedMapOf(
            "entry.0.artifactPath" to
                ".modporter/coverage-fixtures/verified-target.bin",
            "entry.0.artifactSha256" to fileSha256(artifact),
            "entry.0.coverage" to "verified-vanilla",
            "entry.0.id" to "target:fixture"
        )
        val canonicalInventory = entryValues.entries.joinToString(
            "\n",
            postfix = "\n"
        ) { (key, value) -> "$key=$value" }
        file.writeText(
            buildString {
                appendLine("schema=legacy-plant-external-coverage-v1")
                appendLine("producer=dependency-conversion-v1")
                appendLine("complete=true")
                appendLine("runtimeSourceSha256=$sourceHash")
                appendLine("runtimeJarSha256=$jarHash")
                appendLine("entryCount=1")
                appendLine("inventorySha256=${textSha256(canonicalInventory)}")
                for ((key, value) in entryValues) appendLine("$key=$value")
            }
        )
    }

    private fun fileSha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private fun textSha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun writeSharedRuntimeFixture() {
        val templates = runtimeTemplates()
        val sourceHash = runtimeSourceHash(templates)
        for ((name, template) in templates) {
            java(
                "com/modporter/compat/plant/v1/$name.java",
                template
                    .replace("@PACKAGE@", "com.modporter.compat.plant.v1")
                    .replace("@RUNTIME_SOURCE_SHA256@", sourceHash)
            )
        }
    }

    private fun runtimeTemplates(): Map<String, String> =
        listOf(
            "PlantType",
            "IPlantable",
            "LegacyPlantSustainer",
            "LegacyPlantPlacement",
            "LegacyPlantableAdapter",
            "LegacySoilAdapter",
            "LegacyPlantPlacementAdapter",
            "LegacyPlantables"
        ).associateWith { name ->
            checkNotNull(javaClass.getResource("/compat/$name.java.template")).readText()
        }

    private fun runtimeSourceHash(templates: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, content) in templates.toSortedMap()) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeMetadata() {
        val file = directory.resolve("src/main/resources/META-INF/mods.toml")
        file.parent.createDirectories()
        file.writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"
            license="All Rights Reserved"

            [[mods]]
            modId="fixture"
            version="1"
            displayName="Fixture"
        """.trimIndent())
    }

    private fun writeMinecraftStubs() {
        val sources = linkedMapOf(
            "net/minecraft/core/BlockPos.java" to """
                package net.minecraft.core;

                public final class BlockPos {
                    public final int x;
                    public final int y;
                    public final int z;

                    public BlockPos(int x, int y, int z) {
                        this.x = x;
                        this.y = y;
                        this.z = z;
                    }

                    public BlockPos relative(Direction direction) {
                        return new BlockPos(
                            this.x + direction.dx,
                            this.y + direction.dy,
                            this.z + direction.dz
                        );
                    }

                    public BlockPos above() {
                        return relative(Direction.UP);
                    }

                    @Override
                    public boolean equals(Object other) {
                        if (!(other instanceof BlockPos pos)) return false;
                        return this.x == pos.x && this.y == pos.y && this.z == pos.z;
                    }

                    @Override
                    public int hashCode() {
                        return (this.x * 31 + this.y) * 31 + this.z;
                    }
                }
            """,
            "net/minecraft/core/Direction.java" to """
                package net.minecraft.core;

                import java.util.Arrays;
                import java.util.Iterator;

                public enum Direction {
                    DOWN(0, -1, 0),
                    UP(0, 1, 0),
                    NORTH(0, 0, -1),
                    SOUTH(0, 0, 1),
                    WEST(-1, 0, 0),
                    EAST(1, 0, 0);

                    public final int dx;
                    public final int dy;
                    public final int dz;

                    Direction(int dx, int dy, int dz) {
                        this.dx = dx;
                        this.dy = dy;
                        this.dz = dz;
                    }

                    public enum Plane implements Iterable<Direction> {
                        HORIZONTAL;

                        @Override
                        public Iterator<Direction> iterator() {
                            return Arrays.asList(NORTH, SOUTH, WEST, EAST).iterator();
                        }
                    }
                }
            """,
            "net/minecraft/tags/BlockTags.java" to """
                package net.minecraft.tags;

                public final class BlockTags {
                    public static final Object SAND = new Object();
                    public static final Object DIRT = new Object();
                    public static final Object DEAD_BUSH_MAY_PLACE_ON = new Object();
                    public static final Object NYLIUM = new Object();
                    public static final Object SMALL_DRIPLEAF_PLACEABLE = new Object();
                    private BlockTags() {}
                }
            """,
            "net/minecraft/tags/FluidTags.java" to """
                package net.minecraft.tags;

                public final class FluidTags {
                    public static final Object WATER = new Object();
                    private FluidTags() {}
                }
            """,
            "net/minecraft/world/level/material/FluidState.java" to """
                package net.minecraft.world.level.material;

                public final class FluidState {
                    public static final FluidState EMPTY = new FluidState(null);
                    private final Object tag;

                    public FluidState(Object tag) {
                        this.tag = tag;
                    }

                    public boolean is(Object expected) {
                        return this.tag == expected;
                    }

                    public boolean isEmpty() {
                        return this.tag == null;
                    }

                    public Object getType() {
                        return this.tag;
                    }

                    public boolean isSourceOfType(Object expected) {
                        return this.tag == expected;
                    }
                }
            """,
            "net/minecraft/world/level/material/Fluids.java" to """
                package net.minecraft.world.level.material;
                public final class Fluids {
                    public static final Object WATER = new Object();
                    public static final Object EMPTY = null;
                    private Fluids() {}
                }
            """,
            "net/neoforged/neoforge/common/util/TriState.java" to """
                package net.neoforged.neoforge.common.util;
                public enum TriState {
                    TRUE, DEFAULT, FALSE;
                    public boolean isTrue() { return this == TRUE; }
                    public boolean isDefault() { return this == DEFAULT; }
                    public boolean isFalse() { return this == FALSE; }
                }
            """,
            "net/minecraft/world/phys/shapes/VoxelShape.java" to """
                package net.minecraft.world.phys.shapes;
                import net.minecraft.core.Direction;
                public final class VoxelShape {
                    private final boolean empty;
                    public VoxelShape(boolean empty) { this.empty = empty; }
                    public VoxelShape getFaceShape(Direction direction) { return this; }
                    public boolean isEmpty() { return this.empty; }
                }
            """,
            "net/minecraft/world/level/BlockGetter.java" to """
                package net.minecraft.world.level;

                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.level.material.FluidState;

                public interface BlockGetter {
                    BlockState getBlockState(BlockPos pos);
                    FluidState getFluidState(BlockPos pos);
                }
            """,
            "net/minecraft/world/level/TestWorld.java" to """
                package net.minecraft.world.level;

                import java.util.HashMap;
                import java.util.Map;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.Blocks;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.level.material.FluidState;

                public final class TestWorld implements BlockGetter {
                    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
                    private final Map<BlockPos, FluidState> fluids = new HashMap<>();

                    public void put(BlockPos pos, BlockState state) {
                        this.blocks.put(pos, state);
                    }

                    @Override
                    public BlockState getBlockState(BlockPos pos) {
                        return this.blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    }

                    @Override
                    public FluidState getFluidState(BlockPos pos) {
                        return this.fluids.getOrDefault(pos, FluidState.EMPTY);
                    }
                }
            """,
            "net/minecraft/world/level/block/Block.java" to """
                package net.minecraft.world.level.block;

                import net.minecraft.world.level.block.state.BlockState;

                public class Block {
                    public BlockState defaultBlockState() {
                        return new BlockState(this);
                    }
                }
            """,
            "net/minecraft/world/level/block/state/BlockState.java" to """
                package net.minecraft.world.level.block.state;

                import java.util.HashSet;
                import java.util.Set;
                import net.minecraft.core.BlockPos;
                import net.minecraft.core.Direction;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.phys.shapes.VoxelShape;
                import net.neoforged.neoforge.common.util.TriState;

                public final class BlockState {
                    private final Block block;
                    private final Set<Object> tags = new HashSet<>();
                    private boolean sturdy;
                    private TriState decision = TriState.DEFAULT;

                    public BlockState(Block block) {
                        this.block = block;
                    }

                    public Block getBlock() {
                        return this.block;
                    }

                    public BlockState withTag(Object tag) {
                        this.tags.add(tag);
                        return this;
                    }

                    public BlockState withDecision(TriState decision) {
                        this.decision = decision;
                        return this;
                    }

                    public boolean is(Block expected) {
                        return this.block == expected;
                    }

                    public boolean is(Object tag) {
                        return this.tags.contains(tag);
                    }

                    public boolean isFaceSturdy(
                        BlockGetter level,
                        BlockPos pos,
                        Direction direction
                    ) {
                        return this.sturdy;
                    }

                    public boolean isSolidRender(BlockGetter level, BlockPos pos) {
                        return this.sturdy;
                    }

                    public VoxelShape getCollisionShape(BlockGetter level, BlockPos pos) {
                        return new VoxelShape(!this.sturdy);
                    }

                    public TriState canSustainPlant(
                        BlockGetter level,
                        BlockPos pos,
                        Direction direction,
                        BlockState plant
                    ) {
                        return this.decision;
                    }
                }
            """,
            "net/minecraft/world/level/block/BushBlock.java" to """
                package net.minecraft.world.level.block;

                import net.minecraft.core.BlockPos;
                import net.minecraft.tags.BlockTags;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.state.BlockState;

                public class BushBlock extends Block {
                    protected boolean mayPlaceOn(
                        BlockState state,
                        BlockGetter level,
                        BlockPos pos
                    ) {
                        return state.is(BlockTags.DIRT)
                            || state.getBlock() instanceof FarmBlock;
                    }
                }
            """,
            "net/minecraft/world/level/block/FarmBlock.java" to
                "package net.minecraft.world.level.block; public class FarmBlock extends Block {}",
            "net/minecraft/world/level/block/ModdedFarmBlock.java" to
                "package net.minecraft.world.level.block; public final class ModdedFarmBlock extends FarmBlock {}",
            "net/minecraft/world/level/block/BambooStalkBlock.java" to
                "package net.minecraft.world.level.block; public class BambooStalkBlock extends Block {}",
            "net/minecraft/world/level/block/CactusBlock.java" to
                "package net.minecraft.world.level.block; public class CactusBlock extends Block {}",
            "net/minecraft/world/level/block/SugarCaneBlock.java" to
                "package net.minecraft.world.level.block; public class SugarCaneBlock extends Block {}",
            "net/minecraft/world/level/block/PumpkinBlock.java" to
                "package net.minecraft.world.level.block; public class PumpkinBlock extends Block {}",
            "net/minecraft/world/level/block/CropBlock.java" to """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.state.BlockState;
                public class CropBlock extends BushBlock {
                    @Override
                    protected boolean mayPlaceOn(
                        BlockState state, BlockGetter level, BlockPos pos
                    ) {
                        return state.getBlock() instanceof FarmBlock;
                    }
                }
            """,
            "net/minecraft/world/level/block/AttachedStemBlock.java" to """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.state.BlockState;
                public class AttachedStemBlock extends BushBlock {
                    @Override
                    protected boolean mayPlaceOn(
                        BlockState state, BlockGetter level, BlockPos pos
                    ) {
                        return state.getBlock() instanceof FarmBlock;
                    }
                }
            """,
            "net/minecraft/world/level/block/AzaleaBlock.java" to
                "package net.minecraft.world.level.block; public class AzaleaBlock extends BushBlock {}",
            "net/minecraft/world/level/block/DeadBushBlock.java" to
                "package net.minecraft.world.level.block; public class DeadBushBlock extends BushBlock {}",
            "net/minecraft/world/level/block/FungusBlock.java" to
                "package net.minecraft.world.level.block; public class FungusBlock extends BushBlock {}",
            "net/minecraft/world/level/block/MangrovePropaguleBlock.java" to
                "package net.minecraft.world.level.block; public class MangrovePropaguleBlock extends SaplingBlock {}",
            "net/minecraft/world/level/block/MushroomBlock.java" to
                "package net.minecraft.world.level.block; public class MushroomBlock extends BushBlock {}",
            "net/minecraft/world/level/block/NetherSproutsBlock.java" to
                "package net.minecraft.world.level.block; public class NetherSproutsBlock extends BushBlock {}",
            "net/minecraft/world/level/block/NetherWartBlock.java" to
                "package net.minecraft.world.level.block; public class NetherWartBlock extends BushBlock {}",
            "net/minecraft/world/level/block/PitcherCropBlock.java" to """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.state.BlockState;
                public class PitcherCropBlock extends CropBlock {
                    @Override
                    protected boolean mayPlaceOn(
                        BlockState state, BlockGetter level, BlockPos pos
                    ) {
                        return state.getBlock() instanceof FarmBlock;
                    }
                }
            """,
            "net/minecraft/world/level/block/RootsBlock.java" to
                "package net.minecraft.world.level.block; public class RootsBlock extends BushBlock {}",
            "net/minecraft/world/level/block/SeaPickleBlock.java" to
                "package net.minecraft.world.level.block; public class SeaPickleBlock extends BushBlock {}",
            "net/minecraft/world/level/block/SeagrassBlock.java" to
                "package net.minecraft.world.level.block; public class SeagrassBlock extends BushBlock {}",
            "net/minecraft/world/level/block/SmallDripleafBlock.java" to
                "package net.minecraft.world.level.block; public class SmallDripleafBlock extends BushBlock {}",
            "net/minecraft/world/level/block/StemBlock.java" to """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.state.BlockState;
                public class StemBlock extends BushBlock {
                    @Override
                    protected boolean mayPlaceOn(
                        BlockState state, BlockGetter level, BlockPos pos
                    ) {
                        return state.getBlock() instanceof FarmBlock;
                    }
                }
            """,
            "net/minecraft/world/level/block/TallSeagrassBlock.java" to
                "package net.minecraft.world.level.block; public class TallSeagrassBlock extends BushBlock {}",
            "net/minecraft/world/level/block/WaterlilyBlock.java" to
                "package net.minecraft.world.level.block; public class WaterlilyBlock extends BushBlock {}",
            "net/minecraft/world/level/block/WitherRoseBlock.java" to
                "package net.minecraft.world.level.block; public class WitherRoseBlock extends FlowerBlock {}",
            "net/minecraft/world/level/block/SaplingBlock.java" to
                "package net.minecraft.world.level.block; public class SaplingBlock extends BushBlock {}",
            "net/minecraft/world/level/block/FlowerBlock.java" to
                "package net.minecraft.world.level.block; public class FlowerBlock extends BushBlock {}",
            "net/minecraft/world/level/block/GlazedTerracottaBlock.java" to
                "package net.minecraft.world.level.block; public class GlazedTerracottaBlock extends Block {}",
            "net/minecraft/world/level/block/IceBlock.java" to
                "package net.minecraft.world.level.block; public class IceBlock extends Block {}",
            "net/minecraft/world/level/block/Blocks.java" to """
                package net.minecraft.world.level.block;

                public final class Blocks {
                    public static final Block AIR = new Block();
                    public static final Block MELON = new Block();
                    public static final PumpkinBlock PUMPKIN = new PumpkinBlock();
                    public static final CactusBlock CACTUS = new CactusBlock();
                    public static final SugarCaneBlock SUGAR_CANE = new SugarCaneBlock();
                    public static final CropBlock PITCHER_CROP = new CropBlock();
                    public static final BushBlock DEAD_BUSH = new BushBlock();
                    public static final Block LILY_PAD = new Block();
                    public static final Block RED_MUSHROOM = new Block();
                    public static final Block BROWN_MUSHROOM = new Block();
                    public static final Block NETHER_WART = new Block();
                    public static final BushBlock TALL_GRASS = new BushBlock();
                    public static final Block TERRACOTTA = new Block();
                    public static final Block SOUL_SAND = new Block();
                    public static final FarmBlock FARMLAND = new FarmBlock();
                    public static final Block WATER = new Block();
                    public static final Block FROSTED_ICE = new IceBlock();
                    public static final Block CLAY = new Block();
                    public static final Block MYCELIUM = new Block();
                    public static final Block SOUL_SOIL = new Block();
                    public static final Block NETHERRACK = new Block();
                    public static final Block MAGMA_BLOCK = new Block();
                    private Blocks() {}
                }
            """
        )
        for ((path, source) in sources) {
            java(path, source)
        }
    }

    private fun writeBushBlockHeritageStubs() {
        java(
            "net/minecraft/world/level/block/Block.java",
            "package net.minecraft.world.level.block; public class Block {}"
        )
        java(
            "net/minecraft/world/level/block/BushBlock.java",
            """
            package net.minecraft.world.level.block;
            public class BushBlock extends Block {}
            """
        )
    }

    private fun java(relative: String, source: String): Path {
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }

    private fun compileAndRun(mainClass: String) {
        val javaHome = Path.of(System.getProperty("java.home"))
        val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val javac = javaHome.resolve("bin/javac$executableSuffix").toString()
        val java = javaHome.resolve("bin/java$executableSuffix").toString()
        val classes = directory.resolve("classes").createDirectories()
        val sources = Files.walk(directory.resolve("src/main/java")).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.map(Path::toString).toList()
        }

        val compile = ProcessBuilder(listOf(javac, "-d", classes.toString()) + sources)
            .redirectErrorStream(true)
            .start()
        val compileOutput = compile.inputStream.bufferedReader().readText()
        assertEquals(0, compile.waitFor(), compileOutput)

        val run = ProcessBuilder(java, "-cp", classes.toString(), mainClass)
            .redirectErrorStream(true)
            .start()
        val runOutput = run.inputStream.bufferedReader().readText()
        assertEquals(0, run.waitFor(), runOutput)
    }
}
