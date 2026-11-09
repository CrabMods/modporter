package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateBlockEntitySelfLoads
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockEntitySelfLoadsTest {
    @TempDir lateinit var directory: Path
    private fun migrate(source: String): Pair<StructuralRefactorPass, String> {
        val file = directory.resolve("src/main/java/example/Reader.java")
        file.parent.createDirectories()
        file.writeText(source)
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(file))
        return pass to pass.migrateBlockEntitySelfLoads(source)
    }

    @Test
    fun `an instance self load with an existing provider restores components and full custom data`() {
        val source = """
            package example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class Reader extends BlockEntity {
                public void readClient(CompoundTag tag, HolderLookup.Provider registries) {
                    load(tag);
                }
            }
        """.trimIndent()
        val (pass, result) = migrate(source)
        assertTrue(result.contains("loadWithComponents(tag, registries)"), result)
        assertEquals(result, pass.migrateBlockEntitySelfLoads(result))
    }

    @Test
    fun `project load overloads unrelated owners and missing providers cannot authorize a rename`() {
        val source = """
            package example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class Reader extends BlockEntity {
                public void load(String text) {}
                public void read(CompoundTag tag, HolderLookup.Provider registries) { load(tag); }
            }
            class Other {
                public void read(CompoundTag tag, HolderLookup.Provider registries) { load(tag); }
            }
            class Detached extends BlockEntity {
                public void read(CompoundTag tag) { load(tag); }
            }
        """.trimIndent()
        assertEquals(source, migrate(source).second)
    }
}
