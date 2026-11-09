package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** A Nitrogen recipe provider subclass threads the lookup provider its 1.21.1 base requires. */
class LibraryRecipeProviderLookupTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `nitrogen recipe provider subclasses and their creation take the lookup provider`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("LibraryRecipeProvider.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.data.providers.NitrogenRecipeProvider;
            import net.minecraft.data.PackOutput;

            public abstract class LibraryRecipeProvider extends NitrogenRecipeProvider {
                public LibraryRecipeProvider(PackOutput output, String id) {
                    super(output, id);
                }
            }
        """.trimIndent())
        srcDir.resolve("ModRecipes.java").writeText("""
            package com.example;

            import net.minecraft.data.PackOutput;

            public class ModRecipes extends LibraryRecipeProvider {
                public ModRecipes(PackOutput output) {
                    super(output, "example");
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyData.java").writeText("""
            package com.example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class LegacyData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput output = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();
                    generator.addProvider(event.includeServer(), new ModRecipes(output));
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val library = srcDir.resolve("LibraryRecipeProvider.java").readText()
        val recipes = srcDir.resolve("ModRecipes.java").readText()
        assertTrue(library.contains("public LibraryRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, String id)"), library)
        assertTrue(library.contains("super(output, lookupProvider, id);"), library)
        assertTrue(recipes.contains("public ModRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider)"), recipes)
        assertTrue(recipes.contains("super(output, lookupProvider, \"example\");"), recipes)
    }
}
