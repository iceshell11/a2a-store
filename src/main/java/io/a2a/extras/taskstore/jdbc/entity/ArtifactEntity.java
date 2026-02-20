package io.a2a.extras.taskstore.jdbc.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "artifacts")
@Getter
@Setter
public class ArtifactEntity {

    @Id
    @Column(name = "artifact_id", nullable = false, updatable = false, length = 255)
    private String artifactId;

    @Column(name = "task_id", nullable = false, length = 255)
    private String taskId;

    @Column(name = "name", length = 500)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "parts", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode parts;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode metadata;

    @Column(name = "extensions", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode extensions;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
