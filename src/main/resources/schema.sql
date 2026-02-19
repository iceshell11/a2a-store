-- A2A TaskStore Schema for PostgreSQL (Aligned with Task Class)
-- Tables match the A2A protocol Task structure: Task -> history (messages), artifacts

-- Tasks table (core task information)
CREATE TABLE IF NOT EXISTS a2a_tasks (
    task_id VARCHAR(255) PRIMARY KEY,
    context_id VARCHAR(255),
    status_state VARCHAR(50) NOT NULL DEFAULT 'submitted',
    status_message_json JSONB,  -- TaskStatus.message as JSON
    status_timestamp TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    metadata_json JSONB,   -- Task metadata as JSON object
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    finalized_at TIMESTAMPTZ,

    CONSTRAINT chk_status CHECK (status_state IN (
        'submitted', 'working', 'input-required', 'auth-required',
        'completed', 'canceled', 'failed', 'rejected', 'unknown'
    ))
);

-- History table (task history/messages - aligned with Task.history field)
CREATE TABLE IF NOT EXISTS a2a_history (
    task_id VARCHAR(255) NOT NULL REFERENCES a2a_tasks(task_id) ON DELETE CASCADE,
    message_id VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,  -- 'USER' or 'AGENT'
    content_json JSONB NOT NULL,  -- Array of Part objects
    metadata_json JSONB,          -- Message metadata JSON object
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    sequence_num INTEGER NOT NULL,  -- Message order in history
    
    PRIMARY KEY (message_id, task_id),
    CONSTRAINT chk_role CHECK (role IN ('USER', 'AGENT'))
);

-- Artifacts table (optional - controlled by configuration)
CREATE TABLE IF NOT EXISTS a2a_artifacts (
    artifact_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL REFERENCES a2a_tasks(task_id) ON DELETE CASCADE,
    name VARCHAR(500),
    description TEXT,
    content_json JSONB NOT NULL,
    metadata_json JSONB,
    extensions_json JSONB,
    sequence_num INTEGER NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    
    PRIMARY KEY (artifact_id, task_id)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_tasks_status ON a2a_tasks(status_state);
CREATE INDEX IF NOT EXISTS idx_tasks_finalized ON a2a_tasks(finalized_at) WHERE finalized_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_history_task ON a2a_history(task_id, sequence_num);
CREATE INDEX IF NOT EXISTS idx_artifacts_task ON a2a_artifacts(task_id, sequence_num);

-- Trigger to auto-update updated_at on tasks
CREATE OR REPLACE FUNCTION update_a2a_tasks_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_a2a_tasks_updated_at ON a2a_tasks;
CREATE TRIGGER trigger_a2a_tasks_updated_at
    BEFORE UPDATE ON a2a_tasks
    FOR EACH ROW
    EXECUTE FUNCTION update_a2a_tasks_updated_at();

-- Trigger to auto-update updated_at on history
DROP TRIGGER IF EXISTS trigger_a2a_history_updated_at ON a2a_history;
CREATE TRIGGER trigger_a2a_history_updated_at
    BEFORE UPDATE ON a2a_history
    FOR EACH ROW
    EXECUTE FUNCTION update_a2a_tasks_updated_at();

-- Trigger to auto-update updated_at on artifacts
DROP TRIGGER IF EXISTS trigger_a2a_artifacts_updated_at ON a2a_artifacts;
CREATE TRIGGER trigger_a2a_artifacts_updated_at
    BEFORE UPDATE ON a2a_artifacts
    FOR EACH ROW
    EXECUTE FUNCTION update_a2a_tasks_updated_at();
