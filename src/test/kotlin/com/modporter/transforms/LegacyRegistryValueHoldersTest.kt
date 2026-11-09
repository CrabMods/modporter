package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyRegistryValueHolders
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyRegistryValueHoldersTest {
    @Test
    fun `recipe serializer value lookup preserves optional and throwing continuations`() {
        val source = """
            package example;

            import java.util.Optional;
            import net.minecraft.core.Holder;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            final class SerializerTags {
                static Optional<? extends Holder<RecipeSerializer<?>>> optional(
                    RecipeSerializer<?> serializer
                ) {
                    return BuiltInRegistries.RECIPE_SERIALIZER.getHolder(serializer);
                }

                static Holder<RecipeSerializer<?>> required(RecipeSerializer<?> serializer) {
                    return BuiltInRegistries.RECIPE_SERIALIZER.getHolder(serializer)
                        .orElseThrow();
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyRegistryValueHolders(source)
        val compact = migrated.filterNot(Char::isWhitespace)

        val replacement =
            "BuiltInRegistries.RECIPE_SERIALIZER.getResourceKey(serializer)" +
                ".flatMap(BuiltInRegistries.RECIPE_SERIALIZER::getHolder)"
        assertEquals(2, Regex(Regex.escape(replacement)).findAll(compact).count(), migrated)
        assertTrue(compact.contains("$replacement.orElseThrow()"), migrated)
        assertEquals(
            2,
            Regex(Regex.escape("getResourceKey(serializer)")).findAll(compact).count(),
            migrated
        )
        assertFalse(compact.contains("Holder.direct"), migrated)
        assertFalse(compact.contains("wrapAsHolder"), migrated)
    }

    @Test
    fun `block entity value lookup preserves custom throw supplier and argument once`() {
        val source = """
            package example;

            import net.minecraft.core.Holder;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.world.level.block.entity.BlockEntityType;

            final class BlockEntityHolders {
                static Holder<BlockEntityType<?>> required(BlockEntityType<?> value) {
                    return BuiltInRegistries.BLOCK_ENTITY_TYPE.getHolder(value).orElseThrow(
                        () -> new IllegalStateException("Unregistered BlockEntityType: " + value)
                    );
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyRegistryValueHolders(source)
        val compact = migrated.filterNot(Char::isWhitespace)

        assertTrue(
            compact.contains(
                "BuiltInRegistries.BLOCK_ENTITY_TYPE.getResourceKey(value)" +
                    ".flatMap(BuiltInRegistries.BLOCK_ENTITY_TYPE::getHolder)" +
                    ".orElseThrow(()->newIllegalStateException(" +
                    "\"UnregisteredBlockEntityType:\"+value))"
            ),
            migrated
        )
        assertEquals(
            1,
            Regex(Regex.escape("getResourceKey(value)")).findAll(compact).count(),
            migrated
        )
        assertFalse(compact.contains(".getHolder(value)"), migrated)
    }

    @Test
    fun `a typed null keeps the old empty optional path`() {
        val source = """
            package example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            final class NullLookup {
                static void lookup() {
                    BuiltInRegistries.RECIPE_SERIALIZER.getHolder(
                        (RecipeSerializer<?>) null
                    );
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateLegacyRegistryValueHolders(source)

        assertTrue(
            migrated.filterNot(Char::isWhitespace).contains(
                "BuiltInRegistries.RECIPE_SERIALIZER" +
                    ".getResourceKey((RecipeSerializer<?>)null)" +
                    ".flatMap(BuiltInRegistries.RECIPE_SERIALIZER::getHolder)"
            ),
            migrated
        )
    }

    @Test
    fun `key location id holder wrong entry static import and source owned calls remain unchanged`() {
        val source = """
            package example;

            import net.minecraft.core.Holder;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            final class OtherLookups {
                static void lookup(
                    ResourceKey<RecipeSerializer<?>> key,
                    ResourceLocation location,
                    Holder<RecipeSerializer<?>> holder,
                    RecipeSerializer<?> serializer,
                    Item item,
                    OwnRegistry own
                ) {
                    BuiltInRegistries.RECIPE_SERIALIZER.getHolder(key);
                    BuiltInRegistries.RECIPE_SERIALIZER.getHolder(location);
                    BuiltInRegistries.RECIPE_SERIALIZER.getHolder(1);
                    BuiltInRegistries.RECIPE_SERIALIZER.getHolder(holder);
                    BuiltInRegistries.ITEM.getHolder(serializer);
                    BuiltInRegistries.RECIPE_SERIALIZER.<RecipeSerializer<?>>getHolder(serializer);
                    own.getHolder(serializer);
                }
            }

            final class OwnRegistry {
                Object getHolder(Object value) {
                    return value;
                }
            }
        """.trimIndent()

        assertEquals(
            source,
            StructuralRefactorPass().migrateLegacyRegistryValueHolders(source)
        )
    }

    @Test
    fun `a source value named BuiltInRegistries blocks class inference`() {
        val source = """
            package example;

            import net.minecraft.world.item.crafting.RecipeSerializer;

            final class ShadowedRegistry {
                static void lookup(
                    OwnRegistries BuiltInRegistries,
                    RecipeSerializer<?> serializer
                ) {
                    BuiltInRegistries.RECIPE_SERIALIZER.getHolder(serializer);
                }
            }

            final class OwnRegistries {
                final OwnRegistry RECIPE_SERIALIZER = new OwnRegistry();
            }

            final class OwnRegistry {
                Object getHolder(Object value) {
                    return value;
                }
            }
        """.trimIndent()

        assertEquals(
            source,
            StructuralRefactorPass().migrateLegacyRegistryValueHolders(source)
        )
    }
}
