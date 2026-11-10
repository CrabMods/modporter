package com.modporter.transforms

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCommonNeoForge121Apis
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaProjectTypeIndexExternalMemberTypeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Create instruction subclasses inherit exact public Ponder member types`() {
        val source = """
            package sample;

            import net.createmod.ponder.api.scene.Selection;
            import net.createmod.ponder.foundation.PonderScene;
            import net.createmod.ponder.foundation.PonderSceneBuilder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public class CreateSceneBuilder extends PonderSceneBuilder {
                public CreateSceneBuilder(PonderScene scene) {
                    super(scene);
                }

                public class OverlayInstructions extends PonderOverlayInstructions {
                }

                public class EffectInstructions extends PonderEffectInstructions {
                    Selection effectSelection(Selection selection) {
                        return selection;
                    }
                }

                public class WorldInstructions extends PonderWorldInstructions {
                    public void setFilterData(
                        Selection selection,
                        Class<? extends BlockEntity> blockEntityType,
                        ItemStack filter
                    ) {
                    }
                }

                public class SpecialInstructions extends PonderSpecialInstructions {
                    ItemStack specialFilter(ItemStack filter) {
                        return filter;
                    }
                }

                public class DebugInstructions extends PonderDebugInstructions {
                }
            }
        """.trimIndent()
        val file = write("sample/CreateSceneBuilder.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val effectSelection = method(unit, "effectSelection")
        val setFilterData = method(unit, "setFilterData")
        val specialFilter = method(unit, "specialFilter")

        assertTrue(
            index.isTypeAssignableTo(
                "sample.CreateSceneBuilder.OverlayInstructions",
                "$PONDER_SCENE_BUILDER.PonderOverlayInstructions"
            )
        )
        assertTrue(
            index.isTypeAssignableTo(
                "sample.CreateSceneBuilder.EffectInstructions",
                "$PONDER_SCENE_BUILDER.PonderEffectInstructions"
            )
        )
        assertTrue(
            index.isTypeAssignableTo(
                "sample.CreateSceneBuilder.WorldInstructions",
                "$PONDER_SCENE_BUILDER.PonderWorldInstructions"
            )
        )
        assertTrue(
            index.isTypeAssignableTo(
                "sample.CreateSceneBuilder.SpecialInstructions",
                "$PONDER_SCENE_BUILDER.PonderSpecialInstructions"
            )
        )
        assertTrue(
            index.isTypeAssignableTo(
                "sample.CreateSceneBuilder.DebugInstructions",
                "$PONDER_SCENE_BUILDER.PonderDebugInstructions"
            )
        )
        assertFalse(index.isProvenNonOverride(setFilterData))
        assertNull(index.exactProjectOverrideFamily(setFilterData))
        assertEquals(
            "net.createmod.ponder.api.scene.Selection",
            index.declaredType(effectSelection.parameters.single().type, effectSelection)
        )
        assertEquals(
            "net.createmod.ponder.api.scene.Selection",
            index.declaredType(setFilterData.parameters[0].type, setFilterData)
        )
        assertEquals(
            "java.lang.Class" to listOf("net.minecraft.world.level.block.entity.BlockEntity"),
            index.declaredTypeWithArguments(setFilterData.parameters[1].type, setFilterData)
        )
        assertEquals(
            "net.minecraft.world.item.ItemStack",
            index.declaredType(setFilterData.parameters[2].type, setFilterData)
        )
        assertEquals(
            "net.minecraft.world.item.ItemStack",
            index.declaredType(specialFilter.parameters.single().type, specialFilter)
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `literal nested Ponder setFilterData uses existing carrier without widening its method surface`() {
        val source = """
            package sample;

            import net.createmod.ponder.api.scene.Selection;
            import net.createmod.ponder.foundation.PonderScene;
            import net.createmod.ponder.foundation.PonderSceneBuilder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public class CreateSceneBuilder extends PonderSceneBuilder {
                public CreateSceneBuilder(PonderScene scene) {
                    super(scene);
                }

                public class WorldInstructions extends PonderWorldInstructions {
                    public void setFilterData(
                        Selection selection,
                        Class<? extends BlockEntity> blockEntityType,
                        ItemStack filter
                    ) {
                        modifyBlockEntityNBT(selection, blockEntityType, nbt -> {
                            nbt.put("Filter", filter.serializeNBT());
                        });
                    }
                }
            }
        """.trimIndent()
        val file = write("sample/CreateSceneBuilder.java", source)
        val errors = mutableListOf<String>()

        val changes = StructuralRefactorPass().migrateCommonNeoForge121Apis(
            projectDir = tempDir,
            dryRun = false,
            errorSink = errors
        )
        val migrated = file.readText()
        val migratedIndex = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val setFilterData = method(migratedIndex.unit(file), "setFilterData")

        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        assertTrue(changes.any { it.file == file }, changes.joinToString("\n"))
        assertFalse(migrated.contains("filter.serializeNBT()"), migrated)
        assertTrue(
            migrated.contains(
                "nbt.put(\"Filter\", filter.saveOptional(world().getHolderLookupProvider()))"
            ),
            migrated
        )
        assertEquals(3, setFilterData.parameters.size)
        assertEquals(
            "net.createmod.ponder.api.scene.Selection",
            migratedIndex.declaredType(setFilterData.parameters[0].type, setFilterData)
        )
        assertEquals(
            "java.lang.Class" to listOf("net.minecraft.world.level.block.entity.BlockEntity"),
            migratedIndex.declaredTypeWithArguments(setFilterData.parameters[1].type, setFilterData)
        )
        assertEquals(
            "net.minecraft.world.item.ItemStack",
            migratedIndex.declaredType(setFilterData.parameters[2].type, setFilterData)
        )
        assertFalse(migratedIndex.isProvenNonOverride(setFilterData))
        assertNull(migratedIndex.exactProjectOverrideFamily(setFilterData))
    }

    @Test
    fun `nearer enclosing inherited Ponder member beats farther direct declaration`() {
        val source = """
            package sample;

            import net.createmod.ponder.foundation.PonderScene;
            import net.createmod.ponder.foundation.PonderSceneBuilder;

            class Outer {
                class PonderWorldInstructions {
                }

                class Near extends PonderSceneBuilder {
                    Near(PonderScene scene) {
                        super(scene);
                    }

                    class Child extends PonderWorldInstructions {
                        class PonderWorldInstructions {
                        }

                        PonderWorldInstructions bodyType(PonderWorldInstructions value) {
                            return value;
                        }
                    }
                }
            }
        """.trimIndent()
        val file = write("sample/Outer.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val bodyType = method(index.unit(file), "bodyType")

        assertTrue(
            index.isTypeAssignableTo(
                "sample.Outer.Near.Child",
                "$PONDER_SCENE_BUILDER.PonderWorldInstructions"
            )
        )
        assertFalse(
            index.isTypeAssignableTo(
                "sample.Outer.Near.Child",
                "sample.Outer.PonderWorldInstructions"
            )
        )
        assertFalse(
            index.isTypeAssignableTo(
                "sample.Outer.Near.Child",
                "sample.Outer.Near.Child.PonderWorldInstructions"
            )
        )
        assertFalse(index.hasClosedProjectMethodHierarchy("sample.Outer.Near.Child"))
        assertFalse(index.isProvenNonOverride(bodyType))
        assertEquals(
            "sample.Outer.Near.Child.PonderWorldInstructions",
            index.declaredType(bodyType.parameters.single().type, bodyType)
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `direct super generic arguments use header scope before current body members`() {
        val source = """
            package sample;

            class Base<T> {
                T inheritedValue() {
                    return null;
                }
            }

            class Outer {
                class HeaderArg {
                    class Nested {
                    }
                }

                class UnqualifiedChild extends Base<HeaderArg> {
                    class HeaderArg {
                    }

                    HeaderArg bodyType(HeaderArg value) {
                        return value;
                    }

                    Object inheritedResult() {
                        return inheritedValue();
                    }
                }

                class QualifiedChild extends Base<HeaderArg.Nested> {
                    class HeaderArg {
                        class Nested {
                        }
                    }

                    Object qualifiedResult() {
                        return inheritedValue();
                    }
                }

                class Child extends Base<Child.HeaderArg> {
                    class HeaderArg {
                    }

                    Object explicitResult() {
                        return inheritedValue();
                    }
                }
            }
        """.trimIndent()
        val file = write("sample/GenericHeaderScope.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unit = index.unit(file)
        val bodyType = method(unit, "bodyType")
        fun inheritedCallIn(methodName: String): MethodCallExpr =
            method(unit, methodName).findAll(MethodCallExpr::class.java)
                .single { it.nameAsString == "inheritedValue" }

        val inheritedCall = inheritedCallIn("inheritedResult")
        val qualifiedCall = inheritedCallIn("qualifiedResult")
        val explicitCall = inheritedCallIn("explicitResult")

        assertEquals("sample.Outer.HeaderArg", index.expressionType(inheritedCall, inheritedCall))
        assertEquals("sample.Outer.HeaderArg.Nested", index.expressionType(qualifiedCall, qualifiedCall))
        assertEquals(
            "sample.Outer.Child.HeaderArg",
            index.expressionType(explicitCall, explicitCall)
        )
        assertEquals(
            "sample.Outer.UnqualifiedChild.HeaderArg",
            index.declaredType(bodyType.parameters.single().type, bodyType)
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `enclosing source parent contributes its inherited member type`() {
        val source = """
            package sample;

            class Base {
                public class ParentInstructions {
                }
            }

            class Outer extends Base {
                class Child extends ParentInstructions {
                }
            }
        """.trimIndent()
        val file = write("sample/SourceOwned.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        index.unit(file)

        assertTrue(
            index.isTypeAssignableTo(
                "sample.Outer.Child",
                "sample.Base.ParentInstructions"
            )
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `external member contract does not match a different direct owner`() {
        val source = """
            package sample;

            import external.OtherSceneBuilder;
            import external.Selection;

            class Outer extends OtherSceneBuilder {
                class Child extends PonderWorldInstructions {
                    Selection unresolvedSelection(Selection selection) {
                        return selection;
                    }
                }
            }
        """.trimIndent()
        val file = write("sample/WrongOwner.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unresolved = method(index.unit(file), "unresolvedSelection")

        assertNull(index.declaredType(unresolved.parameters.single().type, unresolved))
        assertFalse(
            index.isTypeAssignableTo(
                "sample.Outer.Child",
                "$PONDER_SCENE_BUILDER.PonderWorldInstructions"
            )
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `unresolved project parent vetoes imported direct super fallback`() {
        val parentSource = """
            package sample;

            class ProjectParent extends MissingBase {
            }
        """.trimIndent()
        val childSource = """
            package sample;

            import external.PonderWorldInstructions;
            import external.Selection;

            class Outer extends ProjectParent {
                class Child extends PonderWorldInstructions {
                    Selection unresolvedSelection(Selection selection) {
                        return selection;
                    }
                }
            }
        """.trimIndent()
        val parentFile = write("sample/ProjectParent.java", parentSource)
        val childFile = write("sample/UnresolvedProjectParent.java", childSource)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val unresolved = method(index.unit(childFile), "unresolvedSelection")

        assertNull(index.declaredType(unresolved.parameters.single().type, unresolved))
        assertFalse(
            index.isTypeAssignableTo(
                "sample.Outer.Child",
                "external.PonderWorldInstructions"
            )
        )
        assertEquals(parentSource, parentFile.readText())
        assertEquals(childSource, childFile.readText())
    }

    @Test
    fun `generic superclass preserves primitive array arguments`() {
        val source = """
            package sample;

            class GenericBase<T> {
                T value;
            }

            class PrimitiveArrayChild extends GenericBase<int[]> {
                int at(int i) {
                    return value[i];
                }
            }
        """.trimIndent()
        val file = write("sample/PrimitiveArrayChild.java", source)
        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        val access = method(index.unit(file), "at")
            .findAll(ArrayAccessExpr::class.java)
            .single()

        assertEquals("int", index.expressionType(access, access))
        assertEquals(source, file.readText())
    }

    private fun method(unit: CompilationUnit, name: String): MethodDeclaration =
        unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == name }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }

    private companion object {
        const val PONDER_SCENE_BUILDER =
            "net.createmod.ponder.foundation.PonderSceneBuilder"
    }
}
