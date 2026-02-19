package io.a2a.extras.taskstore.jdbc;

import io.a2a.spec.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static io.a2a.extras.taskstore.support.TaskTestBuilder.aTask;
import static org.assertj.core.api.Assertions.assertThat;

class DataIntegrityTest extends BaseJdbcIntegrationTest {

    @BeforeEach
    void setUp() {
        setUpTaskStore();
    }

    @Test
    void shouldStoreAllTaskFieldsInDatabase() {
        OffsetDateTime timestamp = OffsetDateTime.now();
        Task task = aTask()
                .withId("db-task")
                .withContextId("ctx-123")
                .withTimestamp(timestamp)
                .build();

        taskStore.save(task);

        Map<String, Object> taskRow = jdbcTemplate.queryForMap(
                "SELECT * FROM a2a_tasks WHERE task_id = ?", "db-task");

        assertThat(taskRow.get("task_id")).isEqualTo("db-task");
        assertThat(taskRow.get("context_id")).isEqualTo("ctx-123");
        assertThat(taskRow.get("status_state")).isEqualTo("working");
        assertThat(taskRow.get("status_timestamp")).isNotNull();
    }

    @Test
    void shouldStoreAllHistoryFieldsInDatabase() {
        Message message = new Message.Builder()
                .messageId("msg-db-001")
                .role(Message.Role.AGENT)
                .parts(new TextPart("DB test content"))
                .metadata(Map.of("key", "value"))
                .build();

        Task task = aTask()
                .withId("db-history")
                .withMessage(message)
                .build();

        taskStore.save(task);

        Map<String, Object> historyRow = jdbcTemplate.queryForMap(
                "SELECT * FROM a2a_history WHERE task_id = ?", "db-history");

        assertThat(historyRow.get("task_id")).isEqualTo("db-history");
        assertThat(historyRow.get("message_id")).isEqualTo("msg-db-001");
        assertThat(historyRow.get("role")).isEqualTo("AGENT");
        assertThat(historyRow.get("content_json")).isNotNull();
        assertThat(historyRow.get("metadata_json")).isNotNull();
        assertThat(historyRow.get("sequence_num")).isEqualTo(0);
    }

    @Test
    void shouldStoreAllArtifactFieldsInDatabase() {
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-db-001")
                .name("DB Artifact")
                .description("DB Description")
                .parts(new TextPart("DB content"))
                .metadata(Map.of("meta", "data"))
                .extensions(java.util.List.of("ext1", "ext2"))
                .build();

        Task task = aTask()
                .withId("db-artifact")
                .withArtifact(artifact)
                .build();

        taskStore.save(task);

        Map<String, Object> artifactRow = jdbcTemplate.queryForMap(
                "SELECT * FROM a2a_artifacts WHERE task_id = ?", "db-artifact");

        assertThat(artifactRow.get("task_id")).isEqualTo("db-artifact");
        assertThat(artifactRow.get("artifact_id")).isEqualTo("art-db-001");
        assertThat(artifactRow.get("name")).isEqualTo("DB Artifact");
        assertThat(artifactRow.get("description")).isEqualTo("DB Description");
        assertThat(artifactRow.get("content_json")).isNotNull();
        assertThat(artifactRow.get("metadata_json")).isNotNull();
        assertThat(artifactRow.get("extensions_json")).isNotNull();
    }

    @Test
    void shouldGenerateMessageIdWhenNotProvided() {
        // Build task with message but without explicit messageId
        Task task = aTask()
                .withId("gen-msg-id")
                .withMessage(Message.Role.USER, "Test")
                .build();

        taskStore.save(task);

        String messageId = jdbcTemplate.queryForObject(
                "SELECT message_id FROM a2a_history WHERE task_id = ?",
                String.class, "gen-msg-id");

        // Message ID should be generated - either by Message.Builder (UUID) or by repository (task-msg-N)
        assertThat(messageId).isNotNull();
        assertThat(messageId).isNotEmpty();
    }

    @Test
    void shouldCascadeDeleteToHistory() {
        Task task = aTask()
                .withId("cascade-test")
                .withMessages(3, Message.Role.USER, "Msg")
                .build();
        taskStore.save(task);

        int countBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM a2a_history WHERE task_id = ?",
                Integer.class, "cascade-test");
        assertThat(countBefore).isEqualTo(3);

        taskStore.delete("cascade-test");

        int countAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM a2a_history WHERE task_id = ?",
                Integer.class, "cascade-test");
        assertThat(countAfter).isEqualTo(0);
    }

    @Test
    void shouldCascadeDeleteToArtifacts() {
        Task task = aTask()
                .withId("cascade-art")
                .withArtifact("art-1", "Art1", "Content1")
                .withArtifact("art-2", "Art2", "Content2")
                .build();
        taskStore.save(task);

        int countBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM a2a_artifacts WHERE task_id = ?",
                Integer.class, "cascade-art");
        assertThat(countBefore).isEqualTo(2);

        taskStore.delete("cascade-art");

        int countAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM a2a_artifacts WHERE task_id = ?",
                Integer.class, "cascade-art");
        assertThat(countAfter).isEqualTo(0);
    }

    @Test
    void shouldPreserveUnicodeContent() {
        Task task = aTask()
                .withId("unicode-task-日本語")
                .withMessage(Message.Role.USER, "日本語コンテンツ 🎌")
                .build();

        taskStore.save(task);

        String contentJson = jdbcTemplate.queryForObject(
                "SELECT content_json FROM a2a_history WHERE task_id = ?",
                String.class, "unicode-task-日本語");

        assertThat(contentJson).contains("日本語コンテンツ");
    }

    @Test
    void shouldHandleSpecialCharactersInContent() {
        String specialContent = "Special: \"quotes\" 'apostrophes' \\backslash \n newline \t tab";

        Task task = aTask()
                .withId("special-chars")
                .withMessage(Message.Role.USER, specialContent)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("special-chars");

        String retrievedContent = ((TextPart) retrieved.getHistory().get(0).getParts().get(0)).getText();
        assertThat(retrievedContent).isEqualTo(specialContent);
    }

    @Test
    void shouldSetFinalizedAtForCompletedTask() {
        Task task = aTask()
                .withId("finalized-test")
                .withStatus(TaskState.COMPLETED)
                .build();

        taskStore.save(task);

        Object finalizedAt = jdbcTemplate.queryForObject(
                "SELECT finalized_at FROM a2a_tasks WHERE task_id = ?",
                Object.class, "finalized-test");

        assertThat(finalizedAt).isNotNull();
    }

    @Test
    void shouldNotSetFinalizedAtForWorkingTask() {
        Task task = aTask()
                .withId("not-finalized")
                .withStatus(TaskState.WORKING)
                .build();

        taskStore.save(task);

        Object finalizedAt = jdbcTemplate.queryForObject(
                "SELECT finalized_at FROM a2a_tasks WHERE task_id = ?",
                Object.class, "not-finalized");

        assertThat(finalizedAt).isNull();
    }
}
