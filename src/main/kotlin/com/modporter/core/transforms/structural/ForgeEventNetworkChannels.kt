package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forge's `EventNetworkChannel` carried raw bytes on a channel id: a listener of
 * `NetworkEvent.ClientCustomPayloadEvent` read what clients sent, and the mod sent
 * `new ClientboundCustomPayloadPacket(id, buffer)` itself. NeoForge 1.21.1 only moves typed
 * payloads, so each such channel becomes a payload record holding the same bytes on the same id
 * (written and read raw, so the wire format is unchanged: EngineHub's WorldEditCUI protocol still
 * reads `worldedit:cui` that way), registered on `RegisterPayloadHandlersEvent`:
 * - optional when the channel accepted absent or vanilla peers (its version check mentions
 *   `ABSENT`/`ACCEPTVANILLA`), required otherwise;
 * - the listener takes the payload and context: the sender is the context player, the buffer wraps
 *   the payload bytes, `setPacketHandled` goes; the other direction gets a no-op handler, as Forge
 *   ignored packets nobody listened to;
 * - sends on the same id wrap the buffer's bytes in the payload.
 * The channel id comes from `ChannelBuilder.named(id)` in the field initialiser or in the project
 * helper it calls (its parameters substituted). Any other use of the listener's event, or of the
 * channel field, leaves the whole channel as it is.
 */
internal object ForgeEventNetworkChannels {
    const val RULE_ID = "struct-forge-event-network-channels"
    private const val ID = """[A-Za-z_$][\w$]*"""

    private data class Channel(
        val file: Path,
        val owner: String,
        val field: String,
        val listener: String,
        val id: String,
        val optional: Boolean,
        // imports the id expression needs where it came from (a helper in another file)
        val idImports: List<String>
    )

    fun migrate(projectDir: Path, modId: String, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val channels = sources.mapNotNull { (file, source) -> channel(file, source, sources) }
        if (channels.isEmpty()) return emptyList()
        val texts = sources.toMutableMap()
        val changes = mutableListOf<Change>()
        for (channel in channels) {
            val migrated = migrateOwner(texts.getValue(channel.file), channel, modId) ?: continue
            texts[channel.file] = migrated
            changes += Change(channel.file, 0, "Forge event channel ${channel.owner}.${channel.field} becomes a raw-bytes payload on ${channel.id}",
                "EventNetworkChannel + NetworkEvent.ClientCustomPayloadEvent", "CustomPacketPayload registered on RegisterPayloadHandlersEvent",
                Confidence.HIGH, RULE_ID)
            for ((file, source) in texts.toMap()) {
                val sent = migrateSends(source, channel)
                if (sent != source) {
                    texts[file] = sent
                    changes += Change(file, 0, "Sends on ${channel.id} carry the ${channel.owner} payload", "new ClientboundCustomPayloadPacket(id, buffer)",
                        "new ClientboundCustomPayloadPacket(payload)", Confidence.HIGH, RULE_ID)
                }
            }
        }
        // channel helpers nothing calls any more
        for ((file, source) in texts.toMap()) {
            val pruned = pruneChannelBuilders(source, texts.filterKeys { it != file }.values)
            if (pruned == source) continue
            if (pruned.isBlank()) {
                if (!dryRun) file.deleteIfExists()
                texts.remove(file)
            } else {
                texts[file] = pruned
            }
            changes += Change(file, 0, "Channel builder helpers without callers go", "NetworkRegistry.ChannelBuilder helpers", "(removed)", Confidence.HIGH, RULE_ID)
        }
        if (!dryRun) texts.forEach { (file, text) -> if (text != sources[file]) file.writeText(text) }
        return changes
    }

    private fun channel(file: Path, source: String, sources: Map<Path, String>): Channel? {
        if (!source.contains("EventNetworkChannel") || !source.contains("ClientCustomPayloadEvent")) return null
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val owner = Regex("""\bclass\s+($ID)""").find(masked)?.groupValues?.get(1) ?: return null
        val field = Regex("""\bEventNetworkChannel\s+($ID)\s*=\s*([^;]+);""").find(masked) ?: return null
        val name = field.groupValues[1]
        val initializer = source.substring(field.groups[2]!!.range).trim()
        val listener = Regex("""\b${Regex.escape(name)}\s*\.\s*addListener\(\s*(?:$ID)::($ID)\s*\)\s*;""").find(masked)?.groupValues?.get(1) ?: return null
        val (id, optional, imports) = channelId(initializer, sources.values) ?: return null
        return Channel(file, owner, name, listener, id, optional, imports)
    }

