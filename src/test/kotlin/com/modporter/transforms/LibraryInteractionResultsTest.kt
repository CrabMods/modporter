package com.modporter.transforms

import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateBlockUseMethod
import com.modporter.core.transforms.structural.migrateLibraryItemInteractionResultBridges
import com.modporter.core.transforms.structural.migrateProjectLibraryItemInteractionResultBridges
import com.modporter.core.transforms.structural.migrateUseItemOnInteractionResultReturns
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LibraryInteractionResultsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ponder placement offset result bridges into legacy InteractionResult return`() {
        val source = """
            package example;
            import net.createmod.catnip.placement.IPlacementHelper;
            import net.createmod.catnip.placement.PlacementOffset;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.ItemInteractionResult;
            class CogwheelItem {
                InteractionResult place(IPlacementHelper helper) {
                    return helper.getOffset(player, level, state, pos, hit)
                        .placeInWorld(level, item, player, hand, hit);
                }
                InteractionResult placeKnown(PlacementOffset offset) {
                    return offset.placeInWorld(level, item, player, hand, hit);
                }
                ItemInteractionResult alreadyTarget(IPlacementHelper helper) {
                    return helper.getOffset(player, level, state, pos, hit)
                        .placeInWorld(level, item, player, hand, hit);
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateLibraryItemInteractionResultBridges(source)

        assertEquals(2, Regex("""\.placeInWorld\([^;]+?\)\.result\(\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(migrated).count(), migrated)
        val targetMethod = migrated.substring(migrated.indexOf("ItemInteractionResult alreadyTarget"))
        assertFalse(targetMethod.contains(".result()"), migrated)
    }

    @Test
    fun `same method name on an unproven receiver is unchanged`() {
        val source = """
            package example;
            import net.minecraft.world.InteractionResult;
            class OtherOffset {
                ItemInteractionResult placeInWorld(Object a, Object b, Object c, Object d, Object e) {
                    return ItemInteractionResult.SUCCESS;
                }
            }
            class Caller {
                InteractionResult place(OtherOffset offset) {
                    return offset.placeInWorld(a, b, c, d, e);
                }
            }
        """.trimIndent()

        assertEquals(source, StructuralRefactorPass().migrateLibraryItemInteractionResultBridges(source))
    }

    @Test
    fun `late project scan bridges an Item result but keeps a normalized Block result native`() {
        val sourceDir = tempDir.resolve("src/main/java/example").createDirectories()
        val blockFile = sourceDir.resolve("CogwheelBlock.java")
        blockFile.writeText("""
            package example;

            import net.createmod.catnip.placement.IPlacementHelper;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            class CogwheelBlock extends Block {
                private final IPlacementHelper helper;

                CogwheelBlock(Properties properties, IPlacementHelper helper) {
                    super(properties);
                    this.helper = helper;
                }

                @Override
                public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                             InteractionHand hand, BlockHitResult hit) {
                    ItemStack stack = player.getItemInHand(hand);
                    return helper.getOffset(player, level, state, pos, hit)
                        .placeInWorld(level, stack, player, hand, hit);
                }
            }
        """.trimIndent() + "\n")
        val itemFile = sourceDir.resolve("CogwheelItem.java")
        itemFile.writeText("""
            package example;

            import net.createmod.catnip.placement.IPlacementHelper;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            class CogwheelItem extends Item {
                private final IPlacementHelper helper;

                CogwheelItem(Properties properties, IPlacementHelper helper) {
                    super(properties);
                    this.helper = helper;
                }

                InteractionResult place(Level level, ItemStack stack, Player player, InteractionHand hand,
                                        BlockHitResult hit, BlockState state, BlockPos pos) {
                    return helper.getOffset(player, level, state, pos, hit)
                        .placeInWorld(level, stack, player, hand, hit);
                }
            }
        """.trimIndent() + "\n")

        val pass = StructuralRefactorPass()
        assertEquals(1, pass.migrateBlockUseMethod(tempDir, dryRun = false).size)
        val normalizedBlock = blockFile.readText()
        assertTrue(
            normalizedBlock.contains(
                "protected net.minecraft.world.ItemInteractionResult useItemOn(" +
                    "net.minecraft.world.item.ItemStack itemstack,"
            ),
            normalizedBlock
        )
        assertFalse(normalizedBlock.contains(".result()"), normalizedBlock)
        assertTrue(pass.migrateUseItemOnInteractionResultReturns(tempDir, dryRun = false).isEmpty())

        val itemBeforeDryRun = itemFile.readText()
        val dryRunChanges = pass.migrateProjectLibraryItemInteractionResultBridges(tempDir, dryRun = true)
        assertEquals(1, dryRunChanges.size)
        assertEquals(itemFile, dryRunChanges.single().file)
        assertEquals(Confidence.HIGH, dryRunChanges.single().confidence)
        assertEquals("struct-library-item-interaction-result-bridge", dryRunChanges.single().ruleId)
        assertEquals(itemBeforeDryRun, itemFile.readText())
        assertEquals(normalizedBlock, blockFile.readText())

        val changes = pass.migrateProjectLibraryItemInteractionResultBridges(tempDir, dryRun = false)
        assertEquals(1, changes.size)
        assertEquals(itemFile, changes.single().file)
        val migratedItem = itemFile.readText()
        assertTrue(
            Regex("""\.placeInWorld\([^;]+?\)\.result\(\)""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(migratedItem),
            migratedItem
        )
        val migratedBlock = blockFile.readText()
        assertFalse(migratedBlock.contains(".result()"), migratedBlock)
        assertTrue(
            migratedBlock.contains("net.minecraft.world.ItemInteractionResult useItemOn("),
            migratedBlock
        )
        assertTrue(pass.migrateProjectLibraryItemInteractionResultBridges(tempDir, dryRun = false).isEmpty())
    }
}
