package io.a2a.extras.taskstore.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JsonUtils {

    public static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    
    public static final TypeReference<Artifact> ARTIFACT_TYPE = new TypeReference<>() {};
    public static final TypeReference<Message> MESSAGE_TYPE = new TypeReference<>() {};
    public static final TypeReference<Map<String, Object>> METADATA_MAP_TYPE = new TypeReference<>() {};
    public static final TypeReference<List<Part<?>>> PARTS_TYPE = new TypeReference<>() {};
    public static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() {};
    public static final TypeReference<List<String>> EXTENSIONS_TYPE = new TypeReference<>() {};
    
    private JsonUtils() {
    }

    public static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialize failed: " + obj.getClass().getSimpleName(), e);
        }
    }

    public static <T> Optional<T> fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            T value = parse(json, type);
            return Optional.ofNullable(unwrapIfNeeded(value, type));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON deserialize failed: " + type.getType(), e);
        }
    }
    
    private static <T> T parse(String json, TypeReference<T> type) throws JsonProcessingException {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException first) {
            String unwrapped = unwrapString(json);
            if (unwrapped == null) throw first;
            return MAPPER.readValue(unwrapped, type);
        }
    }
    
    @SuppressWarnings("unchecked")
    private static <T> T unwrapIfNeeded(T value, TypeReference<T> type) {
        if (!isObjectType(type) || !(value instanceof String)) return value;
        String unwrapped = unwrapString((String) value);
        return unwrapped != null ? (T) unwrapped : value;
    }
    
    private static String unwrapString(String json) {
        try {
            return MAPPER.readValue(json, String.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
    
    private static boolean isObjectType(TypeReference<?> type) {
        return type.getType().getTypeName().equals("java.lang.Object");
    }
}
