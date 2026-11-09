package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Identifies a source-owned block-entity base that caches a legacy getRenderBoundingBox hook.
 *
 * Root uses the returned canonical name to wire the corresponding renderer bridge only when this
 * complete source family is present.
 */
internal fun StructuralRefactorPass.legacyCachedRenderBoundsType(source: String): String? {
    if (!source.contains("getRenderBoundingBox") ||
        !source.contains("createRenderBoundingBox")
    ) return null
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return null
    val typing = SourceValueTypes(this, source)
    val family = cachedRenderBoundsFamilies(unit, typing).singleOrNull() ?: return null
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return if (packageName.isBlank()) family.owner.nameAsString
    else "$packageName.${family.owner.nameAsString}"
}

/**
 * Replaces the removed BlockEntity super hook with its old unit-box fallback.
 *
 * Dynamic custom boxes remain available through the public cached getter; the companion renderer
 * migration below is what reconnects that getter to target culling.
 */
internal fun StructuralRefactorPass.migrateLegacyCachedRenderBoundsBase(source: String): String {
    if (!source.contains("super.getRenderBoundingBox()")) return source
    if (!targetRenderBoundsContract()) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val families = cachedRenderBoundsFamilies(unit, typing)
    if (families.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (family in families) {
        val fallback = parser.parseExpression<com.github.javaparser.ast.expr.Expression>(
            "new AABB(getBlockPos())"
        ).result.orElse(null) ?: continue
        family.fallback.setExpression(fallback)
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

/**
 * Adds the target renderer-side culling bridge for a proven cached block-entity family.
 *
 * The fallback delegates to BlockEntityRenderer's target default. Cached and subclass-specific
 * boxes dispatch through the block entity's public getter.
 */
internal fun StructuralRefactorPass.migrateLegacyCachedRenderBoundsBridge(
    source: String,
    cachedBlockEntityType: String
): String {
    if (!source.contains("BlockEntityRenderer") ||
        source.contains("getRenderBoundingBox")
    ) return source
    if (!targetRenderBoundsContract() ||
        !cachedBlockEntityType.matches(Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""))
    ) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val owners = unit.findAll(ClassOrInterfaceDeclaration::class.java).filter { owner ->
        owner.parentNode.orElse(null) is CompilationUnit &&
            owner.isAbstract &&
            owner.typeParameters.any { parameter ->
                parameter.typeBound.any {
                    typing.isTypeName(it.nameWithScope, BLOCK_ENTITY)
                }
            } &&
            owner.implementedTypes.any { implemented ->
                typing.isTypeName(implemented.nameWithScope, BLOCK_ENTITY_RENDERER) &&
                    implemented.typeArguments.map { it.size == 1 }.orElse(false) &&
                    implemented.typeArguments.get().single().asString() in
                    owner.typeParameters.map { it.nameAsString }
            } &&
            owner.getMethodsByName("getRenderBoundingBox").isEmpty()
    }
    if (owners.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (owner in owners) {
        val rendererType = owner.implementedTypes.singleOrNull {
            typing.isTypeName(it.nameWithScope, BLOCK_ENTITY_RENDERER)
        } ?: continue
        if (rendererType.nameWithScope != "BlockEntityRenderer") continue
        val entityType = rendererType.typeArguments.get().single().asString()
        val method = parser.parseBodyDeclaration<MethodDeclaration>(
            """
                @Override
                public AABB getRenderBoundingBox($entityType blockEntity) {
                    if (blockEntity instanceof $cachedBlockEntityType)
                        return (($cachedBlockEntityType) blockEntity).getRenderBoundingBox();
                    return BlockEntityRenderer.super.getRenderBoundingBox(blockEntity);
                }
            """.trimIndent()
        ).result.orElse(null) ?: continue
        owner.addMember(method)
        changed = true
    }
    if (!changed) return source
    return JavaSourceText.addImport(
        LexicalPreservingPrinter.print(unit),
        AABB
    )
}

private data class CachedRenderBoundsFamily(
    val owner: ClassOrInterfaceDeclaration,
    val fallback: ReturnStmt
)

private fun StructuralRefactorPass.cachedRenderBoundsFamilies(
    unit: CompilationUnit,
    typing: SourceValueTypes
): List<CachedRenderBoundsFamily> =
    unit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull { owner ->
        if (owner.parentNode.orElse(null) !is CompilationUnit || !owner.isAbstract) {
            return@mapNotNull null
        }
        if (!isBlockEntityFamily(owner, unit)) return@mapNotNull null
        val cacheFields = owner.fields.flatMap { field ->
            field.variables.filter { variable ->
                typing.isTypeName(variable.type.asString(), AABB)
            }.map { it.nameAsString }
        }.toSet()
        if (cacheFields.isEmpty()) return@mapNotNull null

        val getter = owner.getMethodsByName("getRenderBoundingBox").singleOrNull { method ->
            method.parameters.isEmpty() &&
                typing.isTypeName(method.type.asString(), AABB) &&
                method.body.map { body ->
                    body.findAll(ReturnStmt::class.java).any { returned ->
                        (returned.expression.orElse(null) as? NameExpr)?.nameAsString in cacheFields
                    } &&
                        body.findAll(AssignExpr::class.java).any { assignment ->
                            (assignment.target as? NameExpr)?.nameAsString in cacheFields &&
                                (assignment.value as? MethodCallExpr)?.let {
                                    it.nameAsString == "createRenderBoundingBox" &&
                                        it.arguments.isEmpty()
                                } == true
                        }
                }.orElse(false)
        } ?: return@mapNotNull null
        if (!getter.isPublic) return@mapNotNull null

        val create = owner.getMethodsByName("createRenderBoundingBox").singleOrNull { method ->
            method.parameters.isEmpty() &&
                typing.isTypeName(method.type.asString(), AABB) &&
                method.body.isPresent &&
                method.body.get().statements.size == 1
        } ?: return@mapNotNull null
        val returned = create.body.get().statements.single() as? ReturnStmt
            ?: return@mapNotNull null
        val superCall = returned.expression.orElse(null) as? MethodCallExpr
            ?: return@mapNotNull null
        if (superCall.nameAsString != "getRenderBoundingBox" ||
            superCall.arguments.isNotEmpty() ||
            superCall.scope.orElse(null) !is SuperExpr
        ) return@mapNotNull null
        CachedRenderBoundsFamily(owner, returned)
    }

private fun StructuralRefactorPass.isBlockEntityFamily(
    owner: ClassOrInterfaceDeclaration,
    unit: CompilationUnit
): Boolean {
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    var current: String? = if (packageName.isBlank()) {
        owner.nameAsString
    } else {
        "$packageName.${owner.nameAsString}"
    }
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        if (current == BLOCK_ENTITY ||
            TargetMembers.hasClass(current) &&
            TargetMembers.inheritsFrom(current, BLOCK_ENTITY)
        ) return true
        current = projectValueFacts.parents[current]
    }
    return false
}

private const val BLOCK_ENTITY =
    "net.minecraft.world.level.block.entity.BlockEntity"
private const val BLOCK_ENTITY_RENDERER =
    "net.minecraft.client.renderer.blockentity.BlockEntityRenderer"
private const val AABB = "net.minecraft.world.phys.AABB"

private fun targetRenderBoundsContract(): Boolean {
    val rendererBounds = TargetMembers.inheritedMethod(
        BLOCK_ENTITY_RENDERER,
        "getRenderBoundingBox",
        "(Lnet/minecraft/world/level/block/entity/BlockEntity;)" +
            "Lnet/minecraft/world/phys/AABB;"
    )
    return !TargetMembers.hasMethod(BLOCK_ENTITY, "getRenderBoundingBox") &&
        rendererBounds != null &&
        TargetMembers.hasMethod(BLOCK_ENTITY, "getBlockPos")
}
