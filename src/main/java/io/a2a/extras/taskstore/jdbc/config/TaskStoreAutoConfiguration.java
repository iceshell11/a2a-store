package io.a2a.extras.taskstore.jdbc.config;

import io.a2a.extras.taskstore.jdbc.store.JdbcTaskStore;
import io.a2a.server.tasks.TaskStore;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(TaskStore.class)
@ConditionalOnProperty(prefix = "a2a.taskstore.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TaskStoreProperties.class)
@ComponentScan(basePackages = "io.a2a.extras.taskstore.jdbc")
public class TaskStoreAutoConfiguration {

    private final TaskStoreProperties properties;

    public TaskStoreAutoConfiguration(TaskStoreProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        A2ATablePrefixNamingStrategy.setTablePrefix(properties.getTablePrefix());
    }

    @Bean
    @ConditionalOnMissingBean(TaskStore.class)
    public TaskStore taskStore(JdbcTaskStore jdbcTaskStore) {
        return jdbcTaskStore;
    }
}
