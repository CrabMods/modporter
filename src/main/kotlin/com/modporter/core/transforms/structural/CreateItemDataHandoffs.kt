package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Closes two Create item-data handoffs that cannot safely inherit the generic ItemStack tag rewrite.
 *
 * Forge 1.20.1 encoded ClipboardEditPacket synchronously inside sendToServer. The target payload
 * path retains the packet and may call its StreamCodec later, while ClipboardEditPacket's source
 * constructor retains the supplied CompoundTag directly. ClipboardScreen must therefore hand the
 * packet a complete, deep CUSTOM_DATA snapshot at the send boundary. This is a boundary-specific
 * ownership transfer; it does not classify the packet constructor, encoder, or opaque consumers as
 * globally read-only.
 *
 * MinecartContraptionItem passes the nested Contraption compound into Contraption.fromNBT, whose
 * object graph may retain nested tags. The helper therefore reads from a detached complete
 * CUSTOM_DATA root. This preserves the two proven source-owned consuming paths: both calls are
 * immediately followed, in their enclosing execution block, by shrink(1) of the same stack. It
 * deliberately makes no physical-alias promise for arbitrary external callers of the public helper.
 * Any additional call, overload, method reference, or non-consuming source call rejects the whole
 * project transaction.
 */
internal object CreateItemDataHandoffs {
    const val RULE_ID = "struct-create-item-data-handoffs"

    private const val CLIPBOARD_SCREEN =
        "com.simibubi.create.content.equipment.clipboard.ClipboardScreen"
    private const val CLIPBOARD_PACKET =
        "com.simibubi.create.content.equipment.clipboard.ClipboardEditPacket"
    private const val MINECART_ITEM =
        "com.simibubi.create.content.contraptions.mounted.MinecartContraptionItem"
    private const val SIMPLE_PACKET_BASE =
        "com.simibubi.create.foundation.networking.SimplePacketBase"
    private const val CONTRAPTION =
        "com.simibubi.create.content.contraptions.Contraption"
    private const val ORIENTED_CONTRAPTION =
        "com.simibubi.create.content.contraptions.OrientedContraptionEntity"

    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val BLOCK_POS = "net.minecraft.core.BlockPos"
    private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    private const val REGISTRY_BUFFER = "net.minecraft.network.RegistryFriendlyByteBuf"
    private const val STREAM_CODEC = "net.minecraft.network.codec.StreamCodec"
    private const val PACKET_DISTRIBUTOR =
        "net.neoforged.neoforge.network.PacketDistributor"
    private const val LEVEL = "net.minecraft.world.level.Level"
    private const val ABSTRACT_MINECART =
        "net.minecraft.world.entity.vehicle.AbstractMinecart"
    private const val DIRECTION = "net.minecraft.core.Direction"

    private val CLIPBOARD_EXTERNAL_TYPES = setOf(
        ITEM_STACK, BLOCK_POS, COMPOUND_TAG, REGISTRY_BUFFER, STREAM_CODEC, PACKET_DISTRIBUTOR
    )
    private val MINECART_EXTERNAL_TYPES = setOf(
        LEVEL, ITEM_STACK, ABSTRACT_MINECART, DIRECTION, COMPOUND_TAG
    )

    private const val CUSTOM_DATA_SNAPSHOT =
        "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
            "net.minecraft.world.item.component.CustomData.EMPTY"

    private const val ITEM_SNAPSHOT =
        "item.getOrDefault($CUSTOM_DATA_SNAPSHOT).copyTag()"
    private const val ITEMSTACK_SNAPSHOT =
        "itemstack.getOrDefault($CUSTOM_DATA_SNAPSHOT).copyTag()"

