package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.constructorEventBusParameterExpressionAt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FmlEventBusExecutableScopeTest {
    @TempDir
    lateinit var project: Path

    @Test
    fun `injected constructor bus is threaded into static registration helper`() {
        val file = project.resolve("src/main/java/example/Example.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
            @Mod(Example.ID)
            public class Example {
                public static final String ID = "example";
                public Example(ModContainer modContainer) {
                    onCtor();
                    FMLJavaModLoadingContext.get().getModEventBus().register(this);
                }
                public static void onCtor() {
                    IEventBus modEventBus = FMLJavaModLoadingContext.get()
                        .getModEventBus();
                    modEventBus.register(Example.class);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(project)
        val generated = file.readText()
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(generated.contains("public Example(IEventBus modEventBus, ModContainer modContainer)"), generated)
        assertTrue(generated.contains("onCtor(modEventBus);"), generated)
        assertTrue(generated.contains("modEventBus.register(this);"), generated)
        assertTrue(generated.contains("public static void onCtor(IEventBus modEventBus)"), generated)
        assertTrue(generated.contains("modEventBus.register(Example.class);"), generated)
        assertFalse(generated.contains("IEventBus modEventBus = modEventBus;"), generated)
        assertFalse(generated.contains("IEventBus modEventBus ="), generated)
        assertFalse(generated.contains("FMLJavaModLoadingContext"), generated)
        assertFalse(generated.contains("net.neoforged.fml.ModList"), generated)
    }

    @Test
    fun `native mod constructor bus is the only eligible static getter replacement`() {
        val source = """
            package example;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
            @Mod("example")
            public class Example {
                public Example(IEventBus modEventBus, ModContainer modContainer) {
                    IEventBus direct = FMLJavaModLoadingContext.get().getModEventBus();
                    Runnable deferred = () -> FMLJavaModLoadingContext.get().getModEventBus().register(this);
                    Object anonymous = new Object() {
                        IEventBus bus() {
                            return FMLJavaModLoadingContext.get().getModEventBus();
                        }
                    };
                }
                private static void helper(IEventBus gameBus) {
                    IEventBus helperBus = FMLJavaModLoadingContext.get().getModEventBus();
                }
            }
        """.trimIndent()
        val getter = "FMLJavaModLoadingContext.get().getModEventBus()"
        val offsets = Regex(Regex.escape(getter)).findAll(source).map { it.range.first }.toList()
        val pass = StructuralRefactorPass()

        assertTrue(offsets.size == 4, "expected four getter fixtures, got $offsets")
        assertTrue(
            pass.constructorEventBusParameterExpressionAt(source, offsets[0], "Example") == "modEventBus"
        )
        assertTrue(pass.constructorEventBusParameterExpressionAt(source, offsets[1], "Example") == null)
        assertTrue(pass.constructorEventBusParameterExpressionAt(source, offsets[2], "Example") == null)
        assertTrue(pass.constructorEventBusParameterExpressionAt(source, offsets[3], "Example") == null)
    }

    @Test
    fun `static getter does not borrow an arbitrary helper event bus`() {
        val file = project.resolve("src/main/java/example/Example.java")
        file.parent.createDirectories()
        file.writeText("""
            package example;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.common.NeoForge;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
            @Mod(Example.ID)
            public class Example {
                public static final String ID = "example";
                public Example(ModContainer modContainer) {
                    FMLJavaModLoadingContext.get().getModEventBus().register(this);
                    onCtor(NeoForge.EVENT_BUS);
                }
                private static void onCtor(IEventBus gameBus) {
                    IEventBus helperBus =
                        FMLJavaModLoadingContext.get().getModEventBus();
                    Registration.init(helperBus, gameBus);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(project)
        val generated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(generated.contains("modEventBus.register(this);"), generated)
        assertFalse(generated.contains("IEventBus helperBus = gameBus;"), generated)
    }
}
