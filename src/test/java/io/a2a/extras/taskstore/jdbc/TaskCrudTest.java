package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.support.TaskTestBuilder;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.a2a.extras.taskstore.support.TaskTestBuilder.aTask;
import static org.assertj.core.api.Assertions.assertThat;

class TaskCrudTest extends BaseJdbcIntegrationTest {

    @BeforeEach
    void setUp() {
        setUpTaskStore();
    }

    @Test
    void shouldSaveAndRetrieveTask() {
        Task task = aTask()
                .withId("task-123")
                .withStatus(TaskState.WORKING)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("task-123");

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("task-123");
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.WORKING);
    }

    @Test
    void shouldReturnNullForNonExistentTask() {
        Task retrieved = taskStore.get("non-existent");
        assertThat(retrieved).isNull();
    }

    @Test
    void shouldDeleteTask() {
        Task task = aTask().withId("task-delete").build();
        taskStore.save(task);
        assertThat(taskStore.get("task-delete")).isNotNull();

        taskStore.delete("task-delete");
        assertThat(taskStore.get("task-delete")).isNull();
    }

    @Test
    void shouldUpdateExistingTask() {
        Task initialTask = aTask()
                .withId("task-update")
                .withStatus(TaskState.WORKING)
                .withMessage(io.a2a.spec.Message.Role.USER, "Initial")
                .build();
        taskStore.save(initialTask);

        Task updatedTask = aTask()
                .withId("task-update")
                .withStatus(TaskState.COMPLETED)
                .withMessage(io.a2a.spec.Message.Role.USER, "Initial")
                .withMessage(io.a2a.spec.Message.Role.AGENT, "Response")
                .build();
        taskStore.save(updatedTask);

        Task retrieved = taskStore.get("task-update");
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(retrieved.getHistory()).hasSize(2);
    }

    @Test
    void shouldPreserveContextId() {
        Task task = aTask()
                .withId("task-ctx")
                .withContextId("ctx-789")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("task-ctx");

        assertThat(retrieved.getContextId()).isEqualTo("ctx-789");
    }

    @Test
    void shouldHandleNullContextId() {
        Task task = aTask()
                .withId("task-null-ctx")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("task-null-ctx");

        assertThat(retrieved.getContextId()).isEqualTo("task-null-ctx");
    }

    @Test
    void shouldPreserveStatusTimestamp() {
        OffsetDateTime specificTime = OffsetDateTime.parse("2024-12-25T10:30:00+01:00");
        Task task = aTask()
                .withId("task-timestamp")
                .withTimestamp(specificTime)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("task-timestamp");

        assertThat(retrieved.getStatus().timestamp()).isNotNull();
        // Compare up to seconds to avoid precision issues
        assertThat(retrieved.getStatus().timestamp().toLocalDate())
                .isEqualTo(specificTime.toLocalDate());
        assertThat(retrieved.getStatus().timestamp().getHour())
                .isEqualTo(specificTime.getHour());
        assertThat(retrieved.getStatus().timestamp().getMinute())
                .isEqualTo(specificTime.getMinute());
    }

    @Test
    void shouldHandleStatusWithMessage() {
        Task task = aTask()
                .withId("task-status-msg")
                .withStatusMessage("Status update message")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("task-status-msg");

        assertThat(retrieved.getStatus().message()).isNotNull();
        assertThat(((io.a2a.spec.TextPart) retrieved.getStatus().message().getParts().get(0)).getText())
                .isEqualTo("Status update message");
    }

    @Test
    void shouldHandleTwoWordStatus() {
        Task task = aTask()
                .withId("task-input-req")
                .withStatus(TaskState.INPUT_REQUIRED)
                .build();

        taskStore.save(task);

        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status_state FROM a2a_tasks WHERE task_id = ?",
                String.class,
                "task-input-req"
        );

        assertThat(storedStatus).isEqualTo("input-required");
        assertThat(taskStore.get("task-input-req").getStatus().state())
                .isEqualTo(TaskState.INPUT_REQUIRED);
    }

    @Test
    void shouldReturnActiveStatus() {
        taskStore.save(aTask().withId("active-1").withStatus(TaskState.WORKING).build());
        taskStore.save(aTask().withId("active-2").withStatus(TaskState.INPUT_REQUIRED).build());
        taskStore.save(aTask().withId("completed").withStatus(TaskState.COMPLETED).build());

        assertThat(taskStore.isTaskActive("active-1")).isTrue();
        assertThat(taskStore.isTaskActive("active-2")).isTrue();
        assertThat(taskStore.isTaskActive("completed")).isFalse();
        assertThat(taskStore.isTaskActive("non-existent")).isFalse();
    }

    @Test
    void shouldReturnFinalizedStatus() {
        taskStore.save(aTask().withId("working").withStatus(TaskState.WORKING).build());
        taskStore.save(aTask().withId("done").withStatus(TaskState.COMPLETED).build());

        assertThat(taskStore.isTaskFinalized("working")).isFalse();
        assertThat(taskStore.isTaskFinalized("done")).isTrue();
        assertThat(taskStore.isTaskFinalized("non-existent")).isFalse();
    }
}
