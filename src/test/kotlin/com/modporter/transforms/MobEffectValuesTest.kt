package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobEffectValuesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `effect behavior unwraps holders and scoped raw effects stay raw`() {
        val file = directory.resolve("src/main/java/example/Effects.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.core.Holder;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffects;
            import net.minecraft.world.effect.MobEffectInstance;
            class Effects {
                void use(Holder<MobEffect> effect, MobEffectInstance instance) {
                    effect.isInstantenous();
                    instance.getEffect().applyInstantenousEffect(null, null, entity, 1, 0.5);
                    MobEffects.GLOWING.getColor();
                    { MobEffect effect; effect.getCategory(); }
                }
            }
        """.trimIndent())
        StructuralRefactorPass().apply(directory)
        val result = file.readText()
        assertTrue(result.contains("effect.value().isInstantenous()"), result)
        assertTrue(result.contains("instance.getEffect().value().applyInstantenousEffect("), result)
        assertTrue(result.contains("MobEffects.GLOWING.value().getColor()"), result)
        assertTrue(result.contains("MobEffect effect; effect.getCategory()"), result)
        StructuralRefactorPass().apply(directory)
        assertEquals(result, file.readText())
    }
}