    /** The channel id expression, whether absent/vanilla peers were accepted, and the imports the id needs. */
    private fun channelId(initializer: String, sources: Collection<String>): Triple<String, Boolean, List<String>>? {
        named(initializer)?.let { return Triple(it, lenient(initializer), emptyList()) }
        // a project helper building the channel: Helper.build(args).eventNetworkChannel()
        val call = Regex("""^(?:$ID\.)*($ID)\(([^;]*)\)\s*\.\s*eventNetworkChannel\(\)$""").find(initializer) ?: return null
        val method = call.groupValues[1]
        val args = JavaSourceText.splitTopLevelArgs(call.groupValues[2]).map { it.trim() }
        for (source in sources) {
            val header = Regex("""\bChannelBuilder\s+${Regex.escape(method)}\s*\(([^)]*)\)\s*\{""").find(source) ?: continue
            val close = JavaSourceText.matchingBrace(JavaSourceText.maskCommentsAndLiterals(source), header.range.last)
            if (close < 0) continue
            val params = JavaSourceText.splitTopLevelArgs(header.groupValues[1]).map { it.trim().substringAfterLast(' ') }
            if (params.size != args.size) continue
            val id = named(source.substring(header.range.last, close)) ?: continue
            val substituted = params.zip(args).fold(id) { text, (param, arg) -> text.replace(Regex("""(?<![\w$.])${Regex.escape(param)}(?![\w$])"""), arg) }
            val imports = Regex("""(?m)^import\s+([\w.]+)\s*;""").findAll(source).map { it.groupValues[1] }
                .filter { Regex("""(?<![\w$.])${Regex.escape(it.substringAfterLast('.'))}(?![\w$])""").containsMatchIn(substituted) }
                .toList()
            return Triple(substituted, lenient(source), imports)
        }
        return null
    }

    private fun named(text: String): String? {
        val start = Regex("""ChannelBuilder\s*\.\s*named\(""").find(text) ?: return null
        val close = JavaSourceText.matchingParen(text, start.range.last)
        return if (close < 0) null else text.substring(start.range.last + 1, close).trim()
    }

    private fun lenient(text: String): Boolean = Regex("""\b(?:ABSENT|ACCEPTVANILLA)\b""").containsMatchIn(text)

    private fun payloadName(field: String): String =
        field.lowercase().split('_').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Payload"

