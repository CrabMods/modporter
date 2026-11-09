package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfCapabilityAttachmentsTest {
    @TempDir lateinit var directory: Path
    private fun java(name: String, text: String): Path {
        val file = directory.resolve("src/main/java/example/$name.java")
        file.parent.createDirectories()
        file.writeText(text.trimIndent())
        return file
    }

    @Test
    fun `serializable provider keeps optional identity side arguments initialization and invalidation`() {
        val main = java("ExampleMod", """
            package example;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.neoforge.common.NeoForge;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.Entity;
            @Mod("example")
            public class ExampleMod {
                public static final String ID = "example";
                public ExampleMod(IEventBus bus) {
                    NeoForge.EVENT_BUS.addGenericListener(Entity.class, ControllerProvider::attach);
                }
                public static ResourceLocation resource(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }
            }
        """)
        java("Controller", """
            package example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.neoforged.neoforge.common.util.INBTSerializable;
            public class Controller implements INBTSerializable<CompoundTag> {
                public Controller(AbstractMinecart entity) {}
                public CompoundTag serializeNBT() { return new CompoundTag(); }
                public void deserializeNBT(CompoundTag tag) {}
            }
        """)
        val provider = java("ControllerProvider", """
            package example;
            import net.minecraft.core.Direction;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.neoforged.neoforge.capabilities.Capability;
            import net.neoforged.neoforge.capabilities.CapabilityManager;
            import net.neoforged.neoforge.capabilities.CapabilityToken;
            import net.neoforged.neoforge.capabilities.ICapabilitySerializable;
            import net.neoforged.neoforge.common.util.LazyOptional;
            import net.neoforged.neoforge.event.AttachCapabilitiesEvent;
            public class ControllerProvider implements ICapabilitySerializable<CompoundTag> {
                public static Capability<Controller> CONTROLLER = CapabilityManager.get(new CapabilityToken<>() {});
                private final LazyOptional<Controller> cache;
                private Controller handler;
                public ControllerProvider(AbstractMinecart cart) {
                    handler = new Controller(cart);
                    cache = LazyOptional.of(() -> handler);
                }
                public static void attach(AttachCapabilitiesEvent<Entity> event) {
                    Entity entity = event.getObject();
                    if (!(entity instanceof AbstractMinecart)) return;
                    ControllerProvider provider = new ControllerProvider((AbstractMinecart) entity);
                    ResourceLocation id = ExampleMod.resource("controller");
                    event.addCapability(id, provider);
                    event.addListener(() -> { if (provider.cache.isPresent()) provider.cache.invalidate(); });
                    track((AbstractMinecart) entity);
                }
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    if (cap == CONTROLLER) return this.cache.cast();
                    return LazyOptional.empty();
                }
                public CompoundTag serializeNBT() { return handler.serializeNBT(); }
                public void deserializeNBT(CompoundTag tag) { handler.deserializeNBT(tag); }
            }
        """)
        val caller = java("Caller", """
            package example;
            import net.minecraft.world.entity.Entity;
            class Caller {
                Controller get(Entity entity) {
                    return entity.getCapability(ControllerProvider.CONTROLLER, nextSide()).orElse(null);
                }
            }
        """)
        val commonEvents = java("CommonEvents", """
            package example;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
            public class CommonEvents {
                @EventBusSubscriber(modid = "example", bus = EventBusSubscriber.Bus.MOD)
                public static class ModBusEvents {
                    @SubscribeEvent
                    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
                        event.register(ControllerProvider.class);
                    }
                }
            }
        """)
        val receipt = StructuralRefactorPass().apply(directory)
        assertTrue(receipt.errors.isEmpty(), receipt.errors.joinToString("\n"))
        assertTrue(receipt.changes.any { it.ruleId == "struct-self-capability-attachment" }, receipt.changes.toString())
        val source = provider.readText()
        assertTrue(source.contains("AttachmentType<ControllerProvider>"), source)
        assertTrue(source.contains("EntityJoinLevelEvent event"), source)
        assertTrue(source.contains("EntityLeaveLevelEvent event"), source)
        assertTrue(source.contains("track((AbstractMinecart) entity)"), source)
        assertTrue(source.contains("provider.cache.invalidate()"), source)
        assertTrue(source.contains("return entity.getData(CONTROLLER).cache"), source)
        assertTrue(Regex("""if\s*\(!\(entity instanceof AbstractMinecart\)\)\s*return LazyOptional\.empty\(\)""").containsMatchIn(source), source)
        assertFalse(source.contains("ICapabilitySerializable"), source)
        assertTrue(caller.readText().contains("example.ControllerProvider.modporterEntityAttachmentGet(entity, nextSide())"), caller.readText())
        assertTrue(main.readText().contains("ControllerProvider.modporterEntityAttachmentRegister(bus)"), main.readText())
        assertFalse(main.readText().contains("addGenericListener"), main.readText())
        assertFalse(main.readText().contains("registerCapabilities"), main.readText())
        assertFalse(commonEvents.readText().contains("event.register(ControllerProvider.class)"), commonEvents.readText())
        assertFalse(commonEvents.readText().contains("registerCapabilities"), commonEvents.readText())
    }
}
