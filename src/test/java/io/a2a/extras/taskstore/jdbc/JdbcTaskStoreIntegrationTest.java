package io.a2a.extras.taskstore.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.spec.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Sql(scripts = "/test-schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@ActiveProfiles("test")
class JdbcTaskStoreIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcTaskStore taskStore;
    private A2aTaskStoreProperties properties;

    @BeforeEach
    void setUp() {
        properties = new A2aTaskStoreProperties();
        taskStore = new JdbcTaskStore(jdbcTemplate, properties);

        // Clean up tables before each test
        jdbcTemplate.execute("DELETE FROM a2a_artifacts");
        jdbcTemplate.execute("DELETE FROM a2a_history");
        jdbcTemplate.execute("DELETE FROM a2a_tasks");
    }

    @Test
    void saveAndGetTask() {
        String taskId = "conv-123";
        Task task = createSampleTask(taskId);

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo(taskId);
        assertThat(retrieved.getContextId()).isEqualTo(taskId);
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.WORKING);
        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(retrieved.getArtifacts()).hasSize(1);
        assertThat(retrieved.getMetadata()).containsEntry("key1", "value1");
    }

    @Test
    void saveTaskWithoutArtifactsAndMetadata() {
        properties.setStoreArtifacts(false);
        properties.setStoreMetadata(false);
        taskStore = new JdbcTaskStore(jdbcTemplate, properties);

        String taskId = "conv-456";
        Task task = createSampleTask(taskId);

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(retrieved.getArtifacts()).isEmpty();
        assertThat(retrieved.getMetadata()).isEmpty();
    }

    @Test
    void updateExistingTask() {
        String taskId = "conv-789";
        Task task = createSampleTask(taskId);

        taskStore.save(task);

        // Update with new status and additional message
        TaskStatus newStatus = new TaskStatus(TaskState.COMPLETED, null, OffsetDateTime.now());
        io.a2a.spec.Message newMessage = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("Response"))
                .contextId(taskId)
                .build();

        Task updatedTask = new Task.Builder(task)
                .status(newStatus)
                .history(List.of(task.getHistory().get(0), newMessage))
                .build();

        taskStore.save(updatedTask);

        Task retrieved = taskStore.get(taskId);
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(retrieved.getHistory()).hasSize(2);
    }

    @Test
    void saveAndGetTaskWithTwoWordStatus() {
        String taskId = "conv-input-required";
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.INPUT_REQUIRED, null, OffsetDateTime.now()))
                .build();

        taskStore.save(task);

        Task retrieved = taskStore.get(taskId);
        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status_state FROM a2a_tasks WHERE task_id = ?",
                String.class,
                taskId
        );

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(storedStatus).isEqualTo("input-required");
    }

    @Test
    void deleteTask() {
        String taskId = "conv-delete";
        Task task = createSampleTask(taskId);

        taskStore.save(task);
        assertThat(taskStore.get(taskId)).isNotNull();

        taskStore.delete(taskId);
        assertThat(taskStore.get(taskId)).isNull();
    }

    @Test
    void getNonExistentTask() {
        Task retrieved = taskStore.get("non-existent-id");
        assertThat(retrieved).isNull();
    }

    @Test
    void isTaskActive() {
        String workingId = "conv-working";
        String completedId = "conv-completed";

        Task workingTask = new Task.Builder()
                .id(workingId)
                .contextId(workingId)
                .status(new TaskStatus(TaskState.WORKING))
                .build();

        Task completedTask = new Task.Builder()
                .id(completedId)
                .contextId(completedId)
                .status(new TaskStatus(TaskState.COMPLETED))
                .build();

        taskStore.save(workingTask);
        taskStore.save(completedTask);

        assertThat(taskStore.isTaskActive(workingId)).isTrue();
        assertThat(taskStore.isTaskActive(completedId)).isFalse();
    }

    @Test
    void isTaskFinalized() {
        String workingId = "conv-active";
        String completedId = "conv-done";

        Task workingTask = new Task.Builder()
                .id(workingId)
                .contextId(workingId)
                .status(new TaskStatus(TaskState.WORKING))
                .build();

        Task completedTask = new Task.Builder()
                .id(completedId)
                .contextId(completedId)
                .status(new TaskStatus(TaskState.COMPLETED))
                .build();

        taskStore.save(workingTask);
        taskStore.save(completedTask);

        assertThat(taskStore.isTaskFinalized(workingId)).isFalse();
        assertThat(taskStore.isTaskFinalized(completedId)).isTrue();
    }

    @Test
    void taskStateChecksForNonExistentTask() {
        assertThat(taskStore.isTaskActive("missing-id")).isFalse();
        assertThat(taskStore.isTaskFinalized("missing-id")).isFalse();
    }

    @Test
    void saveWithMultipleMessages() {
        String taskId = "conv-multi";

        List<io.a2a.spec.Message> messages = List.of(
                createMessage(io.a2a.spec.Message.Role.USER, "Message 1"),
                createMessage(io.a2a.spec.Message.Role.AGENT, "Response 1"),
                createMessage(io.a2a.spec.Message.Role.USER, "Message 2"),
                createMessage(io.a2a.spec.Message.Role.AGENT, "Response 2")
        );

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(messages)
                .build();

        taskStore.save(task);

        Task retrieved = taskStore.get(taskId);
        assertThat(retrieved.getHistory()).hasSize(4);

        // Verify order is preserved
        assertThat(retrieved.getHistory().get(0).getRole()).isEqualTo(io.a2a.spec.Message.Role.USER);
        assertThat(retrieved.getHistory().get(1).getRole()).isEqualTo(io.a2a.spec.Message.Role.AGENT);
    }

    @Test
    void saveAndGetTaskWithStatusMessageAndComplexMetadata() {
        String taskId = "conv-status-metadata";
        io.a2a.spec.Message statusMessage = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("Status update"))
                .contextId(taskId)
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING, statusMessage, OffsetDateTime.now()))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "hello")))
                .metadata(Map.of(
                        "stringKey", "stringValue",
                        "numberKey", 42,
                        "booleanKey", true,
                        "nestedKey", Map.of("inner", "value")
                ))
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved.getStatus().message()).isNotNull();
        assertThat(retrieved.getStatus().message().getRole()).isEqualTo(io.a2a.spec.Message.Role.AGENT);
        assertThat(((TextPart) retrieved.getStatus().message().getParts().get(0)).getText()).isEqualTo("Status update");
        assertThat(retrieved.getMetadata())
                .containsKey("stringKey")
                .containsKey("numberKey")
                .containsKey("booleanKey")
                .containsKey("nestedKey");
        assertThat(retrieved.getMetadata().get("stringKey")).isEqualTo("stringValue");
        assertThat(retrieved.getMetadata().get("numberKey").toString()).isEqualTo("42");
        assertThat(retrieved.getMetadata().get("booleanKey").toString()).isEqualTo("true");
        assertThat(retrieved.getMetadata().get("nestedKey").toString()).contains("inner");
    }

    @Test
    void saveAndGetTaskPreservesHistoryMessageMetadata() {
        String taskId = "conv-message-metadata";
        Map<String, Object> messageMetadata = new LinkedHashMap<>();
        messageMetadata.put("traceId", "trace-123");
        messageMetadata.put("attempt", 2);
        messageMetadata.put("tags", List.of("alpha", "beta"));

        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.USER)
                .parts(new TextPart("hello with metadata"))
                .contextId(taskId)
                .metadata(messageMetadata)
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(message))
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getHistory()).hasSize(1);
        io.a2a.spec.Message retrievedMessage = retrieved.getHistory().get(0);
        assertThat(retrievedMessage.getContextId()).isEqualTo(taskId);
        assertThat(retrievedMessage.getMetadata()).containsEntry("traceId", "trace-123");
        assertThat(retrievedMessage.getMetadata().get("attempt").toString()).isEqualTo("2");
        assertThat(retrievedMessage.getMetadata().get("tags").toString()).contains("alpha");
    }

    @Test
    void saveAndGetTaskRestoresMessageIdsAndTaskId() {
        String taskId = "conv-message-id-task-id";
        io.a2a.spec.Message first = new io.a2a.spec.Message.Builder()
                .messageId("custom-msg-001")
                .role(io.a2a.spec.Message.Role.USER)
                .parts(new TextPart("first"))
                .contextId(taskId)
                .taskId(taskId)
                .build();
        io.a2a.spec.Message second = new io.a2a.spec.Message.Builder()
                .messageId("custom-msg-002")
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("second"))
                .contextId(taskId)
                .taskId(taskId)
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(first, second))
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getHistory()).hasSize(2);
        assertThat(retrieved.getHistory().get(0).getMessageId()).isEqualTo("custom-msg-001");
        assertThat(retrieved.getHistory().get(0).getTaskId()).isEqualTo(taskId);
        assertThat(retrieved.getHistory().get(1).getMessageId()).isEqualTo("custom-msg-002");
        assertThat(retrieved.getHistory().get(1).getTaskId()).isEqualTo(taskId);
    }

    @Test
    void saveStoresPartsInContentJsonAndMetadataInMetadataColumn() throws Exception {
        String taskId = "conv-message-json-shape";
        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("payload"))
                .contextId(taskId)
                .metadata(Map.of("channel", "chat", "rank", 7))
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(message))
                .build();

        taskStore.save(task);

        String storedContentJson = jdbcTemplate.queryForObject(
                "SELECT content_json FROM a2a_history WHERE task_id = ? ORDER BY sequence_num",
                String.class,
                taskId
        );
        String storedMetadataJson = jdbcTemplate.queryForObject(
                "SELECT metadata_json FROM a2a_history WHERE task_id = ? ORDER BY sequence_num",
                String.class,
                taskId
        );
        List<Map<String, Object>> storedParts = parseJson(
                storedContentJson,
                new TypeReference<List<Map<String, Object>>>() {}
        );
        Map<String, Object> storedMetadata = parseJson(
                storedMetadataJson,
                new TypeReference<Map<String, Object>>() {}
        );

        assertThat(storedParts).hasSize(1);
        assertThat(storedParts.get(0)).containsEntry("kind", "text");
        assertThat(storedParts.get(0)).containsEntry("text", "payload");
        assertThat(storedMetadata).containsEntry("channel", "chat");
        assertThat(storedMetadata.get("rank").toString()).isEqualTo("7");
    }

    @Test
    void saveStoresNullMetadataColumnWhenMessageMetadataMissingAndLoadsEmptyMap() {
        String taskId = "conv-message-no-metadata";
        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.USER)
                .parts(new TextPart("payload"))
                .contextId(taskId)
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(message))
                .build();

        taskStore.save(task);

        String storedMetadataJson = jdbcTemplate.queryForObject(
                "SELECT metadata_json FROM a2a_history WHERE task_id = ? ORDER BY sequence_num",
                String.class,
                taskId
        );
        Task retrieved = taskStore.get(taskId);

        assertThat(storedMetadataJson).isNull();
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(retrieved.getHistory().get(0).getMetadata()).isEmpty();
    }

    @Test
    void getReadsLegacyPartsOnlyMessageRows() throws Exception {
        String taskId = "conv-legacy-parts-json";
        jdbcTemplate.update(
                """
                INSERT INTO a2a_tasks
                (task_id, status_state, status_message_json, status_timestamp, finalized_at)
                VALUES (?, ?, CAST(? AS JSON), ?, ?)
                """,
                taskId,
                TaskState.WORKING.asString(),
                "null",
                OffsetDateTime.now(),
                null
        );

        String legacyPartsJson = new ObjectMapper().writeValueAsString(List.of(new TextPart("legacy message")));
        jdbcTemplate.update(
                """
                INSERT INTO a2a_history (task_id, message_id, role, content_json, sequence_num)
                VALUES (?, ?, ?, CAST(? AS JSON), ?)
                """,
                taskId,
                taskId + "-msg-0",
                io.a2a.spec.Message.Role.USER.name(),
                legacyPartsJson,
                0
        );

        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(((TextPart) retrieved.getHistory().get(0).getParts().get(0)).getText()).isEqualTo("legacy message");
        assertThat(retrieved.getHistory().get(0).getMessageId()).isEqualTo(taskId + "-msg-0");
        assertThat(retrieved.getHistory().get(0).getTaskId()).isEqualTo(taskId);
        assertThat(retrieved.getHistory().get(0).getMetadata()).isEmpty();
    }

    @Test
    void saveWithEmptyArtifactsAndMetadataClearsExistingData() {
        String taskId = "conv-clear-optional-data";
        taskStore.save(createSampleTask(taskId));

        Task updatedTask = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "updated")))
                .artifacts(List.of())
                .metadata(Map.of())
                .build();

        taskStore.save(updatedTask);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved.getArtifacts()).isNullOrEmpty();
        assertThat(retrieved.getMetadata()).isNullOrEmpty();
    }

    @Test
    void saveUsesBatchingForMessagesArtifactsAndMetadata() {
        properties.setBatchSize(2);
        taskStore = new JdbcTaskStore(jdbcTemplate, properties);

        String taskId = "conv-batch";
        List<io.a2a.spec.Message> messages = IntStream.range(0, 5)
                .mapToObj(i -> createMessage(io.a2a.spec.Message.Role.USER, "msg-" + i))
                .toList();
        List<Artifact> artifacts = IntStream.range(0, 5)
                .mapToObj(i -> new Artifact.Builder()
                        .artifactId("artifact-" + i)
                        .name("Artifact " + i)
                        .parts(new TextPart("artifact-content-" + i))
                        .build())
                .toList();

        Map<String, Object> metadata = new LinkedHashMap<>();
        IntStream.range(0, 5).forEach(i -> metadata.put("key-" + i, "value-" + i));

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(messages)
                .artifacts(artifacts)
                .metadata(metadata)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved.getHistory()).hasSize(5);
        assertThat(((TextPart) retrieved.getHistory().get(0).getParts().get(0)).getText()).isEqualTo("msg-0");
        assertThat(((TextPart) retrieved.getHistory().get(4).getParts().get(0)).getText()).isEqualTo("msg-4");
        assertThat(retrieved.getArtifacts()).hasSize(5);
        assertThat(retrieved.getMetadata()).hasSize(5);
    }

    @Test
    void saveAndLoadArtifactWithAllFields() {
        String taskId = "conv-artifact-full";
        
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-full-001")
                .name("Full Artifact")
                .description("This is a complete artifact with all fields")
                .parts(new TextPart("Artifact content text"))
                .metadata(Map.of("author", "test", "version", 1, "tags", List.of("important", "test")))
                .extensions(List.of("ext1", "ext2"))
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .artifacts(List.of(artifact))
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved.getArtifacts()).hasSize(1);
        Artifact retrievedArtifact = retrieved.getArtifacts().get(0);
        
        // Verify all fields are preserved
        assertThat(retrievedArtifact.artifactId()).isEqualTo("art-full-001");
        assertThat(retrievedArtifact.name()).isEqualTo("Full Artifact");
        assertThat(retrievedArtifact.description()).isEqualTo("This is a complete artifact with all fields");
        assertThat(retrievedArtifact.parts()).hasSize(1);
        assertThat(((TextPart) retrievedArtifact.parts().get(0)).getText()).isEqualTo("Artifact content text");
        assertThat(retrievedArtifact.metadata()).containsEntry("author", "test");
        assertThat(retrievedArtifact.metadata()).containsEntry("version", 1);
        assertThat(retrievedArtifact.extensions()).containsExactly("ext1", "ext2");
    }

    @Test
    void saveAndLoadArtifactWithNullOptionalFields() {
        String taskId = "conv-artifact-nulls";
        
        // Artifact with only required fields
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-minimal-001")
                .name(null)
                .description(null)
                .parts(new TextPart("Minimal content"))
                .metadata(null)
                .extensions(null)
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .artifacts(List.of(artifact))
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved.getArtifacts()).hasSize(1);
        Artifact retrievedArtifact = retrieved.getArtifacts().get(0);
        
        // Verify required fields
        assertThat(retrievedArtifact.artifactId()).isEqualTo("art-minimal-001");
        assertThat(retrievedArtifact.parts()).hasSize(1);
        assertThat(((TextPart) retrievedArtifact.parts().get(0)).getText()).isEqualTo("Minimal content");
        
        // Verify optional fields are null
        assertThat(retrievedArtifact.name()).isNull();
        assertThat(retrievedArtifact.description()).isNull();
        assertThat(retrievedArtifact.metadata()).isEmpty();
        assertThat(retrievedArtifact.extensions()).isEmpty();
    }

    @Test
    void saveAndLoadMultipleArtifactsPreservesOrdering() {
        String taskId = "conv-artifact-order";
        
        List<Artifact> artifacts = List.of(
                new Artifact.Builder()
                        .artifactId("art-first")
                        .name("First Artifact")
                        .parts(new TextPart("First content"))
                        .build(),
                new Artifact.Builder()
                        .artifactId("art-second")
                        .name("Second Artifact")
                        .parts(new TextPart("Second content"))
                        .build(),
                new Artifact.Builder()
                        .artifactId("art-third")
                        .name("Third Artifact")
                        .parts(new TextPart("Third content"))
                        .build()
        );

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .artifacts(artifacts)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved.getArtifacts()).hasSize(3);
        
        // Verify order is preserved
        assertThat(retrieved.getArtifacts().get(0).artifactId()).isEqualTo("art-first");
        assertThat(retrieved.getArtifacts().get(1).artifactId()).isEqualTo("art-second");
        assertThat(retrieved.getArtifacts().get(2).artifactId()).isEqualTo("art-third");
        
        assertThat(retrieved.getArtifacts().get(0).name()).isEqualTo("First Artifact");
        assertThat(retrieved.getArtifacts().get(1).name()).isEqualTo("Second Artifact");
        assertThat(retrieved.getArtifacts().get(2).name()).isEqualTo("Third Artifact");
    }

    @Test
    void saveAndLoadArtifactWithComplexParts() {
        String taskId = "conv-artifact-complex";
        
        // Create artifact with multiple parts of different types
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-complex-001")
                .name("Complex Artifact")
                .description("Artifact with multiple parts")
                .parts(
                        new TextPart("Text part 1"),
                        new TextPart("Text part 2")
                )
                .metadata(Map.of(
                        "nested", Map.of("key", "value"),
                        "array", List.of(1, 2, 3),
                        "boolean", true,
                        "number", 42
                ))
                .extensions(List.of("custom-ext", "another-ext"))
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .artifacts(List.of(artifact))
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        assertThat(retrieved.getArtifacts()).hasSize(1);
        Artifact retrievedArtifact = retrieved.getArtifacts().get(0);
        
        // Verify complex parts
        assertThat(retrievedArtifact.parts()).hasSize(2);
        assertThat(((TextPart) retrievedArtifact.parts().get(0)).getText()).isEqualTo("Text part 1");
        assertThat(((TextPart) retrievedArtifact.parts().get(1)).getText()).isEqualTo("Text part 2");
        
        // Verify complex metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) retrievedArtifact.metadata().get("nested");
        assertThat(nested).containsEntry("key", "value");
        
        @SuppressWarnings("unchecked")
        List<Integer> array = (List<Integer>) retrievedArtifact.metadata().get("array");
        assertThat(array).containsExactly(1, 2, 3);
        
        assertThat(retrievedArtifact.metadata().get("boolean")).isEqualTo(true);
        assertThat(retrievedArtifact.metadata().get("number")).isEqualTo(42);
        
        // Verify extensions
        assertThat(retrievedArtifact.extensions()).containsExactly("custom-ext", "another-ext");
    }

    @Test
    void artifactRoundTripDataIntegrity() {
        String taskId = "conv-artifact-integrity";
        
        // Create artifact with all possible data types
        Artifact original = new Artifact.Builder()
                .artifactId("art-integrity-001")
                .name("Data Integrity Test")
                .description("Testing complete data round-trip without loss")
                .parts(new TextPart("Content that should not change"))
                .metadata(new java.util.HashMap<>() {{
                        put("string", "value");
                        put("integer", 123);
                        put("double", 45.67);
                        put("boolean", true);
                        put("nullValue", null);
                        put("list", List.of("a", "b", "c"));
                        put("map", Map.of("nested", "data"));
                }})
                .extensions(List.of("ext1", "ext2", "ext3"))
                .build();

        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .artifacts(List.of(original))
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);

        Artifact roundTripped = retrieved.getArtifacts().get(0);
        
        // Deep comparison of all fields
        assertThat(roundTripped.artifactId()).isEqualTo(original.artifactId());
        assertThat(roundTripped.name()).isEqualTo(original.name());
        assertThat(roundTripped.description()).isEqualTo(original.description());
        
        // Parts comparison
        assertThat(roundTripped.parts()).hasSize(original.parts().size());
        for (int i = 0; i < original.parts().size(); i++) {
            Part<?> originalPart = original.parts().get(i);
            Part<?> roundTrippedPart = roundTripped.parts().get(i);
            assertThat(roundTrippedPart).isInstanceOf(originalPart.getClass());
            if (originalPart instanceof TextPart) {
                assertThat(((TextPart) roundTrippedPart).getText())
                        .isEqualTo(((TextPart) originalPart).getText());
            }
        }
        
        // Metadata comparison
        assertThat(roundTripped.metadata()).hasSize(original.metadata().size());
        assertThat(roundTripped.metadata().get("string")).isEqualTo("value");
        assertThat(roundTripped.metadata().get("integer")).isEqualTo(123);
        assertThat(roundTripped.metadata().get("double")).isEqualTo(45.67);
        assertThat(roundTripped.metadata().get("boolean")).isEqualTo(true);
        
        // Extensions comparison
        assertThat(roundTripped.extensions()).containsExactlyElementsOf(original.extensions());
    }

    @Test
    void updateArtifactsReplacesPreviousOnes() {
        String taskId = "conv-artifact-update";
        
        // First save with initial artifacts
        Task initialTask = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .artifacts(List.of(
                        new Artifact.Builder()
                                .artifactId("art-initial")
                                .name("Initial Artifact")
                                .parts(new TextPart("Initial content"))
                                .build()
                ))
                .build();

        taskStore.save(initialTask);
        
        // Update with different artifacts
        Task updatedTask = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.COMPLETED))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .artifacts(List.of(
                        new Artifact.Builder()
                                .artifactId("art-updated")
                                .name("Updated Artifact")
                                .parts(new TextPart("Updated content"))
                                .build()
                ))
                .build();

        taskStore.save(updatedTask);
        Task retrieved = taskStore.get(taskId);

        // Should have only the updated artifact
        assertThat(retrieved.getArtifacts()).hasSize(1);
        assertThat(retrieved.getArtifacts().get(0).artifactId()).isEqualTo("art-updated");
        assertThat(retrieved.getArtifacts().get(0).name()).isEqualTo("Updated Artifact");
    }

    @Test
    void completeTaskRoundTripPreservesAllFields() {
        String taskId = "task-complete-roundtrip";
        String contextId = "ctx-789";
        OffsetDateTime timestamp = OffsetDateTime.now();
        
        // Create message with status for TaskStatus
        io.a2a.spec.Message statusMessage = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("Status message content"))
                .contextId(contextId)
                .build();
        
        TaskStatus status = new TaskStatus(TaskState.COMPLETED, statusMessage, timestamp);
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(contextId)
                .status(status)
                .history(List.of(
                        new io.a2a.spec.Message.Builder()
                                .messageId("msg-001")
                                .role(io.a2a.spec.Message.Role.USER)
                                .parts(new TextPart("User message"))
                                .contextId(contextId)
                                .taskId(taskId)
                                .metadata(Map.of("userMeta", "value1"))
                                .build(),
                        new io.a2a.spec.Message.Builder()
                                .messageId("msg-002")
                                .role(io.a2a.spec.Message.Role.AGENT)
                                .parts(new TextPart("Agent response"))
                                .contextId(contextId)
                                .taskId(taskId)
                                .metadata(Map.of("agentMeta", "value2"))
                                .build()
                ))
                .artifacts(List.of(
                        new Artifact.Builder()
                                .artifactId("art-001")
                                .name("Result Document")
                                .description("Generated document")
                                .parts(new TextPart("Document content"))
                                .metadata(Map.of("pages", 5))
                                .extensions(List.of("doc", "pdf"))
                                .build()
                ))
                .metadata(Map.of("source", "test", "priority", 1))
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo(taskId);
        assertThat(retrieved.getContextId()).isEqualTo(contextId);
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(retrieved.getStatus().timestamp()).isNotNull();
        assertThat(retrieved.getStatus().message()).isNotNull();
        assertThat(retrieved.getStatus().message().getParts()).hasSize(1);
        assertThat(((TextPart) retrieved.getStatus().message().getParts().get(0)).getText())
                .isEqualTo("Status message content");
        
        assertThat(retrieved.getHistory()).hasSize(2);
        assertThat(retrieved.getArtifacts()).hasSize(1);
        assertThat(retrieved.getMetadata()).containsEntry("source", "test");
        assertThat(retrieved.getMetadata()).containsEntry("priority", 1);
    }

    @Test
    void historyEntryPreservesAllFields() {
        String taskId = "task-history-fields";
        String messageId = "custom-msg-123";
        String contextId = "ctx-history";
        
        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
                .messageId(messageId)
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("Test content"))
                .contextId(contextId)
                .taskId(taskId)
                .metadata(Map.of("key1", "val1", "num", 42))
                .build();
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(message))
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        assertThat(retrieved.getHistory()).hasSize(1);
        io.a2a.spec.Message retrievedMsg = retrieved.getHistory().get(0);
        
        assertThat(retrievedMsg.getMessageId()).isEqualTo(messageId);
        assertThat(retrievedMsg.getRole()).isEqualTo(io.a2a.spec.Message.Role.AGENT);
        assertThat(retrievedMsg.getParts()).hasSize(1);
        assertThat(((TextPart) retrievedMsg.getParts().get(0)).getText()).isEqualTo("Test content");
        assertThat(retrievedMsg.getContextId()).isEqualTo(taskId); // Uses taskId per design
        assertThat(retrievedMsg.getTaskId()).isEqualTo(taskId); // Uses taskId per design
        assertThat(retrievedMsg.getMetadata()).containsEntry("key1", "val1");
        assertThat(retrievedMsg.getMetadata()).containsEntry("num", 42);
    }

    @Test
    void taskWithSpecialCharactersAndUnicode() {
        String taskId = "task-unicode-日本語";
        String content = "Special chars: \n\t\"quotes\" 'apostrophes' \\backslash emoji: 🎉";
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(
                        new io.a2a.spec.Message.Builder()
                                .role(io.a2a.spec.Message.Role.USER)
                                .parts(new TextPart(content))
                                .build()
                ))
                .artifacts(List.of(
                        new Artifact.Builder()
                                .artifactId("art-unicode")
                                .name("Unicode Test: テスト")
                                .description("Description with émojis: 🚀 🎨 🔧")
                                .parts(new TextPart("Unicode content: привет мир 你好世界"))
                                .metadata(Map.of("unicode-key", "日本語値", "emoji", "🎯"))
                                .build()
                ))
                .metadata(Map.of("special-key", "val\nue", "unicode", "中文"))
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        assertThat(retrieved.getId()).isEqualTo(taskId);
        assertThat(retrieved.getMetadata().get("unicode")).isEqualTo("中文");
        
        TextPart retrievedPart = (TextPart) retrieved.getHistory().get(0).getParts().get(0);
        assertThat(retrievedPart.getText()).isEqualTo(content);
        
        Artifact artifact = retrieved.getArtifacts().get(0);
        assertThat(artifact.name()).contains("テスト");
        assertThat(artifact.metadata().get("emoji")).isEqualTo("🎯");
    }

    @Test
    void taskStatusWithNullMessageIsPreserved() {
        String taskId = "task-null-status-msg";
        OffsetDateTime timestamp = OffsetDateTime.now();
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING, null, timestamp))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.WORKING);
        assertThat(retrieved.getStatus().message()).isNull();
        assertThat(retrieved.getStatus().timestamp()).isNotNull();
    }

    @Test
    void emptyCollectionsArePreserved() {
        String taskId = "task-empty-collections";
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.COMPLETED))
                .history(List.of())
                .artifacts(List.of())
                .metadata(Map.of())
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        assertThat(retrieved.getHistory()).isEmpty();
        assertThat(retrieved.getArtifacts()).isEmpty();
        assertThat(retrieved.getMetadata()).isEmpty();
    }

    @Test
    void databaseColumnsStoreCorrectData() throws Exception {
        String taskId = "task-db-columns";
        OffsetDateTime beforeSave = OffsetDateTime.now();
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING, null, beforeSave))
                .history(List.of(
                        new io.a2a.spec.Message.Builder()
                                .messageId("msg-db-001")
                                .role(io.a2a.spec.Message.Role.USER)
                                .parts(new TextPart("DB test"))
                                .metadata(Map.of("db-key", "db-value"))
                                .build()
                ))
                .artifacts(List.of(
                        new Artifact.Builder()
                                .artifactId("art-db-001")
                                .name("DB Artifact")
                                .parts(new TextPart("Artifact content"))
                                .metadata(Map.of("art-meta", 123))
                                .extensions(List.of("ext1"))
                                .build()
                ))
                .metadata(Map.of("task-meta", "task-value"))
                .build();
        
        taskStore.save(task);
        
        // Verify task table
        Map<String, Object> taskRow = jdbcTemplate.queryForMap(
                "SELECT * FROM a2a_tasks WHERE task_id = ?", taskId);
        assertThat(taskRow.get("task_id")).isEqualTo(taskId);
        assertThat(taskRow.get("status_state")).isEqualTo("working");
        assertThat(taskRow.get("metadata_json")).isNotNull();
        
        // Verify history table
        Map<String, Object> historyRow = jdbcTemplate.queryForMap(
                "SELECT * FROM a2a_history WHERE task_id = ?", taskId);
        assertThat(historyRow.get("task_id")).isEqualTo(taskId);
        assertThat(historyRow.get("message_id")).isEqualTo("msg-db-001");
        assertThat(historyRow.get("role")).isEqualTo("USER");
        assertThat(historyRow.get("content_json")).isNotNull();
        assertThat(historyRow.get("metadata_json")).isNotNull();
        assertThat(historyRow.get("sequence_num")).isEqualTo(0);
        
        // Verify artifacts table
        Map<String, Object> artifactRow = jdbcTemplate.queryForMap(
                "SELECT * FROM a2a_artifacts WHERE task_id = ?", taskId);
        assertThat(artifactRow.get("task_id")).isEqualTo(taskId);
        assertThat(artifactRow.get("artifact_id")).isEqualTo("art-db-001");
        assertThat(artifactRow.get("name")).isEqualTo("DB Artifact");
        assertThat(artifactRow.get("content_json")).isNotNull();
        assertThat(artifactRow.get("metadata_json")).isNotNull();
        assertThat(artifactRow.get("extensions_json")).isNotNull();
    }

    @Test
    void messageWithMultiplePartsPreservesAllParts() throws Exception {
        String taskId = "task-multi-parts";
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(
                        new io.a2a.spec.Message.Builder()
                                .role(io.a2a.spec.Message.Role.AGENT)
                                .parts(
                                        new TextPart("First part"),
                                        new TextPart("Second part"),
                                        new TextPart("Third part")
                                )
                                .build()
                ))
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        assertThat(retrieved.getHistory().get(0).getParts()).hasSize(3);
        List<?> parts = retrieved.getHistory().get(0).getParts();
        assertThat(((TextPart) parts.get(0)).getText()).isEqualTo("First part");
        assertThat(((TextPart) parts.get(1)).getText()).isEqualTo("Second part");
        assertThat(((TextPart) parts.get(2)).getText()).isEqualTo("Third part");
        
        // Verify in DB
        String contentJson = jdbcTemplate.queryForObject(
                "SELECT content_json FROM a2a_history WHERE task_id = ?",
                String.class, taskId);
        assertThat(contentJson).contains("First part");
        assertThat(contentJson).contains("Second part");
        assertThat(contentJson).contains("Third part");
    }

    @Test
    void largeTextContentIsPreserved() {
        String taskId = "task-large-text";
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeText.append("Line ").append(i).append(" with some content here. ");
        }
        String largeContent = largeText.toString();
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(
                        new io.a2a.spec.Message.Builder()
                                .role(io.a2a.spec.Message.Role.USER)
                                .parts(new TextPart(largeContent))
                                .build()
                ))
                .artifacts(List.of(
                        new Artifact.Builder()
                                .artifactId("art-large")
                                .name("Large Content")
                                .parts(new TextPart(largeContent))
                                .build()
                ))
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        String retrievedHistoryText = ((TextPart) retrieved.getHistory().get(0).getParts().get(0)).getText();
        String retrievedArtifactText = ((TextPart) retrieved.getArtifacts().get(0).parts().get(0)).getText();
        
        assertThat(retrievedHistoryText).hasSize(largeContent.length());
        assertThat(retrievedHistoryText).isEqualTo(largeContent);
        assertThat(retrievedArtifactText).isEqualTo(largeContent);
    }

    @Test
    void metadataWithNestedStructuresIsPreserved() {
        String taskId = "task-nested-metadata";
        
        Map<String, Object> deeplyNested = new LinkedHashMap<>();
        deeplyNested.put("level1", Map.of(
                "level2", Map.of(
                        "level3", Map.of("value", "deep")
                )
        ));
        deeplyNested.put("array", List.of(1, 2, List.of("nested", "array")));
        deeplyNested.put("mixed", List.of(
                Map.of("type", "object"),
                "string",
                123,
                true
        ));
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(
                        new io.a2a.spec.Message.Builder()
                                .role(io.a2a.spec.Message.Role.USER)
                                .parts(new TextPart("test"))
                                .metadata(deeplyNested)
                                .build()
                ))
                .metadata(deeplyNested)
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        assertThat(retrieved.getMetadata()).isNotNull();
        assertThat(retrieved.getMetadata()).containsKey("level1");
        assertThat(retrieved.getMetadata()).containsKey("array");
        assertThat(retrieved.getMetadata()).containsKey("mixed");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> level1 = (Map<String, Object>) retrieved.getMetadata().get("level1");
        assertThat(level1).containsKey("level2");
        
        @SuppressWarnings("unchecked")
        List<?> array = (List<?>) retrieved.getMetadata().get("array");
        assertThat(array).hasSize(3);
        
        @SuppressWarnings("unchecked")
        List<?> mixed = (List<?>) retrieved.getMetadata().get("mixed");
        assertThat(mixed).hasSize(4); // null values are dropped during JSON serialization
    }

    @Test
    void taskStatusTimestampIsPreserved() {
        String taskId = "task-status-timestamp";
        OffsetDateTime specificTime = OffsetDateTime.parse("2024-12-25T10:30:00+01:00");
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.COMPLETED, null, specificTime))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        assertThat(retrieved.getStatus().timestamp()).isNotNull();
        // Compare as string to avoid microsecond precision issues
        assertThat(retrieved.getStatus().timestamp().toString())
                .startsWith(specificTime.toString().substring(0, 19));
    }

    @Test
    void artifactExtensionsNullHandling() {
        String taskId = "task-artifact-null-ext";
        
        Task task = new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(createMessage(io.a2a.spec.Message.Role.USER, "test")))
                .artifacts(List.of(
                        new Artifact.Builder()
                                .artifactId("art-null-ext")
                                .name("No Extensions")
                                .parts(new TextPart("content"))
                                .extensions(null)
                                .build()
                ))
                .build();
        
        taskStore.save(task);
        Task retrieved = taskStore.get(taskId);
        
        Artifact artifact = retrieved.getArtifacts().get(0);
        assertThat(artifact.extensions()).isEmpty();
    }

    private Task createSampleTask(String taskId) {
        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.USER)
                .parts(new TextPart("Hello, agent!"))
                .contextId(taskId)
                .build();

        Artifact artifact = new Artifact.Builder()
                .artifactId("art-001")
                .name("Test Artifact")
                .parts(new TextPart("Artifact content"))
                .build();

        return new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(new TaskStatus(TaskState.WORKING, null, OffsetDateTime.now()))
                .history(List.of(message))
                .artifacts(List.of(artifact))
                .metadata(Map.of("key1", "value1"))
                .build();
    }

    private io.a2a.spec.Message createMessage(io.a2a.spec.Message.Role role, String content) {
        return new io.a2a.spec.Message.Builder()
                .role(role)
                .parts(new TextPart(content))
                .build();
    }

    private <T> T parseJson(String json, TypeReference<T> typeReference) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception first) {
            String unwrapped = objectMapper.readValue(json, String.class);
            return objectMapper.readValue(unwrapped, typeReference);
        }
    }
}