    private fun migrateOwner(source: String, channel: Channel, modId: String): String? {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val listener = Regex("""\b(?:public\s+|private\s+|protected\s+)?static\s+void\s+${Regex.escape(channel.listener)}\s*\(\s*(?:[\w.]+\.)?(?:NetworkEvent\.)?ClientCustomPayloadEvent\s+($ID)\s*\)\s*\{""").find(masked)
            ?: return null
        val open = listener.range.last
        val close = JavaSourceText.matchingBrace(masked, open)
        if (close < 0) return null
        val event = Regex.escape(listener.groupValues[1])
        val payload = payloadName(channel.field)
        var body = source.substring(open + 1, close)
        body = body.replace(Regex("""(?m)^[ \t]*$event\s*\.\s*getSource\(\)\s*\.\s*get\(\)\s*\.\s*setPacketHandled\(\s*true\s*\)\s*;[ \t]*\r?\n"""), "")
        body = body.replace(Regex("""(?<![\w$.])$event\s*\.\s*getSource\(\)\s*\.\s*get\(\)\s*\.\s*getSender\(\)"""), "((net.minecraft.server.level.ServerPlayer) context.player())")
        body = body.replace(Regex("""(?<![\w$.])$event\s*\.\s*getSource\(\)\s*\.\s*get\(\)\s*\.\s*enqueueWork\("""), "context.enqueueWork(")
        body = body.replace(Regex("""(?<![\w$.])$event\s*\.\s*getPayload\(\)"""), "new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()))")
        if (Regex("""(?<![\w$.])$event(?![\w$])""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(body))) return null
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        var result = source.substring(0, listener.range.first) +
            "public static void ${channel.listener}($payload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {" +
            body + source.substring(close)
        // the channel field and its listener registration
        val current = JavaSourceText.maskCommentsAndLiterals(result)
        val fieldDecl = Regex("""(?m)^[ \t]*(?:(?:private|public|protected|static|final)\s+)*EventNetworkChannel\s+${Regex.escape(channel.field)}\s*=[^;]*;[ \t]*\r?\n""").find(current) ?: return null
        result = result.removeRange(fieldDecl.range)
        val registration = Regex("""(?m)^[ \t]*${Regex.escape(channel.field)}\s*\.\s*addListener\([^;]*\)\s*;[ \t]*\r?\n""").find(JavaSourceText.maskCommentsAndLiterals(result)) ?: return null
        result = result.removeRange(registration.range)
        if (Regex("""(?<![\w$.])${Regex.escape(channel.field)}(?![\w$])""").containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result))) return null
        val registrar = "event.registrar(\"1\")" + if (channel.optional) ".optional()" else ""
        val members = listOf(
            "",
            "    public record $payload(byte[] data) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {",
            "        public static final Type<$payload> TYPE = new Type<>(${channel.id});",
            "        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, $payload> STREAM_CODEC = net.minecraft.network.codec.StreamCodec.of(",
            "            (buffer, payload) -> buffer.writeBytes(payload.data()),",
            "            buffer -> {",
            "                byte[] data = new byte[buffer.readableBytes()];",
            "                buffer.readBytes(data);",
            "                return new $payload(data);",
            "            });",
            "",
            "        @Override",
            "        public Type<$payload> type() {",
            "            return TYPE;",
            "        }",
            "    }",
            "",
            "    @net.neoforged.fml.common.EventBusSubscriber(modid = \"$modId\", bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)",
            "    public static final class ${payload}Registration {",
            "        @net.neoforged.bus.api.SubscribeEvent",
            "        public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {",
            "            $registrar.playBidirectional($payload.TYPE, $payload.STREAM_CODEC,",
            "                new net.neoforged.neoforge.network.handling.DirectionalPayloadHandler<>((payload, context) -> { }, ${channel.owner}::${channel.listener}));",
            "        }",
            "    }"
        ).joinToString(newline)
        val classEnd = result.trimEnd().lastIndexOf('}')
        result = result.substring(0, classEnd).trimEnd() + newline + members + newline + "}" + newline
        listOf("net.neoforged.neoforge.network.event.EventNetworkChannel", "net.minecraftforge.network.event.EventNetworkChannel",
            "net.neoforged.neoforge.network.NetworkEvent", "net.minecraftforge.network.NetworkEvent").forEach { result = JavaSourceText.removeImport(result, it) }
        channel.idImports.forEach { result = JavaSourceText.addImport(result, it) }
        return result
    }

    /** `new ClientboundCustomPayloadPacket(<the channel id>, buffer)` carries the channel's payload. */
    private fun migrateSends(source: String, channel: Channel): String {
        if (!source.contains("ClientboundCustomPayloadPacket(")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val normalizedId = channel.id.replace(Regex("""\s+"""), "")
        // a constant of the channel's class may be named qualified from elsewhere
        val ids = if (Regex("""^$ID$""").matches(normalizedId)) setOf(normalizedId, "${channel.owner}.$normalizedId") else setOf(normalizedId)
        val edits = Regex("""\bnew\s+(?:[\w.]+\.)?ClientboundCustomPayloadPacket\(""").findAll(masked).mapNotNull { match ->
            val close = JavaSourceText.matchingParen(masked, match.range.last)
            if (close < 0) return@mapNotNull null
            val args = JavaSourceText.splitTopLevelArgs(source.substring(match.range.last + 1, close)).map { it.trim() }
            if (args.size != 2 || args[0].replace(Regex("""\s+"""), "") !in ids) return@mapNotNull null
            (match.range.first..close) to
                "new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new ${channel.owner}.${payloadName(channel.field)}(io.netty.buffer.ByteBufUtil.getBytes(${args[1]})))"
        }.toList()
        if (edits.isEmpty()) return source
        var result = edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
        val packageName = JavaSourceText.packageName(JavaSourceText.maskCommentsAndLiterals(source))
        val ownerPackage = JavaSourceText.packageName(JavaSourceText.maskCommentsAndLiterals(channel.file.readText()))
        if (ownerPackage != null && ownerPackage != packageName) result = JavaSourceText.addImport(result, "$ownerPackage.${channel.owner}")
        // the packet class lives in protocol.common in 1.21.1 (member table)
        if (result.contains("import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;")) {
            result = JavaSourceText.removeImport(result, "net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket")
            result = JavaSourceText.addImport(result, "net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket")
        }
        return result
    }

    /** Removes static methods returning a Forge `ChannelBuilder` (and private helpers only they used) that nothing calls. */
    private fun pruneChannelBuilders(source: String, others: Collection<String>): String {
        if (!source.contains("ChannelBuilder")) return source
        var result = source
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(result)
            val methods = Regex("""(?m)^[ \t]*(?:(?:private|public|protected|static|final)\s+)*([\w.<>?,\s]+?)\s+($ID)\s*\([^)]*\)\s*\{""").findAll(masked).toList()
            val removable = methods.firstOrNull { method ->
                val name = method.groupValues[2]
                val type = method.groupValues[1]
                val referenced = Regex("""(?<![\w$])${Regex.escape(name)}\s*\(""").findAll(masked).count() > 1 ||
                    others.any { Regex("""\.\s*${Regex.escape(name)}\s*\(""").containsMatchIn(it) }
                !referenced && (type.contains("ChannelBuilder") || type.contains("Predicate")) &&
                    JavaSourceText.matchingBrace(masked, method.range.last) > 0 &&
                    masked.substring(method.range.first, JavaSourceText.matchingBrace(masked, method.range.last)).contains("NetworkRegistry")
            } ?: break
            val close = JavaSourceText.matchingBrace(masked, removable.range.last)
            val lineEnd = result.indexOf('\n', close).let { if (it < 0) result.length else it + 1 }
            result = result.removeRange(removable.range.first, lineEnd)
        }
        if (result == source) return source
        // a class left with nothing but a private constructor existed only for the helpers
        val masked = JavaSourceText.maskCommentsAndLiterals(result)
        val body = Regex("""\bclass\s+$ID[^{]*\{([\s\S]*)}\s*$""").find(masked)?.groupValues?.get(1)
        if (body != null && Regex("""^\s*(?:private\s+$ID\s*\(\s*\)\s*\{\s*}\s*)?$""").matches(body)) return ""
        return result
    }
}
