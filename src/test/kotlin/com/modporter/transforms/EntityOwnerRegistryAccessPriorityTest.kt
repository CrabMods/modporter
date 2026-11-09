package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityOwnerRegistryAccessPriorityTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `entity owner registry access outranks an uninitialized entity field for enchantment lookups`() {
        val file = directory.resolve("src/main/java/example/PotatoProjectileEntity.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantments;

            public class PotatoProjectileEntity extends AbstractHurtingProjectile {
                protected Entity stuckEntity;

                public void setEnchantmentEffectsFromCannon(ItemStack cannon) {
                    int power = cannon.getEnchantmentLevel(Enchantments.POWER_ARROWS);
                    int punch = cannon.getEnchantmentLevel(Enchantments.PUNCH_ARROWS);
                    int flame = cannon.getEnchantmentLevel(Enchantments.FLAMING_ARROWS);
                    int recovery = cannon.getEnchantmentLevel(AllEnchantments.POTATO_RECOVERY.get());
                }

                public Entity getStuckEntity() {
                    return stuckEntity;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(directory)
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val migrated = file.readText()
        val selfLookups = Regex(
            """this\.registryAccess\(\)\.lookup\(net\.minecraft\.core\.registries\.Registries\.ENCHANTMENT\)"""
        ).findAll(migrated).count()

        assertEquals(4, selfLookups, migrated)
        assertFalse(migrated.contains("stuckEntity.registryAccess()"), migrated)
        assertTrue(migrated.contains("protected Entity stuckEntity;"), migrated)
        assertTrue(migrated.contains("return stuckEntity;"), migrated)
        assertTrue(migrated.contains("registry.get(AllEnchantments.POTATO_RECOVERY)"), migrated)
    }

    @Test
    fun `explicit level parameter and guarded local level keep priority over entity self and fields`() {
        val file = directory.resolve("src/main/java/example/RegistryPriorityProjectile.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.Level;

            public class RegistryPriorityProjectile extends AbstractHurtingProjectile {
                protected Entity target;

                public int fromParameter(Level explicitLevel, ItemStack stack) {
                    return stack.getEnchantmentLevel(Enchantments.POWER_ARROWS);
                }

                public int fromGuardedLocal(ItemStack stack) {
                    Level explicitLevel = level();
                    if (explicitLevel.isClientSide) return 0;
                    return stack.getEnchantmentLevel(Enchantments.PUNCH_ARROWS);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(directory)
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val migrated = file.readText()
        val explicitLookups = Regex(
            """explicitLevel\.registryAccess\(\)\.lookup\(net\.minecraft\.core\.registries\.Registries\.ENCHANTMENT\)"""
        ).findAll(migrated).count()

        assertEquals(2, explicitLookups, migrated)
        assertFalse(migrated.contains("this.registryAccess().lookup"), migrated)
        assertFalse(migrated.contains("target.registryAccess()"), migrated)
    }
}
