package io.a2a.extras.taskstore.jdbc.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.extras.taskstore.jdbc.entity.TaskEntity;
import io.a2a.extras.taskstore.jdbc.entity.TaskState;
import io.a2a.spec.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

@Component
public class TaskMapper {

    private static final Set<io.a2a.spec.TaskState> FINAL_STATES = Set.of(
            io.a2a.spec.TaskState.COMPLETED,
            io.a2a.spec.TaskState.CANCELED,
            io.a2a.spec.TaskState.FAILED,
            io.a2a.spec.TaskState.REJECTED,
            io.a2a.spec.TaskState.UNKNOWN
    );

    private final ObjectMapper objectMapper;
    private final JsonUtils jsonUtils;
    private final ArtifactMapper artifactMapper;
    private final MessageMapper messageMapper;

    public TaskMapper(ObjectMapper objectMapper, JsonUtils jsonUtils, 
                      ArtifactMapper artifactMapper, MessageMapper messageMapper) {
        this.objectMapper = objectMapper;
        this.jsonUtils = jsonUtils;
        this.artifactMapper = artifactMapper;
        this.messageMapper = messageMapper;
    }

    public TaskEntity toEntity(Task task) {
        TaskEntity entity = new TaskEntity();
        entity.setId(task.getId());
        entity.setContextId(task.getContextId());
        entity.setStatusState(toEntityState(task.getStatus().state()));
        entity.setStatusMessage(jsonUtils.toJsonNode(task.getStatus().message()));
        entity.setStatusTimestamp(toInstant(task.getStatus().timestamp()));
        entity.setMetadata(jsonUtils.toJsonNode(task.getMetadata()));
        entity.setFinalizedAt(isFinalState(task.getStatus().state()) ? Instant.now() : null);

        task.getArtifacts().stream()
                .map(artifact -> artifactMapper.toEntity(artifact, entity.getId()))
                .forEach(entity.getArtifacts()::add);

        task.getHistory().stream()
                .map(message -> messageMapper.toEntity(message, entity.getId()))
                .forEach(entity.getMessages()::add);

        return entity;
    }

    public Task fromEntity(TaskEntity entity) {
        return new Task.Builder()
                .id(entity.getId())
                .contextId(entity.getContextId())
                .status(buildStatus(entity))
                .artifacts(entity.getArtifacts().stream()
                        .map(artifactMapper::fromEntity)
                        .toList())
                .history(entity.getMessages().stream()
                        .map(messageMapper::fromEntity)
                        .toList())
                .metadata(jsonUtils.fromJsonNode(entity.getMetadata(), Map.class))
                .build();
    }

    private TaskStatus buildStatus(TaskEntity entity) {
        return new TaskStatus(
                toSpecState(entity.getStatusState()),
                toMessage(entity.getStatusMessage()),
                toOffsetDateTime(entity.getStatusTimestamp())
        );
    }

    private Message toMessage(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }
        return objectMapper.convertValue(jsonNode, Message.class);
    }

    private TaskState toEntityState(io.a2a.spec.TaskState specState) {
        return specState == null ? TaskState.UNKNOWN : TaskState.valueOf(specState.name());
    }

    private io.a2a.spec.TaskState toSpecState(TaskState state) {
        return state == null ? io.a2a.spec.TaskState.UNKNOWN : io.a2a.spec.TaskState.valueOf(state.name());
    }

    private boolean isFinalState(io.a2a.spec.TaskState state) {
        return state != null && FINAL_STATES.contains(state);
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? Instant.now() : dateTime.toInstant();
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null 
                ? OffsetDateTime.now(ZoneOffset.UTC) 
                : instant.atOffset(ZoneOffset.UTC);
    }
}
