package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactBlockEntityRegistryGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class ExactBlockEntityRegistryGraphSecondaryTypeIndexTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `registry graph preindexes secondary top level factory types before scanning fields`() {
        write("sample/ARegistry.java", """
            package sample;
            import com.tterrag.registrate.util.entry.BlockEntityEntry;
            class ARegistry {
                static Registrate REGISTRATE;
                static final BlockEntityEntry<ConcreteA> A =
                    REGISTRATE.blockEntity("a", ConcreteA::new).register();
                static final BlockEntityEntry<ConcreteB> B =
                    REGISTRATE.blockEntity("b", ConcreteB::new).register();
            }
        """.trimIndent())
        write("sample/ZTypes.java", """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            abstract class BaseBlockEntity extends BlockEntity {
                BaseBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
            }
            class ConcreteA extends BaseBlockEntity {
                ConcreteA(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
            }
            class ConcreteB extends BaseBlockEntity {
                ConcreteB(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
            }
        """.trimIndent())

        val graph = ExactBlockEntityRegistryGraph.build(tempDir.resolve("src/main/java"))

        assertEquals(
            listOf("sample.ARegistry.A.get()"),
            graph.referencesFor("sample.ConcreteA")
        )
        assertEquals(
            listOf("sample.ARegistry.B.get()"),
            graph.referencesFor("sample.ConcreteB")
        )
    }

    private fun write(relative: String, source: String) {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
    }
}