package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyComputerCraftPeripheralCapabilities
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LegacyComputerCraftPeripheralCapabilitiesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `legacy peripheral identity is exposed through canonical computercraft block capability`() {
        val compat = directory.resolve("src/main/java/com/modporter/generated/example/compat")
        compat.createDirectories()
        compat.resolve("LegacyCapabilityProvider.java").writeText(
            """
                package com.modporter.generated.example.compat;
                import net.minecraft.core.Direction;
                public interface LegacyCapabilityProvider {
                    <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side);
                }
            """.trimIndent()
        )
        val owner = directory.resolve("src/main/java/example/ComputerBehaviour.java")
        owner.parent.createDirectories()
        owner.writeText(
            """
                package example;
                import com.modporter.generated.example.compat.Capability;
                import com.modporter.generated.example.compat.CapabilityManager;
                import com.modporter.generated.example.compat.CapabilityToken;
                import dan200.computercraft.api.peripheral.IPeripheral;
                public class ComputerBehaviour {
                    protected static final Capability<IPeripheral> PERIPHERAL_CAPABILITY =
                        CapabilityManager.get(new CapabilityToken<>() {});
                    public <T> boolean isPeripheralCap(Capability<T> cap) {
                        return cap == PERIPHERAL_CAPABILITY;
                    }
                }
            """.trimIndent()
        )

        val result = LegacyComputerCraftPeripheralCapabilities.migrate(
            directory,
            "example",
            "com.modporter.generated.example.compat",
            false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == LegacyComputerCraftPeripheralCapabilities.RULE_ID })
        val migrated = owner.readText()
        assertTrue(migrated.contains("LegacyComputerCraftCapabilities.PERIPHERAL"), migrated)
        assertFalse(migrated.contains("CapabilityManager"), migrated)
        assertFalse(migrated.contains("CapabilityToken"), migrated)

        val bridge = compat.resolve("LegacyComputerCraftPeripheralCapabilities.java").readText()
        assertTrue(bridge.contains("PeripheralCapability.get()"), bridge)
        assertTrue(bridge.contains("ModList.get().isLoaded(\"computercraft\")"), bridge)
        assertTrue(bridge.contains("private static final class ComputerCraft"), bridge)
        assertFalse(bridge.contains("ResourceLocation.fromNamespaceAndPath"), bridge)

        owner.writeText(
            migrated.replace(
                "import com.modporter.generated.example.compat.Capability;",
                """
                import com.modporter.generated.example.compat.Capability;
                import com.modporter.generated.example.compat.CapabilityManager;
                import com.modporter.generated.example.compat.CapabilityToken;
                // CapabilityManager and CapabilityToken are historical documentation only.
                """.trimIndent()
            )
        )
        val cleanup = LegacyComputerCraftPeripheralCapabilities.migrate(
            directory,
            "example",
            "com.modporter.generated.example.compat",
            false
        )
        assertTrue(cleanup.gates.isEmpty(), cleanup.gates.joinToString("\n"))
        val cleaned = owner.readText()
        assertFalse(cleaned.contains("import com.modporter.generated.example.compat.CapabilityManager;"), cleaned)
        assertFalse(cleaned.contains("import com.modporter.generated.example.compat.CapabilityToken;"), cleaned)
        assertTrue(cleaned.contains("historical documentation only"), cleaned)

        val stable = LegacyComputerCraftPeripheralCapabilities.migrate(
            directory,
            "example",
            "com.modporter.generated.example.compat",
            false
        )
        assertTrue(stable.gates.isEmpty(), stable.gates.joinToString("\n"))
        assertTrue(stable.changes.isEmpty(), stable.changes.toString())
    }
}
