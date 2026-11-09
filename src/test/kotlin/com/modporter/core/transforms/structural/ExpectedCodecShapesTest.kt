package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExpectedCodecShapesTest {
    @TempDir
    lateinit var directory: Path

    private fun migrate(target: String, sources: Map<String, String>): String {
        val root = directory.resolve("src/main/java")
        sources.forEach { (relative, source) ->
            root.resolve(relative).also { file ->
                file.parent.createDirectories()
                file.writeText(source.trimIndent() + "\n")
            }
        }
        val files = root.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.toPath() }
            .toList()
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        val source = root.resolve(target).readText()
        val mapBackedCodecFields =
            ExpectedCodecShapes.collectMapBackedCodecFields(pass, files)
        return ExpectedCodecShapes.migrate(pass, source, mapBackedCodecFields)
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()

    @Test
    fun `Create map codec supplier is adapted to the exact DFU 8 Codec shape`() {
        val migrated = migrate(
            "example/CreateCodecs.java",
            mapOf(
                "example/ItemSlots.java" to """
                    package example;

                    import com.mojang.serialization.MapCodec;

                    final class ItemSlots {
                        static final MapCodec<ItemSlots> CODEC = null;
                        Handler toHandler() { return null; }
                        static ItemSlots fromHandler(Handler handler) { return null; }
                    }
                """,
                "example/Handler.java" to """
                    package example;
                    final class Handler {}
                """,
                "example/CreateCodecs.java" to """
                    package example;

                    import com.mojang.serialization.Codec;

                    final class CreateCodecs {
                        static final Codec<Handler> HANDLER = Codec.lazyInitialized(
                            () -> ItemSlots.CODEC.xmap(
                                slots -> slots.toHandler(),
                                ItemSlots::fromHandler
                            )
                        );
                    }
                """
            )
        )

        assertTrue(
            compact(migrated).contains(
                "ItemSlots.CODEC.xmap(slots -> slots.toHandler(), " +
                    "ItemSlots::fromHandler).codec()"
            ),
            migrated
        )
        assertEquals(1, Regex("""\.codec\(\)""").findAll(migrated).count())
    }

    @Test
    fun `field codecs and map transformations gain only the final adapter`() {
        val migrated = migrate(
            "example/Shapes.java",
            mapOf(
                "example/Shapes.java" to """
                    package example;

                    import com.mojang.serialization.Codec;
                    import com.mojang.serialization.DataResult;
                    import com.mojang.serialization.MapCodec;

                    final class Shapes {
                        static final Codec<String> ACTUAL_CODEC = null;
                        static final MapCodec<String> ACTUAL_MAP_CODEC = null;

                        static final Codec<String> FIELD = Codec.lazyInitialized(
                            () -> ACTUAL_CODEC.fieldOf("payload")
                                .xmap(value -> value, value -> value)
                        );

                        static final Codec<String> OPTIONAL = Codec.lazyInitialized(
                            () -> ACTUAL_CODEC.optionalFieldOf("optional_payload", "")
                                .flatXmap(DataResult::success, DataResult::success)
                        );

                        static final Codec<String> DIRECT = Codec.lazyInitialized(
                            () -> ACTUAL_MAP_CODEC.xmap(value -> value, value -> value)
                        );
                    }
                """
            )
        )

        val compact = compact(migrated)
        assertTrue(
            compact.contains(
                """ACTUAL_CODEC.fieldOf("payload").xmap(value -> value, value -> value).codec()"""
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                """ACTUAL_CODEC.optionalFieldOf("optional_payload", "").flatXmap(""" +
                    "DataResult::success, DataResult::success).codec()"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "ACTUAL_MAP_CODEC.xmap(value -> value, value -> value).codec()"
            ),
            migrated
        )
        assertTrue(migrated.contains("\"payload\""), migrated)
        assertTrue(migrated.contains("\"optional_payload\""), migrated)
        assertEquals(3, Regex("""\.codec\(\)""").findAll(migrated).count())
    }

    @Test
    fun `recursive block returns are all proven before any branch changes`() {
        val migrated = migrate(
            "example/BlockSuppliers.java",
            mapOf(
                "example/BlockSuppliers.java" to """
                    package example;

                    import com.mojang.serialization.Codec;
                    import com.mojang.serialization.MapCodec;

                    final class BlockSuppliers {
                        static final Codec<String> ACTUAL_CODEC = null;
                        static final MapCodec<String> ACTUAL_MAP_CODEC = null;

                        static Codec<String> known(boolean first, boolean second) {
                            return Codec.lazyInitialized(() -> {
                                if (first) {
                                    return ACTUAL_MAP_CODEC.xmap(v -> v, v -> v);
                                }
                                if (second)
                                    return ACTUAL_CODEC.fieldOf("second");
                                return ACTUAL_CODEC;
                            });
                        }

                        static Codec<String> unknown(boolean first) {
                            return Codec.lazyInitialized(() -> {
                                if (first)
                                    return ACTUAL_MAP_CODEC.xmap(v -> v, v -> v);
                                return ExternalCodecs.codec();
                            });
                        }
                    }
                """
            )
        )

        val known = migrated.substringBefore("static Codec<String> unknown")
        assertTrue(
            known.contains("return ACTUAL_MAP_CODEC.xmap(v -> v, v -> v).codec();"),
            known
        )
        assertTrue(known.contains("""return ACTUAL_CODEC.fieldOf("second").codec();"""), known)
        assertTrue(known.contains("return ACTUAL_CODEC;"), known)

        val unknown = migrated.substringAfter("static Codec<String> unknown")
        assertFalse(
            unknown.contains("return ACTUAL_MAP_CODEC.xmap(v -> v, v -> v).codec();"),
            unknown
        )
        assertTrue(
            unknown.contains("return ACTUAL_MAP_CODEC.xmap(v -> v, v -> v);"),
            unknown
        )
    }

    @Test
    fun `plain Codec chains fake APIs unknown receivers and repeated migration stay unchanged`() {
        val original = """
            package example;

            import com.mojang.serialization.Codec;

            final class ExactOnly {
                static final Codec<String> ACTUAL_CODEC = null;
                static final FakeCodec FAKE = new FakeCodec();

                static final Codec<String> PLAIN = Codec.lazyInitialized(
                    () -> ACTUAL_CODEC.xmap(value -> value, value -> value)
                );
                static final Codec<String> UNKNOWN = Codec.lazyInitialized(
                    () -> FAKE.fieldOf("payload").xmap(value -> value, value -> value)
                );

                static final class FakeCodec {
                    FakeCodec fieldOf(String name) { return this; }
                    FakeCodec xmap(Object first, Object second) { return this; }
                }
            }
        """.trimIndent()
        val migrated = migrate(
            "example/ExactOnly.java",
            mapOf("example/ExactOnly.java" to original)
        )

        assertEquals(original + "\n", migrated)
        assertEquals(migrated, ExpectedCodecShapes.migrate(StructuralRefactorPass(), migrated))
    }

    @Test
    fun `same named lazy initializer on a non DFU Codec is ignored`() {
        val original = """
            package example;

            import example.fake.Codec;
            import com.mojang.serialization.MapCodec;

            final class FakeOwner {
                static final MapCodec<String> ACTUAL_MAP_CODEC = null;
                static final Object VALUE = Codec.lazyInitialized(
                    () -> ACTUAL_MAP_CODEC.xmap(value -> value, value -> value)
                );
            }
        """.trimIndent()
        val migrated = migrate(
            "example/FakeOwner.java",
            mapOf(
                "example/FakeOwner.java" to original,
                "example/fake/Codec.java" to """
                    package example.fake;
                    public final class Codec {
                        public static Object lazyInitialized(Object supplier) {
                            return supplier;
                        }
                    }
                """
            )
        )

        assertEquals(original + "\n", migrated)
    }

    @Test
    fun `canonical Codec method returns adapt direct and validated MapCodec expressions`() {
        val migrated = migrate(
            "example/ReturnShapes.java",
            mapOf(
                "example/ReturnShapes.java" to """
                    package example;

                    import com.mojang.serialization.Codec;
                    import com.mojang.serialization.DataResult;
                    import com.mojang.serialization.MapCodec;

                    final class ReturnShapes {
                        static final MapCodec<String> SERIALIZER = null;

                        static Codec<String> direct() {
                            return SERIALIZER;
                        }

                        static Codec<String> validated() {
                            return SERIALIZER.validate(DataResult::success);
                        }

                        static MapCodec<String> alreadyExact() {
                            return SERIALIZER;
                        }
                    }
                """
            )
        )

        assertTrue(migrated.contains("return SERIALIZER.codec();"), migrated)
        assertTrue(
            migrated.contains("return SERIALIZER.validate(DataResult::success).codec();"),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "static MapCodec<String> alreadyExact() { return SERIALIZER; }"
            ),
            migrated
        )
        assertEquals(2, Regex("""\.codec\(\)""").findAll(migrated).count())
    }

    @Test
    fun `DFU list and target Feature constructor adapt only source-proven MapCodec arguments`() {
        val migrated = migrate(
            "example/CodecContexts.java",
            mapOf(
                "example/CodecContexts.java" to """
                    package example;

                    import com.mojang.serialization.Codec;
                    import com.mojang.serialization.MapCodec;
                    import java.util.List;
                    import net.minecraft.world.level.levelgen.feature.Feature;
                    import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

                    final class CodecContexts {
                        static final class Layer {
                            static final MapCodec<Layer> SERIALIZER = null;
                        }

                        static final Codec<List<Layer>> LAYERS = Codec.list(Layer.SERIALIZER);

                        static final class Config implements FeatureConfiguration {
                            static final MapCodec<Config> SERIALIZER = null;
                        }

                        static final class ExactFeature extends Feature<Config> {
                            ExactFeature() {
                                super(Config.SERIALIZER);
                            }
                        }
                    }
                """
            )
        )

        assertTrue(
            migrated.contains("Codec.list(Layer.SERIALIZER.codec())"),
            migrated
        )
        assertTrue(
            migrated.contains("super(Config.SERIALIZER.codec());"),
            migrated
        )
        assertEquals(2, Regex("""\.codec\(\)""").findAll(migrated).count())
    }

    @Test
    fun `Feature source without a Codec token still resolves a cross file MapCodec field`() {
        val migrated = migrate(
            "example/LayeredOreFeature.java",
            mapOf(
                "example/LayeredOreConfiguration.java" to """
                    package example;

                    import com.mojang.serialization.MapCodec;
                    import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

                    final class LayeredOreConfiguration implements FeatureConfiguration {
                        static final MapCodec<LayeredOreConfiguration> CODEC = null;
                    }
                """,
                "example/LayeredOreFeature.java" to """
                    package example;

                    import net.minecraft.world.level.levelgen.feature.Feature;

                    final class LayeredOreFeature extends Feature<LayeredOreConfiguration> {
                        LayeredOreFeature() {
                            super(LayeredOreConfiguration.CODEC);
                        }
                    }
                """
            )
        )

        assertTrue(
            migrated.contains("super(LayeredOreConfiguration.CODEC.codec());"),
            migrated
        )
        assertEquals(
            migrated,
            migrate(
                "example/LayeredOreFeature.java",
                mapOf(
                    "example/LayeredOreConfiguration.java" to """
                        package example;

                        import com.mojang.serialization.MapCodec;
                        import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

                        final class LayeredOreConfiguration implements FeatureConfiguration {
                            static final MapCodec<LayeredOreConfiguration> CODEC = null;
                        }
                    """,
                    "example/LayeredOreFeature.java" to migrated
                )
            )
        )
    }

    @Test
    fun `same named list and Feature APIs do not provide canonical expected types`() {
        val original = """
            package example;

            import com.mojang.serialization.MapCodec;
            import example.fake.Codec;
            import example.fake.Feature;

            final class FakeContexts {
                static final MapCodec<String> SERIALIZER = null;
                static final Object VALUES = Codec.list(SERIALIZER);

                static final class FakeFeature extends Feature {
                    FakeFeature() {
                        super(SERIALIZER);
                    }
                }
            }
        """.trimIndent()
        val migrated = migrate(
            "example/FakeContexts.java",
            mapOf(
                "example/FakeContexts.java" to original,
                "example/fake/Codec.java" to """
                    package example.fake;
                    public final class Codec {
                        public static Object list(Object value) {
                            return value;
                        }
                    }
                """,
                "example/fake/Feature.java" to """
                    package example.fake;
                    public class Feature {
                        protected Feature(Object value) {
                        }
                    }
                """
            )
        )

        assertEquals(original + "\n", migrated)
    }

    @Test
    fun `StructureProcessorType registry requires source proven map backed Codec construction`() {
        val migrated = migrate(
            "example/ProcessorRegistry.java",
            mapOf(
                "example/Processor.java" to """
                    package example;

                    import com.mojang.serialization.Codec;

                    final class Processor {
                        static final Codec<Processor> SERIALIZER = Codec.unit(new Processor());
                    }
                """,
                "example/RecordProcessor.java" to """
                    package example;

                    import com.mojang.serialization.Codec;
                    import com.mojang.serialization.codecs.RecordCodecBuilder;

                    final class RecordProcessor {
                        final String value;

                        RecordProcessor(String value) {
                            this.value = value;
                        }

                        static final Codec<RecordProcessor> SERIALIZER =
                            RecordCodecBuilder.create(instance -> instance.group(
                                Codec.STRING.fieldOf("value").forGetter(processor -> processor.value)
                            ).apply(instance, RecordProcessor::new));
                    }
                """,
                "example/WrappedMapProcessor.java" to """
                    package example;

                    import com.mojang.serialization.Codec;

                    final class WrappedMapProcessor {
                        static final Codec<String> VALUE_CODEC = null;
                        final String value;

                        WrappedMapProcessor(String value) {
                            this.value = value;
                        }

                        static final Codec<WrappedMapProcessor> SERIALIZER =
                            VALUE_CODEC.fieldOf("value")
                                .xmap(WrappedMapProcessor::new, processor -> processor.value)
                                .codec();
                    }
                """,
                "example/PrimitiveProcessor.java" to """
                    package example;

                    import com.mojang.serialization.Codec;

                    final class PrimitiveProcessor {
                        final String value;

                        PrimitiveProcessor(String value) {
                            this.value = value;
                        }

                        static final Codec<PrimitiveProcessor> SERIALIZER =
                            Codec.STRING.xmap(PrimitiveProcessor::new, processor -> processor.value);
                    }
                """,
                "example/ProcessorRegistry.java" to """
                    package example;

                    import net.minecraft.core.registries.Registries;
                    import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
                    import net.neoforged.neoforge.registries.DeferredRegister;

                    final class ProcessorRegistry {
                        private static final DeferredRegister<StructureProcessorType<?>> PROCESSORS =
                            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, "example");
                        private static final DeferredRegister<Object> OTHER =
                            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, "example");

                        static final Object EXACT =
                            PROCESSORS.register("exact", () -> () -> Processor.SERIALIZER);
                        static final Object RECORD =
                            PROCESSORS.register("record", () -> () -> RecordProcessor.SERIALIZER);
                        static final Object WRAPPED_MAP =
                            PROCESSORS.register("wrapped_map", () -> () -> WrappedMapProcessor.SERIALIZER);
                        static final Object PRIMITIVE =
                            PROCESSORS.register("primitive", () -> () -> PrimitiveProcessor.SERIALIZER);
                        static final Object WRONG_GENERIC =
                            OTHER.register("wrong", () -> () -> Processor.SERIALIZER);
                    }
                """
            )
        )

        assertTrue(
            compact(migrated).contains(
                "PROCESSORS.register(\"exact\", () -> () -> " +
                    "com.mojang.serialization.MapCodec.assumeMapUnsafe(Processor.SERIALIZER))"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "PROCESSORS.register(\"record\", () -> () -> " +
                    "com.mojang.serialization.MapCodec.assumeMapUnsafe(" +
                    "RecordProcessor.SERIALIZER))"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "PROCESSORS.register(\"wrapped_map\", () -> () -> " +
                    "com.mojang.serialization.MapCodec.assumeMapUnsafe(" +
                    "WrappedMapProcessor.SERIALIZER))"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "PROCESSORS.register(\"primitive\", () -> () -> " +
                    "PrimitiveProcessor.SERIALIZER)"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "OTHER.register(\"wrong\", () -> () -> Processor.SERIALIZER)"
            ),
            migrated
        )
        assertEquals(3, Regex("""assumeMapUnsafe""").findAll(migrated).count())

        val pass = StructuralRefactorPass()
        val root = directory.resolve("src/main/java")
        val files = root.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.toPath() }
            .toList()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        val mapBackedCodecFields =
            ExpectedCodecShapes.collectMapBackedCodecFields(pass, files)
        assertEquals(
            migrated,
            ExpectedCodecShapes.migrate(pass, migrated, mapBackedCodecFields)
        )
    }

    @Test
    fun `project wrapper refreshes facts emits HIGH receipts and honors dry run`() {
        val root = directory.resolve("src/main/java")
        val itemSlots = root.resolve("example/ItemSlots.java")
        val createCodecs = root.resolve("example/CreateCodecs.java")
        itemSlots.parent.createDirectories()
        itemSlots.writeText(
            """
            package example;

            import com.mojang.serialization.MapCodec;

            final class ItemSlots {
                static final MapCodec<ItemSlots> CODEC = null;
            }
            """.trimIndent() + "\n"
        )
        val original =
            """
            package example;

            import com.mojang.serialization.Codec;

            final class CreateCodecs {
                static final Codec<String> VALUE = Codec.lazyInitialized(
                    () -> ItemSlots.CODEC.xmap(value -> value.toString(), value -> null)
                );
            }
            """.trimIndent() + "\n"
        createCodecs.writeText(original)

        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts()
        val proposed = pass.migrateProjectExpectedCodecShapes(directory, dryRun = true)

        assertEquals(1, proposed.size)
        assertEquals(createCodecs, proposed.single().file)
        assertEquals(Confidence.HIGH, proposed.single().confidence)
        assertEquals(ExpectedCodecShapes.RULE_ID, proposed.single().ruleId)
        assertEquals(original, createCodecs.readText())

        val applied = pass.migrateProjectExpectedCodecShapes(directory, dryRun = false)
        assertEquals(1, applied.size)
        assertTrue(
            compact(createCodecs.readText()).contains(
                "ItemSlots.CODEC.xmap(value -> value.toString(), value -> null).codec()"
            ),
            createCodecs.readText()
        )
    }
}
