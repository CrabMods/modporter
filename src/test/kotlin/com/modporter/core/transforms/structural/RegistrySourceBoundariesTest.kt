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
    fun `writable registry registration changes only the registration metadata slot`() {
        val accessor =
            """
            package example.mixin;

            import net.minecraft.core.WritableRegistry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.gen.Accessor;

            @Mixin(BuiltInRegistries.class)
            public interface RootRegistryAccessor {
                @Accessor("WRITABLE_REGISTRY")
                static WritableRegistry<WritableRegistry<?>> example${'$'}rootRegistry() {
                    throw new AssertionError();
                }

                static Object unrelated() {
                    throw new AssertionError();
                }
            }
            """.trimIndent() + "\n"
        val source =
            """
            package example;

            import com.mojang.serialization.Lifecycle;
            import example.mixin.RootRegistryAccessor;
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
                    RootRegistryAccessor.example${'$'}rootRegistry()
                        .register(
                            (ResourceKey<WritableRegistry<?>>) (Object) key,
                            registry,
                            Lifecycle.stable());
                    return registry;
                }

                static void local(MappedRegistry<Object> mapped, ResourceKey<Object> key, Object value) {
                    mapped.register(key, value, Lifecycle.stable());
                }

                static void unproven(ResourceKey<Object> key, Object value) {
                    ((Holder) RootRegistryAccessor.unrelated()).register(key, value, Lifecycle.stable());
                }
            }
            """.trimIndent() + "\n"

        val pass = StructuralRefactorPass().also {
            it.projectSourceDeclarations = ProjectSourceDeclarations.of(listOf(accessor, source))
        }
        val migrated = pass.migrateRegistrySourceBoundaries(source)
        val compact = compact(migrated)
        assertTrue(migrated.contains("import net.minecraft.core.RegistrationInfo;"), migrated)
        assertTrue(compact.contains("registry, RegistrationInfo.BUILT_IN)"), migrated)
        assertTrue(compact.contains("mapped.register(key, value, RegistrationInfo.BUILT_IN)"), migrated)
        assertTrue(compact.contains(".register(key, value, Lifecycle.stable())"), migrated)
        assertTrue(compact.contains("new MappedRegistry<>(key, Lifecycle.stable(), false)"), migrated)
        assertEquals(migrated, pass.migrateRegistrySourceBoundaries(migrated))
    }

    @Test
    fun `id bearing project triggers register through the trigger registry`() {
        val pass = StructuralRefactorPass().also {
            it.projectSourceDeclarations = ProjectSourceDeclarations.of(listOf(triggerBase(), triggers()))
        }
        val migrated = pass.migrateRegistrySourceBoundaries(triggers())
        val compact = compact(migrated)
        assertTrue(migrated.contains("import net.minecraft.core.Registry;"), migrated)
        assertTrue(
            migrated.contains("import net.minecraft.core.registries.BuiltInRegistries;"),
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
            migrated.contains("import net.minecraft.advancements.CriteriaTriggers;"),
            migrated
        )
        assertEquals(migrated, pass.migrateRegistrySourceBoundaries(migrated))
    }

    @Test
    fun `triggers without a declared resource location id remain unchanged`() {
        val withoutId = triggerBase().replace(
            "public ResourceLocation getId() {\n        return id;\n    }",
            "public String getId() {\n        return id.toString();\n    }"
        )
        val objects = triggers().replace("List<TriggerBase<?>>", "List<Object>")
        listOf(
            "non resource location id" to (listOf(withoutId, triggers()) to triggers()),
            "untyped element" to (listOf(triggerBase(), objects) to objects),
            "no project declarations" to (emptyList<String>() to triggers())
        ).forEach { (case, fixture) ->
            val (declarations, source) = fixture
            val pass = StructuralRefactorPass().also {
                it.projectSourceDeclarations = ProjectSourceDeclarations.of(declarations)
            }
            assertEquals(source, pass.migrateRegistrySourceBoundaries(source), case)
        }
    }

    private fun triggerBase(): String =
        """
        package example.advancement;

        import net.minecraft.advancements.CriterionTrigger;
        import net.minecraft.resources.ResourceLocation;

        public abstract class TriggerBase<T> implements CriterionTrigger<T> {
            private final ResourceLocation id;

            protected TriggerBase(ResourceLocation id) {
                this.id = id;
            }

            public ResourceLocation getId() {
                return id;
            }
        }
        """.trimIndent() + "\n"

    private fun triggers(): String =
        """
        package example.advancement;

        import java.util.LinkedList;
        import java.util.List;
        import net.minecraft.advancements.CriteriaTriggers;

        public class ModTriggers {
            private static final List<TriggerBase<?>> triggers =
                new LinkedList<>();

            public static void register() {
                triggers.forEach(CriteriaTriggers::register);
            }
        }
        """.trimIndent() + "\n"

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()
}
