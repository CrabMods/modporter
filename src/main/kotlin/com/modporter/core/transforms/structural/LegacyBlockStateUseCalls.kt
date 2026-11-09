package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forge 1.20.1's BlockState#use entry point dispatched the held-item branch before the block-only
 * fallback. The 1.21 API split those paths into useItemOn and useWithoutItem, so expression-shaped
 * legacy calls use a generated bridge that preserves evaluation order, result mapping, and fallback.
 */
internal object LegacyBlockStateUseCalls {
    const val RULE_ID = "struct-legacy-blockstate-use"
    private const val SIMPLE_NAME = "LegacyBlockStateUse"
    private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
    private const val BLOCK_POS = "net.minecraft.core.BlockPos"
    private const val PLAYER = "net.minecraft.world.entity.player.Player"
    private const val ITEM_RESULT = "net.minecraft.world.ItemInteractionResult"
    private const val USE_ITEM_ON =
        "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/world/entity/player/Player;" +
            "Lnet/minecraft/world/InteractionHand;" +
            "Lnet/minecraft/world/phys/BlockHitResult;)" +
            "Lnet/minecraft/world/ItemInteractionResult;"
    private const val USE_WITHOUT_ITEM =
        "(Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/world/entity/player/Player;" +
            "Lnet/minecraft/world/phys/BlockHitResult;)" +
            "Lnet/minecraft/world/InteractionResult;"
    private const val GET_BLOCK_STATE =
        "(Lnet/minecraft/core/BlockPos;)" +
            "Lnet/minecraft/world/level/block/state/BlockState;"
    private const val GET_BLOCK_STATE_NO_ARG =
        "()Lnet/minecraft/world/level/block/state/BlockState;"
    private const val GET_ITEM_IN_HAND =
        "(Lnet/minecraft/world/InteractionHand;)" +
            "Lnet/minecraft/world/item/ItemStack;"
    private const val RESULT = "()Lnet/minecraft/world/InteractionResult;"
    private val possibleCall = Regex("""\.\s*use\s*\(""")

