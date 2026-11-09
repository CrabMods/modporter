package com.modporter.transforms

import com.modporter.core.transforms.structural.AdvancementValues
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AdvancementValuesTest {
    @TempDir
    lateinit var tempDir: Path

    private val sourceDir: Path
        get() = tempDir.resolve("src/main/java/example")

    private fun java(name: String, source: String): Path {
        sourceDir.createDirectories()
        return sourceDir.resolve("$name.java").also { it.writeText(source.trimIndent() + "\n") }
    }

    private fun generatedEntries(name: String = "GeneratedEntries"): Path = java(
        name,
        """
        package example;

        import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;

        final class $name extends DatapackBuiltinEntriesProvider {
        }
        """
    )

    private fun legacyProvider(
        name: String,
        declaration: String = "public final class",
        extraRunStatement: String = ""
    ): Path = java(
        name,
        """
        package example;

        import java.nio.file.Path;
        import java.util.ArrayList;
        import java.util.List;
        import java.util.concurrent.CompletableFuture;
        import java.util.function.Consumer;
        import net.minecraft.advancements.AdvancementHolder;
        import net.minecraft.data.CachedOutput;
        import net.minecraft.data.DataProvider;
        import net.minecraft.data.PackOutput;
        import net.minecraft.data.PackOutput.PathProvider;
        import net.minecraft.resources.ResourceLocation;

        $declaration $name implements DataProvider {
            private final PackOutput output;

            public $name(PackOutput output) {
                this.output = output;
            }

            public CompletableFuture<?> run(CachedOutput cache) {
                PathProvider paths =
                    output.createPathProvider(PackOutput.Target.DATA_PACK, "advancements");
                List<CompletableFuture<?>> writes = new ArrayList<>();
                Consumer<AdvancementHolder> sink = award -> {
                    ResourceLocation id = award.getId();
                    Path path = paths.json(id);
                    writes.add(DataProvider.saveStable(
                        cache,
                        award.deconstruct().serializeToJson(),
                        path));
                };
                $extraRunStatement
                return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
            }

            public String getName() {
                return "$name";
            }
        }
        """
    )

    @Test
    fun `holder values custom criteria and provider codec migrate from source contracts`() {
        generatedEntries()
        java(
            "PulseTrigger",
            """
            package example;

            import com.mojang.serialization.Codec;
            import java.util.Optional;
            import net.minecraft.advancements.CriterionTrigger;
            import net.minecraft.advancements.CriterionTriggerInstance;

            abstract class TriggerBase<T extends CriterionTriggerInstance> implements CriterionTrigger<T> {
            }

            final class PulseTrigger extends TriggerBase<PulseTrigger.Instance> {
                public Codec<Instance> codec() {
                    return null;
                }

                public Instance instance() {
                    return new Instance();
                }

                static final class Instance implements CriterionTriggerInstance {
                }
            }
            """
        )
        val badge = java(
            "Badge.java".removeSuffix(".java"),
            """
            package example;

            import java.util.function.Consumer;
            import net.minecraft.advancements.Advancement;
            import net.minecraft.advancements.AdvancementHolder;
            import net.minecraft.advancements.AdvancementRequirements;
            import net.minecraft.advancements.AdvancementRewards;
            import net.minecraft.resources.ResourceLocation;

            final class Badge {
                private final Advancement.Builder forge = Advancement.Builder.advancement();
                private final PulseTrigger pulse = new PulseTrigger();
                private Advancement written;
                private Badge parent;

                Badge() {
                    forge.addCriterion("pulse", pulse.instance());
                    forge.rewards(AdvancementRewards.Builder.experience(5));
                    forge.requirements(AdvancementRequirements.Strategy.OR);
                }

                void save(Consumer<AdvancementHolder> output, ResourceLocation id) {
                    if (parent != null)
                        forge.parent(parent.written);
                    written = forge.save(output, id.toString());
                }
            }
            """
        )
        val provider = java(
            "BadgeProvider",
            """
            package example;

            import java.nio.file.Path;
            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.List;
            import java.util.Set;
            import java.util.concurrent.CompletableFuture;
            import java.util.function.Consumer;
            import net.minecraft.advancements.Advancement;
            import net.minecraft.advancements.AdvancementHolder;
            import net.minecraft.data.CachedOutput;
            import net.minecraft.data.DataProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.PackOutput.PathProvider;
            import net.minecraft.resources.ResourceLocation;

            public final class BadgeProvider implements DataProvider {
                private final PackOutput destination;

                public BadgeProvider(PackOutput destination) {
                    this.destination = destination;
                }

                @Override
                public CompletableFuture<?> run(CachedOutput cache) {
                    PathProvider paths =
                        destination.createPathProvider(PackOutput.Target.DATA_PACK, "advancements");
                    List<CompletableFuture<?>> writes = new ArrayList<>();
                    Set<ResourceLocation> ids = new HashSet<>();
                    Consumer<AdvancementHolder> sink = badge -> {
                        ResourceLocation key = badge.getId();
                        if (!ids.add(key))
                            throw new IllegalStateException("duplicate " + key);
                        Path path = paths.json(key);
                        writes.add(DataProvider.saveStable(
                            cache,
                            badge.deconstruct().serializeToJson(),
                            path));
                    };
                    new Badge().save(sink, ResourceLocation.parse("example:badge"));
                    return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
                }

                @Override
                public String getName() {
                    return "badges";
                }
            }
            """
        )
        val caller = java(
            "DatasetHook",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class DatasetHook {
                static void gather(GatherDataEvent request) {
                    DataGenerator generator = request.getGenerator();
                    PackOutput target = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> catalog = request.getLookupProvider();
                    GeneratedEntries generated = new GeneratedEntries(target, catalog);
                    catalog = generated.getRegistryProvider();
                    generator.addProvider(request.includeServer(), new BadgeProvider(target));
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        val badgeText = badge.readText()
        assertTrue(badgeText.contains("private AdvancementHolder written;"), badgeText)
        assertTrue(
            badgeText.contains("forge.addCriterion(\"pulse\", pulse.createCriterion(pulse.instance()));"),
            badgeText
        )
        assertTrue(badgeText.contains("forge.rewards(AdvancementRewards.Builder.experience(5));"), badgeText)
        assertTrue(badgeText.contains("forge.requirements(AdvancementRequirements.Strategy.OR);"), badgeText)
        assertTrue(badgeText.contains("forge.parent(parent.written);"), badgeText)

        val providerText = provider.readText()
        assertTrue(
            providerText.contains("private final CompletableFuture<HolderLookup.Provider> registries;"),
            providerText
        )
        assertTrue(
            providerText.contains("public BadgeProvider(PackOutput destination, CompletableFuture<HolderLookup.Provider> registries)"),
            providerText
        )
        assertTrue(providerText.contains("return registries.thenCompose(registryProvider -> {"), providerText)
        assertTrue(
            providerText.contains("destination.createRegistryElementsPathProvider(Registries.ADVANCEMENT)"),
            providerText
        )
        assertTrue(providerText.contains("ResourceLocation key = badge.id();"), providerText)
        assertTrue(
            providerText.contains(
                "DataProvider.saveStable(cache, registryProvider, Advancement.CODEC, badge.value(), path)"
            ),
            providerText
        )
        assertFalse(providerText.contains("deconstruct()"), providerText)
        assertTrue(caller.readText().contains("new BadgeProvider(target, catalog)"), caller.readText())
    }

    @Test
    fun `provider migration fails closed when the gather handler has ambiguous registry futures`() {
        val provider = java(
            "Awards",
            """
            package example;

            import java.nio.file.Path;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import java.util.function.Consumer;
            import net.minecraft.advancements.Advancement;
            import net.minecraft.advancements.AdvancementHolder;
            import net.minecraft.data.CachedOutput;
            import net.minecraft.data.DataProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.PackOutput.PathProvider;
            import net.minecraft.resources.ResourceLocation;

            final class Awards implements DataProvider {
                private final PackOutput output;

                Awards(PackOutput output) {
                    this.output = output;
                }

                public CompletableFuture<?> run(CachedOutput cache) {
                    PathProvider paths = output.createPathProvider(PackOutput.Target.DATA_PACK, "advancements");
                    List<CompletableFuture<?>> writes = new ArrayList<>();
                    Consumer<AdvancementHolder> sink = award -> {
                        ResourceLocation id = award.getId();
                        Path path = paths.json(id);
                        writes.add(DataProvider.saveStable(cache, award.deconstruct().serializeToJson(), path));
                    };
                    return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
                }

                public String getName() {
                    return "awards";
                }
            }
            """
        )
        java(
            "DataHooks",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class DataHooks {
                static Awards gather(GatherDataEvent event, PackOutput output) {
                    CompletableFuture<HolderLookup.Provider> first = event.getLookupProvider();
                    CompletableFuture<HolderLookup.Provider> second = event.getLookupProvider();
                    return new Awards(output);
                }
            }
            """
        )
        AdvancementValues.migrate(tempDir, dryRun = false)

        val migrated = provider.readText()
        assertTrue(migrated.contains("ResourceLocation id = award.id();"), migrated)
        assertTrue(migrated.contains("Awards(PackOutput output)"), migrated)
        assertTrue(migrated.contains("\"advancements\""), migrated)
        assertTrue(migrated.contains("award.deconstruct().serializeToJson()"), migrated)
        assertFalse(migrated.contains("CompletableFuture<HolderLookup.Provider> registries"), migrated)
        assertFalse(migrated.contains("Advancement.CODEC"), migrated)
    }

    @Test
    fun `a separate enriched future is selected instead of the earlier event future`() {
        generatedEntries()
        val provider = legacyProvider("SeparateAwards")
        val caller = java(
            "SeparateData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class SeparateData {
                static void gather(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput output = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> base = event.getLookupProvider();
                    GeneratedEntries entries = new GeneratedEntries();
                    CompletableFuture<HolderLookup.Provider> enriched =
                        entries.getRegistryProvider();
                    generator.addProvider(event.includeServer(), new SeparateAwards(output));
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertTrue(caller.readText().contains("new SeparateAwards(output, enriched)"), caller.readText())
        assertTrue(
            provider.readText().contains("CompletableFuture<HolderLookup.Provider> registries"),
            provider.readText()
        )
        assertFalse(caller.readText().contains("new SeparateAwards(output, base)"), caller.readText())
    }

    @Test
    fun `an enriched future in the caller nested scope is selected`() {
        generatedEntries()
        val provider = legacyProvider("NestedVisibleAwards")
        val caller = java(
            "NestedVisibleData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class NestedVisibleData {
                static void gather(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput output = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> base =
                        event.getLookupProvider();
                    GeneratedEntries entries = new GeneratedEntries();
                    if (event.includeServer()) {
                        CompletableFuture<HolderLookup.Provider> nested =
                            entries.getRegistryProvider();
                        generator.addProvider(true, new NestedVisibleAwards(output));
                    }
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertTrue(
            caller.readText().contains("new NestedVisibleAwards(output, nested)"),
            caller.readText()
        )
        assertTrue(provider.readText().contains("Advancement.CODEC"), provider.readText())
        assertFalse(
            caller.readText().contains("new NestedVisibleAwards(output, base)"),
            caller.readText()
        )
    }

    @Test
    fun `an enriched future from an exited nested scope is out of scope`() {
        generatedEntries()
        val provider = legacyProvider("NestedAwards")
        java(
            "NestedData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class NestedData {
                static NestedAwards gather(GatherDataEvent event, PackOutput output) {
                    CompletableFuture<HolderLookup.Provider> base = event.getLookupProvider();
                    GeneratedEntries entries = new GeneratedEntries();
                    {
                        CompletableFuture<HolderLookup.Provider> nested =
                            entries.getRegistryProvider();
                    }
                    return new NestedAwards(output);
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        val migrated = provider.readText()
        assertTrue(migrated.contains("NestedAwards(PackOutput output)"), migrated)
        assertTrue(migrated.contains("award.deconstruct().serializeToJson()"), migrated)
        assertFalse(migrated.contains("Advancement.CODEC"), migrated)
    }

    @Test
    fun `a later dominant enriched assignment overrides an earlier conditional write`() {
        generatedEntries()
        val provider = legacyProvider("DominantAwards")
        val caller = java(
            "DominantData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class DominantData {
                static DominantAwards gather(
                    GatherDataEvent event, PackOutput output, boolean provisional
                ) {
                    CompletableFuture<HolderLookup.Provider> registries =
                        event.getLookupProvider();
                    GeneratedEntries entries = new GeneratedEntries();
                    if (provisional) {
                        registries = event.getLookupProvider();
                    }
                    registries = entries.getRegistryProvider();
                    return new DominantAwards(output);
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertTrue(
            caller.readText().contains("new DominantAwards(output, registries)"),
            caller.readText()
        )
        assertTrue(provider.readText().contains("Advancement.CODEC"), provider.readText())
    }

    @Test
    fun `a conditional enriched assignment does not dominate the provider call`() {
        generatedEntries()
        val provider = legacyProvider("ConditionalAwards")
        java(
            "ConditionalData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class ConditionalData {
                static ConditionalAwards gather(
                    GatherDataEvent event, PackOutput output, boolean includeGenerated
                ) {
                    CompletableFuture<HolderLookup.Provider> registries =
                        event.getLookupProvider();
                    GeneratedEntries entries = new GeneratedEntries();
                    if (includeGenerated) {
                        registries = entries.getRegistryProvider();
                    }
                    return new ConditionalAwards(output);
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertTrue(provider.readText().contains("ConditionalAwards(PackOutput output)"), provider.readText())
        assertFalse(provider.readText().contains("Advancement.CODEC"), provider.readText())
    }

    @Test
    fun `a later dominant base assignment overrides an earlier enriched future`() {
        generatedEntries()
        val provider = legacyProvider("OverwrittenAwards")
        java(
            "OverwrittenData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class OverwrittenData {
                static OverwrittenAwards gather(GatherDataEvent event, PackOutput output) {
                    GeneratedEntries entries = new GeneratedEntries();
                    CompletableFuture<HolderLookup.Provider> registries =
                        entries.getRegistryProvider();
                    registries = event.getLookupProvider();
                    return new OverwrittenAwards(output);
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertTrue(provider.readText().contains("OverwrittenAwards(PackOutput output)"), provider.readText())
        assertFalse(provider.readText().contains("Advancement.CODEC"), provider.readText())
    }

    @Test
    fun `a provider constructing another provider does not create stale cross provider edits`() {
        generatedEntries()
        val providerB = legacyProvider("ProviderB")
        val providerA = legacyProvider("ProviderA", extraRunStatement = "new ProviderB(output);")
        val caller = java(
            "ProviderData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class ProviderData {
                static ProviderA gather(GatherDataEvent event, PackOutput output) {
                    CompletableFuture<HolderLookup.Provider> base = event.getLookupProvider();
                    GeneratedEntries entries = new GeneratedEntries();
                    CompletableFuture<HolderLookup.Provider> enriched =
                        entries.getRegistryProvider();
                    return new ProviderA(output);
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertTrue(caller.readText().contains("new ProviderA(output, enriched)"), caller.readText())
        assertTrue(providerA.readText().contains("return registries.thenCompose"), providerA.readText())
        assertTrue(providerA.readText().contains("new ProviderB(output);"), providerA.readText())
        assertTrue(providerB.readText().contains("ProviderB(PackOutput output)"), providerB.readText())
        assertFalse(providerB.readText().contains("Advancement.CODEC"), providerB.readText())
    }

    @Test
    fun `an anonymous provider construction gates constructor widening`() {
        generatedEntries()
        val provider = legacyProvider("AnonymousAwards", declaration = "public class")
        java(
            "AnonymousData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class AnonymousData {
                static AnonymousAwards gather(GatherDataEvent event, PackOutput output) {
                    CompletableFuture<HolderLookup.Provider> base = event.getLookupProvider();
                    GeneratedEntries entries = new GeneratedEntries();
                    CompletableFuture<HolderLookup.Provider> enriched =
                        entries.getRegistryProvider();
                    return new AnonymousAwards(output) {
                    };
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertTrue(provider.readText().contains("AnonymousAwards(PackOutput output)"), provider.readText())
        assertFalse(provider.readText().contains("Advancement.CODEC"), provider.readText())
    }

    @Test
    fun `a possible constructor in an unparsed source gates provider migration`() {
        generatedEntries()
        val provider = legacyProvider("UnparsedAwards")
        java(
            "ValidData",
            """
            package example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            final class ValidData {
                static UnparsedAwards gather(GatherDataEvent event, PackOutput output) {
                    CompletableFuture<HolderLookup.Provider> base = event.getLookupProvider();
                    GeneratedEntries entries = new GeneratedEntries();
                    CompletableFuture<HolderLookup.Provider> enriched =
                        entries.getRegistryProvider();
                    return new UnparsedAwards(output);
                }
            }
            """
        )
        java(
            "BrokenCaller",
            """
            package example;
            final class BrokenCaller {
                void broken() {
                    new UnparsedAwards(output)
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertTrue(provider.readText().contains("UnparsedAwards(PackOutput output)"), provider.readText())
        assertFalse(provider.readText().contains("Advancement.CODEC"), provider.readText())
    }

    @Test
    fun `proven wildcard imports beat unique foreign project simple names`() {
        java(
            "ForeignAdvancement",
            """
            package foreign;

            final class Advancement {
            }
            """
        )
        java(
            "ForeignResourceLocation",
            """
            package foreign;

            final class ResourceLocation {
            }
            """
        )
        val source = java(
            "WildcardBadge",
            """
            package example;

            import java.util.function.Consumer;
            import net.minecraft.advancements.*;
            import net.minecraft.resources.ResourceLocation;

            final class WildcardBadge {
                private final Advancement.Builder builder = Advancement.Builder.advancement();
                private Advancement saved;

                void save(Consumer<AdvancementHolder> output, ResourceLocation id) {
                    saved = builder.save(output, id.toString());
                }

                void inspect(AdvancementHolder holder) {
                    holder.getId();
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)

        val migrated = source.readText()
        assertTrue(migrated.contains("private AdvancementHolder saved;"), migrated)
        assertTrue(migrated.contains("holder.id();"), migrated)
        assertTrue(migrated.contains("ResourceLocation id"), migrated)
        assertFalse(migrated.contains("holder.getId();"), migrated)
    }

    @Test
    fun `lexical advancement and resource location types shadow explicit imports`() {
        val source = java(
            "LexicalShadow",
            """
            package example;

            import java.util.function.Consumer;
            import net.minecraft.advancements.Advancement;
            import net.minecraft.advancements.AdvancementHolder;
            import net.minecraft.resources.ResourceLocation;

            final class LexicalShadow {
                static final class ResourceLocation {
                }

                static final class Advancement {
                    static final class Builder {
                        Advancement save(Consumer<AdvancementHolder> output, String id) {
                            return new Advancement();
                        }
                    }

                    ResourceLocation getId() {
                        return new ResourceLocation();
                    }
                }

                private final Advancement.Builder builder = new Advancement.Builder();
                private Advancement saved;

                void save(Consumer<AdvancementHolder> output, ResourceLocation id) {
                    saved = builder.save(output, id.toString());
                }

                ResourceLocation inspect() {
                    return saved.getId();
                }
            }
            """
        )
        val before = source.readText()

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertEquals(before, source.readText())
    }

    @Test
    fun `multiple proven wildcard candidates fail closed`() {
        java(
            "OtherAdvancement",
            """
            package other;

            final class Advancement {
            }
            """
        )
        val source = java(
            "AmbiguousWildcard",
            """
            package example;

            import java.util.function.Consumer;
            import net.minecraft.advancements.*;
            import other.*;

            final class AmbiguousWildcard {
                private final net.minecraft.advancements.Advancement.Builder builder =
                    net.minecraft.advancements.Advancement.Builder.advancement();
                private Advancement saved;

                void save(Consumer<AdvancementHolder> output) {
                    saved = builder.save(output, "example:ambiguous");
                }
            }
            """
        )
        val before = source.readText()

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertEquals(before, source.readText())
    }

    @Test
    fun `unproven trigger and unrelated id and deconstruct methods remain byte identical`() {
        val source = java(
            "Unrelated",
            """
            package example;

            final class Unrelated {
                private final Builder builder = new Builder();
                private final Factory factory = new Factory();

                void build(Value value) {
                    builder.addCriterion("x", factory.instance());
                    value.getId();
                    value.deconstruct().serializeToJson();
                }

                static final class Builder {
                    void addCriterion(String key, Object value) {
                    }
                }

                static final class Factory {
                    Object instance() {
                        return new Object();
                    }
                }

                static final class Value {
                    String getId() {
                        return "x";
                    }

                    Value deconstruct() {
                        return this;
                    }

                    String serializeToJson() {
                        return "{}";
                    }
                }
            }
            """
        )
        val before = source.readText()

        AdvancementValues.migrate(tempDir, dryRun = false)

        assertEquals(before, source.readText())
    }

    @Test
    fun `migration is idempotent`() {
        val source = java(
            "AlreadyModern",
            """
            package example;

            import java.util.function.Consumer;
            import net.minecraft.advancements.Advancement;
            import net.minecraft.advancements.AdvancementHolder;

            final class AlreadyModern {
                private final Advancement.Builder builder = Advancement.Builder.advancement();
                private AdvancementHolder saved;

                void save(Consumer<AdvancementHolder> output) {
                    saved = builder.save(output, "example:modern");
                    saved.id();
                    saved.value();
                }
            }
            """
        )

        AdvancementValues.migrate(tempDir, dryRun = false)
        val once = source.readText()
        AdvancementValues.migrate(tempDir, dryRun = false)

        assertEquals(once, source.readText())
    }
}
