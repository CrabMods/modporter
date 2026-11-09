package com.modporter.transforms

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.stmt.IfStmt
import com.modporter.core.transforms.structural.CreateAdvancementTriggerRegistrationMigration
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateAdvancementTriggerRegistrationMigrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `moves existing Create trigger instances to the trigger registry event and is idempotent`() {
        val fixture = fixture(directory)
        val before = fixture.create.readText()

        val dryRun =
            CreateAdvancementTriggerRegistrationMigration.migrate(directory, dryRun = true)
        assertTrue(dryRun.gates.isEmpty(), dryRun.gates.joinToString())
        assertEquals(1, dryRun.changes.size)
        assertEquals(before, fixture.create.readText())

        val result =
            CreateAdvancementTriggerRegistrationMigration.migrate(directory, dryRun = false)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(1, result.changes.size)

        val source = fixture.create.readText()
        assertTrue(
            source.contains("import net.minecraft.core.registries.BuiltInRegistries;"),
            source
        )
        val unit = StaticJavaParser.parse(source)
        val owner = unit.types.single() as ClassOrInterfaceDeclaration
        val init = owner.methods.single { it.nameAsString == "init" }
        assertFalse(init.toString().contains("AllAdvancements.register()"), init.toString())
        assertFalse(init.toString().contains("AllTriggers.register()"), init.toString())
        val onRegister = owner.methods.single { it.nameAsString == "onRegister" }
        val target = onRegister.findAll(IfStmt::class.java).single {
            compact(it.condition.toString()) ==
                "event.getRegistry()==BuiltInRegistries.TRIGGER_TYPES"
        }
        assertEquals(
            listOf("AllAdvancements.register();", "AllTriggers.register();"),
            target.thenStmt.asBlockStmt().statements.map { compact(it.toString()) }
        )
        assertTrue(source.contains("UNRELATED_SETUP();"), source)
        assertTrue(source.contains("UNRELATED_REGISTER();"), source)

        val triggers = fixture.triggers.readText()
        assertTrue(
            compact(triggers).contains(
                "Registry.register(BuiltInRegistries.TRIGGER_TYPES,trigger.getId(),trigger)"
            ),
            triggers
        )

        val replay =
            CreateAdvancementTriggerRegistrationMigration.migrate(directory, dryRun = false)
        assertTrue(replay.gates.isEmpty(), replay.gates.joinToString())
        assertTrue(replay.changes.isEmpty(), replay.changes.joinToString())
        assertEquals(source, fixture.create.readText())
    }

    @Test
    fun `refuses a trigger registry implementation that substitutes an ID or object`() {
        val fixture = fixture(directory)
        val triggerSource = fixture.triggers.readText()
        assertTrue(triggerSource.contains("trigger.getId(), trigger"), triggerSource)
        val incompatible = triggerSource.replace(
            "trigger.getId(), trigger",
            "otherId, replacement"
        )
        assertFalse(incompatible.contains("trigger.getId(), trigger"), incompatible)
        fixture.triggers.writeText(incompatible)
        val createBefore = fixture.create.readText()

        val result =
            CreateAdvancementTriggerRegistrationMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(
            result.gates.single().contains("existing trigger instance and ID"),
            result.gates.single()
        )
        assertEquals(createBefore, fixture.create.readText())
    }

    @Test
    fun `refuses a partial timing migration`() {
        val fixture = fixture(directory)
        fixture.create.writeText(
            fixture.create.readText().replace(
                "UNRELATED_REGISTER();",
                """
                UNRELATED_REGISTER();
                if (event.getRegistry() == BuiltInRegistries.TRIGGER_TYPES) {
                    AllAdvancements.register();
                    AllTriggers.register();
                }
                """.trimIndent()
            ).replace(
                "import net.minecraft.resources.ResourceLocation;",
                """
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.core.registries.BuiltInRegistries;
                """.trimIndent()
            )
        )
        val before = fixture.create.readText()

        val result =
            CreateAdvancementTriggerRegistrationMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("partial or incompatible"))
        assertEquals(before, fixture.create.readText())
    }

    @Test
    fun `ignores projects without Create advancement triggers`() {
        directory.resolve("src/main/java/example").createDirectories()
        directory.resolve("src/main/java/example/AllTriggers.java").writeText(
            "package example; class AllTriggers {}"
        )

        val result =
            CreateAdvancementTriggerRegistrationMigration.migrate(directory, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
    }

    private fun fixture(root: Path): Fixture {
        val create = root.resolve("src/main/java/com/simibubi/create/Create.java")
        val triggers = root.resolve(
            "src/main/java/com/simibubi/create/foundation/advancement/AllTriggers.java"
        )
        create.parent.createDirectories()
        triggers.parent.createDirectories()
        create.writeText(
            """
            package com.simibubi.create;

            import com.simibubi.create.foundation.advancement.AllAdvancements;
            import com.simibubi.create.foundation.advancement.AllTriggers;

            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
            import net.neoforged.neoforge.registries.RegisterEvent;

            public class Create {
                public static void init(final FMLCommonSetupEvent event) {
                    event.enqueueWork(() -> {
                        UNRELATED_SETUP();
                        AllAdvancements.register();
                        AllTriggers.register();
                    });
                }

                public static void onRegister(final RegisterEvent event) {
                    UNRELATED_REGISTER();
                }
            }
            """.trimIndent() + "\n"
        )
        triggers.writeText(
            """
            package com.simibubi.create.foundation.advancement;

            import java.util.LinkedList;
            import java.util.List;

            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class AllTriggers {
                private static final List<CriterionTriggerBase<?>> triggers = new LinkedList<>();

                public static void register() {
                    triggers.forEach(trigger ->
                        Registry.register(
                            BuiltInRegistries.TRIGGER_TYPES, trigger.getId(), trigger
                        )
                    );
                }
            }
            """.trimIndent() + "\n"
        )
        return Fixture(create, triggers)
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), "")

    private data class Fixture(
        val create: Path,
        val triggers: Path
    )
}
