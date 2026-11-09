package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

private const val BLOCK = "net.minecraft.world.level.block.Block"
private const val BLOCK_EXTENSION = "net.neoforged.neoforge.common.extensions.IBlockExtension"
private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
private const val BLOCK_GETTER = "net.minecraft.world.level.BlockGetter"
private const val LEVEL_READER = "net.minecraft.world.level.LevelReader"
private const val BLOCK_POS = "net.minecraft.core.BlockPos"
private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"
private const val HIT_RESULT = "net.minecraft.world.phys.HitResult"
private const val PLAYER = "net.minecraft.world.entity.player.Player"

private const val TARGET_THREE_DESCRIPTOR =
    "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/level/block/state/BlockState;)" +
        "Lnet/minecraft/world/item/ItemStack;"

private const val TARGET_FIVE_DESCRIPTOR =
    "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/HitResult;" +
        "Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;" +
        "Lnet/minecraft/world/entity/player/Player;)" +
        "Lnet/minecraft/world/item/ItemStack;"

/**
 * Owns the complete Block clone-item overload family.
 *
 * Source and target both have a three-argument vanilla hook and a sensitive
 * five-argument extension hook. The only type change shared by both hooks is
 * BlockGetter -> LevelReader. A class that already declares both hooks must
 * retain both: the extension default delegates to the three-argument hook.
 *
 * When only the three-argument override exists, its behavior remains available
 * to direct project callers and a five-argument override delegates to it. When
 * both exist, both bodies remain independent. Existing annotations are left
 * untouched.
 */
