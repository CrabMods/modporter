package com.modporter.core.transforms.structural

import com.github.javaparser.StaticJavaParser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FinalBlockSourceAdaptersTest {
    @TempDir
    lateinit var project: Path

    @Test
    fun `closed implementation becomes a fresh target record at the native boundary`() {
        writeFixture()

        val changes = FinalBlockSourceAdapters.migrate(project, dryRun = false)

        assertEquals(2, changes.size)
        val wrapper = read("src/main/java/sample/MobileDispenseOrigin.java")
        val consumer = read("src/main/java/sample/UseOrigin.java")
        val accessor = StaticJavaParser.parse(wrapper)
            .getClassByName("MobileDispenseOrigin")
            .orElseThrow()
            .getMethodsByName("modporter\$asBlockSource")
            .single()
        val nativeRecord = accessor.body.orElseThrow()
            .statements.single()
            .asReturnStmt()
            .expression.orElseThrow()
            .asObjectCreationExpr()

        assertFalse(
            wrapper.contains("implements BlockSource"),
            wrapper
        )
        assertFalse(wrapper.contains("@Override"), wrapper)
        assertTrue(
            wrapper.contains(
                "return (double)this.anchor.getX() + 0.5D;"
            ),
            wrapper
        )
        assertTrue(
            wrapper.contains(
                "return (double)this.anchor.getY() + 0.5D;"
            ),
            wrapper
        )
        assertTrue(
            wrapper.contains(
                "return (double)this.anchor.getZ() + 0.5D;"
            ),
            wrapper
        )
        assertTrue(
            wrapper.contains(
                "this.carrier.state.setValue(FACING, this.direction)"
            ),
            wrapper
        )
        assertTrue(accessor.isPublic, accessor.toString())
        assertEquals(
            "net.minecraft.core.dispenser.BlockSource",
            accessor.type.asString()
        )
        assertEquals(
            "net.minecraft.core.dispenser.BlockSource",
            nativeRecord.type.asString()
        )
        assertEquals(
            listOf(
                "this.getLevel()",
                "this.getPos()",
                "this.getBlockState()",
                "null"
            ),
            nativeRecord.arguments.map { it.toString() }
        )
        assertTrue(
            Regex(
                """public <T extends BlockEntity> T getEntity\(\)\s*\{\s*""" +
                    """return null;\s*\}"""
            ).containsMatchIn(wrapper),
            wrapper
        )
        assertFalse(
            Regex(
                """(?:private|protected|public)\s+""" +
                    """net\.minecraft\.core\.dispenser\.BlockSource\s+""" +
                    """[A-Za-z_${'$'}][\w${'$'}]*\s*(?:=|;)"""
            ).containsMatchIn(wrapper),
            wrapper
        )
        assertTrue(
            Regex(
                """behavior\.dispense\(\s*source""" +
                    """\.modporter\${'$'}asBlockSource\(\),\s*stack\.copy\(\)\)"""
            ).containsMatchIn(consumer),
            consumer
        )
        assertTrue(
            consumer.contains("MobileDispenseOrigin source"),
            consumer
        )
        assertFalse(
            consumer.contains("BlockSource source"),
            consumer
        )
        assertTrue(
            consumer.indexOf("try {") <
                consumer.indexOf("source.modporter\$asBlockSource()"),
            consumer
        )
    }

    @Test
    fun `legacy interface import is normalized only in the admitted family`() {
        writeFixture(useLegacyImport = true)
        write(
            "src/main/java/unrelated/OtherDispenser.java",
            """
            package unrelated;

            import net.minecraft.core.BlockSource;

            public final class OtherDispenser {
                BlockSource source;
            }
            """.trimIndent()
        )

        val changes = FinalBlockSourceAdapters.migrate(project, dryRun = false)

        assertEquals(2, changes.size)
        val wrapper = read("src/main/java/sample/MobileDispenseOrigin.java")
        val consumer = read("src/main/java/sample/UseOrigin.java")
        val unrelated = read("src/main/java/unrelated/OtherDispenser.java")
        assertFalse(wrapper.contains("net.minecraft.core.BlockSource"), wrapper)
        assertFalse(consumer.contains("net.minecraft.core.BlockSource"), consumer)
        assertTrue(
            unrelated.contains("import net.minecraft.core.BlockSource;"),
            unrelated
        )
    }

    @Test
    fun `migration is idempotent once the interface edge is removed`() {
        writeFixture()

        FinalBlockSourceAdapters.migrate(project, dryRun = false)
        val wrapper = read("src/main/java/sample/MobileDispenseOrigin.java")
        val consumer = read("src/main/java/sample/UseOrigin.java")
        val second = FinalBlockSourceAdapters.migrate(project, dryRun = false)

        assertTrue(second.isEmpty())
        assertEquals(
            wrapper,
            read("src/main/java/sample/MobileDispenseOrigin.java")
        )
        assertEquals(
            consumer,
            read("src/main/java/sample/UseOrigin.java")
        )
    }

    @Test
    fun `non centered coordinates fail before any source is written`() {
        val source = wrapperSource().replace(
            "return (double)this.anchor.getX() + 0.5D;",
            "return (double)this.anchor.getX() + 1.0D;"
        )
        write("src/main/java/sample/MobileDispenseOrigin.java", source)
        writeConsumer()

        val error = assertFailsWith<IllegalStateException> {
            FinalBlockSourceAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("exactly one 0.5 literal"))
        assertEquals(
            source,
            read("src/main/java/sample/MobileDispenseOrigin.java")
        )
    }

    @Test
    fun `non null source entity semantics fail closed`() {
        val original = wrapperSource()
        val source = original.replaceFirst(
            "return null;",
            "return this.carrier.entity();"
        )
        assertNotEquals(original, source)
        write("src/main/java/sample/MobileDispenseOrigin.java", source)
        writeConsumer()

        val error = assertFailsWith<IllegalStateException> {
            FinalBlockSourceAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("return null unconditionally"))
        assertEquals(
            source,
            read("src/main/java/sample/MobileDispenseOrigin.java")
        )
    }

    @Test
    fun `unassociated wrapper argument fails closed`() {
        writeFixture()
        val unsafe = """
            package sample;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.core.dispenser.BlockSource;

            public final class UnsafeArgument {
                void accept(BlockSource source) {
                }

                void submit(
                        Carrier carrier,
                        BlockPos pos,
                        Direction facing) {
                    accept(new MobileDispenseOrigin(carrier, pos, facing));
                }
            }
        """.trimIndent()
        write("src/main/java/sample/UnsafeArgument.java", unsafe)

        val error = assertFailsWith<IllegalStateException> {
            FinalBlockSourceAdapters.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "no declaration-proven target BlockSource parameter"
            )
        )
        assertEquals(unsafe, read("src/main/java/sample/UnsafeArgument.java"))
    }

    @Test
    fun `wrapper returning factory fails the closed type boundary`() {
        writeFixture()
        val factory = """
            package sample;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;

            public final class UnsafeFactory {
                MobileDispenseOrigin create(
                        Carrier carrier,
                        BlockPos pos,
                        Direction facing) {
                    return new MobileDispenseOrigin(carrier, pos, facing);
                }
            }
        """.trimIndent()
        write("src/main/java/sample/UnsafeFactory.java", factory)

        val error = assertFailsWith<IllegalStateException> {
            FinalBlockSourceAdapters.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "escapes the closed construction boundary"
            )
        )
        assertEquals(factory, read("src/main/java/sample/UnsafeFactory.java"))
    }

    @Test
    fun `native local with repeated uses is not guessed into snapshots`() {
        writeFixture()
        val consumer = read("src/main/java/sample/UseOrigin.java")
        val repeated = consumer.replace(
            "return behavior.dispense(source, stack.copy());",
            """
            behavior.dispense(source, stack.copy());
                        return behavior.dispense(source, stack.copy());
            """.trimIndent()
        )
        assertNotEquals(consumer, repeated)
        write("src/main/java/sample/UseOrigin.java", repeated)

        val error = assertFailsWith<IllegalStateException> {
            FinalBlockSourceAdapters.migrate(project, dryRun = false)
        }

        assertTrue(
            error.message.orEmpty().contains(
                "must have one closed native use, found 2"
            )
        )
        assertEquals(repeated, read("src/main/java/sample/UseOrigin.java"))
    }

    @Test
    fun `source owned child prevents closing the wrapper family`() {
        writeFixture()
        val child = """
            package sample;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;

            public final class ChildOrigin extends MobileDispenseOrigin {
                ChildOrigin(Carrier carrier, BlockPos pos, Direction facing) {
                    super(carrier, pos, facing);
                }
            }
        """.trimIndent()
        write("src/main/java/sample/ChildOrigin.java", child)

        val error = assertFailsWith<IllegalStateException> {
            FinalBlockSourceAdapters.migrate(project, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("while source"))
        assertEquals(child, read("src/main/java/sample/ChildOrigin.java"))
    }

    private fun writeFixture(useLegacyImport: Boolean = false) {
        write(
            "src/main/java/sample/MobileDispenseOrigin.java",
            wrapperSource(useLegacyImport)
        )
        writeConsumer(useLegacyImport)
    }

    private fun writeConsumer(useLegacyImport: Boolean = false) {
        val blockSource = if (useLegacyImport) {
            "net.minecraft.core.BlockSource"
        } else {
            "net.minecraft.core.dispenser.BlockSource"
        }
        write(
            "src/main/java/sample/UseOrigin.java",
            """
            package sample;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import $blockSource;
            import net.minecraft.core.dispenser.DispenseItemBehavior;
            import net.minecraft.world.item.ItemStack;

            public final class UseOrigin {
                ItemStack execute(
                        DispenseItemBehavior behavior,
                        ItemStack stack,
                        Carrier carrier,
                        BlockPos pos,
                        Direction facing) {
                    BlockSource source =
                        new MobileDispenseOrigin(carrier, pos, facing);
                    try {
                        return behavior.dispense(source, stack.copy());
                    } catch (NullPointerException expected) {
                        return stack;
                    }
                }
            }
            """.trimIndent()
        )
    }

    private fun wrapperSource(useLegacyImport: Boolean = false): String {
        val blockSource = if (useLegacyImport) {
            "net.minecraft.core.BlockSource"
        } else {
            "net.minecraft.core.dispenser.BlockSource"
        }
        return """
            package sample;

            import javax.annotation.Nullable;
            import net.minecraft.core.BlockPos;
            import $blockSource;
            import net.minecraft.core.Direction;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class MobileDispenseOrigin implements BlockSource {
                private static final Object FACING = new Object();
                private final BlockPos anchor;
                private final Carrier carrier;
                private final Direction direction;

                public MobileDispenseOrigin(Carrier carrier, BlockPos anchor) {
                    this(carrier, anchor, null);
                }

                public MobileDispenseOrigin(
                        Carrier carrier,
                        BlockPos anchor,
                        @Nullable Direction direction) {
                    this.anchor = anchor;
                    this.carrier = carrier;
                    this.direction = direction;
                }

                @Override
                public double x() {
                    return (double)this.anchor.getX() + 0.5D;
                }

                @Override
                public double y() {
                    return (double)this.anchor.getY() + 0.5D;
                }

                @Override
                public double z() {
                    return (double)this.anchor.getZ() + 0.5D;
                }

                @Override
                public BlockPos getPos() {
                    return this.anchor;
                }

                @Override
                public BlockState getBlockState() {
                    if (this.direction != null)
                        return this.carrier.state.setValue(FACING, this.direction);
                    return this.carrier.state;
                }

                @Override
                @Nullable
                public <T extends BlockEntity> T getEntity() {
                    return null;
                }

                @Override
                @Nullable
                public ServerLevel getLevel() {
                    return this.carrier.level();
                }
            }
        """.trimIndent()
    }

    private fun write(relative: String, source: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(source)
    }

    private fun read(relative: String): String =
        project.resolve(relative).readText()
}
