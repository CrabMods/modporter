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

/** DFU 7 dispatches over map codecs, so a dispatched codec family moves to MapCodec as a whole. */
class DispatchedCodecFamiliesTest {

    @TempDir
    lateinit var tempDir: Path

    private val srcDir: Path get() = tempDir.resolve("src/main/java/com/example")

    private fun java(name: String, text: String) {
        srcDir.createDirectories()
        srcDir.resolve("$name.java").writeText(text.trimIndent() + "\n")
    }

    private fun read(name: String): String = srcDir.resolve("$name.java").readText()

    @Test
    fun `an identity dispatched family moves its registry, methods and codecs to map codecs`() {
        java("HitAction", """
            package com.example;

            import com.mojang.serialization.Codec;
            import java.util.function.Function;

            public interface HitAction {
                Codec<HitAction> CODEC = ModRegistries.HIT_ACTIONS.byNameCodec()
                    .dispatch(HitAction::codec, Function.identity());

                Codec<? extends HitAction> codec();
            }
        """)
        java("ModRegistries", """
            package com.example;

            import com.mojang.serialization.Codec;
            import net.minecraft.core.Registry;

            public class ModRegistries {
                public static final Registry<Codec<? extends HitAction>> HIT_ACTIONS = null;
            }
        """)
        java("HitActions", """
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.core.Registry;
            import net.minecraft.resources.ResourceLocation;

            public class HitActions {
                static {
                    register("burn", Burn.CODEC);
                    register("noop", Noop.CODEC);
                    register("named", Named.CODEC);
                }

                private static void register(String name, Codec<? extends HitAction> codec) {
                    Registry.register(ModRegistries.HIT_ACTIONS, ResourceLocation.parse(name), codec);
                }

                public record Burn(int ticks) implements HitAction {
                    public static final Codec<Burn> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("ticks").forGetter(Burn::ticks)
                    ).apply(instance, Burn::new));

                    @Override
                    public Codec<? extends HitAction> codec() {
                        return CODEC;
                    }
                }

                public enum Noop implements HitAction {
                    INSTANCE;

                    public static final Codec<Noop> CODEC = Codec.unit(INSTANCE);

                    @Override
                    public Codec<? extends HitAction> codec() {
                        return CODEC;
                    }
                }

                public record Named(String name) implements HitAction {
                    public static final Codec<Named> CODEC = Codec.STRING.xmap(Named::new, Named::name);

                    @Override
                    public Codec<? extends HitAction> codec() {
                        return CODEC;
                    }
                }
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val action = read("HitAction")
        val registries = read("ModRegistries")
        val actions = read("HitActions")
        assertTrue(action.contains("MapCodec<? extends HitAction> codec();"), action)
        assertTrue(action.contains("Codec<HitAction> CODEC = ModRegistries.HIT_ACTIONS.byNameCodec()"), action)
        assertTrue(registries.contains("Registry<MapCodec<? extends HitAction>> HIT_ACTIONS"), registries)
        assertTrue(registries.contains("import com.mojang.serialization.MapCodec;"), registries)
        assertTrue(actions.contains("private static void register(String name, MapCodec<? extends HitAction> codec)"), actions)
        assertTrue(Regex("""MapCodec<Burn> CODEC = RecordCodecBuilder\.(?:<Burn>)?mapCodec\(""").containsMatchIn(actions), actions)
        assertTrue(actions.contains("MapCodec<Noop> CODEC = MapCodec.unit(INSTANCE);"), actions)
        // DFU 6 put a value whose codec was not a map codec under "value"
        assertTrue(actions.contains("MapCodec<Named> CODEC = Codec.STRING.xmap(Named::new, Named::name).fieldOf(\"value\");"), actions)
        assertFalse(actions.contains("public Codec<? extends HitAction> codec()"), actions)
    }

    @Test
    fun `a field dispatched family moves the key's codec field and the codecs its subtypes pass`() {
        java("StorageType", """
            package com.example;

            import com.mojang.serialization.Codec;

            public class StorageType<T extends Storage> {
                public static final Codec<StorageType<?>> CODEC = null;

                public final Codec<? extends T> codec;

                protected StorageType(Codec<? extends T> codec) {
                    this.codec = codec;
                }
            }
        """)
        java("Storage", """
            package com.example;

            import com.mojang.serialization.Codec;

            public abstract class Storage {
                public static final Codec<Storage> CODEC = StorageType.CODEC.dispatch(
                    storage -> storage.type, type -> type.codec
                );

                public StorageType<? extends Storage> type;
            }
        """)
        java("ChestStorage", """
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;

            public class ChestStorage extends Storage {
                public static final Codec<ChestStorage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("size").forGetter(storage -> 0)
                ).apply(instance, size -> new ChestStorage()));
            }
        """)
        java("ChestStorageType", """
            package com.example;

            public class ChestStorageType extends StorageType<ChestStorage> {
                public ChestStorageType() {
                    super(ChestStorage.CODEC);
                }
            }
        """)

        java("SimpleStorageType", """
            package com.example;

            import com.mojang.serialization.Codec;

            public abstract class SimpleStorageType<T extends Storage> extends StorageType<T> {
                protected SimpleStorageType(Codec<T> codec) {
                    super(codec);
                }

                public static final class Impl extends SimpleStorageType<SimpleStorage> {
                    public Impl() {
                        super(SimpleStorage.CODEC);
                    }
                }
            }
        """)
        java("SimpleStorage", """
            package com.example;

            import com.mojang.serialization.Codec;

            public class SimpleStorage extends Storage {
                public static final Codec<SimpleStorage> CODEC = Codec.unit(SimpleStorage::new);
            }
        """)

        StructuralRefactorPass().apply(tempDir)

        val type = read("StorageType")
        val chest = read("ChestStorage")
        val simpleType = read("SimpleStorageType")
        // a subclass passing its own codec parameter on carries map codecs as well
        assertTrue(simpleType.contains("protected SimpleStorageType(MapCodec<T> codec)"), simpleType)
        assertTrue(read("SimpleStorage").contains("MapCodec<SimpleStorage> CODEC = MapCodec.unit(SimpleStorage::new);"), read("SimpleStorage"))
        assertTrue(type.contains("public final MapCodec<? extends T> codec;"), type)
        assertTrue(type.contains("protected StorageType(MapCodec<? extends T> codec)"), type)
        assertTrue(type.contains("Codec<StorageType<?>> CODEC = null;"), type)
        assertTrue(Regex("""MapCodec<ChestStorage> CODEC = RecordCodecBuilder\.(?:<ChestStorage>)?mapCodec\(""").containsMatchIn(chest), chest)
    }
}
