package io.a2a.extras.taskstore.springai;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.a2a.spec.Message.Role.AGENT;
import static io.a2a.spec.Message.Role.USER;

public class TaskStoreChatMemoryAdapter implements ChatMemory {

    private static final Map<MessageType, Message.Role> MESSAGE_TYPE_TO_ROLE = Map.of(
        MessageType.USER, USER,
        MessageType.ASSISTANT, AGENT,
        MessageType.SYSTEM, USER,
        MessageType.TOOL, AGENT
    );

    private static final Map<Message.Role, Function<String, org.springframework.ai.chat.messages.Message>> ROLE_TO_MESSAGE = Map.of(
        USER, UserMessage::new,
        AGENT, AssistantMessage::new
    );

    private final TaskStore taskStore;

    public TaskStoreChatMemoryAdapter(TaskStore taskStore, A2aTaskStoreProperties properties) {
        this.taskStore = taskStore;
    }

    @Override
    public void add(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Task task = getOrCreateTask(conversationId);
        List<Message> a2aMessages = Stream.concat(
                task.getHistory().stream(),
                messages.stream().map(message -> convertToA2aMessage(message, conversationId))
            )
            .collect(Collectors.toCollection(ArrayList::new));

        taskStore.save(new Task.Builder(task).history(a2aMessages).build());
    }

    public List<org.springframework.ai.chat.messages.Message> get(String conversationId, int lastN) {
        Task task = taskStore.get(conversationId);
        if (task == null || task.getHistory().isEmpty()) {
            return List.of();
        }

        List<Message> history = task.getHistory();
        int startIndex = Math.max(0, history.size() - lastN);

        return history.subList(startIndex, history.size()).stream()
            .map(this::convertToSpringAiMessage)
            .toList();
    }

    @Override
    public List<org.springframework.ai.chat.messages.Message> get(String conversationId) {
        return get(conversationId, Integer.MAX_VALUE);
    }

    @Override
    public void clear(String conversationId) {
        Task task = taskStore.get(conversationId);
        if (task != null) {
            taskStore.save(new Task.Builder(task).history(List.of()).build());
        }
    }

    private Task getOrCreateTask(String conversationId) {
        Task task = taskStore.get(conversationId);
        if (task != null) return task;

        return new Task.Builder()
            .id(conversationId)
            .contextId(conversationId)
            .status(new TaskStatus(TaskState.WORKING, null, OffsetDateTime.now()))
            .history(List.of())
            .build();
    }

    private Message convertToA2aMessage(org.springframework.ai.chat.messages.Message springMessage, String conversationId) {
        return new Message.Builder()
            .role(MESSAGE_TYPE_TO_ROLE.getOrDefault(springMessage.getMessageType(), USER))
            .parts(List.of(new TextPart(springMessage.getText())))
            .contextId(conversationId)
            .taskId(conversationId)
            .build();
    }

    private org.springframework.ai.chat.messages.Message convertToSpringAiMessage(Message a2aMessage) {
        String content = extractTextContent(a2aMessage);
        return ROLE_TO_MESSAGE.getOrDefault(a2aMessage.getRole(), UserMessage::new).apply(content);
    }

    private String extractTextContent(Message a2aMessage) {
        if (a2aMessage.getParts() == null) {
            return "";
        }

        return a2aMessage.getParts().stream()
            .filter(TextPart.class::isInstance)
            .map(TextPart.class::cast)
            .map(TextPart::getText)
            .collect(Collectors.joining());
    }
}
