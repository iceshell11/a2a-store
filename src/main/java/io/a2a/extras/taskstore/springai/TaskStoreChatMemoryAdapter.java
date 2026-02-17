package io.a2a.extras.taskstore.springai;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.*;
import io.a2a.spec.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.messages.MessageType;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class TaskStoreChatMemoryAdapter implements ChatMemory {

    private static final Map<MessageType, io.a2a.spec.Message.Role> MESSAGE_TYPE_TO_ROLE = Map.of(
        MessageType.USER, io.a2a.spec.Message.Role.USER,
        MessageType.ASSISTANT, io.a2a.spec.Message.Role.AGENT,
        MessageType.SYSTEM, io.a2a.spec.Message.Role.USER,
        MessageType.TOOL, io.a2a.spec.Message.Role.AGENT
    );

    private static final Map<io.a2a.spec.Message.Role, Function<String, org.springframework.ai.chat.messages.Message>> ROLE_TO_MESSAGE = Map.of(
        io.a2a.spec.Message.Role.USER, UserMessage::new,
        io.a2a.spec.Message.Role.AGENT, AssistantMessage::new
    );

    private final TaskStore taskStore;
    private final TaskStateProvider taskStateProvider;
    private final A2aTaskStoreProperties properties;

    public TaskStoreChatMemoryAdapter(TaskStore taskStore, TaskStateProvider taskStateProvider, A2aTaskStoreProperties properties) {
        this.taskStore = taskStore;
        this.taskStateProvider = taskStateProvider;
        this.properties = properties;
    }

    @Override
    public void add(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        Task task = getOrCreateTask(conversationId);
        List<Message> a2aMessages = new ArrayList<>(task.getHistory());
        messages.stream()
            .map(msg -> convertToA2aMessage(msg, conversationId))
            .forEach(a2aMessages::add);

        taskStore.save(new Task.Builder(task).history(a2aMessages).build());
    }

    public List<org.springframework.ai.chat.messages.Message> get(String conversationId, int lastN) {
        Task task = taskStore.get(conversationId);
        if (task == null || task.getHistory().isEmpty()) {
            return List.of();
        }
        
        List<Message> history = task.getHistory();
        
        // Get last N messages
        int startIndex = Math.max(0, history.size() - lastN);
        List<Message> recentMessages = history.subList(startIndex, history.size());
        
        return recentMessages.stream()
            .map(this::convertToSpringAiMessage)
            .collect(Collectors.toList());
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
            .role(MESSAGE_TYPE_TO_ROLE.getOrDefault(springMessage.getMessageType(), Message.Role.USER))
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
        if (a2aMessage.getParts() == null) return "";
        return a2aMessage.getParts().stream()
            .filter(part -> part instanceof TextPart)
            .map(part -> ((TextPart) part).getText())
            .collect(Collectors.joining());
    }
}
