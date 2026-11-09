package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateProjectileAndStewSurfaces
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateProjectileAndStewSurfacesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `captures exact projectile fields and preserves Create motion and stew order`() {
        val fixture = fixture(directory)

        val changes = migrate(directory)

        assertEquals(5, changes.size, changes.joinToString())
        val accessor = fixture.projectileAccessor.readText()
        assertTrue(accessor.contains("@Mixin(ProjectileDispenseBehavior.class)"), accessor)
        assertTrue(accessor.contains("""@Accessor("projectileItem")"""), accessor)
        assertTrue(accessor.contains("""@Accessor("dispenseConfig")"""), accessor)
        assertTrue(
            accessor.contains("ProjectileItem create\$getProjectileItem();"),
            accessor
        )
        assertTrue(
            accessor.contains(
                "ProjectileItem.DispenseConfig create\$getDispenseConfig();"
            ),
            accessor
        )
        assertFalse(accessor.contains("@Invoker("), accessor)
        assertFalse(accessor.contains("create\$callGetProjectile"), accessor)
        assertFalse(accessor.contains("create\$callGetPower"), accessor)
        assertFalse(accessor.contains("create\$callGetUncertainty"), accessor)

        val mounted = fixture.mounted.readText()
        assertTrue(mounted.contains("new Vec3(x, y, z)"), mounted)
        assertTrue(mounted.contains("stack.copy(), direction"), mounted)
        assertTrue(
            mounted.contains(
                "MountedDispenseBehavior.getClosestFacingDirection(facing)"
            ),
            mounted
        )
        assertTrue(
            compact(mounted).contains(
                "Vec3 motion = facing.scale(dispenseConfig.power()).add(" +
                    "context.motion);"
            ),
            mounted
        )
        assertTrue(mounted.contains("projectileItem.asProjectile("), mounted)
        assertTrue(mounted.contains("projectileItem.shoot("), mounted)
        assertFalse(mounted.contains("createDispenseConfig()"), mounted)
        assertFalse(mounted.contains("(ProjectileItem)"), mounted)
        assertTrue(mounted.contains("(float) motion.length()"), mounted)
        assertTrue(mounted.contains("dispenseConfig.uncertainty()"), mounted)
        assertTrue(
            mounted.contains(
                "dispenseConfig.overrideDispenseEvent().orElse("
            ),
            mounted
        )
        assertFalse(mounted.contains("positionFunction()"), mounted)
        assertFalse(mounted.contains("create\$callGetProjectile"), mounted)
        assertFalse(mounted.contains("create\$callGetPower"), mounted)
        assertFalse(mounted.contains("create\$callGetUncertainty"), mounted)
        assertOrdered(
            mounted,
            "double x = pos.getX() + facing.x * .7 + .5;",
            "double y = pos.getY() + facing.y * .7 + .5;",
            "double z = pos.getZ() + facing.z * .7 + .5;",
            "Direction direction =",
            "Projectile projectile = projectileItem.asProjectile(",
            "if (projectile == null)",
            "Vec3 motion = facing.scale(dispenseConfig.power())",
            "projectileItem.shoot(",
            "(float) motion.length(), dispenseConfig.uncertainty());",
            "context.world.addFreshEntity(projectile);",
            "stack.shrink(1);"
        )

        val hit = fixture.hitActions.readText()
        assertFalse(hit.contains("SuspiciousStewItemAccessor"), hit)
        assertTrue(
            hit.contains(
                "import net.minecraft.core.component.DataComponents;"
            ),
            hit
        )
        assertTrue(
            hit.contains(
                "import net.minecraft.world.item.component.SuspiciousStewEffects;"
            ),
            hit
        )
        assertTrue(hit.contains("projectile.getOrDefault("), hit)
        assertOrdered(
            hit,
            "DataComponents.SUSPICIOUS_STEW_EFFECTS",
            "SuspiciousStewEffects.EMPTY",
            ".effects()",
            "livingEntity.addEffect(",
            "effect.createEffectInstance()"
        )
        assertFalse(fixture.stewAccessor.exists())
        val config = fixture.config.readText()
        assertFalse(config.contains("accessor.SuspiciousStewItemAccessor"), config)
        assertTrue(config.contains("accessor.DispenserBlockAccessor"), config)
        assertTrue(
            config.contains("accessor.AbstractProjectileDispenseBehaviorAccessor"),
            config
        )
        assertTrue(config.contains("accessor.SystemReportAccessor"), config)

        assertTrue(migrate(directory).isEmpty())
    }

    @Test
    fun `dry run validates the closed family without changing or deleting files`() {
        val fixture = fixture(directory)
        val original = fixture.allExisting().associateWith { it.readText() }

        val changes = CreateProjectileAndStewSurfaces.migrate(
            StructuralRefactorPass(),
            directory,
            dryRun = true
        )

        assertEquals(5, changes.size, changes.joinToString())
        original.forEach { (file, source) ->
            assertTrue(file.exists(), file.toString())
            assertEquals(source, file.readText(), file.toString())
        }
    }

    @Test
    fun `an additional stew accessor caller blocks every family write`() {
        val fixture = fixture(directory)
        val other = directory.resolve("src/main/java/example/Other.java")
        other.parent.createDirectories()
        other.writeText(
            """
            package example;
            import com.simibubi.create.foundation.mixin.accessor.SuspiciousStewItemAccessor;
            class Other {
                void apply(ItemStack stack, LivingEntity entity) {
                    SuspiciousStewItemAccessor.create${'$'}listPotionEffects(
                        stack, entity::addEffect);
                }
            }
            """.trimIndent()
        )
        val original = fixture.allExisting().associateWith { it.readText() }

        val error = assertFailsWith<IllegalStateException> {
            migrate(directory)
        }

        assertTrue(error.message.orEmpty().contains("unexpected callers"), error.message)
        original.forEach { (file, source) ->
            assertTrue(file.exists(), file.toString())
            assertEquals(source, file.readText(), file.toString())
        }
    }

    @Test
    fun `an incomplete projectile invoker family blocks stew cleanup and all writes`() {
        val fixture = fixture(directory)
        fixture.projectileAccessor.writeText(
            fixture.projectileAccessor.readText().replace(
                Regex(
                    """(?m)^[ \t]*@Invoker\("getPower"\)[ \t]*\r?\n""" +
                        """[ \t]*float create\${'$'}callGetPower\(\);[ \t]*\r?\n?"""
                ),
                ""
            )
        )
        val original = fixture.allExisting().associateWith { it.readText() }

        val error = assertFailsWith<IllegalStateException> {
            migrate(directory)
        }

        assertTrue(
            error.message.orEmpty().contains("exact three legacy invokers"),
            error.message
        )
        original.forEach { (file, source) ->
            assertTrue(file.exists(), file.toString())
            assertEquals(source, file.readText(), file.toString())
        }
    }

    private fun migrate(root: Path) =
        CreateProjectileAndStewSurfaces.migrate(
            StructuralRefactorPass(),
            root,
            dryRun = false
        )

    private fun fixture(root: Path): Fixture {
        val projectileAccessor = root.resolve(
            "src/main/java/com/simibubi/create/foundation/mixin/accessor/" +
                "AbstractProjectileDispenseBehaviorAccessor.java"
        )
        projectileAccessor.parent.createDirectories()
        projectileAccessor.writeText(
            """
            package com.simibubi.create.foundation.mixin.accessor;

            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.gen.Invoker;
            import net.minecraft.core.Position;
            import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
            import net.minecraft.world.entity.projectile.Projectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            @Mixin(ProjectileDispenseBehavior.class)
            public interface AbstractProjectileDispenseBehaviorAccessor {
            	@Invoker("getProjectile")
            	Projectile create${'$'}callGetProjectile(
                    Level level, Position position, ItemStack stack);
            	@Invoker("getUncertainty")
            	float create${'$'}callGetUncertainty();
            	@Invoker("getPower")
            	float create${'$'}callGetPower();
            }
            """.trimIndent() + "\n"
        )

        val mounted = root.resolve(
            "src/main/java/com/simibubi/create/api/contraption/dispenser/" +
                "MountedProjectileDispenseBehavior.java"
        )
        mounted.parent.createDirectories()
        mounted.writeText(
            """
            package com.simibubi.create.api.contraption.dispenser;

            import com.simibubi.create.content.contraptions.behaviour.MovementContext;
            import com.simibubi.create.foundation.mixin.accessor.AbstractProjectileDispenseBehaviorAccessor;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
            import net.minecraft.world.entity.projectile.Projectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.LevelAccessor;
            import net.minecraft.world.level.block.LevelEvent;
            import net.minecraft.world.phys.Vec3;

            /**
             * A parallel to {@link AbstractProjectileDispenseBehavior}.
             */
            public abstract class MountedProjectileDispenseBehavior
                    extends DefaultMountedDispenseBehavior {
            	protected abstract Projectile getProjectile(
                    Level level, double x, double y, double z, ItemStack stack);
            	protected float getUncertainty() { return 6; }
            	protected float getPower() { return 1.1f; }

            	public static MountedDispenseBehavior of(
                        ProjectileDispenseBehavior vanillaBehaviour) {
            		AbstractProjectileDispenseBehaviorAccessor accessor =
                        (AbstractProjectileDispenseBehaviorAccessor) vanillaBehaviour;
            		return new MountedProjectileDispenseBehavior() {
            			protected Projectile getProjectile(
                                Level level, double x, double y, double z,
                                ItemStack stack) {
            				return accessor.create${'$'}callGetProjectile(
                                level,
                                new com.modporter.generated.create.compat.LegacyPositionImpl(
                                    x, y, z),
                                stack);
            			}
            			protected float getUncertainty() {
            				return accessor.create${'$'}callGetUncertainty();
            			}
            			protected float getPower() {
            				return accessor.create${'$'}callGetPower();
            			}
            		};
            	}
            }
            """.trimIndent() + "\n"
        )

        val hitActions = root.resolve(
            "src/main/java/com/simibubi/create/content/equipment/potatoCannon/" +
                "AllPotatoProjectileEntityHitActions.java"
        )
        hitActions.parent.createDirectories()
        hitActions.writeText(
            """
            package com.simibubi.create.content.equipment.potatoCannon;

            import com.simibubi.create.foundation.mixin.accessor.SuspiciousStewItemAccessor;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.phys.EntityHitResult;

            public class AllPotatoProjectileEntityHitActions {
            	public boolean execute(
                        ItemStack projectile, EntityHitResult ray, Object type) {
            		if (ray.getEntity() instanceof LivingEntity livingEntity)
            			SuspiciousStewItemAccessor.create${'$'}listPotionEffects(projectile, livingEntity::addEffect);

            		return false;
            	}
            }
            """.trimIndent() + "\n"
        )

        val stewAccessor = root.resolve(
            "src/main/java/com/simibubi/create/foundation/mixin/accessor/" +
                "SuspiciousStewItemAccessor.java"
        )
        stewAccessor.writeText(
            """
            package com.simibubi.create.foundation.mixin.accessor;

            import java.util.function.Consumer;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.gen.Invoker;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.SuspiciousStewItem;

            @Mixin(SuspiciousStewItem.class)
            public interface SuspiciousStewItemAccessor {
            	@Invoker("listPotionEffects")
            	static void create${'$'}listPotionEffects(
                        ItemStack stack, Consumer<MobEffectInstance> output) {
            		throw new AssertionError();
            	}
            }
            """.trimIndent() + "\n"
        )

        val config = root.resolve("src/main/resources/create.mixins.json")
        config.parent.createDirectories()
        config.writeText(
            """
            {
              "required": true,
              "package": "com.simibubi.create.foundation.mixin",
              "mixins": [
                "accessor.AbstractProjectileDispenseBehaviorAccessor",
                "accessor.DispenserBlockAccessor",
                "accessor.SuspiciousStewItemAccessor",
                "accessor.SystemReportAccessor"
              ]
            }
            """.trimIndent() + "\n"
        )
        return Fixture(
            projectileAccessor,
            mounted,
            hitActions,
            stewAccessor,
            config
        )
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .trim()

    private fun assertOrdered(source: String, vararg markers: String) {
        var previous = -1
        markers.forEach { marker ->
            val current = source.indexOf(marker)
            assertTrue(current > previous, "marker '$marker' after $previous:\n$source")
            previous = current
        }
    }

    private data class Fixture(
        val projectileAccessor: Path,
        val mounted: Path,
        val hitActions: Path,
        val stewAccessor: Path,
        val config: Path
    ) {
        fun allExisting(): List<Path> = listOf(
            projectileAccessor,
            mounted,
            hitActions,
            stewAccessor,
            config
        )
    }
}
