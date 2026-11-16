package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactExternalTypeContracts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateRecordComponentFieldAccessSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LibraryRecordComponentsTest {
    @Test
    fun `field reads on a dependency type that became a record use its accessors`() {
        val source = """
            package example;

            import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;
            import net.minecraft.core.BlockPos;

            final class Cutter {
                private BlockPos startCutPos;

                Object center(TrunkShellBlock block) {
                    TrunkShellBlock.ShellMuse muse = block.getMuse(null, startCutPos);
                    if (muse != null) {
                        startCutPos = muse.pos; // muse.pos in a comment stays
                        String text = "muse.state";
                        return muse.state;
                    }
                    return muse.getRadius();
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateRecordComponentFieldAccessSource(source, emptyMap())

        assertTrue(migrated.contains("startCutPos = muse.pos(); // muse.pos in a comment stays"), migrated)
        assertTrue(migrated.contains("return muse.state();"), migrated)
        assertTrue(migrated.contains("\"muse.state\""), migrated)
        assertTrue(migrated.contains("muse.getRadius()"), migrated)
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateRecordComponentFieldAccessSource(migrated, emptyMap()),
            "a second run must not add another call"
        )
    }

    @Test
    fun `a project type with the same simple name keeps its fields`() {
        val source = """
            package example;

            import example.other.TrunkShellBlock;

            final class Cutter {
                Object center(TrunkShellBlock block) {
                    TrunkShellBlock.ShellMuse muse = block.muse();
                    return muse.pos;
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateRecordComponentFieldAccessSource(source, emptyMap())

        assertEquals(source, migrated)
        assertFalse(migrated.contains("muse.pos()"), migrated)
    }

    @Test
    fun `every declared record surface names its owner type and components`() {
        assertTrue(ExactExternalTypeContracts.recordComponentSurfaces.isNotEmpty())
        ExactExternalTypeContracts.recordComponentSurfaces.forEach { surface ->
            assertTrue(surface.qualifiedType.startsWith(surface.importOwner + "."), surface.toString())
            assertEquals(surface.importOwner.substringAfterLast('.'), surface.sourceType.substringBefore('.'), surface.toString())
            assertTrue(surface.components.isNotEmpty(), surface.toString())
        }
    }
}
