package com.modporter.integration

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.PassResult
import com.modporter.core.pipeline.PipelineResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrictReviewValidationTest {
    @Test
    fun `unknown medium confidence change remains fatal`(@TempDir tempDir: Path) {
        val admission = StrictReviewValidationRegistry.admit(
            repositoryRoot = Path.of("").toAbsolutePath().normalize(),
            inputProjectDir = tempDir.resolve("input"),
            convertedBuildRoot = tempDir.resolve("output"),
            convertedModDir = tempDir.resolve("output"),
            result = pipelineResult(change(tempDir.resolve("Other.java"), Confidence.MEDIUM, "unknown-medium")),
            sourceLabel = "fixture",
            enforceHandsOff = true
        )

        assertEquals(1, admission.rawMediumCount)
        assertEquals(0, admission.pendingCount)
        assertTrue(admission.fatalIssues.any { it.contains("unsupported MEDIUM-confidence") })
    }

    @Test
    fun `low confidence change is never eligible for pending validation`(@TempDir tempDir: Path) {
        val admission = StrictReviewValidationRegistry.admit(
            repositoryRoot = Path.of("").toAbsolutePath().normalize(),
            inputProjectDir = tempDir.resolve("input"),
            convertedBuildRoot = tempDir.resolve("output"),
            convertedModDir = tempDir.resolve("output"),
            result = pipelineResult(change(tempDir.resolve("Other.java"), Confidence.LOW, "unknown-low")),
            sourceLabel = "fixture",
            enforceHandsOff = true
        )

        assertEquals(1, admission.rawLowCount)
        assertEquals(0, admission.pendingCount)
        assertTrue(admission.fatalIssues.any { it.contains("LOW-confidence") })
    }

    @Test
    fun `only the exact package entity source profile becomes pending`(@TempDir tempDir: Path) {
        val fixture = fixture(tempDir)
        val admission = fixture.admit()

        assertTrue(admission.fatalIssues.isEmpty(), admission.fatalIssues.joinToString("; "))
        assertEquals(1, admission.rawMediumCount)
        assertEquals(1, admission.pendingCount)
        assertEquals(StrictSpawnValidationContract.PROFILE_ID, admission.profile?.let { StrictSpawnValidationContract.PROFILE_ID })
    }

    @Test
    fun `packet access transformer support change does not create a second runtime profile`(
        @TempDir tempDir: Path
    ) {
        val fixture = fixture(tempDir)
        val admission = fixture.admit(includeAccessTransformerSupportChange = true)

        assertTrue(admission.fatalIssues.isEmpty(), admission.fatalIssues.joinToString("; "))
        assertEquals(1, admission.rawMediumCount)
        assertEquals(1, admission.pendingCount)
        assertTrue(admission.profile != null)
    }

    @Test
    fun `unclassified same rule change remains fatal`(@TempDir tempDir: Path) {
        val fixture = fixture(tempDir)
        val admission = fixture.admit(includeUnexpectedRuleChange = true)

        assertEquals(1, admission.rawMediumCount)
        assertEquals(1, admission.pendingCount)
        assertTrue(
            admission.fatalIssues.any { it.contains("unclassified") },
            admission.fatalIssues.joinToString("; ")
        )
    }

    @Test
    fun `station validation is admitted with zero medium review changes`(@TempDir tempDir: Path) {
        val fixture = fixture(tempDir)
        fixture.stageStationFamily()

        val admission = fixture.admit(
            packageConfidence = Confidence.HIGH,
            includeStationChange = true
        )

        assertTrue(admission.fatalIssues.isEmpty(), admission.fatalIssues.joinToString("; "))
        assertEquals(0, admission.rawMediumCount)
        assertEquals(0, admission.pendingCount)
        assertTrue(admission.stationMapRequired)
        assertTrue(admission.profile != null)

        val request = checkNotNull(
            StrictReviewValidationRegistry.createRuntimeRequest(
                admission,
                tempDir.resolve("reports")
            ).request
        )
        assertTrue(request.stationMapRequired)

        val missingReceipt = StrictReviewValidationRegistry.verifyFinal(
            admission = admission,
            request = request,
            runtimePassed = true,
            runtimeNote = "runtime returned zero",
            runtimeLog = null
        )
        assertEquals("FAILED", missingReceipt.state)
        assertEquals(0, missingReceipt.unresolvedCount)
        assertTrue(missingReceipt.issues.any { it.contains("strict spawn receipt is missing") })
    }

    @Test
    fun `station validation rejects more than one package entity runtime profile`(
        @TempDir tempDir: Path
    ) {
        val fixture = fixture(tempDir)
        fixture.stageStationFamily()

        val admission = fixture.admit(
            packageConfidence = Confidence.HIGH,
            includeStationChange = true,
            extraPackageCandidates = 1
        )

        assertEquals(0, admission.rawMediumCount)
        assertEquals(0, admission.pendingCount)
        assertTrue(admission.stationMapRequired)
        assertTrue(admission.profile == null)
        assertTrue(
            admission.fatalIssues.any {
                it.contains("requires exactly one closed") && it.contains("runtime=2")
            },
            admission.fatalIssues.joinToString("; ")
        )
    }

    @Test
    fun `missing native receipt leaves the admitted change unresolved`(@TempDir tempDir: Path) {
        val fixture = fixture(tempDir)
        val admission = fixture.admit()
        val preparation = StrictReviewValidationRegistry.createRuntimeRequest(
            admission,
            tempDir.resolve("reports")
        )

        val summary = StrictReviewValidationRegistry.verifyFinal(
            admission = admission,
            request = preparation.request,
            runtimePassed = true,
            runtimeNote = "runtime returned zero",
            runtimeLog = null
        )

        assertEquals("FAILED", summary.state)
        assertEquals(1, summary.unresolvedCount)
        assertTrue(summary.issues.any { it.contains("strict spawn receipt is missing") })
    }

    @Test
    fun `sealed data generation advances only generated resource tree bindings`(@TempDir tempDir: Path) {
        val fixture = fixture(tempDir)
        val request = fixture.runtimeRequest(tempDir.resolve("reports"))
        val before = request.productBindings
        val receipt = fixture.sealDataGenerationEvidence(
            reportsDir = tempDir.resolve("reports"),
            runId = "strict-create-test"
        )

        val refreshed = StrictReviewValidationRegistry
            .refreshGeneratedResourceBindingsAfterDataGeneration(
                request = request,
                runId = "strict-create-test",
                receipt = receipt
            )

        assertTrue(refreshed.issues.isEmpty(), refreshed.issues.joinToString("; "))
        val refreshedRequest = checkNotNull(refreshed.request)
        val changed = before.keys.filter {
            before[it] != refreshedRequest.productBindings[it]
        }.toSet()
        assertEquals(
            setOf(
                "generated.generatedResourcesTree.sha256",
                "generated.generatedAssetsTree.sha256"
            ),
            changed
        )
        assertEquals(refreshedRequest.productBindings, refreshedRequest.profile.productBindings)
        assertTrue(refreshedRequest.bindings.isEmpty())
    }

    @Test
    fun `data generation refresh rejects source and ordinary resource drift`(@TempDir tempDir: Path) {
        val sourceFixture = fixture(tempDir.resolve("source-drift"))
        val sourceReports = tempDir.resolve("source-drift/reports")
        val sourceRequest = sourceFixture.runtimeRequest(sourceReports)
        val sourceReceipt = sourceFixture.sealDataGenerationEvidence(
            sourceReports,
            "strict-create-test"
        )
        sourceFixture.generatedPackageEntity.writeText(
            sourceFixture.generatedPackageEntity.readText() + "\n// source drift\n"
        )

        val sourceRejected = StrictReviewValidationRegistry
            .refreshGeneratedResourceBindingsAfterDataGeneration(
                sourceRequest,
                "strict-create-test",
                sourceReceipt
            )
        assertTrue(sourceRejected.request == null)
        assertTrue(
            sourceRejected.issues.any { it.contains("outside generated data resources") },
            sourceRejected.issues.joinToString("; ")
        )

        val resourceFixture = fixture(tempDir.resolve("resource-drift"))
        val resourceReports = tempDir.resolve("resource-drift/reports")
        val resourceRequest = resourceFixture.runtimeRequest(resourceReports)
        val resourceReceipt = resourceFixture.sealDataGenerationEvidence(
            resourceReports,
            "strict-create-test"
        )
        resourceFixture.output.resolve("src/main/resources/data/create/tampered.json").also {
            it.parent.createDirectories()
            it.writeText("{}\n")
        }

        val resourceRejected = StrictReviewValidationRegistry
            .refreshGeneratedResourceBindingsAfterDataGeneration(
                resourceRequest,
                "strict-create-test",
                resourceReceipt
            )
        assertTrue(resourceRejected.request == null)
        assertTrue(
            resourceRejected.issues.any { it.contains("outside generated data resources") },
            resourceRejected.issues.joinToString("; ")
        )
    }

    @Test
    fun `data generation refresh rejects invalid other-run and incomplete receipts`(@TempDir tempDir: Path) {
        val invalidFixture = fixture(tempDir.resolve("invalid-reference"))
        val invalidReports = tempDir.resolve("invalid-reference/reports")
        val invalidRequest = invalidFixture.runtimeRequest(invalidReports)
        val invalidReceipt = invalidFixture.sealDataGenerationEvidence(
            invalidReports,
            "strict-create-test"
        ).copy(sha256 = "0".repeat(64))

        val invalidRejected = StrictReviewValidationRegistry
            .refreshGeneratedResourceBindingsAfterDataGeneration(
                invalidRequest,
                "strict-create-test",
                invalidReceipt
            )
        assertTrue(invalidRejected.request == null)
        assertTrue(
            invalidRejected.issues.any { it.contains("receipt hash changed") },
            invalidRejected.issues.joinToString("; ")
        )

        val otherFixture = fixture(tempDir.resolve("other-run"))
        val otherReports = tempDir.resolve("other-run/reports")
        val otherRequest = otherFixture.runtimeRequest(otherReports)
        val otherReceipt = otherFixture.sealDataGenerationEvidence(
            otherReports,
            "different-create-run"
        )

        val otherRejected = StrictReviewValidationRegistry
            .refreshGeneratedResourceBindingsAfterDataGeneration(
                otherRequest,
                "strict-create-test",
                otherReceipt
            )
        assertTrue(otherRejected.request == null)
        assertTrue(
            otherRejected.issues.any { it.contains("another benchmark run") },
            otherRejected.issues.joinToString("; ")
        )

        val incompleteFixture = fixture(tempDir.resolve("incomplete-evidence"))
        val incompleteReports = tempDir.resolve("incomplete-evidence/reports")
        val incompleteRequest = incompleteFixture.runtimeRequest(incompleteReports)
        val incompleteReceipt = incompleteFixture.sealDataGenerationEvidence(
            incompleteReports,
            "strict-create-test"
        )
        Files.delete(incompleteFixture.packagedDataMap("waxables.json"))

        val incompleteRejected = StrictReviewValidationRegistry
            .refreshGeneratedResourceBindingsAfterDataGeneration(
                incompleteRequest,
                "strict-create-test",
                incompleteReceipt
            )
        assertTrue(incompleteRejected.request == null)
        assertTrue(
            incompleteRejected.issues.any { it.contains("Packaged Create data map waxables.json is missing") },
            incompleteRejected.issues.joinToString("; ")
        )
    }

    @Test
    fun `staged harness byte drift fails final validation`(@TempDir tempDir: Path) {
        val fixture = fixture(tempDir)
        val admission = fixture.admit()
        val request = StrictReviewValidationRegistry.createRuntimeRequest(
            admission,
            tempDir.resolve("reports")
        ).request!!
        val harnessRoot = fixture.output.resolve("src/main/java/com/example")
        harnessRoot.createDirectories()
        harnessRoot.resolve(StrictSpawnValidationContract.GENERIC_HARNESS_FILE)
            .writeText("package com.example; final class ModPorterClientWorldHarness {}\n")
        val strictHarness = harnessRoot.resolve(StrictSpawnValidationContract.GENERATED_HARNESS_FILE)
        strictHarness.writeText("package com.example; final class ModPorterStrictSpawnHarness {}\n")
        val sealed = StrictReviewValidationRegistry.sealRuntimeManifest(request)
        assertTrue(sealed.issues.isEmpty(), sealed.issues.joinToString("; "))

        strictHarness.writeText("package com.example; final class ModPorterStrictSpawnHarness { int drift; }\n")
        val summary = StrictReviewValidationRegistry.verifyFinal(
            admission = admission,
            request = sealed.request,
            runtimePassed = true,
            runtimeNote = "runtime returned zero",
            runtimeLog = null
        )

        assertEquals("FAILED", summary.state)
        assertEquals(1, summary.unresolvedCount)
        assertTrue(summary.issues.any { it.contains("Staged runtime harness changed") })
    }

    @Test
    fun `tracker exit receipt accepts one margin past the native tracking boundary`() {
        val issues = StrictReviewValidationRegistry.verifyTrackerExitTrace(trackerExitScenario())
        assertTrue(issues.isEmpty(), issues.joinToString("; "))
    }

    @Test
    fun `tracker exit receipt rejects missing exit evidence`() {
        val issues = StrictReviewValidationRegistry.verifyTrackerExitTrace(trackerExitScenario(includeExit = false))
        assertTrue(issues.any { it.contains("exit observations are missing") }, issues.joinToString("; "))
    }

    @Test
    fun `tracker exit receipt rejects an exit inside the tracking range`() {
        val issues = StrictReviewValidationRegistry.verifyTrackerExitTrace(trackerExitScenario(exitDistance = 170.0))
        assertTrue(issues.any { it.contains("one margin past the native tracking boundary") }, issues.joinToString("; "))
    }

    @Test
    fun `tracker exit receipt rejects an effective range that is not the native minimum`() {
        val issues = StrictReviewValidationRegistry.verifyTrackerExitTrace(trackerExitScenario(effectiveRange = 192.0))
        assertTrue(issues.any { it.contains("min(scaledRangeBlocks, viewDistanceBlocks)") }, issues.joinToString("; "))
    }

    @Test
    fun `tracker exit receipt rejects a destination the player did not hold`() {
        val issues = StrictReviewValidationRegistry.verifyTrackerExitTrace(trackerExitScenario(resident = false))
        assertTrue(issues.any { it.contains("must be resident") }, issues.joinToString("; "))
    }

    private fun trackerExitScenario(
        includeExit: Boolean = true,
        effectiveRange: Double = 160.0,
        exitDistance: Double = 176.0,
        resident: Boolean = true
    ): JsonObject = buildJsonObject {
        put("observations", buildJsonObject {
            put("tracker", buildJsonObject {
                if (includeExit) {
                    put("exit", buildJsonObject {
                        put("typeRangeBlocks", 160)
                        put("scaledRangeBlocks", 160)
                        put("viewDistanceBlocks", 192)
                        put("effectiveRangeBlocks", effectiveRange)
                        put("exitDistanceBlocks", exitDistance)
                        put("marginBlocks", 16.0)
                        put("destinationResident", resident)
                    })
                }
            })
        })
    }

    private data class Fixture(
        val input: Path,
        val output: Path,
        val generatedPackageEntity: Path
    ) {
        fun admit(
            packageConfidence: Confidence = Confidence.MEDIUM,
            includeStationChange: Boolean = false,
            extraPackageCandidates: Int = 0,
            includeAccessTransformerSupportChange: Boolean = false,
            includeUnexpectedRuleChange: Boolean = false
        ): StrictReviewAdmission {
            val packageChange = Change(
                file = generatedPackageEntity,
                line = 130,
                description = "Preserve raw-join and deferred-lerp phases on the native spawn lifecycle",
                before = "setCustomClientFactory(PackageEntity::spawn)",
                after = "registered EntityFactory + recreateFromPacket + real readSpawnData prefix",
                confidence = packageConfidence,
                ruleId = StrictSpawnValidationContract.RULE_ID
            )
            val changes = buildList {
                add(packageChange)
                repeat(extraPackageCandidates) {
                    add(packageChange.copy(line = packageChange.line + it + 1))
                }
                if (includeAccessTransformerSupportChange) {
                    val lines = listOf("xa", "ya", "za").map {
                        "public net.minecraft.network.protocol.game.ClientboundAddEntityPacket $it"
                    }
                    add(
                        Change(
                            file = output.resolve("src/main/resources/META-INF/accesstransformer.cfg"),
                            line = 1,
                            description =
                                "Expose raw native add-entity packet velocity for legacy spawn order",
                            before = lines.joinToString(" + ") { "missing $it" },
                            after = lines.joinToString(" + "),
                            confidence = Confidence.HIGH,
                            ruleId = StrictSpawnValidationContract.RULE_ID
                        )
                    )
                }
                if (includeUnexpectedRuleChange) {
                    add(
                        Change(
                            file = output.resolve("src/main/java/com/example/OtherEntity.java"),
                            line = 1,
                            description = "unexpected same-rule change",
                            before = "before",
                            after = "after",
                            confidence = Confidence.HIGH,
                            ruleId = StrictSpawnValidationContract.RULE_ID
                        )
                    )
                }
                if (includeStationChange) {
                    add(
                        Change(
                            file = output.resolve(
                                "src/main/java/com/simibubi/create/foundation/map/" +
                                    "StationMapDecorationRenderer.java"
                            ),
                            line = 1,
                            description = "Generate the native Create station map decoration family",
                            before = "legacy station map decoration",
                            after = "native map decoration type, renderer and registration",
                            confidence = Confidence.HIGH,
                            ruleId = StrictSpawnValidationContract.STATION_RULE_ID
                        )
                    )
                }
            }
            return StrictReviewValidationRegistry.admit(
                repositoryRoot = Path.of("").toAbsolutePath().normalize(),
                inputProjectDir = input,
                convertedBuildRoot = output,
                convertedModDir = output,
                result = pipelineResult(*changes.toTypedArray()),
                sourceLabel = "local-fixture",
                enforceHandsOff = true
            )
        }

        fun runtimeRequest(reportsDir: Path): StrictReviewRuntimeRequest {
            val admission = admit()
            assertTrue(admission.fatalIssues.isEmpty(), admission.fatalIssues.joinToString("; "))
            return checkNotNull(
                StrictReviewValidationRegistry.createRuntimeRequest(
                    admission,
                    reportsDir
                ).request
            )
        }

        fun sealDataGenerationEvidence(
            reportsDir: Path,
            runId: String
        ): CreateDataGenerationEvidence.ReceiptReference {
            reportsDir.createDirectories()
            val runDataLog = reportsDir.resolve("create-runData.log")
            val processResourcesLog = reportsDir.resolve("create-processResources.log")
            val runDataLeaseReceipt = reportsDir.resolve("create-runData-session.json")
            runDataLog.writeText("runData\nBUILD SUCCESSFUL\n")
            processResourcesLog.writeText("processResources\nBUILD SUCCESSFUL\n")

            val projectRoot = output.toAbsolutePath().normalize()
            val runtimeDirectory = projectRoot.resolve("run")
            val launcherSource = launcherSource(projectRoot, runtimeDirectory)
            val launcher = projectRoot.resolve(
                "build/minecraft-mod-testing/nested-launchers/" +
                    "command-${sha256(launcherSource.toByteArray())}.ps1"
            )
            launcher.parent.createDirectories()
            launcher.writeBytes(launcherSource.toByteArray())
            runDataLeaseReceipt.writeText(
                """
                {
                  "schemaVersion": 1,
                  "sessionId": "strict-review-data-generation-test",
                  "projectRoot": ${jsonString(projectRoot.toString())},
                  "runtimeDirectory": ${jsonString(runtimeDirectory.toString())},
                  "runtimeKind": "server",
                  "processStartCount": 1,
                  "processInventoryChecked": true,
                  "activeRuntimeProcesses": [],
                  "command": {
                    "executable": ${jsonString(launcher.toString())},
                    "arguments": []
                  },
                  "exitCode": 0,
                  "passed": true,
                  "failure": ""
                }
                """.trimIndent() + "\n"
            )

            for ((name, count) in mapOf("oxidizables.json" to 18, "waxables.json" to 24)) {
                val generated = generatedDataMap(name)
                val packaged = packagedDataMap(name)
                generated.parent.createDirectories()
                packaged.parent.createDirectories()
                generated.writeText(dataMapJson(count))
                packaged.writeText(generated.readText())
            }
            output.resolve("src/generated/resources/assets/create/generated-test.txt").also {
                it.parent.createDirectories()
                it.writeText("generated by native runData\n")
            }

            return CreateDataGenerationEvidence.seal(
                runId = runId,
                caseId = "create",
                buildRoot = output,
                modDir = output,
                reportsDir = reportsDir,
                runDataLog = runDataLog,
                runDataLeaseReceipt = runDataLeaseReceipt,
                processResourcesLog = processResourcesLog
            )
        }

        fun packagedDataMap(name: String): Path =
            output.resolve("build/resources/main/data/neoforge/data_maps/block/$name")

        private fun generatedDataMap(name: String): Path =
            output.resolve("src/generated/resources/data/neoforge/data_maps/block/$name")

        fun stageStationFamily() {
            writeStationSource(
                "src/main/java/com/simibubi/create/AllMapDecorationTypes.java",
                """
                final class AllMapDecorationTypes {
                    static final Object STATION_MAP_DECORATION = null;
                    Object value = DeferredRegister.create(Registries.MAP_DECORATION_TYPE, "create");
                }
                """.trimIndent()
            )
            writeStationSource(
                "src/main/java/com/simibubi/create/content/trains/station/StationMapData.java",
                "interface StationMapData { void addStationMarker(Object marker); }\n"
            )
            writeStationSource(
                "src/main/java/com/simibubi/create/foundation/map/StationMapDecorationRenderer.java",
                """
                final class StationMapDecorationRenderer implements IMapDecorationRenderer {
                    public boolean render(Object decoration) { return true; }
                }
                """.trimIndent()
            )
            writeStationSource(
                "src/main/java/com/simibubi/create/foundation/map/StationMapDecorationEvents.java",
                """
                final class StationMapDecorationEvents {
                    RegisterMapDecorationRenderersEvent event;
                    Object renderer = new StationMapDecorationRenderer();
                }
                """.trimIndent()
            )
            val texture = output.resolve(
                "src/main/resources/assets/create/textures/map/decorations/station.png"
            )
            texture.parent.createDirectories()
            texture.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
        }

        private fun writeStationSource(relative: String, source: String) {
            val path = output.resolve(relative)
            path.parent.createDirectories()
            path.writeText(source)
        }
    }

    private fun fixture(root: Path): Fixture {
        val input = root.resolve("input")
        val output = root.resolve("output")
        val relative = Path.of("src/main/java/com/simibubi/create/content/logistics/box/PackageEntity.java")
        val inputPackageEntity = input.resolve(relative)
        val generatedPackageEntity = output.resolve(relative)
        inputPackageEntity.parent.createDirectories()
        generatedPackageEntity.parent.createDirectories()
        inputPackageEntity.writeText(
            """
            package com.simibubi.create.content.logistics.box;
            public class PackageEntity extends LivingEntity implements IEntityAdditionalSpawnData {
              Object register(Object boxBuilder) { return boxBuilder.setCustomClientFactory(PackageEntity::spawn).sized(1, 1); }
              public PackageEntity(Level worldIn, double x, double y, double z) { this(AllEntityTypes.PACKAGE.get(), worldIn); this.setPos(x, y, z); this.refreshDimensions(); }
              public static PackageEntity spawn(SpawnEntity spawnEntity, Level world) { PackageEntity packageEntity = new PackageEntity(world, spawnEntity.getPosX(), spawnEntity.getPosY(), spawnEntity.getPosZ()); packageEntity.setDeltaMovement(spawnEntity.getVelX(), spawnEntity.getVelY(), spawnEntity.getVelZ()); packageEntity.clientPosition = packageEntity.position(); return packageEntity; }
              public void lerpMotion(double x, double y, double z) { setDeltaMovement(getDeltaMovement().add(x, y, z).scale(.5f)); }
              public static PackageEntity fromItemStack(Level world, Vec3 position, ItemStack itemstack) { PackageEntity packageEntity = AllEntityTypes.PACKAGE.get().create(world); packageEntity.setPos(position); packageEntity.setBox(itemstack); return packageEntity; }
              public void writeSpawnData(FriendlyByteBuf buffer) { buffer.writeItem(getBox()); Vec3 motion = getDeltaMovement(); buffer.writeFloat((float) motion.x); buffer.writeFloat((float) motion.y); buffer.writeFloat((float) motion.z); }
              public void readSpawnData(FriendlyByteBuf additionalData) { setBox(additionalData.readItem()); setDeltaMovement(additionalData.readFloat(), additionalData.readFloat(), additionalData.readFloat()); }
              public void setBox(ItemStack box) { this.box = box.copy(); refreshDimensions(); }
            }
            """.trimIndent()
        )
        generatedPackageEntity.writeText(
            """
            package com.simibubi.create.content.logistics.box;
            import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
            public class PackageEntity extends LivingEntity implements IEntityWithComplexSpawn {
              private boolean modporterLegacySpawnMotionPending;
              private double modporterLegacySpawnMotionX;
              private double modporterLegacySpawnMotionY;
              private double modporterLegacySpawnMotionZ;
              public void recreateFromPacket(ClientboundAddEntityPacket packet) { setPos(packet.getX(), packet.getY(), packet.getZ()); refreshDimensions(); clientPosition = position(); super.recreateFromPacket(packet); setDeltaMovement(packet.xa, packet.ya, packet.za); this.modporterLegacySpawnMotionX = packet.getXa(); this.modporterLegacySpawnMotionY = packet.getYa(); this.modporterLegacySpawnMotionZ = packet.getZa(); this.modporterLegacySpawnMotionPending = true; }
              public void readSpawnData(RegistryFriendlyByteBuf additionalData) { if (this.modporterLegacySpawnMotionPending) { this.modporterLegacySpawnMotionPending = false; lerpMotion(this.modporterLegacySpawnMotionX, this.modporterLegacySpawnMotionY, this.modporterLegacySpawnMotionZ); } setBox(ItemStack.OPTIONAL_STREAM_CODEC.decode(additionalData)); setDeltaMovement(additionalData.readFloat(), additionalData.readFloat(), additionalData.readFloat()); }
              public void writeSpawnData(RegistryFriendlyByteBuf buffer) { ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, getBox()); Vec3 motion = getDeltaMovement(); buffer.writeFloat((float) motion.x); buffer.writeFloat((float) motion.y); buffer.writeFloat((float) motion.z); }
              public void lerpMotion(double x, double y, double z) { setDeltaMovement(getDeltaMovement().add(x, y, z).scale(.5f)); }
              public void setBox(ItemStack box) { this.box = box.copy(); refreshDimensions(); }
            }
            """.trimIndent()
        )
        val metaInf = output.resolve("src/main/resources/META-INF")
        metaInf.createDirectories()
        metaInf.resolve("accesstransformer.cfg").writeText(
            """
            public net.minecraft.network.protocol.game.ClientboundAddEntityPacket xa
            public net.minecraft.network.protocol.game.ClientboundAddEntityPacket ya
            public net.minecraft.network.protocol.game.ClientboundAddEntityPacket za
            """.trimIndent()
        )
        output.resolve("build.gradle.kts").writeText(
            """
            neoForge {
                version = "21.1.230"
                accessTransformers {
                    from("src/main/resources/META-INF/accesstransformer.cfg")
                }
                runs {
                    configureEach {
                        systemProperty("minecraftVersion", "1.21.1")
                    }
                }
            }
            val minecraftVersion = "1.21.1"
            """.trimIndent()
        )
        output.resolve("gradle.properties").writeText(
            "minecraft_version=1.21.1\nneo_forge_version=21.1.230\n"
        )
        return Fixture(input, output, generatedPackageEntity)
    }

    private companion object {
        fun dataMapJson(count: Int): String = buildString {
            append("{\"values\":{")
            repeat(count) { index ->
                if (index > 0) append(',')
                append("\"create:entry-$index\":{\"block\":\"create:target-$index\"}")
            }
            append("}}\n")
        }

        fun launcherSource(projectRoot: Path, runtimeDirectory: Path): String =
            listOf(
                "runData",
                "--no-daemon",
                "--stacktrace",
                "--console=plain",
                "-PminecraftModTestingRuntimeDirectory=$runtimeDirectory"
            ).joinToString(",\n") { "    ${powerShellLiteral(it)}" }.let { arguments ->
                """
                    ${'$'}ErrorActionPreference = 'Stop'
                    & 'preflight.ps1' -Guard 'runtime_process_guard.ps1' -ExpectedGuardSha256 'fixture' -Scope ${powerShellLiteral(projectRoot.toString())}
                    if (${'$'}LASTEXITCODE -ne 0) { exit ${'$'}LASTEXITCODE }
                    ${'$'}modporterExecutable = ${powerShellLiteral(projectRoot.resolve("gradlew.bat").toString())}
                    ${'$'}modporterArguments = @(
                    $arguments
                    )
                    & 'hidden-desktop.ps1' -Executable ${'$'}modporterExecutable -ArgumentList ${'$'}modporterArguments -DesktopName "modporter-hidden-${'$'}PID"
                    if (${'$'}null -ne ${'$'}LASTEXITCODE) { exit ${'$'}LASTEXITCODE }
                """.trimIndent() + "\n"
            }

        fun jsonString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun powerShellLiteral(value: String): String =
            "'${value.replace("'", "''")}'"

        fun sha256(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun change(file: Path, confidence: Confidence, ruleId: String): Change = Change(
            file = file,
            line = 1,
            description = "fixture",
            before = "before",
            after = "after",
            confidence = confidence,
            ruleId = ruleId
        )

        fun pipelineResult(vararg changes: Change): PipelineResult = PipelineResult(
            passResults = listOf(PassResult("fixture", changes.toList())),
            dryRun = false
        )
    }
}
