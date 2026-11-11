package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.TryStmt
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Preserves Create's live ZapperItem BlockData alias while moving its root to CUSTOM_DATA.
 * The snapshot is taken after shouldSwap and validation because shouldSwap can publish a sibling.
 * A finally write-back preserves child mutations on false and exceptional activate returns.
 */
internal object CreateZapperDataMigration {
    const val RULE_ID = "struct-create-zapper-data"

    private const val ZAPPER = "com.simibubi.create.content.equipment.zapper.ZapperItem"
    private const val WORLD_SHAPER =
        "com.simibubi.create.content.equipment.zapper.terrainzapper.WorldshaperItem"
    private const val TERRAIN_TOOLS =
        "com.simibubi.create.content.equipment.zapper.terrainzapper.TerrainTools"
    private const val PLACEMENT_PATTERNS =
        "com.simibubi.create.content.equipment.zapper.PlacementPatterns"
    private const val SHOOTABLE =
        "com.simibubi.create.content.equipment.zapper.ShootableGadgetItemMethods"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val DATA_COMPONENTS = "net.minecraft.core.component.DataComponents"
    private const val CUSTOM_DATA = "net.minecraft.world.item.component.CustomData"
    private const val NBT_HELPER = "net.createmod.catnip.nbt.NBTHelper"
    private const val NBT_PROCESSORS = "net.createmod.catnip.nbt.NBTProcessors"

