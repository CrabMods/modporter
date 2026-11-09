package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticComponentSerializationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `static component initializers encode literals without an instance registry accessor`() {
        val file = directory.resolve("src/main/java/example/Display.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public class Display extends BlockEntity {
                private static final String EMPTY = Component.Serializer.toJson(Component.literal(""));
                private static String value;
                static { value = Component.Serializer.toJson(Component.literal("text")); }
                String show() { return Component.Serializer.toJson(Component.literal("now")); }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        val source = file.readText()
        val field = source.substringAfter("String EMPTY =").substringBefore(';')
        assertTrue(field.contains("ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE"), source)
        assertFalse(field.contains("this."), source)
        assertTrue(source.contains("Component.literal(\"now\"), this.getLevel().registryAccess()"), source)
    }
}
