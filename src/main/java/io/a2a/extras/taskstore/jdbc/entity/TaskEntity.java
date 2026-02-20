package io.a2a.extras.taskstore.jdbc.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tasks")
@Getter
@Setter
public class TaskEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 255)
    private String id;

    @Column(name = "context_id", nullable = false, length = 255)
    private String contextId;

    @Column(name = "status_state", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private TaskState statusState;

    @Column(name = "status_message", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode statusMessage;

    @Column(name = "status_timestamp", nullable = false)
    private Instant statusTimestamp;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @OneToMany(mappedBy = "taskId", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ArtifactEntity> artifacts = new ArrayList<>();

    @OneToMany(mappedBy = "taskId", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MessageEntity> messages = new ArrayList<>();
}
