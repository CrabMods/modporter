package com.modporter.transforms

import com.modporter.core.pipeline.PassResult
import com.modporter.core.transforms.structural.LegacyMigrationInventoryDriver
import com.modporter.core.transforms.structural.LegacyMigrationInventoryPrepass
import com.modporter.core.transforms.structural.LegacyMigrationRuntimeIdentity
import com.modporter.core.transforms.structural.LegacyMigrationSourceBinding
import com.modporter.core.transforms.structural.LegacyMigrationSourcePlatformGate
import com.modporter.core.transforms.structural.LegacyMigrationSourcePreflight
import com.modporter.core.transforms.structural.LegacyMigrationSurfaceDetector
import com.modporter.core.transforms.structural.LegacyMigrationSurfaceState
import com.modporter.core.transforms.structural.ParsedLegacyMigrationSurfaceDetector
import com.modporter.core.transforms.structural.ReviewedSourceDeclarationGate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyMigrationInventoryPrepassTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `foreign same-name APIs do not start dependency inventory`() {
        java(
            "foreign/IPlantable.java",
            """
            package foreign;
            public interface IPlantable {}
            """.trimIndent()
        )
        java(
            "foreign/PlantType.java",
            """
            package foreign;
            public final class PlantType {}
            """.trimIndent()
        )
        java(
            "foreign/LootingLevelEvent.java",
            """
            package foreign;
            public final class LootingLevelEvent {}
            """.trimIndent()
        )
        java(
            "example/ForeignNames.java",
            """
            package example;
            import foreign.IPlantable;
            import foreign.PlantType;
            import foreign.LootingLevelEvent;
            final class ForeignNames {
                IPlantable plant;
                PlantType type;
                LootingLevelEvent event;
                ForeignNames getItem() { return this; }
                boolean onBlockStartBreak(Object stack, Object pos, Object player) {
                    return false;
                }
                boolean unrelated(ForeignNames value, Object pos, Object player) {
                    return value.getItem().onBlockStartBreak(value, pos, player);
                }
            }
            """.trimIndent()
        )

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertTrue(state.legacyCandidates.isEmpty())
        assertFalse(state.requiresInventory)
    }

    @Test
    fun `legacy-only test fixtures do not start production inventory`() {
        java("example/Main.java", "package example; final class Main {}")
        directory.resolve("src/test/java/example/LegacyFixture.java").also {
            it.parent.createDirectories()
            it.writeText(
                """
                package example;
                import net.minecraftforge.common.IPlantable;
                final class LegacyFixture { IPlantable value; }
                """.trimIndent()
            )
        }

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertTrue(state.legacyCandidates.isEmpty())
        assertFalse(state.requiresInventory)
    }

    @Test
    fun `malformed unrelated production source preserves downstream skipped-shape handling`() {
        java(
            "example/Broken.java",
            """
            package example;
            import net.minecraftforge.common.MinecraftForge;
            public final class Broken {
                void init( {
                    MinecraftForge.EVENT_BUS.register(this);
                }
            }
            """.trimIndent()
        )

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertTrue(state.legacyCandidates.isEmpty())
        assertFalse(state.requiresInventory)
    }

    @Test
    fun `malformed legacy-only source fails closed before inventory`() {
        val source = java(
            "example/BrokenLegacy.java",
            """
            package example;
            import net.minecraftforge.common.IPlantable;
            public final class BrokenLegacy {
                void inspect(IPlantable plant {
                }
            }
            """.trimIndent()
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)
        }

        assertTrue(failure.message.orEmpty().contains(source.toString()))
        assertTrue(failure.message.orEmpty().contains("Cannot inspect legacy source ownership"))
    }

    @Test
    fun `player item stack block-start call activates inventory`() {
        java(
            "example/Deployer.java",
            """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerPlayer;
            final class Deployer {
                boolean harvest(ServerPlayer player, BlockPos pos) {
                    return player.getMainHandItem().onBlockStartBreak(pos, player);
                }
            }
            """.trimIndent()
        )

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertEquals(setOf("block-start-break"), state.legacyCandidates)
    }

    @Test
    fun `exact imported legacy owners activate all source families`() {
        java(
            "example/LegacySources.java",
            """
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraftforge.common.IPlantable;
            import net.minecraftforge.event.entity.living.LootingLevelEvent;
            final class LegacyItem extends Item {
                LegacyItem(Properties properties) { super(properties); }
                public boolean onBlockStartBreak(
                    ItemStack stack,
                    BlockPos pos,
                    Player player
                ) {
                    return true;
                }
            }
            final class LegacyUse {
                IPlantable plant;
                LootingLevelEvent event;
            }
            """.trimIndent()
        )

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertEquals(
            setOf("plant", "block-start-break", "looting"),
            state.legacyCandidates
        )
        assertTrue(state.requiresInventory)
    }

    @Test
    fun `filtered manager override alone activates model-data inventory`() {
        java(
            "com/simibubi/create/content/decoration/copycat/" +
                "FilteredBlockAndTintGetter.java",
            """
            package com.simibubi.create.content.decoration.copycat;
            import org.jetbrains.annotations.Nullable;
            import net.minecraft.world.level.BlockAndTintGetter;
            import net.minecraftforge.client.model.data.ModelDataManager;
            public final class FilteredBlockAndTintGetter
                    implements BlockAndTintGetter {
                private final BlockAndTintGetter wrapped = null;
                @Override
                public @Nullable ModelDataManager getModelDataManager() {
                    return wrapped.getModelDataManager();
                }
            }
            """.trimIndent()
        )

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertEquals(setOf("model-data-manager"), state.legacyCandidates)
        assertTrue(state.requiresInventory)
    }

    @Test
    fun `copycat manager query alone activates model-data inventory`() {
        java(
            "com/simibubi/create/content/decoration/copycat/CopycatBlock.java",
            """
            package com.simibubi.create.content.decoration.copycat;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.BlockAndTintGetter;
            import net.minecraft.world.level.block.state.BlockState;
            public abstract class CopycatBlock {
                @Override
                public BlockState getAppearance(
                    BlockState state,
                    BlockAndTintGetter level,
                    BlockPos pos,
                    Direction side,
                    BlockState queryState,
                    BlockPos queryPos
                ) {
                    Object manager = level.getModelDataManager();
                    return state;
                }
            }
            """.trimIndent()
        )

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertEquals(setOf("model-data-manager"), state.legacyCandidates)
        assertTrue(state.requiresInventory)
    }

    @Test
    fun `foreign model-data manager shapes do not start inventory`() {
        java(
            "foreign/copycat/ForeignShapes.java",
            """
            package foreign.copycat;
            import org.jetbrains.annotations.Nullable;
            final class FilteredBlockAndTintGetter {
                private final Getter wrapped = null;
                @Override
                public @Nullable Object getModelDataManager() {
                    return wrapped.getModelDataManager();
                }
            }
            final class CopycatBlock {
                @Override
                public Object getAppearance(
                    Object state,
                    Getter level,
                    Object pos,
                    Object side,
                    Object queryState,
                    Object queryPos
                ) {
                    return level.getModelDataManager();
                }
            }
            interface Getter {
                Object getModelDataManager();
            }
            """.trimIndent()
        )

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertTrue(state.legacyCandidates.isEmpty())
        assertFalse(state.requiresInventory)
    }

    @Test
    fun `generated model-data helper is a completed-family rerun marker`() {
        java(
            "com/modporter/generated/example/compat/LegacyModelDataManagers.java",
            """
            package com.modporter.generated.example.compat;
            /**
             * modporter-legacy-model-data-manager-compat-v1
             */
            public final class LegacyModelDataManagers {}
            """.trimIndent()
        )

        val state = ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)

        assertTrue(state.legacyCandidates.isEmpty())
        assertEquals(
            setOf("model-data-manager-generated-source"),
            state.markers
        )
        assertEquals(setOf("model-data-manager"), state.completedFamilies)
        assertTrue(state.requiresInventory)
    }

    @Test
    fun `malformed exact copycat manager source fails closed`() {
        val source = java(
            "com/simibubi/create/content/decoration/copycat/CopycatBlock.java",
            """
            package com.simibubi.create.content.decoration.copycat;
            public class CopycatBlock {
                Object getModelDataManager( {
                }
            }
            """.trimIndent()
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ParsedLegacyMigrationSurfaceDetector.detect(directory, directory)
        }

        assertTrue(failure.message.orEmpty().contains(source.toString()))
        assertTrue(failure.message.orEmpty().contains("Cannot inspect legacy source ownership"))
    }

    @Test
    fun `fresh apply verifies and preflights before binding owner handoff`() {
        java("example/Legacy.java", "package example; final class Legacy {}")
        val detector = SequenceDetector(
            LegacyMigrationSurfaceState(setOf("plant"), emptySet(), false),
            LegacyMigrationSurfaceState(
                emptySet(),
                setOf("sealed-v2-coverage", "plant-runtime-request"),
                true
            )
        )
        val driver = RecordingDriver()
        val binding = RecordingBinding()
        var preflights = 0
        val pass = LegacyMigrationInventoryPrepass(
            detector,
            driver,
            binding,
            LegacyMigrationSourcePreflight { preflights++ }
        )
        assertEquals(-1, pass.order)
        assertTrue(pass.migratesBuildRoot)

        val result = pass.apply(directory)

        assertTrue(result.errors.isEmpty())
        assertEquals(1, driver.productions)
        assertEquals(0, driver.replays)
        assertEquals(1, preflights)
        assertEquals(0, binding.verifications)

        pass.finishProject(directory)

        assertEquals(1, binding.seals)
        assertEquals(1, driver.releases)
    }

    @Test
    fun `sealed replay requires source binding before verifier and preflight`() {
        java("example/Migrated.java", "package example; final class Migrated {}")
        val events = mutableListOf<String>()
        val state = LegacyMigrationSurfaceState(
            emptySet(),
            setOf("sealed-v2-coverage"),
            true
        )
        val driver = RecordingDriver(events)
        val binding = RecordingBinding(events)
        val pass = LegacyMigrationInventoryPrepass(
            SequenceDetector(state, state),
            driver,
            binding,
            LegacyMigrationSourcePreflight { events += "preflight" }
        )

        val result = pass.analyze(directory)
        pass.finishProject(directory)

        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("binding-verify", "v2-verify", "preflight", "release"), events)
        assertEquals(0, binding.seals)
    }

    @Test
    fun `fresh dry run aborts without producer metadata writes`() {
        java("example/Legacy.java", "package example; final class Legacy {}")
        val driver = RecordingDriver()
        val pass = LegacyMigrationInventoryPrepass(
            SequenceDetector(
                LegacyMigrationSurfaceState(setOf("plant"), emptySet(), false)
            ),
            driver,
            RecordingBinding(),
            LegacyMigrationSourcePreflight { error("must not preflight") }
        )

        val result = pass.analyze(directory)

        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("cannot be published read-only"))
        assertEquals(0, driver.productions)
        assertEquals(0, driver.replays)
        pass.finishProject(directory)
        assertEquals(1, driver.releases)
    }

    @Test
    fun `verifier failure releases handoff and reports an aborting pass error`() {
        java("example/Migrated.java", "package example; final class Migrated {}")
        val state = LegacyMigrationSurfaceState(
            emptySet(),
            setOf("sealed-v2-coverage"),
            true
        )
        val driver = RecordingDriver().also { it.replayFailure = "sealed graph drift" }
        var preflights = 0
        val pass = LegacyMigrationInventoryPrepass(
            SequenceDetector(state),
            driver,
            RecordingBinding(),
            LegacyMigrationSourcePreflight { preflights++ }
        )

        val result = pass.apply(directory)

        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("sealed graph drift"))
        assertEquals(0, preflights)
        assertEquals(1, driver.releases)
        pass.finishProject(directory)
        assertEquals(2, driver.releases)
    }

    @Test
    fun `finish does not bind a partially completed migration family set`() {
        java("example/Legacy.java", "package example; final class Legacy {}")
        val binding = RecordingBinding()
        val pass = LegacyMigrationInventoryPrepass(
            SequenceDetector(
                LegacyMigrationSurfaceState(
                    setOf("plant", "block-start-break"),
                    emptySet(),
                    false
                ),
                LegacyMigrationSurfaceState(
                    emptySet(),
                    setOf("sealed-v2-coverage", "plant-runtime-request"),
                    true
                )
            ),
            RecordingDriver(),
            binding,
            LegacyMigrationSourcePreflight {}
        )

        assertTrue(pass.apply(directory).errors.isEmpty())
        pass.finishProject(directory)

        assertEquals(0, binding.seals)
    }

    @Test
    fun `preflight never mutates source through lifecycle seams`() {
        val file = java(
            "example/Legacy.java",
            "package example; final class Legacy { int value = 1; }"
        )
        val before = file.readText()
        val state = LegacyMigrationSurfaceState(setOf("plant"), emptySet(), false)
        val pass = LegacyMigrationInventoryPrepass(
            SequenceDetector(
                state,
                LegacyMigrationSurfaceState(
                    emptySet(),
                    setOf("sealed-v2-coverage", "plant-runtime-request"),
                    true
                )
            ),
            RecordingDriver(),
            RecordingBinding(),
            LegacyMigrationSourcePreflight { leaf ->
                assertEquals(before, leaf.resolve("src/main/java/example/Legacy.java").readText())
            }
        )

        val result = pass.apply(directory)

        assertTrue(result.errors.isEmpty())
        assertEquals(before, file.readText())
        pass.finishProject(directory)
        assertEquals(before, file.readText())
    }

    @Test
    fun `unreviewed source platform keeps established migrations without resolving`() {
        java("example/Crop.java", "package example; final class Crop {}")
        val driver = RecordingDriver()
        val binding = RecordingBinding()
        val pass = LegacyMigrationInventoryPrepass(
            SequenceDetector(LegacyMigrationSurfaceState(setOf("plant"), emptySet(), false)),
            driver,
            binding,
            LegacyMigrationSourcePreflight { error("must not preflight") },
            LegacyMigrationSourcePlatformGate { false }
        )

        val result = pass.apply(directory)
        pass.finishProject(directory)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.changes.isEmpty())
        // The strict source-shape gate treats skipped entries as untransformed source.
        assertTrue(result.skipped.isEmpty(), result.skipped.toString())
        assertEquals(0, driver.productions)
        assertEquals(0, binding.seals)
    }

    @Test
    fun `sealed pointer and rerun markers still bind an unreviewed platform`() {
        val gate = LegacyMigrationSourcePlatformGate { false }
        assertTrue(
            LegacyMigrationSurfaceState(emptySet(), setOf("sealed-v2-coverage"), true)
                .requiresSealedInventory(directory, gate)
        )
        assertTrue(
            LegacyMigrationSurfaceState(setOf("plant"), setOf("plant-runtime-request"), false)
                .requiresSealedInventory(directory, gate)
        )
        assertFalse(
            LegacyMigrationSurfaceState(setOf("plant"), emptySet(), false)
                .requiresSealedInventory(directory, gate)
        )
    }

    @Test
    fun `reviewed declaration gate rejects build declarations of another project`() {
        directory.resolve("build.gradle").writeText("plugins { id 'net.neoforged.moddev.legacyforge' }\n")
        directory.resolve("gradle.properties").writeText("minecraft_version=1.20.1\n")
        directory.resolve("settings.gradle").writeText("rootProject.name = 'other'\n")

        assertFalse(ReviewedSourceDeclarationGate.reviewed(directory))
        assertFalse(ReviewedSourceDeclarationGate.reviewed(directory.resolve("missing")))
    }

    private fun java(relative: String, source: String): Path =
        directory.resolve("src/main/java").resolve(relative).also {
            it.parent.createDirectories()
            it.writeText(source)
        }

    private class SequenceDetector(
        private vararg val states: LegacyMigrationSurfaceState
    ) : LegacyMigrationSurfaceDetector {
        private var index = 0

        override fun detect(owner: Path, leaf: Path): LegacyMigrationSurfaceState =
            states[minOf(index++, states.lastIndex)]
    }

    private class RecordingDriver(
        private val events: MutableList<String> = mutableListOf()
    ) : LegacyMigrationInventoryDriver {
        override val identity = LegacyMigrationRuntimeIdentity("source", "jar")
        var productions = 0
        var replays = 0
        var releases = 0
        var replayFailure: String? = null

        override fun produceAndVerify(owner: Path, leaf: Path): PassResult {
            events += "produce-v2-verify"
            productions++
            return PassResult("producer", emptyList())
        }

        override fun verifyAndPublish(owner: Path, leaf: Path) {
            events += "v2-verify"
            replays++
            replayFailure?.let(::error)
        }

        override fun release(owner: Path) {
            events += "release"
            releases++
        }
    }

    private class RecordingBinding(
        private val events: MutableList<String> = mutableListOf()
    ) : LegacyMigrationSourceBinding {
        var verifications = 0
        var seals = 0

        override fun verify(
            owner: Path,
            leaf: Path,
            identity: LegacyMigrationRuntimeIdentity
        ) {
            events += "binding-verify"
            verifications++
        }

        override fun seal(
            owner: Path,
            leaf: Path,
            identity: LegacyMigrationRuntimeIdentity
        ) {
            events += "binding-seal"
            seals++
        }
    }
}
