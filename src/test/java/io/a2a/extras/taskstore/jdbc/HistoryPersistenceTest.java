package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.support.TaskTestBuilder;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.a2a.extras.taskstore.support.TaskTestBuilder.aMessage;
import static io.a2a.extras.taskstore.support.TaskTestBuilder.aTask;
import static org.assertj.core.api.Assertions.assertThat;

class HistoryPersistenceTest extends BaseJdbcIntegrationTest {

    @BeforeEach
    void setUp() {
        setUpTaskStore();
    }

    @Test
    void shouldSaveAndRetrieveSingleMessage() {
        Task task = aTask()
                .withId("hist-1")
                .withMessage(Message.Role.USER, "Hello")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-1");

        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(retrieved.getHistory().get(0).getRole()).isEqualTo(Message.Role.USER);
        assertThat(((TextPart) retrieved.getHistory().get(0).getParts().get(0)).getText())
                .isEqualTo("Hello");
    }

    @Test
    void shouldPreserveMultipleMessages() {
        Task task = aTask()
                .withId("hist-multi")
                .withMessage(Message.Role.USER, "Message 1")
                .withMessage(Message.Role.AGENT, "Response 1")
                .withMessage(Message.Role.USER, "Message 2")
                .withMessage(Message.Role.AGENT, "Response 2")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-multi");

        assertThat(retrieved.getHistory()).hasSize(4);
        assertThat(retrieved.getHistory().get(0).getRole()).isEqualTo(Message.Role.USER);
        assertThat(retrieved.getHistory().get(1).getRole()).isEqualTo(Message.Role.AGENT);
        assertThat(retrieved.getHistory().get(2).getRole()).isEqualTo(Message.Role.USER);
        assertThat(retrieved.getHistory().get(3).getRole()).isEqualTo(Message.Role.AGENT);
    }

    @Test
    void shouldPreserveMessageId() {
        Message message = new Message.Builder()
                .messageId("custom-msg-id")
                .role(Message.Role.USER)
                .parts(new TextPart("Test"))
                .build();

        Task task = aTask()
                .withId("hist-msg-id")
                .withMessage(message)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-msg-id");

        assertThat(retrieved.getHistory().get(0).getMessageId()).isEqualTo("custom-msg-id");
    }

    @Test
    void shouldPreserveMessageMetadata() {
        Message message = new Message.Builder()
                .role(Message.Role.USER)
                .parts(new TextPart("Test"))
                .metadata(Map.of("key1", "value1", "count", 42))
                .build();

        Task task = aTask()
                .withId("hist-meta")
                .withMessage(message)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-meta");

        assertThat(retrieved.getHistory().get(0).getMetadata())
                .containsEntry("key1", "value1")
                .containsEntry("count", 42);
    }

    @Test
    void shouldAppendNewMessagesToExistingHistory() {
        Task initialTask = aTask()
                .withId("hist-append")
                .withMessage(Message.Role.USER, "First")
                .build();
        taskStore.save(initialTask);

        Task updatedTask = aTask()
                .withId("hist-append")
                .withMessage(Message.Role.USER, "First")
                .withMessage(Message.Role.AGENT, "Second")
                .withMessage(Message.Role.USER, "Third")
                .build();
        taskStore.save(updatedTask);

        Task retrieved = taskStore.get("hist-append");
        assertThat(retrieved.getHistory()).hasSize(3);
    }

    @Test
    void shouldPreserveMessageOrder() {
        Task task = aTask()
                .withId("hist-order")
                .withMessages(5, Message.Role.USER, "Msg")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-order");

        assertThat(retrieved.getHistory()).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(((TextPart) retrieved.getHistory().get(i).getParts().get(0)).getText())
                    .isEqualTo("Msg " + i);
        }
    }

    @Test
    void shouldHandleEmptyHistory() {
        Task task = aTask()
                .withId("hist-empty")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-empty");

        assertThat(retrieved.getHistory()).isEmpty();
    }

    @Test
    void shouldClearHistoryWhenSavedWithEmptyList() {
        Task initialTask = aTask()
                .withId("hist-clear")
                .withMessage(Message.Role.USER, "Initial")
                .build();
        taskStore.save(initialTask);

        Task emptyTask = new io.a2a.spec.Task.Builder()
                .id("hist-clear")
                .contextId("hist-clear")
                .status(new io.a2a.spec.TaskStatus(io.a2a.spec.TaskState.WORKING))
                .history(List.of())
                .build();
        taskStore.save(emptyTask);

        Task retrieved = taskStore.get("hist-clear");
        assertThat(retrieved.getHistory()).isEmpty();
    }

    @Test
    void shouldPreserveMultiplePartsInMessage() {
        Message message = new Message.Builder()
                .role(Message.Role.AGENT)
                .parts(
                        new TextPart("Part 1"),
                        new TextPart("Part 2"),
                        new TextPart("Part 3")
                )
                .build();

        Task task = aTask()
                .withId("hist-parts")
                .withMessage(message)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-parts");

        List<?> parts = retrieved.getHistory().get(0).getParts();
        assertThat(parts).hasSize(3);
        assertThat(((TextPart) parts.get(0)).getText()).isEqualTo("Part 1");
        assertThat(((TextPart) parts.get(1)).getText()).isEqualTo("Part 2");
        assertThat(((TextPart) parts.get(2)).getText()).isEqualTo("Part 3");
    }

    @Test
    void shouldHandleUnicodeInMessages() {
        Task task = aTask()
                .withId("hist-unicode-日本語")
                .withMessage(Message.Role.USER, "Hello 世界! 🌍 Привет мир!")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-unicode-日本語");

        assertThat(((TextPart) retrieved.getHistory().get(0).getParts().get(0)).getText())
                .isEqualTo("Hello 世界! 🌍 Привет мир!");
    }

    @Test
    void shouldHandleLargeMessageContent() {
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeContent.append("Line ").append(i).append(" with content. ");
        }
        String content = largeContent.toString();

        Task task = aTask()
                .withId("hist-large")
                .withMessage(Message.Role.USER, content)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("hist-large");

        assertThat(((TextPart) retrieved.getHistory().get(0).getParts().get(0)).getText())
                .hasSize(content.length())
                .isEqualTo(content);
    }
}
