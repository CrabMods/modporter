package com.modporter.core.transforms.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildDependencyPreparationTest {
    private val testMappings = "/mappings/dependency-preparation"

    @TempDir
    lateinit var temporary: Path

    @Test
    fun `dependency preparation binds source graph decisions and inert AST receipts`() {
        val stage = ownedStage("complete", removedApiSource(), includeNitrogen = true)
        val unrelated = stage.resolve("src/main/java/com/example/ReflectionProbe.java")
        unrelated.writeText(
            """
            package com.example;

            public final class ReflectionProbe {
                public Class<?> optionalType() throws Exception {
                    return Class.forName("com.example.OptionalApi");
                }
            }
            """.trimIndent()
        )
        val unrelatedBefore = unrelated.readText()

        val receipt = buildSystemPass().prepareDependencies(
            stage,
            listOf(
                sourceDependency(
                    "example.removed:optional-api:1.0.0",
                    'a'
                ),
                sourceDependency(
                    "com.aetherteam.nitrogen:nitrogen_internals:1.20.1-1.0.12-neoforge",
                    'b'
                ),
                sourceDependency(
                    "top.theillusivec4.curios:curios-forge:5.12.0+1.20.1",
                    'c'
                )
            )
        )

        assertTrue(receipt.result.errors.isEmpty(), receipt.result.errors.joinToString())
        assertTrue(receipt.mappingResourceSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(3, receipt.decisions.size)

        val removed = receipt.decisions.single {
            it.kind == BuildDependencyDecisionKind.REMOVE
        }
        assertEquals("example.removed:optional-api", removed.sourceSelector)
        assertTrue(removed.targetCoordinates.isEmpty())
        assertTrue(removed.catalogEntrySha256.matches(Regex("[0-9a-f]{64}")))

        val mapped = receipt.decisions.single {
            it.sourceSelector == "com.aetherteam.nitrogen:nitrogen_internals"
        }
        val target = mapped.targetCoordinates.single()
        assertEquals("implementation", target.configuration)
        assertEquals(
            "com.aetherteam.nitrogen:nitrogen_internals:1.21.1-1.1.25-neoforge",
            target.selector
        )
        assertNull(target.classifier)
        val curios = receipt.decisions.single {
            it.sourceSelector == "top.theillusivec4"
        }
        assertTrue(curios.targetCoordinates.any {
            it.configuration == "compileOnly" &&
                it.classifier == "api" &&
                it.selector ==
                "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1:api"
        })
        assertTrue(curios.targetCoordinates.any {
            it.configuration == "runtimeOnly" &&
                it.classifier == null
        })

        val removal = receipt.removals.single()
        assertEquals(removed.sha256, removal.decisionSha256)
        assertTrue(removal.remainingRemovedPackageReferences.isEmpty())
        assertTrue(removal.inertRewrites.any {
            it.memberSignature.contains("isDynamicBranch") &&
                it.verifiedPostcondition == "default-return-body" &&
                it.afterNodeSha256 != null
        })
        assertTrue(removal.inertRewrites.any {
            it.memberSignature.contains("helper") &&
                it.verifiedPostcondition == "removed-signature-member" &&
                it.afterNodeSha256 == null
        })
        removal.inertRewrites.forEach {
            assertTrue(it.beforeNodeSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(it.afterOwnerSha256.matches(Regex("[0-9a-f]{64}")))
        }

        assertEquals(unrelatedBefore, unrelated.readText())
        assertEquals(
            setOf("src/main/java/com/example/compat/removed/RemovedBridge.java"),
            receipt.changedSourceFiles.map { it.path }.toSet()
        )
        assertTrue(receipt.changedBuildFiles.any { it.path == "build.gradle" })
        assertTrue(receipt.changedBuildFiles.any { it.path == "gradle.properties" })
        assertTrue(receipt.changedBuildFiles.any {
            it.path == "gradle/wrapper/gradle-wrapper.properties"
        })
        assertTrue(stage.resolve("build.gradle").readText().contains("nitrogen_internals:1.21.1"))
        assertFalse(stage.resolve("build.gradle").readText().contains("optional-api"))
        assertTrue(stage.resolve("gradle.properties").readText().contains(
            "nitrogen_version=1.21.1-1.1.25-neoforge"
        ))
        assertTrue(stage.resolve("gradle/wrapper/gradle-wrapper.properties").readText()
            .contains("gradle-8.14.4-bin.zip"))
        assertTrue(receipt.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `dependency preparation discovers reflected optional APIs and keeps classifier variants distinct`() {
        val stage = ownedStage(
            "reflected-api",
            """
            package com.example;

            public final class ReflectionProbe {
                public Class<?> optionalType() throws Exception {
                    return Class.forName("top.theillusivec4.curios.api.CuriosApi");
                }
            }
            """.trimIndent(),
            includeRemovedApi = false
        )
        val source = stage.resolve(
            "src/main/java/com/example/compat/removed/RemovedBridge.java"
        )
        val sourceBefore = source.readText()

        val receipt = buildSystemPass().prepareDependencies(
            stage,
            listOf(
                sourceDependency(
                    "top.theillusivec4.curios:curios-forge:5.12.0+1.20.1",
                    'd'
                ),
                sourceDependency(
                    "top.theillusivec4.curios:curios-forge:5.12.0+1.20.1:api",
                    'e'
                )
            )
        )

        assertEquals(
            setOf(
                "top.theillusivec4.curios:curios-forge:5.12.0+1.20.1",
                "top.theillusivec4.curios:curios-forge:5.12.0+1.20.1:api"
            ),
            receipt.decisions.map { it.source.component }.toSet()
        )
        assertEquals(setOf(null, "api"), receipt.decisions.map { it.source.classifier }.toSet())
        assertEquals(sourceBefore, source.readText())
        assertTrue(receipt.changedSourceFiles.isEmpty())

        val build = stage.resolve("build.gradle").readText()
        assertTrue(build.contains(
            "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1:api"
        ))
        assertTrue(build.contains(
            "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1"
        ))
        assertTrue(build.contains("net.fabricmc:fabric-loader:0.16.14"))
        assertTrue(build.contains("https://maven.theillusivec4.top/"))
    }

    @Test
    fun `dependency preparation migrates and records access transformer metadata`() {
        val stage = ownedStage(
            "access-transformer",
            """
            package com.example;

            public final class AccessProbe {
            }
            """.trimIndent(),
            includeRemovedApi = false
        )
        val source = stage.resolve(
            "src/main/java/com/example/compat/removed/RemovedBridge.java"
        )
        val sourceBefore = source.readText()
        val accessTransformer = stage.resolve(
            "src/main/resources/META-INF/accesstransformer.cfg"
        )
        accessTransformer.parent.createDirectories()
        accessTransformer.writeText(
            "protected net.minecraft.world.level.levelgen.structure.StructurePiece f_73379_\n"
        )

        val receipt = buildSystemPass().prepareDependencies(
            stage,
            listOf(sourceDependency(
                "top.theillusivec4.curios:curios-forge:5.12.0+1.20.1",
                'f'
            ))
        )

        val resourcePath = "src/main/resources/META-INF/accesstransformer.cfg"
        assertEquals(setOf(resourcePath), receipt.resourceFilesBefore.keys)
        assertEquals(setOf(resourcePath), receipt.resourceFilesAfter.keys)
        assertEquals(resourcePath, receipt.changedResourceFiles.single().path)
        assertTrue(accessTransformer.readText().contains(
            "protected net.minecraft.world.level.levelgen.structure.StructurePiece rotation"
        ))
        assertTrue(stage.resolve("build.gradle").readText().contains("accessTransformers"))
        assertEquals(sourceBefore, source.readText())
        assertTrue(receipt.changedSourceFiles.isEmpty())
    }

    @Test
    fun `dependency preparation binds the same specific catalog entry as the build rewrite`() {
        val stage = ownedStage(
            "specific-before-group",
            """
            package com.example.compat.removed;
            import dan200.computercraft.api.peripheral.IPeripheral;
            public final class RemovedBridge { IPeripheral peripheral; }
            """.trimIndent(),
            includeRemovedApi = false
        )
        val build = stage.resolve("build.gradle")
        build.writeText(build.readText() + """

            dependencies {
                compileOnly fg.deobf("cc.tweaked:cc-tweaked-1.20.1-forge:1.105.0")
            }
        """.trimIndent())
        val source = sourceDependency("cc.tweaked:cc-tweaked-1.20.1-forge:1.105.0", 'a')
        val receipt = BuildSystemPass().prepareDependencies(
            stage,
            listOf(source, sourceDependency("example.unmapped:cc-tweaked:1.105.0", 'b'))
        )

        val decision = receipt.decisions.single()
        assertEquals(source, decision.source)
        assertEquals("cc.tweaked:cc-tweaked", decision.sourceSelector)
        assertEquals(BuildDependencyDecisionKind.MAPPED, decision.kind)
        val catalog = Json.parseToJsonElement(
            javaClass.getResource("/mappings/forge2neo/neoforge-deps.json")!!.readText()
        ).jsonObject.getValue("dependencies").jsonArray
        val selectedEntry = catalog.single {
            it.jsonObject.getValue("forgePrefix").jsonPrimitive.content == decision.sourceSelector
        }
        assertEquals(buildReceiptSha256(selectedEntry.toString()), decision.catalogEntrySha256)
        assertTrue(decision.targetCoordinates.any {
            it.configuration == "runtimeOnly" &&
                it.selector == "cc.tweaked:cc-tweaked-1.21.1-forge:1.116.1"
        })
        assertTrue(build.readText().contains("cc.tweaked:cc-tweaked-1.21.1-forge:1.116.1"))
        assertFalse(build.readText().contains("cc-tweaked-1.20.1-forge"))
        assertTrue(receipt.changedSourceFiles.isEmpty())
    }

    @Test
    fun `dependency preparation still rejects competing resolved variants of one component`() {
        val stage = ownedStage("ambiguous-variants", removedApiSource())
        val failure = assertFailsWith<IllegalArgumentException> {
            buildSystemPass().prepareDependencies(
                stage,
                listOf(
                    sourceDependency("example.removed:optional-api:1.0.0", 'a'),
                    sourceDependency("example.removed:optional-api:1.0.0", 'b')
                )
            )
        }
        assertTrue(failure.message.orEmpty().contains("ambiguous resolved variants"))
    }

    @Test
    fun `dependency preparation rejects a stage outside producer ownership`() {
        val stage = temporary.resolve("not-owned").createDirectories()

        val failure = assertFailsWith<IllegalArgumentException> {
            buildSystemPass().prepareDependencies(
                stage,
                listOf(sourceDependency(
                    "example.removed:optional-api:1.0.0",
                    'c'
                ))
            )
        }

        assertTrue(failure.message.orEmpty().contains("producer-owned .modporter/tmp stage"))
    }

    @Test
    fun `dependency preparation rejects wildcard removed dependency aliases`() {
        val stage = ownedStage(
            "wildcard",
            """
            package com.example.compat;

            import example.removed.*;

            public final class Bridge {
                public boolean test(Object value) {
                    return OptionalApi.isPresent(value);
                }
            }
            """.trimIndent()
        )

        val failure = prepareFailure(stage, 'd')

        assertTrue(failure.message.orEmpty().contains("Wildcard removed-dependency imports"))
    }

    @Test
    fun `dependency preparation rejects a shadowed removed type name`() {
        val stage = ownedStage(
            "shadow",
            """
            package com.example.compat;

            import example.removed.OptionalApi;

            public final class Bridge {
                public boolean test(Object value) {
                    int OptionalApi = 0;
                    return OptionalApi > 0;
                }
            }
            """.trimIndent()
        )

        val failure = prepareFailure(stage, 'e')

        assertTrue(failure.message.orEmpty().contains("simple name is shadowed"))
    }

    @Test
    fun `dependency preparation rejects side effects before removed type work`() {
        val stage = ownedStage(
            "side-effect",
            """
            package com.example.compat;

            import example.removed.OptionalApi;

            public final class Bridge {
                public boolean test(Object value) {
                    audit();
                    return OptionalApi.isPresent(value);
                }

                private static void audit() {
                }
            }
            """.trimIndent()
        )

        val failure = prepareFailure(stage, 'f')

        assertTrue(failure.message.orEmpty().contains("unanchored side effect"))
    }

    private fun prepareFailure(stage: Path, hashSeed: Char): IllegalArgumentException =
        assertFailsWith<IllegalArgumentException> {
            buildSystemPass().prepareDependencies(
                stage,
                listOf(sourceDependency(
                    "example.removed:optional-api:1.0.0",
                    hashSeed
                ))
            )
        }

    private fun buildSystemPass(): BuildSystemPass =
        BuildSystemPass(mappingsPrefix = testMappings)

    private fun sourceDependency(
        component: String,
        hashSeed: Char
    ): BuildResolvedSourceDependency =
        BuildResolvedSourceDependency(
            component = component,
            configurations = setOf("compileClasspath"),
            artifactSha256 = hashSeed.toString().repeat(64)
        )

    private fun ownedStage(
        name: String,
        bridgeSource: String,
        includeNitrogen: Boolean = false,
        includeRemovedApi: Boolean = true
    ): Path {
        val stage = temporary.resolve(".modporter/tmp/$name/target").createDirectories()
        val dependencyLines = buildList {
            if (includeRemovedApi) {
                add(
                    "compileOnly fg.deobf(\"example.removed:optional-api:1.0.0\")"
                )
            }
            if (includeNitrogen) {
                add(
                    "implementation fg.deobf(\"com.aetherteam.nitrogen:" +
                        "nitrogen_internals:\${project.nitrogen_version}\")"
                )
                add(
                    "compileOnly fg.deobf(\"top.theillusivec4.curios:" +
                        "curios-forge:\${project.curios_version}:api\")"
                )
            }
        }.joinToString("\n    ")
        stage.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }

            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
                $dependencyLines
            }
            """.trimIndent()
        )
        stage.resolve("settings.gradle").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = 'https://maven.minecraftforge.net/' }
                }
            }
            rootProject.name = 'dependency-stage'
            """.trimIndent()
        )
        stage.resolve("gradle.properties").writeText(
            """
            minecraft_version=1.20.1
            forge_version=47.2.0
            nitrogen_version=1.20.1-1.0.12-neoforge
            curios_version=5.12.0+1.20.1
            mod_id=dependency_stage
            """.trimIndent()
        )
        stage.resolve("gradle/wrapper").createDirectories()
        stage.resolve("gradle/wrapper/gradle-wrapper.properties").writeText(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.6-bin.zip"
        )
        val source = stage.resolve(
            "src/main/java/com/example/compat/removed/RemovedBridge.java"
        )
        source.parent.createDirectories()
        source.writeText(bridgeSource)
        return stage
    }

    private fun removedApiSource(): String =
        """
        package com.example.compat.removed;

        import example.removed.OptionalApi;
        import example.removed.OptionalHandle;
        import example.removed.OptionalShell;

        public final class RemovedBridge {
            public static boolean isDynamicBranch(Object block) {
                return OptionalApi.isPresent(block) || block instanceof OptionalShell;
            }

            public void destroyBlocks(Object world) {
                OptionalHandle start = OptionalApi.get(world);
                if (start == null)
                    return;
                world.toString();
            }

            private OptionalHandle helper(Object world) {
                return OptionalApi.get(world);
            }
        }
        """.trimIndent()
}
