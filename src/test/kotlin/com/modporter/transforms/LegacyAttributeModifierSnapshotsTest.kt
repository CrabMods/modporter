package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyAttributeModifierSnapshots
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyAttributeModifierSnapshotsTest {
    @Test
    fun `slot-specific modifiers are snapshotted once around arbitrary activation`() {
        val source = """
            package example;

            import com.google.common.collect.Multimap;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.ai.attributes.Attribute;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.neoforged.neoforge.common.util.FakePlayer;

            final class DeployerFakePlayer extends FakePlayer {
            }

            final class DeployerHandler {
                static void activate(DeployerFakePlayer actor) {
                    Multimap<Attribute, AttributeModifier> temporary =
                        actor.getMainHandItem().getAttributeModifiers(EquipmentSlot.MAINHAND);
                    actor.getAttributes().addTransientAttributeModifiers(temporary);
                    beforeActivation();
                    activateInner(actor);
                    actor.getAttributes().removeAttributeModifiers(temporary);
                }
            }
        """.trimIndent()
        val pass = passWithFakePlayer()

        val migrated = pass.migrateLegacyAttributeModifierSnapshots(source)
        val compact = migrated.filterNot(Char::isWhitespace)

        assertTrue(
            compact.contains(
                "LinkedListMultimap<Holder<Attribute>,AttributeModifier>temporary=" +
                    "LinkedListMultimap.create();"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "actor.getMainHandItem().getAttributeModifiers()" +
                    ".forEach(EquipmentSlot.MAINHAND,temporary::put);"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "actor.getAttributes().addTransientAttributeModifiers(temporary);" +
                    "beforeActivation();activateInner(actor);" +
                    "actor.getAttributes().removeAttributeModifiers(temporary);"
            ),
            migrated
        )
        assertEquals(1, "actor.getMainHandItem()".toRegex().findAll(migrated).count())
        assertEquals(1, ".getAttributeModifiers()".toRegex().findAll(migrated).count())
        assertFalse(migrated.contains("forEachModifier"), migrated)
        assertTrue(
            migrated.contains("import com.google.common.collect.LinkedListMultimap;"),
            migrated
        )
        assertTrue(migrated.contains("import net.minecraft.core.Holder;"), migrated)
        assertFalse(migrated.contains("import com.google.common.collect.Multimap;"), migrated)
    }

    @Test
    fun `a typed local item stack and offhand enum constant are accepted`() {
        val source = """
            package example;

            import com.google.common.collect.Multimap;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.ai.attributes.Attribute;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.minecraft.world.entity.ai.attributes.AttributeMap;
            import net.minecraft.world.item.ItemStack;

            final class AttributeAccess {
                static void apply(ItemStack stack, AttributeMap attributes) {
                    Multimap<Attribute, AttributeModifier> modifiers =
                        stack.getAttributeModifiers(EquipmentSlot.OFFHAND);
                    attributes.addTransientAttributeModifiers(modifiers);
                    invokeTarget();
                    attributes.removeAttributeModifiers(modifiers);
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyAttributeModifierSnapshots(source)

        assertTrue(
            migrated.filterNot(Char::isWhitespace).contains(
                "stack.getAttributeModifiers()" +
                    ".forEach(EquipmentSlot.OFFHAND,modifiers::put);"
            ),
            migrated
        )
        assertFalse(migrated.contains("forEachModifier"), migrated)
    }

    @Test
    fun `impure slot unknown receiver extra use and mismatched attribute maps are rejected`() {
        val sources = listOf(
            """
                package example;
                import com.google.common.collect.Multimap;
                import net.minecraft.world.entity.EquipmentSlot;
                import net.minecraft.world.entity.ai.attributes.Attribute;
                import net.minecraft.world.entity.ai.attributes.AttributeModifier;
                import net.minecraft.world.entity.ai.attributes.AttributeMap;
                import net.minecraft.world.item.ItemStack;
                final class Case {
                    static void run(ItemStack stack, AttributeMap attributes) {
                        Multimap<Attribute, AttributeModifier> modifiers =
                            stack.getAttributeModifiers(slot());
                        attributes.addTransientAttributeModifiers(modifiers);
                        invoke();
                        attributes.removeAttributeModifiers(modifiers);
                    }
                }
            """.trimIndent(),
            """
                package example;
                import com.google.common.collect.Multimap;
                import net.minecraft.world.entity.EquipmentSlot;
                import net.minecraft.world.entity.ai.attributes.Attribute;
                import net.minecraft.world.entity.ai.attributes.AttributeModifier;
                import net.minecraft.world.entity.ai.attributes.AttributeMap;
                final class Case {
                    static void run(Object stack, AttributeMap attributes) {
                        Multimap<Attribute, AttributeModifier> modifiers =
                            stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
                        attributes.addTransientAttributeModifiers(modifiers);
                        invoke();
                        attributes.removeAttributeModifiers(modifiers);
                    }
                }
            """.trimIndent(),
            """
                package example;
                import com.google.common.collect.Multimap;
                import net.minecraft.world.entity.EquipmentSlot;
                import net.minecraft.world.entity.ai.attributes.Attribute;
                import net.minecraft.world.entity.ai.attributes.AttributeModifier;
                import net.minecraft.world.entity.ai.attributes.AttributeMap;
                import net.minecraft.world.item.ItemStack;
                final class Case {
                    static void run(ItemStack stack, AttributeMap attributes) {
                        Multimap<Attribute, AttributeModifier> modifiers =
                            stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
                        attributes.addTransientAttributeModifiers(modifiers);
                        inspect(modifiers);
                        attributes.removeAttributeModifiers(modifiers);
                    }
                }
            """.trimIndent(),
            """
                package example;
                import com.google.common.collect.Multimap;
                import net.minecraft.world.entity.EquipmentSlot;
                import net.minecraft.world.entity.ai.attributes.Attribute;
                import net.minecraft.world.entity.ai.attributes.AttributeModifier;
                import net.minecraft.world.entity.ai.attributes.AttributeMap;
                import net.minecraft.world.item.ItemStack;
                final class Case {
                    static void run(
                        ItemStack stack,
                        AttributeMap applied,
                        AttributeMap removed
                    ) {
                        Multimap<Attribute, AttributeModifier> modifiers =
                            stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
                        applied.addTransientAttributeModifiers(modifiers);
                        invoke();
                        removed.removeAttributeModifiers(modifiers);
                    }
                }
            """.trimIndent()
        )
        val pass = StructuralRefactorPass()

        sources.forEach { source ->
            assertEquals(source, pass.migrateLegacyAttributeModifierSnapshots(source))
        }
    }

    @Test
    fun `a project override with an incompatible return blocks inherited platform inference`() {
        val source = """
            package example;

            import com.google.common.collect.Multimap;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.ai.attributes.Attribute;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.neoforged.neoforge.common.util.FakePlayer;

            final class DeployerFakePlayer extends FakePlayer {
                public Object getMainHandItem() {
                    return null;
                }
            }

            final class DeployerHandler {
                static void activate(DeployerFakePlayer actor) {
                    Multimap<Attribute, AttributeModifier> temporary =
                        actor.getMainHandItem().getAttributeModifiers(EquipmentSlot.MAINHAND);
                    actor.getAttributes().addTransientAttributeModifiers(temporary);
                    activateInner(actor);
                    actor.getAttributes().removeAttributeModifiers(temporary);
                }
            }
        """.trimIndent()
        val pass = passWithFakePlayer(
            methods = mapOf(
                "example.DeployerFakePlayer" to mapOf(
                    "getMainHandItem/0" to listOf(
                        ProjectValueFacts.Method("java.lang.Object", emptyList())
                    )
                )
            )
        )

        assertEquals(source, pass.migrateLegacyAttributeModifierSnapshots(source))
    }

    private fun passWithFakePlayer(
        methods: Map<String, Map<String, List<ProjectValueFacts.Method>>> = emptyMap()
    ): StructuralRefactorPass = StructuralRefactorPass().also { pass ->
        pass.projectValueFacts = ProjectValueFacts(
            methods = methods,
            parents = mapOf(
                "example.DeployerFakePlayer" to
                    "net.neoforged.neoforge.common.util.FakePlayer"
            ),
            types = setOf("example.DeployerFakePlayer")
        )
    }
}