    private data class PlannedWrite(
        val file: Path,
        val content: String,
        val change: Change
    )

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        compatPackageSupplier: () -> String,
        dryRun: Boolean
    ): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }
                .filter {
                    !projectDir.relativize(it).toString().replace('\\', '/')
                        .contains("/com/modporter/generated/")
                }
                .toList()
        }
        val sources = files.associateWith(Path::readText)
        if (sources.values.none { source ->
                possibleCall.containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source))
            }
        ) {
            return emptyList()
        }

        val probeHelper = "com.modporter.generated.LegacyBlockStateUse"
        if (sources.values.none { source ->
                pass.migrateLegacyBlockStateUseCalls(source, probeHelper) != source
            }
        ) {
            return emptyList()
        }

        val compatPackage = compatPackageSupplier()
        val qualifiedHelper = "$compatPackage.$SIMPLE_NAME"
        val migrated = linkedMapOf<Path, String>()
        var helperRequired = false
        for ((file, source) in sources) {
            val result = pass.migrateLegacyBlockStateUseCalls(source, qualifiedHelper)
            helperRequired = helperRequired || result.contains("$qualifiedHelper.use(")
            if (result != source) migrated[file] = result
        }
        if (!helperRequired) return emptyList()

        requireTargetContract()
        val expectedHelper = helperSource(compatPackage)
        check(parser().parse(expectedHelper).isSuccessful) {
            "Legacy BlockState use helper template is invalid; no files changed"
        }
        val helper = sourceRoot.resolve(compatPackage.replace('.', '/')).resolve("$SIMPLE_NAME.java")
        if (helper.exists()) {
            require(helper.readText() == expectedHelper) {
                "Existing legacy BlockState use helper differs from the canonical source: " +
                    "$helper; no files changed"
            }
        }

        val writes = migrated.mapTo(mutableListOf()) { (file, content) ->
            PlannedWrite(
                file,
                content,
                Change(
                    file = file,
                    line = 1,
                    description = "Preserve Forge BlockState use item dispatch and fallback order",
                    before = "state.use(level, player, hand, hit)",
                    after = "$SIMPLE_NAME.use(state, level, player, hand, hit)",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            )
        }
        if (!helper.exists()) {
            writes += PlannedWrite(
                helper,
                expectedHelper,
                Change(
                    file = helper,
                    line = 1,
                    description = "Generate the Forge BlockState use item-dispatch bridge",
                    before = "(none)",
                    after = "$qualifiedHelper.use",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            )
        }
        if (!dryRun) {
            for (write in writes) {
                write.file.parent.createDirectories()
                write.file.writeText(write.content)
            }
        }
        return writes.map(PlannedWrite::change)
    }

    private fun requireTargetContract() {
        fun public(owner: String, name: String, descriptor: String): Boolean =
            TargetMembers.inheritedMethod(owner, name, descriptor)?.access == "public"

        require(
            public(BLOCK_STATE, "useItemOn", USE_ITEM_ON) &&
                public(BLOCK_STATE, "useWithoutItem", USE_WITHOUT_ITEM) &&
                public(PLAYER, "getItemInHand", GET_ITEM_IN_HAND) &&
                public(ITEM_RESULT, "result", RESULT) &&
                TargetMembers.hasField(ITEM_RESULT, "PASS_TO_DEFAULT_BLOCK_INTERACTION")
        ) {
            "NeoForge 1.21.1 BlockState use compatibility contract is unavailable; " +
                "no files changed"
        }
    }

    private fun helperSource(compatPackage: String): String = """
        |package $compatPackage;
        |
        |import net.minecraft.world.InteractionHand;
        |import net.minecraft.world.InteractionResult;
        |import net.minecraft.world.ItemInteractionResult;
        |import net.minecraft.world.entity.player.Player;
        |import net.minecraft.world.item.ItemStack;
        |import net.minecraft.world.level.Level;
        |import net.minecraft.world.level.block.state.BlockState;
        |import net.minecraft.world.phys.BlockHitResult;
        |
        |/** Forge 1.20.1's BlockState#use dispatch on the split 1.21 interaction API. */
        |public final class $SIMPLE_NAME {
        |    private $SIMPLE_NAME() {
        |    }
        |
        |    public static InteractionResult use(
        |        BlockState state,
        |        Level level,
        |        Player player,
        |        InteractionHand hand,
        |        BlockHitResult hit
        |    ) {
        |        java.util.Objects.requireNonNull(state);
        |        ItemStack stack = player.getItemInHand(hand);
        |        ItemInteractionResult itemResult = state.useItemOn(stack, level, player, hand, hit);
        |        if (itemResult == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
        |            return state.useWithoutItem(level, player, hit);
        |        }
        |        return itemResult.result();
        |    }
        |}
        |""".trimMargin()

    internal fun migrateSource(
        pass: StructuralRefactorPass,
        source: String,
        qualifiedHelper: String
    ): String {
        val executable = JavaSourceText.maskCommentsAndLiterals(source)
        if (!possibleCall.containsMatchIn(executable)) return source
        val parser = parser()
        val parsed = parser.parse(source)
        require(parsed.isSuccessful) {
            "Cannot parse a possible legacy BlockState.use call: ${pass.parseProblems(parsed)}"
        }
        val unit = parsed.result.orElseThrow()
        val typing = SourceValueTypes(pass, source)
        val offset = offsetFunction(source)
        val replacements = unit.findAll(MethodCallExpr::class.java)
            .filter { call ->
                call.nameAsString == "use" &&
                    call.arguments.size == 4 &&
                    call.scope.map { receiver ->
                        isNativeBlockStateExpression(pass, receiver, typing, offset)
                    }.orElse(false)
            }
            .sortedByDescending(offset)
        if (replacements.isEmpty()) return source

        LexicalPreservingPrinter.setup(unit)
        for (call in replacements) {
            val receiver = call.scope.orElseThrow()
            val replacement = parser.parseExpression<Expression>(
                "$qualifiedHelper.use($receiver, ${call.arguments.joinToString(", ")})"
            ).result.orElseThrow()
            call.replace(replacement)
        }
        val result = LexicalPreservingPrinter.print(unit)
        check(parser.parse(result).isSuccessful) {
            "Legacy BlockState.use migration emitted invalid Java"
        }
        return result
    }

    private fun isNativeBlockStateExpression(
        pass: StructuralRefactorPass,
        expression: Expression,
        typing: SourceValueTypes,
        offset: (Node) -> Int
    ): Boolean {
        if (typing.typeOf(expression.toString(), offset(expression)) == BLOCK_STATE) return true
        val getter = expression as? MethodCallExpr ?: return false
        if (getter.nameAsString != "getBlockState" || getter.scope.isEmpty) return false
        val descriptor = when (getter.arguments.size) {
            0 -> GET_BLOCK_STATE_NO_ARG
            1 -> {
                if (typing.typeOf(
                        getter.arguments[0].toString(),
                        offset(getter.arguments[0])
                    ) != BLOCK_POS
                ) {
                    return false
                }
                GET_BLOCK_STATE
            }
            else -> return false
        }
        val owner = typing.typeOf(
            getter.scope.get().toString(),
            offset(getter.scope.get())
        ) ?: return false
        return inheritsPublicTargetMethod(pass, owner, "getBlockState", descriptor)
    }

    private fun inheritsPublicTargetMethod(
        pass: StructuralRefactorPass,
        owner: String,
        name: String,
        descriptor: String
    ): Boolean {
        var current: String? = owner
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (current !in pass.projectValueFacts.types &&
                TargetMembers.inheritedMethod(current, name, descriptor)?.access == "public"
            ) {
                return true
            }
            current = pass.projectValueFacts.parents[current]
        }
        return false
    }

    private fun parser(): JavaParser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private fun offsetFunction(source: String): (Node) -> Int {
        val starts = buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }
        return { node ->
            node.range.map {
                starts[it.begin.line - 1] + it.begin.column - 1
            }.orElse(0)
        }
    }
}

internal fun StructuralRefactorPass.migrateLegacyBlockStateUseCalls(
    source: String,
    qualifiedHelper: String
): String = LegacyBlockStateUseCalls.migrateSource(this, source, qualifiedHelper)
