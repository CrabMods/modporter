package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.FinalCapabilityCompatMigration
import com.modporter.core.transforms.structural.LegacyBlockEntityCapabilityQueries
import com.modporter.core.transforms.structural.LegacyComputerCraftPeripheralCapabilities
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FinalCapabilityCompatMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `actual BuildSystem capability relocation admits final query and peripheral normalization`() {
        val leaf = directory.resolve("create")
        leaf.createDirectories()
        leaf.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev' version '2.0.140'
            }
            """.trimIndent()
        )
        val sourceRoot = leaf.resolve("src/main/java")
        fun java(path: String, source: String): Path {
            val file = sourceRoot.resolve("$path.java")
            file.parent.createDirectories()
            file.writeText(source.trimIndent() + "\n")
            return file
        }
        java(
            "example/ExampleMod",
            """
            package example;
            import net.neoforged.fml.common.Mod;
            @Mod("example")
            public class ExampleMod {
            }
            """
        )
        java(
            "example/BlockEntityGraph",
            """
            package example;
            import net.minecraft.world.level.block.entity.BlockEntity;
            abstract class SmartBlockEntity extends BlockEntity {
            }
            """
        )
        java(
            "com/modporter/generated/example/compat/LegacyCapabilityProvider",
            """
            package com.modporter.generated.example.compat;
            import net.minecraft.core.Direction;
            public interface LegacyCapabilityProvider {
                <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side);
            }
            """
        )
        java(
            "com/modporter/generated/example/compat/LegacyCapabilities",
            """
            package com.modporter.generated.example.compat;
            public final class LegacyCapabilities {
                public static final Capability<Object> FLUID_HANDLER = new Capability<>();
                private LegacyCapabilities() {
                }
            }
            """
        )
        val retained = java(
            "example/RetainedProvider",
            """
            package example;
            import com.modporter.generated.example.compat.LazyOptional;
            import com.modporter.generated.example.compat.LegacyCapabilityProvider;
            import net.minecraft.core.Direction;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.capabilities.Capability;
            class RetainedProvider extends SmartBlockEntity implements LegacyCapabilityProvider {
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    return LazyOptional.empty();
                }
                boolean tooltip() {
                    return getCapability(Capabilities.FluidHandler.BLOCK).isPresent();
                }
            }
            """
        )
        val computer = java(
            "example/ComputerBehaviour",
            """
            package example;
            import com.modporter.generated.example.compat.CapabilityManager;
            import com.modporter.generated.example.compat.CapabilityToken;
            import dan200.computercraft.api.peripheral.IPeripheral;
            import net.neoforged.neoforge.capabilities.Capability;
            public class ComputerBehaviour {
                protected static final Capability<IPeripheral> PERIPHERAL_CAPABILITY =
                    CapabilityManager.get(new CapabilityToken<>() {});
                public <T> boolean isPeripheralCap(Capability<T> cap) {
                    return cap == PERIPHERAL_CAPABILITY;
                }
            }
            """
        )

        val earlyQuery = LegacyBlockEntityCapabilityQueries.migrate(
            leaf,
            { "com.modporter.generated.example.compat" },
            false
        )
        assertTrue(earlyQuery.gates.isEmpty(), earlyQuery.gates.joinToString("\n"))
        assertTrue(earlyQuery.changes.isEmpty(), earlyQuery.changes.toString())
        assertTrue(retained.readText().contains("getCapability(Capabilities.FluidHandler.BLOCK)"))

        val earlyPeripheral = LegacyComputerCraftPeripheralCapabilities.migrate(
            leaf,
            "example",
            "com.modporter.generated.example.compat",
            false
        )
        assertTrue(earlyPeripheral.gates.isEmpty(), earlyPeripheral.gates.joinToString("\n"))
        assertTrue(computer.readText().contains("LegacyComputerCraftCapabilities.PERIPHERAL"))

        val build = BuildSystemPass().apply(directory)
        assertTrue(build.errors.isEmpty(), build.errors.joinToString("\n"))
        assertTrue(build.changes.any { it.ruleId == "build-relocate-capability-import" })
        assertTrue(
            retained.readText().contains(
                "import com.modporter.generated.example.compat.Capability;"
            ),
            retained.readText()
        )

        val final = FinalCapabilityCompatMigration.migrate(directory, false)
        assertTrue(final.gates.isEmpty(), final.gates.joinToString("\n"))
        assertTrue(
            retained.readText().contains(
                "getCapability(LegacyCapabilities.FLUID_HANDLER, null).isPresent()"
            ),
            retained.readText()
        )
        val computerSource = computer.readText()
        assertTrue(computerSource.contains("LegacyComputerCraftCapabilities.PERIPHERAL"), computerSource)
        assertFalse(computerSource.contains("CapabilityManager"), computerSource)
        assertFalse(computerSource.contains("CapabilityToken"), computerSource)

        val stable = FinalCapabilityCompatMigration.migrate(directory, false)
        assertTrue(stable.gates.isEmpty(), stable.gates.joinToString("\n"))
        assertTrue(stable.changes.isEmpty(), stable.changes.toString())
    }
    @Test
    fun `project without an owned capability family skips identity requirements`() {
        val source = directory.resolve("src/main/java/example/Plain.java")
        source.parent.createDirectories()
        source.writeText(
            """
            package example;
            class Plain {
            }
            """.trimIndent()
        )

        val result = FinalCapabilityCompatMigration.migrate(directory, false)

        assertTrue(result.changes.isEmpty(), result.changes.toString())
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
    }
}
