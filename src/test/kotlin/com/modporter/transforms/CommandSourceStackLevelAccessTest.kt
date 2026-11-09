package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCommandSourceStackLevelAccess
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandSourceStackLevelAccessTest {
    @Test
    fun `package private command registration methods migrate declared command sources`() {
        val source = """
            package example;

            import net.minecraft.commands.CommandSourceStack;

            public class DumpCommand {
                static ArgumentBuilder<CommandSourceStack, ?> register() {
                    return Commands.literal("dump").executes(context -> {
                        CommandSourceStack source = context.getSource();
                        fillReport(source.level(), source.getPosition());
                        return 1;
                    });
                }

                private static final String DOC = "source.level()";
            }
        """.trimIndent()

        val migrated = with(StructuralRefactorPass()) {
            migrateCommandSourceStackLevelAccess(source)
        }

        assertTrue(migrated.contains("fillReport(source.getLevel(), source.getPosition());"), migrated)
        assertTrue(migrated.contains("""private static final String DOC = "source.level()";"""), migrated)
        assertFalse(migrated.contains("fillReport(source.level(),"), migrated)
    }
}
