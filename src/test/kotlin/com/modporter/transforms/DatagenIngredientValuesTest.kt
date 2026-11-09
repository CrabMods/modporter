package com.modporter.transforms

import com.modporter.core.transforms.structural.DatagenIngredientValues
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DatagenIngredientValuesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `closed datagen subclass becomes an output-only value with scoped codec registration`() {
        val fixture = writeFixture(directory.resolve("positive"))

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(5, result.changes.size)

        val value = fixture.value.readText()
        assertTrue(
            value.contains(
                "public final class SimpleDatagenIngredient implements Ingredient.Value"
            ),
            value
        )
        assertTrue(value.contains("ResourceLocation.CODEC.encodeStart("), value)
        assertTrue(
            value.contains("return DataResult.error("),
            value
        )
        assertTrue(value.contains("\"SimpleDatagenIngredient is output-only\""), value)
        assertTrue(value.contains("return prefix.add("), value)
        assertTrue(value.contains("return List.of();"), value)
        assertTrue(value.contains("public static Ingredient of("), value)
        assertTrue(value.contains("mod.asResource(id)"), value)
        assertTrue(value.contains("return new Ingredient(values);"), value)
        assertFalse(value.contains("extends Ingredient"), value)
        assertFalse(
            Regex("""@Override\s+public\s+JsonElement\s+toJson\s*\(""")
                .containsMatchIn(value),
            value
        )
        assertFalse(value.contains("toJson()"), value)
        assertFalse(value.contains("Items.AIR"), value)
        assertFalse(value.contains("throw "), value)

        val caller = fixture.caller.readText()
        assertTrue(
            caller.contains(
                "params.ingredients.add(SimpleDatagenIngredient.of(mod, id));"
            ),
            caller
        )
        assertFalse(caller.contains("new SimpleDatagenIngredient"), caller)

        val mixin = fixture.generatedMixin.readText()
        assertTrue(
            mixin.contains("if (!DatagenModLoader.isRunningDataGen())"),
            mixin
        )
        assertTrue(mixin.contains("return original;"), mixin)
        assertTrue(
            mixin.contains(
                "Codec.mapEither(SimpleDatagenIngredient.MAP_CODEC, original)"
            ),
            mixin
        )
        assertFalse(mixin.contains("throw "), mixin)
        assertTrue(
            fixture.mixinConfig.readText()
                .contains("\"ModporterDatagenIngredientValueMixin\"")
        )
        assertTrue(
            fixture.accessTransformer.readText().contains(
                "public net.minecraft.world.item.crafting.Ingredient " +
                    "<init>([Lnet/minecraft/world/item/crafting/Ingredient\$Value;)V"
            )
        )

        val second = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )
        assertTrue(second.gates.isEmpty(), second.gates.joinToString("\n"))
        assertTrue(second.changes.isEmpty())
    }

    @Test
    fun `unrelated client-only mixin config does not compete with the common owner`() {
        val fixture = writeFixture(directory.resolve("mixed-common-client-configs"))
        val clientConfig = fixture.project.resolve(
            "src/main/resources/example.modporter-model-data.mixins.json"
        )
        clientConfig.writeText(
            """
            {
              "required": true,
              "minVersion": "0.8",
              "package": "com.modporter.generated.example.compat.mixin",
              "compatibilityLevel": "JAVA_21",
              "client": [
                "LegacyRenderChunkRegionAccessor"
              ],
              "injectors": {
                "defaultRequire": 1
              }
            }
            """.trimIndent() + "\n"
        )
        val clientBefore = clientConfig.readText()

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(5, result.changes.size)
        assertEquals(clientBefore, clientConfig.readText())
        assertTrue(
            fixture.mixinConfig.readText()
                .contains("\"ModporterDatagenIngredientValueMixin\"")
        )
        assertTrue(fixture.generatedMixin.exists())
    }

    @Test
    fun `client-only owning mixin config fails closed before any family write`() {
        val fixture = writeFixture(directory.resolve("client-only-owner"))
        fixture.mixinConfig.writeText(
            """
            {
              "required": true,
              "minVersion": "0.8",
              "package": "example.mod.mixin",
              "compatibilityLevel": "JAVA_21",
              "client": [
                "ExistingMixin"
              ],
              "injectors": {
                "defaultRequire": 1
              }
            }
            """.trimIndent() + "\n"
        )
        val before = listOf(
            fixture.value,
            fixture.caller,
            fixture.mixinConfig,
            fixture.accessTransformer
        ).associateWith { it.readText() }

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any {
                it.contains("Owning mixin config") &&
                    it.contains("no common mixins array")
            },
            result.gates.joinToString("\n")
        )
        before.forEach { (file, source) ->
            assertEquals(source, file.readText())
        }
        assertFalse(fixture.generatedMixin.exists())
    }

    @Test
    fun `dry run reports the complete family without writing any member`() {
        val fixture = writeFixture(directory.resolve("dry-run"))
        val value = fixture.value.readText()
        val caller = fixture.caller.readText()
        val config = fixture.mixinConfig.readText()
        val accessTransformer = fixture.accessTransformer.readText()

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = true
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(5, result.changes.size)
        assertEquals(value, fixture.value.readText())
        assertEquals(caller, fixture.caller.readText())
        assertEquals(config, fixture.mixinConfig.readText())
        assertEquals(accessTransformer, fixture.accessTransformer.readText())
        assertFalse(fixture.generatedMixin.exists())
    }

    @Test
    fun `original Gradle mixin ownership admits pre-build metadata without fabricating it`() {
        val fixture = writeFixture(
            directory.resolve("legacy-gradle-registration"),
            targetMixinRegistration = false,
            legacyMixinRegistrations = listOf("example.mixins.json")
        )
        val oldMetadata = fixture.project.resolve(
            "src/main/resources/META-INF/mods.toml"
        )
        val metadataBefore = oldMetadata.readText()

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(5, result.changes.size)
        assertEquals(metadataBefore, oldMetadata.readText())
        assertFalse(metadataBefore.contains("[[mixins]]"), metadataBefore)
        assertTrue(
            fixture.mixinConfig.readText()
                .contains("\"ModporterDatagenIngredientValueMixin\"")
        )
        assertTrue(fixture.generatedMixin.exists())
    }

    @Test
    fun `ambiguous legacy Gradle mixin ownership leaves the project unchanged`() {
        val fixture = writeFixture(
            directory.resolve("ambiguous-legacy-gradle-registration"),
            targetMixinRegistration = false,
            legacyMixinRegistrations = listOf(
                "example.mixins.json",
                "other.mixins.json"
            )
        )
        val value = fixture.value.readText()
        val caller = fixture.caller.readText()
        val config = fixture.mixinConfig.readText()
        val accessTransformer = fixture.accessTransformer.readText()

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any { it.contains("missing or ambiguous") },
            result.gates.joinToString("\n")
        )
        assertEquals(value, fixture.value.readText())
        assertEquals(caller, fixture.caller.readText())
        assertEquals(config, fixture.mixinConfig.readText())
        assertEquals(accessTransformer, fixture.accessTransformer.readText())
        assertFalse(fixture.generatedMixin.exists())
    }

    @Test
    fun `non-item JSON writer leaves the complete project unchanged`() {
        val fixture = writeFixture(
            directory.resolve("wrong-json-key"),
            valueMutation = {
                it.replace(
                    "jsonobject.addProperty(\"item\",",
                    "jsonobject.addProperty(\"tag\","
                )
            }
        )
        val value = fixture.value.readText()
        val caller = fixture.caller.readText()
        val config = fixture.mixinConfig.readText()
        val accessTransformer = fixture.accessTransformer.readText()

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any { it.contains("literal item property") },
            result.gates.joinToString("\n")
        )
        assertEquals(value, fixture.value.readText())
        assertEquals(caller, fixture.caller.readText())
        assertEquals(config, fixture.mixinConfig.readText())
        assertEquals(accessTransformer, fixture.accessTransformer.readText())
        assertFalse(fixture.generatedMixin.exists())
    }

    @Test
    fun `non Override annotation on JSON writer leaves the complete project unchanged`() {
        val fixture = writeFixture(
            directory.resolve("annotated-json-writer"),
            valueMutation = {
                it.replace("@Override", "@Deprecated")
            }
        )
        val value = fixture.value.readText()
        val caller = fixture.caller.readText()
        val config = fixture.mixinConfig.readText()
        val accessTransformer = fixture.accessTransformer.readText()

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any {
                it.contains("one exact public JsonElement toJson() method")
            },
            result.gates.joinToString("\n")
        )
        assertEquals(value, fixture.value.readText())
        assertEquals(caller, fixture.caller.readText())
        assertEquals(config, fixture.mixinConfig.readText())
        assertEquals(accessTransformer, fixture.accessTransformer.readText())
        assertFalse(fixture.generatedMixin.exists())
    }

    @Test
    fun `constructor consumer outside an Ingredient collection stays gated`() {
        val fixture = writeFixture(
            directory.resolve("wrong-consumer"),
            callerMutation = {
                it.replace(
                    "NonNullList<Ingredient> ingredients",
                    "NonNullList<Object> ingredients"
                )
            }
        )
        val value = fixture.value.readText()
        val caller = fixture.caller.readText()
        val config = fixture.mixinConfig.readText()
        val accessTransformer = fixture.accessTransformer.readText()

        val result = DatagenIngredientValues.migrate(
            StructuralRefactorPass(),
            fixture.project,
            dryRun = false
        )

        assertTrue(result.changes.isEmpty())
        assertTrue(
            result.gates.any { it.contains("Collection<Ingredient>") },
            result.gates.joinToString("\n")
        )
        assertEquals(value, fixture.value.readText())
        assertEquals(caller, fixture.caller.readText())
        assertEquals(config, fixture.mixinConfig.readText())
        assertEquals(accessTransformer, fixture.accessTransformer.readText())
        assertFalse(fixture.generatedMixin.exists())
    }

    private fun writeFixture(
        project: Path,
        valueMutation: (String) -> String = { it },
        callerMutation: (String) -> String = { it },
        targetMixinRegistration: Boolean = true,
        legacyMixinRegistrations: List<String> = emptyList()
    ): Fixture {
        val value = project.resolve(
            "src/main/java/example/mod/data/SimpleDatagenIngredient.java"
        )
        value.parent.createDirectories()
        value.writeText(valueMutation("""
            package example.mod.data;

            import java.util.stream.Stream;

            import com.google.gson.JsonElement;
            import com.google.gson.JsonObject;
            import example.api.DatagenMod;

            import net.minecraft.world.item.crafting.Ingredient;

            public class SimpleDatagenIngredient extends Ingredient {
                private DatagenMod mod;
                private String id;

                public SimpleDatagenIngredient(DatagenMod mod, String id) {
                    super(Stream.empty());
                    this.mod = mod;
                    this.id = id;
                }

                @Override
                public JsonElement toJson() {
                    JsonObject jsonobject = new JsonObject();
                    jsonobject.addProperty("item", mod.asResource(id).toString());
                    return jsonobject;
                }
            }
        """.trimIndent()))

        val api = project.resolve("src/main/java/example/api/DatagenMod.java")
        api.parent.createDirectories()
        api.writeText("""
            package example.api;

            import net.minecraft.resources.ResourceLocation;

            public interface DatagenMod {
                default ResourceLocation asResource(String id) {
                    return ResourceLocation.fromNamespaceAndPath(getId(), id);
                }

                String getId();
            }
        """.trimIndent())

        val caller = project.resolve("src/main/java/example/recipe/Builder.java")
        caller.parent.createDirectories()
        caller.writeText(callerMutation("""
            package example.recipe;

            import example.api.DatagenMod;
            import example.mod.data.SimpleDatagenIngredient;
            import net.minecraft.core.NonNullList;
            import net.minecraft.world.item.crafting.Ingredient;

            public class Builder {
                private Params params = new Params();

                public Builder require(DatagenMod mod, String id) {
                    params.ingredients.add(new SimpleDatagenIngredient(mod, id));
                    return this;
                }

                static class Params {
                    final NonNullList<Ingredient> ingredients = NonNullList.create();
                }
            }
        """.trimIndent()))

        val existingMixin = project.resolve(
            "src/main/java/example/mod/mixin/ExistingMixin.java"
        )
        existingMixin.parent.createDirectories()
        existingMixin.writeText("""
            package example.mod.mixin;

            import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

            final class ExistingMixin {
                private ExistingMixin() {
                }
            }
        """.trimIndent())

        val mixinConfig = project.resolve("src/main/resources/example.mixins.json")
        mixinConfig.parent.createDirectories()
        mixinConfig.writeText("""
            {
              "required": true,
              "minVersion": "0.8",
              "package": "example.mod.mixin",
              "compatibilityLevel": "JAVA_21",
              "mixins": [
                "ExistingMixin"
              ],
              "injectors": {
                "defaultRequire": 1
              }
            }
        """.trimIndent() + "\n")

        val modsToml = project.resolve(
            "src/main/resources/META-INF/" +
                if (targetMixinRegistration) "neoforge.mods.toml" else "mods.toml"
        )
        modsToml.parent.createDirectories()
        val mixinMetadata = if (targetMixinRegistration) {
            "\n\n[[mixins]]\nconfig=\"example.mixins.json\""
        } else {
            ""
        }
        modsToml.writeText("""
            modLoader="javafml"
            loaderVersion="[4,)"
            license="MIT"
        """.trimIndent() + mixinMetadata + "\n")

        if (legacyMixinRegistrations.isNotEmpty()) {
            val registrations = legacyMixinRegistrations.joinToString("\n") {
                "    config \"$it\""
            }
            val buildPrefix = """
                plugins {
                    id "org.spongepowered.mixin"
                }

                Provider<RegularFile> refmap
                mixin {
                    refmap = add(sourceSets.main, "example.refmap.json")
            """.trimIndent()
            project.resolve("build.gradle").writeText(
                buildPrefix + "\n" + registrations + "\n}\n"
            )
        }

        val accessTransformer = project.resolve(
            "src/main/resources/META-INF/accesstransformer.cfg"
        )
        accessTransformer.parent.createDirectories()
        accessTransformer.writeText(
            "public net.minecraft.world.item.crafting.Ingredient values # values\n"
        )
        return Fixture(
            project,
            value,
            caller,
            mixinConfig,
            accessTransformer,
            project.resolve(
                "src/main/java/example/mod/mixin/" +
                    "ModporterDatagenIngredientValueMixin.java"
            )
        )
    }

    private data class Fixture(
        val project: Path,
        val value: Path,
        val caller: Path,
        val mixinConfig: Path,
        val accessTransformer: Path,
        val generatedMixin: Path
    )
}
