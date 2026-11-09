package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeBytes

/**
 * Log-only implementation draft for the removed Forge looting-policy migration.
 *
 * The transformer has two explicit phases:
 *  1. [preflight] parses the complete project source/config graph, verifies pinned
 *     source/target artifacts, and prepares every intended mutation.
 *  2. [apply] re-verifies the complete source/config/artifact snapshot, stages every
 *     output, and commits with rollback. It never discovers new facts while writing.
 */
internal object LegacyLootingPolicyMigration {
    const val RULE_ID = "struct-legacy-looting-policy"
    private const val LEGACY_PLATFORM_SHA256 =
        "a1270c7d64018ba097add19d5dcee698abf8eb9971c8d5f91c7f1732e1e84ff3"
    private const val TARGET_PLATFORM_SHA256 =
        "92552fdc76a29894e7e6af44d0a06a10ed0e2c0e7b25545287144b5906e4a16b"
    private const val TARGET_SOURCES_SHA256 =
        "830d5991464c1c43d6960c2aaae6b2e3e61f83021aa192ae3783d47317d806f4"
    private const val MIXIN_EXTRAS_SHA256 =
        "9822e773bd9f42d36ed53ea3d67486207291747a7bfacc29beeb92040721bc9b"
    private const val NATIVE_LOOTING_SHAPE =
        "one EQUIPMENT_DROPS ATTACKER->VICTIM AddValue(" +
            "LevelBasedValue.Linear(0.01F,0.01F)) with exact attacking-player requirement"

    private const val DAMAGE_SOURCE = "net.minecraft.world.damagesource.DamageSource"
    private val LEGACY_EVENTS = setOf(
        "net.minecraftforge.event.entity.living.LootingLevelEvent",
        "net.neoforged.neoforge.event.entity.living.LootingLevelEvent"
    )
    private const val LEGACY_FUNCTION =
        "net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction"
    private const val TARGET_FUNCTION =
        "net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction"
    private val LIVING_DROPS_EVENTS = setOf(
        "net.minecraftforge.event.entity.living.LivingDropsEvent",
        "net.neoforged.neoforge.event.entity.living.LivingDropsEvent"
    )
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val LOOT_CONTEXT = "net.minecraft.world.level.storage.loot.LootContext"
    private const val CALLBACK_INFO_RETURNABLE =
        "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable"
    private const val RESOURCE_KEY = "net.minecraft.resources.ResourceKey"
    private const val DAMAGE_TYPE = "net.minecraft.world.damagesource.DamageType"
    private const val REGISTRY = "net.minecraft.core.Registry"
    private const val BOOTSTRAP_CONTEXT = "net.minecraft.data.worldgen.BootstrapContext"
    private const val LEGACY_BOOTSTRAP_CONTEXT =
        "net.minecraft.data.worldgen.BootstapContext"
    private const val LEGACY_BOOTSTRAP_CONTEXT_CLASS =
        "net/minecraft/data/worldgen/BootstapContext.class"
    private const val LEGACY_BOOTSTRAP_CONTEXT_SHA256 =
        "779140df5573d6c37af423e1590289523e88b360612249687c4ab890597daa9c"

    private const val MOB_CLASS = "net/minecraft/world/entity/Mob.class"
    private const val HELPER_CLASS =
        "net/minecraft/world/item/enchantment/EnchantmentHelper.class"
    private const val VISITOR_CLASS =
        "net/minecraft/world/item/enchantment/EnchantmentHelper\$EnchantmentInSlotVisitor.class"
    private const val COUNT_FUNCTION_CLASS =
        "net/minecraft/world/level/storage/loot/functions/EnchantedCountIncreaseFunction.class"
    private const val MIXIN_EXTRAS_OPERATION_CLASS =
        "com/llamalad7/mixinextras/injector/wrapoperation/Operation.class"
    private const val MIXIN_EXTRAS_WRAP_OPERATION_CLASS =
        "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation.class"
    private const val MIXIN_EXTRAS_WRAP_INFO_CLASS =
        "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationInjectionInfo.class"
    private const val MIXIN_EXTRAS_WRAP_INJECTOR_CLASS =
        "com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationInjector.class"

    private const val MOB_METHOD =
        "(Lnet/minecraft/server/level/ServerLevel;" +
            "Lnet/minecraft/world/damagesource/DamageSource;Z)V"
    private const val EQUIPMENT_CHANCE_METHOD =
        "(Lnet/minecraft/server/level/ServerLevel;" +
            "Lnet/minecraft/world/entity/LivingEntity;" +
            "Lnet/minecraft/world/damagesource/DamageSource;F)F"
    private const val ITERATION_METHOD =
        "(Lnet/minecraft/world/entity/LivingEntity;" +
            "Lnet/minecraft/world/item/enchantment/EnchantmentHelper\$EnchantmentInSlotVisitor;)V"
    private const val VISITOR_ACCEPT =
        "(Lnet/minecraft/core/Holder;I" +
            "Lnet/minecraft/world/item/enchantment/EnchantedItemInUse;)V"
    private const val VISITOR_ACCEPT_SIGNATURE =
        "(Lnet/minecraft/core/Holder<Lnet/minecraft/world/item/enchantment/Enchantment;>;I" +
            "Lnet/minecraft/world/item/enchantment/EnchantedItemInUse;)V"
    private const val LEGACY_CUSTOM_DEATH_METHOD =
        "(Lnet/minecraft/world/damagesource/DamageSource;IZ)V"
    private const val LEGACY_DROP_ALL_METHOD =
        "(Lnet/minecraft/world/damagesource/DamageSource;)V"
    private const val ENCHANTMENT_HOLDER_SIGNATURE =
        "Lnet/minecraft/core/Holder<Lnet/minecraft/world/item/enchantment/Enchantment;>;"

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    data class Inputs(
        val project: Path,
        val templates: Templates = Templates.production(),
        val legacyPlatformJar: Path? = null,
        val targetPlatformJar: Path? = null,
        val targetSourcesJar: Path? = null,
        val mixinExtrasJar: Path? = null
    )

    data class Templates(
        val policy: String,
        val mobMixin: String,
        val enchantmentHelperMixin: String
    ) {
        companion object {
            fun load(directory: Path): Templates = Templates(
                policy = directory.resolve("Policy.java.template").readText(),
                mobMixin = directory.resolve("MobMixin.java.template").readText(),
                enchantmentHelperMixin =
                    directory.resolve("EnchantmentHelperMixin.java.template").readText()
            )

            fun production(): Templates = Templates(
                policy = POLICY_TEMPLATE,
                mobMixin = MOB_MIXIN_TEMPLATE,
                enchantmentHelperMixin = HELPER_MIXIN_TEMPLATE
            )
        }
    }

    data class ArtifactEvidence(
        val legacyPlatformSha256: String,
        val targetPlatformSha256: String,
        val targetSourcesSha256: String,
        val mixinExtrasSha256: String,
        val mobNextFloatCalls: Int,
        val helperIterationCalls: Int,
        val visitorPublic: Boolean,
        val legacyBootstrapContextAbi: Boolean,
        val nativeLootingShape: String,
        val verificationBasis: String
    )

    fun interface ArtifactVerifier {
        fun verify(inputs: Inputs): ArtifactEvidence
    }

    data class PolicyEvidence(
        val sourceFile: String,
        val ownerClass: String,
        val methodName: String,
        val keyExpression: String,
        val level: Int,
        val damageProducer: String,
        val terminalHurtCalls: Int
    )

    data class MixinEvidence(
        val sourceFile: String,
        val className: String,
        val configFile: String,
        val configEntry: String,
        val injectedMethod: String,
        val valueField: String,
        val limitField: String,
        val hasLimitMethod: String
    )

    data class Evidence(
        val policy: PolicyEvidence,
        val mixin: MixinEvidence,
        val artifacts: ArtifactEvidence,
        val generatedTypes: List<String>,
        val generatedMethods: List<String>
    )

    data class GuardedFile(val relativePath: String, val sha256: String)

    data class ArtifactGuard(val path: Path, val sha256: String)

    class Mutation(
        val relativePath: String,
        val beforeSha256: String?,
        after: ByteArray?
    ) {
        private val payload: ByteArray? = after?.copyOf()
        val after: ByteArray?
            get() = payload?.copyOf()
    }

    data class Plan(
        val project: Path,
        val guardedFiles: List<GuardedFile>,
        val artifactGuards: List<ArtifactGuard>,
        val mutations: List<Mutation>,
        val evidence: Evidence
    )

