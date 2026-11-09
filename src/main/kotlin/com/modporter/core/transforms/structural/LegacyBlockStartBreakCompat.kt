package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Preserves admitted source calls to Forge 1.20.1's removed ItemStack#onBlockStartBreak method.
 *
 * NeoForge 1.21.1 removed both the method and the client/server player-mining dispatch sites.
 * Until those native sites are separately restored and verified, source-owned Item overrides are
 * rejected. Exact source call sites retain the proven old default false result only after a sealed
 * paired dependency inventory proves that no supplied dependency owns an unconverted override.
 */
internal object LegacyBlockStartBreakCompatMigration {
    const val RULE_ID = "struct-legacy-block-start-break-compat"
    const val COMPAT_PACKAGE = "com.modporter.compat.item.v1"

    private const val ITEM = "net.minecraft.world.item.Item"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val BLOCK_POS = "net.minecraft.core.BlockPos"
    private const val PLAYER = "net.minecraft.world.entity.player.Player"
    private const val LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity"
    private const val OLD_ITEM_EXTENSION =
        "net.minecraftforge.common.extensions.IForgeItem"
    private const val OLD_ITEM_STACK_EXTENSION =
        "net.minecraftforge.common.extensions.IForgeItemStack"
    private const val TARGET_ITEM_EXTENSION =
        "net.neoforged.neoforge.common.extensions.IItemExtension"
    private const val TARGET_ITEM_STACK_EXTENSION =
        "net.neoforged.neoforge.common.extensions.IItemStackExtension"
    private const val HOOKS = "$COMPAT_PACKAGE.LegacyBlockStartBreakHooks"
    private const val METHOD = "onBlockStartBreak"
    private const val STACK_DESCRIPTOR =
        "(Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/world/entity/player/Player;)Z"
    private const val ITEM_DESCRIPTOR =
        "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/world/entity/player/Player;)Z"
    private const val SOURCE_CLASS_RUNTIME_FEATURE = 17
    private const val TARGET_CLASS_RUNTIME_FEATURE = 21

    private data class SourceUnit(
        val file: Path,
        val source: String,
        val unit: CompilationUnit
    )

    private data class PlannedWrite(
        val file: Path,
        val content: String,
        val change: Change
    )

    private data class SourceMigration(
        val source: String,
        val admitted: Boolean
    )

    private enum class HeritageProof {
        YES,
        NO,
        UNKNOWN
    }

    fun migrate(
        pass: StructuralRefactorPass,
        project: Path,
        dryRun: Boolean
    ): List<Change> =
        migrate(
            pass,
            project,
            dryRun,
            LegacyMigrationArtifactInventoryHandoff.require(project)
        )

