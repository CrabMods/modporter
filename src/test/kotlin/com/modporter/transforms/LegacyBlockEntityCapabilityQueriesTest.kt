package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyBlockEntityCapabilityQueries
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

class LegacyBlockEntityCapabilityQueriesTest {
    @TempDir
    lateinit var directory: Path

    private val sourceRoot: Path
        get() = directory.resolve("src/main/java/example")

    private val compatPackage = "com.modporter.generated.example.compat"

    private fun java(name: String, source: String): Path {
        val file = sourceRoot.resolve("$name.java")
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
        return file
    }

    private fun migrate(dryRun: Boolean = false): LegacyBlockEntityCapabilityQueries.Result =
        LegacyBlockEntityCapabilityQueries.migrate(directory, { compatPackage }, dryRun)

    @Test
    fun `clean17 capability family uses the typed boundary and preserves invalidation`() {
        writeBlockEntityGraph()
        val itemHelper = java(
            "ItemHelper",
            """
            package example;

            import java.util.Optional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;

            interface IBE<T extends BlockEntity> {
                Optional<T> getBlockEntityOptional(Level level, BlockPos pos);
            }

            class ItemHelper {
                static <T extends IBE<? extends BlockEntity>> int redstone(
                    T ibe, Level level, BlockPos pos
                ) {
                    return ibe.getBlockEntityOptional(level, pos)
                        .map(be -> be.getCapability(LegacyCapabilities.ITEM_HANDLER, null))
                        .map(lo -> lo.map(value -> 1).orElse(0))
                        .orElse(0);
                }
            }
            """
        )
        val beltTunnel = java(
            "BeltTunnelBlockEntity",
            """
            package example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import com.modporter.generated.example.compat.LegacyCapabilityProvider;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;

            class BeltTunnelBlockEntity extends SmartBlockEntity
                implements LegacyCapabilityProvider {
                LazyOptional<Object> cap = LazyOptional.empty();

                public <T> LazyOptional<T> getCapability(
                    Capability<T> capability, Direction side
                ) {
                    if (capability != LegacyCapabilities.ITEM_HANDLER)
                        return LazyOptional.empty();
                    BlockEntity below = getLevel().getBlockEntity(getBlockPos().below());
                    if (below != null) {
                        T found = below.getCapability(capability, Direction.UP).orElse(null);
                    }
                    return cap.cast();
                }
            }
            """
        )
        val manipulation = java(
            "CapManipulationBehaviourBase",
            """
            package example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.fluids.capability.IFluidHandler;
            import net.neoforged.neoforge.items.IItemHandler;

            abstract class CapManipulationBehaviourBase<T> {
                LazyOptional<T> targetCapability;
                boolean findNewNextTick;
                boolean bypassSided;

                CapManipulationBehaviourBase() {
                    targetCapability = LazyOptional.empty();
                    findNewNextTick = false;
                    bypassSided = false;
                }

                abstract Capability<T> capability();

                void onNeighborChanged() {
                    onHandlerInvalidated(targetCapability);
                }

                void onHandlerInvalidated(LazyOptional<T> handler) {
                    findNewNextTick = true;
                    targetCapability = LazyOptional.empty();
                }

                boolean hasInventory() {
                    return targetCapability.isPresent();
                }

                T getInventory() {
                    return targetCapability.orElse(null);
                }

                void lazyTick(BlockEntity target, Direction side) {
                    if (!targetCapability.isPresent())
                        findNewCapability(target, side);
                }

                void findNewCapability(BlockEntity target, Direction side) {
                    targetCapability = LazyOptional.empty();
                    Capability<T> capability = capability();
                    targetCapability = bypassSided
                        ? target.getCapability(capability, null)
                        : target.getCapability(capability, side);
                }
            }

            class InvManipulationBehaviour
                extends CapManipulationBehaviourBase<IItemHandler> {
                Capability<IItemHandler> capability() {
                    return Capabilities.ItemHandler.BLOCK;
                }
            }

            class TankManipulationBehaviour
                extends CapManipulationBehaviourBase<IFluidHandler> {
                Capability<IFluidHandler> capability() {
                    return Capabilities.FluidHandler.BLOCK;
                }
            }
            """
        )
        val direct = java(
            "DirectBeltInputBehaviour",
            """
            package example;

            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import net.minecraft.core.Direction;
            import net.neoforged.neoforge.items.IItemHandler;

            class DirectBeltInputBehaviour extends BlockEntityBehaviour {
                IItemHandler insert(Direction side) {
                    LazyOptional<IItemHandler> lazy = blockEntity.getCapability(
                        LegacyCapabilities.ITEM_HANDLER, side
                    );
                    if (!lazy.isPresent())
                        return null;
                    return lazy.orElse(null);
                }
            }
            """
        )
        val connectivity = java(
            "ConnectivityHandler",
            """
            package example;

            import com.modporter.generated.example.compat.LegacyCapabilities;
            import net.minecraft.world.level.block.entity.BlockEntity;

            class ConnectivityHandler {
                static <T extends BlockEntity & Container> void split(T be) {
                    be.getCapability(LegacyCapabilities.ITEM_HANDLER).invalidate();
                    be.getCapability(LegacyCapabilities.FLUID_HANDLER, null)
                        .invalidate();
                }
            }

            interface Container {
            }
            """
        )
        val repackager = java(
            "RepackagerScenes",
            """
            package example;

            import com.modporter.generated.example.compat.LegacyCapabilities;
            import java.util.function.Consumer;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.createmod.ponder.foundation.PonderSceneBuilder;

            class CreateSceneBuilder extends PonderSceneBuilder {
                WorldInstructions world() {
                    return null;
                }

                class WorldInstructions extends PonderWorldInstructions {
                }
            }

            class RepackagerScenes {
                static void insert(CreateSceneBuilder scene, BlockPos pos) {
                    scene.world().modifyBlockEntity(pos, BlockEntity.class, be -> {
                        Object handler = be.getCapability(
                            LegacyCapabilities.ITEM_HANDLER
                        ).orElse(null);
                    });
                }
            }
            """
        )
        val computer = java(
            "ComputerBehaviour",
            """
            package example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.CapabilityManager;
            import com.modporter.generated.example.compat.CapabilityToken;

            class ComputerBehaviour {
                Capability<Object> peripheral;
            }
            """
        )

        val result = migrate()

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        val item = itemHelper.readText()
        assertTrue(
            item.contains(
                "LegacyBlockEntityCapabilityQueries.get(be, " +
                    "LegacyCapabilities.ITEM_HANDLER, null)"
            ),
            item
        )
        val tunnel = beltTunnel.readText()
        assertTrue(
            tunnel.contains(
                "LegacyBlockEntityCapabilityQueries.get(" +
                    "below, capability, Direction.UP)"
            ),
            tunnel
        )
        val cap = manipulation.readText()
        assertTrue(cap.contains("return LegacyCapabilities.ITEM_HANDLER;"), cap)
        assertTrue(cap.contains("return LegacyCapabilities.FLUID_HANDLER;"), cap)
        assertTrue(
            cap.contains(
                "LegacyBlockEntityCapabilityQueries.get(target, capability, null)"
            ),
            cap
        )
        assertTrue(
            cap.contains(
                "LegacyBlockEntityCapabilityQueries.get(target, capability, side)"
            ),
            cap
        )
        assertTrue(
            direct.readText().contains(
                "LegacyBlockEntityCapabilityQueries.get(" +
                    "blockEntity, LegacyCapabilities.ITEM_HANDLER, side)"
            ),
            direct.readText()
        )
        val invalidated = connectivity.readText()
        assertTrue(
            invalidated.contains(
                "LegacyBlockEntityCapabilityQueries.invalidate(" +
                    "be, LegacyCapabilities.ITEM_HANDLER, null)"
            ),
            invalidated
        )
        assertTrue(
            invalidated.contains(
                "LegacyBlockEntityCapabilityQueries.invalidate(" +
                    "be, LegacyCapabilities.FLUID_HANDLER, null)"
            ),
            invalidated
        )
        assertFalse(invalidated.contains(").invalidate()"), invalidated)
        assertTrue(
            repackager.readText().contains(
                "LegacyBlockEntityCapabilityQueries.get(" +
                    "be, LegacyCapabilities.ITEM_HANDLER, null)"
            ),
            repackager.readText()
        )
        val computerSource = computer.readText()
        assertFalse(computerSource.contains("CapabilityManager"), computerSource)
        assertFalse(computerSource.contains("CapabilityToken"), computerSource)

        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/example/compat/" +
                "LegacyBlockEntityCapabilityQueries.java"
        )
        val boundary = helper.readText()
        assertTrue(
            boundary.contains("return provider.getCapability(capability, side);"),
            boundary
        )
        assertTrue(
            boundary.contains(
                "return LazyOptional.of(() -> nativeCapability(" +
                    "blockEntity, capability, side));"
            ),
            boundary
        )
        assertTrue(
            boundary.contains(
                "provider.getCapability(capability, side).invalidate();"
            ),
            boundary
        )
        assertTrue(boundary.contains("blockEntity.invalidateCapabilities();"), boundary)
        assertTrue(boundary.contains("Capabilities.ItemHandler.BLOCK"), boundary)
        assertTrue(boundary.contains("Capabilities.FluidHandler.BLOCK"), boundary)
        assertTrue(boundary.contains("Capabilities.EnergyStorage.BLOCK"), boundary)

