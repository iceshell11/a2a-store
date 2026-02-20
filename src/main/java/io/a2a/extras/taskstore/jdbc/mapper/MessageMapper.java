package io.a2a.extras.taskstore.jdbc.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import io.a2a.extras.taskstore.jdbc.entity.MessageEntity;
import io.a2a.spec.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MessageMapper {

    private final PartConverter partConverter;
    private final JsonUtils jsonUtils;

    public MessageMapper(PartConverter partConverter, JsonUtils jsonUtils) {
        this.partConverter = partConverter;
        this.jsonUtils = jsonUtils;
    }

    public MessageEntity toEntity(Message message, String taskId) {
        return new MessageEntity()
                .setMessageId(message.getMessageId())
                .setTaskId(taskId)
                .setRole(message.getRole())
                .setParts(partConverter.toJsonNode(message.getParts()))
                .setContextId(message.getContextId())
                .setReferenceTaskIds(jsonUtils.toJsonNode(message.getReferenceTaskIds()))
                .setMetadata(jsonUtils.toJsonNode(message.getMetadata()))
                .setExtensions(jsonUtils.toJsonNode(message.getExtensions()));
    }

    public Message fromEntity(MessageEntity entity) {
        return new Message.Builder()
                .messageId(entity.getMessageId())
                .role(entity.getRole())
                .parts(partConverter.fromJsonNode(entity.getParts()))
                .contextId(entity.getContextId())
                .referenceTaskIds(jsonUtils.fromJsonNode(entity.getReferenceTaskIds(), new TypeReference<List<String>>(){}))
                .metadata(jsonUtils.fromJsonNode(entity.getMetadata(), new TypeReference<Map<String, Object>>(){}))
                .extensions(jsonUtils.fromJsonNode(entity.getExtensions(), new TypeReference<List<String>>(){}))
                .build();
    }
}