    internal fun migrate(
        pass: StructuralRefactorPass,
        project: Path,
        dryRun: Boolean,
        inventory: LegacyMigrationArtifactPair
    ): List<Change> {
        val root = project.resolve("src/main/java")
        if (!root.isDirectory()) return emptyList()
        val parser = legacyInteractionParser()
        val files = pass.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.extension == "java" }
                .filter {
                    !it.toString().replace('\\', '/')
                        .contains("/$COMPAT_PACKAGE/".replace('.', '/'))
                }
                .filter {
                    !it.toString().replace('\\', '/')
                        .contains("/com/modporter/generated/")
                }
                .toList()
        }
        val parseFailures = mutableListOf<String>()
        var hasLegacyToken = false
        val units = files.mapNotNull { file ->
            val source = file.readText()
            hasLegacyToken = hasLegacyToken || source.contains(METHOD)
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful) {
                parseFailures += "$file: ${pass.parseProblems(parsed)}"
                null
            } else {
                SourceUnit(file, source, parsed.result.orElseThrow())
            }
        }
        if (!hasLegacyToken) return emptyList()
        require(parseFailures.isEmpty()) {
            "Cannot build a complete $METHOD source inventory because parsing failed: " +
                parseFailures.joinToString("; ") +
                "; no files changed"
        }

        val normalizedProject = project.toAbsolutePath().normalize()
        require(inventory.acceptsProject(normalizedProject)) {
            "Legacy block-start-break inventory belongs to another project"
        }
        inventory.verifyCurrentBytes("legacy block-start-break preflight")
        require(inventory.source.platformIdentity.family == "legacy-forge" &&
            inventory.source.platformIdentity.minecraftVersion == "1.20.1"
        ) {
            "Legacy block-start-break source inventory is not Forge 1.20.1"
        }
        require(inventory.target.platformIdentity.family == "neoforge" &&
            inventory.target.platformIdentity.minecraftVersion == "1.21.1"
        ) {
            "Legacy block-start-break target inventory is not NeoForge 1.21.1"
        }

        val sourcePlatform = ArtifactClasses.readSingle(
            inventory.source.platformArtifacts.named,
            SOURCE_CLASS_RUNTIME_FEATURE
        )
        val targetPlatform = ArtifactClasses.readSingle(
            inventory.target.platformArtifacts.merged,
            TARGET_CLASS_RUNTIME_FEATURE
        )
        verifyPlatformSurfaces(sourcePlatform, targetPlatform)
        val sourceClasses = verifyDependencyOverrides(inventory)

        val previousFacts = pass.projectValueFacts
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        try {
            val migratedSources = linkedMapOf<Path, String>()
            var admitted = false
            for (source in units.filter { it.source.contains(METHOD) }) {
                val migrated = migrateSource(
                    pass,
                    parser,
                    source,
                    sourceClasses
                )
                admitted = admitted || migrated.admitted
                check(parser.parse(migrated.source).isSuccessful) {
                    "Legacy block-start-break migration emitted invalid Java for " +
                        "${source.file}; no files changed"
                }
                if (migrated.source != source.source) {
                    migratedSources[source.file] = migrated.source
                }
            }

            if (!admitted) return emptyList()
            val writes = mutableListOf<PlannedWrite>()
            for ((file, migrated) in migratedSources) {
                writes += PlannedWrite(
                    file,
                    migrated,
                    Change(
                        file = file,
                        line = 1,
                        description =
                            "Preserve admitted legacy block-start call-site result",
                        before = "ItemStack/Item onBlockStartBreak",
                        after = "verified old-default compatibility call",
                        confidence = Confidence.HIGH,
                        ruleId = RULE_ID
                    )
                )
            }
            for ((name, content) in generatedSources()) {
                check(parser.parse(content).isSuccessful) {
                    "Legacy block-start-break template $name emitted invalid Java; " +
                        "no files changed"
                }
                val file = root.resolve(
                    COMPAT_PACKAGE.replace('.', '/') + "/$name.java"
                )
                if (file.exists()) {
                    require(file.readText() == content) {
                        "Existing legacy block-start-break helper differs from the " +
                            "canonical source: $file; no files changed"
                    }
                    continue
                }
                writes += PlannedWrite(
                    file,
                    content,
                    Change(
                        file = file,
                        line = 1,
                        description =
                            "Generate the legacy block-start default helper",
                        before = "(missing compatibility source)",
                        after = "$COMPAT_PACKAGE.$name",
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
            return writes.map { it.change }
        } finally {
            pass.projectValueFacts = previousFacts
        }
    }

    private fun migrateSource(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        source: SourceUnit,
        sourcePlatform: ArtifactClasses
    ): SourceMigration {
        val unit = source.unit
        val typing = SourceValueTypes(pass, source.source)
        val offset = legacySourceOffset(source.source)
        LexicalPreservingPrinter.setup(unit)
        var changed = false
        var admitted = false
        for (method in unit.findAll(MethodDeclaration::class.java)) {
            if (method.nameAsString != METHOD || method.parameters.size != 3) continue
            val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .orElse(null) ?: continue
            val ownerName = canonicalSourceType(unit, owner) ?: continue
            when (sourceHeritage(
                pass.projectValueFacts,
                sourcePlatform,
                ownerName,
                ITEM
            )) {
                HeritageProof.NO -> continue
                HeritageProof.UNKNOWN -> error(
                    "Cannot prove whether $ownerName owns the legacy $METHOD override in " +
                        "${source.file}; no files changed"
                )
                HeritageProof.YES -> Unit
            }
            val exact = method.type.isPrimitiveType &&
                method.type.asPrimitiveType().type ==
                PrimitiveType.Primitive.BOOLEAN &&
                typing.isTypeName(method.parameters[0].typeAsString, ITEM_STACK) &&
                typing.isTypeName(method.parameters[1].typeAsString, BLOCK_POS) &&
                typing.isTypeName(method.parameters[2].typeAsString, PLAYER)
            if (!exact) {
                val looksLegacy = method.parameters.map {
                    it.typeAsString.substringAfterLast('.')
                } == listOf("ItemStack", "BlockPos", "Player")
                require(!looksLegacy) {
                    "Item subclass $ownerName declares an unresolved or unsupported " +
                        "$METHOD override in ${source.file}; no files changed"
                }
                continue
            }
            require(method.isPublic && !method.isStatic && !method.isPrivate) {
                "Legacy $METHOD override on $ownerName is not a public instance method; " +
                    "no files changed"
            }
            error(
                "Source Item subclass $ownerName declares legacy $METHOD in ${source.file}, " +
                    "but NeoForge 1.21.1 has no verified client/server player-mining dispatch " +
                    "for the compatibility hook; no files changed"
            )
        }

        for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
            if (!call.findCompilationUnit().isPresent ||
                call.nameAsString != METHOD ||
                call.scope.isEmpty ||
                call.scope.orElse(null) is SuperExpr
            ) continue
            val scope = call.scope.get()
            if (scope.toString() == HOOKS) continue
            val scopeType = blockStartBreakExpressionType(
                pass.projectValueFacts,
                sourcePlatform,
                typing,
                scope,
                offset
            )
            if (call.arguments.size == 2) {
                if (scopeType != ITEM_STACK) {
                    val argumentsLookLegacy =
                        typing.typeOf(
                            call.arguments[0].toString(),
                            offset(call.arguments[0])
                        ) == BLOCK_POS &&
                            isSourceSubtype(
                                pass.projectValueFacts,
                                sourcePlatform,
                                typing.typeOf(
                                    call.arguments[1].toString(),
                                    offset(call.arguments[1])
                                ),
                                PLAYER
                            )
                    require(scopeType != null || !argumentsLookLegacy) {
                        "Cannot prove the receiver of a possible ItemStack $METHOD call " +
                            "in ${source.file}; no files changed"
                    }
                    continue
                }
                require(
                    typing.typeOf(
                        call.arguments[0].toString(),
                        offset(call.arguments[0])
                    ) == BLOCK_POS &&
                        isSourceSubtype(
                            pass.projectValueFacts,
                            sourcePlatform,
                            typing.typeOf(
                                call.arguments[1].toString(),
                                offset(call.arguments[1])
                            ),
                            PLAYER
                        )
                ) {
                    "Cannot prove the legacy ItemStack $METHOD arguments in " +
                        "${source.file}; no files changed"
                }
                call.replace(
                    parser.parseExpression<Expression>(
                        "$HOOKS.onBlockStartBreak(" +
                            "$scope, ${call.arguments.joinToString(", ")})"
                    ).result.orElseThrow()
                )
                changed = true
                admitted = true
                continue
            }
            if (call.arguments.size != 3) continue
            val itemScope = sourceHeritage(
                    pass.projectValueFacts,
                    sourcePlatform,
                    scopeType,
                    ITEM
                )
            if (itemScope != HeritageProof.YES) {
                val argumentsLookLegacy =
                    typing.typeOf(
                        call.arguments[0].toString(),
                        offset(call.arguments[0])
                    ) == ITEM_STACK &&
                        typing.typeOf(
                            call.arguments[1].toString(),
                            offset(call.arguments[1])
                        ) == BLOCK_POS &&
                        isSourceSubtype(
                            pass.projectValueFacts,
                            sourcePlatform,
                            typing.typeOf(
                                call.arguments[2].toString(),
                                offset(call.arguments[2])
                            ),
                            PLAYER
                        )
                require(
                    itemScope != HeritageProof.UNKNOWN || !argumentsLookLegacy
                ) {
                    "Cannot prove the receiver of a possible Item $METHOD call in " +
                        "${source.file}; no files changed"
                }
                continue
            }
            require(
                typing.typeOf(
                    call.arguments[0].toString(),
                    offset(call.arguments[0])
                ) == ITEM_STACK &&
                    typing.typeOf(
                        call.arguments[1].toString(),
                        offset(call.arguments[1])
                    ) == BLOCK_POS &&
                    isSourceSubtype(
                        pass.projectValueFacts,
                        sourcePlatform,
                        typing.typeOf(
                            call.arguments[2].toString(),
                            offset(call.arguments[2])
                        ),
                        PLAYER
                    )
            ) {
                "Cannot prove the legacy Item $METHOD arguments in ${source.file}; " +
                    "no files changed"
            }
            call.replace(
                parser.parseExpression<Expression>(
                    "$HOOKS.onBlockStartBreak(" +
                        "$scope, ${call.arguments.joinToString(", ")})"
                ).result.orElseThrow()
            )
            changed = true
            admitted = true
        }
        return SourceMigration(
            if (changed) LexicalPreservingPrinter.print(unit) else source.source,
            admitted
        )
    }

    private fun verifyPlatformSurfaces(
        source: ArtifactClasses,
        target: ArtifactClasses
    ) {
        require(
            source.inheritsFrom(ITEM_STACK, OLD_ITEM_STACK_EXTENSION) == HeritageProof.YES &&
                source.hasPublicConcreteInstanceMethod(
                    OLD_ITEM_STACK_EXTENSION,
                    METHOD,
                    STACK_DESCRIPTOR
                )
        ) {
            "Sealed Forge source platform lacks inherited " +
                "IForgeItemStack#$METHOD$STACK_DESCRIPTOR"
        }
        require(
            source.inheritsFrom(ITEM, OLD_ITEM_EXTENSION) == HeritageProof.YES &&
                source.hasPublicConcreteInstanceMethod(
                    OLD_ITEM_EXTENSION,
                    METHOD,
                    ITEM_DESCRIPTOR
                )
        ) {
            "Sealed Forge source platform lacks inherited " +
                "IForgeItem#$METHOD$ITEM_DESCRIPTOR"
        }
        for ((owner, artifact) in source.declarers(METHOD, ITEM_DESCRIPTOR)) {
            if (owner == OLD_ITEM_EXTENSION) continue
            when (source.inheritsFrom(owner, ITEM)) {
                HeritageProof.YES -> error(
                    "Sealed Forge source platform ${artifact.component} declares native " +
                        "legacy Item override $owner#$METHOD$ITEM_DESCRIPTOR; no files changed"
                )
                HeritageProof.UNKNOWN -> error(
                    "Cannot classify sealed Forge source platform declaration " +
                        "${artifact.component}:$owner#$METHOD$ITEM_DESCRIPTOR; " +
                        "no files changed"
                )
                HeritageProof.NO -> Unit
            }
        }
        require(
            target.inheritsFrom(ITEM_STACK, TARGET_ITEM_STACK_EXTENSION) == HeritageProof.YES &&
                target.inheritsFrom(ITEM, TARGET_ITEM_EXTENSION) == HeritageProof.YES
        ) {
            "Sealed NeoForge target platform lacks Item/ItemStack extension ancestry"
        }
        require(!target.hasMethod(ITEM_STACK, METHOD, STACK_DESCRIPTOR) &&
            !target.hasMethod(ITEM, METHOD, ITEM_DESCRIPTOR) &&
            !target.hasMethod(TARGET_ITEM_STACK_EXTENSION, METHOD, STACK_DESCRIPTOR) &&
            !target.hasMethod(TARGET_ITEM_EXTENSION, METHOD, ITEM_DESCRIPTOR) &&
            TargetMembers.methodDescriptors(ITEM_STACK, METHOD).isEmpty() &&
            TargetMembers.methodDescriptors(ITEM, METHOD).isEmpty() &&
            TargetMembers.methodDescriptors(TARGET_ITEM_STACK_EXTENSION, METHOD).isEmpty() &&
            TargetMembers.methodDescriptors(TARGET_ITEM_EXTENSION, METHOD).isEmpty()
        ) {
            "Target restored a native $METHOD contract; compatibility dispatch must be " +
                "reconsidered"
        }
    }

    private fun verifyDependencyOverrides(
        inventory: LegacyMigrationArtifactPair
    ): ArtifactClasses {
        val complete = ArtifactClasses.readOrderedSource(inventory.source)
        for (owner in listOf(
            ITEM,
            ITEM_STACK,
            OLD_ITEM_EXTENSION,
            OLD_ITEM_STACK_EXTENSION
        )) {
            require(
                complete.selectedFrom(
                    owner,
                    inventory.source.platformArtifacts.named
                )
            ) {
                "Sealed source compile classpath does not select expected Forge platform " +
                    "class $owner from " +
                    inventory.source.platformArtifacts.named.component +
                    "; no files changed"
            }
        }
        for (declaration in complete.declarers(
            METHOD,
            ITEM_DESCRIPTOR
        )) {
            val owner = declaration.owner
            val artifact = declaration.artifact
            if (artifact.componentKind !in setOf(
                    LegacyMigrationArtifactInventory.ComponentKind.MODULE,
                    LegacyMigrationArtifactInventory.ComponentKind.PROJECT
                )
            ) continue
            val shadowEvidence = declaration.shadowEvidence()
            when (complete.inheritsFrom(owner, ITEM)) {
                HeritageProof.YES -> error(
                    "Dependency ${artifact.component} declares unconverted legacy " +
                        "$owner#$METHOD$ITEM_DESCRIPTOR$shadowEvidence; no files changed"
                )
                HeritageProof.UNKNOWN -> error(
                    "Cannot classify dependency declaration " +
                        "${artifact.component}:$owner#$METHOD$ITEM_DESCRIPTOR" +
                        "$shadowEvidence; " +
                        "no files changed"
                )
                HeritageProof.NO -> Unit
            }
        }
        return complete
    }

    private fun generatedSources(): Map<String, String> = linkedMapOf(
        "LegacyBlockStartBreakHooks" to """
            package $COMPAT_PACKAGE;

            public final class LegacyBlockStartBreakHooks {
                private LegacyBlockStartBreakHooks() {}

                public static boolean onBlockStartBreak(
                    net.minecraft.world.item.ItemStack stack,
                    net.minecraft.core.BlockPos pos,
                    net.minecraft.world.entity.player.Player player
                ) {
                    return onBlockStartBreak(stack.getItem(), stack, pos, player);
                }

                public static boolean onBlockStartBreak(
                    net.minecraft.world.item.Item item,
                    net.minecraft.world.item.ItemStack stack,
                    net.minecraft.core.BlockPos pos,
                    net.minecraft.world.entity.player.Player player
                ) {
                    java.util.Objects.requireNonNull(item);
                    return false;
                }
            }
        """.trimIndent() + "\n"
    )

    private fun isSourceSubtype(
        facts: ProjectValueFacts,
        sourcePlatform: ArtifactClasses,
        type: String?,
        ancestor: String
    ): Boolean =
        sourceHeritage(facts, sourcePlatform, type, ancestor) ==
            HeritageProof.YES

    private fun blockStartBreakExpressionType(
        facts: ProjectValueFacts,
        sourcePlatform: ArtifactClasses,
        typing: SourceValueTypes,
        expression: Expression,
        offset: (Node) -> Int
    ): String? {
        typing.typeOf(expression.toString(), offset(expression))?.let {
            return it
        }
        val call = expression as? MethodCallExpr ?: return null
        val receiver = call.scope.orElse(null) ?: return null
        val receiverType =
            typing.typeOf(receiver.toString(), offset(receiver)) ?: return null
        if (sourceHeritage(
                facts,
                sourcePlatform,
                receiverType,
                LIVING_ENTITY
            ) != HeritageProof.YES
        ) return null
        return when {
            call.nameAsString == "getMainHandItem" &&
                call.arguments.isEmpty() &&
                TargetMembers.inheritedMethod(
                    LIVING_ENTITY,
                    "getMainHandItem",
                    "()Lnet/minecraft/world/item/ItemStack;"
                ) != null -> ITEM_STACK
            call.nameAsString == "getItemInHand" &&
                call.arguments.size == 1 &&
                TargetMembers.inheritedMethod(
                    LIVING_ENTITY,
                    "getItemInHand",
                    "(Lnet/minecraft/world/InteractionHand;)" +
                        "Lnet/minecraft/world/item/ItemStack;"
                ) != null -> ITEM_STACK
            else -> null
        }
    }

    private fun sourceHeritage(
        facts: ProjectValueFacts,
        sourcePlatform: ArtifactClasses,
        type: String?,
        ancestor: String
    ): HeritageProof {
        var current = type ?: return HeritageProof.UNKNOWN
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            if (current == ancestor) return HeritageProof.YES
            if (current in facts.types) {
                current = facts.parents[current] ?: return HeritageProof.UNKNOWN
                continue
            }
            return sourcePlatform.inheritsFrom(current, ancestor)
        }
        return HeritageProof.UNKNOWN
    }

    private data class ClassMethod(
        val access: Int,
        val name: String,
        val descriptor: String
    )

    private data class ClassInfo(
        val name: String,
        val superName: String?,
        val interfaces: List<String>,
        val methods: List<ClassMethod>
    )

    private data class ClassCandidate(
        val info: ClassInfo,
        val artifact: LegacyMigrationArtifactInventory.Artifact,
        val order: Int,
        val entryName: String
    ) {
        fun evidence(): String =
            "$order|${artifact.component}|${artifact.sha256}|$entryName"
    }

    private data class ClassSelection(
        val candidate: ClassCandidate,
        val shadowed: List<ClassCandidate>
    )

    private data class ClassDeclaration(
        val owner: String,
        val artifact: LegacyMigrationArtifactInventory.Artifact,
        val shadowed: List<ClassCandidate>
    ) {
        fun shadowEvidence(): String =
            if (shadowed.isEmpty()) {
                ""
            } else {
                "; shadowed definitions: " +
                    shadowed.joinToString { it.evidence() }
            }
    }

    private class ArtifactClasses private constructor(
        private val candidates: Map<String, List<ClassCandidate>>
    ) {
        fun classInfo(name: String): ClassInfo? = select(name)?.candidate?.info

        fun selectedFrom(
            owner: String,
            artifact: LegacyMigrationArtifactInventory.Artifact
        ): Boolean =
            select(owner)?.candidate?.artifact?.sameBytesAs(artifact) == true

        fun hasMethod(owner: String, name: String, descriptor: String): Boolean =
            classInfo(owner)?.methods?.any {
                it.name == name && it.descriptor == descriptor
            } == true

        fun hasPublicConcreteInstanceMethod(
            owner: String,
            name: String,
            descriptor: String
        ): Boolean =
            classInfo(owner)?.methods?.any {
                it.name == name &&
                    it.descriptor == descriptor &&
                    it.access and ACC_PUBLIC != 0 &&
                    it.access and ACC_STATIC == 0 &&
                    it.access and ACC_ABSTRACT == 0
            } == true

        fun declarers(
            name: String,
            descriptor: String
        ): List<ClassDeclaration> =
            candidates.keys.sorted().mapNotNull { owner ->
                val available = checkNotNull(candidates[owner])
                val firstOrder = available.minOf(ClassCandidate::order)
                val first = available.filter { it.order == firstOrder }
                val declarations = first.map { candidate ->
                    candidate.info.methods.any {
                        it.name == name && it.descriptor == descriptor
                    }
                }.distinct()
                require(declarations.size == 1) {
                    "Sealed source compile classpath has an ambiguous needed declaration " +
                        "$owner#$name$descriptor at order $firstOrder: " +
                        first.joinToString { it.evidence() }
                }
                if (!declarations.single()) return@mapNotNull null
                val selection = checkNotNull(select(owner))
                ClassDeclaration(
                    owner,
                    selection.candidate.artifact,
                    selection.shadowed
                )
            }

        fun inheritsFrom(owner: String, ancestor: String): HeritageProof {
            val pending = ArrayDeque(listOf(owner))
            val seen = mutableSetOf<String>()
            var unresolved = false
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current == ancestor) return HeritageProof.YES
                if (!seen.add(current)) continue
                if (current == "java.lang.Object") continue
                val info = classInfo(current)
                if (info == null) {
                    unresolved = true
                    continue
                }
                info.superName?.let(pending::add)
                pending.addAll(info.interfaces)
            }
            return if (unresolved) HeritageProof.UNKNOWN else HeritageProof.NO
        }

        private fun select(owner: String): ClassSelection? {
            val available = candidates[owner] ?: return null
            val firstOrder = available.minOf(ClassCandidate::order)
            val first = available.filter { it.order == firstOrder }
            require(first.map(ClassCandidate::info).distinct().size == 1) {
                "Sealed source compile classpath has an ambiguous needed class $owner at " +
                    "order $firstOrder: " +
                    first.joinToString { it.evidence() }
            }
            val winner = first.first()
            return ClassSelection(
                winner,
                available.filterNot { it === winner }
                    .sortedWith(
                        compareBy<ClassCandidate>(
                            ClassCandidate::order,
                            { it.artifact.component },
                            ClassCandidate::entryName
                        )
                    )
            )
        }

        companion object {
            private const val ACC_PUBLIC = 0x0001
            private const val ACC_STATIC = 0x0008
            private const val ACC_ABSTRACT = 0x0400

            fun readSingle(
                artifact: LegacyMigrationArtifactInventory.Artifact,
                classRuntimeFeature: Int
            ): ArtifactClasses =
                readArtifacts(listOf(artifact to 0), classRuntimeFeature)

            fun readOrderedSource(
                source: LegacyMigrationArtifactInventory.Source
            ): ArtifactClasses {
                val configuration = source.compileClasspathConfiguration
                val active = source.closureArtifacts.filter {
                    configuration in it.resolutionConfigurations
                }.sortedBy {
                    it.configurationOrder.getValue(configuration)
                }.map {
                    it to it.configurationOrder.getValue(configuration)
                }
                return readArtifacts(active, source.classRuntimeFeature)
            }

            private fun readArtifacts(
                artifacts: List<Pair<LegacyMigrationArtifactInventory.Artifact, Int>>,
                classRuntimeFeature: Int
            ): ArtifactClasses =
                ArtifactClasses(
                    artifacts.flatMap { (artifact, order) ->
                        readArtifact(artifact, order, classRuntimeFeature)
                    }.groupBy { it.info.name }
                )

            private fun readArtifact(
                artifact: LegacyMigrationArtifactInventory.Artifact,
                order: Int,
                classRuntimeFeature: Int
            ): List<ClassCandidate> {
                artifact.verifyCurrentBytes("legacy block-start-break class scan")
                JarFile(artifact.file.toFile(), false).use { jar ->
                    val multiRelease = jar.manifest?.mainAttributes
                        ?.getValue("Multi-Release")
                        ?.equals("true", ignoreCase = true) == true
                    val selected = linkedMapOf<String, Pair<Int, String>>()
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory ||
                            !entry.name.endsWith(".class") ||
                            entry.name.endsWith("module-info.class")
                        ) continue
                        var logicalName = entry.name
                        var release = 0
                        if (entry.name.startsWith("META-INF/versions/")) {
                            val parts = entry.name.split('/', limit = 4)
                            val candidate = parts.getOrNull(2)?.toIntOrNull()
                            if (!multiRelease ||
                                parts.size != 4 ||
                                candidate == null ||
                                candidate !in 9..classRuntimeFeature
                            ) continue
                            release = candidate
                            logicalName = parts[3]
                        }
                        val previous = selected[logicalName]
                        if (previous == null || release > previous.first) {
                            selected[logicalName] = release to entry.name
                        }
                    }
                    return selected.toSortedMap().map { (_, selectedEntry) ->
                        val entryName = selectedEntry.second
                        val info = jar.getInputStream(
                            checkNotNull(jar.getJarEntry(entryName))
                        ).use {
                            LegacyClassReader(it.readBytes()).read()
                        }
                        ClassCandidate(info, artifact, order, entryName)
                    }
                }
            }
        }
    }

    private class LegacyClassReader(bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))
        private lateinit var pool: Array<Constant?>

        fun read(): ClassInfo {
            check(input.readInt() == 0xCAFEBABE.toInt()) {
                "Invalid classfile magic in legacy block-start-break inventory"
            }
            input.readUnsignedShort()
            input.readUnsignedShort()
            readConstantPool()
            input.readUnsignedShort()
            val own = className(input.readUnsignedShort())
            val superIndex = input.readUnsignedShort()
            val parent = if (superIndex == 0) null else className(superIndex)
            val interfaces = List(input.readUnsignedShort()) {
                className(input.readUnsignedShort())
            }
            skipMembers()
            val methods = buildList {
                repeat(input.readUnsignedShort()) {
                    val access = input.readUnsignedShort()
                    val name = utf8(input.readUnsignedShort())
                    val descriptor = utf8(input.readUnsignedShort())
                    skipAttributes()
                    if (name == METHOD) {
                        add(ClassMethod(access, name, descriptor))
                    }
                }
            }
            skipAttributes()
            return ClassInfo(own, parent, interfaces, methods)
        }

        private fun readConstantPool() {
            val count = input.readUnsignedShort()
            pool = arrayOfNulls(count)
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> pool[index] = Constant.Utf8(input.readUTF())
                    3, 4 -> {
                        input.readInt()
                        pool[index] = Constant.Other
                    }
                    5, 6 -> {
                        input.readLong()
                        pool[index] = Constant.Other
                        index++
                    }
                    7 -> pool[index] = Constant.Class(input.readUnsignedShort())
                    8, 16, 19, 20 -> {
                        input.readUnsignedShort()
                        pool[index] = Constant.Other
                    }
                    9, 10, 11, 12, 17, 18 -> {
                        input.readUnsignedShort()
                        input.readUnsignedShort()
                        pool[index] = Constant.Other
                    }
                    15 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                        pool[index] = Constant.Other
                    }
                    else -> error(
                        "Unsupported classfile constant-pool tag $tag in legacy " +
                            "block-start-break inventory"
                    )
                }
                index++
            }
        }

        private fun skipMembers() {
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                input.readUnsignedShort()
                input.readUnsignedShort()
                skipAttributes()
            }
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
                check(skipped > 0) {
                    "Truncated classfile attribute in legacy block-start-break inventory"
                }
                remaining -= skipped
            }
        }

        private fun className(index: Int): String {
            val value = pool[index] as? Constant.Class
                ?: error("Invalid class reference at constant $index")
            return utf8(value.nameIndex).replace('/', '.')
        }

        private fun utf8(index: Int): String =
            (pool[index] as? Constant.Utf8)?.value
                ?: error("Invalid UTF-8 reference at constant $index")

        private sealed interface Constant {
            data class Utf8(val value: String) : Constant
            data class Class(val nameIndex: Int) : Constant
            data object Other : Constant
        }
    }
}

