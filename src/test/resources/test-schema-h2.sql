-- A2A TaskStore Schema for H2 (Aligned with Task Class)
-- Tables match the A2A protocol Task structure: Task -> history (messages), artifacts

CREATE TABLE IF NOT EXISTS a2a_tasks (
    task_id VARCHAR(255) PRIMARY KEY,
    context_id VARCHAR(255),
    status_state VARCHAR(50) NOT NULL DEFAULT 'submitted',
    status_message_json JSON,
    status_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    metadata_json JSON,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    finalized_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_status CHECK (status_state IN (
        'submitted', 'working', 'input-required', 'auth-required',
        'completed', 'canceled', 'failed', 'rejected', 'unknown'
    ))
);

CREATE TABLE IF NOT EXISTS a2a_history (
    task_id VARCHAR(255) NOT NULL REFERENCES a2a_tasks(task_id) ON DELETE CASCADE,
    message_id VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content_json JSON NOT NULL,  -- Array of Part objects
    metadata_json JSON,          -- Message metadata JSON object
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    sequence_num INTEGER NOT NULL,

    PRIMARY KEY (message_id, task_id),
    CONSTRAINT chk_role CHECK (role IN ('USER', 'AGENT'))
);

CREATE TABLE IF NOT EXISTS a2a_artifacts (
    artifact_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL REFERENCES a2a_tasks(task_id) ON DELETE CASCADE,
    name VARCHAR(500),
    description TEXT,
    content_json JSON NOT NULL,
    metadata_json JSON,
    extensions_json JSON,
    sequence_num INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    PRIMARY KEY (artifact_id, task_id)
);

CREATE INDEX IF NOT EXISTS idx_tasks_status ON a2a_tasks(status_state);
CREATE INDEX IF NOT EXISTS idx_tasks_finalized ON a2a_tasks(finalized_at);
CREATE INDEX IF NOT EXISTS idx_history_task ON a2a_history(task_id, sequence_num);
CREATE INDEX IF NOT EXISTS idx_artifacts_task ON a2a_artifacts(task_id, sequence_num);
