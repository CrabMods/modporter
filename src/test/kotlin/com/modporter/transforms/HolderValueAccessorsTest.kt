package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class HolderValueAccessorsTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `holder calls and references use value while supplier shadows keep get`() {
        val file = tempDir.resolve("src/main/java/com/example/Sounds.java")
        file.parent.createDirectories()
        file.writeText("""
            package com.example;
            import java.util.function.Supplier;
            import net.minecraft.core.Holder;
            import net.minecraft.sounds.SoundEvent;
            public class Sounds {
                Holder<SoundEvent> event;
                Supplier<SoundEvent> supplier;
                SoundEvent read() {
                    return event.get();
                }
                Supplier<SoundEvent> reference() {
                    return event::get;
                }
                SoundEvent local(Supplier<SoundEvent> event) {
                    return event.get();
                }
                SoundEvent supplied() {
                    return supplier.get();
                }
            }
        """.trimIndent())
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("return event.value();"), result)
        assertTrue(result.contains("return event::value;"), result)
        assertTrue(result.contains("return event.get();"), result)
        assertTrue(result.contains("return supplier.get();"), result)
    }
}
