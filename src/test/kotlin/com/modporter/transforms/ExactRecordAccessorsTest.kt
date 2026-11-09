package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateExactRecordAccessors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ExactRecordAccessorsTest {
    @Test
    fun `exact imported qualified and bound record accessors migrate`() {
        val source = """
            package example;

            import java.util.Comparator;
            import java.util.function.Function;
            import net.minecraft.gametest.framework.TestFunction;
            import net.minecraft.util.random.WeightedEntry;
            import net.minecraft.util.random.WeightedEntry.Wrapper;

            final class RecordAccess {
                Function<Wrapper<String>, String> imported = Wrapper::getData;
                Function<WeightedEntry.Wrapper<String>, String> qualified =
                    WeightedEntry.Wrapper::getData;
                Function<net.minecraft.util.random.WeightedEntry.Wrapper<String>, String> fullyQualified =
                    net.minecraft.util.random.WeightedEntry.Wrapper::getData;
                Comparator<TestFunction> tests =
                    Comparator.comparing(TestFunction::getTestName);

                String direct(Wrapper<String> wrapper, TestFunction test) {
                    return wrapper.getData() + test.getTestName();
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateExactRecordAccessors(source)

        assertTrue(migrated.contains("Wrapper::data"), migrated)
        assertTrue(migrated.contains("WeightedEntry.Wrapper::data"), migrated)
        assertTrue(
            migrated.contains("net.minecraft.util.random.WeightedEntry.Wrapper::data"),
            migrated
        )
        assertTrue(migrated.contains("TestFunction::testName"), migrated)
        assertTrue(migrated.contains("wrapper.data() + test.testName()"), migrated)
        assertFalse(migrated.contains("::getData"), migrated)
        assertFalse(migrated.contains("::getTestName"), migrated)
    }

    @Test
    fun `same names unknown types and shadowed type names remain unchanged`() {
        val source = """
            package example;

            import java.util.function.Function;
            import net.minecraft.util.random.WeightedEntry.Wrapper;

            final class OtherWrapper {
                String getData() { return ""; }
            }

            final class Access {
                private final OtherWrapper Wrapper = new OtherWrapper();
                Function<OtherWrapper, String> sourceType = OtherWrapper::getData;
                java.util.function.Supplier<String> bound = Wrapper::getData;

                <T> String unknown(T value) {
                    return value.getData();
                }
            }
        """.trimIndent()

        val migrated = StructuralRefactorPass().migrateExactRecordAccessors(source)

        assertEquals(source, migrated)
    }

    @Test
    fun `record accessor migration is idempotent and ignores comments and strings`() {
        val source = """
            package example;

            import java.util.function.Function;
            import net.minecraft.util.random.WeightedEntry.Wrapper;

            final class Access {
                // Wrapper::getData must not be edited here.
                String text = "Wrapper::getData";
                Function<Wrapper<String>, String> value = Wrapper::getData;
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        val migrated = pass.migrateExactRecordAccessors(source)

        assertEquals(migrated, pass.migrateExactRecordAccessors(migrated))
        assertTrue(migrated.contains("// Wrapper::getData must not be edited here."), migrated)
        assertTrue(migrated.contains("\"Wrapper::getData\""), migrated)
        assertTrue(migrated.contains("Wrapper::data"), migrated)
    }
}
