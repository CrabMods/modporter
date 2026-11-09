package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class RecordRegistryValueArgumentsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `raw record components unwrap proven holder arguments while holder components stay holders`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        root.resolve("Actions.java").writeText("""
            package example;
            import net.minecraft.core.Holder;
            import net.minecraft.world.effect.MobEffect;
            public class Actions {
                public record Raw(MobEffect effect, int ticks) {}
                public record Held(Holder<MobEffect> effect) {}
            }
        """.trimIndent())
        val file = root.resolve("Use.java")
        file.writeText("""
            package example;
            import example.Actions.Raw;
            import example.Actions.Held;
            import net.minecraft.world.effect.MobEffects;
            class Use {
                Object raw() { return new Raw(MobEffects.GLOWING, 40); }
                Object held() { return new Held(MobEffects.GLOWING); }
            }
        """.trimIndent())
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        assertTrue(file.readText().contains("new Raw((MobEffects.GLOWING).value(), 40)"), file.readText())
        assertTrue(file.readText().contains("new Held(MobEffects.GLOWING)"), file.readText())
    }
}
