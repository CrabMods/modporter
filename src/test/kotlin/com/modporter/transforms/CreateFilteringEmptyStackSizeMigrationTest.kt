package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateFilteringEmptyStackSizeMigration
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateFilteringEmptyStackSizeMigrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `migrates the exact Create family once and preserves receiver evaluation`() {
        val fixture = fixture(directory)
        val before = fixture.behaviour.readText()

        val dryRun =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = true)
        assertTrue(dryRun.gates.isEmpty(), dryRun.gates.joinToString())
        assertEquals(1, dryRun.changes.size)
        assertEquals(before, fixture.behaviour.readText())

        val result =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertEquals(1, result.changes.size)

        val migrated = fixture.behaviour.readText()
        assertTrue(
            migrated.contains("return stack.isEmpty() ? 64 : stack.getMaxStackSize();"),
            migrated
        )
        assertEquals(
            9,
            Regex("""modporterLegacyMaxStackSize\(""").findAll(migrated).count(),
            "eight migrated calls plus the helper declaration"
        )
        assertEquals(1, Regex("""\.getMaxStackSize\(\)""").findAll(migrated).count())
        assertTrue(compileAndRun(fixture), migrated)

        val replay =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)
        assertTrue(replay.gates.isEmpty(), replay.gates.joinToString())
        assertTrue(replay.changes.isEmpty(), replay.changes.joinToString())
        assertEquals(migrated, fixture.behaviour.readText())
    }

    @Test
    fun `keeps an unrelated helper name and selects a collision-free private helper`() {
        val fixture = fixture(directory)
        fixture.behaviour.writeText(
            fixture.behaviour.readText().replace(
                "public class FilteringBehaviour extends BlockEntityBehaviour",
                """
                public class FilteringBehaviour extends BlockEntityBehaviour
                """.trimIndent()
            ).replace(
                "    private FilterItemStack filter",
                """
                    private static int modporterLegacyMaxStackSize(String text) {
                        return text.length();
                    }

                    private FilterItemStack filter
                """.trimIndent()
            )
        )

        val result =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        val migrated = fixture.behaviour.readText()
        assertTrue(migrated.contains("modporterLegacyMaxStackSize(String text)"), migrated)
        assertTrue(migrated.contains("modporterLegacyMaxStackSize2(ItemStack stack)"), migrated)
        assertTrue(migrated.contains("return text.length();"), migrated)
        val replay =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)
        assertTrue(replay.gates.isEmpty(), replay.gates.joinToString())
        assertTrue(replay.changes.isEmpty(), replay.changes.joinToString())
        assertEquals(migrated, fixture.behaviour.readText())
    }

    @Test
    fun `refuses unknown receivers and leaves the source graph unchanged`() {
        val fixture = fixture(directory)
        fixture.behaviour.writeText(
            fixture.behaviour.readText().replace(
                "return showCountPredicate && filter.item().getMaxStackSize() > 1;",
                "return showCountPredicate && getFilter().getMaxStackSize() > 1;"
            )
        )
        val before = fixture.behaviour.readText()
        val filterBefore = fixture.filterStack.readText()

        val result =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("eight verified legacy"))
        assertEquals(before, fixture.behaviour.readText())
        assertEquals(filterBefore, fixture.filterStack.readText())
    }

    @Test
    fun `refuses a fake owner and a wrong FilterItemStack item contract`() {
        val fixture = fixture(directory)
        fixture.behaviour.writeText(
            fixture.behaviour.readText().replace(
                "package com.simibubi.create.foundation.blockEntity.behaviour.filtering;",
                "package example;"
            )
        )
        val fakeBefore = fixture.behaviour.readText()

        val fake = CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)
        assertTrue(fake.changes.isEmpty())
        assertTrue(fake.gates.single().contains("unexpected package"))
        assertEquals(fakeBefore, fixture.behaviour.readText())

        val second = fixture(directory)
        second.filterStack.writeText(
            second.filterStack.readText().replace(
                "public ItemStack item()",
                "public Object item()"
            )
        )
        val behaviourBefore = second.behaviour.readText()
        val wrongContract =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)
        assertTrue(wrongContract.changes.isEmpty())
        assertTrue(wrongContract.gates.single().contains("ItemStack item()"))
        assertEquals(behaviourBefore, second.behaviour.readText())
    }

    @Test
    fun `refuses a same-named ItemStack imported from a non-native owner`() {
        val fixture = fixture(directory)
        fixture.behaviour.writeText(
            fixture.behaviour.readText().replace(
                "import net.minecraft.world.item.ItemStack;",
                "import example.ItemStack;"
            )
        )
        val before = fixture.behaviour.readText()

        val result =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("exact native owner"))
        assertEquals(before, fixture.behaviour.readText())
    }

    @Test
    fun `refuses the stack call when it moved to a fake setFilter overload`() {
        val fixture = fixture(directory)
        val before = fixture.behaviour.readText()
        val altered = before.replace(
            listOf(
                "    public void setFilter(ItemStack stack) {",
                "        filter.set(stack);",
                "        if (!upTo)",
                "            count = Math.min(count, stack.getMaxStackSize());",
                "    }"
            ).joinToString("\n"),
            listOf(
                "    public void setFilter(ItemStack stack) {",
                "        filter.set(stack);",
                "    }",
                "",
                "    public void setFilter(int side, example.ItemStack stack) {",
                "        if (!upTo)",
                "            count = Math.min(count, stack.getMaxStackSize());",
                "    }"
            ).joinToString("\n")
        )
        assertTrue(altered != before, "setFilter fixture edit did not apply")
        fixture.behaviour.writeText(altered)

        val result =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("eight verified legacy"))
        assertEquals(altered, fixture.behaviour.readText())
    }

    @Test
    fun `refuses a fake ItemStack local at the createBoard call`() {
        val fixture = fixture(directory)
        val before = fixture.behaviour.readText()
        val altered = before.replace(
            "ItemStack filter = getFilter();",
            "example.ItemStack filter = new example.ItemStack();"
        )
        assertTrue(altered != before, "createBoard fixture edit did not apply")
        fixture.behaviour.writeText(altered)

        val result =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("eight verified legacy"))
        assertEquals(altered, fixture.behaviour.readText())
    }

    @Test
    fun `refuses parameter shadowing at every filter field receiver`() {
        val signatures = linkedMapOf(
            "read" to
                (
                    "public void read(int storedCount) {" to
                        "public void read(int storedCount, FakeFilter filter) {"
                ),
            "setValueSettings" to
                (
                    "public void setValueSettings(ValueSettings settings) {" to
                        "public void setValueSettings(ValueSettings settings, FakeFilter filter) {"
                ),
            "getValueSettings" to
                (
                    "public ValueSettings getValueSettings() {" to
                        "public ValueSettings getValueSettings(FakeFilter filter) {"
                ),
            "isCountVisible" to
                (
                    "public boolean isCountVisible() {" to
                        "public boolean isCountVisible(FakeFilter filter) {"
                ),
            "formatValue" to
                (
                    "public String formatValue(ValueSettings value) {" to
                        "public String formatValue(ValueSettings value, FakeFilter filter) {"
                ),
            "getCountLabelForValueBox" to
                (
                    "public String getCountLabelForValueBox() {" to
                        "public String getCountLabelForValueBox(FakeFilter filter) {"
                )
        )

        signatures.forEach { (method, replacement) ->
            val caseRoot = directory.resolve(method)
            val fixture = fixture(caseRoot)
            val before = fixture.behaviour.readText()
            val altered = before.replace(replacement.first, replacement.second)
            assertTrue(altered != before, "$method fixture edit did not apply")
            fixture.behaviour.writeText(altered)

            val result =
                CreateFilteringEmptyStackSizeMigration.migrate(caseRoot, dryRun = false)

            assertTrue(result.changes.isEmpty(), "$method: ${result.changes}")
            assertEquals(1, result.gates.size, method)
            assertTrue(
                result.gates.single().contains("eight verified legacy"),
                "$method: ${result.gates}"
            )
            assertEquals(altered, fixture.behaviour.readText(), method)
        }
    }

    @Test
    fun `refuses a method local that shadows the filter field`() {
        val fixture = fixture(directory)
        val before = fixture.behaviour.readText()
        val altered = before.replace(
            listOf(
                "    public boolean isCountVisible() {",
                "        return showCountPredicate && filter.item().getMaxStackSize() > 1;",
                "    }"
            ).joinToString("\n"),
            listOf(
                "    public boolean isCountVisible() {",
                "        FakeFilter filter = new FakeFilter();",
                "        return showCountPredicate && filter.item().getMaxStackSize() > 1;",
                "    }"
            ).joinToString("\n")
        )
        assertTrue(altered != before, "local filter fixture edit did not apply")
        fixture.behaviour.writeText(altered)

        val result =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("eight verified legacy"))
        assertEquals(altered, fixture.behaviour.readText())
    }

    @Test
    fun `refuses a matching receiver moved into a nested owner`() {
        val fixture = fixture(directory)
        val before = fixture.behaviour.readText()
        val withoutOuterCall = before.replace(
            "count = filter.item().getMaxStackSize();",
            "count = 64;"
        )
        val altered = withoutOuterCall.replace(
            "    public record ValueSettings(int row, int value) {}",
            """
                public final class Nested {
                    public void read(int storedCount) {
                        int ignored = filter.item().getMaxStackSize();
                    }
                }

                public record ValueSettings(int row, int value) {}
            """.trimIndent()
        )
        assertTrue(altered != before, "nested owner fixture edit did not apply")
        fixture.behaviour.writeText(altered)

        val result =
            CreateFilteringEmptyStackSizeMigration.migrate(directory, dryRun = false)

        assertTrue(result.changes.isEmpty(), result.changes.joinToString())
        assertEquals(1, result.gates.size)
        assertTrue(result.gates.single().contains("eight verified legacy"))
        assertEquals(altered, fixture.behaviour.readText())
    }

    private fun fixture(root: Path): Fixture {
        val behaviour = root.resolve(
            "src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/" +
                "filtering/FilteringBehaviour.java"
        )
        val filterStack = root.resolve(
            "src/main/java/com/simibubi/create/content/logistics/filter/FilterItemStack.java"
        )
        val itemStack = root.resolve("src/main/java/net/minecraft/world/item/ItemStack.java")
        val blockEntityBehaviour = root.resolve(
            "src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/" +
                "filtering/BlockEntityBehaviour.java"
        )
        val valueSettingsBehaviour = root.resolve(
            "src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/" +
                "filtering/ValueSettingsBehaviour.java"
        )
        val fakeItemStack = root.resolve("src/main/java/example/ItemStack.java")
        val fakeFilter = root.resolve(
            "src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/" +
                "filtering/FakeFilter.java"
        )
        behaviour.parent.createDirectories()
        filterStack.parent.createDirectories()
        itemStack.parent.createDirectories()
        fakeItemStack.parent.createDirectories()

        itemStack.writeText(
            """
            package net.minecraft.world.item;

            public final class ItemStack {
                public static final ItemStack EMPTY = new ItemStack(true, 1);
                private final boolean empty;
                private final int nativeMaximum;

                public ItemStack(boolean empty, int nativeMaximum) {
                    this.empty = empty;
                    this.nativeMaximum = nativeMaximum;
                }

                public boolean isEmpty() {
                    return empty;
                }

                public int getMaxStackSize() {
                    return nativeMaximum;
                }
            }
            """.trimIndent() + "\n"
        )
        fakeItemStack.writeText(
            """
            package example;

            public final class ItemStack {
                public int getMaxStackSize() {
                    return 7;
                }
            }
            """.trimIndent() + "\n"
        )
        fakeFilter.writeText(
            """
            package com.simibubi.create.foundation.blockEntity.behaviour.filtering;

            import net.minecraft.world.item.ItemStack;

            final class FakeFilter {
                ItemStack item() {
                    return ItemStack.EMPTY;
                }
            }
            """.trimIndent() + "\n"
        )
        filterStack.writeText(
            """
            package com.simibubi.create.content.logistics.filter;

            import net.minecraft.world.item.ItemStack;

            public final class FilterItemStack {
                private ItemStack stack;
                private int itemReads;

                public FilterItemStack(ItemStack stack) {
                    this.stack = stack;
                }

                public ItemStack item() {
                    itemReads++;
                    return stack;
                }

                public void set(ItemStack stack) {
                    this.stack = stack;
                }

                public int itemReads() {
                    return itemReads;
                }

                public void resetReads() {
                    itemReads = 0;
                }
            }
            """.trimIndent() + "\n"
        )
        blockEntityBehaviour.writeText(
            """
            package com.simibubi.create.foundation.blockEntity.behaviour.filtering;

            class BlockEntityBehaviour {}
            """.trimIndent() + "\n"
        )
        valueSettingsBehaviour.writeText(
            """
            package com.simibubi.create.foundation.blockEntity.behaviour.filtering;

            interface ValueSettingsBehaviour {}
            """.trimIndent() + "\n"
        )
        behaviour.writeText(
            """
            package com.simibubi.create.foundation.blockEntity.behaviour.filtering;

            import com.simibubi.create.content.logistics.filter.FilterItemStack;
            import net.minecraft.world.item.ItemStack;

            public class FilteringBehaviour extends BlockEntityBehaviour
                    implements ValueSettingsBehaviour {
                private FilterItemStack filter = new FilterItemStack(ItemStack.EMPTY);
                private boolean showCountPredicate = true;
                public int count = 64;
                public boolean upTo = true;

                public void read(int storedCount) {
                    count = storedCount;
                    if (count == 0) {
                        upTo = true;
                        count = filter.item().getMaxStackSize();
                    }
                }

                public void setFilter(ItemStack stack) {
                    filter.set(stack);
                    if (!upTo)
                        count = Math.min(count, stack.getMaxStackSize());
                }

                public void setValueSettings(ValueSettings settings) {
                    count = clamp(settings.value(), 1, filter.item().getMaxStackSize());
                    upTo = settings.row() == 0;
                }

                public ValueSettings getValueSettings() {
                    return new ValueSettings(upTo ? 0 : 1,
                        count == 0 ? filter.item().getMaxStackSize() : count);
                }

                public boolean isCountVisible() {
                    return showCountPredicate && filter.item().getMaxStackSize() > 1;
                }

                public int createBoard() {
                    ItemStack filter = getFilter();
                    return filter.getMaxStackSize();
                }

                public String formatValue(ValueSettings value) {
                    return value.row() == 0 &&
                        value.value() == filter.item().getMaxStackSize() ? "*" : "count";
                }

                public String getCountLabelForValueBox() {
                    return isCountVisible() && upTo &&
                        filter.item().getMaxStackSize() == count ? "*" : "";
                }

                public ItemStack getFilter() {
                    return filter.item();
                }

                public FilterItemStack filterState() {
                    return filter;
                }

                public int extractWholeBatches(int available) {
                    return available / count * count;
                }

                private static int clamp(int value, int minimum, int maximum) {
                    return Math.max(minimum, Math.min(maximum, value));
                }

                public record ValueSettings(int row, int value) {}
            }
            """.trimIndent() + "\n"
        )
        return Fixture(behaviour, filterStack, itemStack)
    }

    private fun compileAndRun(fixture: Fixture): Boolean {
        val harness = directory.resolve("src/test/java/FilteringHarness.java")
        harness.parent.createDirectories()
        harness.writeText(
            """
            import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
            import net.minecraft.world.item.ItemStack;

            public final class FilteringHarness {
                private static void check(boolean value, String message) {
                    if (!value) throw new AssertionError(message);
                }

                public static void main(String[] args) {
                    FilteringBehaviour behaviour = new FilteringBehaviour();

                    behaviour.upTo = false;
                    behaviour.count = 3;
                    behaviour.setFilter(ItemStack.EMPTY);
                    check(behaviour.count == 3, "empty exact count changed");
                    behaviour.filterState().resetReads();
                    check(behaviour.isCountVisible(), "empty filter hid exact count");
                    check(behaviour.filterState().itemReads() == 1, "receiver evaluated twice");

                    behaviour.read(0);
                    check(behaviour.upTo && behaviour.count == 64,
                        "legacy zero count did not become 64 UPTO");

                    ItemStack single = new ItemStack(false, 1);
                    behaviour.setFilter(single);
                    check(!behaviour.isCountVisible(), "nonempty max-one count visible");

                    ItemStack component32 = new ItemStack(false, 32);
                    behaviour.setFilter(component32);
                    check(behaviour.isCountVisible(), "nonempty max-32 count hidden");
                    behaviour.setValueSettings(
                        new FilteringBehaviour.ValueSettings(1, 40)
                    );
                    check(behaviour.count == 32 && !behaviour.upTo,
                        "native nonempty component maximum was not preserved");

                    behaviour.setFilter(ItemStack.EMPTY);
                    behaviour.setValueSettings(
                        new FilteringBehaviour.ValueSettings(1, 3)
                    );
                    check(behaviour.count == 3 && behaviour.isCountVisible(),
                        "empty configured batch of three was not preserved");
                    int extracted = behaviour.extractWholeBatches(29);
                    check(extracted == 27 && 29 - extracted == 2,
                        "exact-three grouping did not extract 27 and preserve 2");
                }
            }
            """.trimIndent() + "\n"
        )
        val classes = directory.resolve("classes")
        classes.createDirectories()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is unavailable")
        val javaSources = Files.walk(directory.resolve("src")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .map(Path::toString)
                .toList()
        }
        val arguments = mutableListOf("-d", classes.toString()).apply {
            addAll(javaSources)
        }
        val result = compiler.run(null, null, null, *arguments.toTypedArray())
        assertEquals(0, result, "javac rejected migrated filtering fixture")
        val process = ProcessBuilder(
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) {
                    "java.exe"
                } else {
                    "java"
                }
            ).toString(),
            "-cp",
            classes.toString(),
            "FilteringHarness"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor() == 0, output)
        return true
    }

    private data class Fixture(
        val behaviour: Path,
        val filterStack: Path,
        val itemStack: Path
    )
}
