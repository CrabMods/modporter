package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class ConstructorReceiverProvidersTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `constructed receivers retain new while resolving their provider signatures`() {
        val file = directory.resolve("src/main/java/example/Factory.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            class Factory {
                CompoundTag save(Level level) {
                    return new Parcel().serializeNBT();
                }
                CompoundTag ordinary(Level level) {
                    return new Other().serializeNBT();
                }
            }
            class Parcel {
                public CompoundTag serializeNBT(HolderLookup.Provider registries) { return new CompoundTag(); }
            }
            class Other {
                public CompoundTag serializeNBT() { return new CompoundTag(); }
            }
        """.trimIndent())
        val result = StructuralRefactorPass().apply(directory)
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        val source = file.readText()
        assertTrue(source.contains("new Parcel().serializeNBT(level.registryAccess())"), source)
        assertTrue(source.contains("new Other().serializeNBT()"), source)
    }
}