        val second = migrate()
        assertTrue(second.changes.isEmpty(), second.changes.toString())
        assertTrue(second.gates.isEmpty(), second.gates.joinToString("\n"))
    }

    @Test
    fun `mixed block entity and entity capability queries keep receiver specific keys`() {
        writeBlockEntityGraph()
        val file = java(
            "MixedCapabilityReceivers",
            """
            package example;

            import net.minecraft.core.Direction;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;

            class MixedCapabilityReceivers {
                boolean query(BlockEntity blockEntity, Entity entity, Direction side) {
                    boolean block = blockEntity.getCapability(
                        Capabilities.ItemHandler.BLOCK, side
                    ).isPresent();
                    boolean other = entity.getCapability(
                        Capabilities.ItemHandler.BLOCK, side
                    ).isPresent();
                    return block && other;
                }
            }
            """
        )

        val result = migrate()

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        val source = file.readText()
        assertTrue(
            source.contains(
                "LegacyBlockEntityCapabilityQueries.get(" +
                    "blockEntity, LegacyCapabilities.ITEM_HANDLER, side).isPresent()"
            ),
            source
        )
        assertTrue(
            source.contains(
                "entity.getCapability(\n" +
                    "            Capabilities.ItemHandler.BLOCK, side"
            ),
            source
        )
        assertFalse(source.contains("entity, LegacyCapabilities.ITEM_HANDLER"), source)
    }

    @Test
    fun `native fallback refuses listener identity return argument and inherited field escapes`() {
        writeBlockEntityGraph()
        val file = java(
            "LifecycleEscapes",
            """
            package example;

            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import net.minecraft.world.level.block.entity.BlockEntity;

            class LifecycleEscapes {
                LazyOptional<Object> listener(BlockEntity be) {
                    LazyOptional<Object> value =
                        be.getCapability(LegacyCapabilities.ITEM_HANDLER);
                    value.addListener(ignored -> {});
                    return value;
                }

                boolean identity(BlockEntity be) {
                    LazyOptional<Object> value =
                        be.getCapability(LegacyCapabilities.ITEM_HANDLER);
                    return value == LazyOptional.empty();
                }

                LazyOptional<Object> returned(BlockEntity be) {
                    return be.getCapability(LegacyCapabilities.ITEM_HANDLER);
                }

                void argument(BlockEntity be) {
                    consume(be.getCapability(LegacyCapabilities.ITEM_HANDLER));
                }

                void consume(LazyOptional<Object> value) {
                }
            }

            class CachedBase {
                LazyOptional<Object> cached = LazyOptional.empty();

                void load(BlockEntity be) {
                    cached = be.getCapability(LegacyCapabilities.ITEM_HANDLER);
                }
            }

            class CachedChild extends CachedBase {
                void watch() {
                    cached.addListener(ignored -> {});
                }
            }
            """
        )
        val original = file.readText()

        val result = migrate()

        assertEquals(original, file.readText())
        assertTrue(result.changes.isEmpty(), result.changes.toString())
        assertEquals(5, result.gates.size, result.gates.joinToString("\n"))
        assertTrue(result.gates.all { it.contains("cannot prove LazyOptional lifecycle use") })
    }

    @Test
    fun `source declared legacy query keeps direct dispatch while BlockEntity uses boundary`() {
        writeBlockEntityGraph()
        val file = java(
            "OwnedQueries",
            """
            package example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import com.modporter.generated.example.compat.LegacyCapabilityProvider;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;

            class OwnedBlockEntity extends SmartBlockEntity
                implements LegacyCapabilityProvider {
                public <T> LazyOptional<T> getCapability(
                    Capability<T> capability, Direction side
                ) {
                    return LazyOptional.empty();
                }
            }

            class OwnedQueries {
                void query(OwnedBlockEntity owned, BlockEntity erased) {
                    owned.getCapability(
                        LegacyCapabilities.ITEM_HANDLER, Direction.NORTH
                    );
                    erased.getCapability(
                        LegacyCapabilities.ITEM_HANDLER, Direction.SOUTH
                    );
                }
            }
            """
        )

        val result = migrate()

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        val source = file.readText()
        assertTrue(
            source.contains(
                "owned.getCapability(\n" +
                    "            LegacyCapabilities.ITEM_HANDLER, Direction.NORTH"
            ),
            source
        )
        assertTrue(
            source.contains(
                "LegacyBlockEntityCapabilityQueries.get(" +
                    "erased, LegacyCapabilities.ITEM_HANDLER, Direction.SOUTH)"
            ),
            source
        )
    }

    @Test
    fun `self recursive value bounds fail closed without blocking proven receivers`() {
        writeBlockEntityGraph()
        val file = java(
            "RecursiveCapabilityReceiver",
            """
            package example;

            import com.modporter.generated.example.compat.LegacyCapabilities;
            import net.minecraft.world.level.block.entity.BlockEntity;

            interface Recursive<T extends Recursive<T>> {
            }

            class RecursiveCapabilityReceiver<R extends Recursive<R>> {
                boolean unresolved(R receiver) {
                    return receiver.getCapability(
                        LegacyCapabilities.ITEM_HANDLER
                    ).isPresent();
                }

                boolean proven(BlockEntity receiver) {
                    return receiver.getCapability(
                        LegacyCapabilities.ITEM_HANDLER
                    ).isPresent();
                }
            }
            """
        )

        val result = migrate()

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        val source = file.readText()
        assertTrue(
            source.contains(
                "receiver.getCapability(\n" +
                    "            LegacyCapabilities.ITEM_HANDLER\n" +
                    "        ).isPresent();"
            ),
            source
        )
        assertTrue(
            source.contains(
                "LegacyBlockEntityCapabilityQueries.get(" +
                    "receiver, LegacyCapabilities.ITEM_HANDLER, null).isPresent()"
            ),
            source
        )
    }

    @Test
    fun `provider typed receiver preserves exact optional through unknown tooltip consumer`() {
        writeBlockEntityGraph()
        val file = java(
            "FluidTankBlockEntity",
            """
            package example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import com.modporter.generated.example.compat.LegacyCapabilityProvider;
            import net.minecraft.core.Direction;

            class FluidTankBlockEntity extends SmartBlockEntity
                implements LegacyCapabilityProvider {
                LazyOptional<Object> fluidCapability = LazyOptional.empty();

                boolean tooltip(FluidTankBlockEntity controller) {
                    return containedFluidTooltip(
                        controller.getCapability(
                            LegacyCapabilities.FLUID_HANDLER, null
                        )
                    );
                }

                boolean containedFluidTooltip(LazyOptional<Object> capability) {
                    return capability.isPresent();
                }

                public <T> LazyOptional<T> getCapability(
                    Capability<T> capability, Direction side
                ) {
                    if (capability == LegacyCapabilities.FLUID_HANDLER)
                        return fluidCapability.cast();
                    return LazyOptional.empty();
                }
            }
            """
        )
        val original = file.readText()

        val result = migrate()

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(original, file.readText())
        assertTrue(result.changes.isEmpty(), result.changes.toString())
    }

    @Test
    fun `canonical collisions unknown keys and non block entities are refused`() {
        val shadowBlockEntity = java(
            "ShadowBlockEntity",
            """
            package example;

            import com.modporter.generated.example.compat.LegacyCapabilities;

            class BlockEntity {
            }

            class ShadowBlockEntity {
                void query(BlockEntity be) {
                    be.getCapability(LegacyCapabilities.ITEM_HANDLER);
                }
            }
            """
        )
        val shadowLegacy = java(
            "ShadowLegacy",
            """
            package example;

            import net.minecraft.world.level.block.entity.BlockEntity;

            class LegacyCapabilities {
                static final Object ITEM_HANDLER = new Object();
            }

            class ShadowLegacy {
                void query(BlockEntity be) {
                    be.getCapability(LegacyCapabilities.ITEM_HANDLER);
                }
            }
            """
        )
        val unknown = java(
            "UnknownCapability",
            """
            package example;

            import com.modporter.generated.example.compat.LegacyCapabilities;
            import net.minecraft.world.level.block.entity.BlockEntity;

            class UnknownCapability {
                void query(BlockEntity be) {
                    be.getCapability(LegacyCapabilities.CUSTOM);
                }
            }
            """
        )
        val nonBlockEntity = java(
            "NonBlockEntity",
            """
            package example;

            import com.modporter.generated.example.compat.LegacyCapabilities;

            class Holder {
                void getCapability(Object capability) {
                }
            }

            class NonBlockEntity {
                void query(Holder holder) {
                    holder.getCapability(LegacyCapabilities.ITEM_HANDLER);
                }
            }
            """
        )

        val result = migrate()

        assertTrue(result.changes.isEmpty(), result.changes.toString())
        assertTrue(
            shadowBlockEntity.readText().contains(
                "be.getCapability(LegacyCapabilities.ITEM_HANDLER)"
            )
        )
        assertTrue(
            shadowLegacy.readText().contains(
                "be.getCapability(LegacyCapabilities.ITEM_HANDLER)"
            )
        )
        assertTrue(
            unknown.readText().contains(
                "be.getCapability(LegacyCapabilities.CUSTOM)"
            )
        )
        assertTrue(
            nonBlockEntity.readText().contains(
                "holder.getCapability(LegacyCapabilities.ITEM_HANDLER)"
            )
        )
    }

    @Test
    fun `retained provider self query uses the legacy key and explicit null side`() {
        writeBlockEntityGraph()
        val retained = java(
            "RetainedProvider",
            """
            package example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilities;
            import com.modporter.generated.example.compat.LegacyCapabilityProvider;
            import net.minecraft.core.Direction;
            import net.neoforged.neoforge.capabilities.Capabilities;

            class RetainedProvider extends SmartBlockEntity implements LegacyCapabilityProvider {
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    return LazyOptional.empty();
                }

                boolean tooltip() {
                    return consume(getCapability(Capabilities.FluidHandler.BLOCK));
                }

                boolean consume(LazyOptional<?> value) {
                    return value.isPresent();
                }
            }
            """
        )
        val explicitOverload = java(
            "ExplicitOneArgumentProvider",
            """
            package example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilityProvider;
            import net.minecraft.core.Direction;
            import net.neoforged.neoforge.capabilities.Capabilities;

            class ExplicitOneArgumentProvider extends SmartBlockEntity implements LegacyCapabilityProvider {
                public <T> LazyOptional<T> getCapability(Capability<T> capability) {
                    return LazyOptional.empty();
                }

                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    return LazyOptional.empty();
                }

                LazyOptional<?> query() {
                    return getCapability(Capabilities.FluidHandler.BLOCK);
                }
            }
            """
        )

        val result = migrate()

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertTrue(
            retained.readText().contains(
                "getCapability(LegacyCapabilities.FLUID_HANDLER, null)"
            ),
            retained.readText()
        )
        assertTrue(
            explicitOverload.readText().contains(
                "getCapability(Capabilities.FluidHandler.BLOCK)"
            ),
            explicitOverload.readText()
        )
    }

    @Test
    fun `dry run reports source and helper without writing either`() {
        val file = java(
            "DryRunQuery",
            """
            package example;

            import com.modporter.generated.example.compat.LegacyCapabilities;
            import net.minecraft.world.level.block.entity.BlockEntity;

            class DryRunQuery {
                void query(BlockEntity be) {
                    be.getCapability(LegacyCapabilities.ENERGY);
                }
            }
            """
        )
        val original = file.readText()
        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/example/compat/" +
                "LegacyBlockEntityCapabilityQueries.java"
        )

        val result = migrate(dryRun = true)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        // the rewritten source, the boundary helper, and the two key types the boundary names
        assertEquals(4, result.changes.size, result.changes.toString())
        assertEquals(
            setOf("DryRunQuery.java", "LegacyBlockEntityCapabilityQueries.java", "LegacyCapabilities.java", "LegacyCapabilityProvider.java"),
            result.changes.map { it.file.fileName.toString() }.toSet()
        )
        assertEquals(original, file.readText())
        assertFalse(helper.exists())
        assertFalse(helper.resolveSibling("LegacyCapabilities.java").exists())
        assertFalse(helper.resolveSibling("LegacyCapabilityProvider.java").exists())
    }

    private fun writeBlockEntityGraph() {
        java(
            "BlockEntityGraph",
            """
            package example;

            import net.minecraft.world.level.block.entity.BlockEntity;

            abstract class SmartBlockEntity extends BlockEntity {
            }

            abstract class BlockEntityBehaviour {
                public SmartBlockEntity blockEntity;
            }
            """
        )
    }
}
