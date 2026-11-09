package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.modporter.core.transforms.structural.LegacyBlockEntityCapabilities
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyBlockEntityCapabilitiesCommentedHeaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `comment-split Create chute header retains the runtime capability provider interface`() {
        val sourceRoot = tempDir.resolve("src/main/java/com/example")
        sourceRoot.createDirectories()
        sourceRoot.resolve("ChuteBlockEntity.java").writeText(
            """
            package com.example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.items.IItemHandler;

            public class // , IAirCurrentSource {
            ChuteBlockEntity extends // , IAirCurrentSource {
            BlockEntity implements // , IAirCurrentSource {
            IHaveGoggleInformation {
                private final LazyOptional<IItemHandler> lazyHandler = LazyOptional.empty();

                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    if (cap == Capabilities.ItemHandler.BLOCK)
                        return lazyHandler.cast();
                    return super.getCapability(cap, side);
                }
            }

            interface IHaveGoggleInformation {
            }
            """.trimIndent()
        )

        val result = LegacyBlockEntityCapabilities.migrate(
            tempDir,
            "example",
            { "com.modporter.generated.example.compat" },
            dryRun = false
        )
        val migrated = sourceRoot.resolve("ChuteBlockEntity.java").readText()
        val unit = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        ).parse(migrated).result.orElseThrow()
        val chute = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .single { it.nameAsString == "ChuteBlockEntity" }

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(
            listOf("IHaveGoggleInformation", "LegacyCapabilityProvider"),
            chute.implementedTypes.map { it.nameAsString }
        )
        assertTrue(migrated.contains("// , IAirCurrentSource {"), migrated)
        assertTrue(migrated.contains("return lazyHandler.cast();"), migrated)
        assertTrue(migrated.contains("return LazyOptional.empty();"), migrated)
        assertTrue(
            migrated.contains("import com.modporter.generated.example.compat.LegacyCapabilityProvider;"),
            migrated
        )
    }
}
