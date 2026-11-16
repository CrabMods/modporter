package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyMixinMemberMoves
import com.modporter.core.transforms.structural.LegacyProjectileDispenseDirections
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyProjectileDispenseDirectionsTest {
    @TempDir
    lateinit var directory: Path

    private fun java(name: String, text: String): Path =
        directory.resolve("src/main/java/${name.replace('.', '/')}.java").also {
            it.parent.createDirectories()
            it.writeText(text.trimIndent() + "\n")
        }

    /** A mod's own projectile launcher base wrapping vanilla dispense behaviors, as 1.20.1 wrote it. */
    private fun project(position: String): Path {
        java(
            "example.mixin.ProjectileAccess",
            """
            package example.mixin;

            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.gen.Invoker;

            import net.minecraft.core.Position;
            import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
            import net.minecraft.world.entity.projectile.Projectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            @Mixin(ProjectileDispenseBehavior.class)
            public interface ProjectileAccess {
            	@Invoker("getProjectile")
            	Projectile example${'$'}projectile(Level level, Position position, ItemStack stack);
            }
            """
        )
        directory.resolve("src/main/resources/example.mixins.json").also { it.parent.createDirectories() }.writeText(
            "{\n  \"package\": \"example.mixin\",\n  \"mixins\": [\n    \"ProjectileAccess\"\n  ]\n}\n"
        )
        return java(
            "example.Launcher",
            """
            package example;

            import example.mixin.ProjectileAccess;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
            import net.minecraft.world.entity.projectile.Projectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.phys.Vec3;

            public abstract class Launcher {
            	public ItemStack launch(Level level, ItemStack stack, BlockPos pos, Vec3 facing, Vec3 drift) {
            		$position
            		Projectile projectile = this.getProjectile(level, x, y, z, stack.copy());
            		if (projectile == null)
            			return stack;
            		Vec3 motion = facing.add(drift);
            		projectile.shoot(motion.x, motion.y, motion.z, 1.1f, 6);
            		return stack;
            	}

            	protected abstract Projectile getProjectile(Level level, double x, double y, double z, ItemStack stack);

            	public static Launcher of(ProjectileDispenseBehavior vanilla) {
            		ProjectileAccess access = (ProjectileAccess) vanilla;
            		return new Launcher() {
            			@Override
            			protected Projectile getProjectile(Level level, double x, double y, double z, ItemStack stack) {
            				return access.example${'$'}projectile(level, new Vec3(x, y, z), stack);
            			}
            		};
            	}
            }
            """
        )
    }

    @Test
    fun `the dispensing direction is threaded from the vector the projectile is offset along`() {
        val launcher = project(
            "double x = pos.getX() + facing.x * .7 + .5;\n" +
                "\t\tdouble y = pos.getY() + facing.y * .7 + .5;\n" +
                "\t\tdouble z = pos.getZ() + facing.z * .7 + .5;"
        )
        val pass = StructuralRefactorPass()

        val threaded = LegacyProjectileDispenseDirections.migrate(pass, directory, dryRun = false)
        assertEquals(1, threaded.size, threaded.joinToString())
        var text = launcher.readText()
        assertTrue(
            text.contains("this.getProjectile(level, x, y, z, stack.copy(), net.minecraft.core.Direction.getNearest(facing));"),
            text
        )
        assertTrue(
            text.contains("protected abstract Projectile getProjectile(Level level, double x, double y, double z, ItemStack stack, net.minecraft.core.Direction direction);"),
            text
        )
        assertTrue(
            text.contains("protected Projectile getProjectile(Level level, double x, double y, double z, ItemStack stack, net.minecraft.core.Direction direction) {"),
            text
        )

        // the member moves now find the direction where the legacy invoker is called
        LegacyMixinMemberMoves.migrate(pass, directory, dryRun = false)
        text = launcher.readText()
        assertTrue(text.contains("access.example\$getProjectileItem().asProjectile(level, new Vec3(x, y, z), stack, direction);"), text)

        assertTrue(LegacyProjectileDispenseDirections.migrate(pass, directory, dryRun = false).isEmpty())
    }

    @Test
    fun `without one vector the position comes from the family stays unchanged`() {
        val launcher = project(
            "double x = pos.getX() + facing.x + drift.x;\n" +
                "\t\tdouble y = pos.getY();\n" +
                "\t\tdouble z = pos.getZ();"
        )
        val before = launcher.readText()

        assertTrue(LegacyProjectileDispenseDirections.migrate(StructuralRefactorPass(), directory, dryRun = false).isEmpty())
        assertEquals(before, launcher.readText())
    }
}
