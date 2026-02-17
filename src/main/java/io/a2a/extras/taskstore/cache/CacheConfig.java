package io.a2a.extras.taskstore.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(prefix = "a2a.taskstore.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

    public static final String TASK_CACHE = "a2a-tasks";
    private static final Set<TaskState> FINAL_STATES = EnumSet.of(
        TaskState.COMPLETED, TaskState.CANCELED, TaskState.FAILED, TaskState.REJECTED
    );

    private final A2aTaskStoreProperties properties;

    public CacheConfig(A2aTaskStoreProperties properties) {
        this.properties = properties;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(TASK_CACHE);
        cacheManager.setCaffeine(caffeineConfig());
        cacheManager.setAllowNullValues(false);
        return cacheManager;
    }

    private Caffeine<Object, Object> caffeineConfig() {
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
            .maximumSize(properties.getCache().getMaxSize())
            .expireAfter(new TaskExpiry());
        
        if (properties.getCache().isRecordStats()) {
            caffeine.recordStats();
        }
        
        return caffeine;
    }

    private class TaskExpiry implements Expiry<Object, Object> {
        @Override
        public long expireAfterCreate(Object key, Object value, long currentTime) {
            if (value instanceof Task task) {
                boolean isFinalized = FINAL_STATES.contains(task.getStatus().state());
                int ttlMinutes = isFinalized 
                    ? properties.getCache().getFinalizedTtlMinutes() 
                    : properties.getCache().getTtlMinutes();
                return TimeUnit.MINUTES.toNanos(ttlMinutes);
            }
            return TimeUnit.MINUTES.toNanos(properties.getCache().getTtlMinutes());
        }

        @Override
        public long expireAfterUpdate(Object key, Object value, long currentTime, long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(Object key, Object value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
