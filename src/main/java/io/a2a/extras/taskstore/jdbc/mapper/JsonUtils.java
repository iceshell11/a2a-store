package io.a2a.extras.taskstore.jdbc.mapper;

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

    public <T> T fromJsonNode(JsonNode node, Class<T> clazz) {
        if (node == null || node.isNull()) {
            return emptyValue(clazz);
        }
        return objectMapper.convertValue(node, clazz);
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

    @SuppressWarnings("unchecked")
    private <T> T emptyValue(Class<T> clazz) {
        if (List.class.equals(clazz)) {
            return (T) new ArrayList<>();
        }
        if (Map.class.equals(clazz)) {
            return (T) new HashMap<>();
        }
        return null;
    }
}
