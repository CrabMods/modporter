package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaProjectIndex
import com.modporter.core.transforms.util.JavaSourceText
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forge mods that route every packet through an abstract base (`write(FriendlyByteBuf)` plus
 * `handle(NetworkEvent.Context)`) and register the classes from a table — an enum of
 * `(Packet.class, Packet::new, direction)` entries feeding `SimpleChannel#messageBuilder` —
 * become NeoForge payloads: the base implements `CustomPacketPayload`, every concrete packet
 * carries its `TYPE` and a `StreamCodec` built from its `write` and buffer constructor, the
 * table registers them through `RegisterPayloadHandlersEvent`, and handlers receive an
 * `IPayloadContext`. Channel send calls become `PacketDistributor` helpers.
 */
internal object PacketTablePayloads {
    const val RULE_ID = "struct-packet-table-payloads"

    private const val PAYLOAD_IMPORT = "net.minecraft.network.protocol.common.custom.CustomPacketPayload"
    private const val CONTEXT_IMPORT = "net.neoforged.neoforge.network.handling.IPayloadContext"
    private const val STREAM_CODEC_IMPORT = "net.minecraft.network.codec.StreamCodec"
    private const val REGISTRY_BUFFER_IMPORT = "net.minecraft.network.RegistryFriendlyByteBuf"
    private const val RESOURCE_LOCATION_IMPORT = "net.minecraft.resources.ResourceLocation"
    private const val PACKET_FLOW_IMPORT = "net.minecraft.network.protocol.PacketFlow"
    private const val REGISTRAR_EVENT_IMPORT = "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent"
    private const val REGISTRAR_IMPORT = "net.neoforged.neoforge.network.registration.PayloadRegistrar"
    private const val DISTRIBUTOR_IMPORT = "net.neoforged.neoforge.network.PacketDistributor"

    private val legacyContextImports = listOf(
        "net.minecraftforge.network.NetworkEvent.Context", "net.minecraftforge.network.NetworkEvent",
        "net.neoforged.neoforge.network.NetworkEvent.Context", "net.neoforged.neoforge.network.NetworkEvent"
    )

    data class PacketBase(val name: String, val file: Path, val writeMethod: String, val handleMethod: String)

    fun migrate(projectDir: Path, modId: String, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val bases = files.mapNotNull { packetBase(it) }
        if (bases.isEmpty()) return emptyList()
        val index = JavaProjectIndex.build(files)
        val changes = mutableListOf<Change>()
        val concretePackets = linkedSetOf<String>()
        val tables = mutableListOf<String>()

        for (base in bases) {
            val hierarchy = index.subtypesOf(base.name)
            val baseSource = base.file.readText()
            val migratedBase = JavaSourceText.addImport(
                migrateHandlers(implementPayload(baseSource, base.name), base.handleMethod),
                PAYLOAD_IMPORT
            )
            write(base.file, baseSource, migratedBase, dryRun, changes, "Packet base ${base.name} implements CustomPacketPayload")
            for (declaration in hierarchy) {
                if (declaration.kind != "class") continue
                val source = declaration.file.readText()
                var migrated = migrateHandlers(source, base.handleMethod)
                val concrete = declaration.name == declaration.file.fileName.toString().removeSuffix(".java") &&
                    !declaration.isAbstract && declaration.typeParameters.isEmpty() &&
                    Regex("""\b${Regex.escape(declaration.name)}\s*\(\s*(?:net\.minecraft\.network\.)?(?:Registry)?FriendlyByteBuf\s+[A-Za-z_$][\w$]*\s*\)""")
                        .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(migrated))
                if (concrete) {
                    migrated = declarePayloadType(migrated, declaration.name, base.writeMethod, modId)
                    migrated = registryBufferEndpoints(migrated, declaration.name, base.writeMethod)
                    concretePackets += declaration.name
                }
                write(declaration.file, source, migrated, dryRun, changes, "Packet ${declaration.name} became a payload")
            }
            // Discover nested declarations in their own file, retaining duplicate simple names in different owners.
            val ancestors = hierarchy.map { it.name }.toSet() + base.name
            for (file in files) {
                val source = file.readText()
                val nested = nestedPackets(source, ancestors)
                if (nested.isEmpty()) continue
                var migrated = migrateHandlers(source, base.handleMethod)
                for ((inner, reference) in nested) {
                    migrated = declarePayloadType(migrated, inner, base.writeMethod, modId, payloadPath(reference.replace('.', '_')))
                    migrated = registryBufferEndpoints(migrated, inner, base.writeMethod)
                    concretePackets += reference
                }
                write(file, source, migrated, dryRun, changes, "Nested packets retain their enclosing type identities")
            }
        }

        for (file in files) {
            val source = file.readText()
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val tableName = Regex("""\benum\s+([A-Za-z_$][\w$]*)""").find(masked)?.groupValues?.get(1) ?: continue
            if (!masked.contains("messageBuilder(") || !masked.contains("SimpleChannel")) {
                if (masked.contains("CustomPacketPayload.Type") && masked.contains("StreamCodec")) {
                    write(file, source, migrateTableEntries(source, concretePackets), dryRun, changes, "Nested packet imports name their declared payload types")
                }
                continue
            }
            val baseName = bases.firstOrNull { masked.contains("<T extends ${it.name}>") }?.name ?: bases.first().name
            val migrated = migrateTable(source, tableName, baseName, concretePackets)
            if (write(file, source, migrated, dryRun, changes, "Packet table $tableName registers payloads")) tables += tableName
        }

