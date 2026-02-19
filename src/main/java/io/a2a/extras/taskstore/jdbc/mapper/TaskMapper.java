package io.a2a.extras.taskstore.jdbc.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.a2a.extras.taskstore.jdbc.entity.ArtifactEntity;
import io.a2a.extras.taskstore.jdbc.entity.MessageEntity;
import io.a2a.extras.taskstore.jdbc.entity.Role;
import io.a2a.extras.taskstore.jdbc.entity.TaskEntity;
import io.a2a.extras.taskstore.jdbc.entity.TaskState;
import io.a2a.spec.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Component
public class TaskMapper {

    private final ObjectMapper objectMapper;

    public TaskMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TaskEntity toEntity(Task task) {
        if (task == null) {
            return null;
        }

        TaskEntity entity = new TaskEntity();
        entity.setId(UUID.fromString(task.getId()));
        entity.setContextId(task.getContextId());
        entity.setStatusState(mapState(task.getStatus().state()));
        entity.setStatusMessage(toJsonNode(task.getStatus().message()));
        entity.setStatusTimestamp(toInstant(task.getStatus().timestamp()));
        entity.setMetadata(toJsonNode(task.getMetadata()));
        entity.setFinalizedAt(isFinalState(task.getStatus().state()) ? Instant.now() : null);

        if (task.getArtifacts() != null) {
            for (Artifact artifact : task.getArtifacts()) {
                entity.getArtifacts().add(toEntity(artifact, entity.getId()));
            }
        }

        if (task.getHistory() != null) {
            for (Message message : task.getHistory()) {
                entity.getMessages().add(toEntity(message, entity.getId()));
            }
        }

        return entity;
    }

    public Task fromEntity(TaskEntity entity) {
        if (entity == null) {
            return null;
        }

        List<Artifact> artifacts = new ArrayList<>();
        for (ArtifactEntity artifactEntity : entity.getArtifacts()) {
            artifacts.add(fromEntity(artifactEntity));
        }

        List<Message> history = new ArrayList<>();
        for (MessageEntity messageEntity : entity.getMessages()) {
            history.add(fromEntity(messageEntity));
        }

        return new Task.Builder()
                .id(entity.getId().toString())
                .contextId(entity.getContextId())
                .status(buildStatus(entity))
                .artifacts(artifacts)
                .history(history)
                .metadata(fromJsonNode(entity.getMetadata(), Map.class))
                .build();
    }

    public ArtifactEntity toEntity(Artifact artifact, UUID taskId) {
        if (artifact == null) {
            return null;
        }

        ArtifactEntity entity = new ArtifactEntity();
        entity.setArtifactId(UUID.fromString(artifact.artifactId()));
        entity.setTaskId(taskId);
        entity.setName(artifact.name());
        entity.setDescription(artifact.description());
        entity.setParts(partsToJsonNode(artifact.parts()));
        entity.setMetadata(toJsonNode(artifact.metadata()));
        entity.setExtensions(toJsonNode(artifact.extensions()));

        return entity;
    }

    public Artifact fromEntity(ArtifactEntity entity) {
        if (entity == null) {
            return null;
        }

        return new Artifact.Builder()
                .artifactId(entity.getArtifactId().toString())
                .name(entity.getName())
                .description(entity.getDescription())
                .parts(partsFromJsonNode(entity.getParts()))
                .metadata(fromJsonNode(entity.getMetadata(), Map.class))
                .extensions(fromJsonNode(entity.getExtensions(), List.class))
                .build();
    }

    public MessageEntity toEntity(Message message, UUID taskId) {
        if (message == null) {
            return null;
        }

        MessageEntity entity = new MessageEntity();
        entity.setMessageId(UUID.fromString(message.getMessageId()));
        entity.setTaskId(taskId);
        entity.setRole(mapRole(message.getRole()));
        entity.setParts(partsToJsonNode(message.getParts()));
        entity.setContextId(message.getContextId());
        entity.setReferenceTaskIds(toJsonNode(message.getReferenceTaskIds()));
        entity.setMetadata(toJsonNode(message.getMetadata()));
        entity.setExtensions(toJsonNode(message.getExtensions()));

        return entity;
    }

    public Message fromEntity(MessageEntity entity) {
        if (entity == null) {
            return null;
        }

        return new Message.Builder()
                .messageId(entity.getMessageId().toString())
                .role(mapRole(entity.getRole()))
                .parts(partsFromJsonNode(entity.getParts()))
                .contextId(entity.getContextId())
                .referenceTaskIds(fromJsonNode(entity.getReferenceTaskIds(), List.class))
                .metadata(fromJsonNode(entity.getMetadata(), Map.class))
                .extensions(fromJsonNode(entity.getExtensions(), List.class))
                .build();
    }

