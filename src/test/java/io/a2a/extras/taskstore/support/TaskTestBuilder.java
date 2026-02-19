package io.a2a.extras.taskstore.support;

import io.a2a.spec.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TaskTestBuilder {

    private String taskId;
    private String contextId;
    private TaskState taskState = TaskState.WORKING;
    private Message statusMessage;
    private OffsetDateTime statusTimestamp = OffsetDateTime.now();
    private final List<Message> history = new ArrayList<>();
    private final List<Artifact> artifacts = new ArrayList<>();
    private Map<String, Object> metadata = Map.of();

    public static TaskTestBuilder aTask() {
        return new TaskTestBuilder();
    }

    public TaskTestBuilder withId(String taskId) {
        this.taskId = taskId;
        return this;
    }

    public TaskTestBuilder withRandomId() {
        this.taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        return this;
    }

    public TaskTestBuilder withContextId(String contextId) {
        this.contextId = contextId;
        return this;
    }

    public TaskTestBuilder withStatus(TaskState state) {
        this.taskState = state;
        return this;
    }

    public TaskTestBuilder withStatusMessage(String text) {
        this.statusMessage = new Message.Builder()
                .role(Message.Role.AGENT)
                .parts(new TextPart(text))
                .build();
        return this;
    }

    public TaskTestBuilder withTimestamp(OffsetDateTime timestamp) {
        this.statusTimestamp = timestamp;
        return this;
    }

    public TaskTestBuilder withMessage(Message.Role role, String content) {
        history.add(new Message.Builder()
                .role(role)
                .parts(new TextPart(content))
                .build());
        return this;
    }

    public TaskTestBuilder withMessage(Message message) {
        history.add(message);
        return this;
    }

    public TaskTestBuilder withMessages(int count, Message.Role role, String contentPrefix) {
        for (int i = 0; i < count; i++) {
            withMessage(role, contentPrefix + " " + i);
        }
        return this;
    }

    public TaskTestBuilder withArtifact(Artifact artifact) {
        artifacts.add(artifact);
        return this;
    }

    public TaskTestBuilder withArtifact(String artifactId, String name, String content) {
        artifacts.add(new Artifact.Builder()
                .artifactId(artifactId)
                .name(name)
                .parts(new TextPart(content))
                .build());
        return this;
    }

    public TaskTestBuilder withMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }

    public TaskTestBuilder withMetadataEntry(String key, Object value) {
        if (this.metadata.isEmpty()) {
            this.metadata = new java.util.HashMap<>();
        }
        if (this.metadata instanceof java.util.HashMap) {
            ((java.util.HashMap<String, Object>) this.metadata).put(key, value);
        } else {
            Map<String, Object> newMetadata = new java.util.HashMap<>(this.metadata);
            newMetadata.put(key, value);
            this.metadata = newMetadata;
        }
        return this;
    }

    public Task build() {
        String finalTaskId = taskId != null ? taskId : "task-" + UUID.randomUUID().toString().substring(0, 8);
        String finalContextId = contextId != null ? contextId : finalTaskId;
        
        TaskStatus status = new TaskStatus(taskState, statusMessage, statusTimestamp);
        
        return new Task.Builder()
                .id(finalTaskId)
                .contextId(finalContextId)
                .status(status)
                .history(List.copyOf(history))
                .artifacts(List.copyOf(artifacts))
                .metadata(metadata)
                .build();
    }

    public Task buildWithDefaultMessage() {
        if (history.isEmpty()) {
            String finalTaskId = taskId != null ? taskId : "task-" + UUID.randomUUID().toString().substring(0, 8);
            withMessage(Message.Role.USER, "Test message");
        }
        return build();
    }

    public static Message aMessage(Message.Role role, String content) {
        return new Message.Builder()
                .role(role)
                .parts(new TextPart(content))
                .build();
    }

    public static Artifact anArtifact(String artifactId, String name, String content) {
        return new Artifact.Builder()
                .artifactId(artifactId)
                .name(name)
                .parts(new TextPart(content))
                .build();
    }
}
