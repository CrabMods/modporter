package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Confidence
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KeyBackedDynamicAttributesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `arbitrary attribute and field names become key backed with exact provider roots`() {
        val root = directory.resolve("accepted")
        writeProject(root, admittedSources())
        val originals = readProject(root)

        val dryPass = StructuralRefactorPass()
        val proposed = dryPass.migrateProjectKeyBackedDynamicAttributes(root, dryRun = true)
        assertEquals(originals, readProject(root))
        assertEquals(6, proposed.size)
        assertTrue(proposed.all { it.confidence == Confidence.HIGH })
        assertTrue(proposed.all {
            it.ruleId == KeyBackedDynamicAttributes.RULE_ID
        })

        val changes = StructuralRefactorPass()
            .migrateProjectKeyBackedDynamicAttributes(root, dryRun = false)
        assertEquals(6, changes.size)

        val attributeSource = read(root, "example/RuneFacet.java")
        val attributeUnit = JavaParser().parse(attributeSource).result.orElseThrow()
        assertPrivateNullableField(
            attributeUnit.findAll(FieldDeclaration::class.java),
            "runeKey",
            "ResourceKey<Enchantment>"
        )
        assertPrivateNullableField(
            attributeUnit.findAll(FieldDeclaration::class.java),
            "directRune",
            "Holder<Enchantment>"
        )
        assertNullableConstructor(
            attributeUnit,
            "RuneFacet",
            "Holder<Enchantment>",
            "rune"
        )
        assertNullableConstructor(
            attributeUnit,
            "RuneFacet",
            "ResourceLocation",
            "modporterEnchantmentId"
        )
        assertNullableProviderOverload(
            attributeUnit,
            "getTranslationParameters",
            "Object[]"
        )
        val attribute = compact(attributeSource)
        assertTrue(attribute.contains("public RuneFacet()"), attribute)
        assertTrue(
            attribute.contains(
                "rune.unwrapKey().orElse(null)"
            ),
            attribute
        )
        assertFalse(attribute.contains("Holder.direct"), attribute)
        assertTrue(
            attribute.contains(
                "candidate.is(this.runeKey)"
            ),
            attribute
        )
        assertTrue(
            attribute.contains(
                "Objects.equals(candidate.value(), this.directRune.value())"
            ),
            attribute
        )
        assertTrue(
            attribute.contains(
                "this.runeKey.location()"
            ),
            attribute
        )
        assertTrue(
            attribute.contains(
                "ResourceKey.create(Registries.ENCHANTMENT, modporterEnchantmentId)"
            ),
            attribute
        )
        assertTrue(
            attribute.contains(
                "registries.lookup(Registries.ENCHANTMENT)"
            ),
            attribute
        )
        assertTrue(
            attribute.contains(
                "holder.value().description().getString()"
            ),
            attribute
        )
        assertEquals(
            2,
            Regex("""Object\[\] getTranslationParameters\(""")
                .findAll(attribute)
                .count(),
            attribute
        )
        assertEquals(
            1,
            Regex("""Object\[\] getTranslationParameters\(\)""")
                .findAll(attribute)
                .count(),
            attribute
        )
        assertTrue(
            attribute.contains(
                "new RuneFacet(modporterRune_)"
            ),
            attribute
        )
        assertTrue(attribute.contains("return new RuneFacet();"), attribute)
        assertFalse(
            attribute.contains(
                "Enchantment rune = modporterRune_.value()"
            ),
            attribute
        )
        assertTrue(attribute.contains("\"enchantId\""), attribute)

        val legacy = compact(read(root, "example/LegacyReaders.java"))
        assertTrue(
            legacy.contains(
                "new RuneFacet(ResourceLocation.tryParse(tag.getString(\"id\")))"
            ),
            legacy
        )
        assertTrue(legacy.contains("\"has_enchant\""), legacy)

        val contractSource = read(root, "example/Facet.java")
        val contractUnit = JavaParser().parse(contractSource).result.orElseThrow()
        assertNullableProviderOverload(
            contractUnit,
            "format",
            "MutableComponent",
            listOf("boolean" to "inverted"),
            requiresDefault = true
        )
        assertNullableProviderOverload(
            contractUnit,
            "getTranslationParameters",
            "Object[]",
            requiresDefault = true
        )
        val contract = compact(contractSource)
        assertTrue(
            contract.contains(
                "getTranslationParameters(registries)"
            ),
            contract
        )
        assertTrue(
            contract.contains(
                "if (registries == null) return format(inverted);"
            ),
            contract
        )
        assertTrue(
            contract.contains(
                "return getTranslationParameters();"
            ),
            contract
        )
        assertEquals(
            2,
            Regex("""default MutableComponent format\(""")
                .findAll(contract)
                .count(),
            contract
        )
        assertEquals(
            2,
            Regex("""default Object\[\] getTranslationParameters\(""")
                .findAll(contract)
                .count(),
            contract
        )
        val summary = compact(read(root, "example/AttributeFilterItem.java"))
        assertTrue(
            summary.contains("attribute.format(inverted, registries)"),
            summary
        )

        val screen = compact(read(root, "example/AttributeFilterScreen.java"))
        assertEquals(
            3,
            Regex("""\.format\([^;]+minecraft\.level\.registryAccess\(\)\)""")
                .findAll(screen)
                .count(),
            screen
        )
        assertEquals(
            4,
            Regex("""\.format\(""").findAll(summary + screen).count(),
            summary + screen
        )

        val filterItem = compact(read(root, "example/FilterItem.java"))
        assertTrue(
            filterItem.contains(
                "makeSummary(stack, worldIn.registries())"
            ),
            filterItem
        )
        assertTrue(
            filterItem.contains("Item.TooltipContext.EMPTY"),
            filterItem
        )

        val amuletSource = read(root, "example/AmuletFacet.java")
        val amuletUnit = JavaParser().parse(amuletSource).result.orElseThrow()
        assertNullableProviderOverload(
            amuletUnit,
            "getTranslationParameters",
            "Object[]"
        )
        val amulet = compact(amuletSource)
        assertTrue(
            amulet.contains(
                "ResourceKey.create(Registries.ENCHANTMENT, modporterEnchantmentId)"
            ),
            amulet
        )
        assertTrue(
            amulet.contains(
                "modporterEnchantmentHolder.value().description().getString()"
            ),
            amulet
        )
        assertTrue(amulet.contains("\"existing \" + label"), amulet)
        assertTrue(amulet.contains("\"runeName\""), amulet)
        assertTrue(amulet.contains("\"runeKind\""), amulet)
        assertTrue(
            amulet.contains(
                "if (registries == null) return getTranslationParameters();"
            ),
            amulet
        )
        assertEquals(
            2,
            Regex("""Object\[\] getTranslationParameters\(""")
                .findAll(amulet)
                .count(),
            amulet
        )
        val siblings = compact(read(root, "example/NativeFacets.java"))
        listOf(
            "\"#forge:ingots\"",
            "\"Create\"",
            "\"Alex\"",
            "\"Purple\""
        ).forEach { parameter ->
            assertTrue(siblings.contains(parameter), siblings)
        }

        val allChanged = changes.joinToString("\n") { it.file.readText() }
        assertFalse(allChanged.contains("BuiltInRegistries.ENCHANTMENT"), allChanged)
        assertFalse(allChanged.contains("getDescriptionId()"), allChanged)
        assertFalse(allChanged.contains("Minecraft.getInstance()"), allChanged)
        assertFalse(allChanged.contains("Holder.direct"), allChanged)

        val repeated = StructuralRefactorPass()
            .migrateProjectKeyBackedDynamicAttributes(root, dryRun = false)
        assertTrue(repeated.isEmpty(), repeated.toString())
    }

    @Test
    fun `unknown source constructor aborts the whole family before mutation`() {
        val root = directory.resolve("unknown-constructor")
        val sources = admittedSources() + (
            "example/ExternalFactory.java" to
                """
                package example;

                import net.minecraft.world.item.enchantment.Enchantment;

                final class ExternalFactory {
                    RuneFacet create(Enchantment enchantment) {
                        return new RuneFacet(enchantment);
                    }
                }
                """
            )
        writeProject(root, sources)
        val before = readProject(root)

        val changes = StructuralRefactorPass()
            .migrateProjectKeyBackedDynamicAttributes(root, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(before, readProject(root))
    }

    @Test
    fun `providerless format root aborts instead of using a client global fallback`() {
        val root = directory.resolve("unknown-format-root")
        val sources = admittedSources() + (
            "example/ServerFormatter.java" to
                """
                package example;

                final class ServerFormatter {
                    Object format(Facet attribute, boolean inverted) {
                        return attribute.format(inverted);
                    }
                }
                """
            )
        writeProject(root, sources)
        val before = readProject(root)

        val changes = StructuralRefactorPass()
            .migrateProjectKeyBackedDynamicAttributes(root, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(before, readProject(root))
    }

    @Test
    fun `reflective raw field dependency aborts before replacing its storage`() {
        val root = directory.resolve("reflection")
        val sources = admittedSources() + (
            "example/Reflection.java" to
                """
                package example;

                final class Reflection {
                    Object field() throws Exception {
                        return RuneFacet.class.getDeclaredField("rune");
                    }
                }
                """
            )
        writeProject(root, sources)
        val before = readProject(root)

        val changes = StructuralRefactorPass()
            .migrateProjectKeyBackedDynamicAttributes(root, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(before, readProject(root))
    }

    @Test
    fun `valid unknown IDs stay opaque and cannot fall through to direct matching`() {
        val root = directory.resolve("unknown-id")
        writeProject(root, admittedSources())

        val changes = StructuralRefactorPass()
            .migrateProjectKeyBackedDynamicAttributes(root, dryRun = false)

        assertEquals(6, changes.size)
        val attribute = compact(read(root, "example/RuneFacet.java"))
        assertTrue(
            attribute.contains(
                "this.runeKey = modporterEnchantmentId == null ? null : " +
                    "ResourceKey.create(Registries.ENCHANTMENT, modporterEnchantmentId);"
            ),
            attribute
        )
        assertTrue(
            attribute.contains(
                "this.directRune = null;"
            ),
            attribute
        )
        assertTrue(
            attribute.contains(
                "this.runeKey != null ? candidate.is(this.runeKey) : " +
                    "this.directRune != null && Objects.equals"
            ),
            attribute
        )
        assertTrue(attribute.contains(".orElse(\"\")"), attribute)
        assertFalse(attribute.contains("BuiltInRegistries.ENCHANTMENT"), attribute)
    }

    @Test
    fun `same named fake registry and helper APIs are ignored`() {
        val root = directory.resolve("fake")
        val source =
            """
            package fake;

            final class Facet {
                private Enchantment rune;

                Facet(Enchantment rune) {
                    this.rune = rune;
                }

                void save(Tag tag) {
                    tag.put("enchantId", BuiltInRegistries.ENCHANTMENT.getKey(rune));
                }

                void load(Tag tag) {
                    rune = BuiltInRegistries.ENCHANTMENT.get(tag.get("enchantId"));
                }
            }
            """.trimIndent() + "\n"
        writeProject(root, mapOf("fake/Facet.java" to source))

        val changes = StructuralRefactorPass()
            .migrateProjectKeyBackedDynamicAttributes(root, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(source, read(root, "fake/Facet.java"))
    }

    @Test
    fun `all six files are staged before replacement and an IO fault rolls back the family`() {
        val root = directory.resolve("io-fault")
        writeProject(root, admittedSources())
        val before = readProject(root)
        val fileIo = FailOnFourthStagedMove()

        assertFailsWith<IOException> {
            KeyBackedDynamicAttributes.migrate(
                StructuralRefactorPass(),
                root,
                dryRun = false,
                fileIo = fileIo
            )
        }

        assertEquals(6, fileIo.stagedWritesAtFirstMove)
        assertEquals(6, fileIo.backupCopiesAtFirstMove)
        assertEquals(before, readProject(root))
        assertFalse(
            root.toFile().walkTopDown().any {
                it.isFile && it.name.contains(".modporter-")
            }
        )
    }

    @Test
    fun `a pre-commit staging fault leaves sources untouched and cleans acquired temporaries`() {
        val root = directory.resolve("staging-fault")
        writeProject(root, admittedSources())
        val before = readProject(root)

        assertFailsWith<IOException> {
            KeyBackedDynamicAttributes.migrate(
                StructuralRefactorPass(),
                root,
                dryRun = false,
                fileIo = FailOnSecondBackupCreation()
            )
        }

        assertEquals(before, readProject(root))
        assertFalse(
            root.toFile().walkTopDown().any {
                it.isFile && it.name.contains(".modporter-")
            }
        )
    }

    @Test
    fun `a failed rollback retains its verified recovery backup`() {
        val root = directory.resolve("rollback-fault")
        writeProject(root, admittedSources())
        val before = readProject(root)
        val failure = assertFailsWith<IOException> {
            KeyBackedDynamicAttributes.migrate(
                StructuralRefactorPass(),
                root,
                dryRun = false,
                fileIo = FailFourthMoveAndFirstRollback()
            )
        }

        assertEquals(before, readProject(root))
        assertTrue(
            failure.suppressed.any {
                it.message?.contains("recovery copy retained at") == true
            }
        )
        val recoveryBackups = root.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".backup") }
            .toList()
        assertEquals(1, recoveryBackups.size)
        assertTrue(recoveryBackups.single().readText() in before.values)
        assertFalse(
            root.toFile().walkTopDown().any {
                it.isFile && it.name.endsWith(".stage")
            }
        )
    }

    private fun admittedSources(): Map<String, String> = mapOf(
        "example/Facet.java" to
            """
            package example;

            import com.simibubi.create.foundation.utility.CreateLang;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.MutableComponent;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public interface Facet {
                boolean appliesTo(ItemStack stack, Level level);
                Object getType();
                void save(CompoundTag tag);
                void load(CompoundTag tag);

                default MutableComponent format(boolean inverted) {
                    return CreateLang.translateDirect(
                        "facets." + getTranslationKey() + (inverted ? ".inverted" : ""),
                        getTranslationParameters());
                }

                String getTranslationKey();

                default Object[] getTranslationParameters() {
                    return new String[0];
                }
            }
            """,
        "example/Types.java" to
            """
            package example;

            final class Types {
                static final Object HAS_ENCHANT = new Object();
                static final Object AMULET = new Object();
            }
            """,
        "example/RuneFacet.java" to
            """
            package example;

            import java.util.ArrayList;
            import java.util.List;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.Holder;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            import net.minecraft.world.level.Level;
            import org.jetbrains.annotations.Nullable;

            public class RuneFacet implements Facet {
                private @Nullable Enchantment rune;

                public RuneFacet(@Nullable Enchantment rune) {
                    this.rune = rune;
                }

                @Override
                public boolean appliesTo(ItemStack stack, Level level) {
                    return EnchantmentHelper.getEnchantmentsForCrafting(stack)
                        .keySet()
                        .stream()
                        .map(Holder::value)
                        .anyMatch(java.util.Collections.singleton(rune)::contains);
                }

                @Override
                public String getTranslationKey() {
                    return "has_enchant";
                }

                @Override
                public Object[] getTranslationParameters() {
                    String parameter = "";
                    if (rune != null)
                        parameter = Component.translatable(rune.getDescriptionId()).getString();
                    return new Object[]{parameter};
                }

                @Override
                public Object getType() {
                    return Types.HAS_ENCHANT;
                }

                @Override
                public void save(CompoundTag tag) {
                    if (rune == null)
                        return;
                    ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(rune);
                    if (id == null)
                        return;
                    NBTHelper.writeResourceLocation(tag, "enchantId", id);
                }

                @Override
                public void load(CompoundTag tag) {
                    if (tag.contains("enchantId")) {
                        rune = BuiltInRegistries.ENCHANTMENT.get(
                            NBTHelper.readResourceLocation(tag, "enchantId"));
                    }
                }

                public static class Type {
                    public Facet createAttribute() {
                        return new RuneFacet(null);
                    }

                    public List<Facet> getAllAttributes(ItemStack stack, Level level) {
                        List<Facet> list = new ArrayList<>();
                        for (Holder<Enchantment> modporterRune_ :
                                EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet()) {
                            if (stack.isEmpty()) {
                                list.add(new RuneFacet(modporterRune_));
                            } else {
                                Enchantment rune = modporterRune_.value();
                                list.add(new RuneFacet(rune));
                            }
                        }
                        return list;
                    }
                }
            }
            """,
        "example/LegacyReaders.java" to
            """
            package example;

            import java.util.function.Function;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;

            final class LegacyReaders {
                static void register() {
                    createLegacyDeserializer("has_enchant", tag ->
                        new RuneFacet(BuiltInRegistries.ENCHANTMENT.get(
                            ResourceLocation.tryParse(tag.getString("id")))));
                }

                static void createLegacyDeserializer(
                        String key,
                        Function<CompoundTag, Facet> reader) {
                }
            }
            """,
        "example/AmuletFacet.java" to
            """
            package example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.level.Level;

            final class AmuletFacet implements Facet {
                String runeName;
                int runeKind;

                @Override
                public Object[] getTranslationParameters() {
                    String label = "";
                    Enchantment enchantment = BuiltInRegistries.ENCHANTMENT.get(
                        ResourceLocation.tryParse(runeName));
                    if (enchantment != null) {
                        label = Component.translatable(
                            enchantment.getDescriptionId()).getString();
                    }
                    if (runeKind == 1)
                        label = "existing " + label;
                    return new Object[]{label};
                }

                @Override
                public boolean appliesTo(ItemStack stack, Level level) {
                    return false;
                }

                @Override
                public String getTranslationKey() {
                    return "amulet";
                }

                @Override
                public Object getType() {
                    return Types.AMULET;
                }

                @Override
                public void save(CompoundTag tag) {
                    tag.putString("runeName", runeName);
                    tag.putInt("runeKind", runeKind);
                }

                @Override
                public void load(CompoundTag tag) {
                    runeName = tag.getString("runeName");
                    runeKind = tag.getInt("runeKind");
                }
            }
            """,
        "example/AttributeFilterItem.java" to
            """
            package example;

            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.network.chat.Component;

            final class AttributeFilterItem {
                List<Component> makeSummary(
                        Facet attribute,
                        boolean inverted,
                        HolderLookup.Provider registries) {
                    List<Component> list = new ArrayList<>();
                    list.add(Component.literal("- ")
                        .append(attribute.format(inverted)));
                    return list;
                }
            }
            """,
        "example/FilterItem.java" to
            """
            package example;

            import java.util.List;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;
            import org.jetbrains.annotations.Nullable;

            abstract class FilterItem {
                void appendHoverText(
                        ItemStack stack,
                        Item.TooltipContext worldIn,
                        List<Component> tooltip,
                        TooltipFlag flag) {
                    tooltip.addAll(makeSummary(stack, worldIn.registries()));
                }

                void appendEmptyContext(
                        ItemStack stack,
                        List<Component> tooltip,
                        TooltipFlag flag) {
                    appendHoverText(stack, Item.TooltipContext.EMPTY, tooltip, flag);
                }

                abstract List<Component> makeSummary(
                    ItemStack stack,
                    @Nullable HolderLookup.Provider registries);
            }
            """,
        "example/NativeFacets.java" to
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            abstract class NativeFacet implements Facet {
                @Override
                public boolean appliesTo(ItemStack stack, Level level) {
                    return false;
                }

                @Override
                public Object getType() {
                    return new Object();
                }

                @Override
                public void save(CompoundTag tag) {
                }

                @Override
                public void load(CompoundTag tag) {
                }
            }

            final class InTagFacet extends NativeFacet {
                @Override
                public String getTranslationKey() {
                    return "in_tag";
                }

                @Override
                public Object[] getTranslationParameters() {
                    return new Object[]{"#forge:ingots"};
                }
            }

            final class AddedByFacet extends NativeFacet {
                @Override
                public String getTranslationKey() {
                    return "added_by";
                }

                @Override
                public Object[] getTranslationParameters() {
                    return new Object[]{"Create"};
                }
            }

            final class BookAuthorFacet extends NativeFacet {
                @Override
                public String getTranslationKey() {
                    return "book_author";
                }

                @Override
                public Object[] getTranslationParameters() {
                    return new Object[]{"Alex"};
                }
            }

            final class ColorFacet extends NativeFacet {
                @Override
                public String getTranslationKey() {
                    return "color";
                }

                @Override
                public Object[] getTranslationParameters() {
                    return new Object[]{"Purple"};
                }
            }
            """,
        "example/Menu.java" to
            """
            package example;

            import java.util.List;
            import net.createmod.catnip.data.Pair;

            final class Menu {
                List<Pair<Facet, Boolean>> selectedAttributes;
            }
            """,
        "example/FacetType.java" to
            """
            package example;

            import java.util.List;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            interface FacetType {
                List<Facet> getAllAttributes(ItemStack stack, Level level);
            }
            """,
        "example/AttributeFilterScreen.java" to
            """
            package example;

            import java.util.ArrayList;
            import java.util.List;
            import net.createmod.catnip.data.Pair;
            import net.minecraft.world.item.ItemStack;

            final class AttributeFilterScreen extends AbstractRuneScreen {
                private final List<Facet> available = new ArrayList<>();

                AttributeFilterScreen(Menu menu) {
                    super(menu);
                }

                void init() {
                    menu.selectedAttributes.forEach(at ->
                        at.getFirst().format(at.getSecond()));
                }

                void referenceItemChanged(
                        ItemStack stack,
                        Iterable<FacetType> types) {
                    available.clear();
                    for (FacetType type : types)
                        available.addAll(type.getAllAttributes(stack, minecraft.level));
                    available.stream()
                        .map(attribute -> attribute.format(false))
                        .toList();
                }

                void handleAddedAttibute(Facet attribute, boolean inverted) {
                    attribute.format(inverted);
                }
            }
            """,
        "example/AbstractRuneScreen.java" to
            """
            package example;

            abstract class AbstractRuneScreen {
                protected final Menu menu;
                protected final MinecraftClient minecraft = null;

                AbstractRuneScreen(Menu menu) {
                    this.menu = menu;
                }
            }
            """,
        "example/MinecraftClient.java" to
            """
            package example;

            final class MinecraftClient {
                net.minecraft.world.level.Level level;
            }
            """
    )

    private fun writeProject(root: Path, sources: Map<String, String>) {
        val java = root.resolve("src/main/java")
        sources.forEach { (relative, source) ->
            java.resolve(relative).also { file ->
                file.parent.createDirectories()
                file.writeText(source.trimIndent() + "\n")
            }
        }
    }

    private fun readProject(root: Path): Map<String, String> {
        val java = root.resolve("src/main/java")
        return java.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .associate { file ->
                java.relativize(file.toPath()).toString().replace('\\', '/') to
                    file.readText()
            }
    }

    private fun read(root: Path, relative: String): String =
        root.resolve("src/main/java").resolve(relative).readText()

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()

    private fun assertPrivateNullableField(
        fields: List<FieldDeclaration>,
        name: String,
        expectedType: String
    ) {
        val (field, variable) = fields.mapNotNull { field ->
            field.variables.singleOrNull { it.nameAsString == name }
                ?.let { field to it }
        }.single()
        assertTrue(
            field.modifiers.any { it.keyword == Modifier.Keyword.PRIVATE },
            field.toString()
        )
        assertTrue(
            field.annotations.any { it.nameAsString == "Nullable" } ||
                variable.type.annotations.any { it.nameAsString == "Nullable" },
            field.toString()
        )
        val unannotatedType = variable.type.clone()
        unannotatedType.annotations.clear()
        assertEquals(expectedType, unannotatedType.asString(), field.toString())
    }

    private fun assertNullableConstructor(
        unit: CompilationUnit,
        owner: String,
        expectedType: String,
        expectedName: String
    ) {
        val constructor = unit.findAll(ConstructorDeclaration::class.java)
            .filter { it.nameAsString == owner && it.parameters.size == 1 }
            .filter {
                unannotatedType(it.parameters.single().type) == expectedType &&
                    it.parameters.single().nameAsString == expectedName
            }
            .single()
        assertNullable(constructor.parameters.single(), constructor.toString())
    }

    private fun assertNullableProviderOverload(
        unit: CompilationUnit,
        name: String,
        expectedReturnType: String,
        leadingParameters: List<Pair<String, String>> = emptyList(),
        requiresDefault: Boolean = false
    ) {
        val expectedParameters =
            leadingParameters + ("HolderLookup.Provider" to "registries")
        val method = unit.findAll(MethodDeclaration::class.java)
            .filter {
                it.nameAsString == name &&
                    unannotatedType(it.type) == expectedReturnType &&
                    it.parameters.size == expectedParameters.size
            }
            .filter { candidate ->
                candidate.parameters.zip(expectedParameters).all { (parameter, expected) ->
                    unannotatedType(parameter.type) == expected.first &&
                        parameter.nameAsString == expected.second
                }
            }
            .single()
        if (requiresDefault) {
            assertTrue(
                method.modifiers.any { it.keyword == Modifier.Keyword.DEFAULT },
                method.toString()
            )
        }
        assertNullable(method.parameters.last(), method.toString())
    }

    private fun assertNullable(parameter: Parameter, message: String) {
        assertTrue(
            parameter.annotations.any { it.nameAsString == "Nullable" } ||
                parameter.type.annotations.any { it.nameAsString == "Nullable" },
            message
        )
    }

    private fun unannotatedType(type: Type): String {
        val result = type.clone()
        result.annotations.clear()
        return result.asString()
    }

    private class FailOnSecondBackupCreation :
        KeyBackedDynamicAttributes.ProjectFileIo {
        private val delegate = KeyBackedDynamicAttributes.NioProjectFileIo
        private var backupCreations = 0

        override fun readText(path: Path): String = delegate.readText(path)

        override fun createTempSibling(target: Path, suffix: String): Path {
            if (suffix == ".backup") {
                backupCreations += 1
                if (backupCreations == 2) {
                    throw IOException("fixture failure on second backup creation")
                }
            }
            return delegate.createTempSibling(target, suffix)
        }

        override fun writeText(path: Path, source: String) {
            delegate.writeText(path, source)
        }

        override fun copy(source: Path, target: Path) {
            delegate.copy(source, target)
        }

        override fun moveReplace(source: Path, target: Path) {
            delegate.moveReplace(source, target)
        }

        override fun deleteIfExists(path: Path) {
            delegate.deleteIfExists(path)
        }
    }

    private class FailOnFourthStagedMove :
        KeyBackedDynamicAttributes.ProjectFileIo {
        private val delegate = KeyBackedDynamicAttributes.NioProjectFileIo
        private var stagedWrites = 0
        private var backupCopies = 0
        private var stagedMoves = 0
        private var faultInjected = false

        var stagedWritesAtFirstMove: Int = -1
            private set
        var backupCopiesAtFirstMove: Int = -1
            private set

        override fun readText(path: Path): String = delegate.readText(path)

        override fun createTempSibling(target: Path, suffix: String): Path =
            delegate.createTempSibling(target, suffix)

        override fun writeText(path: Path, source: String) {
            if (path.fileName.toString().endsWith(".stage")) stagedWrites++
            delegate.writeText(path, source)
        }

        override fun copy(source: Path, target: Path) {
            if (target.fileName.toString().endsWith(".backup")) backupCopies++
            delegate.copy(source, target)
        }

        override fun moveReplace(source: Path, target: Path) {
            if (source.fileName.toString().endsWith(".stage")) {
                if (stagedMoves == 0) {
                    stagedWritesAtFirstMove = stagedWrites
                    backupCopiesAtFirstMove = backupCopies
                }
                stagedMoves++
                if (stagedMoves == 4 && !faultInjected) {
                    faultInjected = true
                    throw IOException("fixture failure on fourth staged replacement")
                }
            }
            delegate.moveReplace(source, target)
        }

        override fun deleteIfExists(path: Path) {
            delegate.deleteIfExists(path)
        }
    }

    private class FailFourthMoveAndFirstRollback :
        KeyBackedDynamicAttributes.ProjectFileIo {
        private val delegate = KeyBackedDynamicAttributes.NioProjectFileIo
        private var stagedMoves = 0
        private var rollbackMoves = 0

        override fun readText(path: Path): String = delegate.readText(path)

        override fun createTempSibling(target: Path, suffix: String): Path =
            delegate.createTempSibling(target, suffix)

        override fun writeText(path: Path, source: String) {
            delegate.writeText(path, source)
        }

        override fun copy(source: Path, target: Path) {
            delegate.copy(source, target)
        }

        override fun moveReplace(source: Path, target: Path) {
            when {
                source.fileName.toString().endsWith(".stage") -> {
                    stagedMoves++
                    if (stagedMoves == 4) {
                        throw IOException("fixture failure on fourth staged replacement")
                    }
                }
                source.fileName.toString().endsWith(".backup") -> {
                    rollbackMoves++
                    if (rollbackMoves == 1) {
                        throw IOException("fixture failure on first rollback replacement")
                    }
                }
            }
            delegate.moveReplace(source, target)
        }

        override fun deleteIfExists(path: Path) {
            delegate.deleteIfExists(path)
        }
    }
}