internal fun StructuralRefactorPass.migrateLegacyBlockCloneItemStackOverloadFamily(
    source: String
): String {
    if (!source.contains("getCloneItemStack") || !source.contains("@Override")) return source
    if (TARGET_THREE_DESCRIPTOR !in TargetMembers.methodDescriptors(BLOCK, "getCloneItemStack") ||
        TARGET_FIVE_DESCRIPTOR !in TargetMembers.methodDescriptors(BLOCK_EXTENSION, "getCloneItemStack")
    ) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val plans = unit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull { owner ->
        if (owner.isInterface) return@mapNotNull null
        val canonicalOwner = qualifiedNamedOwner(owner, unit) ?: return@mapNotNull null
        if (!inheritsFromBlock(canonicalOwner)) return@mapNotNull null

        val methods = owner.methods.filter {
            it.nameAsString == "getCloneItemStack" &&
                it.annotations.any { annotation -> annotation.name.identifier == "Override" } &&
                unit.isType(it.type.asString(), ITEM_STACK, projectValueFacts.types)
        }
        val threes = methods.filter {
            it.cloneShape(unit, projectValueFacts.types) == CloneShape.THREE
        }
        val fives = methods.filter {
            it.cloneShape(unit, projectValueFacts.types) == CloneShape.FIVE
        }
        if (threes.size > 1 || fives.size > 1) return@mapNotNull null
        val three = threes.singleOrNull()
        val five = fives.singleOrNull()
        if (three == null && five == null) return@mapNotNull null
        val changesLevelType =
            three?.parameters?.get(0)?.let {
                unit.isType(it.typeAsString, BLOCK_GETTER, projectValueFacts.types)
            } == true ||
                five?.parameters?.get(2)?.let {
                    unit.isType(it.typeAsString, BLOCK_GETTER, projectValueFacts.types)
                } == true
        if (changesLevelType &&
            !unit.canIntroduceSimpleType(LEVEL_READER, projectValueFacts.types)
        ) return@mapNotNull null
        if (three != null && five == null &&
            (!unit.canIntroduceSimpleType(LEVEL_READER, projectValueFacts.types) ||
                !unit.canIntroduceSimpleType(HIT_RESULT, projectValueFacts.types) ||
                !unit.canIntroduceSimpleType(PLAYER, projectValueFacts.types))
        ) return@mapNotNull null

        OwnerPlan(owner, three, five)
    }
    if (plans.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    var needsHitResult = false
    var needsPlayer = false
    var needsLevelReader = false
    var changed = false

    for (plan in plans) {
        plan.five?.let {
            if (unit.isType(
                    it.parameters[2].typeAsString,
                    BLOCK_GETTER,
                    projectValueFacts.types
                )
            ) {
                it.parameters[2].setType("LevelReader")
                needsLevelReader = true
                changed = true
            }
        }

        val three = plan.three ?: continue
        if (unit.isType(
                three.parameters[0].typeAsString,
                BLOCK_GETTER,
                projectValueFacts.types
            )
        ) {
            three.parameters[0].setType("LevelReader")
            needsLevelReader = true
            changed = true
        }
        if (plan.five != null) continue

        val usedNames = plan.owner.findAll(SimpleName::class.java)
            .mapTo(mutableSetOf()) { it.asString() }
        val stateName = uniqueName("state", usedNames)
        usedNames += stateName
        val targetName = uniqueName("target", usedNames)
        usedNames += targetName
        val levelName = uniqueName("level", usedNames)
        usedNames += levelName
        val posName = uniqueName("pos", usedNames)
        usedNames += posName
        val playerName = uniqueName("player", usedNames)

        plan.owner.addMember(StaticJavaParser.parseBodyDeclaration("""
            @Override
            public ItemStack getCloneItemStack(
                BlockState $stateName,
                HitResult $targetName,
                LevelReader $levelName,
                BlockPos $posName,
                Player $playerName
            ) {
                return getCloneItemStack($levelName, $posName, $stateName);
            }
        """.trimIndent()))
        needsHitResult = true
        needsPlayer = true
        needsLevelReader = true
        changed = true
    }

    if (!changed) return source
    var result = LexicalPreservingPrinter.print(unit)
    if (needsHitResult) result = addExecutableImportIfMissing(result, HIT_RESULT)
    if (needsPlayer) result = addExecutableImportIfMissing(result, PLAYER)
    if (needsLevelReader) result = addExecutableImportIfMissing(result, LEVEL_READER)
    return result
}

private enum class CloneShape {
    THREE,
    FIVE
}

private data class OwnerPlan(
    val owner: ClassOrInterfaceDeclaration,
    val three: MethodDeclaration?,
    val five: MethodDeclaration?
)

private fun MethodDeclaration.cloneShape(
    unit: CompilationUnit,
    projectTypes: Set<String>
): CloneShape? {
    val types = parameters.map { it.typeAsString }
    return when {
        types.size == 3 &&
            unit.isEitherType(types[0], BLOCK_GETTER, LEVEL_READER, projectTypes) &&
            unit.isType(types[1], BLOCK_POS, projectTypes) &&
            unit.isType(types[2], BLOCK_STATE, projectTypes) -> CloneShape.THREE
        types.size == 5 &&
            unit.isType(types[0], BLOCK_STATE, projectTypes) &&
            unit.isType(types[1], HIT_RESULT, projectTypes) &&
            unit.isEitherType(types[2], BLOCK_GETTER, LEVEL_READER, projectTypes) &&
            unit.isType(types[3], BLOCK_POS, projectTypes) &&
            unit.isType(types[4], PLAYER, projectTypes) -> CloneShape.FIVE
        else -> null
    }
}

private fun CompilationUnit.isEitherType(
    sourceType: String,
    first: String,
    second: String,
    projectTypes: Set<String>
): Boolean =
    isType(sourceType, first, projectTypes) || isType(sourceType, second, projectTypes)

private fun CompilationUnit.isType(
    sourceType: String,
    canonical: String,
    projectTypes: Set<String>
): Boolean {
    val plain = sourceType.substringBefore('<').trim()
    if (plain == canonical) return true
    val simple = canonical.substringAfterLast('.')
    if (plain != simple) return false
    if (findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
    val packageName = packageDeclaration.map { it.nameAsString }.orElse("")
    val samePackage = projectTypes.filter {
        it.substringAfterLast('.') == simple &&
            it.substringBeforeLast('.', "") == packageName
    }
    if (samePackage.isNotEmpty()) return samePackage.singleOrNull() == canonical
    val namespace = canonical.substringBeforeLast('.')
    val explicit = imports.filter {
        !it.isStatic && !it.isAsterisk && it.name.identifier == simple
    }.map { it.nameAsString }.distinct()
    if (explicit.isNotEmpty()) return explicit.singleOrNull() == canonical
    return imports.any {
        !it.isStatic &&
            it.isAsterisk && it.nameAsString == namespace
    }
}

private fun CompilationUnit.canIntroduceSimpleType(
    canonical: String,
    projectTypes: Set<String>
): Boolean {
    val simple = canonical.substringAfterLast('.')
    if (findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }) return false
    val packageName = packageDeclaration.map { it.nameAsString }.orElse("")
    if (projectTypes.any {
            it.substringAfterLast('.') == simple &&
                it.substringBeforeLast('.', "") == packageName &&
                it != canonical
        }
    ) return false
    val explicit = imports.filter {
        !it.isStatic && !it.isAsterisk && it.name.identifier == simple
    }.map { it.nameAsString }.distinct()
    return explicit.isEmpty() || explicit.singleOrNull() == canonical
}

private fun StructuralRefactorPass.inheritsFromBlock(owner: String): Boolean {
    var current: String? = owner
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        if (TargetMembers.inheritsFrom(current, BLOCK)) return true
        current = projectValueFacts.parents[current] ?: projectParents[current]
    }
    return false
}

private fun qualifiedNamedOwner(
    declaration: ClassOrInterfaceDeclaration,
    unit: CompilationUnit
): String? {
    val names = mutableListOf<String>()
    var current: Node = declaration
    while (current is TypeDeclaration<*>) {
        names += current.nameAsString
        current = current.parentNode.orElse(null) ?: return null
    }
    if (current !== unit) return null
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val nested = names.asReversed().joinToString(".")
    return if (packageName.isBlank()) nested else "$packageName.$nested"
}

private fun uniqueName(base: String, used: Set<String>): String {
    var candidate = base
    while (candidate in used) candidate += "_"
    return candidate
}
