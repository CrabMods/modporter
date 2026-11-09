package com.simibubi.create;

import java.util.ArrayList;
import java.util.List;

import demo.Timeline;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class AllSoundEvents {
    static {
        Timeline.producerClassInitializations++;
    }

    public static final SoundEntry COPPER_ARMOR_EQUIP =
        new WrappedSoundEntry();

    public static void prepare() {
        Timeline.prepareCalls++;
        ((WrappedSoundEntry) COPPER_ARMOR_EQUIP).prepare();
    }

    public static void bindCopper(SoundEvent value) {
        ((WrappedSoundEntry) COPPER_ARMOR_EQUIP)
            .compiledEvents.get(0).event().bind(value);
    }

    public abstract static class SoundEntry {
        public abstract SoundEvent getMainEvent();
    }

    private static final class WrappedSoundEntry extends SoundEntry {
        private final List<CompiledSoundEvent> compiledEvents =
            new ArrayList<>();

        private void prepare() {
            compiledEvents.add(new CompiledSoundEvent(
                DeferredHolder.create("sound_event", "create:copper_armor_equip")));
        }

        @Override
        public SoundEvent getMainEvent() {
            return compiledEvents.get(0).event().get();
        }

        private record CompiledSoundEvent(
            DeferredHolder<SoundEvent, SoundEvent> event) {}
    }

    private static final class CustomSoundEntry extends SoundEntry {
        private final DeferredHolder<SoundEvent, SoundEvent> event =
            DeferredHolder.create("sound_event", "create:custom");

        @Override
        public SoundEvent getMainEvent() {
            return event.get();
        }
    }

    private AllSoundEvents() {
    }
}
