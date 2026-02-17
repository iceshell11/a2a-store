package io.a2a.extras.taskstore.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Optional;

public final class JsonUtils {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    
    private JsonUtils() {
    }
    
    static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new JsonSerializationException("Failed to serialize to JSON", e);
        }
    }
    
    static <T> Optional<T> fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            T value = parseJson(json, typeRef);
            if (value == null) {
                return Optional.empty();
            }
            T normalized = normalizeStringValues(value, typeRef);
            return Optional.of(normalized);
        } catch (JsonProcessingException e) {
            throw new JsonSerializationException("Failed to deserialize JSON", e);
        }
    }
    
    private static <T> T parseJson(String json, TypeReference<T> typeRef) throws JsonProcessingException {
        try {
            return OBJECT_MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException firstException) {
            Optional<String> unwrapped = tryUnwrapJsonString(json);
            if (unwrapped.isEmpty()) {
                throw firstException;
            }
            return OBJECT_MAPPER.readValue(unwrapped.get(), typeRef);
        }
    }
    
    private static <T> T normalizeStringValues(T value, TypeReference<T> typeRef) {
        if (isObjectType(typeRef) && value instanceof String str) {
            return unwrapString(str);
        }
        return value;
    }
    
    @SuppressWarnings("unchecked")
    private static <T> T unwrapString(String str) {
        try {
            String unwrapped = OBJECT_MAPPER.readValue(str, String.class);
            return (T) (unwrapped != null ? unwrapped : str);
        } catch (JsonProcessingException e) {
            return (T) str;
        }
    }
    
    private static boolean isObjectType(TypeReference<?> typeRef) {
        return typeRef.getType().getTypeName().equals("java.lang.Object");
    }
    
    private static Optional<String> tryUnwrapJsonString(String json) {
        try {
            return Optional.ofNullable(OBJECT_MAPPER.readValue(json, String.class));
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
        }
    }
    
    static class JsonSerializationException extends RuntimeException {
        JsonSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
