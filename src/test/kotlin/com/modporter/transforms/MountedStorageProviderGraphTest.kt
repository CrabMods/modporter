package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MountedStorageProviderGraphTest {
    @TempDir
    lateinit var tempDir: Path

    private val sourceRoot: Path
        get() = tempDir.resolve("src/main/java/example")

    private fun java(name: String, source: String): Path {
        sourceRoot.createDirectories()
        return sourceRoot.resolve("$name.java").also {
            it.writeText(source.trimIndent() + "\n")
        }
    }

    @Test
    fun `mounted storage legacy readers receive the provider through both real source roots`() {
        val toolbox = java(
            "ToolboxMountedStorage",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            class ToolboxMountedStorage {
                static ToolboxMountedStorage fromLegacy(CompoundTag nbt) {
                    ItemStackHandler inventory = new ItemStackHandler();
                    inventory.deserializeNBT(nbt);
                    return new ToolboxMountedStorage();
                }
            }
            """
        )
        val vault = java(
            "ItemVaultMountedStorage",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            class ItemVaultMountedStorage {
                static ItemVaultMountedStorage fromLegacy(CompoundTag nbt) {
                    ItemStackHandler inventory = new ItemStackHandler();
                    inventory.deserializeNBT(nbt);
                    return new ItemVaultMountedStorage();
                }
            }
            """
        )
        val depot = java(
            "DepotMountedStorage",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            class DepotMountedStorage {
                static DepotMountedStorage fromLegacy(CompoundTag nbt) {
                    ItemStackHandler inventory = new ItemStackHandler();
                    inventory.deserializeNBT(nbt);
                    return new DepotMountedStorage();
                }
            }
            """
        )
        val manager = java(
            "MountedStorageManager",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;
            import org.jetbrains.annotations.Nullable;

            class MountedStorageManager {
                public void read(CompoundTag nbt, boolean clientPacket,
                                 @Nullable Contraption contraption) {
                    readLegacy(nbt);
                }

                private void readLegacy(CompoundTag nbt) {
                    ToolboxMountedStorage.fromLegacy(nbt);
                    ItemVaultMountedStorage.fromLegacy(nbt);
                    DepotMountedStorage.fromLegacy(nbt);
                    ItemStackHandler fallback = new ItemStackHandler();
                    fallback.deserializeNBT(nbt);
                }
            }
            """
        )
        val cargo = java(
            "TrainCargoManager",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import org.jetbrains.annotations.Nullable;

            class TrainCargoManager extends MountedStorageManager {
                @Override
                public void read(CompoundTag nbt, boolean clientPacket,
                                 @Nullable Contraption contraption) {
                    super.read(nbt, clientPacket, contraption);
                }
            }
            """
        )
        val contraption = java(
            "Contraption",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;

            class Contraption {
                private final MountedStorageManager storage = new MountedStorageManager();

                public void readNBT(Level world, CompoundTag nbt, boolean spawnData) {
                    storage.read(nbt, spawnData, this);
                }
            }
            """
        )
        java(
            "DimensionPalette",
            """
            package example;

            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.level.Level;

            class DimensionPalette {
                ResourceKey<Level> decode(int index) {
                    return Level.OVERWORLD;
                }
            }
            """
        )
        val carriage = java(
            "Carriage",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.level.Level;

            class Carriage {
                private final TrainCargoManager storage = new TrainCargoManager();

                DimensionalCarriageEntity getDimensional(Level level) {
                    return getDimensional(level.dimension());
                }

                DimensionalCarriageEntity getDimensional(ResourceKey<Level> dimension) {
                    return new DimensionalCarriageEntity();
                }

                static Carriage read(CompoundTag tag, DimensionPalette dimensions) {
                    Carriage carriage = new Carriage();
                    carriage.getDimensional(dimensions.decode(0)).read(tag);
                    return carriage;
                }

                class DimensionalCarriageEntity {
                    void read(CompoundTag tag) {
                        storage.read(tag, false, null);
                    }
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
                static Train read(CompoundTag tag, DimensionPalette dimensions,
                                  HolderLookup.Provider registries) {
                    Carriage.read(tag, dimensions);
                    return new Train();
                }
            }
            """
        )

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        val managerResult = manager.readText()
        assertTrue(
            Regex(
                """public\s+void\s+read\s*\(\s*CompoundTag\s+nbt\s*,\s*boolean\s+clientPacket\s*,""" +
                    """\s*@Nullable\s+Contraption\s+contraption\s*,\s*HolderLookup\.Provider\s+registries\s*\)"""
            ).containsMatchIn(managerResult),
            managerResult
        )
        assertTrue(
            managerResult.contains("private void readLegacy(CompoundTag nbt, HolderLookup.Provider registries)"),
            managerResult
        )
        assertTrue(managerResult.contains("readLegacy(nbt, registries);"), managerResult)
        assertTrue(managerResult.contains("ToolboxMountedStorage.fromLegacy(nbt, registries);"), managerResult)
        assertTrue(managerResult.contains("ItemVaultMountedStorage.fromLegacy(nbt, registries);"), managerResult)
        assertTrue(managerResult.contains("DepotMountedStorage.fromLegacy(nbt, registries);"), managerResult)
        assertTrue(managerResult.contains("fallback.deserializeNBT(registries, nbt);"), managerResult)

        for (storage in listOf(toolbox, vault, depot)) {
            val migrated = storage.readText()
            assertTrue(
                migrated.contains("fromLegacy(CompoundTag nbt, HolderLookup.Provider registries)"),
                migrated
            )
            assertTrue(migrated.contains("inventory.deserializeNBT(registries, nbt);"), migrated)
        }

        val cargoResult = cargo.readText()
        assertTrue(
            Regex(
                """public\s+void\s+read\s*\(\s*CompoundTag\s+nbt\s*,\s*boolean\s+clientPacket\s*,""" +
                    """\s*@Nullable\s+Contraption\s+contraption\s*,\s*HolderLookup\.Provider\s+registries\s*\)"""
            ).containsMatchIn(cargoResult),
            cargoResult
        )
        assertTrue(cargoResult.contains("super.read(nbt, clientPacket, contraption, registries);"), cargoResult)

        val contraptionResult = contraption.readText()
        assertTrue(
            contraptionResult.contains("storage.read(nbt, spawnData, this, world.registryAccess());"),
            contraptionResult
        )

        val carriageResult = carriage.readText()
        assertTrue(
            Regex(
                """static\s+Carriage\s+read\s*\(\s*CompoundTag\s+tag\s*,""" +
                    """\s*DimensionPalette\s+dimensions\s*,""" +
                    """\s*(?:net\.minecraft\.core\.)?HolderLookup\.Provider\s+registries\s*\)"""
            ).containsMatchIn(carriageResult),
            carriageResult
        )
        assertTrue(
            carriageResult.contains(
                "carriage.getDimensional(dimensions.decode(0)).read(tag, registries);"
            ),
            carriageResult
        )
        assertTrue(
            Regex(
                """void\s+read\(\s*CompoundTag\s+tag\s*,""" +
                    """\s*(?:net\.minecraft\.core\.)?HolderLookup\.Provider\s+registries\s*\)"""
            ).containsMatchIn(carriageResult),
            carriageResult
        )
        assertTrue(
            carriageResult.contains("storage.read(tag, false, null, registries);"),
            carriageResult
        )

        val trainResult = train.readText()
        assertTrue(trainResult.contains("Carriage.read(tag, dimensions, registries);"), trainResult)

        val allResults = listOf(
            managerResult,
            cargoResult,
            contraptionResult,
            carriageResult,
            trainResult,
            toolbox.readText(),
            vault.readText(),
            depot.readText()
        ).joinToString("\n")
        assertFalse(allResults.contains("Minecraft.getInstance()"), allResults)
        assertFalse(allResults.contains("RegistryAccess.EMPTY"), allResults)
    }
}
