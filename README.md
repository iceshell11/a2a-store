# A2A TaskStore JDBC + Spring AI ChatMemory Adapter

A PostgreSQL JDBC implementation of the A2A (Agent-to-Agent) protocol `TaskStore` interface with a Spring AI `ChatMemory` adapter, enabling Spring AI `ChatClient` to use A2A TaskStore for conversation memory.

## Features

- **JDBC TaskStore Implementation**: PostgreSQL-backed storage for A2A Tasks
- **Clean Architecture**: Separated repositories for tasks, history, and artifacts
- **Normalized Schema**: Separate tables for tasks, history (messages), and artifacts
- **Spring AI ChatMemory Adapter**: Use TaskStore as ChatMemory for ChatClient
- **Centralized SQL**: All SQL statements in one maintainable location
- **Test Builders**: Fluent API for creating test data
- **Spring Boot Auto-Configuration**: Automatic setup with sensible defaults

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.a2a.extras</groupId>
    <artifactId>task-store-spring-ai-jdbc</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Database Setup

Run the schema SQL from `src/main/resources/schema.sql`:

```sql
-- See schema.sql for full DDL
-- Creates: a2a_tasks, a2a_history, a2a_artifacts
```

### 3. Configure Application

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/a2a_db
    username: postgres
    password: secret

a2a:
  taskstore:
    enabled: true
    store-artifacts: true      # Set false to skip artifact storage
    store-metadata: true       # Set false to skip metadata storage
    chat-memory-enabled: true  # Auto-configure ChatMemory bean
    batch-size: 100            # Batch size for bulk inserts
```

### 4. Use with ChatClient

```java
@Service
public class ChatService {
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    
    public ChatService(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
    }
    
    public String chat(String conversationId, String message) {
        return chatClient.prompt()
            .user(message)
            .advisors(spec -> spec
                .param("chat_memory_conversation_id", conversationId)
                .param("chat_memory_retrieve_size", 10))
            .call()
            .content();
    }
}
```

## Architecture

### Core Components

```
Spring AI ChatClient
        ↓
ChatMemory (interface)
        ↓
TaskStoreChatMemoryAdapter
        ↓
JdbcTaskStore (orchestrator)
        ↓
TaskRepository → PostgreSQL (a2a_tasks)
HistoryRepository → PostgreSQL (a2a_history)
ArtifactRepository → PostgreSQL (a2a_artifacts)
```

### Package Structure

```
io.a2a.extras.taskstore
├── jdbc/
│   ├── JdbcTaskStore.java          # Main orchestrator
│   ├── JsonUtils.java              # JSON serialization
│   ├── JsonbAdapter.java           # PostgreSQL JSONB adapter
│   ├── JsonbAdapterFactory.java    # Adapter factory
│   └── SqlConstants.java           # Centralized SQL
├── repository/
│   ├── TaskRepository.java         # Task CRUD operations
│   ├── HistoryRepository.java      # Message/history operations
│   └── ArtifactRepository.java     # Artifact operations
├── cache/
│   └── CacheConfig.java            # Caffeine cache configuration
├── springai/
│   └── TaskStoreChatMemoryAdapter.java
└── autoconfigure/
    └── A2aTaskStoreAutoConfiguration.java
```

## Schema Design

### Tables

| Table | Purpose | Configurable |
|-------|---------|--------------|
| `a2a_tasks` | Stores task state, status, and metadata | No |
| `a2a_history` | Stores chat history (USER/AGENT messages) | No |
| `a2a_artifacts` | Stores task output artifacts | Yes (`store-artifacts`) |

### One Task = One Conversation

Each conversation maps to a single Task with:
- `Task.id` = unique task identifier
- `Task.contextId` = conversation/session identifier (can differ from task ID)
- Task history contains all messages for that conversation
- Task status tracks conversation state

## Configuration Options

| Property | Default | Description |
|----------|---------|-------------|
| `a2a.taskstore.enabled` | `true` | Enable/disable auto-configuration |
| `a2a.taskstore.store-artifacts` | `true` | Store artifacts in database |
| `a2a.taskstore.store-metadata` | `true` | Store metadata in database |
| `a2a.taskstore.batch-size` | `100` | Batch size for bulk inserts |
| `a2a.taskstore.chat-memory-enabled` | `true` | Register ChatMemory bean |

## Direct TaskStore Usage

```java
@Service
public class TaskService {
    private final TaskStore taskStore;
    
    public TaskService(TaskStore taskStore) {
        this.taskStore = taskStore;
    }
    
    public void createTask(String taskId) {
        Task task = new Task.Builder()
            .id(taskId)
            .contextId(taskId)
            .status(new TaskStatus(TaskState.SUBMITTED, null, OffsetDateTime.now()))
            .build();
        
        taskStore.save(task);
    }
    
    public Task getTask(String taskId) {
        return taskStore.get(taskId);
    }
    
    public void addMessage(String taskId, String text, boolean fromUser) {
        Task task = taskStore.get(taskId);
        
        Message message = new Message.Builder()
            .role(fromUser ? Message.Role.USER : Message.Role.AGENT)
            .parts(new TextPart(text))
            .build();
        
        List<Message> history = new ArrayList<>(task.getHistory());
        history.add(message);
        
        Task updated = new Task.Builder(task)
            .history(history)
            .build();
        
        taskStore.save(updated);
    }
}
```

## Testing

### Test Builders

Use the `TaskTestBuilder` for fluent test data creation:

```java
import static io.a2a.extras.taskstore.support.TaskTestBuilder.aTask;

Task task = aTask()
    .withId("test-task")
    .withContextId("session-123")
    .withStatus(TaskState.WORKING)
    .withMessage(Message.Role.USER, "Hello")
    .withMessage(Message.Role.AGENT, "Hi there!")
    .withArtifact("result-1", "Result", "Output content")
    .withMetadataEntry("source", "test")
    .build();
```

### Running Tests

```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=TaskCrudTest

# Run with coverage
./mvnw test jacoco:report
```

### Test Structure

```
src/test/java/io/a2a/extras/taskstore/
├── jdbc/
│   ├── BaseJdbcIntegrationTest.java      # Common test setup
│   ├── TaskCrudTest.java                 # Basic CRUD tests
│   ├── HistoryPersistenceTest.java       # History tests
│   ├── ArtifactPersistenceTest.java      # Artifact tests
│   ├── MetadataAndConfigurationTest.java # Config tests
│   └── DataIntegrityTest.java            # DB-level tests
└── support/
    └── TaskTestBuilder.java              # Test data builders
```

## Building

```bash
# Build and run tests
./mvnw clean install

# Build only
./mvnw clean package -DskipTests

# Run with specific profile
./mvnw test -Dspring.profiles.active=test
```

## Requirements

- Java 17+
- Spring Boot 3.3+
- PostgreSQL 12+ (or H2 for testing)
- Spring AI 1.1.2+
- a2a-java 0.3.3.Final+

## Design Principles

This implementation follows clean architecture principles:

1. **Single Responsibility**: Each repository handles one entity type
2. **Separation of Concerns**: SQL is centralized, business logic is separated
3. **Testability**: All components are easily testable in isolation
4. **Maintainability**: Small, focused classes are easier to understand and modify
5. **Flexibility**: Easy to swap implementations or extend functionality

## License

Same as a2a-java project
