package io.a2a.extras.taskstore.springai;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.CollectionUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	public void add(String taskId, List<org.springframework.ai.chat.messages.Message> messages) {
		if (CollectionUtils.isEmpty(messages)) {
			return;
		}

		Task task = getOrCreateTask(taskId);
		List<Message> a2aMessages = Stream.concat(
				task.getHistory().stream(),
				messages.stream().map(message -> convertToA2aMessage(message, taskId))
			)
			.collect(Collectors.toCollection(ArrayList::new));

		taskStore.save(new Task.Builder(task).history(a2aMessages).build());
	}

	public List<org.springframework.ai.chat.messages.Message> get(String taskId, int lastN) {
		Task task = taskStore.get(taskId);
		if (task == null || CollectionUtils.isEmpty(task.getHistory())) {
			return List.of();
		}

		List<Message> history = task.getHistory();
		int startIndex = Math.max(0, history.size() - lastN);

		return history.subList(startIndex, history.size()).stream()
			.map(this::convertToSpringAiMessage)
			.toList();
	}

	@Override
	public List<org.springframework.ai.chat.messages.Message> get(String taskId) {
		return get(taskId, Integer.MAX_VALUE);
	}

	@Override
	public void clear(String taskId) {
		Task task = taskStore.get(taskId);
		if (task != null) {
			taskStore.save(new Task.Builder(task).history(List.of()).build());
		}
	}

	private Task getOrCreateTask(String taskId) {
		return Optional.ofNullable(taskStore.get(taskId))
			.orElseGet(() ->
				new Task.Builder()
					.id(taskId)
					.contextId(taskId)
					.status(new TaskStatus(TaskState.WORKING, null, OffsetDateTime.now()))
					.history(List.of())
					.build()
			);
	}

	private Message convertToA2aMessage(org.springframework.ai.chat.messages.Message springMessage, String taskId) {
		return new Message.Builder()
			.role(MESSAGE_TYPE_TO_ROLE.getOrDefault(springMessage.getMessageType(), USER))
			.parts(List.of(new TextPart(springMessage.getText())))
			.contextId(taskId)
			.taskId(taskId)
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
