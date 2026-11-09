package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.ProviderArityRepair
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProviderArityRepairSourceGraphTest {
    @TempDir
    lateinit var tempDir: Path

    private val sourceRoot: Path
        get() = tempDir.resolve("src/main/java/example")

    private fun java(name: String, source: String): Path {
        sourceRoot.createDirectories()
        val file = sourceRoot.resolve("$name.java")
        file.writeText(source.trimIndent() + "\n")
        return file
    }

    private fun migrate() {
        val files = sourceRoot.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.toPath() }
            .toList()
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        ProviderArityRepair.migrate(pass, tempDir, dryRun = false)
    }

    private fun assertProviderBindsCall(
        source: String,
        methodName: String,
        callName: String,
        providerArgumentIndex: Int
    ) {
        val method = JavaParser().parse(source).result.orElseThrow()
            .findAll(MethodDeclaration::class.java)
            .single { it.nameAsString == methodName }
        val provider = method.parameters.singleOrNull {
            it.typeAsString.endsWith("HolderLookup.Provider")
        }
        assertTrue(provider != null, source)
        assertTrue(
            method.findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == callName }
                .any { call ->
                    call.arguments.getOrNull(providerArgumentIndex)
                        ?.takeIf { it.isNameExpr }
                        ?.asNameExpr()
                        ?.nameAsString == provider!!.nameAsString
                },
            source
        )
    }

    @Test
    fun `qualified fields static owners and platform receivers use the provider in scope`() {
        java(
            "EdgePointStorage",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            class EdgePointStorage {
                void read(CompoundTag tag, DimensionPalette dimensions,
                          HolderLookup.Provider registries) {
                }
            }
            """
        )
        val graph = java(
            "TrackGraph",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            class TrackGraph {
                private final EdgePointStorage edgePoints = new EdgePointStorage();

                static TrackGraph read(CompoundTag tag, DimensionPalette dimensions,
                                       HolderLookup.Provider registries) {
                    TrackGraph graph = new TrackGraph();
                    graph.edgePoints.read(tag, dimensions);
                    return graph;
                }
            }
            """
        )
        java(
            "ScheduleRuntime",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            class ScheduleRuntime {
                void read(CompoundTag tag, HolderLookup.Provider registries) {
                }
            }
            """
        )
        val train = java(
            "Train",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            class Train {
                private final ScheduleRuntime runtime = new ScheduleRuntime();

                static Train read(CompoundTag tag, HolderLookup.Provider registries) {
                    Train train = new Train();
                    train.runtime.read(tag);
                    return train;
                }
            }
            """
        )
        java(
            "RailwaySavedData",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.server.MinecraftServer;

            class RailwaySavedData {
                static RailwaySavedData load(MinecraftServer server,
                                             HolderLookup.Provider registries) {
                    return new RailwaySavedData();
                }
            }
            """
        )
        java(
            "LocalSavedData",
            """
            package example;

            import net.minecraft.server.MinecraftServer;

            class LocalSavedData {
                void load(MinecraftServer server) {
                }
            }
            """
        )
        java(
            "MinecraftServer",
            """
            package example;

            class MinecraftServer {
            }
            """
        )
        val unrelatedServer = java(
            "UnrelatedServerRailwayManager",
            """
            package example;

            class UnrelatedServerRailwayManager {
                void load(MinecraftServer server) {
                    RailwaySavedData.load(server);
                }
            }
            """
        )
        val global = java(
            "GlobalRailwayManager",
            """
            package example;

            import net.minecraft.server.MinecraftServer;

            class GlobalRailwayManager {
                void load(MinecraftServer server) {
                    RailwaySavedData.load(server);
                }

                void shadow(MinecraftServer server, LocalSavedData RailwaySavedData) {
                    RailwaySavedData.load(server);
                }

                void ambiguous(MinecraftServer primary, MinecraftServer secondary) {
                    RailwaySavedData.load(primary);
                }
            }
            """
        )
        java(
            "ToolboxMountedStorage",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            class ToolboxMountedStorage {
                static ToolboxMountedStorage fromLegacy(CompoundTag nbt,
                                                         HolderLookup.Provider registries) {
                    ItemStackHandler inventory = new ItemStackHandler();
                    inventory.deserializeNBT(nbt);
                    return new ToolboxMountedStorage();
                }
            }
            """
        )
        val mounted = java(
            "MountedStorageManager",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            class MountedStorageManager {
                void readLegacy(CompoundTag nbt, HolderLookup.Provider registries) {
                    ToolboxMountedStorage.fromLegacy(nbt);
                    ItemStackHandler fallback = new ItemStackHandler();
                    fallback.deserializeNBT(nbt);
                }
            }
            """
        )
        val opaque = java(
            "OpaqueHolder",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            class OpaqueHolder {
                private final ExternalStorage edgePoints;

                OpaqueHolder(ExternalStorage edgePoints) {
                    this.edgePoints = edgePoints;
                }

                void read(CompoundTag tag, DimensionPalette dimensions,
                          HolderLookup.Provider registries) {
                    this.edgePoints.read(tag, dimensions);
                }
            }
            """
        )
        java(
            "DimensionPalette",
            """
            package example;
            class DimensionPalette {
            }
            """
        )

        migrate()

        assertTrue(
            graph.readText().contains("graph.edgePoints.read(tag, dimensions, registries);"),
            graph.readText()
        )
        assertTrue(
            train.readText().contains("train.runtime.read(tag, registries);"),
            train.readText()
        )
        val globalResult = global.readText()
        assertTrue(
            globalResult.contains("RailwaySavedData.load(server, server.registryAccess());"),
            globalResult
        )
        assertTrue(globalResult.contains("RailwaySavedData.load(server);"), globalResult)
        assertTrue(globalResult.contains("RailwaySavedData.load(primary);"), globalResult)
        assertFalse(globalResult.contains("RailwaySavedData.load(primary, primary.registryAccess());"), globalResult)
        val unrelatedServerResult = unrelatedServer.readText()
        assertTrue(unrelatedServerResult.contains("RailwaySavedData.load(server);"), unrelatedServerResult)
        assertFalse(
            unrelatedServerResult.contains("RailwaySavedData.load(server, server.registryAccess());"),
            unrelatedServerResult
        )
        val mountedResult = mounted.readText()
        assertTrue(
            mountedResult.contains("ToolboxMountedStorage.fromLegacy(nbt, registries);"),
            mountedResult
        )
        assertTrue(
            mountedResult.contains("fallback.deserializeNBT(registries, nbt);"),
            mountedResult
        )
        val legacy = sourceRoot.resolve("ToolboxMountedStorage.java").readText()
        assertTrue(
            legacy.contains("inventory.deserializeNBT(registries, nbt);"),
            legacy
        )
        val opaqueResult = opaque.readText()
        assertTrue(
            opaqueResult.contains("this.edgePoints.read(tag, dimensions);"),
            opaqueResult
        )
        assertFalse(opaqueResult.contains("this.edgePoints.read(tag, dimensions, registries);"), opaqueResult)
    }

    @Test
    fun `a source resolved trailing null is removed only for the unique shorter arity`() {
        java(
            "FlowSource",
            """
            package example;

            import net.minecraft.world.level.Level;

            class FlowSource {
                void manageSource(Level level) {
                }
            }
            """
        )
        java(
            "OverloadedFlowSource",
            """
            package example;

            import net.minecraft.world.level.Level;

            class OverloadedFlowSource {
                void manageSource(Level level) {
                }

                void manageSource(Level level, Object context) {
                }
            }
            """
        )
        val pipe = java(
            "PipeConnection",
            """
            package example;

            import net.minecraft.world.level.Level;

            class PipeConnection {
                void update(Level level, FlowSource source,
                            OverloadedFlowSource overloaded, Object context) {
                    source.manageSource(level, null);
                    source.manageSource(level, context);
                    overloaded.manageSource(level, null);
                }
            }
            """
        )

        migrate()

        val migrated = pipe.readText()
        assertTrue(migrated.contains("source.manageSource(level);"), migrated)
        assertTrue(migrated.contains("source.manageSource(level, context);"), migrated)
        assertTrue(migrated.contains("overloaded.manageSource(level, null);"), migrated)
        assertFalse(migrated.contains("source.manageSource(level, null);"), migrated)
    }

    @Test
    fun `codex17 provider residues use canonical receivers lexical bounds and source roots`() {
        val itemRequirement = java(
            "ItemRequirement",
            """
            package example;

            import net.createmod.catnip.nbt.NBTProcessors;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.decoration.ArmorStand;
            import net.minecraft.world.entity.decoration.ItemFrame;
            import net.minecraft.world.item.ItemStack;

            class ItemRequirement {
                static ItemStack frame(Entity entity) {
                    if (entity instanceof ItemFrame itemFrame)
                        return NBTProcessors.withUnsafeNBTDiscarded(itemFrame.getItem());
                    if (entity instanceof ArmorStand armorStand)
                        return armorStand.getAllSlots().stream()
                            .map(stack -> NBTProcessors.withUnsafeNBTDiscarded(stack))
                            .findFirst().orElse(ItemStack.EMPTY);
                    return ItemStack.EMPTY;
                }
            }
            """
        )
        val shadowedProcessors = java(
            "ShadowedProcessors",
            """
            package example;

            import net.createmod.catnip.nbt.NBTProcessors;
            import net.minecraft.world.item.ItemStack;

            class ShadowedProcessors {
                ItemStack keep(ItemStack stack, LocalProcessor NBTProcessors) {
                    return NBTProcessors.withUnsafeNBTDiscarded(stack);
                }
            }

            class LocalProcessor {
                ItemStack withUnsafeNBTDiscarded(ItemStack stack) {
                    return stack;
                }
            }
            """
        )
        val unrelatedProcessors = java(
            "UnrelatedProcessors",
            """
            package example;

            import net.minecraft.world.item.ItemStack;

            class UnrelatedProcessors {
                ItemStack keep(ItemStack stack) {
                    return NBTProcessors.withUnsafeNBTDiscarded(stack);
                }
            }

            class NBTProcessors {
                static ItemStack withUnsafeNBTDiscarded(ItemStack stack) {
                    return stack;
                }
            }
            """
        )
        val collidingComponentProcessors = java(
            "CollidingComponentProcessors",
            """
            package example;

            import net.createmod.catnip.nbt.NBTProcessors;
            import net.minecraft.world.item.ItemStack;

            class CollidingComponentProcessors {
                ItemStack migrate(ItemStack stack) {
                    return NBTProcessors.withUnsafeNBTDiscarded(stack);
                }
            }

            class ComponentProcessors {
            }
            """
        )
        val basin = java(
            "BasinBlockEntity",
            """
            package example;

            import java.util.List;
            import net.createmod.catnip.data.IntAttached;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.fluids.FluidStack;

            class BasinBlockEntity {
                List<IntAttached<FluidStack>> visualizedOutputFluids;

                void write(CompoundTag tag, HolderLookup.Provider registries) {
                    tag.put("Fluids", NBTHelper.writeCompoundList(
                        visualizedOutputFluids,
                        ia -> ia.getValue().writeToNBT(new CompoundTag())
                    ));
                }
            }
            """
        )
        val pipe = java(
            "PipeConnection",
            """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.fluids.FluidStack;

            class PipeConnection {
                Flow flow;

                void serializeNBT(CompoundTag tag, boolean clientPacket) {
                    CompoundTag flowData = new CompoundTag();
                    flow.fluid.writeToNBT(flowData);
                    tag.put("Flow", flowData);
                }

                void deserializeNBT(CompoundTag tag, BlockPos pos, boolean clientPacket) {
                    FluidStack fluid = FluidStack.parseOptional(
                        net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                            net.minecraft.core.registries.BuiltInRegistries.REGISTRY
                        ),
                        tag.getCompound("Flow")
                    );
                    flow = new Flow(fluid);
                }

                static class Flow {
                    FluidStack fluid;
                    Flow(FluidStack fluid) {
                        this.fluid = fluid;
                    }
                }
            }
            """
        )
        val transport = java(
            "FluidTransportBehaviour",
            """
            package example;

            import java.util.Map;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            class FluidTransportBehaviour {
                Map<Direction, PipeConnection> interfaces;

                void write(CompoundTag nbt, boolean clientPacket,
                           HolderLookup.Provider registries) {
                    interfaces.values().forEach(connection ->
                        connection.serializeNBT(nbt, clientPacket));
                }

                void read(CompoundTag nbt, BlockPos pos, boolean clientPacket,
                          HolderLookup.Provider registries) {
                    interfaces.values().forEach(connection ->
                        connection.deserializeNBT(nbt, pos, clientPacket));
                }

                PipeConnection first() {
                    return interfaces.values().iterator().next();
                }

                void writeFirst(CompoundTag nbt, boolean clientPacket,
                                HolderLookup.Provider registries) {
                    first().serializeNBT(nbt, clientPacket);
                }
            }
            """
        )
        java(
            "TrackEdgePoint",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            class TrackEdgePoint {
                void read(CompoundTag tag, boolean migration,
                          DimensionPalette dimensions,
                          HolderLookup.Provider registries) {
                }
            }

            class DimensionPalette {
                static DimensionPalette read(CompoundTag tag) {
                    return new DimensionPalette();
                }
            }
            """
        )
        val targeting = java(
            "TrackTargetingBehaviour",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;

            class TrackTargetingBehaviour<T extends TrackEdgePoint> {
                Level getWorld() {
                    return null;
                }

                T restore(T point, CompoundTag data) {
                    Level level = getWorld();
                    boolean isClientSide = level.isClientSide;
                    if (isClientSide)
                        return null;
                    point.read(data, true, DimensionPalette.read(data));
                    return point;
                }

                <T> T shadow(T point, CompoundTag data, Level level) {
                    point.read(data, true, DimensionPalette.read(data));
                    return point;
                }
            }
            """
        )
        val unrelatedGeneric = java(
            "UnrelatedGeneric",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;

            class UnrelatedGeneric<T> {
                void restore(T point, CompoundTag data, Level level) {
                    point.read(data, true, DimensionPalette.read(data));
                }
            }
            """
        )
        val oriented = java(
            "OrientedContraptionEntity",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.vehicle.MinecartFurnace;

            class OrientedContraptionEntity {
                CompoundTag save(Entity riding) {
                    if (!(riding instanceof MinecartFurnace furnaceCart))
                        return new CompoundTag();
                    return furnaceCart.serializeNBT();
                }
            }
            """
        )
        val unrelatedMinecart = java(
            "UnrelatedMinecart",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;

            class UnrelatedMinecart {
                CompoundTag save(Object riding) {
                    if (!(riding instanceof MinecartFurnace furnaceCart))
                        return new CompoundTag();
                    return furnaceCart.serializeNBT();
                }

                static class MinecartFurnace {
                    CompoundTag serializeNBT() {
                        return new CompoundTag();
                    }
                }
            }
            """
        )
        val shulker = java(
            "ShulkerFillLevelAttribute",
            """
            package example;

            import java.util.List;
            import net.minecraft.core.NonNullList;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class ShulkerFillLevelAttribute {
                ShulkerLevels levels;

                boolean appliesTo(ItemStack stack, Level level) {
                    return levels.canApply(stack);
                }

                enum ShulkerLevels {
                    FULL;

                    boolean canApply(ItemStack stack) {
                        CompoundTag tag = new CompoundTag();
                        NonNullList<ItemStack> items =
                            NonNullList.withSize(27, ItemStack.EMPTY);
                        ContainerHelper.loadAllItems(tag, items);
                        return true;
                    }
                }

                static class Type {
                    List<ShulkerLevels> values(ItemStack stack, Level level) {
                        return java.util.Arrays.stream(ShulkerLevels.values())
                            .filter(value -> value.canApply(stack))
                            .toList();
                    }
                }
            }
            """
        )

        migrate()

        val itemResult = itemRequirement.readText()
        assertTrue(
            itemResult.contains("import net.createmod.catnip.components.ComponentProcessors;"),
            itemResult
        )
        assertFalse(itemResult.contains("import net.createmod.catnip.nbt.NBTProcessors;"), itemResult)
        assertTrue(
            itemResult.contains("ComponentProcessors.withUnsafeComponentsDiscarded(itemFrame.getItem())"),
            itemResult
        )
        assertTrue(
            itemResult.contains("ComponentProcessors.withUnsafeComponentsDiscarded(stack)"),
            itemResult
        )
        assertTrue(
            shadowedProcessors.readText().contains("NBTProcessors.withUnsafeNBTDiscarded(stack)"),
            shadowedProcessors.readText()
        )
        assertTrue(
            unrelatedProcessors.readText().contains("NBTProcessors.withUnsafeNBTDiscarded(stack)"),
            unrelatedProcessors.readText()
        )
        val collisionResult = collidingComponentProcessors.readText()
        assertTrue(
            collisionResult.contains(
                "net.createmod.catnip.components.ComponentProcessors.withUnsafeComponentsDiscarded(stack)"
            ),
            collisionResult
        )
        assertFalse(
            collisionResult.contains("import net.createmod.catnip.components.ComponentProcessors;"),
            collisionResult
        )

        val basinResult = basin.readText()
        assertTrue(
            basinResult.contains(
                "net.minecraft.nbt.CompoundTag.class.cast(ia.getValue().saveOptional(registries))"
            ),
            basinResult
        )
        assertFalse(basinResult.contains("ia.getValue().writeToNBT"), basinResult)

        val pipeResult = pipe.readText()
        assertProviderBindsCall(pipeResult, "serializeNBT", "save", 0)
        assertProviderBindsCall(pipeResult, "deserializeNBT", "parseOptional", 0)
        assertFalse(pipeResult.contains("RegistryAccess.fromRegistryOfRegistries"), pipeResult)
        val transportResult = transport.readText()
        assertTrue(
            transportResult.contains("connection.serializeNBT(nbt, clientPacket, registries)"),
            transportResult
        )
        assertTrue(
            transportResult.contains("connection.deserializeNBT(nbt, pos, clientPacket, registries)"),
            transportResult
        )
        assertTrue(
            transportResult.contains("first().serializeNBT(nbt, clientPacket, registries)"),
            transportResult
        )

        val targetingResult = targeting.readText()
        assertTrue(
            targetingResult.contains(
                "point.read(data, true, DimensionPalette.read(data), level.registryAccess());"
            ),
            targetingResult
        )
        assertTrue(
            targetingResult.contains("point.read(data, true, DimensionPalette.read(data));"),
            targetingResult
        )
        assertTrue(
            unrelatedGeneric.readText().contains(
                "point.read(data, true, DimensionPalette.read(data));"
            ),
            unrelatedGeneric.readText()
        )

        val orientedResult = oriented.readText()
        assertTrue(
            orientedResult.contains("furnaceCart.serializeNBT(furnaceCart.registryAccess())"),
            orientedResult
        )
        assertTrue(
            unrelatedMinecart.readText().contains("furnaceCart.serializeNBT()"),
            unrelatedMinecart.readText()
        )

        val shulkerResult = shulker.readText()
        assertTrue(
            shulkerResult.contains(
                "boolean canApply(ItemStack stack, HolderLookup.Provider registries)"
            ),
            shulkerResult
        )
        assertTrue(shulkerResult.contains("levels.canApply(stack, level.registryAccess())"), shulkerResult)
        assertTrue(shulkerResult.contains("value.canApply(stack, level.registryAccess())"), shulkerResult)
        assertTrue(
            shulkerResult.contains("ContainerHelper.loadAllItems(tag, items, registries)"),
            shulkerResult
        )
    }

    @Test
    fun `source provider roots reject overloads method references and nullable callers`() {
        val overloaded = java(
            "OverloadedLevels",
            """
            package example;

            import net.minecraft.core.NonNullList;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class OverloadedLevels {
                boolean canApply(ItemStack stack) {
                    NonNullList<ItemStack> items =
                        NonNullList.withSize(27, ItemStack.EMPTY);
                    ContainerHelper.loadAllItems(new CompoundTag(), items);
                    return true;
                }

                boolean canApply(ItemStack stack, int count) {
                    return count > 0;
                }

                boolean use(ItemStack stack, Level level) {
                    return canApply(stack);
                }
            }
            """
        )
        val referenced = java(
            "ReferencedLevels",
            """
            package example;

            import java.util.function.Predicate;
            import net.minecraft.core.NonNullList;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class ReferencedLevels {
                boolean canApply(ItemStack stack) {
                    NonNullList<ItemStack> items =
                        NonNullList.withSize(27, ItemStack.EMPTY);
                    ContainerHelper.loadAllItems(new CompoundTag(), items);
                    return true;
                }

                boolean use(ItemStack stack, Level level) {
                    Predicate<ItemStack> predicate = this::canApply;
                    return canApply(stack) && predicate.test(stack);
                }
            }
            """
        )
        val nullable = java(
            "NullableLevels",
            """
            package example;

            import javax.annotation.Nullable;
            import net.minecraft.core.NonNullList;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class NullableLevels {
                boolean canApply(ItemStack stack) {
                    NonNullList<ItemStack> items =
                        NonNullList.withSize(27, ItemStack.EMPTY);
                    ContainerHelper.loadAllItems(new CompoundTag(), items);
                    return true;
                }

                boolean use(ItemStack stack, @Nullable Level level) {
                    return canApply(stack);
                }
            }
            """
        )
        val inherited = java(
            "InheritedLevels",
            """
            package example;

            import net.minecraft.core.NonNullList;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            class BaseLevels {
                boolean canApply(ItemStack stack) {
                    return false;
                }
            }

            class InheritedLevels extends BaseLevels {
                boolean canApply(ItemStack stack) {
                    NonNullList<ItemStack> items =
                        NonNullList.withSize(27, ItemStack.EMPTY);
                    ContainerHelper.loadAllItems(new CompoundTag(), items);
                    return true;
                }

                boolean use(ItemStack stack, Level level) {
                    return canApply(stack);
                }
            }
            """
        )

        migrate()

        for (file in listOf(overloaded, referenced, nullable, inherited)) {
            val result = file.readText()
            assertTrue(result.contains("boolean canApply(ItemStack stack)"), result)
            assertTrue(
                result.contains("ContainerHelper.loadAllItems(new CompoundTag(), items);"),
                result
            )
            assertFalse(result.contains("HolderLookup.Provider"), result)
        }
        assertTrue(referenced.readText().contains("this::canApply"), referenced.readText())
        assertTrue(nullable.readText().contains("return canApply(stack);"), nullable.readText())
    }
}
