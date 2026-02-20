package io.a2a.extras.taskstore.jdbc.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JsonUtils {

    private static final TypeReference<List<?>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public JsonUtils(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode toJsonNode(Object value) {
        if (isEmpty(value)) {
            return null;
        }
        return objectMapper.valueToTree(value);
    }

    public <T> T fromJsonNode(JsonNode node, TypeReference<T> typeRef) {
        if (node == null || node.isNull()) {
            return emptyValue(typeRef);
        }
        return objectMapper.convertValue(node, typeRef);
    }

    @SuppressWarnings("unchecked")
    private <T> T emptyValue(TypeReference<T> typeRef) {
        String type = typeRef.getType().getTypeName();
        if (type.startsWith("java.util.List") || type.startsWith("java.util.Collection")) {
            return (T) new ArrayList<>();
        }
        if (type.startsWith("java.util.Map")) {
            return (T) new HashMap<>();
        }
        return null;
    }

    private boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }
}
