package io.a2a.extras.taskstore.jdbc.mapper;

import io.a2a.extras.taskstore.jdbc.entity.MessageEntity;
import io.a2a.extras.taskstore.jdbc.entity.Role;
import io.a2a.spec.Message;
import org.springframework.stereotype.Component;

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
        MessageEntity entity = new MessageEntity();
        entity.setMessageId(message.getMessageId());
        entity.setTaskId(taskId);
        entity.setRole(mapRole(message.getRole()));
        entity.setParts(partConverter.toJsonNode(message.getParts()));
        entity.setContextId(message.getContextId());
        entity.setReferenceTaskIds(jsonUtils.toJsonNode(message.getReferenceTaskIds()));
        entity.setMetadata(jsonUtils.toJsonNode(message.getMetadata()));
        entity.setExtensions(jsonUtils.toJsonNode(message.getExtensions()));
        return entity;
    }

    public Message fromEntity(MessageEntity entity) {
        return new Message.Builder()
                .messageId(entity.getMessageId())
                .role(mapRole(entity.getRole()))
                .parts(partConverter.fromJsonNode(entity.getParts()))
                .contextId(entity.getContextId())
                .referenceTaskIds(jsonUtils.fromJsonNode(entity.getReferenceTaskIds(), java.util.List.class))
                .metadata(jsonUtils.fromJsonNode(entity.getMetadata(), Map.class))
                .extensions(jsonUtils.fromJsonNode(entity.getExtensions(), java.util.List.class))
                .build();
    }

    private Role mapRole(Message.Role specRole) {
        return specRole == null ? null : Role.valueOf(specRole.name());
    }

    private Message.Role mapRole(Role role) {
        return role == null ? null : Message.Role.valueOf(role.name());
    }
}
