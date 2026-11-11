package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.BreakStmt
import com.github.javaparser.ast.stmt.ContinueStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.TryStmt
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

class CreateAttributeFilterDataMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `generated borrow helper commits mutation and throw before every exit shape`() {
        val fixture = writeFixture(directory.resolve("behavior"))
        val result = CreateAttributeFilterDataMigration.migrate(fixture.project, false)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)

        val itemUnit = parseJava(fixture.item)
        val menuUnit = parseJava(fixture.menu)
        val itemOwner = owner(itemUnit, "AttributeFilterItem")
        val menuOwner = owner(menuUnit, "AttributeFilterMenu")
        val helper = itemOwner.methods.single { it.nameAsString == HELPER }
        val helperTry = helper.findAll(TryStmt::class.java).single()
        assertTrue(helperTry.finallyBlock.isPresent)

        val makeSummary = itemOwner.methods.single { it.nameAsString == "makeSummary" }
        val getFilterItems = itemOwner.methods.single { it.nameAsString == "getFilterItems" }
        val initAndReadInventory = menuOwner.methods.single { it.nameAsString == "initAndReadInventory" }
        assertEquals(1, helperCalls(makeSummary).size)
        assertEquals(1, helperCalls(getFilterItems).size)
        assertEquals(1, helperCalls(initAndReadInventory).size)
        assertTrue(helperCalls(makeSummary).single().findAncestor(ForEachStmt::class.java).isPresent)
        assertTrue(makeSummary.findAll(ContinueStmt::class.java).isNotEmpty())
        assertTrue(makeSummary.findAll(BreakStmt::class.java).isNotEmpty())
        assertTrue(helperCalls(initAndReadInventory).single().findAncestor(LambdaExpr::class.java).isPresent)
        assertTrue(
            listOf(makeSummary, getFilterItems, initAndReadInventory).all { method ->
                method.findAll(MethodCallExpr::class.java).none { it.nameAsString == "getOrCreateTag" }
            }
        )

        val observed = executeGeneratedHelper(directory.resolve("helper-runtime"), helper.toString())
        assertEquals(
            "kept:MUTATE|kept:MUTATE|kept:MUTATE|kept:MUTATE|kept:THROW",
            observed
        )
    }

    @Test
    fun `dry run is byte stable and committed migration is idempotent`() {
        val fixture = writeFixture(directory.resolve("idempotent"))
        val before = mapOf(fixture.item to fixture.item.readBytes(), fixture.menu to fixture.menu.readBytes())
        val dryRun = CreateAttributeFilterDataMigration.migrate(fixture.project, true)
        assertTrue(dryRun.gates.isEmpty(), dryRun.gates.joinToString("\n"))
        assertEquals(2, dryRun.changes.size)
        before.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }

        val first = CreateAttributeFilterDataMigration.migrate(fixture.project, false)
        assertTrue(first.gates.isEmpty(), first.gates.joinToString("\n"))
        assertEquals(2, first.changes.size)
        val after = mapOf(fixture.item to fixture.item.readBytes(), fixture.menu to fixture.menu.readBytes())
        val second = CreateAttributeFilterDataMigration.migrate(fixture.project, false)
        assertTrue(second.gates.isEmpty(), second.gates.joinToString("\n"))
        assertTrue(second.changes.isEmpty())
        after.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test
    fun `alias escape extra stack writes and changed callback refuse atomically`() {
        val variants = listOf<(Fixture) -> Unit>(
            { fixture ->
                val loader = fixture.safeLoader
                loader.writeText(
                    loader.readText().replace(
                        "public void load(CompoundTag nbt) {",
                        "private CompoundTag retained; public void load(CompoundTag nbt) { retained = nbt;"
                    )
                )
            },
            { fixture ->
                fixture.item.writeText(
                    fixture.item.readText().replace(
                        "List<Component> list = new ArrayList<>();",
                        "filter.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                            "net.minecraft.world.item.component.CustomData.EMPTY); " +
                            "List<Component> list = new ArrayList<>();"
                    )
                )
            },
            { fixture ->
                fixture.item.writeText(
                    fixture.item.readText().replace(
                        "List<Component> list = new ArrayList<>();",
                        "consume(filter); List<Component> list = new ArrayList<>();"
                    )
                )
            },
            { fixture ->
                fixture.legacyAdapter.writeText(
                    fixture.legacyAdapter.readText().replace(
                        "return func.apply(nbt);",
                        "retain(nbt); return func.apply(nbt);"
                    )
                )
            },
            { fixture ->
                writeJava(
                    fixture.project,
                    "src/main/java/example/RetainingAnonymousAttribute.java",
                    """
                    package example;
                    import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
                    import net.minecraft.nbt.CompoundTag;
                    public class RetainingAnonymousAttribute {
                        private static CompoundTag retained;
                        public static final ItemAttribute VALUE = new ItemAttribute() {
                            public void load(CompoundTag nbt) { retained = nbt; }
                        };
                    }
                    """.trimIndent()
                )
            }
        )
        variants.forEachIndexed { index, alter ->
            val fixture = writeFixture(directory.resolve("refuse-$index"))
            alter(fixture)
            val before = mapOf(fixture.item to fixture.item.readBytes(), fixture.menu to fixture.menu.readBytes())
            val result = CreateAttributeFilterDataMigration.migrate(fixture.project, false)
            assertTrue(result.changes.isEmpty(), "variant $index")
            assertTrue(result.gates.isNotEmpty(), "variant $index")
            before.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        }
    }

    @Test
    fun `second publish failure restores the complete two file family and removes stages`() {
        val fixture = writeFixture(directory.resolve("rollback"))
        val before = mapOf(fixture.item to fixture.item.readBytes(), fixture.menu to fixture.menu.readBytes())
        val result = CreateAttributeFilterDataMigration.migrate(fixture.project, false) { index, _ ->
            if (index == 1) error("injected publish failure")
        }
        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("injected publish failure"))
        before.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        Files.walk(fixture.project).use { paths ->
            assertTrue(
                paths.noneMatch { path ->
                    path.fileName.toString().contains("modporter-attribute-filter")
                }
            )
        }
    }

    @Test
    fun `optional actual converted source projects the family without writes`() {
        val configured = System.getenv("MODPORTER_CREATE_ITEM_DATA_REPLAY")
        assumeTrue(!configured.isNullOrBlank(), "Optional actual converted-source replay")
        val sourceRoot = Path.of(configured!!)
        val project = sourceRoot.parent.parent.parent
        val item = project.resolve(ITEM_RELATIVE)
        val menu = project.resolve(MENU_RELATIVE)
        val before = mapOf(item to item.readBytes(), menu to menu.readBytes())
        val result = CreateAttributeFilterDataMigration.migrate(project, true)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString("\n"))
        assertEquals(2, result.changes.size)
        before.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    private fun parseJava(path: Path): com.github.javaparser.ast.CompilationUnit {
        val parsed = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        ).parse(path)
        assertTrue(parsed.isSuccessful, parsed.problems.joinToString("\n"))
        return parsed.result.orElseThrow()
    }

    private fun helperCalls(method: MethodDeclaration): List<MethodCallExpr> =
        method.findAll(MethodCallExpr::class.java).filter { it.nameAsString == HELPER }

    private fun owner(
        unit: com.github.javaparser.ast.CompilationUnit,
        name: String
    ): ClassOrInterfaceDeclaration = unit.findAll(ClassOrInterfaceDeclaration::class.java).single {
        it.nameAsString == name
    }

    private fun executeGeneratedHelper(root: Path, helperSource: String): String {
        val sources = root.resolve("src")
        val classes = root.resolve("classes")
        classes.createDirectories()
        writeJava(
            sources,
            "net/minecraft/nbt/CompoundTag.java",
            """
            package net.minecraft.nbt;
            import java.util.LinkedHashMap;
            import java.util.Map;
            public class CompoundTag {
                private final Map<String, Object> values = new LinkedHashMap<>();
                public boolean isEmpty() { return values.isEmpty(); }
                public void putString(String key, String value) { values.put(key, value); }
                public String getString(String key) {
                    Object value = values.get(key);
                    return value instanceof String ? (String) value : "";
                }
                public void put(String key, CompoundTag value) { values.put(key, value); }
                public CompoundTag getCompound(String key) {
                    Object value = values.get(key);
                    return value instanceof CompoundTag ? (CompoundTag) value : new CompoundTag();
                }
                public CompoundTag copy() {
                    CompoundTag copy = new CompoundTag();
                    for (Map.Entry<String, Object> entry : values.entrySet()) {
                        Object value = entry.getValue();
                        copy.values.put(entry.getKey(), value instanceof CompoundTag
                            ? ((CompoundTag) value).copy() : value);
                    }
                    return copy;
                }
            }
            """.trimIndent()
        )
        writeJava(
            sources,
            "net/minecraft/core/component/DataComponents.java",
            """
            package net.minecraft.core.component;
            public final class DataComponents {
                public static final Object CUSTOM_DATA = new Object();
                private DataComponents() {}
            }
            """.trimIndent()
        )
        writeJava(
            sources,
            "net/minecraft/world/item/component/CustomData.java",
            """
            package net.minecraft.world.item.component;
            import net.minecraft.nbt.CompoundTag;
            public final class CustomData {
                public static final CustomData EMPTY = new CustomData(new CompoundTag());
                private final CompoundTag tag;
                private CustomData(CompoundTag tag) { this.tag = tag; }
                public static CustomData of(CompoundTag tag) { return new CustomData(tag.copy()); }
                public CompoundTag copyTag() { return tag.copy(); }
            }
            """.trimIndent()
        )
        writeJava(
            sources,
            "net/minecraft/world/item/ItemStack.java",
            """
            package net.minecraft.world.item;
            import net.minecraft.world.item.component.CustomData;
            public class ItemStack {
                private CustomData data = CustomData.EMPTY;
                public void remove(Object component) { data = CustomData.EMPTY; }
                public void set(Object component, CustomData value) { data = value; }
                public CustomData getOrDefault(Object component, CustomData fallback) { return data; }
            }
            """.trimIndent()
        )
        writeJava(
            sources,
            "com/simibubi/create/content/logistics/item/filter/attribute/ItemAttribute.java",
            """
            package com.simibubi.create.content.logistics.item.filter.attribute;
            import net.minecraft.nbt.CompoundTag;
            public class ItemAttribute {
                public enum Mode { MUTATE, THROW }
                public static Mode mode = Mode.MUTATE;
                public static ItemAttribute loadStatic(CompoundTag child) {
                    child.putString("loader", mode.name());
                    if (mode == Mode.THROW)
                        throw new IllegalStateException("loader failed");
                    return new ItemAttribute();
                }
            }
            """.trimIndent()
        )
        writeJava(
            sources,
            "com/simibubi/create/content/logistics/filter/GeneratedBorrowHarness.java",
            """
            package com.simibubi.create.content.logistics.filter;
            import java.util.List;
            import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.component.CustomData;
            import net.minecraft.core.component.DataComponents;
            public class GeneratedBorrowHarness {
                $helperSource

                private static ItemStack prepared(CompoundTag[] borrowed) {
                    CompoundTag stored = new CompoundTag();
                    stored.putString("sibling", "kept");
                    CompoundTag child = new CompoundTag();
                    stored.put("child", child);
                    ItemStack owner = new ItemStack();
                    owner.set(DataComponents.CUSTOM_DATA, CustomData.of(stored));
                    CompoundTag root = owner.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                    borrowed[0] = root;
                    borrowed[1] = root.getCompound("child");
                    return owner;
                }

                private static String observed(ItemStack owner) {
                    CompoundTag root = owner.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                    return root.getString("sibling") + ":" + root.getCompound("child").getString("loader");
                }

                private static String continueExit() {
                    CompoundTag[] borrowed = new CompoundTag[2];
                    ItemStack owner = prepared(borrowed);
                    ItemAttribute.mode = ItemAttribute.Mode.MUTATE;
                    for (int i = 0; i < 1; i++) {
                        $HELPER(owner, borrowed[0], borrowed[1]);
                        continue;
                    }
                    return observed(owner);
                }

                private static String breakExit() {
                    CompoundTag[] borrowed = new CompoundTag[2];
                    ItemStack owner = prepared(borrowed);
                    ItemAttribute.mode = ItemAttribute.Mode.MUTATE;
                    while (true) {
                        $HELPER(owner, borrowed[0], borrowed[1]);
                        break;
                    }
                    return observed(owner);
                }

                private static String earlyReturn() {
                    CompoundTag[] borrowed = new CompoundTag[2];
                    ItemStack owner = prepared(borrowed);
                    ItemAttribute.mode = ItemAttribute.Mode.MUTATE;
                    $HELPER(owner, borrowed[0], borrowed[1]);
                    return observed(owner);
                }

                private static String lambdaExit() {
                    CompoundTag[] borrowed = new CompoundTag[2];
                    ItemStack owner = prepared(borrowed);
                    ItemAttribute.mode = ItemAttribute.Mode.MUTATE;
                    List.of(borrowed[1]).forEach(child ->
                        $HELPER(owner, borrowed[0], child));
                    return observed(owner);
                }

                private static String throwingExit() {
                    CompoundTag[] borrowed = new CompoundTag[2];
                    ItemStack owner = prepared(borrowed);
                    ItemAttribute.mode = ItemAttribute.Mode.THROW;
                    try {
                        $HELPER(owner, borrowed[0], borrowed[1]);
                    } catch (IllegalStateException expected) {
                    }
                    return observed(owner);
                }

                public static String run() {
                    return String.join("|", continueExit(), breakExit(), earlyReturn(),
                        lambdaExit(), throwingExit());
                }
            }
            """.trimIndent()
        )

        val compiler = ToolProvider.getSystemJavaCompiler()
        assumeTrue(compiler != null, "JDK compiler is required")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { manager ->
            val javaFiles = Files.walk(sources).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".java") }.toList()
            }
            val task = compiler.getTask(
                null,
                manager,
                diagnostics,
                listOf("-d", classes.toString()),
                null,
                manager.getJavaFileObjectsFromPaths(javaFiles)
            )
            assertTrue(
                task.call(),
                diagnostics.diagnostics.joinToString("\n") { it.toString() }
            )
        }
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val harness = loader.loadClass(
                "com.simibubi.create.content.logistics.filter.GeneratedBorrowHarness"
            )
            return harness.getMethod("run").invoke(null) as String
        }
    }

    private fun writeJava(root: Path, relative: String, source: String) {
        val path = root.resolve(relative)
        path.parent.createDirectories()
        Files.writeString(path, source + "\n", StandardCharsets.UTF_8)
    }


    private fun writeFixture(project: Path): Fixture {
        val item = project.resolve(ITEM_RELATIVE)
        val menu = project.resolve(MENU_RELATIVE)

        val safeLoader = project.resolve(
            "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/SafeAttribute.java"
        )

        val legacyAdapter = project.resolve(
            "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/legacydeserializers/" +
                "AllItemAttributeLegacyDeserializers.java"
        )

        writeJava(
            project,
            ITEM_RELATIVE,
            """
            package com.simibubi.create.content.logistics.filter;

            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;
            import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.InTagAttribute;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            public class AttributeFilterItem {
                public List<Component> makeSummary(ItemStack filter) {
                    if (!filter.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA))
                        return Collections.emptyList();
                    List<Component> list = new ArrayList<>();
                    WhitelistMode whitelistMode = WhitelistMode.values()[filter.getOrCreateTag().getInt("WhitelistMode")];
                    list.add((whitelistMode == WhitelistMode.WHITELIST_CONJ
                        ? CreateLang.translateDirect("gui.attribute_filter.allow_list_conjunctive")
                        : whitelistMode == WhitelistMode.WHITELIST_DISJ
                        ? CreateLang.translateDirect("gui.attribute_filter.allow_list_disjunctive")
                        : CreateLang.translateDirect("gui.attribute_filter.deny_list")).withStyle(ChatFormatting.GOLD));
                    int count = 0;
                    ListTag attributes = filter.getOrCreateTag().getList("MatchedAttributes", Tag.TAG_COMPOUND);
                    for (Tag inbt : attributes) {
                        CompoundTag compound = (CompoundTag) inbt;
                        ItemAttribute attribute = ItemAttribute.loadStatic(compound);
                        if (attribute == null) continue;
                        boolean inverted = compound.getBoolean("Inverted");
                        if (count > 3) {
                            list.add(Component.literal("- ...").withStyle(ChatFormatting.DARK_GRAY));
                            break;
                        }
                        list.add(Component.literal("- ").append(attribute.format(inverted)));
                        count++;
                    }
                    if (count == 0) return Collections.emptyList();
                    return list;
                }

                public ItemStack[] getFilterItems(ItemStack itemStack) {
                    CompoundTag tag = itemStack.getOrCreateTag();
                    WhitelistMode whitelistMode = WhitelistMode.values()[tag.getInt("WhitelistMode")];
                    ListTag attributes = tag.getList("MatchedAttributes", net.minecraft.nbt.Tag.TAG_COMPOUND);
                    if (whitelistMode == WhitelistMode.WHITELIST_DISJ && attributes.size() == 1) {
                        ItemAttribute fromNBT = ItemAttribute.loadStatic((CompoundTag) attributes.get(0));
                        if (fromNBT instanceof InTagAttribute inTag) {
                            if (BuiltInRegistries.ITEM.getTag(inTag.tag).isPresent()) {
                                java.util.List<Item> taggedItems = java.util.stream.StreamSupport.stream(BuiltInRegistries.ITEM.getTagOrEmpty(inTag.tag).spliterator(), false).map(net.minecraft.core.Holder::value).toList();
                                if (!taggedItems.isEmpty()) {
                                    ItemStack[] stacks = new ItemStack[taggedItems.size()];
                                    int i = 0;
                                    for (Item item : taggedItems) {
                                        stacks[i] = new ItemStack(item);
                                        i++;
                                    }
                                    return stacks;
                                }
                            }
                        }
                    }
                    return new ItemStack[0];
                }
            }
            """.trimIndent()
        )
        writeJava(
            project,
            "src/main/java/com/simibubi/create/content/logistics/filter/AbstractFilterMenu.java",
            """
            package com.simibubi.create.content.logistics.filter;

            import net.minecraft.world.item.ItemStack;

            public class AbstractFilterMenu {
                protected void initAndReadInventory(ItemStack filterItem) {
                }
            }
            """.trimIndent()
        )
        writeJava(
            project,
            MENU_RELATIVE,
            """
            package com.simibubi.create.content.logistics.filter;

            import java.util.ArrayList;
            import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.world.item.ItemStack;

            public class AttributeFilterMenu extends AbstractFilterMenu {
                protected void initAndReadInventory(ItemStack filterItem) {
                    super.initAndReadInventory(filterItem);
                    selectedAttributes = new ArrayList<>();
                    whitelistMode = WhitelistMode.values()[filterItem.getOrCreateTag()
                        .getInt("WhitelistMode")];
                    ListTag attributes = filterItem.getOrCreateTag()
                        .getList("MatchedAttributes", Tag.TAG_COMPOUND);
                    attributes.forEach(inbt -> {
                        CompoundTag compound = (CompoundTag) inbt;
                        ItemAttribute attribute = ItemAttribute.loadStatic(compound);
                        if (attribute != null)
                            selectedAttributes.add(Pair.of(attribute, compound.getBoolean("Inverted")));
                    });
                }
            }
            """.trimIndent()
        )
        writeJava(
            project,
            "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/ItemAttribute.java",
            """
            package com.simibubi.create.content.logistics.item.filter.attribute;

            import java.util.ArrayList;
            import java.util.List;
            import com.simibubi.create.AllTags;
            import com.simibubi.create.foundation.data.CreateBuiltInRegistries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;

            public interface ItemAttribute {
                static ItemAttribute loadStatic(CompoundTag nbt) {
                    for (LegacyDeserializer deserializer : LegacyDeserializer.ALL) {
                        if (deserializer.canRead(nbt)) {
                            return deserializer.readNBT(nbt.getCompound(deserializer.getNBTKey()));
                        }
                    }
                    ResourceLocation id = ResourceLocation.tryParse(nbt.getString("attributeId"));
                    if (id == null) return null;
                    ItemAttributeType type = CreateBuiltInRegistries.ITEM_ATTRIBUTE_TYPE.get(id);
                    if (type == null) return null;
                    ItemAttribute attribute = type.createAttribute();
                    attribute.load(nbt);
                    return attribute;
                }

                void load(CompoundTag nbt);

                interface LegacyDeserializer {
                    List<LegacyDeserializer> ALL = new ArrayList<>();

                    default boolean canRead(CompoundTag nbt) {
                        return nbt.contains(getNBTKey());
                    }

                    String getNBTKey();

                    ItemAttribute readNBT(CompoundTag nbt);
                }
            }
            """.trimIndent()
        )
        writeJava(
            project,
            "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/SafeAttribute.java",
            """
            package com.simibubi.create.content.logistics.item.filter.attribute;

            import net.minecraft.nbt.CompoundTag;

            public class SafeAttribute implements ItemAttribute {
                public void load(CompoundTag nbt) {
                    nbt.putString("loaded", "yes");
                }
            }
            """.trimIndent()
        )
        writeJava(
            project,
            "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/ItemAttributeType.java",
            """
            package com.simibubi.create.content.logistics.item.filter.attribute;

            public interface ItemAttributeType {
                ItemAttribute createAttribute();
            }
            """.trimIndent()
        )
        writeJava(
            project,
            "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/legacydeserializers/" +
                "StandardTraitsLegacyDeserializer.java",
            """
            package com.simibubi.create.content.logistics.item.filter.attribute.legacydeserializers;

            import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.SafeAttribute;
            import net.minecraft.nbt.CompoundTag;

            public class StandardTraitsLegacyDeserializer implements ItemAttribute.LegacyDeserializer {
                public String getNBTKey() {
                    return "standard_trait";
                }

                public ItemAttribute readNBT(CompoundTag nbt) {
                    if (nbt.contains("x")) return new SafeAttribute();
                    return null;
                }
            }
            """.trimIndent()
        )
        writeJava(
            project,
            "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/legacydeserializers/" +
                "AllItemAttributeLegacyDeserializers.java",
            """
            package com.simibubi.create.content.logistics.item.filter.attribute.legacydeserializers;

            import java.util.function.Function;
            import com.simibubi.create.AllTags.AllItemTags;
            import com.simibubi.create.content.logistics.item.filter.AllItemAttributeTypes;
            import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttributeType;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.AddedByAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.AstralAttunementAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.AstralCrystalAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.AstralPerkGemAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.BookAuthorAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.BookCopyAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.ColorAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.EnchantAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.FluidContentsAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.HasNameAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.InTagAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.ItemGroupAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.ShulkerFillLevelAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.StandardTraitsAttribute;
            import com.simibubi.create.content.logistics.item.filter.attribute.attributes.ShulkerFillLevelAttribute.ShulkerLevels;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.ItemTags;
            import net.minecraft.world.item.DyeColor;

            public class AllItemAttributeLegacyDeserializers {
                public static void register() {
                    addLegacyDeserializer(new StandardTraitsLegacyDeserializer());
                    createLegacyDeserializer("in_tag", tag -> new InTagAttribute(ItemTags.create(ResourceLocation.fromNamespaceAndPath(tag.getString("space"), tag.getString("path")))));
                    createLegacyDeserializer("in_item_group", AllItemAttributeTypes.IN_ITEM_GROUP);
                    createLegacyDeserializer("added_by", tag -> new AddedByAttribute(tag.getString("id")));
                    createLegacyDeserializer("has_enchant", tag -> new EnchantAttribute(BuiltInRegistries.ENCHANTMENT.get(ResourceLocation.tryParse(tag.getString("id")))));
                    createLegacyDeserializer("shulker_fill_level", tag -> new ShulkerFillLevelAttribute(ShulkerLevels.fromKey(tag.getString("id"))));
                    createLegacyDeserializer("has_color", tag -> new ColorAttribute(DyeColor.byId(tag.getInt("id"))));
                    createLegacyDeserializer("has_fluid", tag -> new FluidContentsAttribute(BuiltInRegistries.FLUID.get(ResourceLocation.tryParse(tag.getString("id")))));
                    createLegacyDeserializer("has_name", AllItemAttributeTypes.HAS_NAME);
                    createLegacyDeserializer("book_author", AllItemAttributeTypes.BOOK_AUTHOR);
                    createLegacyDeserializer("book_copy", AllItemAttributeTypes.BOOK_COPY);
                    createLegacyDeserializer("astralsorcery_amulet", AllItemAttributeTypes.ASTRAL_AMULET);
                    createLegacyDeserializer("astralsorcery_constellation", AllItemAttributeTypes.ASTRAL_ATTUNMENT);
                    createLegacyDeserializer("astralsorcery_crystal", AllItemAttributeTypes.ASTRAL_CRYSTAL);
                    createLegacyDeserializer("astralsorcery_perk_gem", AllItemAttributeTypes.ASTRAL_PERK_GEM);
                }

                private static void createLegacyDeserializer(String nbtKey, ItemAttributeType type) {
                    createLegacyDeserializer(nbtKey, tag -> {
                        ItemAttribute attribute = type.createAttribute();
                        attribute.load(tag);
                        return attribute;
                    });
                }

                private static void createLegacyDeserializer(
                    String nbtKey, Function<CompoundTag, ItemAttribute> func) {
                    addLegacyDeserializer(new ItemAttribute.LegacyDeserializer() {
                        public String getNBTKey() { return nbtKey; }
                        public ItemAttribute readNBT(CompoundTag nbt) { return func.apply(nbt); }
                    });
                }

                private static void addLegacyDeserializer(
                    ItemAttribute.LegacyDeserializer legacyDeserializer) {
                    ItemAttribute.LegacyDeserializer.ALL.add(legacyDeserializer);
                }
            }
            """.trimIndent()
        )
        return Fixture(project, item, menu, safeLoader, legacyAdapter)
    }

    private data class Fixture(
        val project: Path,
        val item: Path,
        val menu: Path,
        val safeLoader: Path,
        val legacyAdapter: Path
    )

    companion object {
        private const val HELPER = "modporterLoadStaticAndCommit"
        private const val ITEM_RELATIVE =
            "src/main/java/com/simibubi/create/content/logistics/filter/AttributeFilterItem.java"
        private const val MENU_RELATIVE =
            "src/main/java/com/simibubi/create/content/logistics/filter/AttributeFilterMenu.java"
    }
}
