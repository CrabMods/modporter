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

class StructuralLifecycleGateIsolationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `unsafe capability lifecycle retains the query while later independent families migrate`() {
        write(
            "example/ExampleMod.java",
            """
            package example;

            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
            }
            """
        )
        val unsafeQuery = write(
            "example/UnsafeQuery.java",
            """
            package example;

            import com.modporter.generated.example.compat.LazyOptional;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.items.IItemHandler;

            class UnsafeQuery {
                LazyOptional<IItemHandler> returnRaw(BlockEntity blockEntity) {
                    return blockEntity.getCapability(Capabilities.ItemHandler.BLOCK);
                }
            }
            """
        )
        val laterFamilies = write(
            "example/LaterFamilies.java",
            """
            package example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.alchemy.PotionUtils;
            import net.minecraft.world.item.alchemy.Potions;

            class LaterFamilies {
                void clear(ItemStack stack) {
                    stack.setTag(null);
                }

                ItemStack water(ItemStack stack) {
                    return PotionUtils.setPotion(stack, Potions.WATER);
                }
            }
            """
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val retained = unsafeQuery.readText()
        val migrated = laterFamilies.readText()

        assertTrue(
            result.errors.any { it.contains("cannot prove LazyOptional lifecycle use") },
            result.errors.joinToString("\n")
        )
        assertTrue(
            result.skipped.any { it.contains("Exact BlockEntity capability query fallback retained") },
            result.skipped.joinToString("\n")
        )
        assertTrue(
            retained.contains("blockEntity.getCapability(Capabilities.ItemHandler.BLOCK)"),
            retained
        )
        assertFalse(retained.contains("LazyOptional.ofNullable"), retained)
        assertFalse(retained.contains("LegacyBlockEntityCapabilityQueries.get"), retained)
        assertFalse(migrated.contains("stack.setTag("), migrated)
        assertFalse(migrated.contains("PotionUtils.setPotion("), migrated)
        assertTrue(migrated.contains("DataComponents.CUSTOM_DATA"), migrated)
        assertTrue(migrated.contains("DataComponents.POTION_CONTENTS"), migrated)
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
        return file
    }
}
