package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCreateMixinSignatureFamilies
import com.modporter.mapping.TargetMembers
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CreateNativeMixinFixesTest {
    private val oldArmorDescriptor =
        "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;" +
            "Lnet/minecraft/client/renderer/MultiBufferSource;" +
            "Lnet/minecraft/world/entity/LivingEntity;" +
            "Lnet/minecraft/world/entity/EquipmentSlot;" +
            "ILnet/minecraft/client/model/HumanoidModel;)V"
    private val nativeArmorDescriptor =
        "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;" +
            "Lnet/minecraft/client/renderer/MultiBufferSource;" +
            "Lnet/minecraft/world/entity/LivingEntity;" +
            "Lnet/minecraft/world/entity/EquipmentSlot;" +
            "ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V"

    private fun armorSource(): String = """
        package com.simibubi.create.foundation.mixin.client;

        import com.llamalad7.mixinextras.sugar.Local;
        import com.mojang.blaze3d.vertex.PoseStack;
        import com.simibubi.create.foundation.item.CustomRenderedArmorItem;
        import net.minecraft.client.model.HumanoidModel;
        import net.minecraft.client.renderer.MultiBufferSource;
        import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
        import net.minecraft.world.entity.EquipmentSlot;
        import net.minecraft.world.entity.LivingEntity;
        import net.minecraft.world.item.ItemStack;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.injection.At;
        import org.spongepowered.asm.mixin.injection.Inject;
        import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

        @Mixin(HumanoidArmorLayer.class)
        public class HumanoidArmorLayerMixin {
            @Inject(
                method = "$oldArmorDescriptor",
                at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;"
                ),
                cancellable = true
            )
            private void create${'$'}onRenderArmorPiece(
                PoseStack poseStack,
                MultiBufferSource bufferSource,
                LivingEntity entity,
                EquipmentSlot slot,
                int light,
                HumanoidModel<?> model,
                CallbackInfo ci,
                @Local ItemStack stack
            ) {
                if (stack.getItem() instanceof CustomRenderedArmorItem renderer) {
                    renderer.renderArmorPiece(
                        (HumanoidArmorLayer<?, ?, ?>) (Object) this,
                        poseStack, bufferSource, entity, slot, light, model, stack
                    );
                    ci.cancel();
                }
            }
        }
    """.trimIndent()

    private fun pluginSource(): String = """
        package com.simibubi.create.foundation.mixin;

        import com.simibubi.create.compat.Mods;
        import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

        public class CreateMixinPlugin implements IMixinConfigPlugin {
            @Override
            public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
                if (mixinClassName.startsWith(
                    "com.simibubi.create.foundation.mixin.compat.journeymap")
                    && !Mods.JOURNEYMAP.isLoaded())
                    return false;
                if (targetClassName.startsWith(
                    "com.simibubi.create.foundation.mixin.compat.xaeros")
                    && !Mods.XAEROWORLDMAP.isLoaded())
                    return false;
                return true;
            }
        }
    """.trimIndent()

    @Test
    fun `armor injection selects the native body and carries its six arguments`() {
        val source = armorSource()
        val pass = StructuralRefactorPass()
        val migrated = pass.migrateCreateMixinSignatureFamilies(source)

        assertTrue(migrated.contains("""method = "$nativeArmorDescriptor""""), migrated)
        assertFalse(migrated.contains("""method = "$oldArmorDescriptor""""), migrated)
        assertTrue(
            migrated.contains(
                "HumanoidModel<?> model,\n" +
                    "        float limbSwing, float limbSwingAmount, float partialTick, " +
                    "float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci,\n" +
                    "        @Local ItemStack stack"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "renderer.renderArmorPiece(\n" +
                    "                (HumanoidArmorLayer<?, ?, ?>) (Object) this,\n" +
                    "                poseStack, bufferSource, entity, slot, light, model, stack\n" +
                    "            );"
            ),
            migrated
        )
        assertEquals(migrated, pass.migrateCreateMixinSignatureFamilies(migrated))
    }

    @Test
    fun `armor rewrite is backed by both exact native overload descriptors`() {
        val descriptors = TargetMembers.methodDescriptors(
            "net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer",
            "renderArmorPiece"
        )
        assertTrue(
            "(Lcom/mojang/blaze3d/vertex/PoseStack;" +
                "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                "Lnet/minecraft/world/entity/LivingEntity;" +
                "Lnet/minecraft/world/entity/EquipmentSlot;" +
                "ILnet/minecraft/client/model/HumanoidModel;)V" in descriptors
        )
        assertTrue(
            "(Lcom/mojang/blaze3d/vertex/PoseStack;" +
                "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                "Lnet/minecraft/world/entity/LivingEntity;" +
                "Lnet/minecraft/world/entity/EquipmentSlot;" +
                "ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V" in descriptors
        )
        assertTrue(
            "()Lnet/minecraft/world/item/Item;" in
                TargetMembers.methodDescriptors(
                    "net.minecraft.world.item.ItemStack",
                    "getItem"
                )
        )
    }

    @Test
    fun `armor lookalikes and ambiguous handler sets fail closed`() {
        val source = armorSource()
        val extraHandler = source.replace(
            "\n}",
            """

                @Inject(
                    method = "$oldArmorDescriptor",
                    at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;"
                    ),
                    cancellable = true
                )
                private void create${'$'}extra(
                    PoseStack poseStack, MultiBufferSource bufferSource, LivingEntity entity,
                    EquipmentSlot slot, int light, HumanoidModel<?> model,
                    CallbackInfo ci, @Local ItemStack stack
                ) {}
            }
            """.trimIndent() + "\n}",
            ignoreCase = false
        )
        assertEquals(
            extraHandler,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(extraHandler)
        )

        val wrongOwner = source.replace(
            "@Mixin(HumanoidArmorLayer.class)",
            "@Mixin(net.minecraft.client.Camera.class)"
        )
        assertEquals(
            wrongOwner,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(wrongOwner)
        )

        val wrongPoint = source.replace(
            "Lnet/minecraft/world/item/ItemStack;getItem()Lnet/minecraft/world/item/Item;",
            "Lnet/minecraft/world/item/ItemStack;isEmpty()Z"
        )
        assertEquals(
            wrongPoint,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(wrongPoint)
        )

        val documentationOnly = """
            /** $oldArmorDescriptor and ItemStack.getItem() are historical notes. */
            class HumanoidArmorLayerMixin {}
        """.trimIndent()
        assertEquals(
            documentationOnly,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(documentationOnly)
        )

        val fakeTypes = source
            .replace("import org.spongepowered.asm.mixin.Mixin;\n", "")
            .replace("import org.spongepowered.asm.mixin.injection.At;\n", "")
            .replace("import org.spongepowered.asm.mixin.injection.Inject;\n", "")
            .replace(
                "public class HumanoidArmorLayerMixin {",
                """
                    @interface Mixin { Class<?> value(); }
                    @interface At { String value(); String target(); }
                    @interface Inject { String method(); At at(); boolean cancellable(); }
                    public class HumanoidArmorLayerMixin {
                """.trimIndent()
            )
        assertEquals(
            fakeTypes,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(fakeTypes)
        )
    }

    @Test
    fun `xaero guard follows the mixin name while preserving the other routes`() {
        val source = pluginSource()
        val pass = StructuralRefactorPass()
        val migrated = pass.migrateCreateMixinSignatureFamilies(source)

        assertTrue(
            migrated.contains(
                "if (mixinClassName.startsWith(\n" +
                    "            \"com.simibubi.create.foundation.mixin.compat.xaeros\")"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "if (mixinClassName.startsWith(\n" +
                    "            \"com.simibubi.create.foundation.mixin.compat.journeymap\")"
            ),
            migrated
        )
        assertTrue(migrated.contains("return true;"), migrated)
        assertEquals(migrated, pass.migrateCreateMixinSignatureFamilies(migrated))
    }

    @Test
    fun `xaero migration rejects ambiguous methods and fake plugin identities`() {
        val source = pluginSource()
        val extraMethod = source.replace(
            "\n}",
            """

                public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
                    return true;
                }
            }
            """.trimIndent() + "\n}"
        )
        assertEquals(
            extraMethod,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(extraMethod)
        )

        val wrongPackage = source.replace(
            "package com.simibubi.create.foundation.mixin;",
            "package example;"
        )
        assertEquals(
            wrongPackage,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(wrongPackage)
        )

        val fakeInterface = source
            .replace(
                "import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;\n",
                ""
            )
            .replace(
                "public class CreateMixinPlugin implements IMixinConfigPlugin {",
                """
                    interface IMixinConfigPlugin {
                        boolean shouldApplyMixin(String targetClassName, String mixinClassName);
                    }
                    public class CreateMixinPlugin implements IMixinConfigPlugin {
                """.trimIndent()
            )
        assertEquals(
            fakeInterface,
            StructuralRefactorPass().migrateCreateMixinSignatureFamilies(fakeInterface)
        )
    }

    @Test
    fun `compiled migrated plugin enforces absent and present optional mod routes`() {
        val migrated = StructuralRefactorPass()
            .migrateCreateMixinSignatureFamilies(pluginSource())
        val directory = createTempDirectory("create-xaero-plugin")
        val sources = listOf(
            writeJava(
                directory,
                "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin.java",
                """
                    package org.spongepowered.asm.mixin.extensibility;
                    public interface IMixinConfigPlugin {
                        boolean shouldApplyMixin(
                            String targetClassName,
                            String mixinClassName
                        );
                    }
                """
            ),
            writeJava(
                directory,
                "com/simibubi/create/compat/Mods.java",
                """
                    package com.simibubi.create.compat;
                    public enum Mods {
                        JOURNEYMAP, XAEROWORLDMAP;
                        private boolean loaded;
                        public boolean isLoaded() { return loaded; }
                        public void setLoaded(boolean value) { loaded = value; }
                    }
                """
            ),
            writeJava(
                directory,
                "com/simibubi/create/foundation/mixin/CreateMixinPlugin.java",
                migrated
            ),
            writeJava(
                directory,
                "probe/Probe.java",
                """
                    package probe;
                    import com.simibubi.create.compat.Mods;
                    import com.simibubi.create.foundation.mixin.CreateMixinPlugin;
                    public final class Probe {
                        public static String verify() {
                            CreateMixinPlugin plugin = new CreateMixinPlugin();
                            String journey =
                                "com.simibubi.create.foundation.mixin.compat.journeymap.Screen";
                            String xaero =
                                "com.simibubi.create.foundation.mixin.compat.xaeros.GuiMap";
                            Mods.JOURNEYMAP.setLoaded(false);
                            Mods.XAEROWORLDMAP.setLoaded(false);
                            if (plugin.shouldApplyMixin("journeymap.Target", journey))
                                throw new AssertionError("absent JourneyMap admitted");
                            if (plugin.shouldApplyMixin("xaero.map.gui.GuiMap", xaero))
                                throw new AssertionError("absent Xaero admitted");
                            if (!plugin.shouldApplyMixin(xaero, "example.UnrelatedMixin"))
                                throw new AssertionError("target name still controls Xaero guard");
                            Mods.XAEROWORLDMAP.setLoaded(true);
                            if (!plugin.shouldApplyMixin("xaero.map.gui.GuiMap", xaero))
                                throw new AssertionError("present Xaero rejected");
                            Mods.JOURNEYMAP.setLoaded(true);
                            if (!plugin.shouldApplyMixin("journeymap.Target", journey))
                                throw new AssertionError("present JourneyMap rejected");
                            return "passed";
                        }
                    }
                """
            )
        )
        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for Create plugin semantics")
        val result = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-proc:none",
            "-d",
            classes.toString(),
            *sources.map(Path::toString).toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString(Charsets.UTF_8))
        URLClassLoader(
            arrayOf(classes.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        ).use { loader ->
            assertEquals(
                "passed",
                loader.loadClass("probe.Probe").getMethod("verify").invoke(null)
            )
        }
    }

    private fun writeJava(directory: Path, relative: String, source: String): Path {
        val file = directory.resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent())
        return file
    }
}
