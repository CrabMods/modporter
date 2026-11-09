package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateModLoadingContextConfigCalls
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModLoadingContextConfigTest {
    @Test
    fun `typed context parameters and canonical singleton calls register with their active container`() {
        val source = """
            package example;
            import net.neoforged.fml.ModLoadingContext;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.config.ModConfig;
            import net.neoforged.fml.config.IConfigSpec;
            class Configs {
                void register(ModLoadingContext context, ModContainer container, IConfigSpec spec) {
                    context.registerConfig(ModConfig.Type.COMMON, spec);
                    ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, spec, "custom.toml");
                    container.registerConfig(ModConfig.Type.SERVER, spec);
                    String documentation = "context.registerConfig(a, b)";
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val result = pass.migrateModLoadingContextConfigCalls(source)
        assertTrue(result.contains("context.getActiveContainer().registerConfig(ModConfig.Type.COMMON, spec)"), result)
        assertTrue(result.contains("ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, spec, \"custom.toml\")"), result)
        assertTrue(result.contains("container.registerConfig(ModConfig.Type.SERVER, spec)"), result)
        assertTrue(result.contains("\"context.registerConfig(a, b)\""), result)
        assertEquals(result, pass.migrateModLoadingContextConfigCalls(result))
    }

    @Test
    fun `unrelated context and shadowed singleton names keep their original calls`() {
        val source = """
            package example;
            import net.neoforged.fml.ModLoadingContext;
            class Configs {
                void register(Other context, Other ModLoadingContext) {
                    context.registerConfig(a, b);
                    ModLoadingContext.get().registerConfig(a, b);
                }
            }
        """.trimIndent()
        assertEquals(source, StructuralRefactorPass().migrateModLoadingContextConfigCalls(source))
    }
}
