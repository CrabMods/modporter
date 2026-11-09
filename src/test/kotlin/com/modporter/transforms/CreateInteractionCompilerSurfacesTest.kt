package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateBucketPickupPickupBlockCalls
import com.modporter.core.transforms.structural.migrateBlockUseMethod
import com.modporter.core.transforms.structural.migrateCreateInteractionCompilerSurfaces
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateInteractionCompilerSurfacesTest {
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
    fun `sliding door forwarding becomes item-free without losing the paired-door call`() {
        val source = """
            package com.simibubi.create.content.decoration.slidingDoor;

            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.ItemInteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.phys.BlockHitResult;

            class SlidingDoorBlock {
                protected ItemInteractionResult useItemOn(
                    ItemStack itemstack, BlockState state, Level level, BlockPos pos,
                    Player player, InteractionHand hand, BlockHitResult hit
                ) {
                    BlockState other = level.getBlockState(pos);
                    if (other != state)
                        use(other, level, pos, player, hand, hit);
                    return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateCreateInteractionCompilerSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "protected net.minecraft.world.InteractionResult useWithoutItem(" +
                    "BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit)"
            ),
            migrated
        )
        assertTrue(
            normalized.contains("useWithoutItem(other,level,pos,player,hit);"),
            migrated
        )
        assertTrue(
            normalized.contains(
                "return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);"
            ),
            migrated
        )
        assertFalse(migrated.contains("ItemStack itemstack"), migrated)
        assertFalse(migrated.contains("InteractionHand hand"), migrated)
        assertEquals(migrated, pass.migrateCreateInteractionCompilerSurfaces(migrated))
    }

    @Test
    fun `raw block use classification does not treat a forwarded hand as held-item access`() {
        val file = directory.resolve("src/main/java/example/SlidingDoor.java")
        file.parent.createDirectories()
        file.writeText(
            """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;
            class SlidingDoor {
                @Override
                public InteractionResult use(
                    BlockState state, Level level, BlockPos pos, Player player,
                    InteractionHand hand, BlockHitResult hit
                ) {
                    BlockState otherDoor = level.getBlockState(pos);
                    BlockPos otherPos = pos.above();
                    use(otherDoor, level, otherPos, player, hand, hit);
                    return InteractionResult.sidedSuccess(level.isClientSide);
                }
            }
            """.trimIndent()
        )

        val changes = pass.migrateBlockUseMethod(directory, dryRun = false)
        val migrated = file.readText()
        val normalized = compact(migrated)

        assertEquals(1, changes.size)
        assertTrue(normalized.contains("InteractionResult useWithoutItem("), migrated)
        assertTrue(
            normalized.contains(
                "useWithoutItem(otherDoor,level,otherPos,player,hit);"
            ),
            migrated
        )
        assertFalse(migrated.contains("ItemInteractionResult useItemOn"), migrated)
        assertFalse(migrated.contains("InteractionHand hand"), migrated)
    }

    @Test
    fun `legacy whistle forwarding migrates before the generic block use pass`() {
        val source = """
            package com.simibubi.create.content.decoration.steamWhistle;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            class WhistleExtenderBlock {
                public InteractionResult use(
                    BlockState pState,
                    Level pLevel,
                    BlockPos pPos,
                    Player pPlayer,
                    InteractionHand pHand,
                    BlockHitResult pHit
                ) {
                    if (pPlayer == null ||
                        !AllBlocks.STEAM_WHISTLE.isIn(pPlayer.getItemInHand(pHand)))
                        return InteractionResult.PASS;
                    Level level = pLevel;
                    BlockPos findRoot = findRoot(level, pPos);
                    BlockState blockState = level.getBlockState(findRoot);
                    if (blockState.getBlock() instanceof WhistleBlock whistle)
                        return whistle.use(
                            blockState,
                            pLevel,
                            findRoot,
                            pPlayer,
                            pHand,
                            new BlockHitResult(
                                pHit.getLocation(),
                                pHit.getDirection(),
                                findRoot,
                                pHit.isInside()
                            )
                        );
                    return InteractionResult.PASS;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateCreateInteractionCompilerSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "protected net.minecraft.world.ItemInteractionResult useItemOn(" +
                    "net.minecraft.world.item.ItemStack stack,BlockState pState,"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "AllBlocks.STEAM_WHISTLE.isIn(stack)"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "return whistle.useItemOn(stack,blockState,pLevel,findRoot,pPlayer,pHand," +
                    "new BlockHitResult(pHit.getLocation(),pHit.getDirection(),findRoot," +
                    "pHit.isInside()));"
            ),
            migrated
        )
        assertEquals(
            2,
            Regex(
                """net\.minecraft\.world\.ItemInteractionResult\.""" +
                    """PASS_TO_DEFAULT_BLOCK_INTERACTION"""
            ).findAll(migrated).count(),
            migrated
        )
        assertFalse(migrated.contains("getItemInHand"), migrated)
        assertFalse(migrated.contains("return switch"), migrated)
        assertEquals(migrated, pass.migrateCreateInteractionCompilerSurfaces(migrated))

        val lookalike = source.replace(
            "package com.simibubi.create.content.decoration.steamWhistle;",
            "package example;"
        )
        assertEquals(lookalike, pass.migrateCreateInteractionCompilerSurfaces(lookalike))
    }

    @Test
    fun `whistle forwarding uses the supplied stack and removes only the generated result adapter`() {
        val source = """
            package com.simibubi.create.content.decoration.steamWhistle;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.ItemInteractionResult;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            class WhistleBlock {
                Object use(BlockState state, Level level, BlockPos pos, Player player,
                    InteractionHand hand, BlockHitResult hit) { return null; }
            }
            class WhistleExtenderBlock {
                protected ItemInteractionResult useItemOn(
                    ItemStack stack, BlockState state, Level level, BlockPos pos,
                    Player player, InteractionHand hand, BlockHitResult hit
                ) {
                    if (!AllBlocks.STEAM_WHISTLE.isIn(player.getItemInHand(hand)))
                        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
                    BlockPos root = pos;
                    BlockState rootState = level.getBlockState(root);
                    if (rootState.getBlock() instanceof WhistleBlock whistle)
                        return switch (whistle.use(rootState, level, root, player, hand,
                            new BlockHitResult(hit.getLocation(), hit.getDirection(), root, hit.isInside()))) {
                            case SUCCESS -> net.minecraft.world.ItemInteractionResult.SUCCESS;
                            case SUCCESS_NO_ITEM_USED -> net.minecraft.world.ItemInteractionResult.SUCCESS;
                            case CONSUME -> net.minecraft.world.ItemInteractionResult.CONSUME;
                            case CONSUME_PARTIAL -> net.minecraft.world.ItemInteractionResult.CONSUME_PARTIAL;
                            case FAIL -> net.minecraft.world.ItemInteractionResult.FAIL;
                            default -> net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
                        };
                    return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateCreateInteractionCompilerSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains(
                "return whistle.useItemOn(stack,rootState,level,root,player,hand," +
                    "new BlockHitResult(hit.getLocation(),hit.getDirection(),root,hit.isInside()));"
            ),
            migrated
        )
        assertTrue(
            normalized.contains("AllBlocks.STEAM_WHISTLE.isIn(stack)"),
            migrated
        )
        assertFalse(migrated.contains("return switch"), migrated)
        assertEquals(migrated, pass.migrateCreateInteractionCompilerSurfaces(migrated))
    }

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

        val migrated = pass.migrateCreateInteractionCompilerSurfaces(source)
        val normalized = compact(migrated)

        assertTrue(
            normalized.contains("state.handleNeighborChanged(level,pos,source,pos,false);"),
            migrated
        )
        assertTrue(normalized.contains("return flowingFluid.fluid;"), migrated)
        assertTrue(
            normalized.contains("LevelEvent.PARTICLES_SHOOT_SMOKE"),
            migrated
        )
        assertTrue(
            normalized.contains(
                "new SmallFireball(level,x(),y(),z()," +
                    "new net.minecraft.world.phys.Vec3(dx(),dy(),dz()))"
            ),
            migrated
        )
        assertFalse(normalized.contains("Vec3(dx(),dy(),dz()).normalize()"), migrated)
        assertTrue(
            normalized.contains(
                "makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE)"
            ),
            migrated
        )
        assertTrue(
            normalized.contains(
                "protected void defineSynchedData(" +
                    "net.minecraft.network.syncher.SynchedEntityData.Builder builder)"
            ),
            migrated
        )
        assertEquals(
            Regex("""\bdx\(\)""").findAll(source).count(),
            Regex("""\bdx\(\)""").findAll(migrated).count()
        )
        assertEquals(
            Regex("""\bdy\(\)""").findAll(source).count(),
            Regex("""\bdy\(\)""").findAll(migrated).count()
        )
        assertEquals(
            Regex("""\bdz\(\)""").findAll(source).count(),
            Regex("""\bdz\(\)""").findAll(migrated).count()
        )
        assertEquals(migrated, pass.migrateCreateInteractionCompilerSurfaces(migrated))
    }

    @Test
    fun `potato and cardboard hooks preserve one source and the broader living attacker event`() {
        val potato = """
            package com.simibubi.create.content.equipment.potatoCannon;

            class PotatoProjectileEntity {
                void hit(Entity target, Entity owner, float damage) {
                    boolean onServer = !level().isClientSide;
                    if (onServer && !target.hurt(causePotatoDamage(), damage)) {
                        kill();
                        return;
                    }
                    if (!(target instanceof LivingEntity livingentity))
                        return;
                    if (onServer && owner instanceof LivingEntity) {
                        EnchantmentHelper.doPostHurtEffects(livingentity, owner);
                        EnchantmentHelper.doPostDamageEffects((LivingEntity) owner, livingentity);
                    }
                }
            }
        """.trimIndent()
        val cardboard = """
            package com.simibubi.create.content.equipment.tool;

            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.ai.attributes.Attributes;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

            class CardboardSwordItem {
                static void cardboardSwordsCannotHurtYou(LivingIncomingDamageEvent event) {
                    Entity attacker = event.getSource().getEntity();
                    LivingEntity target = event.getEntity();
                    if (!(attacker instanceof LivingEntity livingAttacker))
                        return;
                    event.setCanceled(true);
                    double knockbackStrength =
                        livingAttacker.getAttributeValue(Attributes.ATTACK_KNOCKBACK) + 2;
                    knockbackStrength += EnchantmentHelper.getKnockbackBonus(livingAttacker);
                    if (livingAttacker.isSprinting())
                        ++knockbackStrength;
                }
            }
        """.trimIndent()

        val migratedPotato = pass.migrateCreateInteractionCompilerSurfaces(potato)
        val migratedCardboard = pass.migrateCreateInteractionCompilerSurfaces(cardboard)
        val potatoCompact = compact(migratedPotato)
        val cardboardCompact = compact(migratedCardboard)

        assertEquals(1, Regex("""\bcausePotatoDamage\(\)""").findAll(migratedPotato).count())
        assertTrue(
            potatoCompact.contains(
                "DamageSource modporterDamageSource = onServer ? causePotatoDamage() : null;"
            ),
            migratedPotato
        )
        assertTrue(
            potatoCompact.contains(
                "target.hurt(modporterDamageSource,damage)"
            ),
            migratedPotato
        )
        assertTrue(
            potatoCompact.contains(
                "EnchantmentHelper.doPostAttackEffects(" +
                    "(net.minecraft.server.level.ServerLevel) level()," +
                    "livingentity,modporterDamageSource);"
            ),
            migratedPotato
        )
        assertFalse(migratedPotato.contains("doPostHurtEffects"), migratedPotato)
        assertFalse(migratedPotato.contains("doPostDamageEffects"), migratedPotato)

        assertTrue(
            cardboardCompact.contains(
                "livingAttacker.level() instanceof net.minecraft.server.level.ServerLevel " +
                    "modporterServerLevel"
            ),
            migratedCardboard
        )
        assertTrue(
            cardboardCompact.contains(
                "livingAttacker.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND)," +
                    "target,event.getSource(),(float) knockbackStrength"
            ),
            migratedCardboard
        )
        assertTrue(migratedCardboard.contains("LivingIncomingDamageEvent event"), migratedCardboard)
        assertTrue(migratedCardboard.contains("attacker instanceof LivingEntity"), migratedCardboard)
        assertTrue(migratedCardboard.contains("event.setCanceled(true)"), migratedCardboard)
        assertFalse(migratedCardboard.contains("getKnockbackBonus"), migratedCardboard)
        assertEquals(
            migratedPotato,
            pass.migrateCreateInteractionCompilerSurfaces(migratedPotato)
        )
        assertEquals(
            migratedCardboard,
            pass.migrateCreateInteractionCompilerSurfaces(migratedCardboard)
        )
    }

    @Test
    fun `mounted dispenser bucket pickup passes the nullable player slot without inventing a player`() {
        val source = """
            package com.simibubi.create;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.BucketPickup;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.Vec3;

            public class AllMountedDispenseItemBehaviors {
                private static final MountedDispenseBehavior BUCKET =
                    new DefaultMountedDispenseBehavior() {
                        protected ItemStack execute(
                            ItemStack stack,
                            MovementContext context,
                            BlockPos pos,
                            Vec3 facing
                        ) {
                            BlockPos interactionPos = pos.relative(
                                MountedDispenseBehavior.getClosestFacingDirection(facing));
                            BlockState state = context.world.getBlockState(interactionPos);
                            if (!(state.getBlock() instanceof BucketPickup bucketPickup)) {
                                return super.execute(stack, context, pos, facing);
                            }
                            ItemStack bucket = bucketPickup.pickupBlock(
                                context.world, interactionPos, state);
                            MountedDispenseBehavior.placeItemInInventory(
                                bucket, context, pos);
                            stack.shrink(1);
                            return stack;
                        }
                    };
            }
        """.trimIndent()

        val migrated = pass.migrateCreateInteractionCompilerSurfaces(source)

        assertTrue(
            compact(migrated).contains(
                "bucketPickup.pickupBlock(null,context.world,interactionPos,state)"
            ),
            migrated
        )
        assertFalse(migrated.contains("Player"), migrated)
        assertEquals(migrated, pass.migrateCreateInteractionCompilerSurfaces(migrated))

        val lookalike = source.replace(
            "package com.simibubi.create;",
            "package example;"
        )
        assertEquals(lookalike, pass.migrateCreateInteractionCompilerSurfaces(lookalike))
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

        assertTrue(
            compact(migrated).contains(
                "bucketPickup.pickupBlock(null,context.world,pos,state)"
            ),
            migrated
        )
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
            class Calls {
                void run(BlockState state, LiquidBlock fluid) {
                    state.neighborChanged(null, null, null, null, false);
                    fluid.getFluid();
                    int event = LevelEvent.PARTICLES_SHOOT;
                    new SmallFireball(null, 1, 2, 3, 4, 5, 6);
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

        assertEquals(lookalikes, pass.migrateCreateInteractionCompilerSurfaces(lookalikes))
        assertEquals(nonDominating, pass.migrateBucketPickupPickupBlockCalls(nonDominating))
    }
}
