package com.modporter.transforms

import com.modporter.core.transforms.structural.PacketTablePayloads
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NestedPacketIdentityTest {
    @TempDir lateinit var directory: Path
    private fun java(name: String, text: String): Path {
        val path = directory.resolve("src/main/java/example/$name.java")
        path.parent.createDirectories()
        path.writeText(text.trimIndent())
        return path
    }

    @Test
    fun `same nested packet name in different owners retains distinct ids and imported table references`() {
        val base = java("Envelope", """
            package example;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
            import net.neoforged.neoforge.network.handling.IPayloadContext;
            public abstract class Envelope implements CustomPacketPayload {
                public abstract void write(RegistryFriendlyByteBuf buffer);
                public abstract boolean handle(IPayloadContext context);
            }
        """)
        val files = listOf("First", "Second").map { owner ->
            java(owner, """
                package example;
                import net.minecraft.network.RegistryFriendlyByteBuf;
                import net.neoforged.neoforge.network.handling.IPayloadContext;
                public abstract class $owner extends Envelope {
                    public static class Reply extends Envelope {
                        public Reply(RegistryFriendlyByteBuf buffer) {}
                        public void write(RegistryFriendlyByteBuf buffer) {}
                        public boolean handle(IPayloadContext context) { return true; }
                    }
                }
            """)
        }
        val table = java("Routes", """
            package example;
            import example.First.Reply;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
            import net.minecraft.network.codec.StreamCodec;
            public enum Routes {
                FIRST(Reply.class, Reply::new),
                SECOND(Second.Reply.class, Second.Reply::new);
                CustomPacketPayload.Type<?> type;
                StreamCodec<RegistryFriendlyByteBuf, ?> codec;
            }
        """)
        PacketTablePayloads.migrate(directory, "example", false)
        assertTrue(files[0].readText().contains("\"first_reply\""), files[0].readText())
        assertTrue(files[1].readText().contains("\"second_reply\""), files[1].readText())
        for (file in files) {
            assertTrue(file.readText().contains("Type<Reply> TYPE"), file.readText())
            assertTrue(file.readText().contains("StreamCodec<RegistryFriendlyByteBuf, Reply> STREAM_CODEC"), file.readText())
        }
        assertTrue(table.readText().contains("FIRST(Reply.TYPE, Reply.STREAM_CODEC)"), table.readText())
        assertTrue(table.readText().contains("SECOND(Second.Reply.TYPE, Second.Reply.STREAM_CODEC)"), table.readText())
        assertFalse(base.readText().contains("CustomPacketPayload, CustomPacketPayload"), base.readText())
        val before = (files + listOf(table, base)).associateWith { it.readText() }
        PacketTablePayloads.migrate(directory, "example", false)
        for ((file, text) in before) assertEquals(text, file.readText())
    }
}