    /**
     * Returns a staged project map only after every active family and every generated source parses
     * and re-admits. Every refusal returns [sources] itself, so filesystem adapters must never fall
     * back to per-file writes.
     */
    fun migrateProject(sources: Map<String, String>): Map<String, String> {
        val activity = activity(sources)
        if (!activity.any) return sources
        if (!targetContractsAvailable()) return sources

        return try {
            val parsed = parseProject(sources)
            val plans = mutableListOf<SourceEdit>()
            if (activity.clipboard) {
                admitClipboard(parsed, requireMigrated = false)?.let(plans::add)
            }
            if (activity.minecart) {
                admitMinecart(parsed, requireMigrated = false)?.let(plans::add)
            }

            val staged = LinkedHashMap(sources)
            plans.forEach { plan ->
                val replacement = when (plan.kind) {
                    HandoffKind.CLIPBOARD -> ITEM_SNAPSHOT
                    HandoffKind.MINECART -> ITEMSTACK_SNAPSHOT
                }
                staged[plan.path] = replaceNode(plan.source, plan.node, replacement)
            }

            if (staged == sources) {
                sources
            } else {
                val reparsed = parseProject(staged)
                if (activity.clipboard) admitClipboard(reparsed, requireMigrated = true)
                if (activity.minecart) admitMinecart(reparsed, requireMigrated = true)
                staged
            }
        } catch (_: AdmissionAbort) {
            sources
        } catch (_: Exception) {
            sources
        }
    }

    /** True when a relevant source surface is legacy, incomplete, or fails its closed-family proof. */
    fun hasPendingProjectMigration(sources: Map<String, String>): Boolean {
        val activity = activity(sources)
        if (!activity.any) return false
        return try {
            val parsed = parseProject(sources)
            if (activity.clipboard) admitClipboard(parsed, requireMigrated = true)
            if (activity.minecart) admitMinecart(parsed, requireMigrated = true)
            false
        } catch (_: Exception) {
            true
        }
    }

    private fun admitClipboard(
        project: ParsedProject,
        requireMigrated: Boolean
    ): SourceEdit? {
        project.rejectSourceDeclarations(CLIPBOARD_EXTERNAL_TYPES)
        val screen = project.requireOwner(CLIPBOARD_SCREEN)
        val screenType = screen.type ?: abort("Missing type declaration for $CLIPBOARD_SCREEN")
        val packet = project.requireOwner(CLIPBOARD_PACKET)
        val packetType = packet.type ?: abort("Missing type declaration for $CLIPBOARD_PACKET")
        verifyClipboardPacket(packet, packetType)
        val screenSemantics = ExactJavaSemantics(screen.unit)

        val itemField = screenType.fields.flatMap { field ->
            field.variables.map { field to it }
        }.singleOrNull { it.second.nameAsString == "item" }
            ?: abort("ClipboardScreen.item field changed")
        if (itemField.first.isStatic ||
            !screenSemantics.exactTypeReference(itemField.second.typeAsString, "ItemStack", ITEM_STACK)
        ) {
            abort("ClipboardScreen.item is not the exact instance ItemStack field")
        }

        val creations = project.files.flatMap { file ->
            file.unit.findAll(ObjectCreationExpr::class.java)
                .filter { it.type.nameAsString == "ClipboardEditPacket" }
                .map { file to it }
        }
        if (creations.size != 1 || creations.single().first.unit !== screen.unit) {
            abort("ClipboardEditPacket must have exactly one source construction in ClipboardScreen")
        }
        val constructorReferences = project.files.flatMap { file ->
            file.unit.findAll(MethodReferenceExpr::class.java)
                .filter { reference ->
                    reference.identifier == "new" &&
                        compact(reference.scope).endsWith("ClipboardEditPacket")
                }
                .map { file to it }
        }
        if (constructorReferences.size != 1 ||
            constructorReferences.single().first.unit !== packet.unit
        ) {
            abort("ClipboardEditPacket constructor references are not closed")
        }

        val creation = creations.single().second
        if (!exactSourceTypeReference(screen, creation.type.nameWithScope, CLIPBOARD_PACKET)) {
            abort("ClipboardEditPacket construction does not name the source packet")
        }
        val send = creation.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: abort("ClipboardEditPacket construction is outside ClipboardScreen.send")
        if (send.nameAsString != "send" || !send.isPrivate || !send.type.isVoidType ||
            send.parameters.isNotEmpty()
        ) {
            abort("ClipboardScreen packet construction moved outside private void send()")
        }
        if (screenType.methods.count { it.nameAsString == "send" } != 1 ||
            screenType.methods.single { it.nameAsString == "send" } !== send ||
            hasNestedExecutionScope(creation, send)
        ) {
            abort("ClipboardScreen.send execution scope changed")
        }
        if (creation.arguments.size != 3 || compact(creation.arguments[0]) != "targetSlot" ||
            compact(creation.arguments[2]) != "targetedBlock"
        ) {
            abort("ClipboardEditPacket send arguments changed")
        }
        val distributor = creation.findAncestor(MethodCallExpr::class.java).orElse(null)
            ?: abort("ClipboardEditPacket is not sent through PacketDistributor")
        val distributorScope = distributor.scope.orElse(null)
        if (distributor.nameAsString != "sendToServer" || distributor.arguments.size != 1 ||
            distributor.arguments.single() !== creation || distributorScope == null ||
            !screenSemantics.exactStaticScope(
                distributorScope, "PacketDistributor", PACKET_DISTRIBUTOR
            )
        ) {
            abort("ClipboardEditPacket send boundary changed")
        }

        val payload = creation.arguments[1]
        val itemReferences = payload.findAll(NameExpr::class.java)
            .filter { it.nameAsString == "item" }
        if (itemReferences.size != 1 ||
            screenSemantics.declarationOf(itemReferences.single()) !== itemField.second
        ) {
            abort("ClipboardScreen payload no longer reads its exact item field")
        }
        return when (compact(payload)) {
            compact("item.getOrCreateTag()") -> {
                if (requireMigrated) abort("ClipboardScreen still hands a live tag to its payload")
                SourceEdit(screen.path, screen.source, payload, HandoffKind.CLIPBOARD)
            }
            compact(ITEM_SNAPSHOT) -> null
            else -> abort("ClipboardScreen payload data expression changed")
        }
    }

