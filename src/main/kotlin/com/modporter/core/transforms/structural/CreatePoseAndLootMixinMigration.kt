package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.comments.Comment
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Closed, source-bound migration of Create's cardboard pose hook and bound-cardboard
 * Silk Touch conditions.
 *
 * The family activates only at Create's exact package/type/member graph. It admits either
 * the complete legacy family or the complete migrated family; mixed and near-match states
 * fail before a project write. The four Java outputs are pre-staged and published with
 * per-file atomic moves plus aggregate rollback. create.mixins.json is an immutable input:
 * PlayerMixin is already registered, so no new mixin class or configuration route is created.
 */
internal object CreatePoseAndLootMixinMigration {
    const val RULE_ID = "struct-create-pose-loot-mixins"

    private const val ENTITY_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/mixin/EntityMixin.java"
    private const val PLAYER_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/mixin/PlayerMixin.java"
    private const val ACCESSOR_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/mixin/accessor/" +
            "BlockLootSubProviderAccessor.java"
    private const val ALL_BLOCKS_RELATIVE =
        "src/main/java/com/simibubi/create/AllBlocks.java"
    private const val MIXIN_CONFIG_RELATIVE = "src/main/resources/create.mixins.json"
    private const val EXTRA_POSE_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/mixin/PlayerPoseMixin.java"

    private const val MIXIN_PACKAGE = "com.simibubi.create.foundation.mixin"
    private const val ACCESSOR_PACKAGE =
        "com.simibubi.create.foundation.mixin.accessor"
    private const val ROOT_PACKAGE = "com.simibubi.create"
    private const val POSE_HANDLER =
        "create\$playerHidingAsBoxIsCrouchingNotSwimming"
    private const val FIRE_HANDLER = "create\$onFireImmune"
    private const val PASSENGER_HANDLER = "pretendNotPassenger"
    private const val LEGACY_SILK_METHOD = "create\$HAS_SILK_TOUCH"
    private const val TARGET_SILK_METHOD = "create\$hasSilkTouch"
    private const val COLLISION_TARGET =
        "Lnet/minecraft/world/level/Level;noCollision" +
            "(Lnet/minecraft/world/entity/Entity;" +
            "Lnet/minecraft/world/phys/AABB;)Z"

    private const val ENTITY_MIXIN =
        "@Mixin(value=Entity.class,priority=1500)"
    private const val PLAYER_MIXIN_LEGACY = "@Mixin(Player.class)"
    private const val PLAYER_MIXIN_TARGET =
        "@Mixin(value=Player.class,priority=1500)"
    private const val ACCESSOR_MIXIN = "@Mixin(BlockLootSubProvider.class)"

    private const val LEGACY_POSE_METHOD = """
        @ModifyExpressionValue(
            method = "canEnterPose",
            at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"
            )
        )
        public boolean create${'$'}playerHidingAsBoxIsCrouchingNotSwimming(
            boolean original,
            @Local(argsOnly = true) Pose pose
        ) {
            return original || (
                pose == Pose.CROUCHING &&
                    CardboardArmorHandler.testForStealth((Entity) (Object) this)
            );
        }
    """

    private const val TARGET_POSE_METHOD = """
        @ModifyExpressionValue(
            method = "canPlayerFitWithinBlocksAndEntitiesWhen",
            at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"
            )
        )
        private boolean create${'$'}playerHidingAsBoxIsCrouchingNotSwimming(
            boolean original,
            @Local(argsOnly = true) Pose pose
        ) {
            return original || (
                pose == Pose.CROUCHING &&
                    CardboardArmorHandler.testForStealth(this)
            );
        }
    """

    private const val FIRE_METHOD = """
        @ModifyReturnValue(method = "fireImmune()Z", at = @At("RETURN"))
        public boolean create${'$'}onFireImmune(boolean original) {
            return ((Entity) (Object) this)
                .getPersistentData()
                .getBoolean(NetheriteDivingHandler.FIRE_IMMUNE_KEY) || original;
        }
    """

    private const val PLAYER_CONSTRUCTOR = """
        protected PlayerMixin(
            EntityType<? extends LivingEntity> entityType,
            Level level
        ) {
            super(entityType, level);
        }
    """

    private const val PASSENGER_METHOD = """
        @ModifyExpressionValue(
            method = "aiStep",
            at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/entity/player/Player;isPassenger()Z"
            )
        )
        private boolean pretendNotPassenger(boolean isPassenger) {
            boolean shouldSync =
                AllConfigs.server().kinetics.syncPlayerPickupHitboxWithContraptionHitbox.get();
            if (
                isPassenger &&
                !shouldSync &&
                this.getVehicle() instanceof AbstractContraptionEntity
            ) {
                return false;
            }
            return isPassenger;
        }
    """

    private const val LEGACY_ACCESSOR_METHOD = """
        @Accessor("HAS_SILK_TOUCH")
        static LootItemCondition.Builder create${'$'}HAS_SILK_TOUCH() {
            throw new AssertionError();
        }
    """

    private const val TARGET_ACCESSOR_METHOD = """
        @Invoker("hasSilkTouch")
        LootItemCondition.Builder create${'$'}hasSilkTouch();
    """

    private const val NORMALIZED_SILK_CALL = "__createNormalizedSilkTouch__()"
    private const val BOUND_CARDBOARD_LOOT_CALLBACK = """
        (r, b) -> r.add(
            b,
            LootTable.lootTable()
                .withPool(
                    LootPool.lootPool()
                        .setRolls(ConstantValue.exactly(1.0F))
                        .add(
                            LootItem.lootTableItem(b)
                                .when(__createNormalizedSilkTouch__())
                                .otherwise(
                                    r.applyExplosionCondition(
                                        b,
                                        LootItem.lootTableItem(Items.STRING)
                                    )
                                )
                        )
                )
                .withPool(
                    r.applyExplosionCondition(
                        b,
                        LootPool.lootPool()
                            .setRolls(ConstantValue.exactly(1.0F))
                            .add(
                                LootItem.lootTableItem(
                                    AllBlocks.CARDBOARD_BLOCK.asItem()
                                )
                            )
                            .when(__createNormalizedSilkTouch__().invert())
                    )
                )
        )
    """

    data class Result(
        val changes: List<Change>,
        val gates: List<String>
    ) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    private enum class FamilyState {
        LEGACY,
        MIGRATED
    }

    private data class CapturedFile(
        val path: Path,
        val bytes: ByteArray,
        val sha256: String
    )

    private data class ParsedFile(
        val captured: CapturedFile,
        val source: String,
        val newline: String,
        val unit: CompilationUnit
    )

    private data class SourceGraph(
        val root: Path,
        val files: List<CapturedFile>,
        val relativePaths: Set<String>
    )

    private data class LootAdmission(
        val state: FamilyState,
        val calls: List<MethodCallExpr>,
        val receiver: String
    )

    private data class PreparedOutput(
        val captured: CapturedFile,
        val after: ByteArray,
        val description: String
    )