        val targetHelpers = if (tables.isEmpty()) emptyMap() else packetTargetHelpers(files)
        for (table in tables) {
            for (file in files) {
                val source = file.readText()
                if (!source.contains(table)) continue
                val migrated = migrateRegistrationCall(migrateChannelSends(source, table, targetHelpers), table)
                write(file, source, migrated, dryRun, changes, "Channel calls of $table use PacketDistributor")
            }
        }
        if (targetHelpers.isNotEmpty()) {
            for (file in files) {
                val source = file.readText()
                if (!source.contains("PacketTarget")) continue
                val migrated = removePacketTargetHelpers(source, targetHelpers.keys)
                write(file, source, migrated, dryRun, changes, "PacketTarget helpers are inlined at their send sites")
            }
        }
        return changes
    }

    /**
     * `public PacketTarget packetTarget() { return PacketDistributor.TRACKING_CHUNK.with(this::containedChunk); }`
     * helpers, by name: 1.21 has no PacketTarget, so each send site gets the distributor call the
     * helper stood for and the helper goes.
     */
    private fun packetTargetHelpers(files: List<Path>): Map<String, String> {
        val helper = Regex("""(?:PacketDistributor\.)?PacketTarget\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{\s*return\s+([^;]+);\s*\}""")
        val result = linkedMapOf<String, String>()
        for (file in files) {
            val source = runCatching { file.readText() }.getOrNull() ?: continue
            if (!source.contains("PacketTarget")) continue
            helper.findAll(JavaSourceText.maskCommentsAndLiterals(source)).forEach { match ->
                result[match.groupValues[1]] = source.substring(match.groups[2]!!.range).trim()
            }
        }
        return result
    }

    private fun removePacketTargetHelpers(source: String, helperNames: Set<String>): String {
        var result = source
        for (name in helperNames) {
            result = replaceMethod(result, Regex("""(?:public|protected|private)?\s*(?:PacketDistributor\.)?PacketTarget\s+${Regex.escape(name)}\s*\(\s*\)\s*\{""")) { "" }
        }
        if (result != source) {
            for (import in listOf("net.neoforged.neoforge.network.PacketDistributor.PacketTarget", "net.minecraftforge.network.PacketDistributor.PacketTarget")) {
                result = JavaSourceText.removeImport(result, import)
            }
        }
        return result
    }

    private fun packetBase(file: Path): PacketBase? {
        val source = runCatching { file.readText() }.getOrNull() ?: return null
        if (!source.contains("abstract") || !source.contains("FriendlyByteBuf")) return null
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val name = Regex("""\babstract\s+class\s+([A-Za-z_$][\w$]*)""").find(masked)?.groupValues?.get(1) ?: return null
        val write = Regex("""\babstract\s+void\s+(write|encode)\s*\(\s*(?:net\.minecraft\.network\.)?(?:Registry)?FriendlyByteBuf\s+[A-Za-z_$][\w$]*\s*\)""")
            .find(masked)?.groupValues?.get(1) ?: return null
        val handle = Regex("""\babstract\s+(?:boolean|void)\s+(handle|execute)\s*\(\s*(?:(?:NetworkEvent\.)?Context|IPayloadContext|Supplier\s*<\s*(?:NetworkEvent\.)?Context\s*>)\s+[A-Za-z_$][\w$]*\s*\)""")
            .find(masked)?.groupValues?.get(1) ?: return null
        return PacketBase(name, file, write, handle)
    }

    private fun implementPayload(source: String, baseName: String): String {
        val header = Regex("""(\babstract\s+class\s+${Regex.escape(baseName)}\b[^{]*?)(\s+implements\s+([^{]+?))?\s*\{""")
        return header.replace(source) { match ->
            val implemented = match.groupValues[3].trim()
            if (implemented.split(',').any { it.trim().substringAfterLast('.') == "CustomPacketPayload" }) return@replace match.value
            val list = if (implemented.isEmpty()) "CustomPacketPayload" else "$implemented, CustomPacketPayload"
            "${match.groupValues[1]} implements $list {"
        }
    }

    /** `handle(NetworkEvent.Context ctx)` takes an `IPayloadContext`; the body uses its player/flow accessors. */
    private fun migrateHandlers(source: String, handleMethod: String): String {
        var result = source
        val contextNames = linkedSetOf<String>()
        val direct = Regex("""(\b$handleMethod\s*\(\s*)(?:(?:(?:net\.minecraftforge\.network\.|net\.neoforged\.neoforge\.network\.)?NetworkEvent\.)?Context|IPayloadContext)\s+([A-Za-z_$][\w$]*)(\s*\))""")
        result = replaceExecutable(result, direct) { match ->
            contextNames += match.groupValues[2]
            "${match.groupValues[1]}IPayloadContext ${match.groupValues[2]}${match.groupValues[3]}"
        }
        val supplier = Regex("""(\b$handleMethod\s*\(\s*)Supplier\s*<\s*(?:NetworkEvent\.)?Context\s*>\s+([A-Za-z_$][\w$]*)(\s*\))""")
        for (match in supplier.findAll(result).toList().asReversed()) {
            val supplierName = match.groupValues[2]
            val inner = Regex("""(?m)^[ \t]*(?:NetworkEvent\.)?Context\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(supplierName)}\.get\(\)\s*;[ \t]*\r?\n""")
                .find(result, match.range.last)
            val contextName = inner?.groupValues?.get(1) ?: supplierName
            contextNames += contextName
            if (inner != null) result = result.removeRange(inner.range)
            result = result.replaceRange(match.range, "${match.groupValues[1]}IPayloadContext $contextName${match.groupValues[3]}")
        }
        if (contextNames.isEmpty()) return source
        for (context in contextNames) {
            val escaped = Regex.escape(context)
            result = Regex("""(?m)^([ \t]*)ServerPlayer\s+([A-Za-z_$][\w$]*)\s*=\s*$escaped\.getSender\(\)\s*;\s*\r?\n[ \t]*if\s*\(\s*\2\s*==\s*null\s*\)\s*return\s*;""")
                .replace(result) { match -> "${match.groupValues[1]}if (!($context.player() instanceof ServerPlayer ${match.groupValues[2]}))\n${match.groupValues[1]}\treturn;" }
            result = Regex("""(?m)^([ \t]*)ServerPlayer\s+([A-Za-z_$][\w$]*)\s*=\s*$escaped\.getSender\(\)\s*;\s*\r?\n[ \t]*if\s*\(\s*\2\s*==\s*null\s*\|\|(.+)\)\s*return\s*;""")
                .replace(result) { match -> "${match.groupValues[1]}if (!($context.player() instanceof ServerPlayer ${match.groupValues[2]}) ||${match.groupValues[3]})\n${match.groupValues[1]}\treturn;" }
            result = replaceExecutable(result, Regex("""\b$escaped\.getSender\(\)""")) { "((ServerPlayer) $context.player())" }
            result = replaceExecutable(result, Regex("""\b$escaped::getSender\b""")) { "$context::player" }
            result = replaceExecutable(result, Regex("""\b$escaped\.getDirection\(\)""")) { "$context.flow()" }
            result = replaceExecutable(result, Regex("""\b$escaped\.getNetworkManager\(\)""")) { "$context.connection()" }
            result = Regex("""(?m)^[ \t]*$escaped\.setPacketHandled\(\s*true\s*\)\s*;[ \t]*\r?\n""").replace(result, "")
        }
        result = replaceExecutable(result, Regex("""\bNetworkDirection\.PLAY_TO_SERVER\b""")) { "PacketFlow.SERVERBOUND" }
        result = replaceExecutable(result, Regex("""\bNetworkDirection\.PLAY_TO_CLIENT\b""")) { "PacketFlow.CLIENTBOUND" }
        legacyContextImports.forEach { result = JavaSourceText.removeImport(result, it) }
        listOf(
            "net.minecraftforge.network.NetworkDirection",
            "net.neoforged.neoforge.network.NetworkDirection"
        ).forEach { result = JavaSourceText.removeImport(result, it) }
        result = JavaSourceText.addImport(result, CONTEXT_IMPORT)
        if (result.contains("PacketFlow.")) result = JavaSourceText.addImport(result, PACKET_FLOW_IMPORT)
        if (result.contains("ServerPlayer") && !result.contains("import net.minecraft.server.level.ServerPlayer;")) {
            result = JavaSourceText.addImport(result, "net.minecraft.server.level.ServerPlayer")
        }
        return result
    }

    private fun replaceExecutable(source: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = pattern.findAll(masked).mapNotNull { maskedMatch ->
            val sourceMatch = pattern.find(source, maskedMatch.range.first)
                ?.takeIf { it.range == maskedMatch.range }
                ?: return@mapNotNull null
            sourceMatch.range to replacement(sourceMatch)
        }.toList()
        return edits.asReversed().fold(source) { result, (range, value) ->
            result.replaceRange(range, value)
        }
    }

    private fun nestedPackets(source: String, ancestors: Set<String>): List<Pair<String, String>> {
        if (!source.contains("static") || !source.contains("FriendlyByteBuf") || !ancestors.any { source.contains("extends $it") }) return emptyList()
        val unit = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)).parse(source).result.orElse(null) ?: return emptyList()
        val classes = unit.findAll(ClassOrInterfaceDeclaration::class.java)
        return classes.filter { type ->
            type.isStatic && !type.isAbstract && type.typeParameters.isEmpty() && classes.count { it.nameAsString == type.nameAsString } == 1 &&
                type.extendedTypes.any { it.nameAsString in ancestors } && type.constructors.any {
                    it.parameters.size == 1 && it.parameters[0].type.asString().substringAfterLast('.') in setOf("FriendlyByteBuf", "RegistryFriendlyByteBuf")
                }
        }.map { type ->
            val owners = mutableListOf(type.nameAsString)
            var parent = type.parentNode.orElse(null)
            while (parent != null) {
                if (parent is TypeDeclaration<*>) owners += parent.nameAsString
                parent = parent.parentNode.orElse(null)
            }
            type.nameAsString to owners.asReversed().joinToString(".")
        }
    }

    private fun declarePayloadType(source: String, className: String, writeMethod: String, modId: String, path: String = payloadPath(className)): String {
        if (source.contains("Type<$className> TYPE")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val header = Regex("""\bclass\s+${Regex.escape(className)}\b[^{]*\{""").find(masked) ?: return source
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val indent = "\t"
        val block = listOf(
            "",
            "${indent}public static final CustomPacketPayload.Type<$className> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(\"$modId\", \"$path\"));",
            "${indent}public static final StreamCodec<RegistryFriendlyByteBuf, $className> STREAM_CODEC = StreamCodec.of((buffer, packet) -> packet.$writeMethod(buffer), $className::new);",
            "",
            "$indent@Override",
            "${indent}public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {",
            "$indent${indent}return TYPE;",
            "$indent}",
            ""
        ).joinToString(newline)
        var result = source.substring(0, header.range.last + 1) + block + source.substring(header.range.last + 1)
        listOf(PAYLOAD_IMPORT, STREAM_CODEC_IMPORT, REGISTRY_BUFFER_IMPORT, RESOURCE_LOCATION_IMPORT).forEach { result = JavaSourceText.addImport(result, it) }
        return result
    }

    /** The payload stream codec hands the buffer constructor and the writer a RegistryFriendlyByteBuf. */
    private fun registryBufferEndpoints(source: String, className: String, writeMethod: String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val endpoint = Regex(
            """(?<![\w$.])(?:${Regex.escape(className)}|void\s+${Regex.escape(writeMethod)})\s*\(\s*((?:net\.minecraft\.network\.)?FriendlyByteBuf)\s+[A-Za-z_$][\w$]*\s*\)"""
        )
        val types = endpoint.findAll(masked).mapNotNull { it.groups[1]?.range }.toList()
        return types.asReversed().fold(source) { text, range -> text.replaceRange(range, "RegistryFriendlyByteBuf") }
    }

    private fun payloadPath(className: String): String =
        className.replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2").lowercase()

    private fun migrateTableEntries(source: String, concretePackets: Set<String>): String {
        var result = source
        for (packet in concretePackets) {
            val imported = '.' in packet && Regex("""(?m)^\s*import\s+[\w.]*${Regex.escape(packet)}\s*;""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source))
            for (reference in listOfNotNull(packet, packet.substringAfterLast('.').takeIf { imported }).distinct()) {
                val escaped = Regex.escape(reference)
                result = result.replace(Regex("""(?<![\w$.])((?:[A-Za-z_$][\w$]*\.)*)$escaped\.class\s*,\s*\1$escaped::new\b""")) { match ->
                    val qualified = match.groupValues[1] + reference
                    "$qualified.TYPE, $qualified.STREAM_CODEC"
                }
            }
        }
        return result
    }

    private fun migrateTable(source: String, tableName: String, baseName: String, concretePackets: Set<String>): String {
        var result = migrateTableEntries(source, concretePackets)
        result = Regex("""(?m)^import\s+static\s+[\w.]+NetworkDirection\.PLAY_TO_(?:SERVER|CLIENT)\s*;[ \t]*\r?\n""").replace(result, "")
        result = result.replace(Regex("""\b(?:NetworkDirection\.)?PLAY_TO_SERVER\b"""), "PacketFlow.SERVERBOUND")
        result = result.replace(Regex("""\b(?:NetworkDirection\.)?PLAY_TO_CLIENT\b"""), "PacketFlow.CLIENTBOUND")
        result = Regex("""\b${Regex.escape(tableName)}\s*\(\s*Class\s*<\s*T\s*>\s+([A-Za-z_$][\w$]*)\s*,\s*Function\s*<\s*FriendlyByteBuf\s*,\s*T\s*>\s+([A-Za-z_$][\w$]*)\s*,\s*NetworkDirection\s+([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match ->
                "$tableName(CustomPacketPayload.Type<T> ${match.groupValues[1]}, StreamCodec<RegistryFriendlyByteBuf, T> ${match.groupValues[2]}, PacketFlow ${match.groupValues[3]})"
            }

        val protocol = Regex("""networkProtocolVersion\(\s*\(\)\s*->\s*([^)]+)\)""").find(result)?.groupValues?.get(1)?.trim()
            ?: Regex("""\b([A-Z_]*VERSION[A-Z_]*)\b""").find(result)?.groupValues?.get(1)
            ?: "\"1\""
        result = replaceNestedClass(result, Regex("""private\s+static\s+class\s+([A-Za-z_$][\w$]*)\s*<\s*T\s+extends\s+${Regex.escape(baseName)}\s*>\s*\{""")) { wrapperName, body ->
            val register = Regex("""\bvoid\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{[^}]*messageBuilder""").find(body)?.groupValues?.get(1) ?: "register"
            """	private static class $wrapperName<T extends $baseName> {
		private final CustomPacketPayload.Type<T> type;
		private final StreamCodec<RegistryFriendlyByteBuf, T> codec;
		private final PacketFlow direction;

		private $wrapperName(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, PacketFlow direction) {
			this.type = type;
			this.codec = codec;
			this.direction = direction;
		}

		private void $register(PayloadRegistrar registrar) {
			if (direction == PacketFlow.SERVERBOUND)
				registrar.playToServer(type, codec, (packet, context) -> packet.handle(context));
			else
				registrar.playToClient(type, codec, (packet, context) -> packet.handle(context));
		}
	}"""
        }
        result = replaceMethod(result, Regex("""public\s+static\s+void\s+registerPackets\s*\(\s*\)\s*\{""")) { body ->
            val loop = Regex("""for\s*\(\s*$tableName\s+([A-Za-z_$][\w$]*)\s*:\s*values\(\)\s*\)\s*\r?\n?\s*\1\.([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\(\)\s*;""").find(body)
            val field = loop?.groupValues?.get(2) ?: "packetType"
            val register = loop?.groupValues?.get(3) ?: "register"
            """public static void registerPackets(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar($protocol);
		for ($tableName packet : values())
			packet.$field.$register(registrar);
	}"""
        }
        result = Regex("""(?m)^[ \t]*private\s+static\s+SimpleChannel\s+[A-Za-z_$][\w$]*\s*;[ \t]*\r?\n""").replace(result, "")
        result = replaceMethod(result, Regex("""public\s+static\s+SimpleChannel\s+getChannel\s*\(\s*\)\s*\{""")) { "" }
        result = migrateNearHelper(result)
        listOf("net.minecraftforge.network.NetworkRegistry", "net.neoforged.neoforge.network.NetworkRegistry",
            "net.minecraftforge.network.simple.SimpleChannel", "net.neoforged.neoforge.network.simple.SimpleChannel",
            "net.minecraftforge.network.NetworkDirection", "net.neoforged.neoforge.network.NetworkDirection",
            "net.minecraftforge.network.PacketDistributor.TargetPoint", "net.neoforged.neoforge.network.PacketDistributor.TargetPoint")
            .forEach { result = JavaSourceText.removeImport(result, it) }
        legacyContextImports.forEach { result = JavaSourceText.removeImport(result, it) }
        listOf(PAYLOAD_IMPORT, STREAM_CODEC_IMPORT, REGISTRY_BUFFER_IMPORT, PACKET_FLOW_IMPORT, REGISTRAR_EVENT_IMPORT, REGISTRAR_IMPORT, DISTRIBUTOR_IMPORT)
            .forEach { result = JavaSourceText.addImport(result, it) }
        for (unused in listOf("java.util.function.Supplier" to "Supplier", "java.util.function.BiConsumer" to "BiConsumer", "java.util.function.Function" to "Function", "net.minecraft.network.FriendlyByteBuf" to "FriendlyByteBuf")) {
            val withoutImport = JavaSourceText.removeImport(result, unused.first)
            if (!Regex("""\b${unused.second}\b""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(withoutImport).replace(Regex("""(?m)^import .*$"""), ""))) result = withoutImport
        }
        return result
    }

    /** `getChannel().send(PacketDistributor.NEAR.with(TargetPoint.p(x, y, z, r, level.dimension())), msg)` */
    private fun migrateNearHelper(source: String): String {
        val near = Regex("""(?:[A-Za-z_$][\w$]*\.)?getChannel\(\)\s*\.send\(\s*PacketDistributor\.NEAR\.with\(\s*(?:PacketDistributor\.)?TargetPoint\.p\(\s*([^,]+),\s*([^,]+),\s*([^,]+),\s*([^,]+),\s*([A-Za-z_$][\w$]*)\.dimension\(\)\s*\)\s*\)\s*,\s*([^;]+?)\s*\)\s*;""")
        var result = near.replace(source) { match ->
            val (x, y, z, radius, level) = match.groupValues.drop(1).take(5).map { it.trim() }
            "PacketDistributor.sendToPlayersNear((ServerLevel) $level, null, $x, $y, $z, $radius, (CustomPacketPayload) ${match.groupValues[6].trim()});"
        }
        if (result != source) {
            result = result.replace(Regex("""\bObject\s+(message|packet|payload)\b"""), "CustomPacketPayload $1")
            result = JavaSourceText.addImport(result, "net.minecraft.server.level.ServerLevel")
        }
        return result
    }

    /** `Table.getChannel().send(...)` / `.sendToServer(...)` across the project. */
    private fun migrateChannelSends(source: String, tableName: String, targetHelpers: Map<String, String> = emptyMap()): String {
        if (!source.contains("getChannel()")) return source
        var result = source
        // `SimpleChannel channel = Table.getChannel();` makes `channel.` another way to say the channel
        val channelLocal = Regex("""(?m)^[ \t]*(?:final\s+)?(?:(?:[a-z][\w$]*\.)*SimpleChannel|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:${Regex.escape(tableName)}\.)?getChannel\(\)\s*;[ \t]*\r?\n""")
        val channelNames = channelLocal.findAll(JavaSourceText.maskCommentsAndLiterals(result)).map { it.groupValues[1] }.toSet()
        // `PacketTarget target = PacketDistributor.X.with(...);` stands for its initializer in a send
        val targetLocal = Regex("""(?m)^[ \t]*(?:final\s+)?(?:(?:PacketDistributor\.)?PacketTarget|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(PacketDistributor\.[A-Z_]+\.(?:with\([^;]*\)|noArg\(\s*\)))\s*;[ \t]*\r?\n""")
        val targetLocals = targetLocal.findAll(result).associate { it.groupValues[1] to it.groupValues[2] }
        val receivers = listOf("""(?:${Regex.escape(tableName)}\.)?getChannel\(\)""") + channelNames.map { """(?<![\w$.])${Regex.escape(it)}""" }
        val receiver = """(?:${receivers.joinToString("|")})\s*\."""
        result = Regex("""$receiver(?:sendToServer)\(""").replace(result, "PacketDistributor.sendToServer(")
        result = replaceSend(result, Regex("""${receiver}send\("""), targetHelpers + targetLocals)
        val code = { text: String -> JavaSourceText.maskCommentsAndLiterals(text) }
        for (name in channelNames) {
            val withoutDeclaration = Regex("""(?m)^[ \t]*(?:final\s+)?(?:(?:[a-z][\w$]*\.)*SimpleChannel|var)\s+${Regex.escape(name)}\s*=\s*(?:${Regex.escape(tableName)}\.)?getChannel\(\)\s*;[ \t]*\r?\n""").replace(result, "")
            if (!Regex("""(?<![\w$.])${Regex.escape(name)}(?![\w$])""").containsMatchIn(code(withoutDeclaration))) result = withoutDeclaration
        }
        for (name in targetLocals.keys) {
            val declaration = Regex("""(?m)^[ \t]*(?:final\s+)?(?:(?:PacketDistributor\.)?PacketTarget|var)\s+${Regex.escape(name)}\s*=\s*PacketDistributor\.[A-Z_]+\.(?:with\([^;]*\)|noArg\(\s*\))\s*;[ \t]*\r?\n""")
            val withoutDeclaration = declaration.replace(result, "")
            if (!Regex("""(?<![\w$.])${Regex.escape(name)}(?![\w$])""").containsMatchIn(code(withoutDeclaration))) result = withoutDeclaration
        }
        if (!Regex("""\bPacketTarget\b""").containsMatchIn(code(result).replace(Regex("""(?m)^import .*$"""), ""))) {
            listOf("net.minecraftforge.network.PacketDistributor.PacketTarget", "net.neoforged.neoforge.network.PacketDistributor.PacketTarget")
                .forEach { result = JavaSourceText.removeImport(result, it) }
        }
        if (result != source) result = JavaSourceText.addImport(result, DISTRIBUTOR_IMPORT)
        return result
    }

    private fun replaceSend(source: String, sendPattern: Regex, targetHelpers: Map<String, String> = emptyMap()): String {
        val builder = StringBuilder()
        var cursor = 0
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        while (true) {
            val match = sendPattern.find(masked, cursor) ?: break
            val openParen = match.range.last
            val closeParen = JavaSourceText.matchingParen(masked, openParen)
            if (closeParen < 0) break
            val args = JavaSourceText.splitTopLevelArgs(source.substring(openParen + 1, closeParen))
            val target = args.getOrNull(0)?.trim()?.let { targetHelpers[it.removePrefix("this.").removeSuffix("()")] ?: it }
            val replacement = if (args.size == 2 && target != null) distributorCall(target, args[1]) else null
            builder.append(source, cursor, match.range.first)
            if (replacement != null) {
                builder.append(replacement)
            } else {
                builder.append(source, match.range.first, closeParen + 1)
            }
            cursor = closeParen + 1
        }
        builder.append(source, cursor, source.length)
        return builder.toString()
    }

    /** The value a `with(...)` supplier yields: `() -> x` gives `x`, `this::m` gives `m()`, `a::m` gives `a.m()`. */
    private fun suppliedValue(supplier: String): String? {
        val trimmed = supplier.trim()
        Regex("""^\(\)\s*->\s*(.+)$""", RegexOption.DOT_MATCHES_ALL).find(trimmed)?.let { return it.groupValues[1].trim() }
        Regex("""^this::([A-Za-z_$][\w$]*)$""").find(trimmed)?.let { return "${it.groupValues[1]}()" }
        Regex("""^((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)::([A-Za-z_$][\w$]*)$""").find(trimmed)?.let { return "${it.groupValues[1]}.${it.groupValues[2]}()" }
        return null
    }

    private fun distributorCall(target: String, payload: String): String? {
        Regex("""^PacketDistributor\.([A-Z_]+)\.with\((.+)\)$""", RegexOption.DOT_MATCHES_ALL).find(target.trim())?.let { match ->
            val argument = suppliedValue(match.groupValues[2]) ?: return null
            return when (match.groupValues[1]) {
                "PLAYER" -> "PacketDistributor.sendToPlayer($argument, $payload)"
                "TRACKING_ENTITY" -> "PacketDistributor.sendToPlayersTrackingEntity($argument, $payload)"
                "TRACKING_ENTITY_AND_SELF" -> "PacketDistributor.sendToPlayersTrackingEntityAndSelf($argument, $payload)"
                "DIMENSION" -> "PacketDistributor.sendToPlayersInDimension($argument, $payload)"
                "TRACKING_CHUNK" -> Regex("""^(.+)\.getChunkAt\((.+)\)$""").find(argument)?.let {
                    "PacketDistributor.sendToPlayersTrackingChunk(${it.groupValues[1].trim()}, new net.minecraft.world.level.ChunkPos(${it.groupValues[2].trim()}), $payload)"
                } ?: "PacketDistributor.sendToPlayersTrackingChunk((net.minecraft.server.level.ServerLevel) $argument.getLevel(), $argument.getPos(), $payload)"
                else -> null
            }
        }
        if (Regex("""^PacketDistributor\.ALL\.noArg\(\s*\)$""").matches(target.trim())) return "PacketDistributor.sendToAllPlayers($payload)"
        if (Regex("""^PacketDistributor\.SERVER\.noArg\(\s*\)$""").matches(target.trim())) return "PacketDistributor.sendToServer($payload)"
        return null
    }

    /** `Table.registerPackets();` in the mod constructor becomes a mod-bus listener. */
    private fun migrateRegistrationCall(source: String, tableName: String): String {
        val unit = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        ).parse(source).result.orElse(null) ?: return source
        val lineStarts = mutableListOf(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') lineStarts += index + 1
        }
        fun offset(node: Node): Int = node.range
            .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
            .orElse(-1)
        fun endOffset(node: Node): Int = node.range
            .map { lineStarts[it.end.line - 1] + it.end.column }
            .orElse(-1)
        val nativeTypes = NativeRegistrationTypes(unit)

        val edits = unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
            if (call.nameAsString != "registerPackets" || call.arguments.isNotEmpty()) {
                return@mapNotNull null
            }
            val scope = call.scope.orElse(null)
            if (scope == null || !scope.isNameExpr ||
                scope.asNameExpr().nameAsString != tableName
            ) {
                return@mapNotNull null
            }
            val statement = call.findAncestor(ExpressionStmt::class.java)
                .orElse(null)
                ?.takeIf { it.expression === call }
                ?: return@mapNotNull null
            val executable = enclosingCallable(call) ?: return@mapNotNull null
            val bus = provenModEventBusName(executable, call, nativeTypes, ::offset)
                ?: return@mapNotNull null
            val start = offset(statement)
            val end = endOffset(statement)
            if (start < 0 || end <= start) return@mapNotNull null
            start until end to "$bus.addListener($tableName::registerPackets);"
        }
        return edits.sortedByDescending { it.first.first }.fold(source) { text, edit ->
            text.replaceRange(edit.first, edit.second)
        }
    }

    private fun enclosingCallable(node: Node): CallableDeclaration<*>? =
        generateSequence(node.parentNode.orElse(null)) {
            it.parentNode.orElse(null)
        }.filterIsInstance<CallableDeclaration<*>>().firstOrNull()

    private fun provenModEventBusName(
        executable: CallableDeclaration<*>,
        call: MethodCallExpr,
        nativeTypes: NativeRegistrationTypes,
        offset: (Node) -> Int
    ): String? {
        val callOffset = offset(call)
        val localCandidates = executable.findAll(VariableDeclarator::class.java)
            .asSequence()
            .filter { enclosingCallable(it) === executable }
            .filter { nativeTypes.isEventBus(it.type.asString()) }
            .filter { offset(it) in 0 until callOffset }
            .filter { visibleAt(it, call, offset) }
            .filter { variable ->
                variable.initializer.orElse(null)?.let {
                    provenModEventBusExpression(
                        it.toString(),
                        executable,
                        variable,
                        nativeTypes,
                        offset
                    )
                } == true
            }
            .filterNot { reassignedBefore(executable, it.nameAsString, offset(it), callOffset, offset) }
            .sortedBy { offset(it) }
            .map { it.nameAsString }
            .toList()
        if (localCandidates.isNotEmpty()) return localCandidates.last()

        if (executable is ConstructorDeclaration &&
            enclosingModType(executable, nativeTypes) != null
        ) {
            return executable.parameters
                .filter { nativeTypes.isEventBus(it.type.asString()) }
                .filterNot {
                    reassignedBefore(
                        executable,
                        it.nameAsString,
                        offset(it),
                        callOffset,
                        offset
                    )
                }
                .map { it.nameAsString }
                .distinct()
                .singleOrNull()
        }
        return null
    }

    private fun provenModEventBusExpression(
        expression: String,
        executable: CallableDeclaration<*>,
        declaration: VariableDeclarator,
        nativeTypes: NativeRegistrationTypes,
        offset: (Node) -> Int
    ): Boolean {
        val compact = expression.replace(Regex("""\s+"""), "")
        val loadingContext = Regex(
            """^((?:[\w$.]+\.)?FMLJavaModLoadingContext)""" +
                """\.get\(\)\.getModEventBus\(\)$"""
        ).matchEntire(compact)?.groupValues?.get(1)
        if (loadingContext != null && nativeTypes.isLoadingContext(loadingContext)) {
            return true
        }

        val modType = enclosingModType(executable, nativeTypes)
        val ownerId = modType?.annotations
            ?.firstOrNull { nativeTypes.isModAnnotation(it.nameAsString) }
            ?.let { annotation ->
                when {
                    annotation.isSingleMemberAnnotationExpr ->
                        annotation.asSingleMemberAnnotationExpr().memberValue.toString()
                    annotation.isNormalAnnotationExpr ->
                        annotation.asNormalAnnotationExpr().pairs
                            .firstOrNull { it.nameAsString == "value" }
                            ?.value
                            ?.toString()
                    else -> null
                }
            }
            ?.replace(Regex("""\s+"""), "")
        val ownerGetter = Regex(
            """^((?:[\w$.]+\.)?ModList)\.get\(\)""" +
                """\.getModContainerById\((.+)\)\.orElseThrow\(\)\.getEventBus\(\)$"""
        ).matchEntire(compact)
        if (ownerGetter != null && ownerId != null &&
            nativeTypes.isModList(ownerGetter.groupValues[1]) &&
            ownerGetter.groupValues[2] == ownerId
        ) {
            return true
        }

        val container = Regex(
            """^([A-Za-z_$][\w$]*)\.getEventBus\(\)$"""
        ).matchEntire(compact)?.groupValues?.get(1) ?: return false
        if (executable !is ConstructorDeclaration || modType == null) return false
        return executable.parameters
            .filter { nativeTypes.isModContainer(it.type.asString()) }
            .map { it.nameAsString }
            .contains(container)
    }

    private fun reassignedBefore(
        executable: CallableDeclaration<*>,
        name: String,
        declarationOffset: Int,
        callOffset: Int,
        offset: (Node) -> Int
    ): Boolean = executable.findAll(AssignExpr::class.java).any { assignment ->
        enclosingCallable(assignment) === executable &&
            offset(assignment) in (declarationOffset + 1) until callOffset &&
            assignment.target.isNameExpr &&
            assignment.target.asNameExpr().nameAsString == name
    }

    private fun visibleAt(
        declaration: VariableDeclarator,
        use: Node,
        offset: (Node) -> Int
    ): Boolean {
        val statement = declaration.findAncestor(ExpressionStmt::class.java)
            .orElse(null)
            ?: return false
        val block = statement.findAncestor(BlockStmt::class.java).orElse(null)
            ?: return false
        if (offset(statement) !in 0 until offset(use)) return false
        return generateSequence<Node>(use) { node -> node.parentNode.orElse(null) }
            .any { ancestor -> ancestor === block }
    }

    private fun enclosingModType(
        executable: CallableDeclaration<*>,
        nativeTypes: NativeRegistrationTypes
    ): TypeDeclaration<*>? =
        executable.findAncestor(TypeDeclaration::class.java)
            .orElse(null)
            ?.takeIf { type ->
                type.annotations.any { nativeTypes.isModAnnotation(it.nameAsString) }
            }

    private class NativeRegistrationTypes(unit: CompilationUnit) {
        private val explicitImports = unit.imports
            .filter { !it.isStatic && !it.isAsterisk }
            .associate { it.name.identifier to it.nameAsString }
        private val declaredTypes = unit.findAll(TypeDeclaration::class.java)
            .mapTo(mutableSetOf()) { it.nameAsString }

        fun isEventBus(type: String): Boolean = isCanonical(
            type,
            setOf(
                "net.neoforged.bus.api.IEventBus",
                "net.minecraftforge.eventbus.api.IEventBus"
            )
        )

        fun isModContainer(type: String): Boolean = isCanonical(
            type,
            setOf(
                "net.neoforged.fml.ModContainer",
                "net.minecraftforge.fml.ModContainer"
            )
        )

        fun isModAnnotation(name: String): Boolean = isCanonical(
            name,
            setOf(
                "net.neoforged.fml.common.Mod",
                "net.minecraftforge.fml.common.Mod"
            )
        )

        fun isLoadingContext(name: String): Boolean = isCanonical(
            name,
            setOf(
                "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext",
                "net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext"
            )
        )

        fun isModList(name: String): Boolean = isCanonical(
            name,
            setOf(
                "net.neoforged.fml.ModList",
                "net.minecraftforge.fml.ModList"
            )
        )

        private fun isCanonical(name: String, canonicalNames: Set<String>): Boolean {
            val value = name.trim()
            if (value in canonicalNames) return true
            if ('.' in value || value in declaredTypes) return false
            return explicitImports[value] in canonicalNames
        }
    }

    private fun replaceNestedClass(source: String, header: Regex, replacement: (name: String, body: String) -> String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val match = header.find(masked) ?: return source
        val openBrace = match.range.last
        val closeBrace = JavaSourceText.matchingBrace(masked, openBrace)
        if (closeBrace < 0) return source
        val lineStart = source.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
        return source.substring(0, lineStart) + replacement(match.groupValues[1], source.substring(openBrace + 1, closeBrace)) + source.substring(closeBrace + 1)
    }

    private fun replaceMethod(source: String, header: Regex, replacement: (body: String) -> String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val match = header.find(masked) ?: return source
        val openBrace = match.range.last
        val closeBrace = JavaSourceText.matchingBrace(masked, openBrace)
        if (closeBrace < 0) return source
        val text = replacement(source.substring(openBrace + 1, closeBrace))
        if (text.isEmpty()) {
            val lineStart = source.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            var end = closeBrace + 1
            while (end < source.length && source[end] != '\n') end++
            if (end < source.length) end++
            return source.removeRange(lineStart, end)
        }
        return source.substring(0, match.range.first) + text + source.substring(closeBrace + 1)
    }

    private fun write(file: Path, original: String, migrated: String, dryRun: Boolean, changes: MutableList<Change>, description: String): Boolean {
        if (migrated == original) return false
        if (!dryRun) file.writeText(migrated)
        changes += Change(file, 1, description, "SimpleChannel packet", "CustomPacketPayload", Confidence.HIGH, RULE_ID)
        return true
    }
}
