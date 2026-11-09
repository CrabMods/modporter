package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyBlockCloneItemStackOverloadFamily
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LegacyBlockCloneOverloadFamiliesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `existing three and five argument hooks retain both bodies and arbitrary identifiers`() {
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.LevelReader;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.HitResult;
            class DualHook extends Block {
                @Override
                public ItemStack getCloneItemStack(BlockGetter sourceView, BlockPos selectedPos, BlockState selectedState) {
                    return cloneFromState(sourceView, selectedPos, selectedState);
                }
                @Override
                public ItemStack getCloneItemStack(BlockState exactState, HitResult exactHit, BlockGetter exactView,
                    BlockPos exactPos, Player exactPlayer) {
                    if (useAlternate(exactPlayer))
                        return alternate().getCloneItemStack(exactState, exactHit, exactView, exactPos, exactPlayer);
                    return super.getCloneItemStack(exactState, exactHit, exactView, exactPos, exactPlayer);
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyBlockCloneItemStackOverloadFamily(source)

        assertTrue(migrated.contains(
            "getCloneItemStack(LevelReader sourceView, BlockPos selectedPos, BlockState selectedState)"
        ), migrated)
        assertTrue(migrated.contains(
            "getCloneItemStack(BlockState exactState, HitResult exactHit, LevelReader exactView,"
        ), migrated)
        assertFalse(migrated.contains("@SuppressWarnings(\"deprecation\")"), migrated)
        assertTrue(migrated.contains(
            "return cloneFromState(sourceView, selectedPos, selectedState);"
        ), migrated)
        assertTrue(migrated.contains(
            "return super.getCloneItemStack(exactState, exactHit, exactView, exactPos, exactPlayer);"
        ), migrated)
        val declarations = JavaParser().parse(migrated).result.orElseThrow()
            .findAll(MethodDeclaration::class.java)
            .count { it.nameAsString == "getCloneItemStack" }
        assertEquals(2, declarations)
    }

    @Test
    fun `Backtank direct clone call stays on the preserved three argument hook`() {
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class BacktankBlock extends Block {
                void equip(Level world, Player player, BlockPos pos, BlockState state) {
                    player.setItemSlot(EquipmentSlot.CHEST, getCloneItemStack(world, pos, state));
                }

                @Override
                public ItemStack getCloneItemStack(BlockGetter blockGetter, BlockPos pos, BlockState state) {
                    if (special(blockGetter, pos))
                        return super.getCloneItemStack(blockGetter, pos, state);
                    return create(blockGetter, pos, state);
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyBlockCloneItemStackOverloadFamily(source)

        assertTrue(migrated.contains(
            "getCloneItemStack(LevelReader blockGetter, BlockPos pos, BlockState state)"
        ), migrated)
        assertTrue(migrated.contains(
            "return super.getCloneItemStack(blockGetter, pos, state);"
        ), migrated)
        assertTrue(migrated.contains("return create(blockGetter, pos, state);"), migrated)
        assertTrue(migrated.contains(
            "player.setItemSlot(EquipmentSlot.CHEST, getCloneItemStack(world, pos, state));"
        ), migrated)
        assertTrue(
            Regex(
                """getCloneItemStack\(\s*BlockState state_,\s*HitResult target,\s*""" +
                    """LevelReader level,\s*BlockPos pos_,\s*Player player_\s*\)"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains(
            "return getCloneItemStack(level, pos_, state_);"
        ), migrated)
        assertFalse(migrated.contains("@SuppressWarnings(\"deprecation\")"), migrated)
        val declarations = JavaParser().parse(migrated).result.orElseThrow()
            .findAll(MethodDeclaration::class.java)
            .count { it.nameAsString == "getCloneItemStack" }
        assertEquals(2, declarations)
    }

    @Test
    fun `five argument hook alone changes only the level parameter type`() {
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.HitResult;
            class SensitiveOnly extends Block {
                @Override
                public ItemStack getCloneItemStack(BlockState valueState, HitResult valueHit, BlockGetter valueReader,
                    BlockPos valuePos, Player valuePlayer) {
                    return choose(valueState, valueHit, valueReader, valuePos, valuePlayer);
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyBlockCloneItemStackOverloadFamily(source)

        assertTrue(migrated.contains(
            "BlockState valueState, HitResult valueHit, LevelReader valueReader,"
        ), migrated)
        assertTrue(migrated.contains(
            "return choose(valueState, valueHit, valueReader, valuePos, valuePlayer);"
        ), migrated)
    }

    @Test
    fun `same signatures on a project interface implementation stay byte identical`() {
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.HitResult;
            interface CloneContract {
                ItemStack getCloneItemStack(BlockGetter reader, BlockPos pos, BlockState state);
                ItemStack getCloneItemStack(BlockState state, HitResult hit, BlockGetter reader, BlockPos pos, Player player);
            }
            class ContractOnly implements CloneContract {
                @Override
                public ItemStack getCloneItemStack(BlockGetter reader, BlockPos pos, BlockState state) {
                    return ItemStack.EMPTY;
                }
                @Override
                public ItemStack getCloneItemStack(BlockState state, HitResult hit, BlockGetter reader,
                    BlockPos pos, Player player) {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent()

        assertEquals(source, passFor(source).migrateLegacyBlockCloneItemStackOverloadFamily(source))
    }

    @Test
    fun `project superclass three argument super dispatch stays and gains a delegate`() {
        val parent = """
            package sample;
            import net.minecraft.world.level.block.Block;
            class ProjectBase extends Block {}
        """.trimIndent()
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.state.BlockState;
            class ProjectChild extends ProjectBase {
                @Override
                public ItemStack getCloneItemStack(BlockGetter reader, BlockPos pos, BlockState state) {
                    return super.getCloneItemStack(reader, pos, state);
                }
            }
        """.trimIndent()
        val parentFile = directory.resolve("ProjectBase.java")
        val sourceFile = directory.resolve("ProjectChild.java")
        parentFile.writeText(parent)
        sourceFile.writeText(source)
        val pass = StructuralRefactorPass().also {
            it.projectValueFacts = ProjectValueFacts.collect(it, listOf(parentFile, sourceFile))
        }

        val migrated = pass.migrateLegacyBlockCloneItemStackOverloadFamily(source)

        assertTrue(
            Regex(
                """getCloneItemStack\(\s*LevelReader reader,\s*BlockPos pos,\s*BlockState state\s*\)"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains(
            "return super.getCloneItemStack(reader, pos, state);"
        ), migrated)
        assertTrue(migrated.contains(
            "return getCloneItemStack(level, pos_, state_);"
        ), migrated)
    }

    @Test
    fun `same-package project type shadowing target api names stays byte identical`() {
        val shadow = """
            package sample;
            class ItemStack {}
        """.trimIndent()
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class Shadowed extends Block {
                @Override
                public ItemStack getCloneItemStack(BlockGetter reader, BlockPos pos, BlockState state) {
                    return null;
                }
            }
        """.trimIndent()
        val shadowFile = directory.resolve("ItemStack.java")
        val sourceFile = directory.resolve("Shadowed.java")
        shadowFile.writeText(shadow)
        sourceFile.writeText(source)
        val pass = StructuralRefactorPass().also {
            it.projectValueFacts = ProjectValueFacts.collect(it, listOf(shadowFile, sourceFile))
        }

        assertEquals(source, pass.migrateLegacyBlockCloneItemStackOverloadFamily(source))
    }

    @Test
    fun `already migrated five argument hook stays byte identical`() {
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.LevelReader;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.HitResult;
            class AlreadyMigrated extends Block {
                @Override
                public ItemStack getCloneItemStack(BlockState state, HitResult hit, LevelReader reader,
                    BlockPos pos, Player player) {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent()

        assertEquals(source, passFor(source).migrateLegacyBlockCloneItemStackOverloadFamily(source))
    }

    @Test
    fun `owner matching is exact when one file contains block and non-block classes`() {
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class ActualBlock extends Block {
                @Override
                public ItemStack getCloneItemStack(BlockGetter blockReader, BlockPos blockPos, BlockState blockState) {
                    return ItemStack.EMPTY;
                }
            }
            class Unrelated {
                @Override
                public ItemStack getCloneItemStack(BlockGetter otherReader, BlockPos otherPos, BlockState otherState) {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyBlockCloneItemStackOverloadFamily(source)

        assertTrue(migrated.contains(
            "getCloneItemStack(LevelReader blockReader, BlockPos blockPos, BlockState blockState)"
        ), migrated)
        assertTrue(
            Regex(
                """getCloneItemStack\(\s*BlockState state,\s*HitResult target,\s*LevelReader level"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains(
            "getCloneItemStack(BlockGetter otherReader, BlockPos otherPos, BlockState otherState)"
        ), migrated)
    }

    @Test
    fun `canonically named nested block owner gains both target hooks`() {
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class Outer {
                static class NestedBlock extends Block {
                    @Override
                    public ItemStack getCloneItemStack(
                        BlockGetter reader,
                        BlockPos pos,
                        BlockState state
                    ) {
                        return make(reader, pos, state);
                    }
                }
            }
        """.trimIndent()

        val migrated = passFor(source).migrateLegacyBlockCloneItemStackOverloadFamily(source)

        assertTrue(
            Regex(
                """getCloneItemStack\(\s*LevelReader reader,\s*BlockPos pos,\s*BlockState state\s*\)"""
            ).containsMatchIn(migrated),
            migrated
        )
        val declarations = JavaParser().parse(migrated).result.orElseThrow()
            .findAll(MethodDeclaration::class.java)
            .count { it.nameAsString == "getCloneItemStack" }
        assertEquals(2, declarations)
    }

    @Test
    fun `same package LevelReader shadow aborts before introducing a target spelling`() {
        val shadow = """
            package sample;
            class LevelReader {}
        """.trimIndent()
        val source = """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class ShadowedTarget extends Block {
                @Override
                public ItemStack getCloneItemStack(
                    BlockGetter reader,
                    BlockPos pos,
                    BlockState state
                ) {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent()
        val shadowFile = directory.resolve("LevelReader.java")
        val sourceFile = directory.resolve("ShadowedTarget.java")
        shadowFile.writeText(shadow)
        sourceFile.writeText(source)
        val pass = StructuralRefactorPass().also {
            it.projectValueFacts = ProjectValueFacts.collect(it, listOf(shadowFile, sourceFile))
        }

        assertEquals(source, pass.migrateLegacyBlockCloneItemStackOverloadFamily(source))
    }

    private fun passFor(source: String): StructuralRefactorPass {
        val file = directory.resolve("Fixture-${source.hashCode()}.java")
        file.writeText(source)
        return StructuralRefactorPass().also { pass ->
            pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(file))
        }
    }
}