    private const val SNAPSHOT =
        "item.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
            "net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
    private const val WRITE_BACK =
        "item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
            "net.minecraft.world.item.component.CustomData.of(nbt))"
    private const val MATERIALIZE_EMPTY =
        "item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
            "net.minecraft.world.item.component.CustomData.of(new net.minecraft.nbt.CompoundTag()))"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object { val EMPTY = Result(emptyList(), emptyList()) }
    }

    fun migrate(projectDir: Path, dryRun: Boolean): Result = try {
        migrateChecked(projectDir.toAbsolutePath().normalize(), dryRun)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create Zapper data admission failed"))
    } catch (failure: Exception) {
        Result(emptyList(), listOf(
            "Create Zapper data migration failed before commit: " +
                (failure.message ?: failure::class.java.simpleName)
        ))
    }

    private fun migrateChecked(projectDir: Path, dryRun: Boolean): Result {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return Result.EMPTY
        val expected = root.resolve("com/simibubi/create/content/equipment/zapper/ZapperItem.java")
        if (!Files.isRegularFile(expected)) return Result.EMPTY

        val files = parseProject(projectDir)
        val types = files.flatMap(::declaredTypes)
        val zapper = uniqueType(types, ZAPPER)
        val use = uniqueMethod(zapper, "use", listOf("Level", "Player", "InteractionHand"))
        if (isMigratedUse(use)) return Result.EMPTY

        val plan = legacyUsePlan(use, zapper.file)
        verifyTargetContracts()
        verifyClosedSubclassFamily(types)
        verifyPreSnapshotCalls(types)
        verifyWorldshaper(types)
        verifyPlacementPatterns(types)
        verifyTerrainTools(types)
        verifySetBlockEntityData(zapper)
        verifyPostSnapshotItemUses(use, plan)

        val migrated = applyEdits(zapper.file.source, listOf(
            TextEdit(plan.declarationStart, plan.declarationEnd,
                "if (!item.has($DATA_COMPONENTS.CUSTOM_DATA))\n" +
                    "${plan.indent}    $MATERIALIZE_EMPTY;"),
            TextEdit(
                plan.snapshotAnchor, plan.snapshotAnchor,
                "CompoundTag nbt = $SNAPSHOT;\n${plan.indent}"
            ),
            TextEdit(plan.activateStart, plan.activateEnd, "activated"),
            TextEdit(
                plan.activateIfStart, plan.activateIfStart,
                "boolean activated;\n${plan.indent}" +
                    "try {\n${plan.indent}    activated = ${plan.activateSource};\n" +
                    "${plan.indent}} finally {\n${plan.indent}    $WRITE_BACK;\n" +
                    "${plan.indent}}\n${plan.indent}"
            )
        ))
        val reparsed = parser().parse(migrated)
        if (!reparsed.isSuccessful || reparsed.result.isEmpty) {
            abort("Create Zapper migration emitted invalid Java: " +
                reparsed.problems.joinToString("; ") { it.verboseMessage })
        }
        val migratedUse = reparsed.result.orElseThrow()
            .findAll(ClassOrInterfaceDeclaration::class.java)
            .singleOrNull { canonicalName(it) == ZAPPER }
            ?.let { type -> methods(type).singleOrNull {
                it.nameAsString == "use" &&
                    parameterTypes(it) == listOf("Level", "Player", "InteractionHand")
            } }
        if (migratedUse == null || !isMigratedUse(migratedUse)) {
            abort("Create Zapper staged source did not retain the try/finally family")
        }

        val change = Change(
            file = zapper.file.path,
            line = plan.line,
            description = "Preserve Create Zapper BlockData mutations in CUSTOM_DATA",
            before = "live root tag and conditional activate visibility",
            after = "fresh post-preflight snapshot with activate finally write-back",
            confidence = Confidence.HIGH,
            ruleId = RULE_ID
        )
        if (!dryRun) Files.writeString(zapper.file.path, migrated, StandardCharsets.UTF_8)
        return Result(listOf(change), emptyList())
    }

    private fun legacyUsePlan(use: MethodDeclaration, file: ParsedSource): LegacyPlan {
        val body = use.body.orElse(null) ?: abort("$ZAPPER.use has no body")
        val item = body.findAll(VariableDeclarator::class.java).singleOrNull {
            it.nameAsString == "item" &&
                it.initializer.map(Node::toString).orElse("") == "player.getItemInHand(hand)"
        } ?: abort("$ZAPPER.use has no exact held-item binding")
        val itemAliases = body.findAll(VariableDeclarator::class.java).filter { candidate ->
            candidate !== item && candidate.typeAsString.substringAfterLast('.') == "ItemStack" &&
                candidate.initializer.map { initializer ->
                    initializer.findAll(NameExpr::class.java).any { it.nameAsString == item.nameAsString }
            }.orElse(false)
        }
        val itemStores = body.findAll(AssignExpr::class.java).filter { assignment ->
            assignment.value.findAll(NameExpr::class.java).any {
                it.nameAsString == item.nameAsString
            }
        }
        if (itemAliases.isNotEmpty() || itemStores.isNotEmpty()) {
            abort("$ZAPPER.use retains an unproved held-item alias across the snapshot")
        }
        val nbt = body.findAll(VariableDeclarator::class.java).singleOrNull {
            it.nameAsString == "nbt"
        } ?: abort("$ZAPPER.use has no unique root tag binding")
        val legacyCall = nbt.initializer.orElse(null) as? MethodCallExpr
            ?: abort("$ZAPPER.use root tag binding is not a call")
        if (legacyCall.nameAsString != "getOrCreateTag" || legacyCall.arguments.isNotEmpty() ||
            legacyCall.scope.map(Node::toString).orElse("") != "item") {
            abort("$ZAPPER.use root tag receiver changed")
        }
        val declaration = nbt.parentNode.orElse(null) as? VariableDeclarationExpr
            ?: abort("$ZAPPER.use root tag is not one local declaration")
        val declarationStatement = declaration.parentNode.orElse(null) as? ExpressionStmt
            ?: abort("$ZAPPER.use root tag declaration is not a statement")
        if (declaration.variables.size != 1 || nbt.typeAsString.substringAfterLast('.') != "CompoundTag") {
            abort("$ZAPPER.use root tag declaration changed shape")
        }

        val data = body.findAll(VariableDeclarator::class.java).singleOrNull {
            it.nameAsString == "data"
        } ?: abort("$ZAPPER.use has no unique BlockData binding")
        if (data.typeAsString.substringAfterLast('.') != "CompoundTag" ||
            !data.initializer.map { it.isNullLiteralExpr }.orElse(false)) {
            abort("$ZAPPER.use BlockData binding changed shape")
        }
        val dataAssignment = body.findAll(AssignExpr::class.java).singleOrNull {
            it.target.toString() == "data" &&
                it.value.toString() == "nbt.getCompound(\"BlockData\")"
        } ?: abort("$ZAPPER.use no longer retains the live BlockData child")
        val dataGuard = dataAssignment.findAncestor(IfStmt::class.java).orElse(null)
            ?: abort("$ZAPPER.use BlockData binding lost its guard")
        if (compact(dataGuard.condition.toString()) != compact(
                "AllBlockTags.SAFE_NBT.matches(stateToUse) && " +
                    "nbt.contains(\"BlockData\", Tag.TAG_COMPOUND)")) {
            abort("$ZAPPER.use BlockData guard changed")
        }

        val nbtUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == "nbt" }
        if (nbtUses.size != 4 || nbtUses.any { expr ->
                val call = expr.parentNode.orElse(null) as? MethodCallExpr
                call == null || call.scope.orElse(null) !== expr ||
                    call.nameAsString !in setOf("contains", "getCompound") }) {
            abort("$ZAPPER.use root snapshot has an altered alias or escape")
        }
        val activate = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "activate"
        } ?: abort("$ZAPPER.use has no unique activate call")
        if (activate.scope.isPresent || activate.arguments.map(Node::toString) != listOf(
                "world", "player", "item", "stateToUse", "raytrace", "data")) {
            abort("$ZAPPER.use activate receiver or arguments changed")
        }
        val activateIf = activate.findAncestor(IfStmt::class.java).orElse(null)
            ?: abort("$ZAPPER.use activate call is not conditionally consumed")
        if (activateIf.condition !== activate || activateIf.elseStmt.isPresent) {
            abort("$ZAPPER.use activate control flow changed")
        }
        val dataUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == "data" }
        if (dataUses.size != 2 || dataUses.any { expr ->
                val parent = expr.parentNode.orElse(null)
                !((parent is AssignExpr && parent === dataAssignment && parent.target === expr) ||
                    (parent is MethodCallExpr && parent === activate &&
                        parent.arguments.indexOfIdentity(expr) == 5)) }) {
            abort("$ZAPPER.use BlockData alias has an extra assignment or escape")
        }
        if (body.findAll(VariableDeclarator::class.java).any { it.nameAsString == "activated" }) {
            abort("$ZAPPER.use already owns the required activated local")
        }
        val anchor = body.findAll(IfStmt::class.java).singleOrNull {
            compact(it.condition.toString()) == "nbt.contains(\"BlockUsed\")"
        } ?: abort("$ZAPPER.use has no exact first root-tag read")
        val firstUse = nbtUses.minByOrNull(file::offset) ?: abort("$ZAPPER.use root tag is unused")
        if (!contains(anchor.condition, firstUse, file) || file.offset(anchor) >= file.offset(activateIf)) {
            abort("$ZAPPER.use root tag is used outside the admitted snapshot interval")
        }

        val statementStart = file.offset(declarationStatement)
        val removalStart = file.lineStart(statementStart)
        if (file.source.substring(removalStart, statementStart).any { !it.isWhitespace() }) {
            abort("$ZAPPER.use root declaration shares a source line")
        }
        val ifStart = file.offset(activateIf)
        val lineStart = file.lineStart(ifStart)
        val indent = file.source.substring(lineStart, ifStart)
        if (indent.any { it != ' ' && it != '\t' }) abort("Invalid activate indentation")
        return LegacyPlan(
            removalStart, file.afterLineEnd(file.endOffset(declarationStatement)),
            file.offset(anchor), anchor.range.map { it.begin.line }.orElse(1),
            file.offset(activate), file.endOffset(activate), ifStart,
            file.slice(activate), indent, activate.range.map { it.begin.line }.orElse(1),
            item, activateIf
        )
    }
    private fun verifyPreSnapshotCalls(types: List<TypeRef>) {
        val shootable = uniqueType(types, SHOOTABLE)
        val shouldSwap = uniqueMethod(
            shootable, "shouldSwap", listOf("Player", "ItemStack", "InteractionHand", "Predicate<ItemStack>")
        )
        verifyShouldSwapPresenceIndependent(shouldSwap)

        val zapper = uniqueType(types, ZAPPER)
        verifyZapperValidation(zapper)
        val worldshaper = uniqueType(types, WORLD_SHAPER)
        verifyWorldshaperValidation(worldshaper, types)
    }

    private fun verifyShouldSwapPresenceIndependent(method: MethodDeclaration) {
        val body = method.body.orElse(null) ?: abort("$SHOOTABLE.shouldSwap has no body")
        val itemName = method.parameters[1].nameAsString
        val itemUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == itemName }
        var detachedReads = 0
        itemUses.forEach { expr ->
            val call = expr.parentNode.orElse(null) as? MethodCallExpr
                ?: abort("$SHOOTABLE.shouldSwap aliases the candidate item")
            if (call.scope.orElse(null) !== expr) {
                abort("$SHOOTABLE.shouldSwap exposes the candidate item")
            }
            when (call.nameAsString) {
                "getOrDefault" -> {
                    if (call.arguments.map(Node::toString).map(::compact) != listOf(
                            "net.minecraft.core.component.DataComponents.CUSTOM_DATA",
                            "net.minecraft.world.item.component.CustomData.EMPTY")) {
                        abort("$SHOOTABLE.shouldSwap reads a different component")
                    }
                    val copy = call.parentNode.orElse(null) as? MethodCallExpr
                    if (copy == null || copy.scope.orElse(null) !== call ||
                        copy.nameAsString != "copyTag" || copy.arguments.isNotEmpty()) {
                        abort("$SHOOTABLE.shouldSwap observes CUSTOM_DATA without a detached copy")
                    }
                    detachedReads++
                }
                "set" -> {
                    if (call.arguments.size != 2 ||
                        compact(call.arguments[0].toString()) !=
                        "net.minecraft.core.component.DataComponents.CUSTOM_DATA") {
                        abort("$SHOOTABLE.shouldSwap writes a different component")
                    }
                    val value = call.arguments[1] as? MethodCallExpr
                        ?: abort("$SHOOTABLE.shouldSwap CUSTOM_DATA write changed value shape")
                    if (value.nameAsString != "of" || value.arguments.size != 1 ||
                        compact(value.scope.map(Node::toString).orElse("")) !=
                        "net.minecraft.world.item.component.CustomData" ||
                        call.parentNode.orElse(null) !is ExpressionStmt) {
                        abort("$SHOOTABLE.shouldSwap observes a component write result")
                    }
                }
                else -> abort(
                    "$SHOOTABLE.shouldSwap depends on pre-materialized CUSTOM_DATA presence via ${call.nameAsString}"
                )
            }
        }
        if (detachedReads == 0) {
            abort("$SHOOTABLE.shouldSwap no longer uses detached CUSTOM_DATA contents")
        }
        val swapRead = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "contains" &&
                it.arguments.singleOrNull()?.toString() == "\"_Swap\"" &&
                (it.scope.orElse(null) as? MethodCallExpr)?.nameAsString == "copyTag"
        } ?: abort("$SHOOTABLE.shouldSwap no longer uses one detached _Swap read")
        val copied = swapRead.scope.orElseThrow() as MethodCallExpr
        if ((copied.scope.orElse(null) as? MethodCallExpr)?.nameAsString != "getOrDefault") {
            abort("$SHOOTABLE.shouldSwap _Swap read no longer comes from CUSTOM_DATA")
        }
    }

    private fun verifyZapperValidation(zapper: TypeRef) {
        val method = uniqueMethod(zapper, "validateUsage", listOf("ItemStack"))
        val body = method.body.orElse(null) ?: abort("$ZAPPER.validateUsage has no body")
        val itemName = method.parameters.single().nameAsString
        val root = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "getOrCreateTag" && it.arguments.isEmpty() &&
                it.scope.map(Node::toString).orElse("") == itemName
        } ?: abort("$ZAPPER.validateUsage CUSTOM_DATA read changed shape")
        val local = root.parentNode.orElse(null) as? VariableDeclarator
            ?: abort("$ZAPPER.validateUsage tag read has an unknown alias")
        if (local.initializer.orElse(null) !== root ||
            local.typeAsString.substringAfterLast('.') != "CompoundTag") {
            abort("$ZAPPER.validateUsage tag read changed local shape")
        }
        val localUses = body.findAll(NameExpr::class.java).filter {
            it.nameAsString == local.nameAsString
        }
        if (localUses.size != 1 || localUses.any { expr ->
                val call = expr.parentNode.orElse(null) as? MethodCallExpr
                call == null || call.scope.orElse(null) !== expr || call.nameAsString != "contains" ||
                    call.arguments.singleOrNull()?.toString() != "\"BlockUsed\""
            }) {
            abort("$ZAPPER.validateUsage tag alias is not the exact BlockUsed read")
        }
        val capability = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "canActivateWithoutSelectedBlock" &&
                it.arguments.map(Node::toString) == listOf(itemName) &&
                it.scope.map(Node::toString).orElse("this") == "this"
        } ?: abort("$ZAPPER.validateUsage capability dispatch changed")
        val itemUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == itemName }
        if (itemUses.size != 2 || itemUses.any { expr ->
                val parent = expr.parentNode.orElse(null)
                !((parent === root && root.scope.orElse(null) === expr) ||
                    (parent === capability && capability.arguments.indexOfIdentity(expr) == 0))
            }) {
            abort("$ZAPPER.validateUsage exposes the pre-snapshot item")
        }
        val base = uniqueMethod(zapper, "canActivateWithoutSelectedBlock", listOf("ItemStack"))
        if (compact(base.body.orElse(null)?.toString().orEmpty()) != "{ return false; }") {
            abort("$ZAPPER.canActivateWithoutSelectedBlock base contract changed")
        }
    }

    private fun verifyWorldshaperValidation(worldshaper: TypeRef, types: List<TypeRef>) {
        val method = uniqueMethod(worldshaper, "validateUsage", listOf("ItemStack"))
        val body = method.body.orElse(null) ?: abort("$WORLD_SHAPER.validateUsage has no body")
        val itemName = method.parameters.single().nameAsString
        val root = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "getOrCreateTag" && it.arguments.isEmpty() &&
                it.scope.map(Node::toString).orElse("") == itemName
        } ?: abort("$WORLD_SHAPER.validateUsage CUSTOM_DATA read changed shape")
        val contains = root.parentNode.orElse(null) as? MethodCallExpr
        if (contains == null || contains.scope.orElse(null) !== root ||
            contains.nameAsString != "contains" ||
            contains.arguments.singleOrNull()?.toString() != "\"BrushParams\"") {
            abort("$WORLD_SHAPER.validateUsage is not the exact BrushParams read")
        }
        val parent = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "validateUsage" &&
                it.scope.map(Node::toString).orElse("") == "super" &&
                it.arguments.map(Node::toString) == listOf(itemName)
        } ?: abort("$WORLD_SHAPER.validateUsage parent dispatch changed")
        val itemUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == itemName }
        if (itemUses.size != 2 || itemUses.any { expr ->
                val owner = expr.parentNode.orElse(null)
                !((owner === root && root.scope.orElse(null) === expr) ||
                    (owner === parent && parent.arguments.indexOfIdentity(expr) == 0))
            }) {
            abort("$WORLD_SHAPER.validateUsage exposes the pre-snapshot item")
        }

        val capability = uniqueMethod(
            worldshaper, "canActivateWithoutSelectedBlock", listOf("ItemStack")
        )
        val expected = compact("""
            {
                CompoundTag tag = stack.getOrCreateTag();
                TerrainTools tool = NBTHelper.readEnum(tag, "Tool", TerrainTools.class);
                return !tool.requiresSelectedBlock();
            }
        """.trimIndent())
        if (compact(capability.body.orElse(null)?.toString().orEmpty()) != expected) {
            abort("$WORLD_SHAPER.canActivateWithoutSelectedBlock changed from its read-only contract")
        }
        val enumRead = capability.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "readEnum"
        } ?: abort("$WORLD_SHAPER capability has no unique enum read")
        if (resolveType(enumRead.scope.map(Node::toString).orElse(""), worldshaper.file, types) !=
            NBT_HELPER) {
            abort("$WORLD_SHAPER capability enum reader owner changed")
        }
        val terrainTools = uniqueType(types, TERRAIN_TOOLS)
        val requirement = uniqueMethod(terrainTools, "requiresSelectedBlock", emptyList())
        if (compact(requirement.body.orElse(null)?.toString().orEmpty()) !=
            "{ return this != Clear && this != Flatten; }") {
            abort("$TERRAIN_TOOLS.requiresSelectedBlock changed from its read-only contract")
        }
    }

    private fun verifyPostSnapshotItemUses(use: MethodDeclaration, plan: LegacyPlan) {
        val itemName = plan.item.nameAsString
        val itemUses = use.findAll(NameExpr::class.java).filter { expr ->
            expr.nameAsString == itemName && expr.range.isPresent &&
                expr.range.get().begin.line >= plan.snapshotLine &&
                expr.range.get().end.line <= plan.activateIf.range.get().begin.line
        }
        itemUses.forEach { expr ->
            val parent = expr.parentNode.orElse(null)
            if (parent is MethodCallExpr && parent.scope.orElse(null) === expr) {
                if (parent.nameAsString != "getItem") {
                    abort("$ZAPPER.use mutates or exposes item after the root snapshot")
                }
                return@forEach
            }
            if (parent is ObjectCreationExpr) {
                val returned = parent.findAncestor(ReturnStmt::class.java).orElse(null)
                val guarded = returned?.findAncestor(IfStmt::class.java)?.orElse(null)
                if (returned == null || guarded == null ||
                    fileOrder(returned, plan.activateIf) >= 0) {
                    abort("$ZAPPER.use aliases item after the root snapshot")
                }
                return@forEach
            }
            if (parent !is MethodCallExpr) {
                abort("$ZAPPER.use aliases item after the root snapshot")
            }
            val argument = parent.arguments.indexOfIdentity(expr)
            if (argument < 0) abort("$ZAPPER.use has an unknown post-snapshot item use")
            val admitted = when (parent.nameAsString) {
                "getZappingRange", "getCooldownDelay" -> argument == 0 && parent.scope.isEmpty
                "activate" -> argument == 2
                "applyCooldown" -> argument == 1 && earlyReturnBranch(parent)
                else -> false
            }
            if (!admitted) {
                abort("$ZAPPER.use exposes item after the root snapshot via ${parent.nameAsString}")
            }
        }
    }

    private fun fileOrder(left: Node, right: Node): Int {
        val leftRange = left.range.orElse(null) ?: return 0
        val rightRange = right.range.orElse(null) ?: return 0
        return compareValuesBy(leftRange.begin, rightRange.begin, { it.line }, { it.column })
    }
    private fun earlyReturnBranch(call: MethodCallExpr): Boolean {
        val enclosing = call.findAncestor(IfStmt::class.java).orElse(null) ?: return false
        val block = enclosing.thenStmt as? BlockStmt ?: return false
        return block.findAll(ReturnStmt::class.java).isNotEmpty()
    }

    private fun verifyClosedSubclassFamily(types: List<TypeRef>) {
        val direct = types.filter { type ->
            (type.declaration as? ClassOrInterfaceDeclaration)?.extendedTypes?.any {
                resolveType(it.nameWithScope, type.file, types) == ZAPPER
            } == true
        }
        if (direct.map { it.name } != listOf(WORLD_SHAPER)) {
            abort("$ZAPPER must have only the source-owned WorldshaperItem subclass")
        }
        val indirect = types.filter { type ->
            type.name != WORLD_SHAPER &&
                (type.declaration as? ClassOrInterfaceDeclaration)?.extendedTypes?.any {
                    resolveType(it.nameWithScope, type.file, types) == WORLD_SHAPER
                } == true
        }
        if (indirect.isNotEmpty()) abort("$ZAPPER has an unproved indirect concrete subclass")
        val concrete = direct.single().declaration as ClassOrInterfaceDeclaration
        if (concrete.isAbstract || concrete.isInterface) {
            abort("$WORLD_SHAPER is not the one concrete activate implementation")
        }
    }

    private fun verifyWorldshaper(types: List<TypeRef>) {
        val owner = uniqueType(types, WORLD_SHAPER)
        val activate = uniqueMethod(owner, "activate", listOf(
            "Level", "Player", "ItemStack", "BlockState", "BlockHitResult", "CompoundTag"
        ))
        val body = activate.body.orElse(null) ?: abort("$WORLD_SHAPER.activate has no body")
        val stackName = activate.parameters[2].nameAsString
        val dataName = activate.parameters[5].nameAsString
        val tag = body.findAll(VariableDeclarator::class.java).singleOrNull {
            it.nameAsString == "tag" &&
                it.initializer.map(Node::toString).orElse("") == "$stackName.getOrCreateTag()"
        } ?: abort("$WORLD_SHAPER.activate stack tag read changed")
        val pattern = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "applyPattern" &&
                resolveType(it.scope.map(Node::toString).orElse(""), owner.file, types) ==
                PLACEMENT_PATTERNS
        } ?: abort("$WORLD_SHAPER.activate has no unique placement-pattern read")
        if (pattern.arguments.map(Node::toString) != listOf("affectedPositions", stackName)) {
            abort("$WORLD_SHAPER.activate placement-pattern stack flow changed")
        }
        val stackUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == stackName }
        if (stackUses.size != 2 || stackUses.any { expr ->
                val parent = expr.parentNode.orElse(null)
                !((parent is MethodCallExpr && parent.nameAsString == "getOrCreateTag" &&
                    parent.scope.orElse(null) === expr) ||
                    (parent === pattern && pattern.arguments.indexOfIdentity(expr) == 1)) }) {
            abort("$WORLD_SHAPER.activate has an extra stack CUSTOM_DATA path")
        }
        val tagUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == tag.nameAsString }
        if (tagUses.size != 4 || tagUses.any { expr ->
                val parent = expr.parentNode.orElse(null) as? MethodCallExpr
                parent == null || !(
                    (parent.nameAsString == "readEnum" &&
                        resolveType(parent.scope.map(Node::toString).orElse(""), owner.file, types) ==
                        NBT_HELPER &&
                        parent.arguments.indexOfIdentity(expr) == 0) ||
                        (parent.nameAsString == "getCompound" && parent.scope.orElse(null) === expr &&
                            (parent.arguments.singleOrNull() as? StringLiteralExpr)?.asString() == "BrushParams")
                    ) }) {
            abort("$WORLD_SHAPER.activate tag read has an alias or mutation")
        }
        val run = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "run" &&
                (it.scope.orElse(null) as? MethodCallExpr)?.nameAsString == "redirectTool"
        } ?: abort("$WORLD_SHAPER.activate has no unique TerrainTools.run flow")
        if (run.arguments.map(Node::toString) != listOf(
                "world", "affectedPositions", "raytrace.getDirection()", "stateToUse", dataName, "player")) {
            abort("$WORLD_SHAPER.activate TerrainTools.run arguments changed")
        }
        val redirect = run.scope.orElseThrow() as MethodCallExpr
        if (redirect.scope.map(Node::toString).orElse("") != "brush" ||
            redirect.arguments.map(Node::toString) != listOf("tool")) {
            abort("$WORLD_SHAPER.activate redirectTool receiver changed")
        }
        val dataUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == dataName }
        if (dataUses.size != 1 || run.arguments.indexOfIdentity(dataUses.single()) != 4) {
            abort("$WORLD_SHAPER.activate BlockData has an extra alias or escape")
        }
        val returns = body.statements.filterIsInstance<ReturnStmt>()
        if (returns.size != 1 ||
            (returns.single().expression.orElse(null) as? BooleanLiteralExpr)?.value != true) {
            abort("$WORLD_SHAPER.activate completion contract changed")
        }

        verifyReadOnlyItemQuery(owner, "getZappingRange")
        verifyReadOnlyItemQuery(owner, "getCooldownDelay")
    }

    private fun verifyReadOnlyItemQuery(owner: TypeRef, name: String) {
        val method = uniqueMethod(owner, name, listOf("ItemStack"))
        val body = method.body.orElse(null) ?: abort("${owner.name}.$name has no body")
        if (body.findAll(NameExpr::class.java).any {
                it.nameAsString == method.parameters.single().nameAsString
            } || body.statements.singleOrNull() !is ReturnStmt) {
            abort("${owner.name}.$name may mutate the post-snapshot item")
        }
    }

    private fun verifyPlacementPatterns(types: List<TypeRef>) {
        val owner = uniqueType(types, PLACEMENT_PATTERNS)
        val method = uniqueMethod(owner, "applyPattern", listOf("List<BlockPos>", "ItemStack"))
        val body = method.body.orElse(null) ?: abort("$PLACEMENT_PATTERNS.applyPattern has no body")
        val stackName = method.parameters[1].nameAsString
        val tag = body.findAll(VariableDeclarator::class.java).singleOrNull {
            it.nameAsString == "tag"
        } ?: abort("$PLACEMENT_PATTERNS.applyPattern has no exact tag snapshot")
        val copy = tag.initializer.orElse(null) as? MethodCallExpr
            ?: abort("$PLACEMENT_PATTERNS.applyPattern tag snapshot changed")
        val get = copy.scope.orElse(null) as? MethodCallExpr
        if (copy.nameAsString != "copyTag" || copy.arguments.isNotEmpty() ||
            get?.nameAsString != "getOrDefault" ||
            get.scope.map(Node::toString).orElse("") != stackName ||
            get.arguments.map(Node::toString).map(::compact) != listOf(
                "net.minecraft.core.component.DataComponents.CUSTOM_DATA",
                "net.minecraft.world.item.component.CustomData.EMPTY")) {
            abort("$PLACEMENT_PATTERNS.applyPattern is not a detached CUSTOM_DATA read")
        }
        val stackUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == stackName }
        if (stackUses.size != 1 || get.scope.orElse(null) !== stackUses.single()) {
            abort("$PLACEMENT_PATTERNS.applyPattern has an extra stack path")
        }
        val tagUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == tag.nameAsString }
        if (tagUses.size != 2 || tagUses.any { expr ->
                val call = expr.parentNode.orElse(null) as? MethodCallExpr
                call == null || call.scope.orElse(null) !== expr ||
                    call.nameAsString !in setOf("contains", "getString") }) {
            abort("$PLACEMENT_PATTERNS.applyPattern detached tag is mutated or escaped")
        }
    }

    private fun verifyTerrainTools(types: List<TypeRef>) {
        val owner = uniqueType(types, TERRAIN_TOOLS)
        val run = uniqueMethod(owner, "run", listOf(
            "Level", "List<BlockPos>", "Direction", "BlockState", "CompoundTag", "Player"
        ))
        val body = run.body.orElse(null) ?: abort("$TERRAIN_TOOLS.run has no body")
        val dataName = run.parameters[4].nameAsString
        val uses = body.findAll(NameExpr::class.java).filter { it.nameAsString == dataName }
        if (uses.size != 4 || uses.any { expr ->
                val call = expr.parentNode.orElse(null) as? MethodCallExpr
                call == null || call.nameAsString != "setBlockEntityData" ||
                    resolveType(call.scope.map(Node::toString).orElse(""), owner.file, types) !=
                    ZAPPER ||
                    call.arguments.indexOfIdentity(expr) != 3 ||
                    !call.arguments.map(Node::toString).let {
                        it.size == 5 && it[0] == "world" && it[2] == "paintedState" &&
                            it[4] == "player"
                    } }) {
            abort("$TERRAIN_TOOLS.run no longer passes only the original BlockData child")
        }
    }

    private fun verifySetBlockEntityData(zapper: TypeRef) {
        val method = uniqueMethod(zapper, "setBlockEntityData", listOf(
            "Level", "BlockPos", "BlockState", "CompoundTag", "Player"
        ))
        val body = method.body.orElse(null) ?: abort("$ZAPPER.setBlockEntityData has no body")
        val actual = compact(body.toString())
        val expected = compact("""
            {
                if (data != null && AllBlockTags.SAFE_NBT.matches(state)) {
                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity != null) {
                        data = NBTProcessors.process(state, blockEntity, data, !player.isCreative());
                        if (data == null)
                            return;
                        data.putInt("x", pos.getX());
                        data.putInt("y", pos.getY());
                        data.putInt("z", pos.getZ());
                        blockEntity.loadWithComponents(data, world.registryAccess());
                    }
                }
            }
        """.trimIndent())
        if (actual != expected) {
            abort("$ZAPPER.setBlockEntityData changed; distinct NBTProcessors results are unproved")
        }
        val processor = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "process"
        } ?: abort("$ZAPPER.setBlockEntityData has no unique NBT processor call")
        if (resolveType(processor.scope.map(Node::toString).orElse(""), zapper.file, emptyList()) !=
            NBT_PROCESSORS) {
            abort("$ZAPPER.setBlockEntityData processor owner changed")
        }
    }

    private fun isMigratedUse(use: MethodDeclaration): Boolean {
        val body = use.body.orElse(null) ?: return false
        if (body.findAll(IfStmt::class.java).none {
                compact(it.condition.toString()) == compact("!item.has($DATA_COMPONENTS.CUSTOM_DATA)") &&
                    compact(it.thenStmt.toString()) == compact("$MATERIALIZE_EMPTY;")
            }) return false
        val nbt = body.findAll(VariableDeclarator::class.java).singleOrNull {
            it.nameAsString == "nbt"
        } ?: return false
        if (compact(nbt.initializer.map(Node::toString).orElse("")) != compact(SNAPSHOT)) return false
        val activated = body.findAll(VariableDeclarator::class.java).singleOrNull {
            it.nameAsString == "activated" && it.typeAsString == "boolean" && it.initializer.isEmpty
        } ?: return false
        val tryStmt = body.findAll(TryStmt::class.java).singleOrNull() ?: return false
        if (tryStmt.catchClauses.isNotEmpty() || tryStmt.finallyBlock.isEmpty) return false
        val assignment = tryStmt.tryBlock.findAll(AssignExpr::class.java).singleOrNull()
            ?: return false
        if (assignment.target.toString() != activated.nameAsString ||
            (assignment.value as? MethodCallExpr)?.nameAsString != "activate") return false
        val finallyCalls = tryStmt.finallyBlock.orElseThrow().findAll(MethodCallExpr::class.java)
            .filter { it.nameAsString == "set" }
        if (finallyCalls.size != 1 ||
            compact(finallyCalls.single().toString()) != compact(WRITE_BACK)) return false
        return body.findAll(IfStmt::class.java).any {
            it.condition.toString() == activated.nameAsString
        }
    }

    private fun verifyTargetContracts() {
        fun public(owner: String, name: String, descriptor: String): Boolean =
            TargetMembers.inheritedMethod(owner, name, descriptor)?.access == "public"
        val valid = public(
            ITEM_STACK, "getOrDefault",
            "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;"
        ) && public(
            ITEM_STACK, "set",
            "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;"
        ) && public(
            ITEM_STACK, "has", "(Lnet/minecraft/core/component/DataComponentType;)Z"
        ) && public(
            "net.minecraft.nbt.CompoundTag", "<init>", "()V"
        ) && public(
            CUSTOM_DATA, "of",
            "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/item/component/CustomData;"
        ) && public(
            CUSTOM_DATA, "copyTag", "()Lnet/minecraft/nbt/CompoundTag;"
        ) && TargetMembers.fieldDescriptor(
            DATA_COMPONENTS, "CUSTOM_DATA"
        ) == "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor(
                CUSTOM_DATA, "EMPTY"
            ) == "Lnet/minecraft/world/item/component/CustomData;"
        if (!valid) abort("Create Zapper target CUSTOM_DATA ABI is not verified")
    }

    private fun parseProject(projectDir: Path): List<ParsedSource> {
        val root = projectDir.resolve("src/main/java")
        val parser = parser()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "java" }
                .sorted()
                .map { path ->
                    val bytes = Files.readAllBytes(path)
                    val source = String(bytes, StandardCharsets.UTF_8)
                    if (!source.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
                        abort("Create Zapper source is not canonical UTF-8: $path")
                    }
                    val result = parser.parse(source)
                    if (!result.isSuccessful || result.result.isEmpty) {
                        abort("Create Zapper source proof could not parse $path")
                    }
                    ParsedSource(path, source, result.result.orElseThrow())
                }.toList()
        }
    }

    private fun parser(): JavaParser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private fun declaredTypes(file: ParsedSource): List<TypeRef> =
        file.unit.findAll(TypeDeclaration::class.java).mapNotNull { declaration ->
            canonicalName(declaration)?.let { TypeRef(file, it, declaration) }
        }

    private fun canonicalName(declaration: TypeDeclaration<*>): String? {
        val names = mutableListOf<String>()
        var current: Node = declaration
        while (current is TypeDeclaration<*>) {
            names += current.nameAsString
            current = current.parentNode.orElse(null) ?: return null
        }
        if (current !is CompilationUnit) return null
        val packageName = current.packageDeclaration.map { it.nameAsString }.orElse("")
        val nested = names.asReversed().joinToString(".")
        return if (packageName.isBlank()) nested else "$packageName.$nested"
    }

    private fun uniqueType(types: List<TypeRef>, name: String): TypeRef =
        types.singleOrNull { it.name == name }
            ?: abort("Create Zapper proof requires one exact $name source type")

    private fun uniqueMethod(
        owner: TypeRef,
        name: String,
        parameters: List<String>
    ): MethodDeclaration = methods(owner.declaration).singleOrNull {
        it.nameAsString == name && parameterTypes(it) == parameters
    } ?: abort("${owner.name} has no unique $name(${parameters.joinToString()})")

    private fun methods(type: TypeDeclaration<*>): List<MethodDeclaration> =
        type.members.filterIsInstance<MethodDeclaration>()

    private fun parameterTypes(method: MethodDeclaration): List<String> =
        method.parameters.map { compactType(it.typeAsString) }

    private fun compactType(type: String): String = type.replace(" ", "").substringAfterLast('.')

    private fun resolveType(raw: String, file: ParsedSource, types: List<TypeRef>): String? {
        val plain = raw.substringBefore('<').replace(" ", "")
        if (plain.firstOrNull()?.isLowerCase() == true && '.' in plain) return plain
        val simple = plain.substringAfterLast('.')
        val imported = file.unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.name.identifier == simple
        }.map { it.nameAsString }.distinct()
        if (imported.size == 1) return imported.single()
        val packageName = file.unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val samePackage = types.map { it.name }.filter {
            it.substringAfterLast('.') == simple && it.substringBeforeLast('.', "") == packageName
        }
        if (samePackage.size == 1) return samePackage.single()
        return types.map { it.name }.filter {
            it.substringAfterLast('.') == simple
        }.distinct().singleOrNull()
    }
    private fun contains(scope: Node, child: Node, file: ParsedSource): Boolean =
        file.offset(scope) <= file.offset(child) && file.endOffset(scope) >= file.endOffset(child)

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()

    private fun applyEdits(source: String, edits: List<TextEdit>): String {
        val ordered = edits.sortedWith(
            compareByDescending<TextEdit> { it.start }.thenByDescending { it.end }
        )
        ordered.zipWithNext().forEach { (left, right) ->
            if (right.end > left.start) abort("Create Zapper staged text edits overlap")
        }
        var result = source
        ordered.forEach { edit ->
            if (edit.start !in 0..result.length || edit.end !in edit.start..result.length) {
                abort("Create Zapper staged text edit is outside the source")
            }
            result = result.replaceRange(edit.start, edit.end, edit.replacement)
        }
        return result
    }

    private fun <T : Node> List<T>.indexOfIdentity(target: T): Int =
        indices.firstOrNull { this[it] === target } ?: -1

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)
    private class AdmissionAbort(message: String) : RuntimeException(message)

    private data class ParsedSource(
        val path: Path,
        val source: String,
        val unit: CompilationUnit
    ) {
        private val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        fun offset(node: Node): Int = node.range.map {
            lineStarts[it.begin.line - 1] + it.begin.column - 1
        }.orElse(-1)
        fun endOffset(node: Node): Int = node.range.map {
            lineStarts[it.end.line - 1] + it.end.column
        }.orElse(-1)
        fun slice(node: Node): String = source.substring(offset(node), endOffset(node))
        fun lineStart(offset: Int): Int = source.lastIndexOf('\n', offset - 1) + 1
        fun afterLineEnd(offset: Int): Int {
            val newline = source.indexOf('\n', offset)
            return if (newline < 0) source.length else newline + 1
        }
    }

    private data class TypeRef(
        val file: ParsedSource,
        val name: String,
        val declaration: TypeDeclaration<*>
    )

    private data class LegacyPlan(
        val declarationStart: Int,
        val declarationEnd: Int,
        val snapshotAnchor: Int,
        val snapshotLine: Int,
        val activateStart: Int,
        val activateEnd: Int,
        val activateIfStart: Int,
        val activateSource: String,
        val indent: String,
        val line: Int,
        val item: VariableDeclarator,
        val activateIf: IfStmt
    )

    private data class TextEdit(val start: Int, val end: Int, val replacement: String)
}
