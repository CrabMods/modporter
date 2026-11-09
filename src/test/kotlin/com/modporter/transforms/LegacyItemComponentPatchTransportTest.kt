package com.modporter.transforms

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.LegacyItemComponentPatchTransport
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LegacyItemComponentPatchTransportTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `closed arbitrary-name family transports full patches and preserves opaque bytes`() {
        val project = directory.resolve("positive")
        val fixture = writeFixture(project)

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)
        val entity = fixture.entity.readText()
        val block = fixture.block.readText()

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)
        assertTrue(entity.contains("DataComponentPatch.CODEC.encodeStart("), entity)
        assertTrue(entity.contains("artifact.getComponentsPatch()").not(), entity)
        assertTrue(entity.contains("stack.getComponentsPatch()"), entity)
        assertTrue(entity.contains(").getOrThrow();"), entity)
        assertTrue(entity.contains("DataComponentPatch.CODEC.parse("), entity)
        assertTrue(entity.contains(").result();"), entity)
        assertFalse(entity.contains("DataComponentPatch.EMPTY"), entity)
        assertFalse(entity.contains("orElse(") && entity.contains("DataComponentPatch"), entity)

        assertTrue(entity.contains("private net.minecraft.nbt.Tag modporterComponentPatch;"), entity)
        assertTrue(entity.contains("private int modporterComponentPatchVersion;"), entity)
        assertTrue(entity.contains(
            "private net.minecraft.nbt.CompoundTag modporterOpaqueComponentState;"
        ), entity)
        assertTrue(entity.contains("\"OpaqueBlob\""), entity)
        assertTrue(entity.contains("root.put(\"OpaqueBlob\", opaquePayload);"), entity)
        assertTrue(entity.contains("\"__ModporterComponentPatch\""), entity)
        assertTrue(entity.contains("root.get(MODPORTER_COMPONENT_PATCH_KEY)"), entity)
        assertTrue(entity.contains("raw.copy()"), entity)
        assertTrue(entity.contains("version != 1"), entity)
        assertTrue(entity.contains("decoded.isEmpty()"), entity)
        assertTrue(entity.contains("DisplacedValue"), entity)
        assertTrue(entity.contains("ComposedOpaqueState"), entity)
        assertTrue(entity.contains("mergeModporterOpaqueComponents"), entity)
        assertTrue(entity.contains(
            "transport.remove(MODPORTER_COMPONENT_DISPLACED_KEY)"
        ), entity)
        assertTrue(entity.contains(
            "root.put(MODPORTER_COMPONENT_CARRIER_KEY, displaced.copy())"
        ), entity)
        assertTrue(entity.contains("LegacyForgeCaps"), entity)
        assertTrue(entity.contains("\"__ModporterOpaqueComponentState\""), entity)
        assertTrue(entity.contains("detachModporterOpaqueCustomData"), entity)

        assertFalse(block.contains("serializeNBT()"), block)
        assertFalse(block.contains("\"ForgeCaps\""), block)
        assertFalse(block.contains("new ItemStack(entry.getItem(), entry.getCount(), hidden.copy())"), block)
        assertFalse(block.contains("new ItemStack(product, 1, hidden)"), block)
        assertTrue(block.contains(
            "ItemStack componentSource = artifact.copy()"
        ), block)
        assertTrue(block.contains(
            "encodeModporterComponentPatch(componentSource, habitat.registryAccess())"
        ), block)
        assertTrue(block.contains(
            "vessel.acceptPayload(visible, null, 1, componentPatch, opaqueComponentState);"
        ), block)
        assertTrue(
            block.windowed("mergeModporterOpaqueComponents".length)
                .count { it == "mergeModporterOpaqueComponents" } >= 4,
            block
        )
        assertTrue(
            Regex(
                """getCloneItemStack\(\s*LevelReader view,\s*BlockPos position,\s*BlockState state\s*\)"""
            ).containsMatchIn(block),
            block
        )
        val cloneDelegate = StaticJavaParser.parse(block)
            .findAll(MethodDeclaration::class.java)
            .single {
                it.nameAsString == "getCloneItemStack" &&
                    it.parameters.size == 5
            }
        val cloneDelegateCall = cloneDelegate.findAll(MethodCallExpr::class.java)
            .single {
                it.nameAsString == "getCloneItemStack" &&
                    it.arguments.size == 3
            }
        assertEquals(
            cloneDelegate.parameters[2].nameAsString,
            cloneDelegateCall.arguments[0].toString()
        )
        assertEquals(
            cloneDelegate.parameters[3].nameAsString,
            cloneDelegateCall.arguments[1].toString()
        )
        assertEquals(
            cloneDelegate.parameters[0].nameAsString,
            cloneDelegateCall.arguments[2].toString()
        )

        val dropStored = block.indexOf("applyModporterComponentPatch(")
        val dropSanitize = block.indexOf("detachModporterOpaqueComponents(cleanLootOverlay)")
        val dropOverlay = block.indexOf("restored.applyComponents(cleanLootOverlay.getComponentsPatch())")
        val dropOpaque = block.indexOf("attachModporterOpaqueComponents(", dropOverlay)
        assertTrue(
            dropStored >= 0 &&
                dropStored < dropSanitize &&
                dropSanitize < dropOverlay &&
                dropOverlay < dropOpaque,
            block
        )

        val cloneStart = block.indexOf("getCloneItemStack(")
        val cloneStored = block.indexOf("applyModporterComponentPatch(", cloneStart)
        val cloneSanitize = block.indexOf("detachModporterOpaqueCustomData(visible)", cloneStart)
        val cloneCustom = block.indexOf("custom.merge(visible.copy())", cloneStart)
        val cloneOpaque = block.indexOf("attachModporterOpaqueComponents(", cloneStart)
        val cloneTerminal = block.indexOf("\"PressureUnits\"", cloneStart)
        assertTrue(
            cloneStart >= 0 &&
                cloneSanitize < cloneStored &&
                cloneStored < cloneCustom &&
                cloneCustom < cloneOpaque &&
                cloneOpaque < cloneTerminal,
            block
        )

        val onceEntity = entity
        val onceBlock = block
        val second = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)
        assertTrue(second.changes.isEmpty())
        assertTrue(second.gates.isEmpty())
        assertEquals(onceEntity, fixture.entity.readText())
        assertEquals(onceBlock, fixture.block.readText())
    }

    @Test
    fun `dry run reports both files without changing either byte`() {
        val project = directory.resolve("dry-run")
        val fixture = writeFixture(project)
        val entity = fixture.entity.readText()
        val block = fixture.block.readText()

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = true)

        assertEquals(2, result.changes.size)
        assertTrue(result.gates.isEmpty())
        assertEquals(entity, fixture.entity.readText())
        assertEquals(block, fixture.block.readText())
    }

    @Test
    fun `every source read is attempted and one read failure gates the family`() {
        val project = directory.resolve("read-failure")
        val fixture = writeFixture(project)
        val entity = Files.readAllBytes(fixture.entity)
        val block = Files.readAllBytes(fixture.block)
        val attempts = mutableListOf<Path>()

        val result = LegacyItemComponentPatchTransport.migrateForTest(
            project,
            dryRun = false,
            hooks = LegacyItemComponentPatchTransport.IoHooks(
                readBytes = { path ->
                    attempts.add(path)
                    if (path == fixture.block) throw IOException("injected read failure")
                    Files.readAllBytes(path)
                }
            )
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.any { it.contains("could not be captured") }, result.gates.joinToString("\n"))
        assertEquals(setOf(fixture.entity, fixture.block), attempts.toSet())
        assertTrue(entity.contentEquals(Files.readAllBytes(fixture.entity)))
        assertTrue(block.contentEquals(Files.readAllBytes(fixture.block)))
    }

    @Test
    fun `project facts and discovery use only the immutable captured snapshot`() {
        val project = directory.resolve("captured-facts")
        val fixture = writeFixture(project)
        var reads = 0

        val result = LegacyItemComponentPatchTransport.migrateForTest(
            project,
            dryRun = true,
            hooks = LegacyItemComponentPatchTransport.IoHooks(
                readBytes = { path ->
                    val bytes = Files.readAllBytes(path)
                    reads++
                    if (reads == 2) {
                        fixture.entity.writeText("package sample; class ReplacedEntity {}")
                        fixture.block.writeText("package sample; class ReplacedBlock {}")
                    }
                    bytes
                }
            )
        )

        assertEquals(2, reads)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)
    }

    @Test
    fun `one parse failure gates the complete captured family`() {
        val project = directory.resolve("parse-failure")
        val fixture = writeFixture(project)
        val entity = fixture.entity.readText()
        val block = fixture.block.readText()
        project.resolve("src/main/java/com/modporter/generated").createDirectories()
        project.resolve("src/main/java/com/modporter/generated/Broken.java")
            .writeText("package sample; class Broken {")

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.any { it.contains("did not parse") }, result.gates.joinToString("\n"))
        assertEquals(entity, fixture.entity.readText())
        assertEquals(block, fixture.block.readText())
    }

    @Test
    fun `rollback originals are prestaged and every committed path is restored or reported`() {
        val family = directory.resolve("rollback-family")
        family.createDirectories()
        val first = family.resolve("A.java")
        val second = family.resolve("B.java")
        val third = family.resolve("C.java")
        first.writeText("old-a")
        second.writeText("old-b")
        third.writeText("old-c")
        val originals = linkedMapOf(
            first to Files.readAllBytes(first),
            second to Files.readAllBytes(second),
            third to Files.readAllBytes(third)
        )
        val outputs = linkedMapOf(
            first to "new-a",
            second to "new-b",
            third to "new-c"
        )
        var commitMoves = 0
        var prestagedOriginals = 0L
        val restoreAttempts = mutableListOf<Path>()

        val failure = assertFailsWith<IOException> {
            LegacyItemComponentPatchTransport.writeAtomicFamilyForTest(
                outputs,
                originals
            ) { source, target ->
                if (source.fileName.toString().startsWith(".modporter-component-stage-")) {
                    commitMoves++
                    if (commitMoves == 1) {
                        Files.list(family).use { paths ->
                            prestagedOriginals = paths.filter {
                                it.fileName.toString()
                                    .startsWith(".modporter-component-original-")
                            }.count()
                        }
                    }
                    if (commitMoves == 3) throw IOException("injected commit failure")
                } else {
                    restoreAttempts.add(target)
                    if (target == second) throw IOException("injected restore failure")
                }
                Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
            }
        }

        assertEquals(3L, prestagedOriginals)
        assertEquals(listOf(second, first), restoreAttempts)
        assertEquals(1, failure.suppressed.size)
        assertEquals("old-a", first.readText())
        assertEquals("new-b", second.readText())
        assertEquals("old-c", third.readText())
        val preserved = Files.list(family).use { paths ->
            paths.filter {
                it.fileName.toString().startsWith(".modporter-component-original-")
            }.toList()
        }
        assertEquals(1, preserved.size)
        assertEquals("old-b", preserved.single().readText())
    }

    @Test
    fun `clone preflight gate aborts the whole family before the first production write`() {
        val project = directory.resolve("clone-preflight-gate")
        val fixture = writeFixture(project)
        val entity = fixture.entity.readText()
        val block = fixture.block.readText()
        project.resolve("src/main/java/sample/LevelReader.java").writeText("""
            package sample;
            class LevelReader {}
        """.trimIndent())

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.any { it.contains("LevelReader signature") }, result.gates.joinToString("\n"))
        assertEquals(entity, fixture.entity.readText())
        assertEquals(block, fixture.block.readText())
    }

    @Test
    fun `additional field writer gates the complete family`() {
        assertGatedUnchanged(
            "extra-writer",
            entityMutation = {
                it.replace(
                    "    public CompoundTag readOpaque() {",
                    """
                        void replaceOpaque(CompoundTag replacement) {
                            opaquePayload = replacement;
                        }

                        public CompoundTag readOpaque() {
                    """.trimIndent()
                )
            },
            gateText = "exactly one proven nullable CompoundTag carrier"
        )
    }

    @Test
    fun `additional setter caller gates the complete family`() {
        assertGatedUnchanged(
            "extra-setter",
            blockMutation = {
                it.replace(
                    "    List<ItemStack> drops(",
                    """
                        void secondWriter(ReservoirEntity vessel, CompoundTag visible) {
                            vessel.acceptPayload(visible, null);
                        }

                        List<ItemStack> drops(
                    """.trimIndent()
                )
            },
            gateText = "setter has 2 callers"
        )
    }

    @Test
    fun `additional getter consumer gates the complete family`() {
        assertGatedUnchanged(
            "extra-getter",
            blockMutation = {
                it.replace(
                    "    List<ItemStack> drops(",
                    """
                        CompoundTag inspect(ReservoirEntity vessel) {
                            return vessel.readOpaque();
                        }

                        List<ItemStack> drops(
                    """.trimIndent()
                )
            },
            gateText = "getter consumers"
        )
    }

    @Test
    fun `mismatched persistence key gates the complete family`() {
        assertGatedUnchanged(
            "mismatched-key",
            entityMutation = {
                it.replace(
                    "opaquePayload = root.contains(\"OpaqueBlob\") ? root.getCompound(\"OpaqueBlob\") : null;",
                    "opaquePayload = root.contains(\"DifferentBlob\") ? root.getCompound(\"DifferentBlob\") : null;"
                )
            },
            gateText = "exactly one proven nullable CompoundTag carrier"
        )
    }

    @Test
    fun `an additional field assignment inside the admitted reader gates the family`() {
        assertGatedUnchanged(
            "extra-reader-assignment",
            entityMutation = {
                it.replace(
                    """opaquePayload = root.contains("OpaqueBlob") ? root.getCompound("OpaqueBlob") : null;""",
                    """
                        opaquePayload = root.contains("OpaqueBlob") ? root.getCompound("OpaqueBlob") : null;
                        opaquePayload = root.getCompound("SecondOpaqueBlob");
                    """.trimIndent()
                )
            },
            gateText = "exactly one proven nullable CompoundTag carrier"
        )
    }

    @Test
    fun `non ForgeCaps producer is not admitted`() {
        val project = directory.resolve("other-producer")
        val fixture = writeFixture(
            project,
            blockMutation = {
                it.replace("\"ForgeCaps\"", "\"OtherOpaqueData\"")
            }
        )
        val entity = fixture.entity.readText()
        val block = fixture.block.readText()

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertEquals(entity, fixture.entity.readText())
        assertEquals(block, fixture.block.readText())
    }

    @Test
    fun `unrecognized getter constructor consumer gates the complete family`() {
        assertGatedUnchanged(
            "constructor-shape",
            blockMutation = {
                it.replace(
                    "new ItemStack(entry.getItem(), entry.getCount(), hidden.copy())",
                    "new ItemStack(entry.getItem(), hidden.copy(), entry.getCount())"
                )
            },
            gateText = "drop consumer"
        )
    }

    @Test
    fun `same named unrelated declarations and receivers do not enter the canonical family`() {
        val project = directory.resolve("canonical-receiver")
        val fixture = writeFixture(
            project,
            blockMutation = {
                it + """

                    class OtherCarrier {
                        void acceptPayload(CompoundTag visible, CompoundTag opaque) {}
                    }

                    class OtherCalls {
                        void write(OtherCarrier other, CompoundTag visible) {
                            other.acceptPayload(visible, null);
                        }
                    }
                """.trimIndent()
            }
        )
        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)
        assertTrue(fixture.block.readText().contains("OtherCarrier"), fixture.block.readText())
    }

    @Test
    fun `subclass receiver remains a canonical extra setter caller`() {
        assertGatedUnchanged(
            "subclass-receiver",
            blockMutation = {
                it + """

                    abstract class DerivedReservoirEntity extends ReservoirEntity {
                    }

                    class DerivedCalls {
                        void write(
                            DerivedReservoirEntity vessel,
                            CompoundTag visible
                        ) {
                            vessel.acceptPayload(visible, null);
                        }
                    }
                """.trimIndent()
            },
            gateText = "setter has 2 callers"
        )
    }

    @Test
    fun `enclosing local typed lambda catch foreach and nested owner shadows stay unrelated`() {
        val project = directory.resolve("lexical-shadows")
        val fixture = writeFixture(
            project,
            blockMutation = {
                it + """

                    class OtherCarrier {
                        void acceptPayload(CompoundTag visible, CompoundTag opaque) {}
                    }

                    class OtherCarrierException extends RuntimeException {
                        void acceptPayload(CompoundTag visible, CompoundTag opaque) {}
                    }

                    class ReceiverShadowCases {
                        private ReservoirEntity vessel;

                        void exercise(List<OtherCarrier> others, CompoundTag visible) {
                            OtherCarrier enclosing = others.get(0);
                            {
                                enclosing.acceptPayload(visible, null);
                            }
                            {
                                OtherCarrier vessel = others.get(0);
                                vessel.acceptPayload(visible, null);
                            }
                            others.forEach((OtherCarrier vessel) ->
                                vessel.acceptPayload(visible, null));
                            for (OtherCarrier vessel : others) {
                                vessel.acceptPayload(visible, null);
                            }
                            try {
                                throw new OtherCarrierException();
                            } catch (OtherCarrierException vessel) {
                                vessel.acceptPayload(visible, null);
                            }
                        }

                        static class NestedOwner {
                            OtherCarrier vessel;

                            void call(CompoundTag visible) {
                                vessel.acceptPayload(visible, null);
                            }
                        }
                    }
                """.trimIndent()
            }
        )

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)
        assertTrue(fixture.block.readText().contains("class ReceiverShadowCases"))
    }

    @Test
    fun `untyped lambda receiver with the carrier method name gates as ambiguous`() {
        assertGatedUnchanged(
            "untyped-lambda-shadow",
            blockMutation = {
                it + """

                    class OtherCarrier {
                        void acceptPayload(CompoundTag visible, CompoundTag opaque) {}
                    }

                    class UntypedLambdaShadow {
                        void call(List<OtherCarrier> others, CompoundTag visible) {
                            others.forEach(vessel ->
                                vessel.acceptPayload(visible, null));
                        }
                    }
                """.trimIndent()
            },
            gateText = "lexically ambiguous receiver"
        )
    }

    @Test
    fun `negated instanceof early return flow scopes the canonical getter receiver`() {
        val project = directory.resolve("negated-instanceof-flow-scope")
        val fixture = writeFixture(
            project,
            blockMutation = {
                it.replace(
                    "import net.minecraft.world.level.block.Block;",
                    "import net.minecraft.world.level.block.Block;\n" +
                        "    import net.minecraft.world.level.block.entity.BlockEntity;"
                ).replace(
                    "LootParams.Builder context,\n" +
                        "        ReservoirEntity vessel,\n" +
                        "        List<ItemStack> products\n" +
                        "    ) {\n" +
                        "        CompoundTag hidden = vessel.readOpaque();",
                    "LootParams.Builder context,\n" +
                        "        BlockEntity blockEntity,\n" +
                        "        List<ItemStack> products\n" +
                        "    ) {\n" +
                        "        if (!(blockEntity instanceof ReservoirEntity vessel))\n" +
                        "            return products;\n" +
                        "        CompoundTag hidden = vessel.readOpaque();"
                )
            }
        )

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)
        assertFalse(fixture.block.readText().contains("serializeNBT()"))
    }

    @Test
    fun `negated instanceof without abrupt exit keeps the getter receiver ambiguous`() {
        assertGatedUnchanged(
            "negated-instanceof-non-abrupt",
            blockMutation = {
                it.replace(
                    "import net.minecraft.world.level.block.Block;",
                    "import net.minecraft.world.level.block.Block;\n" +
                        "    import net.minecraft.world.level.block.entity.BlockEntity;"
                ).replace(
                    "LootParams.Builder context,\n" +
                        "        ReservoirEntity vessel,\n" +
                        "        List<ItemStack> products\n" +
                        "    ) {\n" +
                        "        CompoundTag hidden = vessel.readOpaque();",
                    "LootParams.Builder context,\n" +
                        "        BlockEntity blockEntity,\n" +
                        "        List<ItemStack> products\n" +
                        "    ) {\n" +
                        "        if (!(blockEntity instanceof ReservoirEntity vessel))\n" +
                        "            products.clear();\n" +
                        "        CompoundTag hidden = vessel.readOpaque();"
                )
            },
            gateText = "lexically ambiguous receiver"
        )
    }

    @Test
    fun `exact Create IBE Consumer contract admits its untyped receiver and same-name setter field`() {
        val project = directory.resolve("create-ibe-shape")
        val fixture = writeFixture(
            project,
            entityMutation = {
                it.replace(
                    "public void acceptPayload(CompoundTag visible, CompoundTag opaque)",
                    "public void acceptPayload(CompoundTag visible, CompoundTag opaquePayload)"
                ).replace(
                    "this.opaquePayload = opaque == null ? null : opaque.copy();",
                    "this.opaquePayload = opaquePayload == null ? null : opaquePayload.copy();"
                )
            },
            blockMutation = {
                it.replace(
                    "import java.util.Optional;",
                    """
                    import java.util.Optional;
                    import com.simibubi.create.foundation.block.IBE;
                    """.trimIndent()
                ).replace(
                    "class ReservoirBlock extends Block {",
                    "class ReservoirBlock extends Block implements IBE<ReservoirEntity> {"
                ).replace(
                    "void placed(Level habitat, ItemStack artifact, ReservoirEntity vessel, CompoundTag visible) {",
                    "void placed(Level habitat, BlockPos position, ItemStack artifact, CompoundTag visible) {"
                ).replace(
                    "CompoundTag serialized = artifact.serializeNBT();",
                    "withBlockEntityDo(habitat, position, vessel -> {\n" +
                        "            CompoundTag serialized = artifact.serializeNBT();"
                ).replace(
                    "vessel.acceptPayload(visible, hidden);",
                    "vessel.acceptPayload(visible, hidden);\n        });"
                )
            }
        )
        val ibe = project.resolve(
            "src/main/java/com/simibubi/create/foundation/block/IBE.java"
        )
        ibe.parent.createDirectories()
        ibe.writeText("""
            package com.simibubi.create.foundation.block;

            import java.util.Optional;
            import java.util.function.Consumer;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.EntityBlock;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public interface IBE<T extends BlockEntity> extends EntityBlock {
                default void withBlockEntityDo(
                    BlockGetter world,
                    BlockPos pos,
                    Consumer<T> action
                ) {
                    getBlockEntityOptional(world, pos).ifPresent(action);
                }

                Optional<T> getBlockEntityOptional(BlockGetter world, BlockPos pos);
            }
        """.trimIndent())

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)
        val entity = fixture.entity.readText()
        val block = fixture.block.readText()

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)
        assertTrue(entity.contains(
            "acceptPayload(CompoundTag visible, CompoundTag opaquePayload,"
        ), entity)
        assertTrue(entity.contains(
            "this.opaquePayload = opaquePayload == null ? null : opaquePayload.copy();"
        ), entity)
        assertTrue(block.contains(
            "withBlockEntityDo(habitat, position, vessel -> {"
        ), block)
        assertFalse(block.contains("serializeNBT()"), block)
    }

    @Test
    fun `type parameter shadow of the carrier owner gates as ambiguous`() {
        assertGatedUnchanged(
            "type-parameter-shadow",
            blockMutation = {
                it + """

                    class OtherCarrier {
                        void acceptPayload(CompoundTag visible, CompoundTag opaque) {}
                    }

                    class TypeParameterShadow {
                        <ReservoirEntity extends OtherCarrier> void call(
                            ReservoirEntity vessel,
                            CompoundTag visible
                        ) {
                            vessel.acceptPayload(visible, null);
                        }
                    }
                """.trimIndent()
            },
            gateText = "lexically ambiguous receiver"
        )
    }

    @Test
    fun `actual generated helper members are extractable for the native probe`() {
        val project = directory.resolve("native-helper-extraction")
        val fixture = writeFixture(project)
        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))

        val generated = fixture.entity.readText()
        val helpers = extractGeneratedHelpers(generated)
        val wrapper = """
            package probe;

            final class GeneratedComponentTransportHelpers {
            ${helpers.prependIndent("    ")}
            }
        """.trimIndent()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        assertTrue(parser.parse(wrapper).isSuccessful, wrapper)
        assertTrue(helpers.contains("DataComponentPatch.CODEC.encodeStart("), helpers)
        assertTrue(helpers.contains("DataComponentPatch.CODEC.parse("), helpers)
        assertTrue(helpers.contains("ItemStack stack"), helpers)
        assertTrue(helpers.contains("CustomData.of(root)"), helpers)
        assertTrue(helpers.contains("CompoundTag transport"), helpers)
        assertFalse(helpers.contains("MutableMap"), helpers)

        System.getenv("MODPORTER_COMPONENT_PROBE_HELPERS")?.let { output ->
            Path.of(output).writeText(helpers + "\n")
            Path.of(output + ".entity.java").writeText(generated)
        }
    }

    private fun assertGatedUnchanged(
        name: String,
        entityMutation: (String) -> String = { it },
        blockMutation: (String) -> String = { it },
        gateText: String
    ) {
        val project = directory.resolve(name)
        val fixture = writeFixture(project, entityMutation, blockMutation)
        val entity = fixture.entity.readText()
        val block = fixture.block.readText()

        val result = LegacyItemComponentPatchTransport.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.any { it.contains(gateText) }, result.gates.joinToString("\n"))
        assertEquals(entity, fixture.entity.readText())
        assertEquals(block, fixture.block.readText())
    }

    private fun extractGeneratedHelpers(source: String): String {
        val unit = StaticJavaParser.parse(source)
        val owner = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .single { it.nameAsString == "ReservoirEntity" }
        val fields = setOf(
            "MODPORTER_COMPONENT_CARRIER_KEY",
            "MODPORTER_COMPONENT_MARKER_KEY",
            "MODPORTER_COMPONENT_RAW_KEY",
            "MODPORTER_COMPONENT_RAW_VERSION_KEY",
            "MODPORTER_COMPONENT_LEGACY_KEY",
            "MODPORTER_COMPONENT_DISPLACED_KEY",
            "MODPORTER_COMPONENT_COMPOSED_KEY",
            "MODPORTER_COMPONENT_PATCH_KEY",
            "MODPORTER_COMPONENT_PATCH_VERSION_KEY",
            "MODPORTER_COMPONENT_OPAQUE_KEY"
        )
        val methods = setOf(
            "encodeModporterComponentPatch",
            "modporterOpaqueRawPatch",
            "detachModporterOpaqueCustomData",
            "detachModporterOpaqueComponents",
            "applyModporterComponentPatch",
            "mergeModporterOpaqueComponents",
            "attachModporterOpaqueComponents"
        )
        val members = owner.members.filter { member ->
            member is FieldDeclaration &&
                member.variables.any { it.nameAsString in fields } ||
                member is MethodDeclaration && member.nameAsString in methods
        }.sortedBy { nodeStart(source, it) }
        assertEquals(fields.size + methods.size, members.size)
        return members.joinToString("\n\n") { sourceSlice(source, it) }
    }

    private fun sourceSlice(source: String, node: Node): String {
        val range = node.range.orElseThrow()
        val starts = lineStarts(source)
        val start = starts[range.begin.line - 1] + range.begin.column - 1
        val endExclusive = starts[range.end.line - 1] + range.end.column
        return source.substring(start, endExclusive)
    }

    private fun nodeStart(source: String, node: Node): Int {
        val begin = node.range.orElseThrow().begin
        return lineStarts(source)[begin.line - 1] + begin.column - 1
    }

    private fun lineStarts(source: String): List<Int> = buildList {
        add(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') add(index + 1)
        }
    }

    private data class Fixture(val entity: Path, val block: Path)

    private fun writeFixture(
        project: Path,
        entityMutation: (String) -> String = { it },
        blockMutation: (String) -> String = { it }
    ): Fixture {
        val source = project.resolve("src/main/java/sample")
        source.createDirectories()
        val entity = source.resolve("ReservoirEntity.java")
        val block = source.resolve("ReservoirBlock.java")
        entity.writeText(entityMutation("""
            package sample;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            class ReservoirEntity extends BlockEntity {
                private CompoundTag visiblePayload;
                private CompoundTag opaquePayload;
                private int pressure;

                ReservoirEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                    visiblePayload = new CompoundTag();
                    opaquePayload = null;
                }

                protected void savePayload(CompoundTag root) {
                    root.put("VisibleBlob", visiblePayload);
                    if (opaquePayload != null)
                        root.put("OpaqueBlob", opaquePayload);
                }

                protected void loadPayload(CompoundTag root) {
                    visiblePayload = root.getCompound("VisibleBlob");
                    opaquePayload = root.contains("OpaqueBlob") ? root.getCompound("OpaqueBlob") : null;
                }

                public void acceptPayload(CompoundTag visible, CompoundTag opaque) {
                    this.visiblePayload = visible.copy();
                    this.opaquePayload = opaque == null ? null : opaque.copy();
                    visible.remove("EmbeddedEntity");
                }

                public CompoundTag readVisible() {
                    return visiblePayload;
                }

                public CompoundTag readOpaque() {
                    return opaquePayload;
                }

                public int readPressure() {
                    return pressure;
                }
            }
        """.trimIndent()))
        block.writeText(blockMutation("""
            package sample;

            import java.util.List;
            import java.util.Optional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.storage.loot.LootParams;

            class ReservoirBlock extends Block {
                ReservoirBlock(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties properties
                ) {
                    super(properties);
                }

                void placed(Level habitat, ItemStack artifact, ReservoirEntity vessel, CompoundTag visible) {
                    CompoundTag serialized = artifact.serializeNBT();
                    CompoundTag hidden = serialized.contains("ForgeCaps")
                        ? serialized.getCompound("ForgeCaps")
                        : null;
                    vessel.acceptPayload(visible, hidden);
                }

                List<ItemStack> drops(
                    LootParams.Builder context,
                    ReservoirEntity vessel,
                    List<ItemStack> products
                ) {
                    CompoundTag hidden = vessel.readOpaque();
                    if (hidden == null)
                        return products;
                    return products.stream().map(entry -> {
                        if (!(entry.getItem() instanceof ReservoirItem))
                            return entry;
                        ItemStack restored =
                            new ItemStack(entry.getItem(), entry.getCount(), hidden.copy());
                        restored.setTag(entry.getTag());
                        return restored;
                    }).toList();
                }

                @Override
                public ItemStack getCloneItemStack(
                    BlockGetter view,
                    BlockPos position,
                    BlockState state
                ) {
                    Item product = selectProduct();
                    Optional<ReservoirEntity> selected = selectedEntity(view, position);
                    CompoundTag hidden = selected.map(ReservoirEntity::readOpaque)
                        .map(CompoundTag::copy).orElse(null);
                    CompoundTag visible = selected.map(ReservoirEntity::readVisible)
                        .map(CompoundTag::copy).orElse(new CompoundTag());
                    int pressureValue = selected.map(ReservoirEntity::readPressure).orElse(0);
                    ItemStack rebuilt = new ItemStack(product, 1, hidden);
                    visible.putInt("PressureUnits", pressureValue);
                    rebuilt.setTag(visible);
                    return rebuilt;
                }
            }

            class ReservoirItem extends Item {
                ReservoirItem(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent()))
        return Fixture(entity, block)
    }
}
