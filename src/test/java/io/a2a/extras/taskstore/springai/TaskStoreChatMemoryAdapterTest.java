package io.a2a.extras.taskstore.springai;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskStoreChatMemoryAdapterTest {

    @Mock
    private TaskStore taskStore;

    @Mock
    private TaskStateProvider taskStateProvider;

    private A2aTaskStoreProperties properties;
    private TaskStoreChatMemoryAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new A2aTaskStoreProperties();
        adapter = new TaskStoreChatMemoryAdapter(taskStore, taskStateProvider, properties);
    }

    @Test
    void addMessagesToNewConversation() {
        String conversationId = "conv-123";
        UserMessage springMessage = new UserMessage("Hello, agent!");

        when(taskStore.get(conversationId)).thenReturn(null);

        adapter.add(conversationId, List.of(springMessage));

        verify(taskStore).get(conversationId);
        verify(taskStore).save(any(Task.class));
    }

    @Test
    void addMessagesToExistingConversation() {
        String conversationId = "conv-123";
        UserMessage springMessage = new UserMessage("Follow up message");

        Task existingTask = createSampleTask(conversationId);
        when(taskStore.get(conversationId)).thenReturn(existingTask);

        adapter.add(conversationId, List.of(springMessage));

        verify(taskStore).save(argThat(task ->
            task.getHistory().size() == 2
        ));
    }

    @Test
    void addEmptyMessageList() {
        String conversationId = "conv-123";

        adapter.add(conversationId, List.of());

        verify(taskStore, never()).get(any());
        verify(taskStore, never()).save(any());
    }

    @Test
    void addNullMessageList() {
        String conversationId = "conv-123";

        adapter.add(conversationId, (List<Message>) null);

        verify(taskStore, never()).get(any());
        verify(taskStore, never()).save(any());
    }

    @Test
    void getAllMessages() {
        String conversationId = "conv-123";
        Task task = createSampleTask(conversationId);

        when(taskStore.get(conversationId)).thenReturn(task);

        List<Message> messages = adapter.get(conversationId);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void getLastNMessages() {
        String conversationId = "conv-123";
        Task task = createTaskWithMultipleMessages(conversationId, 5);

        when(taskStore.get(conversationId)).thenReturn(task);

        List<Message> messages = adapter.get(conversationId, 3);

        assertThat(messages).hasSize(3);
    }

    @Test
    void getLastNMessagesWhenNExceedsHistorySize() {
        String conversationId = "conv-123";
        Task task = createTaskWithMultipleMessages(conversationId, 2);

        when(taskStore.get(conversationId)).thenReturn(task);

        List<Message> messages = adapter.get(conversationId, 10);

        assertThat(messages).hasSize(2);
    }

    @Test
    void getLastNMessagesWhenNIsZero() {
        String conversationId = "conv-123";
        Task task = createTaskWithMultipleMessages(conversationId, 3);

        when(taskStore.get(conversationId)).thenReturn(task);

        List<Message> messages = adapter.get(conversationId, 0);

        assertThat(messages).isEmpty();
    }

    @Test
    void getFromNonExistentConversation() {
        String conversationId = "conv-missing";

        when(taskStore.get(conversationId)).thenReturn(null);

        List<Message> messages = adapter.get(conversationId);

        assertThat(messages).isEmpty();
    }

    @Test
    void getFromConversationWithEmptyHistory() {
        String conversationId = "conv-empty";
        Task task = new Task.Builder()
                .id(conversationId)
                .contextId(conversationId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of())
                .build();

        when(taskStore.get(conversationId)).thenReturn(task);

        List<Message> messages = adapter.get(conversationId);

        assertThat(messages).isEmpty();
    }

    @Test
    void clearConversation() {
        String conversationId = "conv-123";
        Task task = createSampleTask(conversationId);

        when(taskStore.get(conversationId)).thenReturn(task);

        adapter.clear(conversationId);

        verify(taskStore).save(argThat(t -> t.getHistory().isEmpty()));
    }

    @Test
    void clearNonExistentConversation() {
        String conversationId = "conv-missing";

        when(taskStore.get(conversationId)).thenReturn(null);

        adapter.clear(conversationId);

        verify(taskStore, never()).save(any());
    }

    @Test
    void convertUserMessage() {
        String conversationId = "conv-123";
        UserMessage springMessage = new UserMessage("User question");

        when(taskStore.get(conversationId)).thenReturn(null);

        adapter.add(conversationId, List.of(springMessage));

        verify(taskStore).save(argThat(task -> {
            if (task.getHistory().isEmpty()) return false;
            io.a2a.spec.Message a2aMessage = task.getHistory().get(0);
            return a2aMessage.getRole() == io.a2a.spec.Message.Role.USER;
        }));
    }

    @Test
    void convertAssistantMessage() {
        String conversationId = "conv-123";
        AssistantMessage springMessage = new AssistantMessage("Assistant response");

        when(taskStore.get(conversationId)).thenReturn(null);

        adapter.add(conversationId, List.of(springMessage));

        verify(taskStore).save(argThat(task -> {
            if (task.getHistory().isEmpty()) return false;
            io.a2a.spec.Message a2aMessage = task.getHistory().get(0);
            return a2aMessage.getRole() == io.a2a.spec.Message.Role.AGENT;
        }));
    }

    @Test
    void convertSystemMessage() {
        String conversationId = "conv-123";
        SystemMessage springMessage = new SystemMessage("System instruction");

        when(taskStore.get(conversationId)).thenReturn(null);

        adapter.add(conversationId, List.of(springMessage));

        verify(taskStore).save(argThat(task -> {
            if (task.getHistory().isEmpty()) return false;
            io.a2a.spec.Message a2aMessage = task.getHistory().get(0);
            return a2aMessage.getRole() == io.a2a.spec.Message.Role.USER;
        }));
    }

    @Test
    void convertToolMessage() {
        String conversationId = "conv-123";
        Message springMessage = mock(Message.class);
        when(springMessage.getMessageType()).thenReturn(MessageType.TOOL);
        when(springMessage.getText()).thenReturn("Tool output");
        when(taskStore.get(conversationId)).thenReturn(null);

        adapter.add(conversationId, List.of(springMessage));

        verify(taskStore).save(argThat(task -> {
            if (task.getHistory().isEmpty()) return false;
            io.a2a.spec.Message a2aMessage = task.getHistory().get(0);
            return a2aMessage.getRole() == io.a2a.spec.Message.Role.AGENT;
        }));
    }

    @Test
    void extractTextContent() {
        String conversationId = "conv-123";
        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
            .role(io.a2a.spec.Message.Role.USER)
            .parts(new TextPart("Hello"))
            .contextId(conversationId)
            .build();

        Task task = new Task.Builder()
            .id(conversationId)
            .contextId(conversationId)
            .status(new TaskStatus(TaskState.WORKING))
            .history(List.of(a2aMessage))
            .build();

        when(taskStore.get(conversationId)).thenReturn(task);

        List<Message> messages = adapter.get(conversationId);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).isEqualTo("Hello");
    }

    @Test
    void extractTextContentIgnoresNonTextParts() {
        String conversationId = "conv-123";
        io.a2a.spec.Message a2aMessage = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.USER)
                .parts(new DataPart(java.util.Map.of("key", "value")))
                .contextId(conversationId)
                .build();

        Task task = new Task.Builder()
                .id(conversationId)
                .contextId(conversationId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(List.of(a2aMessage))
                .build();

        when(taskStore.get(conversationId)).thenReturn(task);

        List<Message> messages = adapter.get(conversationId);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).isEmpty();
    }

    private Task createSampleTask(String conversationId) {
        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
            .role(io.a2a.spec.Message.Role.USER)
            .parts(new TextPart("Initial message"))
            .contextId(conversationId)
            .build();

        return new Task.Builder()
            .id(conversationId)
            .contextId(conversationId)
            .status(new TaskStatus(TaskState.WORKING))
            .history(List.of(message))
            .build();
    }

    private Task createTaskWithMultipleMessages(String conversationId, int messageCount) {
        List<io.a2a.spec.Message> messages = IntStream.range(0, messageCount)
            .mapToObj(i -> new io.a2a.spec.Message.Builder()
                .role(i % 2 == 0 ? io.a2a.spec.Message.Role.USER : io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("Message " + i))
                .contextId(conversationId)
                .build())
            .toList();

        return new Task.Builder()
            .id(conversationId)
            .contextId(conversationId)
            .status(new TaskStatus(TaskState.WORKING))
            .history(messages)
            .build();
    }
}