    private fun verifyClipboardPacket(
        packet: ParsedSource,
        owner: ClassOrInterfaceDeclaration
    ) {
        val semantics = ExactJavaSemantics(packet.unit)
        if (!owner.isPublic || owner.extendedTypes.size != 1 ||
            !semantics.exactTypeReference(
                owner.extendedTypes.single().nameWithScope,
                "SimplePacketBase",
                SIMPLE_PACKET_BASE
            )
        ) {
            abort("ClipboardEditPacket inheritance changed")
        }

        val fields = owner.fields.flatMap { field -> field.variables.map { field to it } }
        fun field(name: String): Pair<FieldDeclaration, VariableDeclarator> =
            fields.singleOrNull { it.second.nameAsString == name }
                ?: abort("ClipboardEditPacket.$name field changed")

        val data = field("data")
        if (!data.first.isPrivate || data.first.isStatic ||
            !semantics.exactTypeReference(data.second.typeAsString, "CompoundTag", COMPOUND_TAG)
        ) {
            abort("ClipboardEditPacket.data ownership changed")
        }
        val slot = field("hotbarSlot")
        val target = field("targetedBlock")
        if (!slot.first.isPrivate || slot.first.isStatic || slot.second.typeAsString != "int" ||
            !target.first.isPrivate || target.first.isStatic ||
            !semantics.exactTypeReference(target.second.typeAsString, "BlockPos", BLOCK_POS)
        ) {
            abort("ClipboardEditPacket retained fields changed")
        }

        val codec = field("STREAM_CODEC")
        val codecType = codec.second.type.takeIf { it.isClassOrInterfaceType }
            ?.asClassOrInterfaceType()
        val codecArguments = codecType?.typeArguments?.orElse(null)
        val codecBufferType = codecArguments?.getOrNull(0)?.asString()
        val codecPacketType = codecArguments?.getOrNull(1)?.asString()
        val codecInitializer = codec.second.initializer.orElse(null) as? MethodCallExpr
        if (!codec.first.isPublic || !codec.first.isStatic || !codec.first.isFinal ||
            codecType == null ||
            !semantics.exactTypeReference(codecType.nameWithScope, "StreamCodec", STREAM_CODEC) ||
            codecArguments?.size != 2 || codecBufferType == null ||
            !semantics.exactTypeReference(
                codecBufferType, "RegistryFriendlyByteBuf", REGISTRY_BUFFER
            ) ||
            codecPacketType != "ClipboardEditPacket" ||
            codecInitializer == null || codecInitializer.scope.isEmpty ||
            !semantics.exactStaticScope(
                codecInitializer.scope.get(), "StreamCodec", STREAM_CODEC
            ) ||
            compact(codec.second.initializer.orElse(null)) != compact(
                "StreamCodec.of((buffer, packet) -> packet.write(buffer), ClipboardEditPacket::new)"
            )
        ) {
            abort("ClipboardEditPacket delayed StreamCodec changed")
        }

        if (owner.constructors.size != 2) abort("ClipboardEditPacket constructors changed")
        val sourceConstructor = owner.constructors.singleOrNull {
            parameterNames(it) == listOf("hotbarSlot", "data", "targetedBlock")
        } ?: abort("ClipboardEditPacket source constructor changed")
        if (!sourceConstructor.isPublic || sourceConstructor.parameters.size != 3 ||
            sourceConstructor.parameters[0].typeAsString != "int" ||
            !semantics.exactTypeReference(
                sourceConstructor.parameters[1].typeAsString, "CompoundTag", COMPOUND_TAG
            ) ||
            !semantics.exactTypeReference(
                sourceConstructor.parameters[2].typeAsString, "BlockPos", BLOCK_POS
            ) ||
            sourceConstructor.parameters[2].annotations.none { it.nameAsString == "Nullable" } ||
            compact(sourceConstructor.body) !=
            compact("{ this.hotbarSlot = hotbarSlot; this.data = data; this.targetedBlock = targetedBlock; }")
        ) {
            abort("ClipboardEditPacket no longer retains the supplied data directly")
        }

        val decodeConstructor = owner.constructors.singleOrNull {
            parameterNames(it) == listOf("buffer")
        } ?: abort("ClipboardEditPacket buffer constructor changed")
        if (!decodeConstructor.isPublic ||
            !semantics.exactTypeReference(
                decodeConstructor.parameters.single().typeAsString,
                "RegistryFriendlyByteBuf",
                REGISTRY_BUFFER
            ) ||
            compact(decodeConstructor.body) != compact(
                """
                {
                    hotbarSlot = buffer.readVarInt();
                    data = buffer.readNbt();
                    if (buffer.readBoolean()) targetedBlock = buffer.readBlockPos();
                }
                """.trimIndent()
            )
        ) {
            abort("ClipboardEditPacket buffer constructor changed")
        }

        val write = owner.methods.singleOrNull { it.nameAsString == "write" }
            ?: abort("ClipboardEditPacket.write changed")
        if (!write.isPublic || !write.type.isVoidType || write.parameters.size != 1 ||
            !semantics.exactTypeReference(
                write.parameters.single().typeAsString,
                "RegistryFriendlyByteBuf",
                REGISTRY_BUFFER
            ) ||
            write.parameters.single().nameAsString != "buffer" ||
            compact(write.body.orElse(null)) != compact(
                """
                {
                    buffer.writeVarInt(hotbarSlot);
                    buffer.writeNbt(data);
                    buffer.writeBoolean(targetedBlock != null);
                    if (targetedBlock != null) buffer.writeBlockPos(targetedBlock);
                }
                """.trimIndent()
            )
        ) {
            abort("ClipboardEditPacket delayed encoder changed")
        }
    }

