package com.modporter.transforms

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.CreateRegistrateDatagenLifecycleMigration
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateRegistrateDatagenLifecycleMigrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `splits Create Registrate registration before root construction and is idempotent`() {
        val fixture = fixture(directory)
        val createBefore = fixture.create.readText()
        val datagenBefore = fixture.datagen.readText()

        val dryRun = CreateRegistrateDatagenLifecycleMigration.migrate(directory, dryRun = true)
        assertTrue(dryRun.gates.isEmpty(), dryRun.gates.joinToString())
        assertEquals(2, dryRun.changes.size)
        assertEquals(createBefore, fixture.create.readText())
        assertEquals(datagenBefore, fixture.datagen.readText())

        val result = CreateRegistrateDatagenLifecycleMigration.migrate(directory, dryRun = false)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(2, result.changes.size)

        val create = fixture.create.readText()
        val highListener =
            "modEventBus.addListener(EventPriority.HIGHEST, CreateDatagen::gatherDataHighPriority)"
        val lowListener =
            "modEventBus.addListener(EventPriority.LOWEST, CreateDatagen::gatherData)"
        assertTrue(compact(create).contains(compact(highListener)), create)
        assertTrue(create.indexOf("gatherDataHighPriority") < create.indexOf("gatherData);"), create)

        val datagen = fixture.datagen.readText()
        val owner = StaticJavaParser.parse(datagen).types.single() as ClassOrInterfaceDeclaration
        val high = owner.methods.single { it.nameAsString == "gatherDataHighPriority" }
        val gather = owner.methods.single { it.nameAsString == "gatherData" }
        assertEquals(
            compact(
                """
                {
                    if (event.getMods().contains(Create.ID))
                        addExtraRegistrateData();
                }
                """.trimIndent()
            ),
            compact(high.body.orElseThrow().toString())
        )
        assertEquals(
            "if(!event.getMods().contains(Create.ID))return;",
            compact(gather.body.orElseThrow().statements.first().toString())
        )
        assertTrue(
            gather.findAll(MethodCallExpr::class.java)
                .none { it.nameAsString == "addExtraRegistrateData" },
            datagen
        )
        assertTrue(datagen.contains("ORIGINAL_PROVIDER_BODY();"), datagen)
        assertTrue(datagen.contains("ORIGINAL_EXTRA_BODY();"), datagen)

        val replay = CreateRegistrateDatagenLifecycleMigration.migrate(directory, dryRun = false)
        assertTrue(replay.gates.isEmpty(), replay.gates.joinToString())
        assertTrue(replay.changes.isEmpty(), replay.changes.joinToString())
        assertEquals(create, fixture.create.readText())
        assertEquals(datagen, fixture.datagen.readText())
    }

    @Test
    fun `refuses a partial lifecycle instead of adding a second high priority listener`() {
        val fixture = fixture(directory)
        fixture.create.writeText(
            fixture.create.readText().replace(
                "modEventBus.addListener(EventPriority.LOWEST, CreateDatagen::gatherData);",
                """
                modEventBus.addListener(
                    EventPriority.HIGHEST, CreateDatagen::gatherDataHighPriority);
                modEventBus.addListener(EventPriority.LOWEST, CreateDatagen::gatherData);
                """.trimIndent()
            )
        )
        val createBefore = fixture.create.readText()
        val datagenBefore = fixture.datagen.readText()

        val result = CreateRegistrateDatagenLifecycleMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("partial or incompatible"), result.gates.single())
        assertEquals(createBefore, fixture.create.readText())
        assertEquals(datagenBefore, fixture.datagen.readText())
    }

    @Test
    fun `ignores projects without the exact Create datagen path`() {
        directory.resolve("src/main/java/example").createDirectories()
        directory.resolve("src/main/java/example/CreateDatagen.java").writeText(
            "package example; class CreateDatagen {}"
        )

        val result = CreateRegistrateDatagenLifecycleMigration.migrate(directory, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
    }

    private fun fixture(root: Path): Fixture {
        val create = root.resolve(
            "src/main/java/com/simibubi/create/Create.java"
        )
        val datagen = root.resolve(
            "src/main/java/com/simibubi/create/infrastructure/data/CreateDatagen.java"
        )
        create.parent.createDirectories()
        datagen.parent.createDirectories()
        create.writeText(
            """
            package com.simibubi.create;

            import com.simibubi.create.infrastructure.data.CreateDatagen;

            import net.neoforged.bus.api.EventPriority;

            public class Create {
                public static void onCtor() {
                    UNRELATED_BEFORE();
                    modEventBus.addListener(EventPriority.LOWEST, CreateDatagen::gatherData);
                    UNRELATED_AFTER();
                }
            }
            """.trimIndent() + "\n"
        )
        datagen.writeText(
            """
            package com.simibubi.create.infrastructure.data;

            import com.simibubi.create.Create;

            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class CreateDatagen {
                public static void gatherData(GatherDataEvent event) {
                    addExtraRegistrateData();

                    ORIGINAL_PROVIDER_BODY();
                }

                private static void addExtraRegistrateData() {
                    ORIGINAL_EXTRA_BODY();
                }
            }
            """.trimIndent() + "\n"
        )
        return Fixture(create, datagen)
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), "")

    private data class Fixture(
        val create: Path,
        val datagen: Path
    )
}
