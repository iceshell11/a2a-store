package io.a2a.extras.taskstore.jdbc.repository;

import io.a2a.extras.taskstore.jdbc.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findByTaskIdOrderByCreatedAtAsc(UUID taskId);

    List<MessageEntity> findByContextId(String contextId);

    void deleteByTaskId(UUID taskId);
}