    private fun admitMinecart(
        project: ParsedProject,
        requireMigrated: Boolean
    ): SourceEdit? {
        project.rejectSourceDeclarations(MINECART_EXTERNAL_TYPES)
        val owner = project.requireOwner(MINECART_ITEM)
        val ownerType = owner.type ?: abort("Missing type declaration for $MINECART_ITEM")
        val semantics = ExactJavaSemantics(owner.unit)
        val declarations = project.files.flatMap { file ->
            file.unit.findAll(MethodDeclaration::class.java)
                .filter { it.nameAsString == "addContraptionToMinecart" }
                .map { file to it }
        }
        if (declarations.size != 1 || declarations.single().first.unit !== owner.unit) {
            abort("addContraptionToMinecart declaration is not unique and source-owned")
        }
        val helper = declarations.single().second
        if (helper.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) !== ownerType) {
            abort("addContraptionToMinecart declaration moved outside MinecartContraptionItem")
        }
        verifyMinecartHelperSignature(helper, semantics)

        val calls = project.files.flatMap { file ->
            file.unit.findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == "addContraptionToMinecart" }
                .map { file to it }
        }
        val occurrences = project.files.sumOf { file ->
            Regex("""(?<![\w$])addContraptionToMinecart(?![\w$])""")
                .findAll(JavaSourceText.maskCommentsAndLiterals(file.source)).count()
        }
        if (calls.size != 2 || occurrences != calls.size + 1 || calls.any { it.first.unit !== owner.unit }) {
            abort("addContraptionToMinecart has unknown calls, references, or overloads")
        }

        val expectedCalls = mapOf(
            compact(
                "addContraptionToMinecart(world, stack, abstractminecartentity, direction)"
            ) to CallerProof(
                "execute", "stack", "ItemStack", listOf("BlockSource" to "source", "ItemStack" to "stack")
            ),
            compact(
                """
                addContraptionToMinecart(
                    world, itemstack, abstractminecartentity,
                    player == null ? null : player.getDirection()
                )
                """.trimIndent()
            ) to CallerProof(
                "useOn", "itemstack", "InteractionResult", listOf("UseOnContext" to "context")
            )
        )
        val seen = mutableSetOf<String>()
        calls.forEach { (_, call) ->
            if (call.scope.isPresent) abort("addContraptionToMinecart call is no longer source-local")
            val key = compact(call)
            val proof = expectedCalls[key]
                ?: abort("addContraptionToMinecart caller shape changed")
            if (!seen.add(key)) abort("addContraptionToMinecart caller is duplicated")
            val caller = call.findAncestor(MethodDeclaration::class.java).orElse(null)
                ?: abort("addContraptionToMinecart call is outside a method")
            if (!caller.isPublic || caller.nameAsString != proof.method ||
                simpleType(caller.typeAsString) != proof.returnType ||
                caller.parameters.map { simpleType(it.typeAsString) to it.nameAsString } !=
                proof.parameters
            ) {
                abort("addContraptionToMinecart moved outside its consuming caller")
            }
            requireImmediateEnclosingConsumption(call, proof.stack)
        }
        if (seen != expectedCalls.keys) abort("addContraptionToMinecart caller family is incomplete")

        val body = helper.body.orElse(null) ?: abort("addContraptionToMinecart has no body")
        val first = body.statements.firstOrNull()?.let { statement ->
            statement.takeIf { it.isExpressionStmt }?.asExpressionStmt()?.expression
                ?.takeIf { it.isVariableDeclarationExpr }?.asVariableDeclarationExpr()
        } ?: abort("addContraptionToMinecart tag handoff changed")
        val tag = first.variables.singleOrNull()
            ?: abort("addContraptionToMinecart tag declaration changed")
        if (tag.nameAsString != "tag" ||
            !semantics.exactTypeReference(tag.typeAsString, "CompoundTag", COMPOUND_TAG)
        ) {
            abort("addContraptionToMinecart tag declaration changed")
        }
        val initializer = tag.initializer.orElse(null)
            ?: abort("addContraptionToMinecart tag initializer is missing")

        val state = when (compact(initializer)) {
            compact("itemstack.getOrCreateTag()") -> HandoffState.LEGACY
            compact(ITEMSTACK_SNAPSHOT) -> HandoffState.MIGRATED
            else -> abort("addContraptionToMinecart data handoff changed")
        }
        verifyMinecartHelperBody(body, state, semantics)
        if (requireMigrated && state == HandoffState.LEGACY) {
            abort("addContraptionToMinecart still exposes the source stack tag")
        }
        return if (state == HandoffState.LEGACY) {
            SourceEdit(owner.path, owner.source, initializer, HandoffKind.MINECART)
        } else {
            null
        }
    }

    private fun verifyMinecartHelperSignature(
        helper: MethodDeclaration,
        semantics: ExactJavaSemantics
    ) {
        if (!helper.isPublic || !helper.isStatic || !helper.type.isVoidType ||
            helper.parameters.size != 4 ||
            !semantics.exactTypeReference(helper.parameters[0].typeAsString, "Level", LEVEL) ||
            !semantics.exactTypeReference(
                helper.parameters[1].typeAsString, "ItemStack", ITEM_STACK
            ) ||
            !semantics.exactTypeReference(
                helper.parameters[2].typeAsString, "AbstractMinecart", ABSTRACT_MINECART
            ) ||
            !semantics.exactTypeReference(
                helper.parameters[3].typeAsString, "Direction", DIRECTION
            ) ||
            helper.parameters.map { it.nameAsString } !=
            listOf("world", "itemstack", "cart", "newFacing") ||
            helper.parameters[3].annotations.none { it.nameAsString == "Nullable" }
        ) {
            abort("addContraptionToMinecart public signature changed")
        }
    }

    private fun verifyMinecartHelperBody(
        body: BlockStmt,
        state: HandoffState,
        semantics: ExactJavaSemantics
    ) {
        val handoff = if (state == HandoffState.LEGACY) {
            "itemstack.getOrCreateTag()"
        } else {
            ITEMSTACK_SNAPSHOT
        }
        val expected = """
            {
                CompoundTag tag = $handoff;
                if (tag.contains("Contraption")) {
                    CompoundTag contraptionTag = tag.getCompound("Contraption");
                    Direction intialOrientation =
                        NBTHelper.readEnum(contraptionTag, "InitialOrientation", Direction.class);
                    Contraption mountedContraption =
                        Contraption.fromNBT(world, contraptionTag, false);
                    OrientedContraptionEntity contraptionEntity =
                        newFacing == null
                            ? OrientedContraptionEntity.create(
                                world, mountedContraption, intialOrientation)
                            : OrientedContraptionEntity.createAtYaw(
                                world, mountedContraption, intialOrientation,
                                newFacing.toYRot());
                    contraptionEntity.startRiding(cart);
                    contraptionEntity.setPos(cart.getX(), cart.getY(), cart.getZ());
                    world.addFreshEntity(contraptionEntity);
                }
            }
        """.trimIndent()
        if (compact(body) != compact(expected)) {
            abort("addContraptionToMinecart consumer body changed")
        }

        val variables = body.findAll(VariableDeclarator::class.java).associateBy { it.nameAsString }
        fun exactVariable(name: String, simpleName: String, fqn: String) {
            val variable = variables[name] ?: abort("Missing minecart helper variable $name")
            if (!semantics.exactTypeReference(variable.typeAsString, simpleName, fqn)) {
                abort("Minecart helper variable $name has an ambiguous type")
            }
        }
        exactVariable("tag", "CompoundTag", COMPOUND_TAG)
        exactVariable("contraptionTag", "CompoundTag", COMPOUND_TAG)
        exactVariable("intialOrientation", "Direction", DIRECTION)
        exactVariable("mountedContraption", "Contraption", CONTRAPTION)
        exactVariable("contraptionEntity", "OrientedContraptionEntity", ORIENTED_CONTRAPTION)

        fun requireStaticCalls(method: String, simpleName: String, fqn: String, count: Int) {
            val calls = body.findAll(MethodCallExpr::class.java).filter { it.nameAsString == method }
            if (calls.size != count || calls.any { call ->
                    call.scope.isEmpty ||
                        !semantics.exactStaticScope(call.scope.get(), simpleName, fqn)
                }
            ) {
                abort("Minecart helper $simpleName.$method scope is ambiguous")
            }
        }
        requireStaticCalls("fromNBT", "Contraption", CONTRAPTION, 1)
        requireStaticCalls("create", "OrientedContraptionEntity", ORIENTED_CONTRAPTION, 1)
        requireStaticCalls("createAtYaw", "OrientedContraptionEntity", ORIENTED_CONTRAPTION, 1)
    }

    private fun requireImmediateEnclosingConsumption(call: MethodCallExpr, stack: String) {
        val callStatement = call.findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: abort("addContraptionToMinecart is not an expression statement")
        var cursor: Node = callStatement
        while (true) {
            val parent = cursor.parentNode.orElse(null) ?: break
            if (parent is BlockStmt) {
                val containing = parent.statements.indexOfFirst { statement ->
                    statement === cursor || statement.findAll(MethodCallExpr::class.java).any { it === call }
                }
                if (containing >= 0 && containing + 1 < parent.statements.size &&
                    compact(parent.statements[containing + 1]) == compact("$stack.shrink(1);")
                ) {
                    return
                }
            }
            cursor = parent
        }
        abort("addContraptionToMinecart caller no longer consumes $stack with shrink(1)")
    }

    private fun parseProject(sources: Map<String, String>): ParsedProject {
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val files = sources.map { (path, source) -> parse(parser, path, source) }
        val topTypes = LinkedHashMap<String, TopTypeRef>()
        files.forEach { file ->
            file.unit.types.forEach { type ->
                val pkg = file.unit.packageDeclaration.map { it.nameAsString }.orElse("")
                val name = if (pkg.isEmpty()) type.nameAsString else "$pkg.${type.nameAsString}"
                if (topTypes.put(name, TopTypeRef(file, type)) != null) {
                    abort("Duplicate source type $name")
                }
            }
        }
        val owners = LinkedHashMap<String, ParsedSource>()
        listOf(CLIPBOARD_SCREEN, CLIPBOARD_PACKET, MINECART_ITEM).forEach { name ->
            val ref = topTypes[name] ?: return@forEach
            val type = ref.declaration as? ClassOrInterfaceDeclaration
                ?: abort("Source owner $name is not a class or interface")
            owners[name] = ref.file.copy(type = type)
        }
        return ParsedProject(files, owners, topTypes)
    }

    private fun hasNestedExecutionScope(node: Node, method: MethodDeclaration): Boolean =
        generateSequence(node.parentNode.orElse(null)) { it.parentNode.orElse(null) }
            .takeWhile { it !== method }
            .any { ancestor ->
                ancestor is LambdaExpr || ancestor is TypeDeclaration<*> ||
                    (ancestor is ObjectCreationExpr && ancestor.anonymousClassBody.isPresent)
            }

    private fun exactSourceTypeReference(
        file: ParsedSource,
        typeText: String,
        fqn: String
    ): Boolean {
        val normalized = typeText.replace(" ", "")
        if (normalized == fqn) return true
        val simpleName = fqn.substringAfterLast('.')
        if (normalized != simpleName) return false
        val semantics = ExactJavaSemantics(file.unit)
        val conflictingImport = file.unit.imports.any {
            !it.isStatic && !it.isAsterisk && it.name.identifier == simpleName &&
                it.nameAsString != fqn
        }
        val pkg = file.unit.packageDeclaration.map { it.nameAsString }.orElse("")
        return !conflictingImport && !semantics.hasDeclaredType(simpleName) &&
            (semantics.hasExactImport(fqn) || pkg == fqn.substringBeforeLast('.'))
    }

    private fun parse(parser: JavaParser, path: String, source: String): ParsedSource {
        val result = parser.parse(source)
        if (!result.isSuccessful || result.result.isEmpty) {
            abort("Cannot parse $path")
        }
        return ParsedSource(path, source, result.result.get(), null)
    }

    private fun replaceNode(source: String, node: Node, replacement: String): String {
        val range = node.range.orElse(null) ?: abort("Source handoff has no range")
        val starts = lineStarts(source)
        val start = offset(starts, range.begin)
        val end = offset(starts, range.end) + 1
        if (start !in 0..source.length || end !in start..source.length) {
            abort("Source handoff range is invalid")
        }
        return source.substring(0, start) + replacement + source.substring(end)
    }

    private fun lineStarts(source: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        source.forEachIndexed { index, ch -> if (ch == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun offset(starts: IntArray, position: Position): Int {
        if (position.line <= 0 || position.line > starts.size || position.column <= 0) {
            abort("Source position is invalid")
        }
        return starts[position.line - 1] + position.column - 1
    }

    private fun targetContractsAvailable(): Boolean {
        fun public(owner: String, name: String, descriptor: String): Boolean =
            TargetMembers.inheritedMethod(owner, name, descriptor)?.access == "public"
        return TargetMembers.fieldDescriptor(
            "net.minecraft.core.component.DataComponents",
            "CUSTOM_DATA"
        ) == "Lnet/minecraft/core/component/DataComponentType;" &&
            TargetMembers.fieldDescriptor(
                "net.minecraft.world.item.component.CustomData",
                "EMPTY"
            ) == "Lnet/minecraft/world/item/component/CustomData;" &&
            public(
                "net.minecraft.world.item.ItemStack",
                "getOrDefault",
                "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;"
            ) && public(
                "net.minecraft.world.item.component.CustomData",
                "copyTag",
                "()Lnet/minecraft/nbt/CompoundTag;"
            )
    }

    private fun activity(sources: Map<String, String>): Activity {
        var clipboard = false
        var minecart = false
        sources.values.forEach { source ->
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            if (Regex("""\b(?:ClipboardScreen|ClipboardEditPacket)\b""").containsMatchIn(masked)) {
                clipboard = true
            }
            if (Regex("""\b(?:MinecartContraptionItem|addContraptionToMinecart)\b""")
                    .containsMatchIn(masked)
            ) {
                minecart = true
            }
        }
        return Activity(clipboard, minecart)
    }

    private fun parameterTypes(constructor: ConstructorDeclaration): List<String> =
        constructor.parameters.map { simpleType(it.typeAsString) }

    private fun parameterNames(constructor: ConstructorDeclaration): List<String> =
        constructor.parameters.map { it.nameAsString }

    private fun simpleType(type: String): String = type.substringAfterLast('.')

    private fun compact(node: Node?): String = compact(node?.toString() ?: "")

    private fun compact(source: String): String = source.replace(Regex("""\s+"""), "")

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private data class Activity(val clipboard: Boolean, val minecart: Boolean) {
        val any: Boolean get() = clipboard || minecart
    }

    private data class ParsedSource(
        val path: String,
        val source: String,
        val unit: CompilationUnit,
        val type: ClassOrInterfaceDeclaration?
    )

    private data class ParsedProject(
        val files: List<ParsedSource>,
        val owners: Map<String, ParsedSource>,
        val topTypes: Map<String, TopTypeRef>
    ) {
        fun requireOwner(name: String): ParsedSource {
            val source = owners[name] ?: abort("Missing source owner $name")
            if (source.type == null) abort("Missing type declaration for $name")
            return source
        }

        fun rejectSourceDeclarations(names: Set<String>) {
            names.firstOrNull(topTypes::containsKey)?.let { name ->
                abort("SDK type $name must not be supplied as project source")
            }
        }
    }

    private data class TopTypeRef(
        val file: ParsedSource,
        val declaration: TypeDeclaration<*>
    )

    private data class SourceEdit(
        val path: String,
        val source: String,
        val node: Expression,
        val kind: HandoffKind
    )
    private data class CallerProof(
        val method: String,
        val stack: String,
        val returnType: String,
        val parameters: List<Pair<String, String>>
    )
    private enum class HandoffState { LEGACY, MIGRATED }
    private enum class HandoffKind { CLIPBOARD, MINECART }
    private class AdmissionAbort(message: String) : RuntimeException(message)
}
