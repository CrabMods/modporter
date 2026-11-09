package com.modporter.core.transforms.structural

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RegistrySourceBoundariesTest {
    @Test
    fun `vanilla registry key changes while deferred register key remains`() {
        val source =
            """
            package example;

            import net.minecraft.core.Registry;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class Tags {
                static <T> TagKey<T> optional(
                    Registry<T> registry, ResourceLocation id) {
                    return TagKey.create(registry.getRegistryKey(), id);
                }

                static Object retained(DeferredRegister<?> registry) {
                    return registry.getRegistryKey();
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateRegistrySourceBoundaries(source)
        assertTrue(migrated.contains("registry.key()"), migrated)
        assertTrue(
            migrated.contains("return registry.getRegistryKey();"),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateRegistrySourceBoundaries(migrated)
        )
    }

    @Test
    fun `particle holder base follows its deferred register declaration`() {
        val source =
            """
            package example;

            import java.util.function.Supplier;
            import net.minecraft.core.particles.ParticleOptions;
            import net.minecraft.core.particles.ParticleType;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class Particles {
                private static class Entry<D extends ParticleOptions> {
                    private static final DeferredRegister<ParticleType<?>>
                        REGISTER = null;
                    private final DeferredHolder<
                        ParticleType<D>, ParticleType<D>> object;
                    private final Supplier<ParticleType<D>> factory;

                    Entry(Supplier<ParticleType<D>> factory) {
                        this.factory = factory;
                        object = REGISTER.register("entry", factory);
                    }
                }

                private static class Unproven<D extends ParticleOptions> {
                    private final DeferredHolder<
                        ParticleType<D>, ParticleType<D>> retained = null;
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateRegistrySourceBoundaries(source)
        val compact = compact(migrated)
        assertTrue(
            compact.contains(
                "DeferredHolder<ParticleType<?>, ParticleType<D>> object"
            ),
            migrated
        )
        assertTrue(
            Regex(
                """DeferredHolder<\s*ParticleType<D>\s*,\s*""" +
                    """ParticleType<D>>\s+retained"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateRegistrySourceBoundaries(migrated)
        )
    }

    @Test
    fun `built in registration changes only the registration metadata slot`() {
        val source =
            """
            package example;

            import com.mojang.serialization.Lifecycle;
            import com.simibubi.create.foundation.mixin.accessor.BuiltInRegistriesAccessor;
            import net.minecraft.core.MappedRegistry;
            import net.minecraft.core.Registry;
            import net.minecraft.core.WritableRegistry;
            import net.minecraft.resources.ResourceKey;

            final class Registries {
                static <T> Registry<T> simple(ResourceKey<Registry<T>> key) {
                    return register(
                        key, new MappedRegistry<>(key, Lifecycle.stable(), false));
                }

                static <T> Registry<T> register(
                    ResourceKey<Registry<T>> key,
                    WritableRegistry<T> registry) {
                    BuiltInRegistriesAccessor.create${'$'}getWRITABLE_REGISTRY()
                        .register(
                            (ResourceKey<WritableRegistry<?>>) (Object) key,
                            registry,
                            Lifecycle.stable());
                    return registry;
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateRegistrySourceBoundaries(source)
        assertTrue(
            migrated.contains("import net.minecraft.core.RegistrationInfo;"),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "registry, RegistrationInfo.BUILT_IN)"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "new MappedRegistry<>(key, Lifecycle.stable(), false)"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateRegistrySourceBoundaries(migrated)
        )
    }

    @Test
    fun `id bearing Create triggers register through the trigger registry`() {
        val source =
            """
            package com.simibubi.create.foundation.advancement;

            import java.util.LinkedList;
            import java.util.List;
            import net.minecraft.advancements.CriteriaTriggers;

            public class AllTriggers {
                private static final List<CriterionTriggerBase<?>> triggers =
                    new LinkedList<>();

                public static void register() {
                    triggers.forEach(CriteriaTriggers::register);
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateRegistrySourceBoundaries(source)
        val compact = compact(migrated)
        assertTrue(
            migrated.contains("import net.minecraft.core.Registry;"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "import net.minecraft.core.registries.BuiltInRegistries;"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "triggers.forEach(trigger -> Registry.register(" +
                    "BuiltInRegistries.TRIGGER_TYPES, trigger.getId(), trigger))"
            ),
            migrated
        )
        assertFalse(migrated.contains("CriteriaTriggers::register"), migrated)
        assertFalse(
            migrated.contains(
                "import net.minecraft.advancements.CriteriaTriggers;"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateRegistrySourceBoundaries(migrated)
        )
    }

    @Test
    fun `similarly named triggers outside the canonical family remain unchanged`() {
        val source =
            """
            package example;

            import java.util.List;
            import net.minecraft.advancements.CriteriaTriggers;

            class AllTriggers {
                private static final List<Object> triggers = List.of();

                static void register() {
                    triggers.forEach(CriteriaTriggers::register);
                }
            }
            """.trimIndent() + "\n"

        assertEquals(
            source,
            StructuralRefactorPass().migrateRegistrySourceBoundaries(source)
        )
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()
}
