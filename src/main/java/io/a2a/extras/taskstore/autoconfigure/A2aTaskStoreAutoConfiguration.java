package io.a2a.extras.taskstore.autoconfigure;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.extras.taskstore.cache.CacheConfig;
import io.a2a.extras.taskstore.jdbc.JdbcTaskStore;
import io.a2a.extras.taskstore.springai.TaskStoreChatMemoryAdapter;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@ConditionalOnClass({TaskStore.class, JdbcTemplate.class})
@ConditionalOnProperty(prefix = "a2a.taskstore", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(A2aTaskStoreProperties.class)
@Import(CacheConfig.class)
public class A2aTaskStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JdbcTaskStore jdbcTaskStore(JdbcTemplate jdbcTemplate, A2aTaskStoreProperties properties) {
        return new JdbcTaskStore(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(TaskStore.class)
    public TaskStore taskStore(JdbcTaskStore jdbcTaskStore) {
        return jdbcTaskStore;
    }

    @Bean
    @ConditionalOnMissingBean(TaskStateProvider.class)
    public TaskStateProvider taskStateProvider(JdbcTaskStore jdbcTaskStore) {
        return jdbcTaskStore;
    }

    @Bean
    @ConditionalOnClass(ChatMemory.class)
    @ConditionalOnMissingBean(ChatMemory.class)
    @ConditionalOnProperty(prefix = "a2a.taskstore", name = "chat-memory-enabled", havingValue = "true", matchIfMissing = true)
    public TaskStoreChatMemoryAdapter taskStoreChatMemoryAdapter(
            TaskStore taskStore,
            A2aTaskStoreProperties properties) {
        return new TaskStoreChatMemoryAdapter(taskStore, properties);
    }
}
