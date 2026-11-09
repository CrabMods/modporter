package com.modporter.core.transforms.build

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CreateArmorMaterialSoundHolderGraphFocusedTest {
    private val fixtureRoot = Path.of(
        "src",
        "test",
        "resources",
        "fixtures",
        "create-armor-sound-holder"
    )
    private val producerPath =
        Path.of("src/main/java/com/simibubi/create/AllSoundEvents.java")

    @Test
    fun `exact Create sources produce only holder suppliers and exact owned producer edits`() {
        val enumSource = createFixture("AllArmorMaterials.pre-render.java")
        val producer = createFixture("AllSoundEvents.owned-before.java")
        val expectedProducer =
            createFixture("AllSoundEvents.expected-holder-accessors.java")

        assertEquals(
            "9D7095693E4FB90747020C77F1244B907EBEA644F3D010292400E3ED6DD4C0A3",
            sha256(enumSource)
        )
        assertEquals(
            "A77D84E66C1C4F50F71AFE0953F81B500D5BDDCF0A43D79219139BDE2FDAF4E2",
            sha256(producer)
        )
        assertEquals(
            "50FD9D8B897586BBB1825B29345E50941D70B3C109111F84A0F3EA1D223C774A",
            sha256(expectedProducer)
        )

        val plan = LegacyArmorMaterialSoundHolderGraph.resolve(
            enumSource,
            mapOf(producerPath to producer)
        )
        assertEquals(
            mapOf(
                "COPPER" to
                    "() -> AllSoundEvents.COPPER_ARMOR_EQUIP.getMainEventHolder()",
                "CARDBOARD" to
                    "() -> SoundEvents.ARMOR_EQUIP_LEATHER"
            ),
            plan.holderSuppliers
        )
        assertEquals(setOf(producerPath), plan.rewrittenSources.keys)
        assertEquals(expectedProducer, plan.rewrittenSources.getValue(producerPath))
        assertTrue(
            plan.rewrittenSources.getValue(producerPath).contains(
                "return compiledEvents.get(0).event().get();"
            )
        )
        assertTrue(
            plan.rewrittenSources.getValue(producerPath)
                .contains("return event.get();")
        )

        val repeated = LegacyArmorMaterialSoundHolderGraph.resolve(
            enumSource,
            mapOf(producerPath to expectedProducer)
        )
        assertEquals(plan.holderSuppliers, repeated.holderSuppliers)
        assertTrue(repeated.rewrittenSources.isEmpty())
    }

    @Test
    fun `rendered Create metadata queues lazy holder suppliers and preserves material data`() {
        val enumSource = createFixture("AllArmorMaterials.pre-render.java")
        val producer = createFixture("AllSoundEvents.owned-before.java")
        val plan = LegacyArmorMaterialSoundHolderGraph.resolve(
            enumSource,
            mapOf(producerPath to producer)
        )
        val rendered = requireNotNull(
            LegacyArmorMaterialEnums.render(
                enumSource,
                "com.simibubi.create.Create.ID",
                setOf("CARDBOARD"),
                plan.holderSuppliers
            )
        )

        assertEquals(listOf("COPPER", "CARDBOARD"), rendered.constants)
        assertTrue(
            rendered.source.contains(
                "register(Legacy.COPPER, " +
                    "() -> AllSoundEvents.COPPER_ARMOR_EQUIP.getMainEventHolder(), false)"
            ),
            rendered.source
        )
        assertTrue(
            rendered.source.contains(
                "register(Legacy.CARDBOARD, " +
                    "() -> SoundEvents.ARMOR_EQUIP_LEATHER, true)"
            ),
            rendered.source
        )
        val metadataSource = rendered.source.substringBefore("    enum Legacy")
        assertFalse(metadataSource.contains("legacy.getEquipSound()"), metadataSource)
        assertFalse(metadataSource.contains("wrapAsHolder"), metadataSource)
        assertTrue(metadataSource.contains("Supplier<Holder<SoundEvent>> equipSound"))
        assertTrue(metadataSource.contains("equipSound.get()"))

        assertTrue(rendered.source.contains("new int[] { 2, 4, 3, 1 }"))
        assertTrue(rendered.source.contains("new int[] { 1, 1, 1, 1 }"))
        assertTrue(rendered.source.contains("25, () ->"))
        assertTrue(rendered.source.contains("() -> Ingredient.of(Items.COPPER_INGOT)"))
        assertTrue(rendered.source.contains("() -> Ingredient.of(AllItems.CARDBOARD)"))
        assertTrue(rendered.source.contains("return this.name;"))
        assertTrue(rendered.source.contains("return this.toughness;"))
        assertTrue(rendered.source.contains("return this.knockbackResistance;"))
        assertTrue(
            rendered.source.contains(
                "new ArmorMaterial.Layer(texture, \"\", true), " +
                    "new ArmorMaterial.Layer(texture, \"_overlay\", false)"
            )
        )
    }

    @Test
    fun `complex unknown inherited collision and partial producer shapes fail closed`() {
        val enumSource = createFixture("AllArmorMaterials.pre-render.java")
        val producer = runtimeFixture("AllSoundEvents.java")

        val complexEnum = enumSource.replace(
            "() -> AllSoundEvents.COPPER_ARMOR_EQUIP.getMainEvent()",
            "() -> { audit(); return " +
                "AllSoundEvents.COPPER_ARMOR_EQUIP.getMainEvent(); }"
        )
        val complex = LegacyArmorMaterialSoundHolderGraph.resolve(
            complexEnum,
            mapOf(producerPath to producer)
        )
        assertFalse(complex.holderSuppliers.containsKey("COPPER"))
        assertTrue(complex.rewrittenSources.isEmpty())

        val unknownEnum = enumSource.replace(
            "AllSoundEvents.COPPER_ARMOR_EQUIP.getMainEvent()",
            "ExternalSounds.COPPER_ARMOR_EQUIP.getMainEvent()"
        )
        val unknown = LegacyArmorMaterialSoundHolderGraph.resolve(
            unknownEnum,
            mapOf(producerPath to producer)
        )
        assertFalse(unknown.holderSuppliers.containsKey("COPPER"))
        assertTrue(unknown.rewrittenSources.isEmpty())

        val externalSubclass = """
            package other;
            import com.simibubi.create.AllSoundEvents.SoundEntry;
            final class ExternalEntry extends SoundEntry {
                public net.minecraft.sounds.SoundEvent getMainEvent() {
                    return null;
                }
            }
        """.trimIndent()
        val inherited = LegacyArmorMaterialSoundHolderGraph.resolve(
            enumSource,
            mapOf(
                producerPath to producer,
                Path.of("src/main/java/other/ExternalEntry.java") to externalSubclass
            )
        )
        assertFalse(inherited.holderSuppliers.containsKey("COPPER"))
        assertTrue(inherited.rewrittenSources.isEmpty())

        val collisionProducer = producer.replace(
            "public abstract SoundEvent getMainEvent();",
            """
                public abstract SoundEvent getMainEvent();
                public SoundEvent getMainEventHolder() { return getMainEvent(); }
            """.trimIndent()
        )
        val collision = LegacyArmorMaterialSoundHolderGraph.resolve(
            enumSource,
            mapOf(producerPath to collisionProducer)
        )
        assertFalse(collision.holderSuppliers.containsKey("COPPER"))
        assertTrue(collision.rewrittenSources.isEmpty())

        val partialProducer = producer.replace(
            "return event.get();",
            "return audit(event.get());"
        )
        val partial = LegacyArmorMaterialSoundHolderGraph.resolve(
            enumSource,
            mapOf(producerPath to partialProducer)
        )
        assertFalse(partial.holderSuppliers.containsKey("COPPER"))
        assertTrue(partial.rewrittenSources.isEmpty())
    }

    @Test
    fun `multiple constructors and aliased getters reject the graph without partial edits`() {
        val enumSource = createFixture("AllArmorMaterials.pre-render.java")
        val producer = runtimeFixture("AllSoundEvents.java")
        val newline = if ("\r\n" in enumSource) "\r\n" else "\n"
        val constructorMarker =
            "\t@Override${newline}\tpublic int getEnchantmentValue()"
        val multipleConstructors = enumSource.replace(
            constructorMarker,
            """

                private AllArmorMaterials(int ignored) {
                    this("create:ignored", 1, new int[] { 1, 1, 1, 1 }, 1,
                        () -> SoundEvents.ARMOR_EQUIP_LEATHER.value(),
                        0.0F, 0.0F, () -> Ingredient.of(Items.COPPER_INGOT));
                }

                @Override
                public int getEnchantmentValue()
            """.trimIndent().replace("\n", newline)
        )
        assertTrue(multipleConstructors != enumSource)
        val constructorPlan = LegacyArmorMaterialSoundHolderGraph.resolve(
            multipleConstructors,
            mapOf(producerPath to producer)
        )
        assertTrue(constructorPlan.holderSuppliers.isEmpty())
        assertTrue(constructorPlan.rewrittenSources.isEmpty())

        val aliasedEnumGetter = enumSource.replace(
            "return this.soundEvent.get();",
            """
                SoundEvent selected = this.soundEvent.get();
                return selected;
            """.trimIndent()
        )
        val enumGetterPlan = LegacyArmorMaterialSoundHolderGraph.resolve(
            aliasedEnumGetter,
            mapOf(producerPath to producer)
        )
        assertTrue(enumGetterPlan.holderSuppliers.isEmpty())
        assertTrue(enumGetterPlan.rewrittenSources.isEmpty())

        val aliasedProducer = producer.replace(
            "return event.get();",
            """
                SoundEvent selected = event.get();
                return selected;
            """.trimIndent()
        )
        val producerPlan = LegacyArmorMaterialSoundHolderGraph.resolve(
            enumSource,
            mapOf(producerPath to aliasedProducer)
        )
        assertFalse(producerPlan.holderSuppliers.containsKey("COPPER"))
        assertTrue(producerPlan.rewrittenSources.isEmpty())
    }

    @Test
    fun `constant specific getEquipSound override is not promoted from constructor supplier`() {
        val enumSource = createFixture("AllArmorMaterials.pre-render.java")
        val anonymousConstant = enumSource.replace(
            "() -> Ingredient.of(Items.COPPER_INGOT)),",
            """
                () -> Ingredient.of(Items.COPPER_INGOT)) {
                    @Override
                    public SoundEvent getEquipSound() {
                        return SoundEvents.ARMOR_EQUIP_GOLD.value();
                    }
                },
            """.trimIndent()
        )
        assertTrue(anonymousConstant != enumSource)
        val plan = LegacyArmorMaterialSoundHolderGraph.resolve(
            anonymousConstant,
            mapOf(producerPath to runtimeFixture("AllSoundEvents.java"))
        )

        assertFalse(plan.holderSuppliers.containsKey("COPPER"))
        assertEquals(
            "() -> SoundEvents.ARMOR_EQUIP_LEATHER",
            plan.holderSuppliers["CARDBOARD"]
        )
        assertTrue(plan.rewrittenSources.isEmpty())
    }

    private fun createFixture(name: String): String =
        fixtureRoot.resolve("create").resolve(name).readText()

    private fun runtimeFixture(name: String): String =
        fixtureRoot.resolve("runtime").resolve(name).readText()

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
