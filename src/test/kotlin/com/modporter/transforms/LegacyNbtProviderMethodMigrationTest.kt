package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyNbtProviderMethodMigration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LegacyNbtProviderMethodMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `propagates provider aware compound tag contracts through an override family`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("BaseData.java").writeText(
            """
            package com.example;
            import java.util.function.Consumer;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            public class BaseData {
                public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
                    forEach(value -> value.write(tag, registries, clientPacket));
                }
                public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
                }
                public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
                    write(tag, registries, false);
                }
                public void writeClient(CompoundTag tag, HolderLookup.Provider registries) {
                    saveAdditional(tag);
                }
                public void readClient(CompoundTag tag, HolderLookup.Provider registries) {
                    loadAdditional(tag);
                }
                void saveAdditional(CompoundTag tag) {
                    write(tag, false);
                }
                void loadAdditional(CompoundTag tag) {
                    read(tag, false);
                }
                void forEach(Consumer<BaseData> action) {
                    action.accept(this);
                }
            }
            """.trimIndent()
        )
        source.resolve("ChildData.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class ChildData extends BaseData {
                @Override
                public void write(CompoundTag data, boolean clientPacket) {
                    super.write(data, clientPacket);
                }
                @Override
                public void read(CompoundTag data, boolean clientPacket) {
                    super.read(data, clientPacket);
                }
                @Override
                public void readClient(CompoundTag data) {
                    super.readClient(data);
                }
                @Override
                public void writeClient(CompoundTag data) {
                    super.writeClient(data);
                }
                private static final String DOC = "write(CompoundTag, boolean)";
            }
            """.trimIndent()
        )

        val changes = LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val base = source.resolve("BaseData.java").readText()
        val child = source.resolve("ChildData.java").readText()

        assertTrue(changes.all { it.ruleId == "struct-nbt-provider-method-family" })
        assertTrue(base.contains("write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket)"), base)
        assertTrue(base.contains("writeSafe(CompoundTag tag, HolderLookup.Provider registries)"), base)
        assertTrue(base.contains("write(tag, registries, false);"), base)
        assertTrue(base.contains("value.write(tag, registries, clientPacket)"), base)
        assertTrue(base.contains("writeClient(CompoundTag tag, HolderLookup.Provider registries)"), base)
        assertTrue(child.contains("super.write(data, registries, clientPacket);"), child)
        assertTrue(child.contains("super.read(data, registries, clientPacket);"), child)
        assertTrue(child.contains("readClient(CompoundTag data, net.minecraft.core.HolderLookup.Provider registries)"), child)
        assertTrue(child.contains("super.readClient(data, registries);"), child)
        assertTrue(child.contains("writeClient(CompoundTag data, net.minecraft.core.HolderLookup.Provider registries)"), child)
        assertTrue(child.contains("super.writeClient(data, registries);"), child)
        assertTrue(child.contains("\"write(CompoundTag, boolean)\""), child)
    }

    @Test
    fun `propagates an arbitrary provider aware parent signature without method name rules`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("BaseData.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.core.HolderLookup;
            public class BaseData {
                public void hydrateSnapshot(CompoundTag tag, HolderLookup.Provider exactProvider, int revision) {
                }
            }
            """.trimIndent()
        )
        source.resolve("ChildData.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class ChildData extends BaseData {
                @Override
                public void hydrateSnapshot(CompoundTag tag, int revision) {
                    super.hydrateSnapshot(tag, revision);
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val child = source.resolve("ChildData.java").readText()

        assertTrue(child.contains("hydrateSnapshot(CompoundTag tag, net.minecraft.core.HolderLookup.Provider exactProvider, int revision)"), child)
        assertTrue(child.contains("super.hydrateSnapshot(tag, exactProvider, revision);"), child)
    }

    @Test
    fun `does not migrate unrelated write overloads`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("Unrelated.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class Unrelated {
                void write(String text, boolean flush) {
                }
                void invoke(CompoundTag tag) {
                    write("doc", false);
                }
            }
            """.trimIndent()
        )

        val changes = LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        assertTrue(changes.isEmpty())
        assertTrue(source.resolve("Unrelated.java").readText().contains("write(String text, boolean flush)"))
    }

    @Test
    fun `does not add providers to same shaped static calls on an unrelated declared owner`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("DataFamily.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class DataFamily {
                void write(CompoundTag tag, boolean clientPacket) {
                    Unrelated.write(tag, new Unrelated());
                }
            }
            """.trimIndent()
        )
        source.resolve("Unrelated.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class Unrelated {
                static void write(CompoundTag tag, Unrelated value) {
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val migrated = source.resolve("DataFamily.java").readText()

        assertTrue(migrated.contains("Unrelated.write(tag, new Unrelated());"), migrated)
        assertTrue(!migrated.contains("HolderLookup.Provider"), migrated)
    }

    @Test
    fun `uses exact typed contexts for provider aware nbt family calls`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("SyncedData.java").writeText(
            """
            package com.example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public abstract class SyncedData extends BlockEntity {
                public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
                    return new CompoundTag();
                }
                public void readClient(CompoundTag tag, HolderLookup.Provider provider) {
                }
                public void writeSafe(CompoundTag tag, HolderLookup.Provider provider) {
                }
                void send() {
                    if (this.getLevel() == null) return;
                    getUpdateTag();
                }
            }
            """.trimIndent()
        )
        source.resolve("TypedCallers.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.entity.player.Inventory;
            import net.minecraft.world.level.Level;
            public class TypedCallers {
                TypedCallers(Inventory inventory, FriendlyByteBuf buffer, SyncedData data) {
                    data.readClient(buffer.readNbt());
                }
                void copy(Level level, SyncedData data, CompoundTag tag) {
                    data.writeSafe(tag);
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val synced = source.resolve("SyncedData.java").readText()
        val callers = source.resolve("TypedCallers.java").readText()

        assertTrue(synced.contains("getUpdateTag(this.getLevel().registryAccess());"), synced)
        assertTrue(callers.contains("data.readClient(buffer.readNbt(), inventory.player.registryAccess());"), callers)
        assertTrue(callers.contains("data.writeSafe(tag, level.registryAccess());"), callers)
    }

    @Test
    fun `nullable typed roots are excluded unless dominating control flow proves them non null`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("RequestData.java").writeText(
            """
            package com.example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            public class RequestData {
                static RequestData read(CompoundTag tag, HolderLookup.Provider registries) {
                    return new RequestData();
                }
                static RequestData readFromItem(Level level, Player player, CompoundTag tag) {
                    RequestData data = read(tag);
                    if (player != null) player.toString();
                    return data;
                }
                static RequestData readForPlayer(Player player, CompoundTag tag) {
                    if (player != null) {
                        return read(tag);
                    }
                    return null;
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val migrated = source.resolve("RequestData.java").readText()

        assertTrue(migrated.contains("RequestData data = read(tag, level.registryAccess());"), migrated)
        assertTrue(migrated.contains("return read(tag, player.registryAccess());"), migrated)
    }

    @Test
    fun `resolves inherited nbt targets and typed pattern variables`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("SyncedData.java").writeText(
            """
            package com.example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public abstract class SyncedData extends BlockEntity {
                public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
                    return new CompoundTag();
                }
                public void readClient(CompoundTag tag, HolderLookup.Provider provider) {
                }
                public void writeSafe(CompoundTag tag, HolderLookup.Provider provider) {
                }
            }
            """.trimIndent()
        )
        source.resolve("ChildData.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class ChildData extends CachedData {
                void send() {
                    if (this.getLevel() == null) return;
                    getUpdateTag();
                }
            }
            """.trimIndent()
        )
        source.resolve("CachedData.java").writeText(
            """
            package com.example;
            public abstract class CachedData extends SyncedData {
            }
            """.trimIndent()
        )
        source.resolve("PatternCaller.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public class PatternCaller {
                void read(Level level, Object value, CompoundTag tag) {
                    if (value instanceof ChildData child) {
                        child.readClient(tag);
                    }
                }
                void write(BlockEntity blockEntity, CompoundTag tag) {
                    if (blockEntity instanceof ChildData child) {
                        if (blockEntity.getLevel() == null) return;
                        child.writeSafe(tag);
                    }
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val child = source.resolve("ChildData.java").readText()
        val caller = source.resolve("PatternCaller.java").readText()

        assertTrue(child.contains("getUpdateTag(this.getLevel().registryAccess());"), child)
        assertTrue(caller.contains("child.readClient(tag, level.registryAccess());"), caller)
        assertTrue(caller.contains("child.writeSafe(tag, blockEntity.getLevel().registryAccess());"), caller)
    }
}
