package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** A record component typed WeightedRandomList of wrappers types the getRandom(...).get() chain. */
class WeightedWrapperChainAccessorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `wrapper data accessor resolves through a record component and the weighted list chain`() {
        val source = """
            package example;

            import java.util.List;
            import net.minecraft.util.RandomSource;
            import net.minecraft.util.random.SimpleWeightedRandomList;
            import net.minecraft.util.random.WeightedEntry;
            import net.minecraft.util.random.WeightedRandomList;

            public record Palette(WeightedRandomList<WeightedEntry.Wrapper<String>> choices, SimpleWeightedRandomList<Integer> simple) {
                public String pick(RandomSource random) {
                    return this.choices().getRandom(random).get().getData();
                }

                public Integer pickSimple(RandomSource random) {
                    return simple().getRandom(random).orElseThrow().getData();
                }

                public String withDefault(RandomSource random) {
                    return choices().getRandom(random).orElse(WeightedEntry.wrap("none", 1)).getData();
                }

                public void each(java.util.Optional<WeightedEntry.Wrapper<String>> picked) {
                    picked.ifPresent(e -> consume(e.getData()));
                }

                public Object unknown(Thing thing) {
                    return thing.getData();
                }
            }
        """.trimIndent()
        val file = directory.resolve("src/main/java/example/Palette.java")
        file.parent.createDirectories()
        file.writeText(source)
        // a declared Optional local under an on-demand java.util import, with a nested project enum argument
        val ritual = directory.resolve("src/main/java/example/Ritual.java")
        ritual.writeText(
            """
            package example;

            import java.util.*;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.util.random.WeightedEntry;
            import net.minecraft.util.random.WeightedRandomList;

            public class Ritual {
                private void roll(ServerLevel level, List<WeightedEntry.Wrapper<Effect>> effects) {
                    Optional<WeightedEntry.Wrapper<Effect>> effect = WeightedRandomList.create(effects).getRandom(level.getRandom());
                    effect.ifPresent(e -> this.apply(e.getData(), level));
                }

                private void apply(Effect effect, ServerLevel level) {
                }

                private enum Effect { A, B }
            }
            """.trimIndent()
        )
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(file, ritual))

        val ritualMigrated = pass.migrateExactRecordAccessors(ritual.readText())
        assertTrue(ritualMigrated.contains("this.apply(e.data(), level)"), ritualMigrated)

        val migrated = pass.migrateExactRecordAccessors(source)

        assertTrue(migrated.contains("this.choices().getRandom(random).get().data()"), migrated)
        assertTrue(migrated.contains("simple().getRandom(random).orElseThrow().data()"), migrated)
        assertTrue(migrated.contains("orElse(WeightedEntry.wrap(\"none\", 1)).data()"), migrated)
        assertTrue(migrated.contains("consume(e.data())"), migrated)
        assertTrue(migrated.contains("thing.getData()"), migrated)
    }
}