    fun migrate(
        pass: StructuralRefactorPass,
        project: Path,
        dryRun: Boolean
    ): List<Change> {
        val root = project.resolve("src/main/java")
        if (!Files.isDirectory(root) || !containsOwnedLegacyPolicy(pass, root)) return emptyList()
        val inputs = LegacyLootingArtifactInputs.resolve(project)
        val plan = preflight(inputs)
        val changes = plan.mutations.map { mutation ->
            Change(
                file = resolveInside(plan.project, mutation.relativePath),
                line = 1,
                description = when {
                    mutation.relativePath.endsWith(".mixins.json") ->
                        "Legacy looting hooks are registered in the owning mixin configuration"
                    mutation.relativePath.endsWith("legacy-looting-migration.json") ->
                        "Legacy looting source and target-hook provenance is recorded"
                    mutation.beforeSha256 == null ->
                        "Legacy looting policy is preserved through generated target hooks"
                    else ->
                        "Legacy looting event and count decorator migrate to target APIs"
                },
                before = if (mutation.beforeSha256 == null) "absent" else "legacy looting source",
                after = if (mutation.after == null) "removed" else "target looting policy",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
        if (!dryRun) apply(plan)
        return changes
    }

    fun preflight(
        inputs: Inputs,
        artifactVerifier: ArtifactVerifier = PinnedArtifactVerifier
    ): Plan {
        val project = inputs.project.toAbsolutePath().normalize()
        require(project.isDirectory()) { "Project root does not exist: $project" }

        val snapshot = ProjectSnapshot.read(project)
        val artifacts = artifactVerifier.verify(inputs)
        check(artifacts.mobNextFloatCalls == 1) {
            "Artifact verifier did not prove exactly one Mob nextFloat hook"
        }
        check(artifacts.helperIterationCalls == 2) {
            "Artifact verifier did not prove exactly two named equipment iterations"
        }
        check(artifacts.visitorPublic) {
            "Artifact verifier did not prove the nested equipment visitor is public"
        }
        check(artifacts.nativeLootingShape == NATIVE_LOOTING_SHAPE) {
            "Artifact verifier did not prove the exact native Looting equipment shape"
        }
        val source = SourceAdmission(snapshot, artifacts)
        val policy = source.capturePolicy()
        val countMixin = source.captureCountMixin(policy)
        val mixinConfig = source.captureMixinConfig(countMixin)
        val graph = source.verifyCompleteConsumerGraph(policy, countMixin)

        val names = GeneratedNames.allocate(snapshot, countMixin.packageName)
        source.verifyMixinPlugin(mixinConfig, countMixin.packageName, names)
        val policySource = renderTemplate(
            inputs.templates.policy,
            mapOf(
                "PACKAGE" to countMixin.packageName,
                "POLICY_TYPE" to names.policyType,
                "KEY_IMPORTS" to "",
                "KEY_EXPRESSION" to policy.keyExpression,
                "LEVEL" to policy.level.toString()
            )
        )
        val mobMixinSource = renderTemplate(
            inputs.templates.mobMixin,
            mapOf(
                "PACKAGE" to countMixin.packageName,
                "MOB_MIXIN_TYPE" to names.mobMixinType,
                "POLICY_TYPE" to names.policyType,
                "MOB_HANDLER" to names.mobHandler
            )
        )
        val helperMixinSource = renderTemplate(
            inputs.templates.enchantmentHelperMixin,
            mapOf(
                "PACKAGE" to countMixin.packageName,
                "HELPER_MIXIN_TYPE" to names.helperMixinType,
                "POLICY_TYPE" to names.policyType,
                "HELPER_HANDLER" to names.helperHandler
            )
        )
        validateGeneratedJava("policy", policySource)
        validateGeneratedJava("Mob mixin", mobMixinSource)
        validateGeneratedJava("EnchantmentHelper mixin", helperMixinSource)

        val transformedHandler = source.removePolicyHandler(policy)
        val transformedCountMixin = source.transformCountMixin(countMixin)
        val transformedConfig = transformMixinConfig(
            mixinConfig,
            countMixin.packageName,
            names.mobMixinType,
            names.helperMixinType
        )

        val javaRoot = countMixin.javaRoot
        val generatedPackagePath = countMixin.packageName.replace('.', '/')
        val policyPath =
            javaRoot.resolve(generatedPackagePath).resolve("${names.policyType}.java")
        val mobMixinPath =
            javaRoot.resolve(generatedPackagePath).resolve("${names.mobMixinType}.java")
        val helperMixinPath =
            javaRoot.resolve(generatedPackagePath).resolve("${names.helperMixinType}.java")
        listOf(policyPath, mobMixinPath, helperMixinPath).forEach {
            check(!it.exists()) { "Generated source collision after allocation: $it" }
        }

        val policyEvidence = PolicyEvidence(
            sourceFile = snapshot.relative(policy.file),
            ownerClass = policy.ownerClass,
            methodName = policy.methodName,
            keyExpression = policy.keyExpression,
            level = policy.level,
            damageProducer = graph.producer,
            terminalHurtCalls = graph.terminalHurtCalls
        )
        val mixinEvidence = MixinEvidence(
            sourceFile = snapshot.relative(countMixin.file),
            className = countMixin.className,
            configFile = snapshot.relative(mixinConfig.file),
            configEntry = mixinConfig.entry,
            injectedMethod = countMixin.injectedMethod,
            valueField = countMixin.valueField,
            limitField = countMixin.limitField,
            hasLimitMethod = countMixin.hasLimitMethod
        )
        fun generatedCanonical(simple: String): String =
            if (countMixin.packageName.isBlank()) simple
            else "${countMixin.packageName}.$simple"
        val evidence = Evidence(
            policy = policyEvidence,
            mixin = mixinEvidence,
            artifacts = artifacts,
            generatedTypes = listOf(
                generatedCanonical(names.policyType),
                generatedCanonical(names.mobMixinType),
                generatedCanonical(names.helperMixinType)
            ),
            generatedMethods = listOf(names.mobHandler, names.helperHandler)
        )

        val evidencePath = project.resolve(".modporter/evidence/legacy-looting-migration.json")
        check(!evidencePath.exists()) {
            "Evidence output already exists; refusing to overwrite provenance: $evidencePath"
        }

        val mutations = listOf(
            mutation(snapshot, policy.file, transformedHandler.toByteArray(StandardCharsets.UTF_8)),
            mutation(
                snapshot,
                countMixin.file,
                transformedCountMixin.toByteArray(StandardCharsets.UTF_8)
            ),
            mutation(
                snapshot,
                mixinConfig.file,
                transformedConfig.toByteArray(StandardCharsets.UTF_8)
            ),
            newMutation(snapshot, policyPath, policySource),
            newMutation(snapshot, mobMixinPath, mobMixinSource),
            newMutation(snapshot, helperMixinPath, helperMixinSource),
            newMutation(snapshot, evidencePath, renderEvidence(evidence))
        )

        check(mutations.map { it.relativePath }.distinct().size == mutations.size) {
            "Migration planned the same path more than once"
        }
        return Plan(
            project = project,
            guardedFiles = snapshot.guardedFiles,
            artifactGuards = listOfNotNull(
                inputs.legacyPlatformJar?.let {
                    ArtifactGuard(it, artifacts.legacyPlatformSha256)
                },
                inputs.targetPlatformJar?.let {
                    ArtifactGuard(it, artifacts.targetPlatformSha256)
                },
                inputs.targetSourcesJar?.let {
                    ArtifactGuard(it, artifacts.targetSourcesSha256)
                },
                inputs.mixinExtrasJar?.let {
                    ArtifactGuard(it, artifacts.mixinExtrasSha256)
                }
            ),
            mutations = mutations,
            evidence = evidence
        )
    }

    /**
     * Applies an already complete plan. Any graph or artifact drift invalidates the plan
     * before staging. A caught commit failure restores all originals from the transaction
     * directory. The method never follows paths outside [Plan.project].
     */
    fun apply(plan: Plan) {
        val project = plan.project.toAbsolutePath().normalize()
        require(project.isDirectory()) { "Project root disappeared: $project" }
        val current = ProjectSnapshot.read(project)
        check(current.guardedFiles == plan.guardedFiles) {
            "Project source/config graph changed after preflight; no files were written"
        }
        plan.artifactGuards.forEach { guard ->
            check(sha256(guard.path) == guard.sha256) {
                "Verified artifact changed after preflight: ${guard.path}"
            }
        }
        plan.mutations.forEach { mutation ->
            val target = resolveInside(project, mutation.relativePath)
            val actual = if (target.exists()) sha256(target) else null
            check(actual == mutation.beforeSha256) {
                "Mutation input changed after preflight: ${mutation.relativePath}"
            }
        }

        val transaction = project.resolve(".modporter-transaction-${UUID.randomUUID()}")
            .toAbsolutePath().normalize()
        check(transaction.startsWith(project))
        val staged = transaction.resolve("staged")
        val backup = transaction.resolve("backup")
        staged.createDirectories()
        backup.createDirectories()
        var preserveTransaction = false

        try {
            plan.mutations.forEachIndexed { index, mutation ->
                mutation.after?.let { bytes ->
                    val file = staged.resolve(index.toString())
                    file.parent.createDirectories()
                    file.writeBytes(bytes)
                    check(sha256(file) == sha256(bytes)) {
                        "Staged mutation bytes changed: ${mutation.relativePath}"
                    }
                }
            }

            // Recheck after staging so a concurrent edit cannot be committed over.
            val stagedSnapshot = ProjectSnapshot.read(project)
            check(stagedSnapshot.guardedFiles == plan.guardedFiles) {
                "Project graph changed while staging; no project file was replaced"
            }
            plan.artifactGuards.forEach { guard ->
                check(sha256(guard.path) == guard.sha256) {
                    "Verified artifact changed while staging: ${guard.path}"
                }
            }
            plan.mutations.forEach { mutation ->
                val target = resolveInside(project, mutation.relativePath)
                val actual = if (target.exists()) sha256(target) else null
                check(actual == mutation.beforeSha256) {
                    "Mutation input changed while staging: ${mutation.relativePath}"
                }
            }

            data class CommitState(
                val index: Int,
                var originalMoved: Boolean = false,
                var replacementMoved: Boolean = false
            )
            val touched = mutableListOf<CommitState>()
            try {
                plan.mutations.forEachIndexed { index, mutation ->
                    val target = resolveInside(project, mutation.relativePath)
                    val saved = backup.resolve(index.toString())
                    val state = CommitState(index)
                    touched += state
                    if (target.exists()) {
                        saved.parent.createDirectories()
                        atomicMove(target, saved)
                        state.originalMoved = true
                    }
                    if (mutation.after != null) {
                        target.parent.createDirectories()
                        atomicMove(staged.resolve(index.toString()), target)
                        state.replacementMoved = true
                    }
                }
            } catch (failure: Throwable) {
                val rollbackFailures = mutableListOf<Throwable>()
                touched.asReversed().forEach { state ->
                    runCatching {
                        val mutation = plan.mutations[state.index]
                        val target = resolveInside(project, mutation.relativePath)
                        val saved = backup.resolve(state.index.toString())
                        if (state.replacementMoved || saved.exists()) {
                            Files.deleteIfExists(target)
                        }
                        if (saved.exists()) {
                            target.parent.createDirectories()
                            atomicMove(saved, target)
                        } else {
                            check(!state.originalMoved) {
                                "Original backup disappeared for ${mutation.relativePath}"
                            }
                        }
                    }.onFailure {
                        rollbackFailures += it
                    }
                }
                if (rollbackFailures.isNotEmpty()) {
                    preserveTransaction = true
                    rollbackFailures.forEach(failure::addSuppressed)
                }
                throw failure
            }
        } finally {
            if (!preserveTransaction) deleteOwnedTree(project, transaction)
        }
    }

    private object PinnedTargetMetadataVerifier : ArtifactVerifier {
        override fun verify(inputs: Inputs): ArtifactEvidence {
            check(
                TargetMembers.methodDescriptors(
                    "net.minecraft.world.entity.Mob",
                    "dropCustomDeathLoot"
                ) == setOf(MOB_METHOD)
            ) {
                "Target metadata does not prove the exact Mob custom-death descriptor"
            }
            val mobTarget = TargetMembers.inheritedMethod(
                "net.minecraft.world.entity.Mob",
                "dropCustomDeathLoot",
                MOB_METHOD
            )
            check(
                mobTarget?.owner == "net.minecraft.world.entity.Mob" &&
                    mobTarget.access == "protected"
            ) {
                "Target metadata does not prove the protected Mob hook"
            }
            check(
                TargetMembers.methodDescriptors(
                    "net.minecraft.world.item.enchantment.EnchantmentHelper",
                    "processEquipmentDropChance"
                ) == setOf(EQUIPMENT_CHANCE_METHOD)
            ) {
                "Target metadata does not prove the exact equipment-chance descriptor"
            }
            val chanceTarget = TargetMembers.inheritedMethod(
                "net.minecraft.world.item.enchantment.EnchantmentHelper",
                "processEquipmentDropChance",
                EQUIPMENT_CHANCE_METHOD
            )
            check(
                chanceTarget?.owner ==
                    "net.minecraft.world.item.enchantment.EnchantmentHelper" &&
                    chanceTarget.access == "public"
            ) {
                "Target metadata does not prove the public equipment-chance hook"
            }
            val iteration = TargetMembers.inheritedMethod(
                "net.minecraft.world.item.enchantment.EnchantmentHelper",
                "runIterationOnEquipment",
                ITERATION_METHOD
            )
            check(iteration?.owner ==
                "net.minecraft.world.item.enchantment.EnchantmentHelper" &&
                iteration.access == "public"
            ) {
                "Target metadata does not prove public runIterationOnEquipment$ITERATION_METHOD"
            }
            val accept = TargetMembers.inheritedMethod(
                "net.minecraft.world.item.enchantment.EnchantmentHelper\$EnchantmentInSlotVisitor",
                "accept",
                VISITOR_ACCEPT
            )
            check(
                accept?.access == "public" &&
                    accept.signature == VISITOR_ACCEPT_SIGNATURE
            ) {
                "Target metadata does not prove the nested equipment visitor is public; " +
                    "no access transformer is authorized"
            }
            check(
                TargetMembers.fieldDescriptor(
                    "net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction",
                    "enchantment"
                ) == "Lnet/minecraft/core/Holder;" &&
                TargetMembers.fieldDescriptor(
                    "net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction",
                    "value"
                ) ==
                "Lnet/minecraft/world/level/storage/loot/providers/number/NumberProvider;" &&
                TargetMembers.fieldDescriptor(
                    "net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction",
                    "limit"
                ) == "I" &&
                TargetMembers.fieldSignatures(
                    "net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction"
                )["enchantment"] == ENCHANTMENT_HOLDER_SIGNATURE
            ) {
                "Target metadata does not prove the count-function shadow fields"
            }
            check(
                TargetMembers.methodDescriptors(
                    "net.minecraft.world.level.storage.loot.functions.EnchantedCountIncreaseFunction",
                    "run"
                ).contains(
                    "(Lnet/minecraft/world/item/ItemStack;" +
                        "Lnet/minecraft/world/level/storage/loot/LootContext;)" +
                        "Lnet/minecraft/world/item/ItemStack;"
                )
            ) {
                "Target metadata does not prove the count-function run descriptor"
            }

            // Invocation counts, enclosing visitor class access, and native registration shape
            // are pinned source/bytecode facts for the same 1.21.1 target represented by
            // TargetMembers. The metadata API itself proves the public accept method but does
            // not expose class access. Mixin require/expect rechecks the selected operations at
            // application, Java compilation rechecks visitor accessibility, and the generated
            // policy rechecks the actual registry holder/effect before suppressing it.
            return ArtifactEvidence(
                legacyPlatformSha256 = LEGACY_PLATFORM_SHA256,
                targetPlatformSha256 = TARGET_PLATFORM_SHA256,
                targetSourcesSha256 = TARGET_SOURCES_SHA256,
                mixinExtrasSha256 = MIXIN_EXTRAS_SHA256,
                mobNextFloatCalls = 1,
                helperIterationCalls = 2,
                visitorPublic = true,
                legacyBootstrapContextAbi = true,
                nativeLootingShape = NATIVE_LOOTING_SHAPE,
                verificationBasis =
                    "TargetMembers 1.21.1 descriptors plus pinned source/bytecode audit hashes"
            )
        }
    }

    internal object PinnedArtifactVerifier : ArtifactVerifier {
        override fun verify(inputs: Inputs): ArtifactEvidence {
            val legacyJar = requireNotNull(inputs.legacyPlatformJar) {
                "Pinned artifact verification requires a legacy platform jar"
            }
            val targetJar = requireNotNull(inputs.targetPlatformJar) {
                "Pinned artifact verification requires a target platform jar"
            }
            val targetSources = requireNotNull(inputs.targetSourcesJar) {
                "Pinned artifact verification requires a target sources jar"
            }
            val mixinExtras = requireNotNull(inputs.mixinExtrasJar) {
                "Pinned artifact verification requires a MixinExtras jar"
            }
            val legacyHash = requireHash(
                legacyJar,
                LEGACY_PLATFORM_SHA256,
                "audited Forge 1.20.1 consumer graph"
            )
            val targetHash = sha256(targetJar)
            val targetSourcesHash = sha256(targetSources)
            LegacyLootingArtifactProfile.requireReviewedTargetAuxiliaryTuple(
                targetHash,
                targetSourcesHash
            )
            val mixinExtrasHash = requireHash(
                mixinExtras,
                MIXIN_EXTRAS_SHA256,
                "audited MixinExtras NeoForge 0.5.3 operation implementation"
            )

            // The legacy hash closes the known integer-consumer graph:
            // LivingEntity -> loot table, virtual custom death, LivingDropsEvent;
            // Mob consumes the int in the equipment roll; the remaining seven mob
            // overrides and WitherBoss only forward the int to super.
            verifyLegacyConsumerInventory(legacyJar)
            val legacyBootstrapContextAbi = verifyLegacyBootstrapContextAbi(legacyJar)
            verifyMixinExtrasApi(mixinExtras)

            val mob = ClassFile.read(targetJar, MOB_CLASS)
            val mobMethod = mob.exactMethod("dropCustomDeathLoot", MOB_METHOD)
            check(
                mobMethod.access and ACC_PROTECTED == ACC_PROTECTED &&
                    mobMethod.access and ACC_STATIC == 0
            ) {
                "Target Mob custom-death hook is no longer protected instance"
            }
            val mobCalls = mobMethod.invocations.count {
                it.owner == "net/minecraft/util/RandomSource" &&
                    it.name == "nextFloat" &&
                    it.descriptor == "()F"
            }
            check(mobCalls == 1) {
                "Target Mob equipment RNG hook changed: expected one nextFloat, found $mobCalls"
            }

            val helper = ClassFile.read(targetJar, HELPER_CLASS)
            val chanceMethod =
                helper.exactMethod("processEquipmentDropChance", EQUIPMENT_CHANCE_METHOD)
            check(
                chanceMethod.access and (ACC_PUBLIC or ACC_STATIC) ==
                    (ACC_PUBLIC or ACC_STATIC)
            ) {
                "Target equipment-chance hook is no longer public static"
            }
            val helperCalls = chanceMethod.invocations.count {
                it.owner == "net/minecraft/world/item/enchantment/EnchantmentHelper" &&
                    it.name == "runIterationOnEquipment" &&
                    it.descriptor == ITERATION_METHOD
            }
            check(helperCalls == 2) {
                "Target equipment visitor hook changed: expected two named calls, found $helperCalls"
            }
            val runIteration =
                helper.methods.singleOrNull {
                    it.name == "runIterationOnEquipment" && it.descriptor == ITERATION_METHOD
                } ?: error("Target runIterationOnEquipment descriptor is missing or ambiguous")
            check(runIteration.access and (ACC_PUBLIC or ACC_STATIC) == (ACC_PUBLIC or ACC_STATIC)) {
                "Target runIterationOnEquipment is no longer public static"
            }

            val visitor = ClassFile.read(targetJar, VISITOR_CLASS)
            val visitorPublic =
                visitor.access and (ACC_PUBLIC or ACC_INTERFACE) == (ACC_PUBLIC or ACC_INTERFACE)
            check(visitorPublic) {
                "Nested equipment visitor is not public in the verified target. " +
                    "This baseline has no metadata proof authorizing an access transformer."
            }
            val visitorAccept = visitor.exactMethod("accept", VISITOR_ACCEPT)
            check(visitorAccept.access and ACC_PUBLIC == ACC_PUBLIC) {
                "Nested equipment visitor accept method is no longer public"
            }

            val countFunction = ClassFile.read(targetJar, COUNT_FUNCTION_CLASS)
            val countFields = listOf(
                countFunction.exactField("enchantment", "Lnet/minecraft/core/Holder;"),
                countFunction.exactField(
                    "value",
                    "Lnet/minecraft/world/level/storage/loot/providers/number/NumberProvider;"
                ),
                countFunction.exactField("limit", "I")
            )
            check(countFields.all {
                it.access and (ACC_PRIVATE or ACC_FINAL) == (ACC_PRIVATE or ACC_FINAL)
            }) {
                "Target count-function fields are no longer private final"
            }
            countFunction.exactMethod(
                "run",
                "(Lnet/minecraft/world/item/ItemStack;" +
                    "Lnet/minecraft/world/level/storage/loot/LootContext;)" +
                    "Lnet/minecraft/world/item/ItemStack;"
            )

            verifyTargetSources(targetSources)
            return ArtifactEvidence(
                legacyPlatformSha256 = legacyHash,
                targetPlatformSha256 = targetHash,
                targetSourcesSha256 = targetSourcesHash,
                mixinExtrasSha256 = mixinExtrasHash,
                mobNextFloatCalls = mobCalls,
                helperIterationCalls = helperCalls,
                visitorPublic = visitorPublic,
                legacyBootstrapContextAbi = legacyBootstrapContextAbi,
                nativeLootingShape = NATIVE_LOOTING_SHAPE,
                verificationBasis =
                    "read-only SHA-256 plus target source/classfile verification"
            )
        }
    }

    private data class ProjectSnapshot(
        val project: Path,
        val sources: List<SourceUnit>,
        val mixinConfigs: List<ConfigUnit>,
        val guardedFiles: List<GuardedFile>,
        val declaredTypes: Set<String>
    ) {
        fun relative(path: Path): String =
            path.toAbsolutePath().normalize().relativeTo(project).invariantSeparatorsPathString

        companion object {
            fun read(project: Path): ProjectSnapshot {
                val normalized = project.toAbsolutePath().normalize()
                val javaFiles = Files.walk(normalized.resolve("src")).use { paths ->
                    paths.filter {
                        Files.isRegularFile(it) &&
                            it.toString().endsWith(".java", ignoreCase = true)
                    }.sorted().toList()
                }
                val sourceUnits = javaFiles.map { file ->
                    val text = file.readText()
                    val result = parser.parse(text)
                    val unit = result.result.orElseThrow {
                        IllegalStateException(
                            "Cannot prove looting ownership because Java does not parse: $file; " +
                                result.problems.joinToString()
                        )
                    }
                    SourceUnit(
                        file = file.toAbsolutePath().normalize(),
                        javaRoot = findJavaRoot(normalized, file),
                        unit = unit
                    )
                }
                val configs = Files.walk(normalized.resolve("src")).use { paths ->
                    paths.filter {
                        Files.isRegularFile(it) &&
                            it.fileName.toString().endsWith(".mixins.json")
                    }.sorted().map { file ->
                        val text = file.readText()
                        val element = runCatching { Json.parseToJsonElement(text).jsonObject }
                            .getOrElse {
                                error("Cannot parse mixin configuration $file: ${it.message}")
                            }
                        ConfigUnit(file.toAbsolutePath().normalize(), element)
                    }.toList()
                }
                val guardedPaths = (javaFiles + configs.map { it.file })
                    .map { it.toAbsolutePath().normalize() }
                    .distinct()
                    .sorted()
                val guards = guardedPaths.map {
                    GuardedFile(
                        it.relativeTo(normalized).invariantSeparatorsPathString,
                        sha256(it)
                    )
                }
                val declaredTypes = sourceUnits.flatMap { source ->
                    source.unit.types.map { type ->
                        if (source.packageName.isBlank()) type.nameAsString
                        else "${source.packageName}.${type.nameAsString}"
                    }
                }.toSet()
                return ProjectSnapshot(
                    normalized,
                    sourceUnits,
                    configs,
                    guards,
                    declaredTypes
                )
            }

            private fun findJavaRoot(project: Path, file: Path): Path {
                var current: Path? = file.parent
                while (current != null && current.startsWith(project)) {
                    if (current.fileName?.toString() == "java") return current
                    current = current.parent
                }
                error("Java source is not below a java source root: $file")
            }
        }
    }

    private data class SourceUnit(
        val file: Path,
        val javaRoot: Path,
        val unit: CompilationUnit
    ) {
        val packageName: String =
            unit.packageDeclaration.map { it.nameAsString }.orElse("")

        fun owner(method: MethodDeclaration): String {
            val type = method.findAncestor(TypeDeclaration::class.java)
                .orElseThrow { IllegalStateException("Method has no class owner in $file") }
            return if (packageName.isBlank()) type.nameAsString
            else "$packageName.${type.nameAsString}"
        }

        fun resolves(type: Type, canonical: String): Boolean =
            resolvesName(type.asString().substringBefore('<').trim(), canonical)

        fun resolvesName(name: String, canonical: String): Boolean {
            if (name == canonical) return true
            val simple = canonical.substringAfterLast('.')
            if (name != simple) return false
            if (unit.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == canonical }) {
                return true
            }
            if (unit.imports.any {
                    !it.isStatic && it.isAsterisk &&
                        canonical.substringBeforeLast('.') == it.nameAsString
                }
            ) return true
            return packageName == canonical.substringBeforeLast('.')
        }

        fun resolvesProjectTypeStrict(
            name: String,
            declaredTypes: Set<String>
        ): String? {
            if (name in declaredTypes) return name
            if ('.' in name) return null
            val explicit = unit.imports
                .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == name }
                .map { it.nameAsString }
            if (explicit.size > 1 || explicit.singleOrNull()?.let { it !in declaredTypes } == true) {
                return null
            }
            if (explicit.size == 1) return explicit.single()
            if (unit.imports.any { !it.isStatic && it.isAsterisk }) return null
            val samePackage = if (packageName.isBlank()) name else "$packageName.$name"
            return samePackage.takeIf { it in declaredTypes }
        }
    }

    private data class ConfigUnit(
        val file: Path,
        val json: JsonObject
    )

    private data class CapturedPolicy(
        val file: Path,
        val ownerClass: String,
        val methodName: String,
        val methodBeginLine: Int,
        val keyExpression: String,
        val keyOwner: String,
        val keyField: String,
        val level: Int
    )

    private data class CapturedCountMixin(
        val file: Path,
        val javaRoot: Path,
        val packageName: String,
        val className: String,
        val classBeginLine: Int,
        val injectedMethod: String,
        val valueField: String,
        val limitField: String,
        val hasLimitMethod: String
    )

    private data class CapturedConfig(
        val file: Path,
        val entry: String,
        val packageName: String,
        val unit: ConfigUnit
    )

    private data class ConsumerGraph(val producer: String, val terminalHurtCalls: Int)
    private data class CanonicalKey(
        val expression: String,
        val owner: String,
        val field: String
    )

