package com.modporter.core.transforms.structural

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ArmorHolderBoundariesTest {
    @Test
    fun `enum sound supplier slots unwrap only proven vanilla holders`() {
        val source =
            """
            package example;

            import java.util.function.Supplier;
            import net.minecraft.core.Holder;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorMaterial;

            public enum ArmorMaterials implements ArmorMaterial {
                COPPER(() -> CustomSounds.COPPER.get(), 25),
                CARDBOARD(() -> SoundEvents.ARMOR_EQUIP_LEATHER, 12);

                private final Supplier<SoundEvent> sound;

                private ArmorMaterials(Supplier<SoundEvent> sound, int enchantment) {
                    this.sound = sound;
                }
            }

            final class OrdinaryConsumer {
                OrdinaryConsumer(Supplier<SoundEvent> sound) {
                }

                Object retained =
                    new OrdinaryConsumer(() -> SoundEvents.ARMOR_EQUIP_CHAIN);
            }

            enum HolderConsumer {
                RETAINED(() -> SoundEvents.TRIDENT_THROW);

                HolderConsumer(Supplier<Holder<SoundEvent>> sound) {
                }
            }

            enum UnrelatedSound {
                RETAINED(() -> SoundEvents.ARMOR_EQUIP_DIAMOND);

                UnrelatedSound(Supplier<SoundEvent> sound) {
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateArmorHolderBoundaries(source)
        val compact = compact(migrated)

        assertTrue(
            compact.contains(
                "CARDBOARD(() -> SoundEvents.ARMOR_EQUIP_LEATHER.value(), 12)"
            ),
            migrated
        )
        assertTrue(
            compact.contains("COPPER(() -> CustomSounds.COPPER.get(), 25)"),
            migrated
        )
        assertTrue(
            compact.contains(
                "new OrdinaryConsumer(() -> SoundEvents.ARMOR_EQUIP_CHAIN)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "HolderConsumer(Supplier<Holder<SoundEvent>> sound)"
            ),
            migrated
        )
        assertTrue(
            compact.contains("RETAINED(() -> SoundEvents.TRIDENT_THROW)"),
            migrated
        )
        assertTrue(
            compact.contains(
                "RETAINED(() -> SoundEvents.ARMOR_EQUIP_DIAMOND)"
            ),
            migrated
        )
        assertFalse(
            compact.contains("SoundEvents.ARMOR_EQUIP_CHAIN.value()"),
            migrated
        )
        assertFalse(
            compact.contains("SoundEvents.TRIDENT_THROW.value()"),
            migrated
        )
        assertFalse(
            compact.contains("SoundEvents.ARMOR_EQUIP_DIAMOND.value()"),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateArmorHolderBoundaries(migrated)
        )
    }

    @Test
    fun `ambiguous enum constructor supplier positions fail closed`() {
        val source =
            """
            package example;

            import java.util.function.Supplier;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorMaterial;

            enum AmbiguousArmor implements ArmorMaterial {
                VALUE(() -> SoundEvents.ARMOR_EQUIP_LEATHER, 4);

                AmbiguousArmor(Supplier<SoundEvent> sound, int defense) {
                }

                AmbiguousArmor(int defense, Supplier<SoundEvent> sound) {
                }
            }
            """.trimIndent() + "\n"

        assertEquals(
            source,
            StructuralRefactorPass().migrateArmorHolderBoundaries(source)
        )
    }

    @Test
    fun `legacy armor trim mixin migrates as one complete holder ABI family`() {
        val source = legacyArmorTrimMixin()

        val migrated = StructuralRefactorPass()
            .migrateArmorHolderBoundaries(source)
        val compact = compact(migrated)

        assertTrue(
            migrated.contains(
                "import net.minecraft.world.item.armortrim.TrimMaterial;"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "@Shadow @Final private Holder<TrimMaterial> material;"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "private static String getColorPaletteSuffix(" +
                    "Holder<TrimMaterial> trimMaterial, " +
                    "Holder<ArmorMaterial> pArmorMaterial)"
            ),
            migrated
        )
        assertTrue(
            compact.contains("throw new AssertionError();"),
            migrated
        )
        assertTrue(
            compact.contains(
                "BiFunction<Boolean, Holder<ArmorMaterial>, ResourceLocation> " +
                    "create${'$'}textureCardboard"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "getColorPaletteSuffix(this.material, material)"
            ),
            migrated
        )
        assertEquals(
            2,
            Regex(
                """private void create\${'$'}swapTexturesForCardboardTrims(?:Inner|Outer)\(""" +
                    """Holder<ArmorMaterial> pArmorMaterial,"""
            ).findAll(compact).count(),
            migrated
        )
        assertEquals(
            2,
            Regex(
                """pArmorMaterial == AllArmorMaterials\.CARDBOARD"""
            ).findAll(compact).count(),
            migrated
        )
        assertFalse(
            compact.contains(
                "abstract String getColorPaletteSuffix(ArmorMaterial"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass().migrateArmorHolderBoundaries(migrated)
        )
    }

    @Test
    fun `partial armor trim mixin family remains untouched`() {
        val complete = legacyArmorTrimMixin()
        val outerStart = complete.indexOf(
            "@Inject(method = \"outerTexture\""
        )
        check(outerStart >= 0)
        val source = complete.removeRange(outerStart, complete.lastIndexOf('}'))

        assertEquals(
            source,
            StructuralRefactorPass().migrateArmorHolderBoundaries(source)
        )
    }

    private fun legacyArmorTrimMixin(): String =
        """
        package com.simibubi.create.foundation.mixin;

        import java.util.function.BiFunction;
        import org.spongepowered.asm.mixin.Final;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.Shadow;
        import org.spongepowered.asm.mixin.Unique;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.Inject;
        import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
        import com.simibubi.create.Create;
        import com.simibubi.create.content.equipment.armor.AllArmorMaterials;
        import net.minecraft.Util;
        import net.minecraft.core.Holder;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.world.item.ArmorMaterial;
        import net.minecraft.world.item.armortrim.ArmorTrim;
        import net.minecraft.world.item.armortrim.TrimPattern;

        @Mixin(ArmorTrim.class)
        public abstract class ArmorTrimMixin {
            @Shadow
            @Final
            private Holder<TrimPattern> pattern;

            @Shadow
            protected abstract String getColorPaletteSuffix(
                ArmorMaterial pArmorMaterial);

            @Unique
            private final BiFunction<Boolean, ArmorMaterial, ResourceLocation>
                create${'$'}textureCardboard = Util.memoize((inner, material) -> {
                    String assetPath = pattern.value().assetId().getPath();
                    String colorSuffix = getColorPaletteSuffix(material);
                    return Create.asResource(
                        "trims/models/armor/card_" + assetPath +
                            (inner ? "_leggings_" : "_") + colorSuffix);
                });

            @Inject(method = "innerTexture", at = @At("HEAD"), cancellable = true)
            private void create${'$'}swapTexturesForCardboardTrimsInner(
                ArmorMaterial pArmorMaterial,
                CallbackInfoReturnable<ResourceLocation> cir) {
                if (pArmorMaterial == AllArmorMaterials.CARDBOARD) {
                    cir.setReturnValue(
                        create${'$'}textureCardboard.apply(true, pArmorMaterial));
                }
            }

            @Inject(method = "outerTexture", at = @At("HEAD"), cancellable = true)
            private void create${'$'}swapTexturesForCardboardTrimsOuter(
                ArmorMaterial pArmorMaterial,
                CallbackInfoReturnable<ResourceLocation> cir) {
                if (pArmorMaterial == AllArmorMaterials.CARDBOARD) {
                    cir.setReturnValue(
                        create${'$'}textureCardboard.apply(false, pArmorMaterial));
                }
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