    private TaskStatus buildStatus(TaskEntity entity) {
        return new TaskStatus(
                mapState(entity.getStatusState()),
                fromEntity(entity.getStatusMessage()),
                toOffsetDateTime(entity.getStatusTimestamp())
        );
    }

    private Message fromEntity(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }
        return objectMapper.convertValue(jsonNode, Message.class);
    }

    private TaskState mapState(io.a2a.spec.TaskState specState) {
        if (specState == null) {
            return TaskState.UNKNOWN;
        }
        return TaskState.valueOf(specState.name());
    }

    private io.a2a.spec.TaskState mapState(TaskState state) {
        if (state == null) {
            return io.a2a.spec.TaskState.UNKNOWN;
        }
        return io.a2a.spec.TaskState.valueOf(state.name());
    }

    private Role mapRole(Message.Role specRole) {
        if (specRole == null) {
            return null;
        }
        return Role.valueOf(specRole.name());
    }

    private Message.Role mapRole(Role role) {
        if (role == null) {
            return null;
        }
        return Message.Role.valueOf(role.name());
    }

    private boolean isFinalState(io.a2a.spec.TaskState state) {
        if (state == null) {
            return false;
        }
        return switch (state) {
            case COMPLETED, CANCELED, FAILED, REJECTED, UNKNOWN -> true;
            default -> false;
        };
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
            return null;
        }
        if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) {
            return null;
        }
        return objectMapper.valueToTree(value);
    }

    private <T> T fromJsonNode(JsonNode node, Class<T> clazz) {
        if (node == null || node.isNull()) {
            if (List.class.equals(clazz)) {
                return clazz.cast(new ArrayList<>());
            }
            if (Map.class.equals(clazz)) {
                return clazz.cast(new HashMap<>());
            }
            return null;
        }
        return objectMapper.convertValue(node, clazz);
    }

    private JsonNode partsToJsonNode(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return objectMapper.createArrayNode();
        }

        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (Part<?> part : parts) {
            ObjectNode partNode = objectMapper.createObjectNode();
            partNode.put("type", part.getKind().name());
            
            if (part instanceof TextPart textPart) {
                partNode.put("text", textPart.getText());
            } else if (part instanceof FilePart filePart) {
                partNode.set("file", objectMapper.valueToTree(filePart.getFile()));
            } else if (part instanceof DataPart dataPart) {
                partNode.set("data", objectMapper.valueToTree(dataPart.getData()));
            }
            
            if (part.getMetadata() != null && !part.getMetadata().isEmpty()) {
                partNode.set("metadata", objectMapper.valueToTree(part.getMetadata()));
            }
            
            arrayNode.add(partNode);
        }
        return arrayNode;
    }

    private List<Part<?>> partsFromJsonNode(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }

        List<Part<?>> parts = new ArrayList<>();
        for (JsonNode partNode : node) {
            String type = partNode.get("type").asText();
            
            Part<?> part = switch (type) {
                case "TEXT" -> {
                    String text = partNode.get("text").asText();
                    Map<String, Object> metadata = partNode.has("metadata") 
                        ? objectMapper.convertValue(partNode.get("metadata"), Map.class)
                        : null;
                    yield new TextPart(text, metadata);
                }
                case "FILE" -> {
                    JsonNode fileNode = partNode.get("file");
                    FileContent fileContent;
                    if (fileNode.has("bytes")) {
                        fileContent = new FileWithBytes(
                            fileNode.get("mimeType").asText(),
                            fileNode.get("name").asText(),
                            fileNode.get("bytes").asText()
                        );
                    } else {
                        fileContent = new FileWithUri(
                            fileNode.get("mimeType").asText(),
                            fileNode.get("name").asText(),
                            fileNode.get("uri").asText()
                        );
                    }
                    Map<String, Object> metadata = partNode.has("metadata") 
                        ? objectMapper.convertValue(partNode.get("metadata"), Map.class)
                        : null;
                    yield new FilePart(fileContent, metadata);
                }
                case "DATA" -> {
                    Map<String, Object> data = objectMapper.convertValue(partNode.get("data"), Map.class);
                    Map<String, Object> metadata = partNode.has("metadata") 
                        ? objectMapper.convertValue(partNode.get("metadata"), Map.class)
                        : null;
                    yield new DataPart(data, metadata);
                }
                default -> null;
            };
            
            if (part != null) {
                parts.add(part);
            }
        }
        return parts;
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return Instant.now();
        }
        return dateTime.toInstant();
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        if (instant == null) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        return instant.atOffset(ZoneOffset.UTC);
    }
}
