package com.modporter.transforms

import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertTrue

class StructuralRefactorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createTestFile(filename: String, content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve(filename)
        file.writeText(content)
        return tempDir
    }

    @Test
    fun `detects ICapabilityProvider implementation`() {
        val projectDir = createTestFile("MyBE.java", """
            package com.example;
            import net.minecraftforge.common.capabilities.ICapabilityProvider;
            public class MyBE extends BlockEntity implements ICapabilityProvider {
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    return super.getCapability(cap, side);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)

        assertTrue(result.changes.any { it.ruleId == "struct-capability-provider" })
        assertTrue(result.changes.any { it.ruleId == "struct-capability-getcap" })
    }

    @Test
    fun `detects SimpleChannel usage`() {
        val projectDir = createTestFile("MyNetwork.java", """
            package com.example;
            import net.minecraftforge.network.simple.SimpleChannel;
            public class MyNetwork {
                public static final SimpleChannel CHANNEL = null;
                public static void register() {
                    CHANNEL.registerMessage(0, MyPacket.class, MyPacket::encode, MyPacket::decode, MyPacket::handle);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)

        assertTrue(result.changes.any { it.ruleId == "struct-networking-channel" })
        assertTrue(result.changes.any { it.ruleId == "struct-networking-register" })
    }

    @Test
    fun `detects LazyOptional type usage`() {
        val projectDir = createTestFile("MyHandler.java", """
            package com.example;
            import net.minecraftforge.common.util.LazyOptional;
            public class MyHandler {
                private LazyOptional<IItemHandler> handler = LazyOptional.of(() -> inventory);
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)

        assertTrue(result.changes.any { it.ruleId == "struct-lazy-optional" })
    }

    @Test
    fun `loot table resource keys do not infer owners from java file names`() {
        val projectDir = createTestFile("LootTables.java", """
            package com.example;

            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.level.storage.loot.LootTable;

            public static ResourceKey<LootTable> USELESS;
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.resolve("Usage.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.level.storage.loot.LootTable;

            public class Usage {
                ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, LootTables.USELESS);
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(projectDir)
        val usage = srcDir.resolve("Usage.java").readText()

        assertTrue(usage.contains("ResourceKey.create(Registries.LOOT_TABLE, LootTables.USELESS)"), usage)
        assertTrue(result.changes.none { it.ruleId == "struct-loot-table-resourcekey-reference" }, result.changes.joinToString("\n"))
    }

    @Test
    fun `automated structural migrations report high confidence`() {
        val projectDir = createTestFile("ComplexMod.java", """
            package com.example;
            import net.minecraftforge.common.capabilities.ICapabilityProvider;
            import net.minecraftforge.common.util.LazyOptional;
            import net.minecraftforge.network.simple.SimpleChannel;
            public class ComplexMod implements ICapabilityProvider {
                private SimpleChannel channel;
                private LazyOptional<Object> cap;
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    return null;
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)

        assertTrue(result.changes.isNotEmpty())
        assertTrue(result.changes.all {
            it.confidence == Confidence.HIGH
        })
    }

    @Test
    fun `modern Java source syntax is parsed without skipped structural transforms`() {
        val projectDir = createTestFile("ModernSyntax.java", """
            package com.example;
            import java.util.List;

            public record ModernSyntax(int size) {
                public String describe(Object value) {
                    if (value instanceof String text) {
                        return text;
                    }
                    return switch (size) {
                        case 0 -> "empty";
                        case 1 -> {
                            yield List.of("one").get(0);
                        }
                        default -> "many";
                    };
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)

        assertTrue(result.skipped.isEmpty(), "Structural pass must parse modern Java source syntax: ${result.skipped}")
    }
}
