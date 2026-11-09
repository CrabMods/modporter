package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyPortalInfoValues
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyPortalInfoValuesTest {
    @TempDir lateinit var directory: Path

    private fun java(relative: String, source: String): Path {
        val file = directory.resolve("src/main/java/$relative")
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }

    private fun modEntry() {
        java("example/ExampleMod.java", """
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("portal_values") public class ExampleMod {}
        """)
    }

    @Test
    fun `platform value import moves to an exact generated shape without changing executable source`() {
        modEntry()
        val original = """
            package example;

            import java.util.function.UnaryOperator;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraft.world.phys.Vec3;

            class PortalHandler {
                // net.minecraft.world.level.portal.PortalInfo remains source evidence.
                static final String LEGACY_SHAPE = "net.minecraft.world.level.portal.PortalInfo";

                PortalInfo dispatch(boolean custom, UnaryOperator<PortalInfo> handler,
                                    Vec3 pos, Vec3 speed, float yaw, float pitch) {
                    PortalInfo original = new PortalInfo(pos, speed, yaw, pitch);
                    if (custom) {
                        return handler.apply(original);
                    }
                    return new PortalInfo(original.pos, original.speed, original.yRot, original.xRot);
                }
            }
        """.trimIndent()
        val file = java("example/PortalHandler.java", original)
        val helper = directory.resolve(
            "src/main/java/com/modporter/generated/portal_values/compat/PortalInfo.java"
        )
        val pass = StructuralRefactorPass()

        val dryRun = pass.migrateLegacyPortalInfoValues(directory, dryRun = true)
        assertEquals(2, dryRun.size, dryRun.toString())
        assertEquals(original, file.readText())
        assertFalse(helper.exists())

        val changes = pass.migrateLegacyPortalInfoValues(directory, dryRun = false)
        assertEquals(2, changes.size, changes.toString())
        assertTrue(changes.all { it.ruleId == "struct-legacy-portal-info-value" }, changes.toString())
        assertEquals(
            original.replace(
                "import net.minecraft.world.level.portal.PortalInfo;",
                "import com.modporter.generated.portal_values.compat.PortalInfo;"
            ),
            file.readText()
        )
        val generated = helper.readText()
        assertTrue(generated.contains("public class PortalInfo"), generated)
        assertTrue(generated.contains("public final Vec3 pos;"), generated)
        assertTrue(generated.contains("public final Vec3 speed;"), generated)
        assertTrue(generated.contains("public final float yRot;"), generated)
        assertTrue(generated.contains("public final float xRot;"), generated)
        assertTrue(generated.contains("public PortalInfo(Vec3 pos, Vec3 speed, float yRot, float xRot)"), generated)
        assertFalse(generated.contains("DimensionTransition"), generated)
        assertFalse(generated.contains("null"), generated)
        assertTrue(pass.migrateLegacyPortalInfoValues(directory, dryRun = false).isEmpty())

        Files.delete(helper)
        val repaired = pass.migrateLegacyPortalInfoValues(directory, dryRun = false)
        assertEquals(1, repaired.size, repaired.toString())
        assertEquals(helper, repaired.single().file)
        assertTrue(helper.exists())
    }

    @Test
    fun `project shadows type parameters wildcard imports and reflected return shapes remain untouched`() {
        modEntry()
        val own = java("shadow/PortalInfo.java", """
            package shadow;
            public class PortalInfo {}
        """)
        val samePackage = java("shadow/SamePackageUse.java", """
            package shadow;
            import net.minecraft.world.level.portal.PortalInfo;
            class SamePackageUse { PortalInfo value; }
        """)
        val nested = java("nested/NestedShadow.java", """
            package nested;
            import net.minecraft.world.level.portal.PortalInfo;
            class NestedShadow {
                static class PortalInfo {}
                PortalInfo value;
            }
        """)
        val generic = java("generic/GenericShadow.java", """
            package generic;
            import net.minecraft.world.level.portal.PortalInfo;
            class GenericShadow<PortalInfo> { PortalInfo value; }
        """)
        val projectImport = java("consumer/ProjectImport.java", """
            package consumer;
            import shadow.PortalInfo;
            class ProjectImport { PortalInfo value; }
        """)
        val conflictingImport = java("consumer/ConflictingImport.java", """
            package consumer;
            import net.minecraft.world.level.portal.PortalInfo;
            import shadow.PortalInfo;
            class ConflictingImport { PortalInfo value; }
        """)
        val wildcard = java("wildcard/WildcardImport.java", """
            package wildcard;
            import net.minecraft.world.level.portal.*;
            class WildcardImport { PortalInfo value; }
        """)
        val reflected = java("reflection/ReflectedShape.java", """
            package reflection;
            import java.lang.invoke.MethodType;
            import net.minecraft.world.level.portal.PortalInfo;
            class ReflectedShape {
                MethodType shape() { return MethodType.methodType(PortalInfo.class); }
            }
        """)
        val originals = listOf(
            own,
            samePackage,
            nested,
            generic,
            projectImport,
            conflictingImport,
            wildcard,
            reflected
        )
            .associateWith { it.readText() }

        val changes = StructuralRefactorPass().migrateLegacyPortalInfoValues(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        originals.forEach { (file, source) -> assertEquals(source, file.readText(), file.toString()) }
        assertFalse(
            directory.resolve("src/main/java/com/modporter/generated/portal_values/compat/PortalInfo.java").exists()
        )
    }
}
