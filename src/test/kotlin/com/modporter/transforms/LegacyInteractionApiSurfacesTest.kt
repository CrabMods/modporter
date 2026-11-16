package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateBlockUseMethod
import com.modporter.core.transforms.structural.migrateBucketPickupPickupBlockCalls
import com.modporter.core.transforms.structural.migrateLegacyInteractionApiSurfaces
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyInteractionApiSurfacesTest {
    @TempDir lateinit var directory: Path

    private val pass = StructuralRefactorPass()

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\s*,\s*"""), ",")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()

    @Test
    fun `canonical native receivers retain all arguments across 121 signature moves`() {
        val source = """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.world.entity.decoration.HangingEntity;
            import net.minecraft.world.entity.projectile.SmallFireball;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.LevelAccessor;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.LevelEvent;
            import net.minecraft.world.level.block.LiquidBlock;
            import net.minecraft.world.level.block.state.BlockState;

            class Calls {
                void neighbor(BlockState state, Level level, BlockPos pos, Block source) {
                    state.neighborChanged(level, pos, source, pos, false);
                }
                Object fluid(BlockState state) {
                    if (state.getBlock() instanceof LiquidBlock flowingFluid)
                        return flowingFluid.getFluid();
                    return null;
                }
                void smoke(LevelAccessor level, BlockPos pos) {
                    level.levelEvent(LevelEvent.PARTICLES_SHOOT, pos, 0);
                }
                SmallFireball fire(Level level) {
                    return new SmallFireball(
                        level, x(), y(), z(), dx(), dy(), dz()
                    );
                }
                double x() { return 1; }
                double y() { return 2; }
                double z() { return 3; }
                double dx() { return 4; }
                double dy() { return 5; }
                double dz() { return 6; }
            }
            class Helper extends GameTestHelper {
                void interact() {
                    Runnable action = () -> consume(makeMockPlayer());
                }
                void consume(Object player) {}
            }
            class Poster extends HangingEntity {
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyInteractionApiSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(normalized.contains("state.handleNeighborChanged(level,pos,source,pos,false);"), migrated)
        assertTrue(normalized.contains("return flowingFluid.fluid;"), migrated)
        assertTrue(normalized.contains("LevelEvent.PARTICLES_SHOOT_SMOKE"), migrated)
        assertTrue(
            normalized.contains("new SmallFireball(level,x(),y(),z(),new net.minecraft.world.phys.Vec3(dx(),dy(),dz()))"),
            migrated
        )
        assertTrue(normalized.contains("makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE)"), migrated)
        assertTrue(
            normalized.contains(
                "protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder)"
            ),
            migrated
        )
        for (call in listOf("dx()", "dy()", "dz()")) {
            assertEquals(source.split(call).size, migrated.split(call).size, call)
        }
        assertEquals(migrated, pass.migrateLegacyInteractionApiSurfaces(migrated))
    }

    @Test
    fun `post attack hooks reuse the damage source that already reached hurt`() {
        val captured = """
            package example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            class Projectile {
                void hit(Entity target, Entity owner, float damage) {
                    boolean onServer = !level().isClientSide;
                    if (onServer && !target.hurt(causeDamage(), damage)) {
                        discard();
                        return;
                    }
                    if (!(target instanceof LivingEntity living))
                        return;
                    if (onServer && owner instanceof LivingEntity) {
                        EnchantmentHelper.doPostHurtEffects(living, owner);
                        EnchantmentHelper.doPostDamageEffects((LivingEntity) owner, living);
                    }
                }
            }
        """.trimIndent()
        val local = """
            package example;

            import net.minecraft.world.damagesource.DamageSource;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            class Melee {
                void strike(LivingEntity self, LivingEntity victim) {
                    DamageSource source = self.damageSources().mobAttack(self);
                    victim.hurt(source, 4.0F);
                    EnchantmentHelper.doPostHurtEffects(victim, self);
                    EnchantmentHelper.doPostDamageEffects(self, victim);
                }
            }
        """.trimIndent()

        val migratedCaptured = pass.migrateLegacyInteractionApiSurfaces(captured)
        val capturedCompact = compact(migratedCaptured)
        assertEquals(1, Regex("""\bcauseDamage\(\)""").findAll(migratedCaptured).count(), migratedCaptured)
        assertTrue(capturedCompact.contains("net.minecraft.world.damagesource.DamageSource modporterDamageSource = null;"), migratedCaptured)
        assertTrue(capturedCompact.contains("onServer && !target.hurt((modporterDamageSource = causeDamage()),damage)"), migratedCaptured)
        assertTrue(
            capturedCompact.contains(
                "if (living.level() instanceof net.minecraft.server.level.ServerLevel && modporterDamageSource != null) " +
                    "net.minecraft.world.item.enchantment.EnchantmentHelper.doPostAttackEffects(" +
                    "(net.minecraft.server.level.ServerLevel) living.level(),living,modporterDamageSource);"
            ),
            migratedCaptured
        )
        assertFalse(migratedCaptured.contains("doPostHurtEffects"), migratedCaptured)
        assertFalse(migratedCaptured.contains("doPostDamageEffects"), migratedCaptured)
        assertEquals(migratedCaptured, pass.migrateLegacyInteractionApiSurfaces(migratedCaptured))

        val migratedLocal = pass.migrateLegacyInteractionApiSurfaces(local)
        assertTrue(
            compact(migratedLocal).contains(
                "if (victim.level() instanceof net.minecraft.server.level.ServerLevel) " +
                    "net.minecraft.world.item.enchantment.EnchantmentHelper.doPostAttackEffects(" +
                    "(net.minecraft.server.level.ServerLevel) victim.level(),victim,source);"
            ),
            migratedLocal
        )
        assertFalse(migratedLocal.contains("modporterDamageSource"), migratedLocal)
    }

    @Test
    fun `knockback bonus keeps the equipment enchantment level lookup`() {
        val source = """
            package example;

            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            class Knock {
                double strength(LivingEntity attacker) {
                    double knockback = 2;
                    knockback += EnchantmentHelper.getKnockbackBonus(attacker);
                    return knockback;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyInteractionApiSurfaces(source)

        assertTrue(
            compact(migrated).contains(
                "knockback += net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentLevel(" +
                    "attacker.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)" +
                    ".getOrThrow(net.minecraft.world.item.enchantment.Enchantments.KNOCKBACK),attacker);"
            ),
            migrated
        )
        assertEquals(migrated, pass.migrateLegacyInteractionApiSurfaces(migrated))
    }

    @Test
    fun `a call to another block's legacy use reaches the hook that block migrated to`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        root.resolve("Target.java").writeText(
            """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public class Target extends Block {
                @Override
                public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
                    ItemStack held = player.getItemInHand(hand);
                    return held.isEmpty() ? InteractionResult.PASS : InteractionResult.SUCCESS;
                }
            }
            """.trimIndent()
        )
        root.resolve("Plain.java").writeText(
            """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public class Plain extends Block {
                @Override
                public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
                    return InteractionResult.SUCCESS;
                }
            }
            """.trimIndent()
        )
        root.resolve("Extender.java").writeText(
            """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public class Extender extends Block {
                @Override
                public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
                    if (player.getItemInHand(hand).isEmpty())
                        return InteractionResult.PASS;
                    BlockState root = level.getBlockState(pos.below());
                    if (root.getBlock() instanceof Target target)
                        return target.use(root, level, pos.below(), player, hand, hit);
                    return InteractionResult.PASS;
                }

                InteractionResult relay(Plain plain, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
                    return plain.use(state, level, pos, player, hand, hit);
                }

                InteractionResult remote(Target target, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
                    InteractionResult result = target.use(state, level, pos, player, hand, hit);
                    return result;
                }
            }
            """.trimIndent()
        )

        pass.migrateBlockUseMethod(directory, dryRun = false)

        val extender = compact(root.resolve("Extender.java").readText())
        assertTrue(extender.contains("return target.useItemOn(itemstack,root,level,pos.below(),player,hand,hit);"), extender)
        assertTrue(extender.contains("return plain.useWithoutItem(state,level,pos,player,hit);"), extender)
        assertTrue(
            extender.contains("InteractionResult result = target.useItemOn(player.getItemInHand(hand),state,level,pos,player,hand,hit).result();"),
            extender
        )
        assertFalse(extender.contains(".use("), extender)
    }

    @Test
    fun `negated bucket pickup exit guard proves the pattern variable after the if`() {
        val source = """
            import net.minecraft.world.level.block.BucketPickup;
            class Mounted {
                Object execute(Context context, Object pos, Object state) {
                    if (!(state.getBlock() instanceof BucketPickup bucketPickup)) {
                        return fallback();
                    }
                    return bucketPickup.pickupBlock(context.world, pos, state);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateBucketPickupPickupBlockCalls(source)

        assertTrue(compact(migrated).contains("bucketPickup.pickupBlock(null,context.world,pos,state)"), migrated)
        assertEquals(migrated, pass.migrateBucketPickupPickupBlockCalls(migrated))
    }

    @Test
    fun `same-named local APIs and non-dominating pattern guards remain unchanged`() {
        val lookalikes = """
            class BlockState {
                void neighborChanged(Object a, Object b, Object c, Object d, boolean e) {}
            }
            class LiquidBlock {
                Object getFluid() { return null; }
            }
            class LevelEvent {
                static int PARTICLES_SHOOT;
            }
            class SmallFireball {
                SmallFireball(Object a, double b, double c, double d,
                    double e, double f, double g) {}
            }
            class EnchantmentHelper {
                static int getKnockbackBonus(Object entity) { return 0; }
            }
            class Calls {
                void run(BlockState state, LiquidBlock fluid) {
                    state.neighborChanged(null, null, null, null, false);
                    fluid.getFluid();
                    int event = LevelEvent.PARTICLES_SHOOT;
                    new SmallFireball(null, 1, 2, 3, 4, 5, 6);
                    int bonus = EnchantmentHelper.getKnockbackBonus(this);
                }
            }
        """.trimIndent()
        val nonDominating = """
            import net.minecraft.world.level.block.BucketPickup;
            class Mounted {
                Object execute(Context context, Object pos, Object state, boolean enabled) {
                    if (enabled) {
                        if (!(state.getBlock() instanceof BucketPickup bucketPickup)) {
                            return fallback();
                        }
                    }
                    return bucketPickup.pickupBlock(context.world, pos, state);
                }
            }
        """.trimIndent()

        assertEquals(lookalikes, pass.migrateLegacyInteractionApiSurfaces(lookalikes))
        assertEquals(nonDominating, pass.migrateBucketPickupPickupBlockCalls(nonDominating))
    }
}
