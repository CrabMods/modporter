package com.modporter.transforms

import com.modporter.core.transforms.structural.cleanupMixinLegacyCapabilityProviderSuperclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyCapabilityProviderMixinsTest {
    @Test
    fun `generated provider base and private delegation constructor are removed from mixin`() {
        val source = """
            package example;

            import com.modporter.generated.example.compat.CapabilityProvider;
            import net.minecraft.world.entity.Entity;
            import org.spongepowered.asm.mixin.Mixin;

            @Mixin(Entity.class)
            public abstract class EntityMixin extends CapabilityProvider<Entity> {
                private EntityMixin(Class<Entity> baseClass) {
                    super(baseClass);
                }

                public void touch() {
                }
            }
        """.trimIndent()

        val migrated = cleanupMixinLegacyCapabilityProviderSuperclass(source)

        assertTrue(migrated.contains("public abstract class EntityMixin {"), migrated)
        assertFalse(migrated.contains("CapabilityProvider"), migrated)
        assertFalse(migrated.contains("EntityMixin(Class<Entity> baseClass)"), migrated)
        assertTrue(migrated.contains("public void touch()"), migrated)
    }

    @Test
    fun `nontrivial constructor keeps provider base unchanged`() {
        val source = """
            package example;
            import com.modporter.generated.example.compat.CapabilityProvider;
            import net.minecraft.world.entity.Entity;
            import org.spongepowered.asm.mixin.Mixin;
            @Mixin(Entity.class)
            abstract class EntityMixin extends CapabilityProvider<Entity> {
                private EntityMixin(Class<Entity> baseClass) {
                    super(baseClass);
                    initialize();
                }
                abstract void initialize();
            }
        """.trimIndent()

        assertEquals(source, cleanupMixinLegacyCapabilityProviderSuperclass(source))
    }

    @Test
    fun `inherited capability use keeps provider base unchanged`() {
        val source = """
            package example;
            import net.minecraftforge.common.capabilities.CapabilityProvider;
            import net.minecraft.world.entity.Entity;
            import org.spongepowered.asm.mixin.Mixin;
            @Mixin(Entity.class)
            abstract class EntityMixin extends CapabilityProvider<Entity> {
                protected EntityMixin(Class<Entity> baseClass) {
                    super(baseClass);
                }
                void clear() {
                    super.invalidateCaps();
                }
            }
        """.trimIndent()

        assertEquals(source, cleanupMixinLegacyCapabilityProviderSuperclass(source))
    }

    @Test
    fun `ordinary provider subclass is not rewritten`() {
        val source = """
            package example;
            import net.minecraftforge.common.capabilities.CapabilityProvider;
            class Provider extends CapabilityProvider<Object> {
                Provider(Class<Object> baseClass) {
                    super(baseClass);
                }
            }
        """.trimIndent()

        assertEquals(source, cleanupMixinLegacyCapabilityProviderSuperclass(source))
    }
}
