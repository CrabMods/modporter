package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateConfigScreenFactoryRegistrationExact
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ConfigScreenFactoryRegistrationTest {
    @Test
    fun `lazy mod container registration keeps supplier and drops unused minecraft parameter`() {
        val source = """
            package example;

            import net.neoforged.fml.ModContainer;
            import net.neoforged.neoforge.client.ConfigScreenHandler;

            final class ClientRegistration {
                static void register(ModContainer container) {
                    container.registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new SettingsScreen(parent)
                        )
                    );
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)

        assertTrue(
            migrated.filterNot(Char::isWhitespace).contains(
                "container.registerExtensionPoint(" +
                    "IConfigScreenFactory.class,()->(minecraft,parent)->" +
                    "newSettingsScreen(parent))"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "import net.neoforged.neoforge.client.gui.IConfigScreenFactory;"
            ),
            migrated
        )
        assertFalse(migrated.contains("ConfigScreenHandler"), migrated)
    }

    @Test
    fun `qualified old factory becomes qualified target without an import`() {
        val source = """
            package example;

            final class ClientRegistration {
                static void register(net.neoforged.fml.ModContainer container) {
                    container.registerExtensionPoint(
                        net.neoforged.neoforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new net.neoforged.neoforge.client.ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new SettingsScreen(parent)));
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)

        assertTrue(
            migrated.contains(
                "net.neoforged.neoforge.client.gui.IConfigScreenFactory.class"
            ),
            migrated
        )
        assertFalse(
            migrated.contains(
                "import net.neoforged.neoforge.client.gui.IConfigScreenFactory;"
            ),
            migrated
        )
        assertFalse(migrated.contains("ConfigScreenHandler"), migrated)
    }

    @Test
    fun `mod loading context route keeps supplier lazy`() {
        val source = """
            package example;

            import net.neoforged.fml.ModLoadingContext;
            import net.neoforged.neoforge.client.ConfigScreenHandler;

            final class ClientRegistration {
                static void register() {
                    ModLoadingContext.get().registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new SettingsScreen(parent)));
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)
        val compact = migrated.filterNot(Char::isWhitespace)

        assertTrue(
            compact.contains(
                "ModLoadingContext.get().getActiveContainer().registerExtensionPoint(" +
                    "IConfigScreenFactory.class,()->(minecraft,parent)->" +
                    "newSettingsScreen(parent))"
            ),
            migrated
        )
        assertFalse(migrated.contains("ConfigScreenHandler"), migrated)
    }

    @Test
    fun `exact same source method reference is captured once inside lazy supplier`() {
        val source = """
            package example;

            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.screens.Screen;
            import net.neoforged.fml.ModLoadingContext;
            import net.neoforged.neoforge.client.ConfigScreenHandler;

            final class ClientConfigAccess {
                static void register() {
                    ModLoadingContext.get().registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            ClientConfigAccess::createConfigScreen));
                }

                private static Screen createConfigScreen(Minecraft minecraft, Screen parent) {
                    return parent;
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)
        val compact = migrated.filterNot(Char::isWhitespace)

        assertTrue(
            compact.contains(
                "ModLoadingContext.get().getActiveContainer().registerExtensionPoint(" +
                    "IConfigScreenFactory.class,()->"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "java.util.function.Function<java.util.function.BiFunction<" +
                    "net.minecraft.client.Minecraft," +
                    "net.minecraft.client.gui.screens.Screen," +
                    "net.minecraft.client.gui.screens.Screen>," +
                    "net.neoforged.neoforge.client.gui.IConfigScreenFactory>"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "modporterConfigFactory.apply(" +
                    "net.minecraft.client.Minecraft.getInstance(),modporterParent)"
            ),
            migrated
        )
        assertTrue(
            compact.contains(".apply(ClientConfigAccess::createConfigScreen)"),
            migrated
        )
        assertEquals(1, "ClientConfigAccess::createConfigScreen".toRegex().findAll(migrated).count())
        assertFalse(migrated.contains("ConfigScreenHandler"), migrated)
    }

    @Test
    fun `screen-only factory reference and lambda bind the delegating function overload`() {
        val source = """
            package example;

            import net.minecraft.client.gui.screens.Screen;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.ModLoadingContext;
            import net.neoforged.neoforge.client.ConfigScreenHandler;

            public final class ClientAccess {
                public static void register() {
                    ModLoadingContext.get().registerExtensionPoint(
                            ConfigScreenHandler.ConfigScreenFactory.class,
                            () -> new ConfigScreenHandler.ConfigScreenFactory(ClientAccess::createScreen)
                    );
                }

                static void registerLambda(ModContainer container) {
                    container.registerExtensionPoint(
                            ConfigScreenHandler.ConfigScreenFactory.class,
                            () -> new ConfigScreenHandler.ConfigScreenFactory(parent -> createScreen(parent)));
                }

                private static Screen createScreen(Screen parent) {
                    return parent;
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)
        val compact = migrated.filterNot(Char::isWhitespace)

        assertTrue(
            compact.contains(
                "ModLoadingContext.get().getActiveContainer().registerExtensionPoint(" +
                    "IConfigScreenFactory.class,()->(modporterContainer,modporterParent)->" +
                    "ClientAccess.createScreen(modporterParent))"
            ),
            migrated
        )
        assertTrue(
            compact.contains(
                "container.registerExtensionPoint(IConfigScreenFactory.class," +
                    "()->(modporterContainer,parent)->createScreen(parent))"
            ),
            migrated
        )
        assertFalse(migrated.contains("ConfigScreenHandler"), migrated)
    }

    @Test
    fun `same-source screen constructor reference binds its single factory-shaped constructor`() {
        val source = """
            package example;

            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.network.chat.Component;

            public class SettingsScreen extends Screen {
                private final Screen parent;

                public SettingsScreen(Screen parent) {
                    super(Component.literal("settings"));
                    this.parent = parent;
                }

                public static void register() {
                    net.neoforged.fml.ModLoadingContext.get().registerExtensionPoint(
                            net.neoforged.neoforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                            () -> new net.neoforged.neoforge.client.ConfigScreenHandler.ConfigScreenFactory(SettingsScreen::new));
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)

        assertTrue(
            migrated.filterNot(Char::isWhitespace).contains(
                "net.neoforged.fml.ModLoadingContext.get().getActiveContainer().registerExtensionPoint(" +
                    "net.neoforged.neoforge.client.gui.IConfigScreenFactory.class," +
                    "()->(modporterContainer,modporterParent)->newSettingsScreen(modporterParent))"
            ),
            migrated
        )
        assertFalse(migrated.contains("ConfigScreenHandler"), migrated)
    }

    @Test
    fun `overloaded factory method names stay unchanged`() {
        val source = """
            package example;

            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.screens.Screen;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.neoforge.client.ConfigScreenHandler;

            public final class ClientAccess {
                static void register(ModContainer container) {
                    container.registerExtensionPoint(
                            ConfigScreenHandler.ConfigScreenFactory.class,
                            () -> new ConfigScreenHandler.ConfigScreenFactory(ClientAccess::createScreen));
                }

                private static Screen createScreen(Screen parent) { return parent; }
                private static Screen createScreen(Minecraft minecraft, Screen parent) { return parent; }
            }
        """.trimIndent()

        assertEquals(
            source,
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)
        )
    }

    @Test
    fun `used old minecraft parameter unknown receiver and unary constructor reference are rejected`() {
        val source = """
            package example;

            import net.neoforged.fml.ModContainer;
            import net.neoforged.neoforge.client.ConfigScreenHandler;

            final class ClientRegistration {
                static void usedMinecraft(ModContainer container) {
                    container.registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> build(minecraft, parent)));
                }

                static void unknown(Object container) {
                    container.registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new SettingsScreen(parent)));
                }

                static void invalidUnaryConstructorReference(ModContainer container) {
                    container.registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            SettingsScreen::new));
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)

        assertEquals(source, migrated)
    }

    @Test
    fun `conflicting target simple name rejects an import insertion`() {
        val source = """
            package example;

            import net.neoforged.fml.ModContainer;
            import net.neoforged.neoforge.client.ConfigScreenHandler;

            final class IConfigScreenFactory {
            }

            final class ClientRegistration {
                static void register(ModContainer container) {
                    container.registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new SettingsScreen(parent)));
                }
            }
        """.trimIndent()

        val migrated =
            StructuralRefactorPass().migrateConfigScreenFactoryRegistrationExact(source)

        assertEquals(source, migrated)
    }

    @Test
    fun `config screen migration is idempotent`() {
        val source = """
            package example;

            import net.neoforged.fml.ModContainer;
            import net.neoforged.neoforge.client.ConfigScreenHandler;

            final class ClientRegistration {
                static void register(ModContainer container) {
                    container.registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new SettingsScreen(parent)));
                }
            }
        """.trimIndent()

        val pass = StructuralRefactorPass()
        val once = pass.migrateConfigScreenFactoryRegistrationExact(source)
        assertEquals(once, pass.migrateConfigScreenFactoryRegistrationExact(once))
    }
}
