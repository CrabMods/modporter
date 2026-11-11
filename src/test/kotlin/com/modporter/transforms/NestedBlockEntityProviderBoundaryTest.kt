package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NestedBlockEntityProviderBoundaryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `nested helper caller derives a world provider only with a proven attached level`() {
        for (attached in listOf(false, true)) {
            val project = tempDir.resolve(if (attached) "attached" else "unattached")
            val file = project.resolve("src/main/java/sample/Shelf.java")
            file.parent.createDirectories()
            val guard = if (attached) {
                "if (this.level == null) throw new IllegalStateException(\"Not attached\");"
            } else ""
            file.writeText("""
                package sample;
                import net.minecraft.core.BlockPos;
                import net.minecraft.core.HolderLookup;
                import net.minecraft.nbt.CompoundTag;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.block.entity.BlockEntity;
                import net.minecraft.world.level.block.entity.BlockEntityType;
                import net.minecraft.world.level.block.state.BlockState;
                class Shelf extends BlockEntity {
                    Shelf(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                        super(type, pos, state);
                    }
                    public ItemStack emit(Payload payload) {
                        $guard
                        return payload.toItem();
                    }
                    static class Payload {
                        ItemStack toItem() {
                            ItemStack stack = ItemStack.EMPTY;
                            stack.save(new CompoundTag());
                            return stack;
                        }
                    }
                }
            """.trimIndent())
            val result = StructuralRefactorPass().apply(project)
            assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
            val migrated = file.readText()
            val parsed = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17))
                .parse(migrated)
            assertTrue(parsed.isSuccessful, parsed.problems.toString())
            val unit = parsed.result.orElseThrow()
            val method = unit.getClassByName("Shelf").orElseThrow().getMethodsByName("emit").single()
            assertEquals(if (attached) 1 else 2, method.parameters.size, migrated)
            if (attached) {
                assertTrue(migrated.contains("payload.toItem(this.level.registryAccess())"), migrated)
            } else {
                val provider = method.parameters.last()
                assertTrue(provider.typeAsString.endsWith("HolderLookup.Provider"), migrated)
                assertTrue(migrated.contains("payload.toItem(${provider.nameAsString})"), migrated)
                assertFalse(migrated.contains("this.getLevel().registryAccess()"), migrated)
                assertFalse(migrated.contains("this.level.registryAccess()"), migrated)
            }
        }
    }
}
