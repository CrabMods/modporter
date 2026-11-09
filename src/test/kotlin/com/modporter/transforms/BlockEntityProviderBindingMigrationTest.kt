package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockEntityProviderBindingMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `legacy BlockEntity self serialization calls close through staged helper providers`() {
        val file = javaFile(
            "SyncedBlockEntity.java",
            """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            public abstract class SyncedBlockEntity extends BlockEntity {
                protected SyncedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }

                public void readClient(CompoundTag tag) {
                    load(tag);
                }

                public CompoundTag writeClient(CompoundTag tag) {
                    saveAdditional(tag);
                    return tag;
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val readProvider = Regex(
            """readClient\(CompoundTag tag, (?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+)\)"""
        ).find(migrated)?.groupValues?.get(1)
        val writeProvider = Regex(
            """writeClient\(CompoundTag tag, (?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+)\)"""
        ).find(migrated)?.groupValues?.get(1)
        assertTrue(readProvider != null, migrated)
        assertTrue(writeProvider != null, migrated)
        assertTrue(migrated.contains("loadWithComponents(tag, $readProvider);"), migrated)
        assertTrue(migrated.contains("saveAdditional(tag, $writeProvider);"), migrated)
    }

    @Test
    fun `block entity super serialization calls preserve the exact provider parameter name`() {
        val file = javaFile(
            "ExactProviderBlockEntity.java",
            """
            package com.example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            public class ExactProviderBlockEntity extends BlockEntity {
                public ExactProviderBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                protected void write(CompoundTag tag, HolderLookup.Provider exactLookup, boolean clientPacket) {
                    super.saveAdditional(tag);
                }
                protected void read(CompoundTag tag, HolderLookup.Provider exactLookup, boolean clientPacket) {
                    super.load(tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("super.saveAdditional(tag, exactLookup);"), migrated)
        assertTrue(migrated.contains("super.loadAdditional(tag, exactLookup);"), migrated)
        assertFalse(Regex("""\bregistries\b""").containsMatchIn(migrated), migrated)
    }

    @Test
    fun `final legacy block entity hooks retain modifiers while gaining providers`() {
        val file = javaFile(
            "FinalHookBlockEntity.java",
            """
            package com.example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            public class FinalHookBlockEntity extends BlockEntity {
                public FinalHookBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                protected void read(CompoundTag tag, boolean clientPacket) {
                }
                protected void write(CompoundTag tag, boolean clientPacket) {
                }
                @Override
                public final void load(CompoundTag tag) {
                    super.load(tag);
                    read(tag, false);
                }
                @Override
                public final void saveAdditional(CompoundTag tag) {
                    super.saveAdditional(tag);
                    write(tag, false);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("protected final void loadAdditional(CompoundTag tag, HolderLookup.Provider registries)"), migrated)
        assertTrue(migrated.contains("public final void saveAdditional(CompoundTag tag, HolderLookup.Provider registries)"), migrated)
        assertTrue(migrated.contains("super.loadAdditional(tag, registries);"), migrated)
        assertTrue(migrated.contains("super.saveAdditional(tag, registries);"), migrated)
        assertTrue(migrated.contains("read(tag, false, registries);"), migrated)
        assertTrue(migrated.contains("write(tag, false, registries);"), migrated)
    }

    @Test
    fun `block entity provider contract closes custom hooks lambda calls and override families before call migration`() {
        val file = javaFile(
            "DelegatingBlockEntity.java",
            """
            package com.example;
            import java.util.List;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            class Behaviour {
                public void read(CompoundTag tag, boolean clientPacket) {
                }
            }

            class SpecializedBehaviour extends Behaviour {
                @Override
                public void read(CompoundTag tag, boolean clientPacket) {
                    super.read(tag, clientPacket);
                }
            }

            public class DelegatingBlockEntity extends BlockEntity {
                private List<Behaviour> behaviours;

                public DelegatingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }

                protected void read(CompoundTag tag, boolean clientPacket) {
                    super.load(tag);
                    behaviours.forEach(behaviour -> behaviour.read(tag, clientPacket));
                }

                @Override
                public final void load(CompoundTag tag) {
                    read(tag, false);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("protected final void loadAdditional(CompoundTag tag, HolderLookup.Provider registries)"), migrated)
        assertTrue(
            Regex("""protected void read\(CompoundTag tag, boolean clientPacket, (?:net\.minecraft\.core\.)?HolderLookup\.Provider (\w+)\)""")
                .containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            Regex("""public void read\(CompoundTag tag, boolean clientPacket, (?:net\.minecraft\.core\.)?HolderLookup\.Provider \w+\)""")
                .findAll(migrated).count() == 2,
            migrated
        )
        assertFalse(migrated.contains("super.load(tag);"), migrated)
        assertFalse(migrated.contains("behaviour.read(tag, clientPacket)"), migrated)
        assertFalse(migrated.contains("super.read(tag, clientPacket)"), migrated)
        assertTrue(Regex("""super\.loadAdditional\(tag, \w+\);""").containsMatchIn(migrated), migrated)
        assertTrue(Regex("""behaviour\.read\(tag, clientPacket, \w+\)""").containsMatchIn(migrated), migrated)
        assertTrue(Regex("""super\.read\(tag, clientPacket, \w+\)""").containsMatchIn(migrated), migrated)
    }

    @Test
    fun `legacy block entity helper interfaces inherit exact provider demand from project and base calls`() {
        val file = javaFile(
            "SafeBlockEntity.java",
            """
            package com.example;
            import java.util.List;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            interface SafeWriter {
                void writeSafe(CompoundTag tag);
            }

            class Behaviour {
                ItemStack stack;
                void write(CompoundTag tag, boolean clientPacket) {
                    tag.put("Stack", stack.serializeNBT());
                }
                void writeSafe(CompoundTag tag) {
                    write(tag, false);
                }
            }

            public class SafeBlockEntity extends BlockEntity implements SafeWriter {
                List<Behaviour> behaviours;

                public SafeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }

                @Override
                public void writeSafe(CompoundTag tag) {
                    super.saveAdditional(tag);
                    behaviours.forEach(behaviour -> behaviour.writeSafe(tag));
                }

                public CompoundTag writeClient(CompoundTag tag) {
                    super.saveAdditional(tag);
                    return tag;
                }

                public void readClient(CompoundTag tag) {
                    super.load(tag);
                }

                static void copy(Level level, SafeWriter writer, CompoundTag tag) {
                    writer.writeSafe(tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}\n$migrated")
        assertTrue(Regex("""void writeSafe\(CompoundTag tag, (?:net\.minecraft\.core\.)?HolderLookup\.Provider \w+\)""")
            .findAll(migrated).count() == 3, migrated)
        assertTrue(Regex("""CompoundTag writeClient\(CompoundTag tag, (?:net\.minecraft\.core\.)?HolderLookup\.Provider \w+\)""")
            .containsMatchIn(migrated), migrated)
        assertTrue(Regex("""void readClient\(CompoundTag tag, (?:net\.minecraft\.core\.)?HolderLookup\.Provider \w+\)""")
            .containsMatchIn(migrated), migrated)
        assertTrue(Regex("""super\.saveAdditional\(tag, \w+\);""").findAll(migrated).count() == 2, migrated)
        assertTrue(Regex("""super\.loadAdditional\(tag, \w+\);""").containsMatchIn(migrated), migrated)
        assertTrue(Regex("""behaviour\.writeSafe\(tag, \w+\)""").containsMatchIn(migrated), migrated)
        assertTrue(migrated.contains("writer.writeSafe(tag, level.registryAccess())"), migrated)
    }

    @Test
    fun `minecraft client use in a sibling method is not registry provider evidence`() {
        val file = javaFile(
            "ClientImportIsNotEvidence.java",
            """
            package com.example;
            import net.minecraft.client.Minecraft;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            public class ClientImportIsNotEvidence {
                void render() {
                    Minecraft.getInstance().execute(() -> {});
                }
                void encode(CompoundTag tag, ItemStack stack) {
                    tag.put("Stack", stack.save(new CompoundTag()));
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertFalse(migrated.contains("Minecraft.getInstance().level.registryAccess()"), migrated)
        assertFalse(migrated.contains("Minecraft.getInstance().modporterRegistries"), migrated)
        assertTrue(migrated.contains("void encode(CompoundTag tag, ItemStack stack, HolderLookup.Provider registries)"), migrated)
        assertTrue(migrated.contains("stack.saveOptional(registries)"), migrated)
        assertFalse(migrated.contains("void render(HolderLookup.Provider"), migrated)
    }

    @Test
    fun `unbound level registry rewrite does not consume a qualified receiver suffix`() {
        val file = javaFile(
            "QualifiedClientLevel.java",
            """
            package com.example;
            import net.minecraft.client.Minecraft;
            public class QualifiedClientLevel {
                Object registries(Minecraft mc) {
                    return mc.level.registryAccess();
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("mc.level.registryAccess()"), migrated)
        assertFalse(migrated.contains("mc.registries"), migrated)
    }

    @Test
    fun `provider added to nbt family is consumed by item stack serialization in the same pass`() {
        val file = javaFile(
            "ItemStackNbtBlockEntity.java",
            """
            package com.example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.items.ItemStackHandler;
            public class ItemStackNbtBlockEntity extends BlockEntity {
                private ItemStack stored;
                private SmartInventory inventory;
                private static class SmartInventory extends ItemStackHandler {
                }
                public ItemStackNbtBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                protected void write(CompoundTag tag, boolean clientPacket) {
                    tag.put("Stored", stored.serializeNBT());
                    tag.put("Inventory", inventory.serializeNBT());
                    inventory.deserializeNBT(tag.getCompound("Inventory"));
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("write(CompoundTag tag, boolean clientPacket, HolderLookup.Provider registries)"), migrated)
        assertTrue(migrated.contains("stored.saveOptional(registries)"), migrated)
        assertTrue(migrated.contains("inventory.serializeNBT(registries)"), migrated)
        assertTrue(migrated.contains("inventory.deserializeNBT(registries, tag.getCompound(\"Inventory\"))"), migrated)
        assertFalse(migrated.contains("stored.serializeNBT()"), migrated)
    }

    @Test
    fun `method parameters shadow same named item stack fields while retaining entity provider migration`() {
        val file = javaFile(
            "LexicalReceiverShadow.java",
            """
            package com.example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.item.ItemStack;
            public class LexicalReceiverShadow {
                private ItemStack value;
                void serialize(Entity value, HolderLookup.Provider provider) {
                    value.serializeNBT();
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("value.serializeNBT(value.registryAccess());"), migrated)
        assertFalse(migrated.contains("value.saveOptional("), migrated)
    }

    @Test
    fun `later method locals do not shadow item handler fields at earlier call sites`() {
        val source = javaFile("InventoryOwner.java", """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.items.ItemStackHandler;

            class CustomInventory extends ItemStackHandler {}

            public class InventoryOwner extends BlockEntity {
                private CustomInventory inventory;
                InventoryOwner(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                void saveInventory(CompoundTag tag, HolderLookup.Provider provider) {
                    tag.put("Inventory", inventory.serializeNBT());
                }
                void unrelated(Entity inventory) {
                    inventory.serializeNBT();
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = source.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("inventory.serializeNBT(provider)"), transformed)
        assertTrue(
            transformed.contains("inventory.serializeNBT(inventory.registryAccess());"),
            transformed
        )
    }

    private fun javaFile(name: String, source: String): Path {
        val directory = tempDir.resolve("src/main/java/com/example").createDirectories()
        return directory.resolve(name).also { it.writeText(source) }
    }
}
