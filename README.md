# A2A TaskStore JDBC + Spring AI ChatMemory Adapter

A PostgreSQL JDBC implementation of the A2A (Agent-to-Agent) protocol `TaskStore` interface with a Spring AI `ChatMemory` adapter, enabling Spring AI `ChatClient` to use A2A TaskStore for conversation memory.

## Features

- **JDBC TaskStore Implementation**: PostgreSQL-backed storage for A2A Tasks
- **Normalized Schema**: Separate tables for conversations, messages, artifacts, and metadata
- **Spring AI ChatMemory Adapter**: Use TaskStore as ChatMemory for ChatClient
- **Optional Storage**: Disable artifacts and metadata storage via configuration
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
-- Creates: a2a_conversations, a2a_messages, a2a_artifacts, a2a_metadata
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

## Schema Design

### Tables

| Table | Purpose | Configurable |
|-------|---------|--------------|
| `a2a_conversations` | Stores conversation state and status | No |
| `a2a_messages` | Stores chat history (USER/AGENT messages) | No |
| `a2a_artifacts` | Stores task output artifacts | Yes (`store-artifacts`) |
| `a2a_metadata` | Stores key-value metadata | Yes (`store-metadata`) |

### One Task = One Conversation

Each conversation maps to a single Task with:
- `Task.id` = `Task.contextId` = conversation ID
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
    
    public void createTask(String conversationId) {
        Task task = new Task.Builder()
            .id(conversationId)
            .contextId(conversationId)
            .status(new TaskStatus(TaskState.SUBMITTED, null, OffsetDateTime.now()))
            .build();
        
        taskStore.save(task);
    }
    
    public Task getTask(String conversationId) {
        return taskStore.get(conversationId);
    }
    
    public void addMessage(String conversationId, String text, boolean fromUser) {
        Task task = taskStore.get(conversationId);
        
        Message message = new Message.Builder()
            .role(fromUser ? Message.Role.USER : Message.Role.AGENT)
            .parts(new TextPart(text))
            .contextId(conversationId)
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

## Building

```bash
mvn clean install
```

## Requirements

- Java 17+
- Spring Boot 3.3+
- PostgreSQL 12+
- Spring AI 1.1.2+
- a2a-java 0.3.3.Final+

## Architecture

```
Spring AI ChatClient
        ↓
ChatMemory (interface)
        ↓
TaskStoreChatMemoryAdapter
        ↓
JdbcTaskStore
        ↓
PostgreSQL (a2a_* tables)
```

The adapter bridges Spring AI's message model with A2A's Task/Message model:
- Spring AI `UserMessage` ↔ A2A `Message(role=USER)`
- Spring AI `AssistantMessage` ↔ A2A `Message(role=AGENT)`
- Spring AI `SystemMessage` ↔ A2A `Message(role=USER)`

## License

Same as a2a-java project
