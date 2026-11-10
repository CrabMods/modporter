package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactExternalProviderCallMigration
import com.modporter.core.transforms.structural.ExactProjectProviderCallMigration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactExternalProviderCallMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `BlockEntity legacy self calls use the callers declared provider`() {
        val file = write("sample/SyncedBlockEntity.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            abstract class SyncedBlockEntity extends BlockEntity {
                void readClient(CompoundTag tag, HolderLookup.Provider registries) {
                    load(tag);
                }
                void writeClient(CompoundTag tag, HolderLookup.Provider registries) {
                    saveAdditional(tag);
                }
            }
        """.trimIndent())

        val changes = ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.size == 1)
        assertTrue(migrated.contains("loadAdditional(tag, registries);"), migrated)
        assertTrue(migrated.contains("saveAdditional(tag, registries);"), migrated)
    }

    @Test
    fun `BlockEntity update tag calls use the callers declared provider`() {
        val file = write("sample/BlockEntitySnapshot.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class BlockEntitySnapshot {
                Object capture(Level level, BlockEntity blockEntity, HolderLookup.Provider registries) {
                    return blockEntity.getUpdateTag();
                }
            }
        """.trimIndent())

        val changes = ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.size == 1)
        assertTrue(migrated.contains("blockEntity.getUpdateTag(registries);"), migrated)
    }

    @Test
    fun `BlockEntity menu writers promote their exact registry buffer provider`() {
        val file = write("sample/SyncedBlockEntity.java", """
            package sample;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class SyncedBlockEntity extends BlockEntity {
                void sendToMenu(FriendlyByteBuf buffer) {
                    buffer.writeNbt(getUpdateTag());
                }
            }
        """.trimIndent())

        ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("sendToMenu(RegistryFriendlyByteBuf buffer)"), migrated)
        assertTrue(
            migrated.contains("getUpdateTag(buffer.registryAccess())"),
            migrated
        )
        assertTrue(!migrated.contains("getLevel().registryAccess()"), migrated)
    }

    @Test
    fun `external item list contracts use exact direct and level providers`() {
        val file = write("sample/ItemListCodec.java", """
            package sample;
            import java.util.List;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            class ItemListCodec {
                List<ItemStack> read(Level level, ListTag tag) {
                    return NBTHelper.readItemList(tag);
                }
                ListTag write(HolderLookup.Provider registries, List<ItemStack> stacks) {
                    return NBTHelper.writeItemList(stacks);
                }
            }
        """.trimIndent())

        val changes = ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.size == 1)
        assertTrue(migrated.contains("NBTHelper.readItemList(tag, level.registryAccess())"), migrated)
        assertTrue(migrated.contains("NBTHelper.writeItemList(stacks, registries)"), migrated)
    }

    @Test
    fun `external provider demand migrates a closed project override family and its callers`() {
        val base = write("sample/BaseData.java", """
            package sample;
            import java.util.List;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.item.ItemStack;
            class BaseData {
                private final List<ItemStack> stacks = List.of();
                ListTag write(boolean packet) {
                    return NBTHelper.writeItemList(stacks);
                }
            }
        """.trimIndent())
        val child = write("sample/ChildData.java", """
            package sample;
            import net.minecraft.nbt.ListTag;
            class ChildData extends BaseData {
                @Override
                ListTag write(boolean packet) {
                    return super.write(packet);
                }
            }
        """.trimIndent())
        val leaf = write("sample/LeafData.java", """
            package sample;
            import net.minecraft.nbt.ListTag;
            class LeafData extends ChildData {
                @Override
                ListTag write(boolean packet) {
                    return super.write(packet);
                }
            }
        """.trimIndent())
        val caller = write("sample/DataEncoder.java", """
            package sample;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.level.Level;
            class DataEncoder {
                private final BaseData data;
                DataEncoder(BaseData data) {
                    this.data = data;
                }
                ListTag encode(Level level) {
                    return data.write(false);
                }
            }
        """.trimIndent())

        ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        val migratedBase = base.readText()
        val migratedChild = child.readText()
        val migratedLeaf = leaf.readText()
        val migratedCaller = caller.readText()
        assertTrue(migratedBase.contains("write(boolean packet, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedBase)
        assertTrue(migratedBase.contains("NBTHelper.writeItemList(stacks, modporterRegistries)"), migratedBase)
        assertTrue(migratedChild.contains("write(boolean packet, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedChild)
        assertTrue(migratedChild.contains("super.write(packet, modporterRegistries)"), migratedChild)
        assertTrue(migratedLeaf.contains("write(boolean packet, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedLeaf)
        assertTrue(migratedLeaf.contains("super.write(packet, modporterRegistries)"), migratedLeaf)
        assertTrue(migratedCaller.contains("data.write(false, level.registryAccess())"), migratedCaller)
    }

    @Test
    fun `same named project methods are not treated as external BlockEntity calls`() {
        val file = write("sample/ProjectData.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            class ProjectData {
                void load(CompoundTag tag) {}
                void saveAdditional(CompoundTag tag) {}
                void copy(CompoundTag tag) {
                    load(tag);
                    saveAdditional(tag);
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `registry buffer provider promotion is atomic across an exact override family`() {
        val base = write("sample/BufferBlockEntity.java", """
            package sample;
            import net.createmod.ponder.api.VirtualBlockEntity;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.level.block.entity.BlockEntity;
            abstract class BufferBlockEntity extends BlockEntity implements VirtualBlockEntity {
                void send(FriendlyByteBuf buffer) {
                    getUpdateTag();
                }
            }
        """.trimIndent())
        val child = write("sample/ChildBufferBlockEntity.java", """
            package sample;
            import net.minecraft.network.FriendlyByteBuf;
            abstract class ChildBufferBlockEntity extends BufferBlockEntity {
                @Override
                void send(FriendlyByteBuf buffer) {
                    super.send(buffer);
                }
            }
        """.trimIndent())

        val changes = ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        val migratedBase = base.readText()
        val migratedChild = child.readText()

        assertTrue(changes.size == 2, changes.toString())
        assertTrue(migratedBase.contains("send(RegistryFriendlyByteBuf buffer)"), migratedBase)
        assertTrue(migratedBase.contains("getUpdateTag(buffer.registryAccess())"), migratedBase)
        assertTrue(migratedChild.contains("send(RegistryFriendlyByteBuf buffer)"), migratedChild)
        assertTrue(migratedChild.contains("super.send(buffer);"), migratedChild)
    }

    @Test
    fun `external provider call from an exact target callback without a source hard fails`() {
        val file = write("sample/BrokenBlockEntity.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.protocol.Packet;
            import net.minecraft.network.protocol.game.ClientGamePacketListener;
            import net.minecraft.world.level.block.entity.BlockEntity;
            abstract class BrokenBlockEntity extends BlockEntity {
                @Override
                public Packet<ClientGamePacketListener> getUpdatePacket() {
                    getUpdateTag();
                    return null;
                }
            }
        """.trimIndent())
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("no declared provider source"), error.message)
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `external BlockEntity receiver provenance selects the producing world provider`() {
        val file = write("sample/ZapperItem.java", """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class ZapperItem {
                static void setBlockEntityData(Level world, BlockPos pos, CompoundTag data, Player player) {
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    blockEntity.load(data);
                }
            }
        """.trimIndent())

        ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("blockEntity.loadAdditional(data, world.registryAccess());"), migrated)
        assertTrue(!migrated.contains("player.registryAccess()"), migrated)
    }

    @Test
    fun `reassigned BlockEntity receiver does not claim its original world provenance`() {
        val file = write("sample/ZapperItem.java", """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class ZapperItem {
                static void setBlockEntityData(Level world, Level other, BlockPos pos, CompoundTag data, Player player) {
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    blockEntity = other.getBlockEntity(pos);
                    blockEntity.load(data);
                }
            }
        """.trimIndent())
        val original = file.readText()

        assertFailsWith<IllegalStateException> {
            ExactExternalProviderCallMigration().migrate(tempDir, dryRun = false)
        }
        assertTrue(file.readText() == original)
    }
    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
