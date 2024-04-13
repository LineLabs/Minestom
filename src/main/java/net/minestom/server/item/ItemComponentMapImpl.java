package net.minestom.server.item;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record ItemComponentMapImpl(@NotNull Int2ObjectMap<Object> components) implements ItemComponentMap {
    @Override
    public boolean has(@NotNull ItemComponentType<?> component) {
        return components.get(component.id()) != null;
    }

    @Override
    public <T> @Nullable T get(@NotNull ItemComponentType<T> component) {
        return (T) components.get(component.id());
    }

    public record BuilderImpl(@NotNull Int2ObjectMap<Object> components) implements ItemComponentMap.Builder {

        @Override
        public boolean has(@NotNull ItemComponentType<?> component) {
            return components.get(component.id()) != null;
        }

        @Override
        public <T> @Nullable T get(@NotNull ItemComponentType<T> component) {
            return (T) components.get(component.id());
        }

        @Override
        public @NotNull Builder set(@NotNull ItemComponentType<?> component, @Nullable Object value) {
            components.put(component.id(), value);
            return this;
        }

        @Override
        public @NotNull Builder remove(@NotNull ItemComponentType<?> component) {
            components.remove(component.id());
            return this;
        }

        @Override
        public @NotNull ItemComponentMap build() {
            return new ItemComponentMapImpl(components);
        }
    }
}
