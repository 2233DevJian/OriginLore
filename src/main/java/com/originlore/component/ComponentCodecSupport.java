package com.originlore.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.component.ComponentType;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;

import java.util.Optional;

/** Strict JSON Codec bridge for the advanced data-component editor. */
public final class ComponentCodecSupport {
    private ComponentCodecSupport() {
    }

    public record DecodedComponent(ComponentType<?> type, Object value) {
    }

    public static DecodedComponent decode(String componentId, JsonElement value,
                                          RegistryWrapper.WrapperLookup lookup) throws ValidationException {
        Identifier id = Identifier.tryParse(componentId);
        if (id == null) throw new ValidationException("invalid component id: " + componentId);
        Optional<ComponentType<?>> component = Registries.DATA_COMPONENT_TYPE.getOrEmpty(id);
        if (component.isEmpty()) throw new ValidationException("unknown component: " + componentId);
        ComponentType<?> type = component.get();
        Codec<?> codec;
        try {
            codec = type.getCodecOrThrow();
        } catch (RuntimeException exception) {
            throw new ValidationException("component is not persistent: " + componentId);
        }
        if (value == null || value.isJsonNull()) throw new ValidationException("component value is null: " + componentId);
        DataResult<?> result = codec.parse(RegistryOps.of(JsonOps.INSTANCE, lookup), value);
        Object decoded = result.result().orElseThrow(() -> new ValidationException(
                result.error().map(Object::toString).orElse("invalid component value")));
        return new DecodedComponent(type, decoded);
    }

    public static JsonElement encode(ComponentType<?> type, Object value,
                                     RegistryWrapper.WrapperLookup lookup) throws ValidationException {
        if (type == null || value == null) throw new ValidationException("component is null");
        @SuppressWarnings("unchecked")
        Codec<Object> codec = (Codec<Object>) type.getCodecOrThrow();
        DataResult<JsonElement> result = codec.encodeStart(RegistryOps.of(JsonOps.INSTANCE, lookup), value);
        return result.result().orElseThrow(() -> new ValidationException(
                result.error().map(Object::toString).orElse("component could not be encoded")));
    }

    public static DecodedComponent decodeNbt(String componentId, NbtElement value,
                                             RegistryWrapper.WrapperLookup lookup) throws ValidationException {
        Identifier id = Identifier.tryParse(componentId);
        if (id == null) throw new ValidationException("invalid component id: " + componentId);
        ComponentType<?> type = Registries.DATA_COMPONENT_TYPE.get(id);
        if (type == null) throw new ValidationException("unknown component: " + componentId);
        Codec<?> codec;
        try {
            codec = type.getCodecOrThrow();
        } catch (RuntimeException exception) {
            throw new ValidationException("component is not persistent: " + componentId);
        }
        DataResult<?> result = codec.parse(RegistryOps.of(NbtOps.INSTANCE, lookup), value);
        Object decoded = result.result().orElseThrow(() -> new ValidationException(
                result.error().map(error -> error.message()).orElse("invalid component value")));
        return new DecodedComponent(type, decoded);
    }

    public static NbtElement encodeNbt(ComponentType<?> type, Object value,
                                       RegistryWrapper.WrapperLookup lookup) throws ValidationException {
        if (type == null || value == null) throw new ValidationException("component is null");
        try {
            @SuppressWarnings("unchecked")
            Codec<Object> codec = (Codec<Object>) type.getCodecOrThrow();
            DataResult<NbtElement> result = codec.encodeStart(RegistryOps.of(NbtOps.INSTANCE, lookup), value);
            return result.result().orElseThrow(() -> new ValidationException(
                    result.error().map(error -> error.message()).orElse("component could not be encoded")));
        } catch (ValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public static DecodedComponent decode(String componentId, String json,
                                          RegistryWrapper.WrapperLookup lookup) throws ValidationException {
        try {
            return decode(componentId, JsonParser.parseString(json), lookup);
        } catch (RuntimeException exception) {
            if (exception instanceof ValidationException validation) throw validation;
            throw new ValidationException(exception.getMessage());
        }
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message == null ? "validation failed" : message);
        }
    }
}