/**
 * NeoForge 1.21.1 stores eye height in EntityDimensions. Preserve the current event dimensions
 * while replacing only their eye-height component.
 */
internal fun StructuralRefactorPass.migrateLegacyEntitySizeEyeHeight(
    source: String
): String {
    if (!source.contains("setNewEyeHeight")) return source
    if (!targetEntitySizeEyeHeightContract()) return source
    val parser = legacyInteractionParser()
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val offset = legacySourceOffset(source)
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
        if (call.nameAsString != "setNewEyeHeight" ||
            call.arguments.size != 1 ||
            call.scope.orElse(null) !is NameExpr
        ) continue
        val receiver = call.scope.get()
        if (typing.typeOf(receiver.toString(), offset(call)) != ENTITY_SIZE_EVENT) {
            continue
        }
        val replacement = parser.parseExpression<Expression>(
            "$receiver.setNewSize(" +
                "$receiver.getNewSize().withEyeHeight(${call.arguments[0]}))"
        ).result.orElse(null) ?: continue
        call.replace(replacement)
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

private fun targetEntitySizeEyeHeightContract(): Boolean =
    TargetMembers.inheritedMethod(
        ENTITY_SIZE_EVENT_BINARY,
        "getNewSize",
        "()Lnet/minecraft/world/entity/EntityDimensions;"
    )?.owner == ENTITY_SIZE_EVENT_BINARY &&
        TargetMembers.inheritedMethod(
            ENTITY_SIZE_EVENT_BINARY,
            "setNewSize",
            "(Lnet/minecraft/world/entity/EntityDimensions;)V"
        )?.owner == ENTITY_SIZE_EVENT_BINARY &&
        TargetMembers.inheritedMethod(
            ENTITY_DIMENSIONS,
            "withEyeHeight",
            "(F)Lnet/minecraft/world/entity/EntityDimensions;"
        )?.owner == ENTITY_DIMENSIONS

private fun legacyInteractionParser(): JavaParser =
    JavaParser(
        ParserConfiguration().setLanguageLevel(
            ParserConfiguration.LanguageLevel.BLEEDING_EDGE
        )
    )

private fun legacySourceOffset(source: String): (Node) -> Int {
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

private fun canonicalSourceType(
    unit: CompilationUnit,
    declaration: TypeDeclaration<*>
): String? {
    val names = mutableListOf<String>()
    var current: Node = declaration
    while (true) {
        val type = current as? TypeDeclaration<*> ?: return null
        names += type.nameAsString
        val parent = type.parentNode.orElse(null) ?: return null
        if (parent === unit) break
        if (parent !is TypeDeclaration<*>) return null
        current = parent
    }
    val nested = names.asReversed().joinToString(".")
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return if (packageName.isBlank()) nested else "$packageName.$nested"
}

private const val ENTITY_SIZE_EVENT =
    "net.neoforged.neoforge.event.entity.EntityEvent.Size"
private const val ENTITY_SIZE_EVENT_BINARY =
    "net.neoforged.neoforge.event.entity.EntityEvent\$Size"
private const val ENTITY_DIMENSIONS =
    "net.minecraft.world.entity.EntityDimensions"
