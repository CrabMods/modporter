package com.modporter.transforms

import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyDeployerInteractionHooks
import com.modporter.core.transforms.structural.migrateLegacyEntityInteractionHooks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyInteractionHookTransitionsTest {
    @Test
    fun `non player incoming damage preserves environmental cancellation without attack callbacks`() {
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts(
            parents = mapOf(
                "example.ParcelEntity" to "net.minecraft.world.entity.LivingEntity"
            ),
            types = setOf("example.ParcelEntity")
        )
        val source = """
            package example;

            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.damagesource.DamageSource;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.common.CommonHooks;

            abstract class ParcelEntity extends LivingEntity {
                ItemStack box;

                @Override
                public boolean hurt(DamageSource source, float amount) {
                    if (!CommonHooks.onLivingAttack(this, source, amount))
                        return false;
                    recordDamage(source, amount);
                    if (!box.getItem().canBeHurtBy(source))
                        return false;
                    destroy(source);
                    return true;
                }

                private void destroy(DamageSource source) {
                    notifyTracking();
                    this.dropAllDeathLoot(source);
                }

                @Override
                protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
                    super.dropAllDeathLoot(level, source);
                    dropContents(level);
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyEntityInteractionHooks(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertFalse(migrated.contains("onLivingAttack"), migrated)
        assertTrue(
            compact.contains(
                "net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(" +
                    "newnet.neoforged.neoforge.event.entity.living." +
                    "LivingIncomingDamageEvent(" +
                    "this,newnet.neoforged.neoforge.common.damagesource." +
                    "DamageContainer(source,amount))).isCanceled()"
            ),
            migrated
        )
        assertFalse(migrated.contains("source.getEntity()"), migrated)
        assertFalse(migrated.contains("onPlayerAttackTarget"), migrated)
        assertFalse(migrated.contains("AttackEntityEvent"), migrated)
        assertFalse(migrated.contains("onLeftClickEntity"), migrated)
        assertFalse(migrated.contains("getNewDamage"), migrated)
        assertFalse(migrated.contains("setAmount"), migrated)
        assertEquals(
            1,
            Regex("""LivingIncomingDamageEvent""").findAll(migrated).count(),
            migrated
        )
        assertTrue(migrated.contains("recordDamage(source, amount);"), migrated)
        assertTrue(
            migrated.indexOf("LivingIncomingDamageEvent") <
                migrated.indexOf("recordDamage(source, amount);"),
            migrated
        )
        assertTrue(migrated.contains("box.getItem().canBeHurtBy(box, source)"), migrated)
        assertTrue(
            compact.contains(
                "if(level()instanceofnet.minecraft.server.level.ServerLevel)" +
                    "this.dropAllDeathLoot(" +
                    "(net.minecraft.server.level.ServerLevel)level(),source);"
            ),
            migrated
        )
        assertTrue(migrated.indexOf("notifyTracking();") < migrated.indexOf("dropAllDeathLoot"))
        assertEquals(migrated, pass.migrateLegacyEntityInteractionHooks(migrated))
    }

    @Test
    fun `incoming damage dispatch rejects player and unresolved source owners`() {
        val playerPass = StructuralRefactorPass()
        playerPass.projectValueFacts = ProjectValueFacts(
            parents = mapOf(
                "example.PlayerParcel" to "net.minecraft.world.entity.player.Player"
            ),
            types = setOf("example.PlayerParcel")
        )
        val playerSource = """
            package example;
            import net.minecraft.world.damagesource.DamageSource;
            import net.minecraft.world.entity.player.Player;
            import net.neoforged.neoforge.common.CommonHooks;
            abstract class PlayerParcel extends Player {
                public boolean hurt(DamageSource source, float amount) {
                    if (!CommonHooks.onLivingAttack(this, source, amount))
                        return false;
                    return continueDamage(source, amount);
                }
            }
        """.trimIndent()

        assertEquals(
            playerSource,
            playerPass.migrateLegacyEntityInteractionHooks(playerSource)
        )

        val unresolvedPass = StructuralRefactorPass()
        unresolvedPass.projectValueFacts = ProjectValueFacts(
            types = setOf("example.UnresolvedParcel")
        )
        val unresolvedSource = """
            package example;
            import net.minecraft.world.damagesource.DamageSource;
            import net.neoforged.neoforge.common.CommonHooks;
            abstract class UnresolvedParcel extends MissingLivingBase {
                public boolean hurt(DamageSource source, float amount) {
                    if (!CommonHooks.onLivingAttack(this, source, amount))
                        return false;
                    return continueDamage(source, amount);
                }
            }
        """.trimIndent()

        assertEquals(
            unresolvedSource,
            unresolvedPass.migrateLegacyEntityInteractionHooks(unresolvedSource)
        )
    }

    @Test
    fun `source owned attack hook is rejected`() {
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts(
            parents = mapOf(
                "example.ParcelEntity" to "net.minecraft.world.entity.LivingEntity"
            ),
            types = setOf(
                "example.CommonHooks",
                "example.ParcelEntity"
            )
        )
        val source = """
            package example;
            import net.minecraft.world.damagesource.DamageSource;
            import net.minecraft.world.entity.LivingEntity;
            class CommonHooks {
                static boolean onLivingAttack(
                    LivingEntity entity,
                    DamageSource source,
                    float amount
                ) {
                    return audit(entity, source, amount);
                }
            }
            abstract class ParcelEntity extends LivingEntity {
                public boolean hurt(DamageSource source, float amount) {
                    if (!CommonHooks.onLivingAttack(this, source, amount))
                        return false;
                    return true;
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyEntityInteractionHooks(source))
    }

    @Test
    fun `deployer hooks retain left click action and break cancellation while removed item hook stays visible`() {
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts(
            parents = mapOf(
                "example.DeployerFakePlayer" to
                    "net.minecraft.server.level.ServerPlayer"
            ),
            types = setOf("example.DeployerFakePlayer")
        )
        val source = """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.server.level.ServerPlayerGameMode;
            import net.minecraft.world.level.GameType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.common.CommonHooks;
            import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;

            abstract class DeployerFakePlayer extends ServerPlayer {}

            class DeployerHandler {
                static void punch(
                    DeployerFakePlayer player,
                    BlockPos clickedPos,
                    Direction face
                ) {
                    LeftClickBlock event =
                        CommonHooks.onLeftClickBlock(player, clickedPos, face);
                    if (event.isCanceled())
                        return;
                    attackBlock();
                }

                static boolean harvest(
                    ServerPlayer player,
                    ServerPlayerGameMode interactionManager,
                    BlockPos pos
                ) {
                    ServerLevel world = player.serverLevel();
                    BlockState blockstate = world.getBlockState(pos);
                    GameType gameType = interactionManager.getGameModeForPlayer();
                    if (CommonHooks.onBlockBreakEvent(
                            world, gameType, player, pos
                        ) == -1)
                        return false;
                    if (player.getMainHandItem().onBlockStartBreak(pos, player))
                        return false;
                    breakBlock(blockstate);
                    return true;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyDeployerInteractionHooks(source)
        val compact = migrated.replace(Regex("""\s+"""), "")

        assertTrue(
            compact.contains(
                "CommonHooks.onLeftClickBlock(player,clickedPos,face," +
                    "net.minecraft.network.protocol.game." +
                    "ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "CommonHooks.fireBlockBreak(" +
                    "world,gameType,player,pos,blockstate).isCanceled()"
            ),
            migrated
        )
        assertFalse(migrated.contains("onBlockBreakEvent"), migrated)
        assertTrue(
            migrated.contains(
                "player.getMainHandItem().onBlockStartBreak(pos, player)"
            ),
            migrated
        )
        assertTrue(
            migrated.indexOf("fireBlockBreak") <
                migrated.indexOf("onBlockStartBreak")
        )
        assertEquals(migrated, pass.migrateLegacyDeployerInteractionHooks(migrated))
    }

    @Test
    fun `block break hook without matching state value is rejected`() {
        val pass = StructuralRefactorPass()
        val source = """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.level.GameType;
            import net.neoforged.neoforge.common.CommonHooks;
            class UnsafeBreak {
                static boolean harvest(
                    ServerLevel world,
                    GameType gameType,
                    ServerPlayer player,
                    BlockPos pos
                ) {
                    return CommonHooks.onBlockBreakEvent(
                        world, gameType, player, pos
                    ) != -1;
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyDeployerInteractionHooks(source))
    }

    @Test
    fun `living change target getter follows the target event contract`() {
        val pass = StructuralRefactorPass()
        val source = """
            package example;
            import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
            class AggroEvents {
                static boolean targetsDeployer(LivingChangeTargetEvent event) {
                    return event.getOriginalTarget() instanceof DeployerFakePlayer;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyDeployerInteractionHooks(source)

        assertTrue(
            migrated.contains("event.getOriginalAboutToBeSetTarget()"),
            migrated
        )
        assertFalse(migrated.contains("getOriginalTarget"), migrated)
        assertEquals(migrated, pass.migrateLegacyDeployerInteractionHooks(migrated))
    }

    @Test
    fun `source owned target event getter is rejected`() {
        val pass = StructuralRefactorPass()
        val source = """
            package example;
            class LivingChangeTargetEvent {
                Object getOriginalTarget() {
                    return target;
                }
            }
            class AggroEvents {
                static boolean targetsDeployer(LivingChangeTargetEvent event) {
                    return event.getOriginalTarget() instanceof DeployerFakePlayer;
                }
            }
        """.trimIndent()

        assertEquals(source, pass.migrateLegacyDeployerInteractionHooks(source))
    }
}
