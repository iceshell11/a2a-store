package io.a2a.extras.taskstore.jdbc.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.a2a.spec.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PartConverter {

    private static final String TYPE_FIELD = "type";
    private static final String TEXT_TYPE = "TEXT";
    private static final String FILE_TYPE = "FILE";
    private static final String DATA_TYPE = "DATA";
    private static final String TEXT_FIELD = "text";
    private static final String FILE_FIELD = "file";
    private static final String DATA_FIELD = "data";
    private static final String METADATA_FIELD = "metadata";
    private static final String MIME_TYPE_FIELD = "mimeType";
    private static final String NAME_FIELD = "name";
    private static final String BYTES_FIELD = "bytes";
    private static final String URI_FIELD = "uri";

    private final ObjectMapper objectMapper;

    public PartConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Part<?>> fromJsonNode(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<Part<?>> parts = new ArrayList<>();
        for (JsonNode partNode : node) {
            parsePart(partNode).ifPresent(parts::add);
        }
        return parts;
    }

    public JsonNode toJsonNode(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return objectMapper.createArrayNode();
        }

        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (Part<?> part : parts) {
            serializePart(part).ifPresent(arrayNode::add);
        }
        return arrayNode;
    }

    private Optional<Part<?>> parsePart(JsonNode node) {
        if (!node.has(TYPE_FIELD)) {
            return Optional.empty();
        }

        String type = node.get(TYPE_FIELD).asText();
        
        return switch (type) {
            case TEXT_TYPE -> parseTextPart(node);
            case FILE_TYPE -> parseFilePart(node);
            case DATA_TYPE -> parseDataPart(node);
            default -> Optional.empty();
        };
    }

    private Optional<Part<?>> parseTextPart(JsonNode node) {
        if (!node.has(TEXT_FIELD)) {
            return Optional.empty();
        }
        
        String text = node.get(TEXT_FIELD).asText();
        Map<String, Object> metadata = extractMetadata(node);
        return Optional.of(new TextPart(text, metadata));
    }

    private Optional<Part<?>> parseFilePart(JsonNode node) {
        if (!node.has(FILE_FIELD)) {
            return Optional.empty();
        }
        
        JsonNode fileNode = node.get(FILE_FIELD);
        FileContent content = parseFileContent(fileNode);
        Map<String, Object> metadata = extractMetadata(node);
        return Optional.of(new FilePart(content, metadata));
    }

    private FileContent parseFileContent(JsonNode fileNode) {
        String mimeType = fileNode.path(MIME_TYPE_FIELD).asText();
        String name = fileNode.path(NAME_FIELD).asText();
        
        if (fileNode.has(BYTES_FIELD)) {
            return new FileWithBytes(mimeType, name, fileNode.get(BYTES_FIELD).asText());
        } else if (fileNode.has(URI_FIELD)) {
            return new FileWithUri(mimeType, name, fileNode.get(URI_FIELD).asText());
        }
        
        throw new IllegalArgumentException("File content must have either 'bytes' or 'uri'");
    }

    private Optional<Part<?>> parseDataPart(JsonNode node) {
        if (!node.has(DATA_FIELD)) {
            return Optional.empty();
        }
        
        Map<String, Object> data = objectMapper.convertValue(node.get(DATA_FIELD), Map.class);
        Map<String, Object> metadata = extractMetadata(node);
        return Optional.of(new DataPart(data, metadata));
    }

    private Map<String, Object> extractMetadata(JsonNode node) {
        if (node.has(METADATA_FIELD)) {
            return objectMapper.convertValue(node.get(METADATA_FIELD), Map.class);
        }
        return null;
    }

    private Optional<JsonNode> serializePart(Part<?> part) {
        ObjectNode node = objectMapper.createObjectNode();
        
        if (part instanceof TextPart textPart) {
            serializeTextPart(node, textPart);
        } else if (part instanceof FilePart filePart) {
            serializeFilePart(node, filePart);
        } else if (part instanceof DataPart dataPart) {
            serializeDataPart(node, dataPart);
        } else {
            return Optional.empty();
        }
        
        return Optional.of(node);
    }

    private void serializeTextPart(ObjectNode node, TextPart part) {
        node.put(TYPE_FIELD, TEXT_TYPE);
        node.put(TEXT_FIELD, part.getText());
        addMetadataIfPresent(node, part.getMetadata());
    }

    private void serializeFilePart(ObjectNode node, FilePart part) {
        node.put(TYPE_FIELD, FILE_TYPE);
        node.set(FILE_FIELD, serializeFileContent(part.getFile()));
        addMetadataIfPresent(node, part.getMetadata());
    }

    private JsonNode serializeFileContent(FileContent content) {
        ObjectNode fileNode = objectMapper.createObjectNode();
        fileNode.put(MIME_TYPE_FIELD, content.mimeType());
        fileNode.put(NAME_FIELD, content.name());
        
        if (content instanceof FileWithBytes fileWithBytes) {
            fileNode.put(BYTES_FIELD, fileWithBytes.bytes());
        } else if (content instanceof FileWithUri fileWithUri) {
            fileNode.put(URI_FIELD, fileWithUri.uri());
        }
        
        return fileNode;
    }

    private void serializeDataPart(ObjectNode node, DataPart part) {
        node.put(TYPE_FIELD, DATA_TYPE);
        node.set(DATA_FIELD, objectMapper.valueToTree(part.getData()));
        addMetadataIfPresent(node, part.getMetadata());
    }

    private void addMetadataIfPresent(ObjectNode node, Map<String, Object> metadata) {
        if (metadata != null && !metadata.isEmpty()) {
            node.set(METADATA_FIELD, objectMapper.valueToTree(metadata));
        }
    }
}
