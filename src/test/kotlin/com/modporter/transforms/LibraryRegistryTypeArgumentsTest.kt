package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** Registrate entries that grew a registry-type parameter (`RegistryEntry<R, S extends R>`) name the registry from evidence. */
class LibraryRegistryTypeArgumentsTest {

    @TempDir
    lateinit var tempDir: Path

    private val srcDir: Path get() = tempDir.resolve("src/main/java/com/example")

    private fun java(name: String, text: String) {
        srcDir.createDirectories()
        srcDir.resolve("$name.java").writeText(text.trimIndent() + "\n")
    }

    private fun read(name: String): String = srcDir.resolve("$name.java").readText()

    @Test
    fun `entries of a project registry and of platform registries name their registry type`() {
        java("DisplaySource", """
            package com.example;

            public abstract class DisplaySource {
            }
        """)
        java("SingleLineDisplaySource", """
            package com.example;

            public abstract class SingleLineDisplaySource extends DisplaySource {
            }
        """)
        java("ScoreboardDisplaySource", """
            package com.example;

            public class ScoreboardDisplaySource extends SingleLineDisplaySource {
            }
        """)
        java("StorageType", """
            package com.example;

            public abstract class StorageType<T> {
            }
        """)
        java("ModRegistries", """
            package com.example;

            import net.minecraft.core.Registry;
            import net.minecraft.resources.ResourceKey;

            public class ModRegistries {
                public static final ResourceKey<Registry<DisplaySource>> DISPLAY_SOURCE = null;
                public static final ResourceKey<Registry<StorageType<?>>> STORAGE_TYPE = null;
            }
        """)
        java("BeltBlock", """
            package com.example;

            import net.minecraft.world.level.block.Block;

            public class BeltBlock extends Block {
                public BeltBlock(Properties properties) {
                    super(properties);
                }
            }
        """)
        java("Entries", """
            package com.example;

            import com.tterrag.registrate.util.entry.RegistryEntry;
            import net.minecraft.core.Registry;
            import net.minecraft.resources.ResourceKey;

            public class Entries {
                public static final RegistryEntry<ScoreboardDisplaySource> SCOREBOARD = simple("scoreboard");
                public static final RegistryEntry<BeltBlock> BELT = null;
                public static final RegistryEntry<?> ANY = null;
                public static final RegistryEntry<Unrelated> UNKNOWN = null;

                private static <T extends DisplaySource> RegistryEntry<T> simple(String name) {
                    return null;
                }

                private static <T extends StorageType<?>> RegistryEntry<T> storage(String name) {
                    return null;
                }

                protected <R, T extends R> RegistryEntry<T> accept(String name, ResourceKey<? extends Registry<R>> type) {
                    RegistryEntry<T> entry = null;
                    return entry;
                }
            }

            class Unrelated {
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val entries = read("Entries")
        // the nearest declared registry element above the entry type, through the project hierarchy
        assertTrue(entries.contains("RegistryEntry<DisplaySource, ScoreboardDisplaySource> SCOREBOARD"), entries)
        // a platform registry (Registries.BLOCK) reached through the 1.21.1 class hierarchy
        assertTrue(entries.contains("RegistryEntry<net.minecraft.world.level.block.Block, BeltBlock> BELT"), entries)
        // type variables take their bound's registry, or the bounding type variable itself
        assertTrue(entries.contains("<T extends DisplaySource> RegistryEntry<DisplaySource, T> simple("), entries)
        assertTrue(entries.contains("<T extends StorageType<?>> RegistryEntry<StorageType<?>, T> storage("), entries)
        assertTrue(entries.contains("<R, T extends R> RegistryEntry<R, T> accept("), entries)
        assertTrue(entries.contains("RegistryEntry<R, T> entry = null;"), entries)
        // no evidence: the registry slot stays open
        assertTrue(entries.contains("RegistryEntry<?, ?> ANY"), entries)
        assertTrue(entries.contains("RegistryEntry<?, Unrelated> UNKNOWN"), entries)
    }

    @Test
    fun `concrete block bounds select the registry while abstract bounds remain unchanged`() {
        java("Registrate", """
            package com.example;

            import java.util.function.Function;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class Registrate {
                public <T extends Block> DeferredHolder<T, T> block(String name) {
                    return null;
                }

                protected <R, T extends R> void accept(String name, Function<DeferredHolder<T, T>, ?> factory) {
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val registrate = read("Registrate")
        assertTrue(registrate.contains("<T extends Block> DeferredHolder<Block, T> block("), registrate)
        assertTrue(registrate.contains("Function<DeferredHolder<T, T>, ?> factory"), registrate)
    }
}