    fun migrate(
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)? = null
    ): Result = try {
        migrateChecked(projectDir, dryRun, commitProbe)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create pose/loot admission failed"))
    } catch (failure: Exception) {
        val rollback = failure.suppressed
            .map { it.message ?: it::class.java.simpleName }
        val suffix = if (rollback.isEmpty()) {
            ""
        } else {
            "; rollback failures: ${rollback.joinToString()}"
        }
        Result(
            emptyList(),
            listOf(
                "Create pose/loot migration transaction failed: " +
                    (failure.message ?: failure::class.java.simpleName) +
                    suffix
            )
        )
    }

    private fun migrateChecked(
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)?
    ): Result {
        val project = projectDir.toAbsolutePath().normalize()
        val entityPath = project.resolve(ENTITY_RELATIVE)
        if (!entityPath.exists()) return Result.EMPTY

        val required = listOf(
            entityPath,
            project.resolve(PLAYER_RELATIVE),
            project.resolve(ACCESSOR_RELATIVE),
            project.resolve(ALL_BLOCKS_RELATIVE),
            project.resolve(MIXIN_CONFIG_RELATIVE)
        )
        required.forEach { path ->
            if (!path.isRegularFile() || Files.isSymbolicLink(path)) {
                abort("Create pose/loot family requires a regular owned file: $path")
            }
            if (!path.toAbsolutePath().normalize().startsWith(project)) {
                abort("Create pose/loot path escaped the project: $path")
            }
        }

        val parser = JavaParser(
            ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val sourceGraph = captureJavaSourceGraph(project)
        val sourceByPath = sourceGraph.files.associateBy {
            it.path.toAbsolutePath().normalize()
        }
        fun capturedSource(relative: String): CapturedFile {
            val path = project.resolve(relative).toAbsolutePath().normalize()
            return sourceByPath[path]
                ?: abort("Create pose/loot source graph omitted required file: $relative")
        }
        val entity = parse(parser, capturedSource(ENTITY_RELATIVE))
        val player = parse(parser, capturedSource(PLAYER_RELATIVE))
        val accessor = parse(parser, capturedSource(ACCESSOR_RELATIVE))
        val allBlocks = parse(parser, capturedSource(ALL_BLOCKS_RELATIVE))
        val config = capture(project.resolve(MIXIN_CONFIG_RELATIVE))

        admitMixinConfig(config)
        verifyNoExtraRoute(project, config)
        val entityState = admitEntity(parser, entity.unit)
        val playerState = admitPlayer(parser, player.unit)
        val accessorState = admitAccessor(parser, accessor.unit)
        val lootAdmission = admitAllBlocks(parser, allBlocks.unit)
        val states = setOf(
            entityState,
            playerState,
            accessorState,
            lootAdmission.state
        )
        if (states.size != 1) {
            abort("Create pose/loot family is partially migrated: ${states.sortedBy { it.name }}")
        }
        admitSourceClosure(sourceGraph, states.single())

        if (states.single() == FamilyState.MIGRATED) {
            verifySourceGraph(sourceGraph)
            return Result.EMPTY
        }

        LexicalPreservingPrinter.setup(entity.unit)
        LexicalPreservingPrinter.setup(player.unit)
        LexicalPreservingPrinter.setup(accessor.unit)
        LexicalPreservingPrinter.setup(allBlocks.unit)

        val poseDocumentation = migrateEntity(entity.unit)
        migratePlayer(parser, player.unit)
        val accessorDocumentation = migrateAccessor(parser, accessor.unit)
        migrateAllBlocks(parser, lootAdmission)

        val outputs = listOf(
            prepareOutput(
                parser,
                entity,
                removeMovedDocumentation(
                    parser,
                    LexicalPreservingPrinter.print(entity.unit),
                    poseDocumentation,
                    "EntityMixin legacy pose handler"
                ),
                "Move cardboard pose hook off Entity while retaining fire immunity"
            ),
            prepareOutput(
                parser,
                player,
                ensureAttachedDocumentation(
                    parser,
                    LexicalPreservingPrinter.print(player.unit),
                    POSE_HANDLER,
                    poseDocumentation
                ),
                "Install official 1.21 player-fit hook on existing PlayerMixin"
            ),
            prepareOutput(
                parser,
                accessor,
                ensureAttachedDocumentation(
                    parser,
                    removeMovedDocumentation(
                        parser,
                        LexicalPreservingPrinter.print(accessor.unit),
                        accessorDocumentation,
                        "legacy Silk Touch accessor"
                    ),
                    TARGET_SILK_METHOD,
                    accessorDocumentation
                ),
                "Replace removed static Silk Touch field accessor with provider invoker"
            ),
            prepareOutput(
                parser,
                allBlocks,
                LexicalPreservingPrinter.print(allBlocks.unit),
                "Bind bound-cardboard Silk Touch conditions to the loot provider instance"
            )
        )
        if (outputs.any { it.after.contentEquals(it.captured.bytes) }) {
            abort("Legacy Create pose/loot family did not change all four owned Java files")
        }

        val renderedEntity = parseBytes(parser, outputs[0].captured.path, outputs[0].after)
        val renderedPlayer = parseBytes(parser, outputs[1].captured.path, outputs[1].after)
        val renderedAccessor = parseBytes(parser, outputs[2].captured.path, outputs[2].after)
        val renderedAllBlocks = parseBytes(parser, outputs[3].captured.path, outputs[3].after)
        if (
            admitEntity(parser, renderedEntity) != FamilyState.MIGRATED ||
            admitPlayer(parser, renderedPlayer) != FamilyState.MIGRATED ||
            admitAccessor(parser, renderedAccessor) != FamilyState.MIGRATED ||
            admitAllBlocks(parser, renderedAllBlocks).state != FamilyState.MIGRATED
        ) {
            abort("Rendered Create pose/loot family did not pass migrated-state admission")
        }
        val renderedGraph = sourceGraphWithOutputs(sourceGraph, outputs)
        admitSourceClosure(renderedGraph, FamilyState.MIGRATED)
        verifyNoExtraRoute(project, config)
        verifySourceGraph(sourceGraph)
        verifyCapturedFiles(listOf(config))

        if (!dryRun) {
            val outputPaths = outputs.map { it.captured.path.toAbsolutePath().normalize() }.toSet()
            publishFamily(
                outputs,
                immutableInputs = sourceGraph.files.filter {
                    it.path.toAbsolutePath().normalize() !in outputPaths
                } + config,
                sourceGraph = sourceGraph,
                forbiddenPaths = listOf(project.resolve(EXTRA_POSE_RELATIVE)),
                commitProbe = commitProbe
            )
        }

        return Result(
            outputs.map { output ->
                Change(
                    file = output.captured.path,
                    line = 1,
                    description = output.description,
                    before = String(output.captured.bytes, StandardCharsets.UTF_8).take(160),
                    after = String(output.after, StandardCharsets.UTF_8).take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    private fun parse(parser: JavaParser, captured: CapturedFile): ParsedFile {
        val source = decodeUtf8(captured.path, captured.bytes)
        val result = parser.parse(source)
        if (!result.isSuccessful || result.result.isEmpty) {
            abort(
                "Cannot parse ${captured.path}: " +
                    result.problems.joinToString("; ") { it.verboseMessage }
                        .ifBlank { "unknown parse failure" }
            )
        }
        return ParsedFile(
            captured,
            source,
            if (source.contains("\r\n")) "\r\n" else "\n",
            result.result.orElseThrow()
        )
    }

    private fun parseBytes(
        parser: JavaParser,
        path: Path,
        bytes: ByteArray
    ): CompilationUnit {
        val source = decodeUtf8(path, bytes)
        val result = parser.parse(source)
        if (!result.isSuccessful || result.result.isEmpty) {
            abort(
                "Rendered Java does not parse at $path: " +
                    result.problems.joinToString("; ") { it.verboseMessage }
            )
        }
        return result.result.orElseThrow()
    }

    private fun prepareOutput(
        parser: JavaParser,
        parsed: ParsedFile,
        printed: String,
        description: String
    ): PreparedOutput {
        val normalized = printed.replace("\r\n", "\n")
        val source = if (parsed.newline == "\r\n") {
            normalized.replace("\n", "\r\n")
        } else {
            normalized
        }
        val withFinalNewline = when {
            parsed.source.endsWith(parsed.newline) && !source.endsWith(parsed.newline) ->
                source + parsed.newline
            !parsed.source.endsWith(parsed.newline) && source.endsWith(parsed.newline) ->
                source.removeSuffix(parsed.newline)
            else -> source
        }
        val bytes = withFinalNewline.toByteArray(StandardCharsets.UTF_8)
        parseBytes(parser, parsed.captured.path, bytes)
        return PreparedOutput(parsed.captured, bytes, description)
    }

    private fun ensureAttachedDocumentation(
        parser: JavaParser,
        source: String,
        methodName: String,
        documentation: Comment?
    ): String {
        if (documentation == null) return source
        val expected = documentationText(documentation)
        val parsed = parseSource(parser, source, methodName)
        val method = parsed.findAll(MethodDeclaration::class.java)
            .singleOrNull { it.nameAsString == methodName }
            ?: abort("Rendered source lost documentation target $methodName")
        val existing = method.comment.orElse(null)
        if (existing != null) {
            if (documentationText(existing) != expected) {
                abort("Rendered $methodName gained different attached documentation")
            }
            return source
        }
        val position = method.annotations.firstOrNull()
            ?.range?.orElse(null)?.begin
            ?: method.range.orElse(null)?.begin
            ?: abort("Rendered $methodName has no source position")
        val offset = sourceOffset(source, position.line, position.column)
        val lineStart = source.lastIndexOf('\n', offset - 1).let {
            if (it < 0) 0 else it + 1
        }
        val indentation = source.substring(lineStart, offset)
        if (indentation.any { !it.isWhitespace() }) {
            abort("Rendered $methodName documentation anchor is not line-leading")
        }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val renderedDocumentation = documentation.toString()
            .replace("\r\n", "\n")
            .trimEnd()
            .split('\n')
            .joinToString(newline + indentation)
        val withDocumentation = source.substring(0, offset) +
            renderedDocumentation +
            newline +
            indentation +
            source.substring(offset)
        val verified = parseSource(parser, withDocumentation, methodName)
            .findAll(MethodDeclaration::class.java)
            .singleOrNull { it.nameAsString == methodName }
            ?.comment?.orElse(null)
        if (verified == null || documentationText(verified) != expected) {
            abort("Could not attach preserved documentation to rendered $methodName")
        }
        return withDocumentation
    }

    private fun removeMovedDocumentation(
        parser: JavaParser,
        source: String,
        documentation: Comment?,
        label: String
    ): String {
        if (documentation == null) return source
        val expected = documentationText(documentation)
        val parsed = parseSource(parser, source, label)
        val matches = parsed.allContainedComments.filter {
            documentationText(it) == expected
        }
        if (matches.isEmpty()) return source
        if (matches.size != 1) {
            abort("Rendered $label contains ${matches.size} copies of moved documentation")
        }
        val range = matches.single().range.orElse(null)
            ?: abort("Rendered $label documentation has no lexical range")
        val start = sourceOffset(source, range.begin.line, range.begin.column)
        val endInclusive = sourceOffset(source, range.end.line, range.end.column)
        val endExclusive = endInclusive + 1
        if (endExclusive > source.length) {
            abort("Rendered $label documentation range escaped source")
        }
        val lineStart = source.lastIndexOf('\n', start - 1).let {
            if (it < 0) 0 else it + 1
        }
        val nextNewline = source.indexOf('\n', endExclusive)
        val lineEndExclusive = if (nextNewline < 0) source.length else nextNewline + 1
        val before = source.substring(lineStart, start)
        val after = source.substring(endExclusive, lineEndExclusive)
        val withoutDocumentation = if (
            before.all { it.isWhitespace() } &&
            after.all { it.isWhitespace() }
        ) {
            source.removeRange(lineStart, lineEndExclusive)
        } else {
            source.removeRange(start, endExclusive)
        }
        val remaining = parseSource(parser, withoutDocumentation, label)
            .allContainedComments
            .count { documentationText(it) == expected }
        if (remaining != 0) {
            abort("Could not remove moved documentation from rendered $label")
        }
        return withoutDocumentation
    }

    private fun parseSource(
        parser: JavaParser,
        source: String,
        label: String
    ): CompilationUnit {
        val result = parser.parse(source)
        if (!result.isSuccessful || result.result.isEmpty) {
            abort(
                "Rendered source for $label does not parse: " +
                    result.problems.joinToString("; ") { it.verboseMessage }
            )
        }
        return result.result.orElseThrow()
    }

    private fun sourceOffset(source: String, line: Int, column: Int): Int {
        var offset = 0
        repeat(line - 1) {
            val newline = source.indexOf('\n', offset)
            if (newline < 0) abort("Rendered documentation line escaped source")
            offset = newline + 1
        }
        val result = offset + column - 1
        if (result !in 0..source.length) {
            abort("Rendered documentation column escaped source")
        }
        return result
    }

    private fun documentationText(comment: Comment): String =
        comment.content
            .lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun admitEntity(
        parser: JavaParser,
        unit: CompilationUnit
    ): FamilyState {
        val owner = admitOwner(
            unit,
            MIXIN_PACKAGE,
            "EntityMixin",
            interfaceType = false,
            abstractType = false
        )
        requireNativeTypeImports(
            unit,
            mapOf(
                "Mixin" to "org.spongepowered.asm.mixin.Mixin",
                "At" to "org.spongepowered.asm.mixin.injection.At",
                "ModifyReturnValue" to
                    "com.llamalad7.mixinextras.injector.ModifyReturnValue",
                "NetheriteDivingHandler" to
                    "com.simibubi.create.content.equipment.armor.NetheriteDivingHandler",
                "Entity" to "net.minecraft.world.entity.Entity"
            )
        )
        requireCanonicalAnnotation(owner, "Mixin", ENTITY_MIXIN)
        if (
            owner.constructors.isNotEmpty() ||
            owner.fields.isNotEmpty() ||
            owner.extendedTypes.isNotEmpty() ||
            owner.implementedTypes.isNotEmpty() ||
            owner.members.any { it !is MethodDeclaration }
        ) {
            abort("EntityMixin gained an unproved owner member")
        }
        val fire = owner.methods.filter { it.nameAsString == FIRE_HANDLER }
        if (fire.size != 1 ||
            canonical(fire.single()) != canonicalMethod(parser, FIRE_METHOD)
        ) {
            abort("EntityMixin fireImmune handler changed from the preserved source contract")
        }
        val pose = owner.methods.filter { it.nameAsString == POSE_HANDLER }
        val other = owner.methods.filter {
            it.nameAsString !in setOf(FIRE_HANDLER, POSE_HANDLER)
        }
        if (other.isNotEmpty()) {
            abort("EntityMixin gained an unproved method: ${other.map { it.nameAsString }}")
        }
        return when {
            pose.size == 1 &&
                canonical(pose.single()) == canonicalMethod(parser, LEGACY_POSE_METHOD) -> {
                requireOnlyAttachedDocumentation(pose.single(), "EntityMixin pose handler")
                requireNativeTypeImports(
                    unit,
                    mapOf(
                        "ModifyExpressionValue" to
                            "com.llamalad7.mixinextras.injector.ModifyExpressionValue",
                        "Local" to "com.llamalad7.mixinextras.sugar.Local",
                        "CardboardArmorHandler" to
                            "com.simibubi.create.content.equipment.armor.CardboardArmorHandler",
                        "Pose" to "net.minecraft.world.entity.Pose"
                    )
                )
                FamilyState.LEGACY
            }
            pose.isEmpty() -> {
                requireNoSimpleUse(unit, "CardboardArmorHandler")
                requireNoSimpleUse(unit, "Pose")
                requireNoImport(
                    unit,
                    "com.llamalad7.mixinextras.injector.ModifyExpressionValue"
                )
                requireNoImport(unit, "com.llamalad7.mixinextras.sugar.Local")
                requireNoImport(
                    unit,
                    "com.simibubi.create.content.equipment.armor.CardboardArmorHandler"
                )
                requireNoImport(unit, "net.minecraft.world.entity.Pose")
                FamilyState.MIGRATED
            }
            else -> abort("EntityMixin pose handler is neither exact legacy nor absent target state")
        }
    }

    private fun admitPlayer(
        parser: JavaParser,
        unit: CompilationUnit
    ): FamilyState {
        val owner = admitOwner(
            unit,
            MIXIN_PACKAGE,
            "PlayerMixin",
            interfaceType = false,
            abstractType = true
        )
        requireNativeTypeImports(
            unit,
            mapOf(
                "Mixin" to "org.spongepowered.asm.mixin.Mixin",
                "At" to "org.spongepowered.asm.mixin.injection.At",
                "ModifyExpressionValue" to
                    "com.llamalad7.mixinextras.injector.ModifyExpressionValue",
                "AbstractContraptionEntity" to
                    "com.simibubi.create.content.contraptions.AbstractContraptionEntity",
                "AllConfigs" to "com.simibubi.create.infrastructure.config.AllConfigs",
                "EntityType" to "net.minecraft.world.entity.EntityType",
                "LivingEntity" to "net.minecraft.world.entity.LivingEntity",
                "Player" to "net.minecraft.world.entity.player.Player",
                "Level" to "net.minecraft.world.level.Level"
            )
        )
        if (
            owner.extendedTypes.map { canonical(it) } != listOf("LivingEntity") ||
            owner.implementedTypes.isNotEmpty() ||
            owner.fields.isNotEmpty() ||
            owner.members.any {
                it !is ConstructorDeclaration && it !is MethodDeclaration
            }
        ) {
            abort("PlayerMixin owner graph changed from abstract LivingEntity subclass")
        }
        val constructors = owner.constructors
        if (
            constructors.size != 1 ||
            canonical(constructors.single()) !=
            canonicalConstructor(parser, PLAYER_CONSTRUCTOR)
        ) {
            abort("PlayerMixin constructor changed from the admitted LivingEntity bridge")
        }
        val passenger = owner.methods.filter { it.nameAsString == PASSENGER_HANDLER }
        if (
            passenger.size != 1 ||
            canonical(passenger.single()) != canonicalMethod(parser, PASSENGER_METHOD)
        ) {
            abort("PlayerMixin aiStep passenger handler changed from the preserved contract")
        }
        val pose = owner.methods.filter { it.nameAsString == POSE_HANDLER }
        val other = owner.methods.filter {
            it.nameAsString !in setOf(PASSENGER_HANDLER, POSE_HANDLER)
        }
        if (other.isNotEmpty()) {
            abort("PlayerMixin gained an unproved method: ${other.map { it.nameAsString }}")
        }
        val mixin = canonicalAnnotation(owner, "Mixin")
        return when {
            pose.isEmpty() && mixin == PLAYER_MIXIN_LEGACY -> {
                requireNoSimpleUse(unit, "CardboardArmorHandler")
                requireNoSimpleUse(unit, "Pose")
                requireNoImport(unit, "com.llamalad7.mixinextras.sugar.Local")
                requireNoImport(
                    unit,
                    "com.simibubi.create.content.equipment.armor.CardboardArmorHandler"
                )
                requireNoImport(unit, "net.minecraft.world.entity.Pose")
                FamilyState.LEGACY
            }
            pose.size == 1 &&
                mixin == PLAYER_MIXIN_TARGET &&
                canonical(pose.single()) == canonicalMethod(parser, TARGET_POSE_METHOD) -> {
                requireOnlyAttachedDocumentation(pose.single(), "PlayerMixin pose handler")
                requireNativeTypeImports(
                    unit,
                    mapOf(
                        "Local" to "com.llamalad7.mixinextras.sugar.Local",
                        "CardboardArmorHandler" to
                            "com.simibubi.create.content.equipment.armor.CardboardArmorHandler",
                        "Pose" to "net.minecraft.world.entity.Pose"
                    )
                )
                FamilyState.MIGRATED
            }
            else -> abort("PlayerMixin is neither exact legacy nor exact official 1.21 state")
        }
    }

    private fun admitAccessor(
        parser: JavaParser,
        unit: CompilationUnit
    ): FamilyState {
        val owner = admitOwner(
            unit,
            ACCESSOR_PACKAGE,
            "BlockLootSubProviderAccessor",
            interfaceType = true,
            abstractType = false
        )
        requireNativeTypeImports(
            unit,
            mapOf(
                "Mixin" to "org.spongepowered.asm.mixin.Mixin",
                "BlockLootSubProvider" to
                    "net.minecraft.data.loot.BlockLootSubProvider",
                "LootItemCondition" to
                    "net.minecraft.world.level.storage.loot.predicates.LootItemCondition"
            )
        )
        requireCanonicalAnnotation(owner, "Mixin", ACCESSOR_MIXIN)
        if (
            owner.constructors.isNotEmpty() ||
            owner.fields.isNotEmpty() ||
            owner.extendedTypes.isNotEmpty() ||
            owner.implementedTypes.isNotEmpty() ||
            owner.methods.size != 1 ||
            owner.members.any { it !is MethodDeclaration }
        ) {
            abort("BlockLootSubProviderAccessor must own exactly one method")
        }
        val method = owner.methods.single()
        requireOnlyAttachedDocumentation(method, "BlockLootSubProviderAccessor method")
        return when {
            method.nameAsString == LEGACY_SILK_METHOD &&
                canonical(method) == canonicalMethod(parser, LEGACY_ACCESSOR_METHOD) -> {
                requireExactImport(unit, "org.spongepowered.asm.mixin.gen.Accessor")
                requireNoImport(unit, "org.spongepowered.asm.mixin.gen.Invoker")
                FamilyState.LEGACY
            }
            method.nameAsString == TARGET_SILK_METHOD &&
                canonical(method) == canonicalMethod(parser, TARGET_ACCESSOR_METHOD) -> {
                requireExactImport(unit, "org.spongepowered.asm.mixin.gen.Invoker")
                requireNoImport(unit, "org.spongepowered.asm.mixin.gen.Accessor")
                FamilyState.MIGRATED
            }
            else -> abort("Silk Touch accessor is neither exact legacy nor target invoker")
        }
    }

    private fun admitAllBlocks(
        parser: JavaParser,
        unit: CompilationUnit
    ): LootAdmission {
        val owner = admitOwner(
            unit,
            ROOT_PACKAGE,
            "AllBlocks",
            interfaceType = false,
            abstractType = false,
            requireSingleType = false
        )
        requireNativeTypeImports(
            unit,
            mapOf(
                "BlockLootSubProviderAccessor" to
                    "com.simibubi.create.foundation.mixin.accessor." +
                    "BlockLootSubProviderAccessor",
                "RegistrateBlockLootTables" to
                    "com.tterrag.registrate.providers.loot.RegistrateBlockLootTables",
                "BlockEntry" to "com.tterrag.registrate.util.entry.BlockEntry",
                "CardboardBlock" to
                    "com.simibubi.create.content.decoration.CardboardBlock",
                "Items" to "net.minecraft.world.item.Items",
                "LootPool" to
                    "net.minecraft.world.level.storage.loot.LootPool",
                "LootTable" to
                    "net.minecraft.world.level.storage.loot.LootTable",
                "LootItem" to
                    "net.minecraft.world.level.storage.loot.entries.LootItem",
                "ConstantValue" to
                    "net.minecraft.world.level.storage.loot.providers.number.ConstantValue"
            )
        )
        val variables = owner.fields.flatMap { it.variables }
            .filter { it.nameAsString == "BOUND_CARDBOARD_BLOCK" }
        if (variables.size != 1) {
            abort("AllBlocks must own exactly one BOUND_CARDBOARD_BLOCK field")
        }
        val bound = variables.single()
        if (canonical(bound.type) != "BlockEntry<CardboardBlock>") {
            abort("BOUND_CARDBOARD_BLOCK type changed from BlockEntry<CardboardBlock>")
        }
        val initializer = bound.initializer.orElse(null)
            ?: abort("BOUND_CARDBOARD_BLOCK lost its registration initializer")
        val blockFactory = initializer.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "block" &&
                canonical(call.scope.orElse(null)) == "REGISTRATE" &&
                call.arguments.size == 2 &&
                (call.arguments[0] as? StringLiteralExpr)?.value ==
                    "bound_cardboard_block" &&
                canonical(call.arguments[1]) == "CardboardBlock::new"
        }
        if (blockFactory.size != 1) {
            abort("Bound cardboard registration owner/factory changed")
        }

        val namedCalls = unit.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString in setOf(LEGACY_SILK_METHOD, TARGET_SILK_METHOD)
        }
        if (namedCalls.size != 2) {
            abort("Project must contain exactly two bound-cardboard Silk Touch calls")
        }
        if (namedCalls.any { !it.isDescendantOf(initializer) }) {
            abort("Silk Touch call escaped BOUND_CARDBOARD_BLOCK")
        }

        val legacy = namedCalls.filter { call ->
            call.nameAsString == LEGACY_SILK_METHOD &&
                call.arguments.isEmpty() &&
                canonical(call.scope.orElse(null)) == "BlockLootSubProviderAccessor"
        }
        val migrated = namedCalls.filter { call ->
            call.nameAsString == TARGET_SILK_METHOD &&
                call.arguments.isEmpty() &&
                isCastReceiver(
                    call.scope.orElse(null),
                    "BlockLootSubProviderAccessor",
                    "r"
                )
        }
        val state = when {
            legacy.size == 2 && migrated.isEmpty() -> FamilyState.LEGACY
            migrated.size == 2 && legacy.isEmpty() -> FamilyState.MIGRATED
            else -> abort("Bound-cardboard Silk Touch calls are partially or incorrectly migrated")
        }

        val lambdas = namedCalls.map { call ->
            call.findAncestor(LambdaExpr::class.java).orElse(null)
                ?: abort("Silk Touch call is not owned by a loot callback")
        }.distinct()
        if (lambdas.size != 1) {
            abort("Silk Touch calls do not share the exact bound-cardboard callback")
        }
        val lambda = lambdas.single()
        val parameters = lambda.parameters.map { it.nameAsString }
        if (parameters != listOf("r", "b")) {
            abort("Bound-cardboard loot callback receiver changed: $parameters")
        }
        val body = lambda.body as? ExpressionStmt
            ?: abort("Bound-cardboard loot callback is no longer an expression lambda")
        val add = body.expression as? MethodCallExpr
            ?: abort("Bound-cardboard loot callback no longer calls the provider")
        if (
            add.nameAsString != "add" ||
            canonical(add.scope.orElse(null)) != "r" ||
            add.arguments.size != 2 ||
            canonical(add.arguments[0]) != "b"
        ) {
            abort("Bound-cardboard callback is not r.add(b, lootTable)")
        }
        val lootCalls = initializer.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "loot" &&
                call.arguments.size == 1 &&
                call.arguments[0] === lambda
        }
        if (lootCalls.size != 1) {
            abort("Bound-cardboard callback is not owned by one registrate loot stage")
        }
        val normalizedLambda = lambda.clone()
        val normalizedCalls = normalizedLambda.findAll(MethodCallExpr::class.java)
            .filter {
                it.nameAsString in setOf(LEGACY_SILK_METHOD, TARGET_SILK_METHOD)
            }
        if (normalizedCalls.size != 2) {
            abort("Bound-cardboard loot callback lost its two Silk Touch conditions")
        }
        normalizedCalls.forEach { call ->
            val replacement = parser.parseExpression<Expression>(
                NORMALIZED_SILK_CALL
            ).result.orElseThrow()
            if (!call.replace(replacement)) {
                abort("Could not normalize bound-cardboard Silk Touch condition")
            }
        }
        val expectedLootCallback = parser.parseExpression<LambdaExpr>(
            BOUND_CARDBOARD_LOOT_CALLBACK
        ).result.orElseThrow()
        if (canonical(normalizedLambda) != canonical(expectedLootCallback)) {
            abort("BOUND_CARDBOARD_BLOCK loot callback changed outside Silk Touch API")
        }
        val polarity = namedCalls.map { call -> silkPolarity(call) }.sorted()
        if (polarity != listOf("inverted", "positive")) {
            abort("Bound-cardboard Silk Touch positive/inverted conditions changed: $polarity")
        }
        return LootAdmission(state, namedCalls, "r")
    }

    private fun silkPolarity(call: MethodCallExpr): String {
        val parent = call.parentNode.orElse(null)
        if (
            parent is MethodCallExpr &&
            parent.nameAsString == "when" &&
            parent.arguments.size == 1 &&
            parent.arguments[0] === call
        ) {
            return "positive"
        }
        if (
            parent is MethodCallExpr &&
            parent.nameAsString == "invert" &&
            parent.arguments.isEmpty() &&
            parent.scope.orElse(null) === call
        ) {
            val whenCall = parent.parentNode.orElse(null)
            if (
                whenCall is MethodCallExpr &&
                whenCall.nameAsString == "when" &&
                whenCall.arguments.size == 1 &&
                whenCall.arguments[0] === parent
            ) {
                return "inverted"
            }
        }
        abort("Silk Touch call is not a direct positive or inverted loot condition")
    }

    private fun migrateEntity(unit: CompilationUnit): Comment? {
        val owner = unit.getClassByName("EntityMixin").orElseThrow()
        val pose = owner.methods.single { it.nameAsString == POSE_HANDLER }
        val documentation = pose.comment.map { it.clone() }.orElse(null)
        pose.remove()
        listOf(
            "com.llamalad7.mixinextras.injector.ModifyExpressionValue",
            "com.llamalad7.mixinextras.sugar.Local",
            "com.simibubi.create.content.equipment.armor.CardboardArmorHandler",
            "net.minecraft.world.entity.Pose"
        ).forEach { removeExactImport(unit, it) }
        return documentation
    }

    private fun migratePlayer(
        parser: JavaParser,
        unit: CompilationUnit
    ) {
        val owner = unit.getClassByName("PlayerMixin").orElseThrow()
        val mixin = owner.annotations.single { it.nameAsString == "Mixin" }
        val targetMixin = parser.parseAnnotation(
            "@Mixin(value = Player.class, priority = 1500)"
        ).result.orElseThrow()
        mixin.replace(targetMixin)
        listOf(
            "com.llamalad7.mixinextras.sugar.Local",
            "com.simibubi.create.content.equipment.armor.CardboardArmorHandler",
            "net.minecraft.world.entity.Pose"
        ).forEach { addExactImport(unit, it) }
        val handler = parser.parseBodyDeclaration<MethodDeclaration>(
            TARGET_POSE_METHOD
        ).result.orElseThrow()
        val passengerIndex = owner.members.indexOfFirst {
            it is MethodDeclaration && it.nameAsString == PASSENGER_HANDLER
        }
        if (passengerIndex < 0) abort("PlayerMixin passenger insertion anchor disappeared")
        owner.members.add(passengerIndex, handler)
    }

    private fun migrateAccessor(
        parser: JavaParser,
        unit: CompilationUnit
    ): Comment? {
        val owner = unit.types.filterIsInstance<ClassOrInterfaceDeclaration>()
            .single { it.nameAsString == "BlockLootSubProviderAccessor" }
        val legacy = owner.methods.single()
        val documentation = legacy.comment.map { it.clone() }.orElse(null)
        val target = parser.parseBodyDeclaration<MethodDeclaration>(
            TARGET_ACCESSOR_METHOD
        ).result.orElseThrow()
        legacy.replace(target)
        removeExactImport(unit, "org.spongepowered.asm.mixin.gen.Accessor")
        addExactImport(unit, "org.spongepowered.asm.mixin.gen.Invoker")
        return documentation
    }

    private fun migrateAllBlocks(
        parser: JavaParser,
        admission: LootAdmission
    ) {
        admission.calls.forEach { call ->
            call.setName(TARGET_SILK_METHOD)
            val receiver = parser.parseExpression<Expression>(
                "((BlockLootSubProviderAccessor) ${admission.receiver})"
            ).result.orElseThrow()
            call.setScope(receiver)
        }
    }

    private fun admitMixinConfig(config: CapturedFile) {
        val source = decodeUtf8(config.path, config.bytes)
        listOf("required", "package", "mixins", "injectors", "defaultRequire")
            .forEach { key ->
                val count = Regex(
                    """"${Regex.escape(key)}"\s*:"""
                ).findAll(source).count()
                if (count != 1) {
                    abort("Create mixin config must contain one unambiguous $key key")
                }
            }
        val root = try {
            Json.parseToJsonElement(source) as? JsonObject
                ?: abort("Create mixin config root must be a JSON object")
        } catch (failure: SerializationException) {
            abort("Create mixin config is not strict JSON: ${failure.message}")
        } catch (failure: IllegalArgumentException) {
            abort("Create mixin config is not strict JSON: ${failure.message}")
        }
        if ((root["required"] as? JsonPrimitive)?.booleanOrNull != true) {
            abort("Create mixin config must remain required=true")
        }
        if (
            (root["package"] as? JsonPrimitive)?.contentOrNull !=
            "com.simibubi.create.foundation.mixin"
        ) {
            abort("Create mixin config package changed from the native owner")
        }
        val injectors = root["injectors"] as? JsonObject
            ?: abort("Create mixin config injectors must be an object")
        if (
            (injectors["defaultRequire"] as? JsonPrimitive)?.intOrNull != 1
        ) {
            abort("Create mixin config must retain injectors.defaultRequire=1")
        }
        val mixins = root["mixins"] as? JsonArray
            ?: abort("Create mixin config mixins must be an array")
        val names = mixins.map { element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?: abort("Create mixin config mixins entries must be strings")
        }
        if (names.size != names.toSet().size) {
            abort("Create mixin config contains duplicate mixin registrations")
        }
        listOf(
            "EntityMixin",
            "PlayerMixin",
            "accessor.BlockLootSubProviderAccessor"
        ).forEach { required ->
            if (names.count { it == required } != 1) {
                abort("Create mixin config must register $required exactly once in mixins")
            }
        }
        val otherArrays = root.filterKeys { it != "mixins" }
            .values
            .filterIsInstance<JsonArray>()
            .flatMap { array ->
                array.mapNotNull { element ->
                    (element as? JsonPrimitive)?.contentOrNull
                }
            }
        val owned = setOf(
            "EntityMixin",
            "PlayerMixin",
            "accessor.BlockLootSubProviderAccessor"
        )
        if (otherArrays.any { it in owned }) {
            abort("Create mixin family escaped the root mixins array")
        }
        if (source.contains("PlayerPoseMixin")) {
            abort("Create mixin config contains an extra or weakened pose route")
        }
    }

    private fun verifyNoExtraRoute(project: Path, config: CapturedFile) {
        verifyCapturedFiles(listOf(config))
        val extra = project.resolve(EXTRA_POSE_RELATIVE)
        if (extra.exists()) {
            abort("Create pose migration must not create PlayerPoseMixin")
        }
    }

    private fun admitOwner(
        unit: CompilationUnit,
        packageName: String,
        typeName: String,
        interfaceType: Boolean,
        abstractType: Boolean,
        requireSingleType: Boolean = true
    ): ClassOrInterfaceDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != packageName) {
            abort("$typeName package changed from $packageName")
        }
        if (requireSingleType && unit.types.size != 1) {
            abort("$typeName source must contain one top-level type")
        }
        val owner = unit.types.filterIsInstance<ClassOrInterfaceDeclaration>()
            .singleOrNull { it.nameAsString == typeName }
            ?: abort("$typeName source does not own exactly one $typeName")
        if (
            owner.isInterface != interfaceType ||
            owner.isAbstract != abstractType ||
            !owner.isPublic ||
            owner.isStatic ||
            owner.typeParameters.isNotEmpty()
        ) {
            abort("$typeName owner modifiers changed")
        }
        return owner
    }

    private fun requireNativeTypeImports(
        unit: CompilationUnit,
        expected: Map<String, String>
    ) {
        val wildcardImports = unit.imports.filter {
            !it.isStatic && it.isAsterisk
        }
        if (wildcardImports.isNotEmpty()) {
            abort(
                "Native Create owner proof rejects wildcard type imports: " +
                    wildcardImports.map { it.nameAsString }
            )
        }
        expected.forEach { (simpleName, imported) ->
            requireExactImport(unit, imported)
            val conflicting = unit.imports.filter {
                !it.isStatic &&
                    !it.isAsterisk &&
                    it.name.identifier == simpleName &&
                    it.nameAsString != imported
            }
            if (conflicting.isNotEmpty()) {
                abort(
                    "Native type $simpleName has conflicting imports: " +
                        conflicting.map { it.nameAsString }
                )
            }
            val declarationShadow =
                unit.findAll(ClassOrInterfaceDeclaration::class.java)
                    .any { it.nameAsString == simpleName } ||
                    unit.findAll(EnumDeclaration::class.java)
                        .any { it.nameAsString == simpleName } ||
                    unit.findAll(AnnotationDeclaration::class.java)
                        .any { it.nameAsString == simpleName } ||
                    unit.findAll(RecordDeclaration::class.java)
                        .any { it.nameAsString == simpleName }
            val typeParameterShadow = unit.findAll(TypeParameter::class.java)
                .any { it.nameAsString == simpleName }
            if (declarationShadow || typeParameterShadow) {
                abort("Native type $simpleName is shadowed inside its source unit")
            }
        }
    }

    private fun requireOnlyAttachedDocumentation(
        method: MethodDeclaration,
        label: String
    ) {
        val attached = method.comment.orElse(null)
        val internal = method.allContainedComments.filter { it !== attached }
        if (internal.isNotEmpty()) {
            abort("$label contains comments that cannot be moved byte-faithfully")
        }
    }

    private fun requireCanonicalAnnotation(
        owner: ClassOrInterfaceDeclaration,
        name: String,
        expected: String
    ) {
        if (canonicalAnnotation(owner, name) != expected) {
            abort("${owner.nameAsString} @$name annotation changed")
        }
    }

    private fun canonicalAnnotation(
        owner: ClassOrInterfaceDeclaration,
        name: String
    ): String {
        val matches = owner.annotations.filter { it.nameAsString == name }
        if (matches.size != 1) {
            abort("${owner.nameAsString} must own exactly one @$name annotation")
        }
        return canonical(matches.single())
    }

    private fun canonicalMethod(parser: JavaParser, source: String): String =
        canonical(
            parser.parseBodyDeclaration<MethodDeclaration>(source)
                .result.orElseThrow()
        )

    private fun canonicalConstructor(
        parser: JavaParser,
        source: String
    ): String = canonical(
        parser.parseBodyDeclaration<ConstructorDeclaration>(source)
            .result.orElseThrow()
    )

    private fun canonical(node: Node?): String {
        if (node == null) return ""
        val copy = node.clone()
        copy.allContainedComments.toList().forEach { it.remove() }
        copy.removeComment()
        return normalizeJava(copy.toString())
    }

    private fun normalizeJava(source: String): String {
        val normalized = StringBuilder(source.length)
        var quoted = false
        var characterLiteral = false
        var escaped = false
        source.forEach { character ->
            if (quoted || characterLiteral) {
                normalized.append(character)
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (quoted && character == '"') {
                    quoted = false
                } else if (characterLiteral && character == '\'') {
                    characterLiteral = false
                }
            } else if (character == '"') {
                quoted = true
                normalized.append(character)
            } else if (character == '\'') {
                characterLiteral = true
                normalized.append(character)
            } else if (!character.isWhitespace()) {
                normalized.append(character)
            }
        }
        return normalized.toString()
    }

    private fun requireNoSimpleUse(unit: CompilationUnit, name: String) {
        val uses = unit.findAll(com.github.javaparser.ast.expr.SimpleName::class.java)
            .filter { simple ->
                simple.identifier == name &&
                    simple.findAncestor(
                        com.github.javaparser.ast.ImportDeclaration::class.java
                    ).isEmpty
            }
        if (uses.isNotEmpty()) {
            abort("Unexpected retained $name use in migrated mixin")
        }
    }

    private fun requireExactImport(unit: CompilationUnit, imported: String) {
        val matches = unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.nameAsString == imported
        }
        if (matches.size != 1) {
            abort("Expected exactly one import: $imported")
        }
    }

    private fun requireNoImport(unit: CompilationUnit, imported: String) {
        if (unit.imports.any {
                !it.isStatic && !it.isAsterisk && it.nameAsString == imported
            }
        ) {
            abort("Unexpected import in admitted state: $imported")
        }
    }

    private fun removeExactImport(unit: CompilationUnit, imported: String) {
        val matches = unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.nameAsString == imported
        }
        if (matches.size != 1) {
            abort("Expected exactly one import to remove: $imported")
        }
        matches.single().remove()
    }

    private fun addExactImport(unit: CompilationUnit, imported: String) {
        val matches = unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.nameAsString == imported
        }
        if (matches.isNotEmpty()) {
            abort("Target import already existed before closed migration: $imported")
        }
        unit.addImport(imported)
    }

    private fun Node.isDescendantOf(ancestor: Node): Boolean {
        var current: Node? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun isCastReceiver(
        expression: Expression?,
        type: String,
        receiver: String
    ): Boolean {
        var current = expression ?: return false
        while (current is EnclosedExpr) current = current.inner
        val cast = current as? CastExpr ?: return false
        return canonical(cast.type) == type &&
            canonical(cast.expression) == receiver
    }

    private fun captureJavaSourceGraph(project: Path): SourceGraph {
        val sourceRoot = project.resolve("src/main/java").toAbsolutePath().normalize()
        if (!Files.isDirectory(sourceRoot) || Files.isSymbolicLink(sourceRoot)) {
            abort("Create Java source graph is not a regular owned directory: $sourceRoot")
        }
        val paths = listJavaSources(sourceRoot)
        if (paths.isEmpty()) {
            abort("Create Java source graph is empty: $sourceRoot")
        }
        val files = paths.map(::capture)
        return SourceGraph(
            root = project,
            files = files,
            relativePaths = paths.mapTo(linkedSetOf()) {
                slash(project.relativize(it))
            }
        )
    }

    private fun listJavaSources(sourceRoot: Path): List<Path> {
        val paths = mutableListOf<Path>()
        Files.walk(sourceRoot).use { stream ->
            stream.filter { path ->
                path.fileName.toString().endsWith(".java") &&
                    Files.isRegularFile(path)
            }.forEach { path ->
                val normalized = path.toAbsolutePath().normalize()
                if (
                    !normalized.startsWith(sourceRoot) ||
                    Files.isSymbolicLink(path)
                ) {
                    abort("Create Java source graph contains an escaped link: $path")
                }
                paths.add(normalized)
            }
        }
        return paths.sortedBy { slash(sourceRoot.relativize(it)) }
    }

    private fun verifySourceGraph(graph: SourceGraph) {
        verifySourceGraphMembership(graph)
        verifyCapturedFiles(graph.files)
    }

    private fun verifySourceGraphMembership(graph: SourceGraph) {
        val sourceRoot = graph.root.resolve("src/main/java")
            .toAbsolutePath().normalize()
        val currentPaths = listJavaSources(sourceRoot).mapTo(linkedSetOf()) {
            slash(graph.root.relativize(it))
        }
        if (currentPaths != graph.relativePaths) {
            throw IllegalStateException(
                "Create Java source graph membership changed before publication"
            )
        }
    }

    private fun sourceGraphWithOutputs(
        graph: SourceGraph,
        outputs: List<PreparedOutput>
    ): SourceGraph {
        val replacements = outputs.associateBy {
            it.captured.path.toAbsolutePath().normalize()
        }
        return graph.copy(
            files = graph.files.map { captured ->
                val output = replacements[captured.path.toAbsolutePath().normalize()]
                if (output == null) {
                    captured
                } else {
                    CapturedFile(captured.path, output.after, sha256(output.after))
                }
            }
        )
    }

    private fun admitSourceClosure(
        graph: SourceGraph,
        state: FamilyState
    ) {
        val sources = graph.files.associate { captured ->
            slash(graph.root.relativize(captured.path)) to
                decodeUtf8(captured.path, captured.bytes)
        }
        fun requireExactDistribution(
            token: String,
            expected: Map<String, Int>
        ) {
            sources.forEach { (relative, source) ->
                val actual = occurrences(source, token)
                val wanted = expected[relative] ?: 0
                if (actual != wanted) {
                    abort(
                        "Create source closure for $token changed at $relative: " +
                            "expected $wanted, found $actual"
                    )
                }
            }
        }
        val legacySilk = if (state == FamilyState.LEGACY) {
            mapOf(ACCESSOR_RELATIVE to 1, ALL_BLOCKS_RELATIVE to 2)
        } else {
            emptyMap()
        }
        val targetSilk = if (state == FamilyState.MIGRATED) {
            mapOf(ACCESSOR_RELATIVE to 1, ALL_BLOCKS_RELATIVE to 2)
        } else {
            emptyMap()
        }
        val poseOwner = if (state == FamilyState.LEGACY) {
            ENTITY_RELATIVE
        } else {
            PLAYER_RELATIVE
        }
        requireExactDistribution(LEGACY_SILK_METHOD, legacySilk)
        requireExactDistribution(TARGET_SILK_METHOD, targetSilk)
        requireExactDistribution(POSE_HANDLER, mapOf(poseOwner to 1))
        requireExactDistribution("PlayerPoseMixin", emptyMap())
    }

    private fun occurrences(source: String, token: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val found = source.indexOf(token, offset)
            if (found < 0) return count
            count++
            offset = found + token.length
        }
    }

    private fun slash(path: Path): String =
        path.toString().replace('\\', '/')

    private fun capture(path: Path): CapturedFile {
        val bytes = path.readBytes()
        return CapturedFile(path, bytes, sha256(bytes))
    }

    private fun decodeUtf8(path: Path, bytes: ByteArray): String {
        val source = String(bytes, StandardCharsets.UTF_8)
        if (!source.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            abort("Create pose/loot source is not canonical UTF-8: $path")
        }
        return source
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun verifyCapturedFiles(captured: List<CapturedFile>) {
        captured.forEach { original ->
            if (!original.path.isRegularFile() || Files.isSymbolicLink(original.path)) {
                throw IllegalStateException(
                    "Create pose/loot input disappeared before publication: ${original.path}"
                )
            }
            val current = capture(original.path)
            if (
                current.sha256 != original.sha256 ||
                !current.bytes.contentEquals(original.bytes)
            ) {
                throw IllegalStateException(
                    "Create pose/loot input changed before publication: ${original.path}"
                )
            }
        }
    }

    private fun publishFamily(
        outputs: List<PreparedOutput>,
        immutableInputs: List<CapturedFile>,
        sourceGraph: SourceGraph,
        forbiddenPaths: List<Path>,
        commitProbe: ((Int, Path) -> Unit)?
    ) {
        val staged = linkedMapOf<PreparedOutput, Path>()
        val committed = mutableListOf<PreparedOutput>()
        try {
            outputs.forEach { output ->
                output.captured.path.parent.createDirectories()
                val temporary = Files.createTempFile(
                    output.captured.path.parent,
                    ".modporter-create-pose-loot-",
                    ".tmp"
                )
                Files.write(temporary, output.after)
                val stagedBytes = Files.readAllBytes(temporary)
                if (!stagedBytes.contentEquals(output.after)) {
                    error("Staged Create pose/loot bytes changed: ${output.captured.path}")
                }
                staged[output] = temporary
            }
            verifySourceGraph(sourceGraph)
            verifyCapturedFiles(immutableInputs)
            verifyForbiddenPaths(forbiddenPaths)
            staged.entries.forEachIndexed { index, (output, temporary) ->
                verifyPublicationState(
                    outputs,
                    committed,
                    immutableInputs,
                    sourceGraph,
                    forbiddenPaths
                )
                atomicMove(temporary, output.captured.path)
                committed += output
                commitProbe?.invoke(index, output.captured.path)
            }
            outputs.forEach { output ->
                val published = Files.readAllBytes(output.captured.path)
                if (!published.contentEquals(output.after)) {
                    error("Published Create pose/loot bytes changed: ${output.captured.path}")
                }
            }
            verifyCapturedFiles(immutableInputs)
            verifySourceGraphMembership(sourceGraph)
            verifyForbiddenPaths(forbiddenPaths)
        } catch (failure: Exception) {
            committed.asReversed().forEach { output ->
                runCatching {
                    val current = Files.readAllBytes(output.captured.path)
                    if (!current.contentEquals(output.after)) {
                        error(
                            "Published target changed before Create pose/loot rollback: " +
                                output.captured.path
                        )
                    }
                    val rollback = Files.createTempFile(
                        output.captured.path.parent,
                        ".modporter-create-pose-loot-rollback-",
                        ".tmp"
                    )
                    try {
                        Files.write(rollback, output.captured.bytes)
                        atomicMove(rollback, output.captured.path)
                    } finally {
                        Files.deleteIfExists(rollback)
                    }
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            runCatching {
                verifyPublicationState(
                    outputs = outputs,
                    committed = emptyList(),
                    immutableInputs = immutableInputs,
                    sourceGraph = sourceGraph,
                    forbiddenPaths = forbiddenPaths
                )
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            staged.values.forEach { temporary ->
                runCatching { Files.deleteIfExists(temporary) }
            }
        }
    }

    private fun verifyPublicationState(
        outputs: List<PreparedOutput>,
        committed: List<PreparedOutput>,
        immutableInputs: List<CapturedFile>,
        sourceGraph: SourceGraph,
        forbiddenPaths: List<Path>
    ) {
        verifySourceGraphMembership(sourceGraph)
        val committedSet = committed.toSet()
        immutableInputs.forEach { immutable ->
            val current = capture(immutable.path)
            if (
                current.sha256 != immutable.sha256 ||
                !current.bytes.contentEquals(immutable.bytes)
            ) {
                error("Immutable Create pose/loot input changed: ${immutable.path}")
            }
        }
        outputs.forEach { output ->
            val expected = if (output in committedSet) {
                output.after
            } else {
                output.captured.bytes
            }
            val actual = Files.readAllBytes(output.captured.path)
            if (!actual.contentEquals(expected)) {
                error("Create pose/loot publication state drifted: ${output.captured.path}")
            }
        }
        verifyForbiddenPaths(forbiddenPaths)
    }

    private fun verifyForbiddenPaths(paths: List<Path>) {
        paths.forEach { path ->
            if (path.exists()) {
                error("Forbidden Create pose/loot route appeared: $path")
            }
        }
    }

    private fun atomicMove(source: Path, target: Path) {
        try {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException(
                "Atomic Create pose/loot publication is unavailable for $target",
                unsupported
            )
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)
}
