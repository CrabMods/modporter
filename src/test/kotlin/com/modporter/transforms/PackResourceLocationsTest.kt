package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migratePackResourceLocations
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackResourceLocationsTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `registered resources receive the exact location object and metadata`(@TempDir project: Path) {
        val sourceRoot = project.resolve("src/main/java/example")
        sourceRoot.createDirectories()
        val resources = sourceRoot.resolve("MemoryResources.java")
        resources.writeText(
            """
            package example;

            import net.minecraft.server.packs.PackResources;

            public class MemoryResources implements PackResources {
                private final String id;

                public MemoryResources(String id) {
                    this.id = id;
                }

                @Override
                public String packId() {
                    return id;
                }

                @Override
                public void close() {
                }
            }
            """.trimIndent()
        )
        val source = sourceRoot.resolve("MemorySource.java")
        source.writeText(
            """
            package example;

            import java.util.Optional;
            import java.util.function.Consumer;
            import net.minecraft.network.chat.Component;
            import net.minecraft.server.packs.KnownPack;
            import net.minecraft.server.packs.PackLocationInfo;
            import net.minecraft.server.packs.PackResources;
            import net.minecraft.server.packs.PackSelectionConfig;
            import net.minecraft.server.packs.PackType;
            import net.minecraft.server.packs.repository.Pack;
            import net.minecraft.server.packs.repository.PackSource;
            import net.minecraft.server.packs.repository.RepositorySource;

            public record MemorySource(
                    String id,
                    Component title,
                    PackSource source,
                    Optional<KnownPack> knownPack,
                    PackResources resources) implements RepositorySource {
                @Override
                public void loadPacks(Consumer<Pack> output) {
                    output.accept(Pack.readMetaAndCreate(
                        new PackLocationInfo(id, title, source, knownPack),
                        new com.modporter.generated.example.compat.LegacyPackResourcesSupplier(
                            ignored -> resources),
                        PackType.SERVER_DATA,
                        new PackSelectionConfig(true, Pack.Position.BOTTOM, false)));
                }
            }
            """.trimIndent()
        )
        val use = sourceRoot.resolve("Use.java")
        use.writeText(
            """
            package example;

            import java.util.Optional;
            import net.minecraft.network.chat.Component;
            import net.minecraft.server.packs.KnownPack;
            import net.minecraft.server.packs.repository.PackSource;

            class Use {
                MemorySource create(Component title, PackSource source, Optional<KnownPack> knownPack) {
                    MemoryResources resources = new MemoryResources("actual:id");
                    return new MemorySource("actual:id", title, source, knownPack, resources);
                }

                MemorySource createDirect(Component title, PackSource source, Optional<KnownPack> knownPack) {
                    return new MemorySource(
                        "direct:id", title, source, knownPack, new MemoryResources("direct:id"));
                }
            }
            """.trimIndent()
        )

        val changes = pass.migratePackResourceLocations(project, dryRun = false)
        val migratedResources = resources.readText()
        val migratedSource = source.readText()
        val interfaceFile = project.resolve(
            "src/main/java/com/modporter/generated/example/compat/LocationAwarePackResources.java"
        )

        assertTrue(changes.isNotEmpty())
        assertTrue(
            migratedResources.contains(
                "implements PackResources , " +
                    "com.modporter.generated.example.compat.LocationAwarePackResources"
            ),
            migratedResources
        )
        assertTrue(migratedResources.contains("PackLocationInfo location()"), migratedResources)
        assertTrue(migratedResources.contains("this.modporterPackLocation = location;"), migratedResources)
        assertTrue(
            migratedSource.contains(
                "PackLocationInfo modporterPackLocation = " +
                    "new PackLocationInfo(id, title, source, knownPack);"
            ),
            migratedSource
        )
        assertTrue(
            migratedSource.contains(
                "modporterLocationAware.bindLocation(modporterPackLocation);"
            ),
            migratedSource
        )
        assertTrue(
            migratedSource.contains("Pack.readMetaAndCreate(\n            modporterPackLocation,"),
            migratedSource
        )
        assertFalse(migratedSource.contains("Component.literal(id)"), migratedSource)
        assertFalse(migratedSource.contains("PackSource.BUILT_IN"), migratedSource)
        assertTrue(interfaceFile.toFile().isFile)

        val snapshot = listOf(resources, source, use, interfaceFile).associateWith { it.readText() }
        assertTrue(pass.migratePackResourceLocations(project, dryRun = false).isEmpty())
        assertEquals(snapshot, snapshot.keys.associateWith { it.readText() })
    }

    @Test
    fun `unregistered pack resources do not receive guessed metadata`(@TempDir project: Path) {
        val sourceRoot = project.resolve("src/main/java/example")
        sourceRoot.createDirectories()
        val resources = sourceRoot.resolve("LooseResources.java")
        val original = """
            package example;

            import net.minecraft.server.packs.PackResources;

            class LooseResources implements PackResources {
                private final String id;
                LooseResources(String id) { this.id = id; }
                public String packId() { return id; }
                public void close() {}
            }
        """.trimIndent()
        resources.writeText(original)

        assertTrue(pass.migratePackResourceLocations(project, dryRun = false).isEmpty())
        assertEquals(original, resources.readText())
    }
}
