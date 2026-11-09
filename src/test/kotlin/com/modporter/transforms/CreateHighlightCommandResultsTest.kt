package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCreateHighlightPrefixedCommandResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateHighlightCommandResultsTest {
    @Test
    fun `exact Create highlight dispatch keeps ordering and returns its closed branch result`() {
        val source = highlightSource(
            """"/create highlight " + p.getX() + " " + p.getY() + " " + p.getZ()"""
        )

        val pass = StructuralRefactorPass()
        val migrated = pass.migrateCreateHighlightPrefixedCommandResult(source)

        assertFalse(
            migrated.contains("return player.server.getCommands()"),
            migrated
        )
        val dispatch = migrated.indexOf(".performPrefixedCommand(source, command);")
        val result = migrated.indexOf("return Command.SINGLE_SUCCESS;", dispatch)
        assertTrue(dispatch >= 0, migrated)
        assertTrue(result > dispatch, migrated)
        assertEquals(migrated, pass.migrateCreateHighlightPrefixedCommandResult(migrated))
    }

    @Test
    fun `dynamic and unknown prefixed commands are not assigned a result`() {
        val dynamic = highlightSource(
            """commandPrefix + p.getX() + " " + p.getY() + " " + p.getZ()"""
        )
        val unknown = highlightSource(
            """"/create inspect " + p.getX() + " " + p.getY() + " " + p.getZ()"""
        )

        val pass = StructuralRefactorPass()
        assertEquals(dynamic, pass.migrateCreateHighlightPrefixedCommandResult(dynamic))
        assertEquals(unknown, pass.migrateCreateHighlightPrefixedCommandResult(unknown))
    }

    @Test
    fun `highlight branches without the proven single-success result remain unchanged`() {
        val source = highlightSource(
            """"/create highlight " + p.getX() + " " + p.getY() + " " + p.getZ()""",
            branchResult = "2"
        )

        assertEquals(
            source,
            StructuralRefactorPass().migrateCreateHighlightPrefixedCommandResult(source)
        )
    }

    private fun highlightSource(
        commandInitializer: String,
        branchResult: String = "Command.SINGLE_SUCCESS"
    ): String = """
        package com.simibubi.create.infrastructure.command;

        import com.mojang.brigadier.Command;
        import com.mojang.brigadier.builder.ArgumentBuilder;
        import net.minecraft.commands.CommandSourceStack;
        import net.minecraft.commands.Commands;
        import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
        import net.minecraft.core.BlockPos;
        import net.minecraft.server.level.ServerPlayer;
        import net.neoforged.neoforge.network.PacketDistributor;

        public class HighlightCommand {
            public static ArgumentBuilder<CommandSourceStack, ?> register() {
                return Commands.literal("highlight")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                            PacketDistributor.sendToPlayer(
                                (ServerPlayer) ctx.getSource().getEntity(),
                                new HighlightPacket(pos));
                            return $branchResult;
                        }))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        return highlightAssemblyExceptionFor(player, ctx.getSource());
                    });
            }

            private static int highlightAssemblyExceptionFor(
                ServerPlayer player, CommandSourceStack source
            ) {
                BlockPos p = exception.getPosition();
                String command = $commandInitializer;
                return player.server.getCommands()
                    .performPrefixedCommand(source, command);
            }
        }
    """.trimIndent()
}