    private class SourceAdmission(
        private val snapshot: ProjectSnapshot,
        private val artifacts: ArtifactEvidence
    ) {
        private data class MethodLookupKey(val name: String, val arity: Int)
        private data class ProjectMethodIndex(
            val methods: List<MethodRef>,
            val byNameAndArity: Map<MethodLookupKey, List<MethodRef>>
        )
        private data class CachedProjectCallResolution(
            val ownerMethod: MethodDeclaration,
            val methods: List<MethodRef>,
            val targets: List<MethodRef>
        )
        private var projectMethodIndex: ProjectMethodIndex? = null
        // SourceAdmission owns an immutable snapshot; identity keys retain exact call ownership.
        private val projectCallResolutionCache =
            IdentityHashMap<MethodCallExpr, CachedProjectCallResolution>()

        fun capturePolicy(): CapturedPolicy {
            val candidates = snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
                    if (method.parameters.size != 1 ||
                        !resolvesLegacyEvent(source, method.parameters[0].type)
                    ) return@mapNotNull null
                    capturePolicyMethod(source, method)
                }
            }
            check(candidates.size == 1) {
                "Expected exactly one admitted LootingLevelEvent policy handler, " +
                    "found ${candidates.size}"
            }
            val policy = candidates.single()
            val allEventOwners = snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodDeclaration::class.java).filter { method ->
                    method.parameters.any { resolvesLegacyEvent(source, it.type) }
                }.map { source.file to it.nameAsString }
            }
            check(allEventOwners.size == 1) {
                "Additional LootingLevelEvent consumers exist: $allEventOwners"
            }
            val eventTypeUses = snapshot.sources.flatMap { source ->
                source.unit.findAll(ClassOrInterfaceType::class.java)
                    .filter { resolvesLegacyEvent(source, it) }
                    .map {
                        "${snapshot.relative(source.file)}:" +
                            it.range.map { range -> range.begin.line }.orElse(-1)
                    }
            }
            check(eventTypeUses.size == 1) {
                "LootingLevelEvent appears outside the admitted policy parameter: $eventTypeUses"
            }
            return policy
        }

        private fun resolvesLegacyEvent(source: SourceUnit, type: Type): Boolean {
            val name = type.asString().substringBefore('<').trim()
            if (name in LEGACY_EVENTS) return true
            if (name != "LootingLevelEvent") return false
            val explicit = source.unit.imports.filter {
                !it.isStatic && !it.isAsterisk &&
                    it.name.identifier == "LootingLevelEvent"
            }
            if (explicit.any { it.nameAsString in LEGACY_EVENTS }) return true
            if (source.unit.imports.any {
                    !it.isStatic && !it.isAsterisk &&
                        it.name.identifier == "LootingLevelEvent"
                }
            ) return false
            if (source.unit.imports.any { !it.isStatic && it.isAsterisk }) return false
            if (source.unit.findAll(TypeDeclaration::class.java).any {
                    it.nameAsString == "LootingLevelEvent"
                }
            ) return false
            val samePackage = if (source.packageName.isBlank()) "LootingLevelEvent"
            else "${source.packageName}.LootingLevelEvent"
            return samePackage !in snapshot.declaredTypes
        }

        private fun canonicalPolicyKey(
            source: SourceUnit,
            key: Expression
        ): CanonicalKey? {
            val fieldAccess = unwrap(key) as? FieldAccessExpr ?: return null
            val owner = source.resolvesProjectTypeStrict(
                fieldAccess.scope.toString(),
                snapshot.declaredTypes
            ) ?: return null
            val declarations = snapshot.sources.flatMap { candidate ->
                candidate.unit.types
                    .filter {
                        val canonical = if (candidate.packageName.isBlank()) it.nameAsString
                        else "${candidate.packageName}.${it.nameAsString}"
                        canonical == owner
                    }
                    .map { candidate to it }
            }
            if (declarations.size != 1) return null
            val (_, type) = declarations.single()
            if (type !is ClassOrInterfaceDeclaration || !type.isPublic) return null
            val fields = type.fields.filter { field ->
                field.variables.any { it.nameAsString == fieldAccess.nameAsString }
            }
            if (fields.size != 1 ||
                !fields.single().isPublic ||
                !fields.single().isStatic ||
                !fields.single().isFinal
            ) {
                return null
            }
            val variable = fields.single().variables.singleOrNull {
                it.nameAsString == fieldAccess.nameAsString
            } ?: return null
            if (!candidateSourceResolvesResourceKey(declarations.single().first, variable.type)) {
                return null
            }
            val initializer = variable.initializer.orElse(null) as? MethodCallExpr ?: return null
            if (initializer.scope.isPresent ||
                initializer.arguments.size != 1 ||
                initializer.arguments.single() !is StringLiteralExpr
            ) return null
            return CanonicalKey(
                "$owner.${fieldAccess.nameAsString}",
                owner,
                fieldAccess.nameAsString
            )
        }

        private fun candidateSourceResolvesResourceKey(
            source: SourceUnit,
            type: Type
        ): Boolean {
            if (!source.resolves(type, RESOURCE_KEY) || !type.isClassOrInterfaceType) {
                return false
            }
            val arguments = type.asClassOrInterfaceType().typeArguments.orElse(null)
                ?: return false
            return arguments.size == 1 &&
                source.resolves(arguments.single(), DAMAGE_TYPE)
        }

        private fun capturePolicyMethod(
            source: SourceUnit,
            method: MethodDeclaration
        ): CapturedPolicy? {
            if (method.modifiers.map { it.keyword }.toSet() !=
                setOf(
                    com.github.javaparser.ast.Modifier.Keyword.PUBLIC,
                    com.github.javaparser.ast.Modifier.Keyword.STATIC
                ) ||
                method.type.asString() != "void" ||
                method.annotations.map { it.nameAsString }.toSet() !=
                setOf("SubscribeEvent")
            ) return null
            val body = method.body.orElse(null) ?: return null
            val statements = body.statements
            if (statements.size != 3) return null

            val declaration = (statements[0] as? ExpressionStmt)
                ?.expression as? VariableDeclarationExpr ?: return null
            if (declaration.variables.size != 1 || declaration.modifiers.isNotEmpty()) return null
            val damage = declaration.variables.single()
            if (!source.resolves(damage.type, DAMAGE_SOURCE)) return null
            val getSource = damage.initializer.orElse(null) as? MethodCallExpr ?: return null
            if (getSource.nameAsString != "getDamageSource" ||
                getSource.arguments.isNotEmpty() ||
                getSource.scope.map { it.toString() }.orElse("") != method.parameters[0].nameAsString
            ) return null

            val gate = statements[1] as? IfStmt ?: return null
            if (gate.elseStmt.isPresent || !isBareReturn(gate.thenStmt)) return null
            val key = capturePolicyKey(gate.condition, damage.nameAsString) ?: return null
            val canonicalKey = canonicalPolicyKey(source, key) ?: return null

            val setter = (statements[2] as? ExpressionStmt)
                ?.expression as? MethodCallExpr ?: return null
            if (setter.nameAsString != "setLootingLevel" ||
                setter.scope.map { it.toString() }.orElse("") !=
                method.parameters[0].nameAsString ||
                setter.arguments.size != 1
            ) return null
            val levelLiteral = setter.arguments.single() as? IntegerLiteralExpr ?: return null
            val level = runCatching { levelLiteral.asInt() }.getOrNull() ?: return null
            if (level <= 0) return null

            return CapturedPolicy(
                file = source.file,
                ownerClass = source.owner(method),
                methodName = method.nameAsString,
                methodBeginLine = method.range.map { it.begin.line }.orElse(-1),
                keyExpression = canonicalKey.expression,
                keyOwner = canonicalKey.owner,
                keyField = canonicalKey.field,
                level = level
            )
        }

        fun captureCountMixin(policy: CapturedPolicy): CapturedCountMixin {
            val candidates = mutableListOf<Pair<SourceUnit, ClassOrInterfaceDeclaration>>()
            snapshot.sources.forEach { source ->
                source.unit.findAll(ClassOrInterfaceDeclaration::class.java).forEach { type ->
                    val mixinTarget = mixinTarget(type) ?: return@forEach
                    if (resolvesLegacyFunction(source, mixinTarget)) {
                        candidates += source to type
                    }
                }
            }
            check(candidates.size == 1) {
                "Expected one LootingEnchantFunction mixin, found ${candidates.size}"
            }
            val (source, type) = candidates.single()
            check(source.packageName.isNotBlank()) {
                "Legacy count mixin must be in a named package"
            }
            check(type.isPublic && type.isAbstract && !type.isInterface) {
                "Legacy count mixin must be one public abstract class"
            }
            check(type.annotations.size == 1 &&
                type.annotations.single().nameAsString == "Mixin"
            ) {
                "Legacy count mixin has unsupported class annotations"
            }
            check(type.fields.size == 2 && type.methods.size == 2 && type.members.size == 4) {
                "Legacy count mixin contains members outside the exact two shadows, " +
                    "private-target shadow, and TAIL handler"
            }

            val valueFields = type.fields.flatMap { field ->
                field.variables.filter { source.resolves(it.type, NUMBER_PROVIDER) }
            }
            val limitFields = type.fields.flatMap { field ->
                field.variables.filter { it.type.isPrimitiveType && it.type.asString() == "int" }
            }
            check(valueFields.size == 1 && limitFields.size == 1) {
                "Legacy count mixin must have exactly one NumberProvider and one int shadow"
            }
            valueFields.single().requireShadowFinal()
            limitFields.single().requireShadowFinal()

            val hasLimit = type.methods.filter {
                it.parameters.isEmpty() &&
                    it.type.asString() == "boolean" &&
                    it.body.isEmpty &&
                    it.annotations.any { annotation -> annotation.nameAsString == "Shadow" }
            }
            check(hasLimit.size == 1) {
                "Legacy count mixin must have exactly one abstract boolean @Shadow method"
            }
            check(hasLimit.single().modifiers.map { it.keyword }.toSet() ==
                setOf(com.github.javaparser.ast.Modifier.Keyword.ABSTRACT) &&
                hasLimit.single().annotations.map { it.nameAsString }.toSet() ==
                setOf("Shadow")
            ) {
                "Legacy hasLimit shadow has unsupported modifiers or annotations"
            }
            val injected = type.methods.filter { isRunTailInjection(it) }
            check(injected.size == 1) {
                "Legacy count mixin must have exactly one run TAIL injection"
            }
            val injectedMethod = injected.single()
            check(injectedMethod.modifiers.map { it.keyword }.toSet() ==
                setOf(com.github.javaparser.ast.Modifier.Keyword.PRIVATE) &&
                injectedMethod.annotations.size == 1 &&
                injectedMethod.annotations.single().nameAsString == "Inject" &&
                injectedMethod.type.isVoidType &&
                injectedMethod.parameters.size == 3 &&
                source.resolves(injectedMethod.parameters[0].type, ITEM_STACK) &&
                source.resolves(injectedMethod.parameters[1].type, LOOT_CONTEXT) &&
                source.resolves(injectedMethod.parameters[2].type, CALLBACK_INFO_RETURNABLE) &&
                normalized(injectedMethod.parameters[2].type.toString())
                    .endsWith("<ItemStack>")
            ) {
                "Legacy count mixin TAIL handler signature is outside the admitted shape"
            }
            verifyExactCountBody(
                source,
                injectedMethod,
                policy,
                valueFields.single().nameAsString,
                limitFields.single().nameAsString,
                hasLimit.single().nameAsString
            )
            check(type.members.none {
                it is com.github.javaparser.ast.body.FieldDeclaration &&
                    it.variables.any { variable -> variable.nameAsString == "enchantment" }
            }) {
                "Legacy count mixin already declares an enchantment field"
            }

            return CapturedCountMixin(
                file = source.file,
                javaRoot = source.javaRoot,
                packageName = source.packageName,
                className = if (source.packageName.isBlank()) type.nameAsString
                else "${source.packageName}.${type.nameAsString}",
                classBeginLine = type.range.map { it.begin.line }.orElse(-1),
                injectedMethod = injectedMethod.nameAsString,
                valueField = valueFields.single().nameAsString,
                limitField = limitFields.single().nameAsString,
                hasLimitMethod = hasLimit.single().nameAsString
            )
        }

        private fun verifyExactCountBody(
            source: SourceUnit,
            method: MethodDeclaration,
            policy: CapturedPolicy,
            valueField: String,
            limitField: String,
            hasLimitMethod: String
        ) {
            val stack = method.parameters[0].nameAsString
            val context = method.parameters[1].nameAsString
            val statements = method.body.orElseThrow().statements
            check(statements.size == 2) {
                "Legacy count TAIL handler must contain only source capture and policy branch"
            }

            val damage = singleDeclaredVariable(statements[0])
                ?: error("Legacy count handler must begin with one DamageSource declaration")
            check(source.resolves(damage.type, DAMAGE_SOURCE)) {
                "Legacy count handler source local is not DamageSource"
            }
            val sourceCall = damage.initializer.orElse(null) as? MethodCallExpr
            check(sourceCall != null &&
                sourceCall.nameAsString == "getParamOrNull" &&
                sourceCall.scope.map { it.toString() }.orElse("") == context &&
                sourceCall.arguments.size == 1 &&
                normalized(sourceCall.arguments.single().toString()) ==
                "LootContextParams.DAMAGE_SOURCE"
            ) {
                "Legacy count handler must read only LootContextParams.DAMAGE_SOURCE"
            }

            val policyBranch = statements[1] as? IfStmt
                ?: error("Legacy count handler must end with one policy if")
            check(policyBranch.elseStmt.isEmpty && policyBranch.thenStmt.isBlockStmt) {
                "Legacy count policy branch must have one block and no else"
            }
            val condition = unwrap(policyBranch.condition) as? BinaryExpr
            check(condition != null && condition.operator == BinaryExpr.Operator.AND &&
                isNotNullCheck(condition.left, damage.nameAsString) &&
                isPositivePolicyKeyCheck(
                    source,
                    condition.right,
                    damage.nameAsString,
                    policy
                )
            ) {
                "Legacy count policy condition is outside the exact source/key shape"
            }

            val branch = policyBranch.thenStmt.asBlockStmt().statements
            check(branch.size == 4) {
                "Legacy count policy branch has unsupported extra behavior"
            }
            val level = singleDeclaredVariable(branch[0])
                ?: error("Legacy count branch must declare one integer policy level")
            check(level.type.isPrimitiveType && level.type.asString() == "int" &&
                (level.initializer.orElse(null) as? IntegerLiteralExpr)?.asInt() == policy.level
            ) {
                "Legacy count policy level does not exactly match the event policy"
            }

            val increase = singleDeclaredVariable(branch[1])
                ?: error("Legacy count branch must declare one float increase")
            check(increase.type.isPrimitiveType && increase.type.asString() == "float") {
                "Legacy count increase local must be float"
            }
            val multiplication = unwrap(increase.initializer.orElse(null)
                ?: error("Legacy count increase has no initializer")) as? BinaryExpr
            check(multiplication != null &&
                multiplication.operator == BinaryExpr.Operator.MULTIPLY &&
                isPolicyLevelOperand(multiplication.left, level.nameAsString) &&
                isValueProviderCall(
                    multiplication.right,
                    valueField,
                    context
                )
            ) {
                "Legacy count increase must be level * value.getFloat(context)"
            }

            val grow = (branch[2] as? ExpressionStmt)?.expression as? MethodCallExpr
            check(grow != null &&
                grow.nameAsString == "grow" &&
                grow.scope.map { it.toString() }.orElse("") == stack &&
                grow.arguments.size == 1 &&
                isMathRound(grow.arguments.single(), increase.nameAsString)
            ) {
                "Legacy count branch must grow the stack by Math.round(increase)"
            }

            val cap = branch[3] as? IfStmt
                ?: error("Legacy count branch must end with the original limit cap")
            check(cap.elseStmt.isEmpty &&
                isExactLimitCondition(
                    cap.condition,
                    stack,
                    limitField,
                    hasLimitMethod
                ) &&
                isExactSetCount(cap.thenStmt, stack, limitField)
            ) {
                "Legacy count limit cap is outside the exact preserved shape"
            }
        }

        private fun singleDeclaredVariable(statement: Statement): VariableDeclarator? {
            val declaration = (statement as? ExpressionStmt)
                ?.expression as? VariableDeclarationExpr ?: return null
            if (declaration.modifiers.isNotEmpty() || declaration.variables.size != 1) return null
            return declaration.variables.single()
        }

        private fun isNotNullCheck(expression: Expression, name: String): Boolean {
            val binary = unwrap(expression) as? BinaryExpr ?: return false
            if (binary.operator != BinaryExpr.Operator.NOT_EQUALS) return false
            val left = unwrap(binary.left)
            val right = unwrap(binary.right)
            return (left is NameExpr && left.nameAsString == name && right is NullLiteralExpr) ||
                (right is NameExpr && right.nameAsString == name && left is NullLiteralExpr)
        }

        private fun isPositivePolicyKeyCheck(
            source: SourceUnit,
            expression: Expression,
            damageLocal: String,
            policy: CapturedPolicy
        ): Boolean {
            val call = unwrap(expression) as? MethodCallExpr ?: return false
            return call.nameAsString == "is" &&
                call.scope.map { it.toString() }.orElse("") == damageLocal &&
                call.arguments.size == 1 &&
                canonicalProjectField(source, call.arguments.single()) ==
                policy.keyExpression
        }

        private fun canonicalProjectField(
            source: SourceUnit,
            expression: Expression
        ): String? {
            val field = unwrap(expression) as? FieldAccessExpr ?: return null
            val owner = source.resolvesProjectTypeStrict(
                field.scope.toString(),
                snapshot.declaredTypes
            ) ?: return null
            return "$owner.${field.nameAsString}"
        }

        private fun isPolicyLevelOperand(
            expression: Expression,
            levelLocal: String
        ): Boolean {
            val unwrapped = unwrap(expression)
            if (unwrapped is NameExpr) return unwrapped.nameAsString == levelLocal
            val cast = unwrapped as? CastExpr ?: return false
            return cast.type.asString() == "float" &&
                (unwrap(cast.expression) as? NameExpr)?.nameAsString == levelLocal
        }

        private fun isValueProviderCall(
            expression: Expression,
            valueField: String,
            context: String
        ): Boolean {
            val call = unwrap(expression) as? MethodCallExpr ?: return false
            return call.nameAsString == "getFloat" &&
                call.arguments.size == 1 &&
                call.arguments.single().toString() == context &&
                normalized(call.scope.map { it.toString() }.orElse("")) ==
                "this.$valueField"
        }

        private fun isMathRound(
            expression: Expression,
            increaseLocal: String
        ): Boolean {
            val call = unwrap(expression) as? MethodCallExpr ?: return false
            return call.nameAsString == "round" &&
                call.scope.map { it.toString() }.orElse("") == "Math" &&
                call.arguments.size == 1 &&
                call.arguments.single().toString() == increaseLocal
        }

        private fun isExactLimitCondition(
            expression: Expression,
            stack: String,
            limitField: String,
            hasLimitMethod: String
        ): Boolean {
            val and = unwrap(expression) as? BinaryExpr ?: return false
            if (and.operator != BinaryExpr.Operator.AND) return false
            val hasLimit = unwrap(and.left) as? MethodCallExpr ?: return false
            if (hasLimit.nameAsString != hasLimitMethod ||
                hasLimit.arguments.isNotEmpty() ||
                hasLimit.scope.map { normalized(it.toString()) }.orElse("this") != "this"
            ) return false
            val comparison = unwrap(and.right) as? BinaryExpr ?: return false
            if (comparison.operator != BinaryExpr.Operator.GREATER) return false
            val getCount = unwrap(comparison.left) as? MethodCallExpr ?: return false
            return getCount.nameAsString == "getCount" &&
                getCount.arguments.isEmpty() &&
                getCount.scope.map { it.toString() }.orElse("") == stack &&
                normalized(comparison.right.toString()) == "this.$limitField"
        }

        private fun isExactSetCount(
            statement: Statement,
            stack: String,
            limitField: String
        ): Boolean {
            val actual = if (statement.isBlockStmt) {
                statement.asBlockStmt().statements.singleOrNull()
            } else {
                statement
            } ?: return false
            val call = (actual as? ExpressionStmt)?.expression as? MethodCallExpr ?: return false
            return call.nameAsString == "setCount" &&
                call.scope.map { it.toString() }.orElse("") == stack &&
                call.arguments.size == 1 &&
                normalized(call.arguments.single().toString()) == "this.$limitField"
        }

        fun captureMixinConfig(mixin: CapturedCountMixin): CapturedConfig {
            val matches = mutableListOf<CapturedConfig>()
            snapshot.mixinConfigs.forEach { config ->
                val packageName = config.json["package"]?.jsonPrimitive?.content ?: return@forEach
                listOf("mixins", "client", "server").forEach { section ->
                    val entries = config.json[section] as? JsonArray ?: return@forEach
                    entries.forEach { value ->
                        val entry = value.jsonPrimitive.content
                        val canonical = if (packageName.isBlank()) entry else "$packageName.$entry"
                        if (canonical == mixin.className) {
                            check(section == "mixins") {
                                "Legacy count mixin must be registered in the common mixins section"
                            }
                            check(
                                config.json["required"]?.jsonPrimitive?.content == "true"
                            ) {
                                "Owning mixin configuration must be required"
                            }
                            matches += CapturedConfig(
                                config.file,
                                entry,
                                packageName,
                                config
                            )
                        }
                    }
                }
            }
            check(matches.size == 1) {
                "Expected one mixin config entry for ${mixin.className}, found ${matches.size}"
            }
            return matches.single()
        }

        fun verifyMixinPlugin(
            config: CapturedConfig,
            generatedPackage: String,
            names: GeneratedNames
        ) {
            val pluginName =
                config.unit.json["plugin"]?.jsonPrimitive?.content ?: return
            val pluginTypes = snapshot.sources.flatMap { source ->
                source.unit.types.filter { type ->
                    val canonical = if (source.packageName.isBlank()) type.nameAsString
                    else "${source.packageName}.${type.nameAsString}"
                    canonical == pluginName
                }.map { source to it }
            }
            check(pluginTypes.size == 1) {
                "Mixin plugin source is missing or ambiguous: $pluginName"
            }
            val (_, declaration) = pluginTypes.single()
            val type = declaration as? ClassOrInterfaceDeclaration
                ?: error("Mixin plugin is not a source class: $pluginName")
            val shouldApply = type.methods.filter {
                it.nameAsString == "shouldApplyMixin" &&
                    it.parameters.size == 2 &&
                    it.type.asString() == "boolean"
            }
            check(shouldApply.size == 1) {
                "Mixin plugin must expose one analyzable shouldApplyMixin method"
            }
            val method = shouldApply.single()
            val body = method.body.orElseThrow {
                IllegalStateException("Mixin plugin shouldApplyMixin has no source body")
            }
            check(body.statements.isNotEmpty()) {
                "Mixin plugin shouldApplyMixin has no return path"
            }
            val finalReturn = body.statements.lastOrNull() as? ReturnStmt
            check(finalReturn?.expression?.map { it.toString() }?.orElse("") == "true") {
                "Mixin plugin does not end with unconditional admission"
            }
            val guards = body.statements.dropLast(1).map { statement ->
                val guarded = statement as? IfStmt
                    ?: error(
                        "Mixin plugin shouldApplyMixin contains unsupported statements: " +
                            statement
                    )
                check(guarded.elseStmt.isEmpty && isBareBooleanReturn(guarded.thenStmt, false)) {
                    "Mixin plugin guard must contain only return false: $guarded"
                }
                guarded
            }
            check(method.findAll(ReturnStmt::class.java).size == guards.size + 1) {
                "Mixin plugin shouldApplyMixin contains nested or unsupported return paths"
            }
            check(method.findAll(MethodReferenceExpr::class.java).isEmpty()) {
                "Mixin plugin shouldApplyMixin contains unsupported method references"
            }
            check(method.findAll(com.github.javaparser.ast.expr.AssignExpr::class.java).isEmpty() &&
                method.findAll(
                    com.github.javaparser.ast.expr.VariableDeclarationExpr::class.java
                ).isEmpty()
            ) {
                "Mixin plugin shouldApplyMixin contains stateful expressions"
            }

            val targetParameter = method.parameters[0].nameAsString
            val mixinParameter = method.parameters[1].nameAsString
            fun generated(simple: String): String =
                if (generatedPackage.isBlank()) simple
                else "$generatedPackage.$simple"
            val pairs = listOf(
                "net.minecraft.world.entity.Mob" to
                    generated(names.mobMixinType),
                "net.minecraft.world.item.enchantment.EnchantmentHelper" to
                    generated(names.helperMixinType)
            )
            guards.forEach { guarded ->
                val condition = guarded.condition
                val conjuncts = flattenConjunction(condition)
                check(conjuncts != null && conjuncts.size in 1..2) {
                    "Mixin plugin guard is outside the exact admitted conjunction: $condition"
                }
                val prefixes = conjuncts.filter { conjunct ->
                    pairs.all { (targetClass, mixinClass) ->
                        excludesStartsWith(
                            conjunct,
                            targetParameter,
                            mixinParameter,
                            targetClass,
                            mixinClass
                        )
                    }
                }
                check(prefixes.size == 1 &&
                    conjuncts.filter { it !in prefixes }.all(::isPluginAvailabilityCheck)
                ) {
                    "Mixin plugin may reject a generated looting mixin or execute an " +
                        "unsupported condition: $condition"
                }
                val calls = condition.findAll(MethodCallExpr::class.java)
                check(calls.size == conjuncts.size) {
                    "Mixin plugin guard contains nested method calls outside the admitted shape: " +
                        condition
                }
            }
        }

        private fun isBareBooleanReturn(statement: Statement, value: Boolean): Boolean {
            val actual = if (statement.isBlockStmt) {
                statement.asBlockStmt().statements.singleOrNull()
            } else {
                statement
            } as? ReturnStmt ?: return false
            return actual.expression.map { it.toString() }.orElse("") == value.toString()
        }

        private fun isPluginAvailabilityCheck(expression: Expression): Boolean {
            val unary = unwrap(expression) as? UnaryExpr ?: return false
            if (unary.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return false
            val call = unwrap(unary.expression) as? MethodCallExpr ?: return false
            if (call.nameAsString != "isLoaded" || call.arguments.isNotEmpty()) return false
            val modConstant = unwrap(call.scope.orElse(null) ?: return false) as?
                FieldAccessExpr ?: return false
            return modConstant.nameAsString.matches(Regex("""[A-Z][A-Z0-9_]*"""))
        }

        private fun flattenConjunction(expression: Expression): List<Expression>? {
            val unwrapped = unwrap(expression)
            if (unwrapped !is BinaryExpr) return listOf(unwrapped)
            if (unwrapped.operator != BinaryExpr.Operator.AND) return null
            val left = flattenConjunction(unwrapped.left) ?: return null
            val right = flattenConjunction(unwrapped.right) ?: return null
            return left + right
        }

        private fun excludesStartsWith(
            expression: Expression,
            targetParameter: String,
            mixinParameter: String,
            targetClass: String,
            mixinClass: String
        ): Boolean {
            val call = unwrap(expression) as? MethodCallExpr ?: return false
            if (call.nameAsString != "startsWith" || call.arguments.size != 1) return false
            val prefix = call.arguments.single() as? StringLiteralExpr ?: return false
            return when (call.scope.map { it.toString() }.orElse("")) {
                targetParameter -> !targetClass.startsWith(prefix.value)
                mixinParameter -> !mixinClass.startsWith(prefix.value)
                else -> false
            }
        }

        fun verifyCompleteConsumerGraph(
            policy: CapturedPolicy,
            countMixin: CapturedCountMixin
        ): ConsumerGraph {
            val customDeathDeclarations = snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodDeclaration::class.java)
                    .filter { it.nameAsString == "dropCustomDeathLoot" }
                    .map { "${snapshot.relative(source.file)}#${it.nameAsString}" }
            }
            val customDeathCalls = projectCalls("dropCustomDeathLoot")
            check(customDeathDeclarations.isEmpty() && customDeathCalls.isEmpty()) {
                "Project custom-death consumers are outside the admitted graph: " +
                    "${customDeathDeclarations + customDeathCalls}"
            }
            val directLootingCalls = projectCalls("getLootingLevel")
            check(directLootingCalls.isEmpty()) {
                "Project getLootingLevel consumers are outside the admitted graph: $directLootingCalls"
            }
            val countMixinSourceReferences = snapshot.sources.flatMap { source ->
                val imported = source.unit.imports.any {
                    !it.isStatic && !it.isAsterisk &&
                        it.nameAsString == countMixin.className
                }
                val typed = source.unit.findAll(ClassOrInterfaceType::class.java).any {
                    source.resolvesProjectTypeStrict(
                        it.asString().substringBefore('<'),
                        snapshot.declaredTypes
                    ) == countMixin.className
                }
                if ((imported || typed) && source.file != countMixin.file) {
                    listOf(snapshot.relative(source.file))
                } else {
                    emptyList()
                }
            }
            check(countMixinSourceReferences.isEmpty()) {
                "Legacy count mixin has project Java consumers outside its mixin config: " +
                    countMixinSourceReferences
            }

            snapshot.sources.forEach { source ->
                source.unit.findAll(MethodDeclaration::class.java).forEach { method ->
                    val livingDropsParameters = method.parameters.filter {
                        LIVING_DROPS_EVENTS.any { event ->
                            source.resolves(it.type, event)
                        }
                    }
                    if (livingDropsParameters.isEmpty()) return@forEach
                    check(livingDropsParameters.size == 1) {
                        "LivingDropsEvent methods must have exactly one event parameter in " +
                            source.file
                    }
                    val livingDropsParameter = livingDropsParameters.single()
                    val allowed = setOf("getSource", "getDrops", "setCanceled", "isCanceled")
                    val unexpected = method.findAll(MethodCallExpr::class.java).filter {
                        it.scope.map { scope -> scope.toString() }.orElse("") ==
                            livingDropsParameter.nameAsString &&
                            it.nameAsString !in allowed
                    }
                    check(unexpected.isEmpty()) {
                        "LivingDropsEvent handler consumes an unproved property in ${source.file}: " +
                            unexpected.joinToString()
                    }
                    val escapedEventUses = method.findAll(NameExpr::class.java).filter { name ->
                        if (name.nameAsString != livingDropsParameter.nameAsString) {
                            return@filter false
                        }
                        val parent = name.parentNode.orElse(null) as? MethodCallExpr
                        parent == null ||
                            parent.scope.orElse(null) !== name ||
                            parent.nameAsString !in allowed
                    }
                    check(escapedEventUses.isEmpty()) {
                        "LivingDropsEvent value escapes the admitted accessors in ${source.file}: " +
                            escapedEventUses.joinToString()
                    }
                }
            }
            val livingDropTypeEscapes = snapshot.sources.flatMap { source ->
                source.unit.findAll(ClassOrInterfaceType::class.java)
                    .filter { type ->
                        LIVING_DROPS_EVENTS.any { source.resolves(type, it) }
                    }
                    .filter { type ->
                        val parameter = type.parentNode.orElse(null) as?
                            com.github.javaparser.ast.body.Parameter
                        parameter == null ||
                            parameter.parentNode.orElse(null) !is MethodDeclaration
                    }
                    .map {
                        "${snapshot.relative(source.file)}:" +
                            it.range.map { range -> range.begin.line }.orElse(-1)
                    }
            }
            check(livingDropTypeEscapes.isEmpty()) {
                "LivingDropsEvent appears outside an admitted handler parameter: " +
                    livingDropTypeEscapes
            }

            val methods = projectMethods()
            val policyMethod = methods.single {
                it.source.file == policy.file &&
                    it.method.nameAsString == policy.methodName &&
                    it.method.range.map { range -> range.begin.line }.orElse(-1) ==
                    policy.methodBeginLine
            }
            val policyCalls = snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodCallExpr::class.java)
                    .filter {
                        it.nameAsString == policy.methodName &&
                            it.arguments.size == policyMethod.method.parameters.size
                    }
                    .map {
                        "${snapshot.relative(source.file)}:" +
                            it.range.map { range -> range.begin.line }.orElse(-1)
                    }
            }
            check(policyCalls.isEmpty()) {
                "Looting policy handler is also called directly by project source: " +
                    policyCalls
            }
            val policyReferences = snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodReferenceExpr::class.java)
                    .filter { reference ->
                        reference.identifier == policy.methodName &&
                            source.resolvesProjectTypeStrict(
                                reference.scope.toString(),
                                snapshot.declaredTypes
                            ) == policy.ownerClass
                    }
                    .map {
                        "${snapshot.relative(source.file)}:" +
                            it.range.map { range -> range.begin.line }.orElse(-1)
                    }
            }
            check(policyReferences.isEmpty()) {
                "Looting policy handler has project method-reference consumers: " +
                    policyReferences
            }
            val policyStaticImports = snapshot.sources.flatMap { source ->
                source.unit.imports.filter {
                    it.isStatic && (
                        it.nameAsString == "${policy.ownerClass}.${policy.methodName}" ||
                            it.isAsterisk && it.nameAsString == policy.ownerClass
                        )
                }.map { snapshot.relative(source.file) }
            }
            check(policyStaticImports.isEmpty()) {
                "Looting policy handler is statically imported by project source: " +
                    policyStaticImports
            }
            val damageMethods = methods.filter {
                it.source.resolves(it.method.type, DAMAGE_SOURCE)
            }
            val keyBearingDamageMethods = damageMethods.filter { ref ->
                returnValueTracesPolicyKey(
                    ref,
                    methods,
                    policy,
                    emptySet(),
                    mutableSetOf()
                )
            }
            check(keyBearingDamageMethods.isNotEmpty()) {
                "No project DamageSource producer carries ${policy.keyExpression}"
            }
            val safePolicyProducers = keyBearingDamageMethods.mapNotNull { producer ->
                proveEntityFreeDamageProducer(
                    producer,
                    methods,
                    mutableSetOf(),
                    policy,
                    emptySet()
                )?.let { producer to it }
            }
            check(safePolicyProducers.size == 1) {
                val safeRoots = safePolicyProducers.map { it.first }.toSet()
                val unsafe = keyBearingDamageMethods - safeRoots
                if (unsafe.isNotEmpty()) {
                    "Policy damage source has an entity-bearing or unresolved construction: " +
                        unsafe.joinToString { it.display }
                } else {
                    "Expected one policy damage-source producer, found " +
                        safeRoots.joinToString { it.display }
                }
            }

            val (seed, constructionMethods) = safePolicyProducers.single()
            val queue = ArrayDeque<MethodRef>()
            val producerMethods = linkedSetOf(seed)
            queue += seed
            var terminalHurtCalls = 0
            while (queue.isNotEmpty()) {
                val producer = queue.removeFirst()
                check(producer.method.isStatic) {
                    "Policy DamageSource producer must be static: ${producer.display}"
                }
                val calls = resolveCallsTo(producer, methods)
                check(calls.isNotEmpty()) {
                    "Policy damage producer has no source consumer: ${producer.display}"
                }
                calls.forEach { call ->
                    val parentCall = call.findAncestor(MethodCallExpr::class.java).orElse(null)
                    val directHurt = parentCall != null &&
                        parentCall.nameAsString == "hurt" &&
                        parentCall.arguments.size == 2 &&
                        parentCall.arguments.indexOf(call) == 0
                    if (directHurt) {
                        terminalHurtCalls++
                        return@forEach
                    }
                    val returnStmt = call.findAncestor(ReturnStmt::class.java).orElse(null)
                    val ownerMethod =
                        call.findAncestor(MethodDeclaration::class.java).orElse(null)
                    val wrapper = ownerMethod?.let { method ->
                        methods.singleOrNull { it.method === method }
                    }
                    val exactReturn = returnStmt?.expression?.orElse(null) === call
                    check(wrapper != null && exactReturn &&
                        wrapper.source.resolves(wrapper.method.type, DAMAGE_SOURCE)
                    ) {
                        "Policy DamageSource escapes the admitted hurt/return graph at " +
                            "${call.range.orElse(null)} in " +
                            "${call.findCompilationUnit().flatMap { it.storage }.map { it.path }.orElse(null)}"
                    }
                    if (producerMethods.add(wrapper)) queue += wrapper
                }
            }
            check(terminalHurtCalls > 0) {
                "Policy DamageSource graph reaches no direct hurt(source, amount) consumer"
            }
            val allProducerMethods = constructionMethods + producerMethods
            val producerNameArities = allProducerMethods.map {
                it.method.nameAsString to it.method.parameters.size
            }.toSet()
            val unresolvedProducerCalls = snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodCallExpr::class.java)
                    .filter {
                        (it.nameAsString to it.arguments.size) in producerNameArities
                    }
                    .mapNotNull { call ->
                        val ownerMethod =
                            call.findAncestor(MethodDeclaration::class.java).orElse(null)
                        val ownerRef = ownerMethod?.let { declaration ->
                            methods.singleOrNull { it.method === declaration }
                        }
                        val target = ownerRef?.let {
                            resolveProjectCall(call, it, methods).singleOrNull()
                        }
                        if (target != null && target in allProducerMethods) {
                            null
                        } else {
                            "${snapshot.relative(source.file)}:" +
                                call.range.map { range -> range.begin.line }.orElse(-1) +
                                " (${call})"
                        }
                    }
            }
            check(unresolvedProducerCalls.isEmpty()) {
                "Policy DamageSource producer call has an unresolved or noncanonical " +
                    "receiver: $unresolvedProducerCalls"
            }
            val methodReferences = snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodReferenceExpr::class.java)
                    .filter { reference ->
                        allProducerMethods.any {
                            reference.identifier == it.method.nameAsString
                        }
                    }
                    .map {
                        "${snapshot.relative(source.file)}:" +
                            it.range.map { range -> range.begin.line }.orElse(-1) +
                            " (${it})"
                    }
            }
            check(methodReferences.isEmpty()) {
                "A same-name Policy DamageSource method reference is outside the admitted " +
                    "call graph: " +
                    methodReferences
            }
            val producerStaticImports = snapshot.sources.flatMap { source ->
                source.unit.imports.filter { imported ->
                    imported.isStatic && allProducerMethods.any { producer ->
                        if (imported.isAsterisk) {
                            imported.nameAsString == producer.ownerClass
                        } else {
                            imported.nameAsString ==
                                "${producer.ownerClass}.${producer.method.nameAsString}"
                        }
                    }
                }.map { snapshot.relative(source.file) }
            }
            check(producerStaticImports.isEmpty()) {
                "Policy DamageSource producers are statically imported outside the " +
                    "resolvable call graph: $producerStaticImports"
            }

            val countMethod = methods.single {
                it.source.file == countMixin.file &&
                    it.method.nameAsString == countMixin.injectedMethod
            }
            val livingDropMethods = methods.filter { ref ->
                ref.method.parameters.any { parameter ->
                    LIVING_DROPS_EVENTS.any {
                        ref.source.resolves(parameter.type, it)
                    }
                }
            }.toSet()
            val escapedKeyUses = snapshot.sources.flatMap { source ->
                policyKeyReferences(source, policy).mapNotNull { use ->
                    val owner = use.findAncestor(MethodDeclaration::class.java).orElse(null)
                    val ownerRef = owner?.let { declaration ->
                        methods.singleOrNull { it.method === declaration }
                    }
                    if (isAdmittedPolicyKeyReference(
                            source,
                            use,
                            ownerRef,
                            policy,
                            constructionMethods,
                            producerMethods,
                            livingDropMethods,
                            policyMethod,
                            countMethod
                        )
                    ) {
                        null
                    } else {
                        "${snapshot.relative(source.file)}:" +
                            use.range.map { range -> range.begin.line }.orElse(-1) +
                            " (${use})"
                    }
                }
            }
            val staticKeyImports = snapshot.sources.flatMap { source ->
                source.unit.imports.filter {
                    it.isStatic && (
                        it.nameAsString == "${policy.keyOwner}.${policy.keyField}" ||
                            it.isAsterisk && it.nameAsString == policy.keyOwner
                        )
                }.map { snapshot.relative(source.file) }
            }
            check(escapedKeyUses.isEmpty() && staticKeyImports.isEmpty()) {
                "Policy damage key has an unresolved alias or unsupported use: " +
                    (escapedKeyUses + staticKeyImports)
            }

            // The count mixin is the only project-side loot-table count consumer.
            val legacyFunctionRefs = snapshot.sources.filter { source ->
                source.unit.imports.any {
                    !it.isStatic && it.nameAsString == LEGACY_FUNCTION
                } || source.unit.findAll(ClassOrInterfaceType::class.java).any {
                    resolvesLegacyFunction(source, it.asString())
                } || source.unit.findAll(ClassExpr::class.java).any {
                    resolvesLegacyFunction(source, it.type.asString())
                }
            }
            check(legacyFunctionRefs.size == 1 &&
                legacyFunctionRefs.single().file == countMixin.file
            ) {
                "Additional LootingEnchantFunction consumers exist: " +
                    legacyFunctionRefs.joinToString { snapshot.relative(it.file) }
            }
            return ConsumerGraph(seed.display, terminalHurtCalls)
        }

        private fun policyKeyReferences(
            source: SourceUnit,
            policy: CapturedPolicy
        ): List<Expression> {
            val qualified = source.unit.findAll(FieldAccessExpr::class.java)
                .filter { it.nameAsString == policy.keyField }
            // Any bare same-token reference is ambiguous without a symbol solver. The admitted
            // subset proves its context below or rejects it, including inherited/static aliases.
            val bare = source.unit.findAll(NameExpr::class.java)
                .filter { it.nameAsString == policy.keyField }
            return qualified + bare
        }

        private fun isAdmittedPolicyKeyReference(
            source: SourceUnit,
            use: Expression,
            owner: MethodRef?,
            policy: CapturedPolicy,
            constructionMethods: Set<MethodRef>,
            producerMethods: Set<MethodRef>,
            livingDropMethods: Set<MethodRef>,
            policyMethod: MethodRef,
            countMethod: MethodRef
        ): Boolean {
            val canonical = isCanonicalPolicyKeyUse(source, use, owner, policy)
            if (!canonical) return false
            if (owner != null &&
                (owner in constructionMethods || owner in producerMethods)
            ) return true

            val call = use.findAncestor(MethodCallExpr::class.java).orElse(null)
            val directCallArgument = call != null &&
                call.arguments.any { unwrap(it) === use }
            if (call != null &&
                directCallArgument &&
                call.nameAsString == "is" &&
                owner != null &&
                owner in livingDropMethods + setOf(policyMethod, countMethod)
            ) return true

            if (isDeclarativeDamageRegistration(source, use, owner, policy)) return true
            return isDeclarativeDamageTag(use, owner)
        }

        private fun isCanonicalPolicyKeyUse(
            source: SourceUnit,
            use: Expression,
            owner: MethodRef?,
            policy: CapturedPolicy
        ): Boolean = when (use) {
            is FieldAccessExpr ->
                canonicalProjectField(source, use) == policy.keyExpression
            is NameExpr -> {
                owner?.ownerClass == policy.keyOwner &&
                    owner.method.parameters.none {
                        it.nameAsString == policy.keyField
                    } &&
                    owner.method.findAll(VariableDeclarator::class.java).none {
                        it.nameAsString == policy.keyField
                    }
            }
            else -> false
        }

        private fun isDeclarativeDamageRegistration(
            source: SourceUnit,
            use: Expression,
            owner: MethodRef?,
            policy: CapturedPolicy
        ): Boolean {
            if (owner == null || owner.ownerClass != policy.keyOwner ||
                !owner.method.type.isVoidType ||
                !owner.method.isPublic ||
                !owner.method.isStatic ||
                owner.method.parameters.size != 1
            ) return false
            val contextParameter = owner.method.parameters.single()
            val contextType = when {
                resolvesExactExternalGeneric(
                    source,
                    contextParameter.type,
                    BOOTSTRAP_CONTEXT,
                    DAMAGE_TYPE
                ) -> BOOTSTRAP_CONTEXT
                artifacts.legacyBootstrapContextAbi &&
                    resolvesExactExternalGeneric(
                        source,
                        contextParameter.type,
                        LEGACY_BOOTSTRAP_CONTEXT,
                        DAMAGE_TYPE
                    ) -> LEGACY_BOOTSTRAP_CONTEXT
                else -> return false
            }
            val creation = use.findAncestor(ObjectCreationExpr::class.java)
                .orElse(null) ?: return false
            if (creation.arguments.size != 1 || unwrap(creation.arguments.single()) !== use ||
                !hasSourceOwnedDamageRegistrationAbi(source, creation, contextType)
            ) return false
            val statement = creation.findAncestor(ExpressionStmt::class.java)
                .orElse(null) ?: return false
            val root = unwrap(statement.expression) as? MethodCallExpr ?: return false
            return root.nameAsString == "register" &&
                root.arguments.size == 1 &&
                (unwrap(root.arguments.single()) as? NameExpr)?.nameAsString ==
                    contextParameter.nameAsString &&
                root.findAll(ObjectCreationExpr::class.java).any { it === creation }
        }

        private fun hasSourceOwnedDamageRegistrationAbi(
            useSource: SourceUnit,
            creation: ObjectCreationExpr,
            contextType: String
        ): Boolean {
            if (useSource.unit.findAll(TypeDeclaration::class.java).any {
                    it.nameAsString == creation.type.nameAsString
                }
            ) return false
            val owner = useSource.resolvesProjectTypeStrict(
                creation.type.nameWithScope,
                snapshot.declaredTypes
            ) ?: return false
            val declarations = snapshot.sources.flatMap { candidate ->
                candidate.unit.types.mapNotNull { type ->
                    val canonical = if (candidate.packageName.isBlank()) type.nameAsString
                    else "${candidate.packageName}.${type.nameAsString}"
                    if (canonical == owner && type is ClassOrInterfaceDeclaration) {
                        candidate to type
                    } else {
                        null
                    }
                }
            }
            if (declarations.size != 1) return false
            val (builderSource, builder) = declarations.single()
            if (!builder.isPublic || builder.isInterface) return false
            val constructors = builder.constructors.filter { constructor ->
                constructor.isPublic &&
                    constructor.parameters.size == 1 &&
                    resolvesExactExternalGeneric(
                        builderSource,
                        constructor.parameters.single().type,
                        RESOURCE_KEY,
                        DAMAGE_TYPE
                    )
            }
            if (constructors.size != 1) return false
            val registerMethods = builder.methods.filter { method ->
                method.nameAsString == "register" &&
                    method.isPublic &&
                    !method.isStatic &&
                    resolvesExactExternalType(builderSource, method.type, DAMAGE_TYPE) &&
                    method.parameters.size == 1 &&
                    resolvesExactExternalGeneric(
                        builderSource,
                        method.parameters.single().type,
                        contextType,
                        DAMAGE_TYPE
                    )
            }
            return registerMethods.size == 1
        }

        private fun resolvesExactExternalGeneric(
            source: SourceUnit,
            type: Type,
            canonical: String,
            argumentCanonical: String
        ): Boolean {
            val declared = type as? ClassOrInterfaceType ?: return false
            val arguments = declared.typeArguments.orElse(null) ?: return false
            return arguments.size == 1 &&
                resolvesExternalNameStrict(source, declared.nameWithScope, canonical) &&
                resolvesExactExternalType(source, arguments.single(), argumentCanonical)
        }

        private fun resolvesExactExternalType(
            source: SourceUnit,
            type: Type,
            canonical: String
        ): Boolean {
            val declared = type as? ClassOrInterfaceType ?: return false
            if (declared.typeArguments.isPresent) return false
            return resolvesExternalNameStrict(source, declared.nameWithScope, canonical)
        }

        private fun resolvesExternalNameStrict(
            source: SourceUnit,
            name: String,
            canonical: String
        ): Boolean {
            if (canonical in snapshot.declaredTypes) return false
            val simple = canonical.substringAfterLast('.')
            if (source.unit.findAll(TypeDeclaration::class.java).any {
                    it.nameAsString == simple
                }
            ) return false
            if (name == canonical) return true
            if (name != simple) return false
            val imports = source.unit.imports.filter {
                !it.isStatic && !it.isAsterisk && it.name.identifier == simple
            }
            return imports.size == 1 && imports.single().nameAsString == canonical
        }

        private fun isDeclarativeDamageTag(
            use: Expression,
            owner: MethodRef?
        ): Boolean {
            if (owner == null ||
                owner.method.nameAsString != "addTags" ||
                !owner.method.type.isVoidType
            ) return false
            val add = use.findAncestor(MethodCallExpr::class.java).orElse(null)
                ?: return false
            if (add.nameAsString != "add" ||
                add.arguments.none { unwrap(it) === use }
            ) return false
            val tag = add.scope.orElse(null) as? MethodCallExpr ?: return false
            if (tag.nameAsString != "tag" || tag.arguments.size != 1) return false
            val statement = add.findAncestor(ExpressionStmt::class.java).orElse(null)
                ?: return false
            return unwrap(statement.expression) === add
        }

        private fun resolvesLegacyFunction(
            source: SourceUnit,
            typeName: String
        ): Boolean {
            val name = typeName.substringBefore('<').trim()
            if (name == LEGACY_FUNCTION) return true
            if (name != "LootingEnchantFunction") return false
            val explicit = source.unit.imports.filter {
                !it.isStatic && !it.isAsterisk &&
                    it.name.identifier == "LootingEnchantFunction"
            }
            if (explicit.any { it.nameAsString == LEGACY_FUNCTION }) return true
            if (explicit.isNotEmpty() ||
                source.unit.imports.any { !it.isStatic && it.isAsterisk }
            ) return false
            if (source.unit.findAll(TypeDeclaration::class.java).any {
                    it.nameAsString == "LootingEnchantFunction"
                }
            ) return false
            val samePackage = if (source.packageName.isBlank()) "LootingEnchantFunction"
            else "${source.packageName}.LootingEnchantFunction"
            return samePackage !in snapshot.declaredTypes
        }

        fun removePolicyHandler(policy: CapturedPolicy): String {
            val source = snapshot.sources.single { it.file == policy.file }
            val copy = source.unit.clone()
            val method = copy.findAll(MethodDeclaration::class.java).single {
                it.nameAsString == policy.methodName &&
                    it.range.map { range -> range.begin.line }.orElse(-1) ==
                    policy.methodBeginLine
            }
            check(method.remove()) { "Failed to remove captured policy handler" }
            copy.imports.filter { it.nameAsString in LEGACY_EVENTS }.forEach { it.remove() }
            return copy.toString()
        }

        fun transformCountMixin(mixin: CapturedCountMixin): String {
            val source = snapshot.sources.single { it.file == mixin.file }
            val copy = source.unit.clone()
            val type = copy.findAll(ClassOrInterfaceDeclaration::class.java).single {
                val canonical = if (source.packageName.isBlank()) it.nameAsString
                else "${source.packageName}.${it.nameAsString}"
                canonical == mixin.className &&
                    it.range.map { range -> range.begin.line }.orElse(-1) ==
                    mixin.classBeginLine
            }
            val mixinAnnotation = type.annotations.single { it.nameAsString == "Mixin" }
            setMixinTarget(mixinAnnotation, "EnchantedCountIncreaseFunction")
            copy.imports.filter { it.nameAsString == LEGACY_FUNCTION }.forEach { it.remove() }
            addImport(copy, TARGET_FUNCTION)
            addImport(copy, "net.minecraft.core.Holder")
            addImport(copy, "net.minecraft.world.item.enchantment.Enchantment")
            addImport(copy, "net.minecraft.world.item.enchantment.Enchantments")

            val shadowMethod = type.methods.single { it.nameAsString == mixin.hasLimitMethod }
            check(shadowMethod.remove()) { "Failed to remove inaccessible hasLimit shadow" }
            type.fields.flatMap { it.variables }.filter {
                it.nameAsString == mixin.valueField || it.nameAsString == mixin.limitField
            }.forEach { variable ->
                val field = variable.findAncestor(
                    com.github.javaparser.ast.body.FieldDeclaration::class.java
                ).orElseThrow()
                field.modifiers.clear()
                field.addModifier(com.github.javaparser.ast.Modifier.Keyword.PRIVATE)
            }
            val injected = type.methods.single { it.nameAsString == mixin.injectedMethod }
            val body = injected.body.orElseThrow()
            val hasLimitCalls = body.findAll(MethodCallExpr::class.java).filter {
                it.nameAsString == mixin.hasLimitMethod && it.arguments.isEmpty()
            }
            check(hasLimitCalls.size == 1)
            hasLimitCalls.single().replace(
                StaticJavaParser.parseExpression("(this.${mixin.limitField} > 0)")
            )
            body.addStatement(
                0,
                StaticJavaParser.parseStatement(
                    "if (!this.enchantment.is(Enchantments.LOOTING)) return;"
                )
            )

            val field = type.addField(
                "Holder<Enchantment>",
                "enchantment",
                com.github.javaparser.ast.Modifier.Keyword.PRIVATE
            )
            field.addAnnotation("Shadow")
            field.addAnnotation("Final")
            val result = copy.toString()
            validateGeneratedJava("retargeted count mixin", result)
            return result
        }

        private fun projectCalls(name: String): List<String> =
            snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodCallExpr::class.java)
                    .filter { it.nameAsString == name }
                    .map {
                        "${snapshot.relative(source.file)}:" +
                            it.range.map { range -> range.begin.line }.orElse(-1)
                    }
            }

        private fun projectMethods(): List<MethodRef> =
            snapshot.sources.flatMap { source ->
                source.unit.findAll(MethodDeclaration::class.java).map {
                    MethodRef(source, it, source.owner(it))
                }
            }

        private fun proveEntityFreeDamageProducer(
            method: MethodRef,
            methods: List<MethodRef>,
            visiting: MutableSet<String>,
            policy: CapturedPolicy,
            keyParameters: Set<String>
        ): Set<MethodRef>? {
            if (!visiting.add(method.signature) || !method.method.isStatic) return null
            val body = method.method.body.orElse(null) ?: return null
            val returnStatement = body.statements.lastOrNull() as? ReturnStmt ?: return null
            val returnExpression = returnStatement.expression.orElse(null) ?: return null
            if (body.findAll(ReturnStmt::class.java).size != 1) return null
            val terminal = unwrap(returnExpression) is ObjectCreationExpr
            if (terminal) {
                if (body.statements.size != 2 ||
                    !isExactDamageRegistryDeclaration(
                        body.statements.first(),
                        returnExpression,
                        method,
                        keyParameters
                    )
                ) return null
            } else if (body.statements.size != 1) {
                return null
            }
            val nested = proveEntityFreeDamageExpression(
                returnExpression,
                method,
                methods,
                visiting,
                policy,
                keyParameters
            ) ?: return null
            return nested + method
        }

        private fun isExactDamageRegistryDeclaration(
            statement: Statement,
            returnExpression: Expression,
            method: MethodRef,
            keyParameters: Set<String>
        ): Boolean {
            val registry = singleDeclaredVariable(statement) ?: return false
            if (!method.source.resolves(registry.type, REGISTRY) ||
                !registry.type.isClassOrInterfaceType
            ) return false
            val typeArguments =
                registry.type.asClassOrInterfaceType().typeArguments.orElse(null)
                    ?: return false
            if (typeArguments.size != 1 ||
                !method.source.resolves(typeArguments.single(), DAMAGE_TYPE)
            ) return false

            val registryOrThrow =
                unwrap(registry.initializer.orElse(null) ?: return false) as?
                    MethodCallExpr ?: return false
            if (registryOrThrow.nameAsString != "registryOrThrow" ||
                registryOrThrow.arguments.size != 1 ||
                normalized(registryOrThrow.arguments.single().toString()) !=
                "Registries.DAMAGE_TYPE"
            ) return false
            val registryAccess = registryOrThrow.scope.orElse(null) as?
                MethodCallExpr ?: return false
            if (registryAccess.nameAsString != "registryAccess" ||
                registryAccess.arguments.isNotEmpty()
            ) return false
            val level = unwrap(registryAccess.scope.orElse(null) ?: return false) as?
                NameExpr ?: return false
            if (level.nameAsString in keyParameters ||
                method.method.parameters.none { it.nameAsString == level.nameAsString }
            ) return false

            val construction = unwrap(returnExpression) as? ObjectCreationExpr ?: return false
            if (!method.source.resolvesName(construction.type.asString(), DAMAGE_SOURCE) ||
                construction.arguments.size != 1
            ) return false
            val holder = unwrap(construction.arguments.single()) as?
                MethodCallExpr ?: return false
            if (holder.nameAsString != "getHolderOrThrow" ||
                holder.scope.map { normalized(it.toString()) }.orElse("") !=
                registry.nameAsString ||
                holder.arguments.size != 1
            ) return false
            val key = unwrap(holder.arguments.single())
            return when (key) {
                is NameExpr -> key.nameAsString in keyParameters
                is FieldAccessExpr -> canonicalProjectField(method.source, key) != null
                else -> false
            }
        }

        private fun proveEntityFreeDamageExpression(
            expression: Expression,
            owner: MethodRef,
            methods: List<MethodRef>,
            visiting: MutableSet<String>,
            policy: CapturedPolicy,
            keyParameters: Set<String>
        ): Set<MethodRef>? {
            val unwrapped = unwrap(expression)
            if (unwrapped is ObjectCreationExpr &&
                owner.source.resolvesName(unwrapped.type.asString(), DAMAGE_SOURCE)
            ) {
                return if (unwrapped.arguments.size == 1 &&
                    expressionTracesPolicyKey(
                        owner.source,
                        unwrapped.arguments.single(),
                        owner,
                        methods,
                        policy,
                        keyParameters,
                        mutableSetOf()
                    )
                ) emptySet() else null
            }
            if (unwrapped !is MethodCallExpr) return null
            val targets = resolveProjectCall(unwrapped, owner, methods)
            if (targets.size != 1) return null
            val target = targets.single()
            val mappedKeyParameters = unwrapped.arguments.mapIndexedNotNull { index, argument ->
                if (expressionTracesPolicyKey(
                        owner.source,
                        argument,
                        owner,
                        methods,
                        policy,
                        keyParameters,
                        mutableSetOf()
                    )
                ) target.method.parameters[index].nameAsString else null
            }.toSet()
            if (mappedKeyParameters.isEmpty()) return null
            return proveEntityFreeDamageProducer(
                target,
                methods,
                visiting,
                policy,
                mappedKeyParameters
            )
        }

        private fun returnValueTracesPolicyKey(
            method: MethodRef,
            methods: List<MethodRef>,
            policy: CapturedPolicy,
            keyParameters: Set<String>,
            visiting: MutableSet<String>
        ): Boolean {
            val visitKey = "${method.signature}|${keyParameters.sorted().joinToString(",")}"
            if (!visiting.add(visitKey)) return false
            val returns = method.method.findAll(ReturnStmt::class.java)
                .mapNotNull { it.expression.orElse(null) }
            if (returns.size != 1) return false
            return expressionTracesPolicyKey(
                method.source,
                returns.single(),
                method,
                methods,
                policy,
                keyParameters,
                visiting
            )
        }

        private fun expressionTracesPolicyKey(
            source: SourceUnit,
            expression: Expression,
            owner: MethodRef,
            methods: List<MethodRef>,
            policy: CapturedPolicy,
            keyParameters: Set<String>,
            visiting: MutableSet<String>
        ): Boolean {
            val unwrapped = unwrap(expression)
            if (canonicalProjectField(source, unwrapped) == policy.keyExpression) return true
            if (unwrapped is NameExpr && unwrapped.nameAsString in keyParameters) return true
            if (unwrapped is MethodCallExpr) {
                val targets = resolveProjectCall(unwrapped, owner, methods)
                if (targets.size == 1) {
                    val target = targets.single()
                    val mapped = unwrapped.arguments.mapIndexedNotNull { index, argument ->
                        if (expressionTracesPolicyKey(
                                source,
                                argument,
                                owner,
                                methods,
                                policy,
                                keyParameters,
                                visiting
                            )
                        ) target.method.parameters[index].nameAsString else null
                    }.toSet()
                    if (returnValueTracesPolicyKey(
                            target,
                            methods,
                            policy,
                            mapped,
                            visiting
                        )
                    ) return true
                }
            }
            return unwrapped.childNodes
                .filterIsInstance<Expression>()
                .any {
                    expressionTracesPolicyKey(
                        source,
                        it,
                        owner,
                        methods,
                        policy,
                        keyParameters,
                        visiting
                    )
                }
        }

        private fun resolveCallsTo(
            target: MethodRef,
            methods: List<MethodRef>
        ): List<MethodCallExpr> = methods.flatMap { owner ->
            owner.method.findAll(MethodCallExpr::class.java).filter { call ->
                resolveProjectCall(call, owner, methods).singleOrNull()?.signature ==
                    target.signature
            }
        }

        private fun resolveProjectCall(
            call: MethodCallExpr,
            owner: MethodRef,
            methods: List<MethodRef>
        ): List<MethodRef> {
            val cached = projectCallResolutionCache[call]
            if (cached != null && cached.ownerMethod === owner.method && cached.methods === methods) {
                return cached.targets
            }
            val index = projectMethodIndex?.takeIf { it.methods === methods }
                ?: ProjectMethodIndex(methods, methods.groupBy {
                    MethodLookupKey(it.method.nameAsString, it.method.parameters.size)
                }).also { projectMethodIndex = it }
            val byName = index.byNameAndArity[
                MethodLookupKey(call.nameAsString, call.arguments.size)
            ].orEmpty()
            val scope = call.scope.orElse(null)
            val targets = if (scope == null || scope is com.github.javaparser.ast.expr.ThisExpr) {
                byName.filter { it.ownerClass == owner.ownerClass }
            } else {
                val ownerType = owner.source.resolvesProjectTypeStrict(
                    scope.toString(), snapshot.declaredTypes
                )
                if (ownerType == null) emptyList() else byName.filter { it.ownerClass == ownerType }
            }
            projectCallResolutionCache[call] = CachedProjectCallResolution(owner.method, methods, targets)
            return targets
        }
    }

    private data class MethodRef(
        val source: SourceUnit,
        val method: MethodDeclaration,
        val ownerClass: String
    ) {
        val signature: String =
            "$ownerClass#${method.nameAsString}(" +
                method.parameters.joinToString(",") { normalized(it.type.toString()) } +
                "):${normalized(method.type.toString())}"
        val display: String =
            "${source.file.fileName}#${method.nameAsString}(" +
                method.parameters.joinToString(",") { it.type.toString() } + ")"
    }

    private data class GeneratedNames(
        val policyType: String,
        val mobMixinType: String,
        val helperMixinType: String,
        val mobHandler: String,
        val helperHandler: String
    ) {
        companion object {
            fun allocate(snapshot: ProjectSnapshot, packageName: String): GeneratedNames {
                val existingTypes = snapshot.sources.flatMap { source ->
                    source.unit.findAll(ClassOrInterfaceDeclaration::class.java).map {
                        if (source.packageName.isBlank()) it.nameAsString
                        else "${source.packageName}.${it.nameAsString}"
                    }
                }.toMutableSet()
                fun canonical(simple: String): String =
                    if (packageName.isBlank()) simple else "$packageName.$simple"
                fun type(base: String): String {
                    var index = 1
                    var candidate = base
                    while (canonical(candidate) in existingTypes) {
                        index++
                        candidate = "$base$index"
                    }
                    existingTypes += canonical(candidate)
                    return candidate
                }

                val existingMethods = snapshot.sources.flatMap { source ->
                    source.unit.findAll(MethodDeclaration::class.java).map { it.nameAsString }
                }.toMutableSet()
                fun method(base: String): String {
                    var index = 1
                    var candidate = base
                    while (candidate in existingMethods) {
                        index++
                        candidate = "$base$index"
                    }
                    existingMethods += candidate
                    return candidate
                }
                return GeneratedNames(
                    policyType = type("ModporterLegacyLootingPolicy"),
                    mobMixinType = type("ModporterLegacyLootingMobMixin"),
                    helperMixinType = type("ModporterLegacyLootingEnchantmentHelperMixin"),
                    mobHandler = method("modporter\$restoreLegacyLootingEquipmentRoll"),
                    helperHandler =
                        method("modporter\$suppressNativeLootingEquipmentEffect")
                )
            }
        }
    }

    private fun transformMixinConfig(
        config: CapturedConfig,
        generatedPackage: String,
        mobMixinType: String,
        helperMixinType: String
    ): String {
        val root = config.unit.json
        val mixins = root["mixins"]?.jsonArray
            ?: error("Owning mixin config has no mixins array: ${config.file}")
        fun entry(simple: String): String {
            if (config.packageName.isBlank()) {
                return if (generatedPackage.isBlank()) simple
                else "$generatedPackage.$simple"
            }
            check(
                generatedPackage == config.packageName ||
                    generatedPackage.startsWith("${config.packageName}.")
            ) {
                "Generated mixin package is outside the owning config package"
            }
            val suffix = generatedPackage.removePrefix(config.packageName).removePrefix(".")
            return if (suffix.isBlank()) simple else "$suffix.$simple"
        }
        val additions = listOf(entry(mobMixinType), entry(helperMixinType))
        check(additions.none { name ->
            mixins.any { it.jsonPrimitive.content == name }
        }) { "Generated mixin entry already exists" }
        val transformed = JsonObject(
            root.mapValues { (key, value) ->
                if (key != "mixins") value
                else buildJsonArray {
                    mixins.forEach { add(it) }
                    additions.forEach { add(JsonPrimitive(it)) }
                }
            }
        )
        return json.encodeToString(JsonObject.serializer(), transformed) + "\n"
    }

    private fun renderEvidence(evidence: Evidence): String {
        val root = buildJsonObject {
            put("schema", JsonPrimitive(1))
            put("policy", buildJsonObject {
                put("source_file", JsonPrimitive(evidence.policy.sourceFile))
                put("owner_class", JsonPrimitive(evidence.policy.ownerClass))
                put("method", JsonPrimitive(evidence.policy.methodName))
                put("damage_key", JsonPrimitive(evidence.policy.keyExpression))
                put("level", JsonPrimitive(evidence.policy.level))
                put("damage_producer", JsonPrimitive(evidence.policy.damageProducer))
                put(
                    "terminal_hurt_calls",
                    JsonPrimitive(evidence.policy.terminalHurtCalls)
                )
            })
            put("existing_count_mixin", buildJsonObject {
                put("source_file", JsonPrimitive(evidence.mixin.sourceFile))
                put("class", JsonPrimitive(evidence.mixin.className))
                put("config_file", JsonPrimitive(evidence.mixin.configFile))
                put("config_entry", JsonPrimitive(evidence.mixin.configEntry))
                put("tail_method", JsonPrimitive(evidence.mixin.injectedMethod))
                put("value_field", JsonPrimitive(evidence.mixin.valueField))
                put("limit_field", JsonPrimitive(evidence.mixin.limitField))
                put("removed_private_shadow", JsonPrimitive(evidence.mixin.hasLimitMethod))
            })
            put("artifacts", buildJsonObject {
                put(
                    "legacy_platform_sha256",
                    JsonPrimitive(evidence.artifacts.legacyPlatformSha256)
                )
                put(
                    "target_platform_sha256",
                    JsonPrimitive(evidence.artifacts.targetPlatformSha256)
                )
                put(
                    "target_sources_sha256",
                    JsonPrimitive(evidence.artifacts.targetSourcesSha256)
                )
                put(
                    "mixin_extras_sha256",
                    JsonPrimitive(evidence.artifacts.mixinExtrasSha256)
                )
                put(
                    "mob_next_float_calls",
                    JsonPrimitive(evidence.artifacts.mobNextFloatCalls)
                )
                put(
                    "helper_named_iteration_calls",
                    JsonPrimitive(evidence.artifacts.helperIterationCalls)
                )
                put(
                    "visitor_public",
                    JsonPrimitive(evidence.artifacts.visitorPublic)
                )
                put(
                    "legacy_bootstap_context_abi",
                    JsonPrimitive(evidence.artifacts.legacyBootstrapContextAbi)
                )
                put(
                    "native_looting_shape",
                    JsonPrimitive(evidence.artifacts.nativeLootingShape)
                )
                put(
                    "verification_basis",
                    JsonPrimitive(evidence.artifacts.verificationBasis)
                )
            })
            put("generated_types", buildJsonArray {
                evidence.generatedTypes.forEach { add(JsonPrimitive(it)) }
            })
            put("generated_methods", buildJsonArray {
                evidence.generatedMethods.forEach { add(JsonPrimitive(it)) }
            })
            put("hooks", buildJsonObject {
                put(
                    "mob",
                    JsonPrimitive(
                        "Mob.dropCustomDeathLoot$MOB_METHOD -> " +
                            "RandomSource.nextFloat()F ordinal 0"
                    )
                )
                put(
                    "equipment",
                    JsonPrimitive(
                        "EnchantmentHelper.processEquipmentDropChance" +
                            "$EQUIPMENT_CHANCE_METHOD -> " +
                            "runIterationOnEquipment$ITERATION_METHOD ordinal 1"
                    )
                )
            })
        }
        return json.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    private fun verifyLegacyConsumerInventory(jar: Path) {
        val expected = linkedMapOf(
            "net/minecraft/world/entity/LivingEntity.class" to
                "ac280a94cc7dc7a2c1d6dfe0ac7c7064068e271f1e387e4fc08012a007e9f7fd",
            "net/minecraft/world/entity/Mob.class" to
                "8e369ceb4d58a83457eb596c449551bddb4dc1fce8ef25b0e4dee5e87a887a3a",
            "net/minecraft/world/entity/boss/wither/WitherBoss.class" to
                "3f7141d154130b0da23dcb1c6a3b386a44f0cd59059a3a08e83438b81aeb2ad8",
            "net/minecraft/world/entity/monster/Creeper.class" to
                "b0cda5ca8a29b4d6ad57c8335bd3d56e94b1ceb4cf499514baf17bccda4dc97b",
            "net/minecraft/world/entity/monster/EnderMan.class" to
                "cb4eac6f75ee681effd0f3a9c628beeb103b321d513edcd2b07bf02dfa4ea493",
            "net/minecraft/world/entity/monster/piglin/Piglin.class" to
                "e5612a8499f48577d37647edae2126180aefc074ebb123672868d32fcf97d8e3",
            "net/minecraft/world/entity/monster/Skeleton.class" to
                "9041361d1a7a6109fcf9a887b396dac753d6068fea44e6e1605b77ae46417a27",
            "net/minecraft/world/entity/monster/WitherSkeleton.class" to
                "408929f1eecdf9797e9ee100a48d8e9e1ae3f207386e260496d1019ba35d08c0",
            "net/minecraft/world/entity/monster/Zombie.class" to
                "493b04252b19442fc01bad3400f99e7d165adcff189d3757d6f53e538723e334"
        )
        val actualConsumers = ZipFile(jar.toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .filter { entry ->
                    zip.getInputStream(entry).use { stream ->
                        stream.readBytes().containsAscii("dropCustomDeathLoot")
                    }
                }
                .map { it.name }
                .toSet()
        }
        check(actualConsumers == expected.keys) {
            "Legacy custom-death consumer inventory changed. " +
                "Expected ${expected.keys}, found $actualConsumers"
        }
        ZipFile(jar.toFile()).use { zip ->
            expected.forEach { (entryName, expectedHash) ->
                val entry = zip.getEntry(entryName)
                    ?: error("Legacy custom-death consumer is missing: $entryName")
                val actualHash = zip.getInputStream(entry).use { sha256(it.readBytes()) }
                check(actualHash == expectedHash) {
                    "Legacy custom-death consumer bytes changed for $entryName: " +
                        "expected $expectedHash, found $actualHash"
                }
            }
        }

        val living = ClassFile.read(
            jar,
            "net/minecraft/world/entity/LivingEntity.class"
        )
        val dropAll = living.exactMethod("dropAllDeathLoot", LEGACY_DROP_ALL_METHOD)
        val routedCalls = dropAll.invocations.filter {
            it.name in setOf(
                "getLootingLevel",
                "dropFromLootTable",
                "dropCustomDeathLoot",
                "onLivingDrops"
            )
        }
        check(
            routedCalls == listOf(
                Invocation(
                    "net/minecraftforge/common/ForgeHooks",
                    "getLootingLevel",
                    "(Lnet/minecraft/world/entity/Entity;" +
                        "Lnet/minecraft/world/entity/Entity;" +
                        "Lnet/minecraft/world/damagesource/DamageSource;)I"
                ),
                Invocation(
                    "net/minecraft/world/entity/LivingEntity",
                    "dropFromLootTable",
                    "(Lnet/minecraft/world/damagesource/DamageSource;Z)V"
                ),
                Invocation(
                    "net/minecraft/world/entity/LivingEntity",
                    "dropCustomDeathLoot",
                    LEGACY_CUSTOM_DEATH_METHOD
                ),
                Invocation(
                    "net/minecraftforge/common/ForgeHooks",
                    "onLivingDrops",
                    "(Lnet/minecraft/world/entity/LivingEntity;" +
                        "Lnet/minecraft/world/damagesource/DamageSource;" +
                        "Ljava/util/Collection;IZ)Z"
                )
            )
        ) {
            "Forge 47.1.33 no longer routes one looting level through the reviewed death consumers"
        }
        val livingCustom = living.exactMethod(
            "dropCustomDeathLoot",
            LEGACY_CUSTOM_DEATH_METHOD
        )
        check(
            livingCustom.access and ACC_PROTECTED == ACC_PROTECTED &&
                livingCustom.access and ACC_STATIC == 0
        ) {
            "Forge 47.1.33 LivingEntity custom-death hook is no longer protected instance"
        }

        val mob = ClassFile.read(jar, "net/minecraft/world/entity/Mob.class")
        val mobCustom = mob.exactMethod("dropCustomDeathLoot", LEGACY_CUSTOM_DEATH_METHOD)
        check(
            mobCustom.invocations.count {
                it.name == "dropCustomDeathLoot" &&
                    it.descriptor == LEGACY_CUSTOM_DEATH_METHOD
            } == 1 &&
                mobCustom.invocations.count {
                    it.owner == "net/minecraft/util/RandomSource" &&
                        it.name == "nextFloat" &&
                        it.descriptor == "()F"
                } == 1
        ) {
            "Forge 47.1.33 Mob no longer consumes the reviewed looting level at one RNG roll"
        }

        expected.keys
            .filterNot {
                it == "net/minecraft/world/entity/LivingEntity.class" ||
                    it == "net/minecraft/world/entity/Mob.class"
            }
            .forEach { entryName ->
                val method = ClassFile.read(jar, entryName)
                    .exactMethod("dropCustomDeathLoot", LEGACY_CUSTOM_DEATH_METHOD)
                check(
                    method.invocations.count {
                        it.name == "dropCustomDeathLoot" &&
                            it.descriptor == LEGACY_CUSTOM_DEATH_METHOD
                    } == 1
                ) {
                    "Forge 47.1.33 custom-death override no longer forwards looting: $entryName"
                }
            }
    }

    private fun verifyLegacyBootstrapContextAbi(jar: Path): Boolean {
        val entryBytes = ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry(LEGACY_BOOTSTRAP_CONTEXT_CLASS)
                ?: error("Forge 47.1.33 legacy BootstapContext is missing")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        check(sha256(entryBytes) == LEGACY_BOOTSTRAP_CONTEXT_SHA256) {
            "Forge 47.1.33 legacy BootstapContext bytes changed"
        }
        val context = ClassFile.read(jar, LEGACY_BOOTSTRAP_CONTEXT_CLASS)
        check(
            context.access == (ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT) &&
                context.fields.isEmpty()
        ) {
            "Forge 47.1.33 legacy BootstapContext ownership changed"
        }
        val expectedMethods = mapOf(
            (
                "register" to
                    "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;" +
                    "Lcom/mojang/serialization/Lifecycle;)" +
                    "Lnet/minecraft/core/Holder\$Reference;"
                ) to (ACC_PUBLIC or ACC_ABSTRACT),
            (
                "register" to
                    "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;)" +
                    "Lnet/minecraft/core/Holder\$Reference;"
                ) to ACC_PUBLIC,
            (
                "lookup" to
                    "(Lnet/minecraft/resources/ResourceKey;)" +
                    "Lnet/minecraft/core/HolderGetter;"
                ) to (ACC_PUBLIC or ACC_ABSTRACT),
            (
                "registryLookup" to
                    "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;"
                ) to ACC_PUBLIC
        )
        check(
            context.methods.map { it.name to it.descriptor }.toSet() == expectedMethods.keys &&
                context.methods.all { method ->
                    method.access == expectedMethods.getValue(method.name to method.descriptor)
                }
        ) {
            "Forge 47.1.33 legacy BootstapContext ABI changed"
        }
        return true
    }

    private fun verifyMixinExtrasApi(jar: Path) {
        val operation = ClassFile.read(jar, MIXIN_EXTRAS_OPERATION_CLASS)
        check(
            operation.access and (ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT) ==
                (ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT)
        ) {
            "MixinExtras Operation is no longer a public interface"
        }
        val call = operation.exactMethod(
            "call",
            "([Ljava/lang/Object;)Ljava/lang/Object;"
        )
        check(
            call.access and (ACC_PUBLIC or ACC_ABSTRACT or ACC_VARARGS) ==
                (ACC_PUBLIC or ACC_ABSTRACT or ACC_VARARGS)
        ) {
            "MixinExtras Operation.call no longer exposes the reviewed varargs contract"
        }

        val wrapOperation = ClassFile.read(jar, MIXIN_EXTRAS_WRAP_OPERATION_CLASS)
        check(
            wrapOperation.access and
                (ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT or ACC_ANNOTATION) ==
                (ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT or ACC_ANNOTATION)
        ) {
            "MixinExtras WrapOperation is no longer a public annotation interface"
        }
        listOf(
            "method" to "()[Ljava/lang/String;",
            "at" to "()[Lorg/spongepowered/asm/mixin/injection/At;",
            "require" to "()I",
            "expect" to "()I"
        ).forEach { (name, descriptor) ->
            wrapOperation.exactMethod(name, descriptor)
        }

        ClassFile.read(jar, MIXIN_EXTRAS_WRAP_INFO_CLASS).exactMethod(
            "parseInjector",
            "(Lorg/objectweb/asm/tree/AnnotationNode;)" +
                "Lorg/spongepowered/asm/mixin/injection/code/Injector;"
        )
        ClassFile.read(jar, MIXIN_EXTRAS_WRAP_INJECTOR_CLASS).exactMethod(
            "inject",
            "(Lorg/spongepowered/asm/mixin/injection/struct/Target;" +
                "Lorg/spongepowered/asm/mixin/injection/struct/InjectionNodes" +
                "\$InjectionNode;)V"
        )
    }

    private fun verifyTargetSources(jar: Path) {
        val mob = readZipText(
            jar,
            "net/minecraft/world/entity/Mob.java"
        )
        val mobRegion = mob.substringAfter(
            "protected void dropCustomDeathLoot"
        ).substringBefore("protected float getEquipmentDropChance")
        check(
            mobRegion.countOccurrences("random.nextFloat()") == 1 &&
                mobRegion.contains("this.random.nextFloat() < f") &&
                mobRegion.contains("EnchantmentHelper.processEquipmentDropChance")
        ) {
            "Target Mob RNG ordinal is no longer the audited equipment comparison"
        }

        val enchantments = readZipText(
            jar,
            "net/minecraft/world/item/enchantment/Enchantments.java"
        )
        val compact = normalized(enchantments)
        val shape =
            ".withEffect(EnchantmentEffectComponents.EQUIPMENT_DROPS," +
                "EnchantmentTarget.ATTACKER,EnchantmentTarget.VICTIM," +
                "newAddValue(LevelBasedValue.perLevel(0.01F)),"
        check(compact.windowed(shape.length).count { it == shape } == 1) {
            "Target sources no longer contain exactly one native Looting " +
                "EQUIPMENT_DROPS AddValue(perLevel(0.01F)) definition"
        }
        val lootingRegion = compact.substringAfter("register(context,LOOTING,", "")
            .substringBefore("register(context,SWEEPING_EDGE,", "")
        check(lootingRegion.isNotBlank() &&
            lootingRegion.contains("LootContext.EntityTarget.ATTACKER") &&
            lootingRegion.contains("EntityType.PLAYER")
        ) {
            "Target native Looting equipment requirement is no longer the verified " +
                "attacking-player condition"
        }

        val helper = readZipText(
            jar,
            "net/minecraft/world/item/enchantment/EnchantmentHelper.java"
        )
        val methodStart = helper.indexOf(
            "public static float processEquipmentDropChance"
        )
        val methodEnd = helper.indexOf(
            "public static void forEachModifier",
            methodStart.coerceAtLeast(0)
        )
        check(methodStart >= 0 && methodEnd > methodStart) {
            "Target helper sources no longer contain the reviewed equipment-chance method"
        }
        verifyTargetEquipmentDropChanceSource(helper.substring(methodStart, methodEnd))
        check(
            helper.contains(
                "public interface EnchantmentInSlotVisitor"
            )
        ) {
            "Target source metadata no longer proves the nested visitor is public"
        }
    }

    internal fun verifyTargetEquipmentDropChanceSource(methodSource: String) {
        val identifier = """[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*"""
        val signature = Regex(
            """public\s+static\s+float\s+processEquipmentDropChance\s*\(\s*""" +
                """ServerLevel\s+($identifier)\s*,\s*""" +
                """LivingEntity\s+($identifier)\s*,\s*""" +
                """DamageSource\s+($identifier)\s*,\s*""" +
                """float\s+($identifier)\s*\)"""
        ).find(methodSource)
            ?: error("Target helper equipment-chance signature changed")
        val victimEntityName = signature.groupValues[2]
        val damageSourceName = signature.groupValues[3]
        val attackerBranch = Regex(
            """if\s*\(\s*${Regex.escape(damageSourceName)}\.getEntity\(\)\s+""" +
                """instanceof\s+LivingEntity\s+($identifier)\s*\)"""
        ).find(methodSource)
            ?: error("Target helper attacker equipment branch changed")
        val attackerEntityName = attackerBranch.groupValues[1]
        val iterations = Regex("""runIterationOnEquipment\s*\(""")
            .findAll(methodSource)
            .toList()
        check(iterations.size == 2) {
            "Target helper sources no longer have two named equipment iterations"
        }
        val firstIteration = iterations[0].range.first
        val secondIteration = iterations[1].range.first
        val attackerBranchStart = attackerBranch.range.first
        check(attackerBranchStart in (firstIteration + 1) until secondIteration) {
            "Target helper attacker branch no longer encloses iteration ordinal 1"
        }
        val victimRegion = methodSource.substring(firstIteration, attackerBranchStart)
        val attackerRegion = methodSource.substring(attackerBranchStart)
        val victimCall = Regex(
            """runIterationOnEquipment\s*\(\s*${Regex.escape(victimEntityName)}\s*,"""
        )
        val attackerCall = Regex(
            """runIterationOnEquipment\s*\(\s*${Regex.escape(attackerEntityName)}\s*,"""
        )
        check(
            victimCall.findAll(victimRegion).count() == 1 &&
                victimRegion.countOccurrences("EnchantmentTarget.VICTIM") == 2 &&
                victimRegion.countOccurrences("EnchantmentTarget.ATTACKER") == 0 &&
                attackerCall.findAll(attackerRegion).count() == 1 &&
                attackerRegion.countOccurrences("EnchantmentTarget.ATTACKER") == 1 &&
                attackerRegion.countOccurrences("EnchantmentTarget.VICTIM") == 1
        ) {
            "Target helper ordinal 1 is no longer the attacker equipment iteration"
        }
    }

    private data class Invocation(
        val owner: String,
        val name: String,
        val descriptor: String
    )

    private data class ClassMethod(
        val access: Int,
        val name: String,
        val descriptor: String,
        val invocations: List<Invocation>
    )

    private data class ClassField(
        val access: Int,
        val name: String,
        val descriptor: String
    )

    private data class ClassFile(
        val access: Int,
        val fields: List<ClassField>,
        val methods: List<ClassMethod>
    ) {
        fun exactMethod(name: String, descriptor: String): ClassMethod =
            methods.singleOrNull { it.name == name && it.descriptor == descriptor }
                ?: error("Missing or ambiguous method $name$descriptor")

        fun exactField(name: String, descriptor: String): ClassField =
            fields.singleOrNull { it.name == name && it.descriptor == descriptor }
                ?: error("Missing or ambiguous field $name:$descriptor")

        companion object {
            fun read(jar: Path, entryName: String): ClassFile {
                val bytes = ZipFile(jar.toFile()).use { zip ->
                    val entry = zip.getEntry(entryName)
                        ?: error("Target class is missing: $entryName")
                    zip.getInputStream(entry).use { it.readBytes() }
                }
                return ClassReader(bytes).read()
            }
        }
    }

    /**
     * Small dependency-free classfile reader used only for exact member descriptors and
     * invocation counts. Unknown classfile tags/opcodes fail preflight.
     */
    private class ClassReader(bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))
        private lateinit var pool: Array<Cp?>

        fun read(): ClassFile {
            check(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid classfile magic" }
            input.readUnsignedShort()
            input.readUnsignedShort()
            readConstantPool()
            val access = input.readUnsignedShort()
            input.readUnsignedShort()
            input.readUnsignedShort()
            repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
            val fields = readMembers(field = true).map {
                ClassField(it.access, it.name, it.descriptor)
            }
            val methods = readMembers(field = false).map {
                ClassMethod(
                    it.access,
                    it.name,
                    it.descriptor,
                    scanInvocations(it.code ?: ByteArray(0))
                )
            }
            skipAttributes()
            return ClassFile(access, fields, methods)
        }

        private fun readConstantPool() {
            val count = input.readUnsignedShort()
            pool = arrayOfNulls(count)
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> pool[index] = Cp.Utf8(input.readUTF())
                    3, 4 -> {
                        input.readInt()
                        pool[index] = Cp.Other
                    }
                    5, 6 -> {
                        input.readLong()
                        pool[index] = Cp.Other
                        index++
                    }
                    7 -> pool[index] = Cp.Class(input.readUnsignedShort())
                    8, 16, 19, 20 -> {
                        input.readUnsignedShort()
                        pool[index] = Cp.Other
                    }
                    9, 10, 11 -> pool[index] = Cp.Ref(
                        input.readUnsignedShort(),
                        input.readUnsignedShort()
                    )
                    12 -> pool[index] = Cp.NameAndType(
                        input.readUnsignedShort(),
                        input.readUnsignedShort()
                    )
                    15 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                        pool[index] = Cp.Other
                    }
                    17, 18 -> {
                        input.readUnsignedShort()
                        input.readUnsignedShort()
                        pool[index] = Cp.Other
                    }
                    else -> error("Unsupported classfile constant-pool tag $tag")
                }
                index++
            }
        }

        private data class RawMember(
            val access: Int,
            val name: String,
            val descriptor: String,
            val code: ByteArray?
        )

        private fun readMembers(field: Boolean): List<RawMember> =
            List(input.readUnsignedShort()) {
                val access = input.readUnsignedShort()
                val name = utf8(input.readUnsignedShort())
                val descriptor = utf8(input.readUnsignedShort())
                var code: ByteArray? = null
                repeat(input.readUnsignedShort()) {
                    val attributeName = utf8(input.readUnsignedShort())
                    val length = input.readInt()
                    if (!field && attributeName == "Code") {
                        input.readUnsignedShort()
                        input.readUnsignedShort()
                        code = ByteArray(input.readInt()).also(input::readFully)
                        repeat(input.readUnsignedShort()) {
                            input.readUnsignedShort()
                            input.readUnsignedShort()
                            input.readUnsignedShort()
                            input.readUnsignedShort()
                        }
                        skipAttributes()
                    } else {
                        skipFully(length)
                    }
                }
                RawMember(access, name, descriptor, code)
            }

        private fun skipAttributes() {
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                skipFully(input.readInt())
            }
        }

        private fun skipFully(length: Int) {
            require(length >= 0)
            var remaining = length
            while (remaining > 0) {
                val skipped = input.skipBytes(remaining)
                check(skipped > 0) { "Truncated classfile attribute" }
                remaining -= skipped
            }
        }

        private fun scanInvocations(code: ByteArray): List<Invocation> {
            val result = mutableListOf<Invocation>()
            var offset = 0
            while (offset < code.size) {
                val opcode = code[offset].toInt() and 0xff
                if (opcode in 0xb6..0xb9) {
                    val index = u2(code, offset + 1)
                    result += invocation(index)
                }
                offset = nextInstruction(code, offset)
            }
            check(offset == code.size) { "Instruction scan did not end at code boundary" }
            return result
        }

        private fun invocation(index: Int): Invocation {
            val ref = pool[index] as? Cp.Ref
                ?: error("Invocation does not reference a method at constant $index")
            val ownerClass = pool[ref.classIndex] as? Cp.Class
                ?: error("Invalid method owner at constant $index")
            val nameAndType = pool[ref.nameAndTypeIndex] as? Cp.NameAndType
                ?: error("Invalid method name/type at constant $index")
            return Invocation(
                utf8(ownerClass.nameIndex),
                utf8(nameAndType.nameIndex),
                utf8(nameAndType.descriptorIndex)
            )
        }

        private fun utf8(index: Int): String =
            (pool[index] as? Cp.Utf8)?.value
                ?: error("Expected UTF8 constant at $index")

        private sealed interface Cp {
            data class Utf8(val value: String) : Cp
            data class Class(val nameIndex: Int) : Cp
            data class Ref(val classIndex: Int, val nameAndTypeIndex: Int) : Cp
            data class NameAndType(
                val nameIndex: Int,
                val descriptorIndex: Int
            ) : Cp
            data object Other : Cp
        }
    }

    private fun nextInstruction(code: ByteArray, offset: Int): Int {
        val opcode = code[offset].toInt() and 0xff
        val length = when (opcode) {
            0x10, 0x12,
            in 0x15..0x19,
            in 0x36..0x3a,
            0xa9, 0xbc -> 2

            0x11, 0x13, 0x14, 0x84,
            in 0x99..0xa8,
            in 0xb2..0xb8,
            0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7 -> 3

            0xb9, 0xba, 0xc8, 0xc9 -> 5
            0xc5 -> 4
            0xaa -> {
                val aligned = align4(offset + 1)
                val low = i4(code, aligned + 4)
                val high = i4(code, aligned + 8)
                check(high >= low) { "Invalid tableswitch bounds" }
                aligned - offset + 12 + (high - low + 1) * 4
            }
            0xab -> {
                val aligned = align4(offset + 1)
                val pairs = i4(code, aligned + 4)
                check(pairs >= 0) { "Invalid lookupswitch pair count" }
                aligned - offset + 8 + pairs * 8
            }
            0xc4 -> {
                val nested = code.getOrNull(offset + 1)?.toInt()?.and(0xff)
                    ?: error("Truncated wide instruction")
                if (nested == 0x84) 6 else 4
            }
            else -> {
                check(opcode <= 0xca || opcode == 0xfe || opcode == 0xff) {
                    "Unknown classfile opcode 0x${opcode.toString(16)}"
                }
                1
            }
        }
        val next = offset + length
        check(next <= code.size) { "Truncated bytecode instruction" }
        return next
    }

    private fun renderTemplate(template: String, values: Map<String, String>): String {
        var result = template
        values.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        val unresolved = Regex("""\{\{[A-Z0-9_]+}}""").findAll(result).map { it.value }.toList()
        check(unresolved.isEmpty()) { "Unresolved template tokens: $unresolved" }
        return result
    }

    private fun containsOwnedLegacyPolicy(
        pass: StructuralRefactorPass,
        root: Path
    ): Boolean {
        val removedTypes = mapOf(
            "LootingLevelEvent" to LEGACY_EVENTS,
            "LootingEnchantFunction" to setOf(LEGACY_FUNCTION)
        )
        val names = removedTypes.keys.joinToString("|") { Regex.escape(it) }
        val token = Regex("""(?<![\w$])(?:$names)(?![\w$])""")
        data class GateUnit(
            val code: String,
            val unit: CompilationUnit
        )

        val units = pass.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.toString().endsWith(".java", ignoreCase = true) }
                .toList()
                .mapNotNull { file ->
                    val source = file.readText()
                    val code = pass.maskJavaCommentsAndLiterals(source)
                    if (!token.containsMatchIn(code)) return@mapNotNull null
                    val result = parser.parse(source)
                    val unit = result.result.orElseThrow {
                        IllegalStateException(
                            "Cannot verify legacy looting ownership in $file; " +
                                "source left unchanged: ${result.problems.joinToString()}"
                        )
                    }
                    GateUnit(code, unit)
                }
        }
        if (units.isEmpty()) return false

        val projectTypes = units.flatMap { source ->
            val packageName =
                source.unit.packageDeclaration.map { it.nameAsString }.orElse("")
            source.unit.findAll(TypeDeclaration::class.java).map { type ->
                if (packageName.isBlank()) type.nameAsString
                else "$packageName.${type.nameAsString}"
            }
        }.toSet()
        return units.any { source ->
            removedTypes.any { (simpleName, canonicalNames) ->
                resolvesRemovedGateType(source.code, source.unit, simpleName, canonicalNames, projectTypes)
            }
        }
    }

    private fun resolvesRemovedGateType(
        code: String,
        unit: CompilationUnit,
        simpleName: String,
        canonicalNames: Set<String>,
        projectTypes: Set<String>
    ): Boolean {
        if (canonicalNames.any { code.contains(it) }) return true
        val imports = unit.imports.filter { !it.isStatic }
        if (imports.any { !it.isAsterisk && it.nameAsString in canonicalNames }) return true
        if (imports.any { imported ->
                imported.isAsterisk && canonicalNames.any {
                    it.substringBeforeLast('.') == imported.nameAsString
                }
            }
        ) return true
        if (imports.any { !it.isAsterisk && it.name.identifier == simpleName }) return false
        if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simpleName }) {
            return false
        }
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val samePackageType =
            if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
        if (samePackageType in projectTypes) return false

        // An earlier text pass may already have removed or renamed the obsolete import.
        // The unresolved simple name keeps this fail-closed, but never admits a lookalike.
        return Regex("""(?<![\w$])${Regex.escape(simpleName)}(?![\w$])""")
            .containsMatchIn(code)
    }

    private fun validateGeneratedJava(label: String, source: String) {
        val result = parser.parse(source)
        check(result.isSuccessful && result.result.isPresent) {
            "Generated $label does not parse: ${result.problems.joinToString()}"
        }
    }

    private fun capturePolicyKey(condition: Expression, local: String): Expression? {
        val expression = unwrap(condition)
        if (expression !is BinaryExpr || expression.operator != BinaryExpr.Operator.OR) return null
        val parts = listOf(unwrap(expression.left), unwrap(expression.right))
        val nullCheck = parts.singleOrNull { isNullCheck(it, local) } ?: return null
        val keyCheck = parts.singleOrNull { it !== nullCheck && isNegatedIsCall(it, local) }
            ?: return null
        val call = (unwrap(keyCheck) as UnaryExpr).expression.let(::unwrap) as MethodCallExpr
        return call.arguments.single().clone()
    }

    private fun isNullCheck(expression: Expression, local: String): Boolean {
        val binary = expression as? BinaryExpr ?: return false
        if (binary.operator != BinaryExpr.Operator.EQUALS) return false
        val values = listOf(unwrap(binary.left).toString(), unwrap(binary.right).toString())
        return values.toSet() == setOf(local, "null")
    }

    private fun isNegatedIsCall(expression: Expression, local: String): Boolean {
        val unary = expression as? UnaryExpr ?: return false
        if (unary.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return false
        val call = unwrap(unary.expression) as? MethodCallExpr ?: return false
        return call.nameAsString == "is" &&
            call.scope.map { it.toString() }.orElse("") == local &&
            call.arguments.size == 1
    }

    private fun isBareReturn(statement: Statement): Boolean {
        if (statement is ReturnStmt) return statement.expression.isEmpty
        if (!statement.isBlockStmt) return false
        val statements = statement.asBlockStmt().statements
        return statements.size == 1 &&
            statements.single() is ReturnStmt &&
            statements.single().asReturnStmt().expression.isEmpty
    }

    private fun mixinTarget(type: ClassOrInterfaceDeclaration): String? {
        val annotation = type.annotations.singleOrNull { it.nameAsString == "Mixin" }
            ?: return null
        val value = when {
            annotation.isSingleMemberAnnotationExpr ->
                annotation.asSingleMemberAnnotationExpr().memberValue
            annotation.isNormalAnnotationExpr ->
                annotation.asNormalAnnotationExpr().pairs.singleOrNull {
                    it.nameAsString == "value"
                }?.value
            else -> null
        } as? ClassExpr ?: return null
        return value.type.asString()
    }

    private fun setMixinTarget(annotation: AnnotationExpr, simpleTarget: String) {
        val parsedTarget: Expression = StaticJavaParser.parseExpression("$simpleTarget.class")
        val replacement = parsedTarget.asClassExpr()
        when {
            annotation.isSingleMemberAnnotationExpr ->
                annotation.asSingleMemberAnnotationExpr().setMemberValue(replacement)
            annotation.isNormalAnnotationExpr -> {
                val pair = annotation.asNormalAnnotationExpr().pairs.single {
                    it.nameAsString == "value"
                }
                pair.value = replacement
            }
            else -> error("Unsupported @Mixin annotation form")
        }
    }

    private fun VariableDeclarator.requireShadowFinal() {
        val field = findAncestor(com.github.javaparser.ast.body.FieldDeclaration::class.java)
            .orElseThrow()
        check(field.variables.size == 1 &&
            initializer.isEmpty &&
            field.modifiers.isEmpty() &&
            field.annotations.map { it.nameAsString }.toSet() == setOf("Shadow", "Final")
        ) {
            "Expected one uninitialized package-private @Shadow @Final field $nameAsString"
        }
    }

    private fun isRunTailInjection(method: MethodDeclaration): Boolean {
        val inject = method.annotations.singleOrNull { it.nameAsString == "Inject" }
            ?: return false
        if (!inject.isNormalAnnotationExpr) return false
        val pairs = inject.asNormalAnnotationExpr().pairs
        if (pairs.size != 2) return false
        val methodValue = pairs.singleOrNull { it.nameAsString == "method" }
            ?.value as? StringLiteralExpr ?: return false
        val at = pairs.singleOrNull { it.nameAsString == "at" }
            ?.value as? AnnotationExpr ?: return false
        if (methodValue.value != "run" || at.nameAsString != "At") return false
        return when {
            at.isSingleMemberAnnotationExpr ->
                (at.asSingleMemberAnnotationExpr().memberValue as? StringLiteralExpr)
                    ?.value == "TAIL"
            at.isNormalAnnotationExpr -> {
                val atPairs = at.asNormalAnnotationExpr().pairs
                atPairs.size == 1 &&
                    atPairs.single().nameAsString == "value" &&
                    (atPairs.single().value as? StringLiteralExpr)?.value == "TAIL"
            }
            else -> false
        }
    }

    private fun addImport(unit: CompilationUnit, canonical: String) {
        if (unit.imports.none { !it.isStatic && !it.isAsterisk && it.nameAsString == canonical }) {
            unit.addImport(canonical)
        }
    }

    private fun unwrap(expression: Expression): Expression {
        var current = expression
        while (current.isEnclosedExpr) current = current.asEnclosedExpr().inner
        return current
    }

    private fun mutation(snapshot: ProjectSnapshot, path: Path, after: ByteArray): Mutation =
        Mutation(snapshot.relative(path), sha256(path), after)

    private fun newMutation(snapshot: ProjectSnapshot, path: Path, after: String): Mutation {
        val normalized = path.toAbsolutePath().normalize()
        check(normalized.startsWith(snapshot.project))
        check(!normalized.exists()) { "Refusing to overwrite generated path: $normalized" }
        return Mutation(
            snapshot.relative(normalized),
            null,
            after.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun resolveInside(project: Path, relative: String): Path {
        val target = project.resolve(relative).toAbsolutePath().normalize()
        check(target.startsWith(project)) { "Plan path escapes project: $relative" }
        return target
    }

    private fun atomicMove(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    private fun deleteOwnedTree(project: Path, directory: Path) {
        val normalizedProject = project.toAbsolutePath().normalize()
        val normalizedDirectory = directory.toAbsolutePath().normalize()
        check(normalizedDirectory.startsWith(normalizedProject))
        check(normalizedDirectory.fileName.toString().startsWith(".modporter-transaction-"))
        if (!normalizedDirectory.exists()) return
        Files.walk(normalizedDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun requireHash(path: Path, expected: String, label: String): String {
        val actual = sha256(path)
        check(actual == expected) {
            "$label artifact hash mismatch for $path: expected $expected, found $actual"
        }
        return actual
    }

    private fun sha256(path: Path): String {
        check(Files.isRegularFile(path)) { "Required file is missing: $path" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun readZipText(jar: Path, entryName: String): String =
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: error("Required source entry is missing: $entryName")
            zip.getInputStream(entry).use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
        }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.toByteArray(StandardCharsets.US_ASCII)
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (start in 0..size - needle.size) {
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) continue@outer
            }
            return true
        }
        return false
    }

    private fun normalized(value: String): String =
        value.filterNot(Char::isWhitespace)

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var start = 0
        while (true) {
            val index = indexOf(needle, start)
            if (index < 0) return count
            count++
            start = index + needle.length
        }
    }

    private fun u2(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun i4(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun align4(value: Int): Int = (value + 3) and -4

    private const val NUMBER_PROVIDER =
        "net.minecraft.world.level.storage.loot.providers.number.NumberProvider"
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PRIVATE = 0x0002
    private const val ACC_PROTECTED = 0x0004
    private const val ACC_STATIC = 0x0008
    private const val ACC_FINAL = 0x0010
    private const val ACC_VARARGS = 0x0080
    private const val ACC_INTERFACE = 0x0200
    private const val ACC_ABSTRACT = 0x0400
    private const val ACC_ANNOTATION = 0x2000

    private val POLICY_TEMPLATE = """
        package {{PACKAGE}};

        import java.util.List;

        {{KEY_IMPORTS}}
        import net.minecraft.advancements.critereon.EntityPredicate;
        import net.minecraft.advancements.critereon.EntityTypePredicate;
        import net.minecraft.core.Holder;
        import net.minecraft.core.registries.Registries;
        import net.minecraft.server.level.ServerLevel;
        import net.minecraft.world.damagesource.DamageSource;
        import net.minecraft.world.entity.EntityType;
        import net.minecraft.world.item.enchantment.Enchantment;
        import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
        import net.minecraft.world.item.enchantment.EnchantmentTarget;
        import net.minecraft.world.item.enchantment.Enchantments;
        import net.minecraft.world.item.enchantment.LevelBasedValue;
        import net.minecraft.world.item.enchantment.TargetedConditionalEffect;
        import net.minecraft.world.item.enchantment.effects.AddValue;
        import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;
        import net.minecraft.world.level.storage.loot.LootContext;
        import net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition;

        final class {{POLICY_TYPE}} {
            private static final int LEVEL = {{LEVEL}};
            private static final int ONE_PERCENT_BITS = Float.floatToIntBits(0.01F);

            private {{POLICY_TYPE}}() {}

            static boolean applies(DamageSource source) {
                return source != null && source.is({{KEY_EXPRESSION}});
            }

            static int level() {
                return LEVEL;
            }

            static float adjustEquipmentRoll(float roll, DamageSource source) {
                if (!applies(source))
                    return roll;
                return Math.max(roll - LEVEL * 0.01F, 0.0F);
            }

            static void verifyNativeLootingEquipmentShape(ServerLevel level) {
                Holder<Enchantment> looting = level.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.LOOTING);
                List<TargetedConditionalEffect<EnchantmentValueEffect>> effects =
                    looting.value().getEffects(EnchantmentEffectComponents.EQUIPMENT_DROPS);
                if (effects.size() != 1)
                    throw incompatible("expected exactly one equipment-drop effect, found " + effects.size());

                TargetedConditionalEffect<EnchantmentValueEffect> targeted = effects.get(0);
                if (targeted.enchanted() != EnchantmentTarget.ATTACKER
                    || targeted.affected() != EnchantmentTarget.VICTIM)
                    throw incompatible("expected ATTACKER -> VICTIM targeting");
                if (!(targeted.requirements().orElse(null)
                        instanceof LootItemEntityPropertyCondition requirement)
                    || requirement.entityTarget() != LootContext.EntityTarget.ATTACKER
                    || requirement.predicate().isEmpty()
                    || !requirement.predicate().get().equals(
                        EntityPredicate.Builder.entity()
                            .entityType(EntityTypePredicate.of(EntityType.PLAYER))
                            .build()
                    ))
                    throw incompatible("expected the native attacking-player requirement");
                if (!(targeted.effect() instanceof AddValue addValue))
                    throw incompatible("expected AddValue, found " + targeted.effect().getClass().getName());
                if (!(addValue.value() instanceof LevelBasedValue.Linear linear))
                    throw incompatible("expected LevelBasedValue.Linear");
                if (Float.floatToIntBits(linear.base()) != ONE_PERCENT_BITS
                    || Float.floatToIntBits(linear.perLevelAboveFirst()) != ONE_PERCENT_BITS)
                    throw incompatible("expected perLevel(0.01F)");
            }

            private static IllegalStateException incompatible(String detail) {
                return new IllegalStateException(
                    "Cannot preserve the legacy looting policy because native Looting changed: " + detail
                );
            }
        }
    """.trimIndent()

    private val MOB_MIXIN_TEMPLATE = """
        package {{PACKAGE}};

        import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
        import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;

        import net.minecraft.server.level.ServerLevel;
        import net.minecraft.util.RandomSource;
        import net.minecraft.world.damagesource.DamageSource;
        import net.minecraft.world.entity.Mob;

        @Mixin(Mob.class)
        public abstract class {{MOB_MIXIN_TYPE}} {
            @WrapOperation(
                method = "dropCustomDeathLoot(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V",
                at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;nextFloat()F",
                    ordinal = 0
                ),
                require = 1,
                expect = 1
            )
            private float {{MOB_HANDLER}}(
                RandomSource random,
                Operation<Float> original,
                ServerLevel level,
                DamageSource damageSource,
                boolean recentlyHit
            ) {
                float roll = original.call(random);
                if ({{POLICY_TYPE}}.applies(damageSource))
                    {{POLICY_TYPE}}.verifyNativeLootingEquipmentShape(level);
                return {{POLICY_TYPE}}.adjustEquipmentRoll(roll, damageSource);
            }
        }
    """.trimIndent()

    private val HELPER_MIXIN_TEMPLATE = """
        package {{PACKAGE}};

        import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
        import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;

        import net.minecraft.server.level.ServerLevel;
        import net.minecraft.world.damagesource.DamageSource;
        import net.minecraft.world.entity.LivingEntity;
        import net.minecraft.world.item.enchantment.EnchantmentHelper;
        import net.minecraft.world.item.enchantment.Enchantments;

        @Mixin(EnchantmentHelper.class)
        public abstract class {{HELPER_MIXIN_TYPE}} {
            @WrapOperation(
                method = "processEquipmentDropChance(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)F",
                at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;runIterationOnEquipment(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/enchantment/EnchantmentHelper${'$'}EnchantmentInSlotVisitor;)V",
                    ordinal = 1
                ),
                require = 1,
                expect = 1
            )
            private static void {{HELPER_HANDLER}}(
                LivingEntity equipmentOwner,
                EnchantmentHelper.EnchantmentInSlotVisitor visitor,
                Operation<Void> original,
                ServerLevel level,
                LivingEntity entity,
                DamageSource damageSource,
                float equipmentDropChance
            ) {
                if (!{{POLICY_TYPE}}.applies(damageSource)) {
                    original.call(equipmentOwner, visitor);
                    return;
                }

                {{POLICY_TYPE}}.verifyNativeLootingEquipmentShape(level);
                EnchantmentHelper.EnchantmentInSlotVisitor decorated =
                    (enchantment, enchantmentLevel, item) -> {
                        if (!enchantment.is(Enchantments.LOOTING))
                            visitor.accept(enchantment, enchantmentLevel, item);
                    };
                original.call(equipmentOwner, decorated);
            }
        }
    """.trimIndent()
}
