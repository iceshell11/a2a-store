-- A2A TaskStore Schema for PostgreSQL (Variant 2: Normalized)
-- Supports optional artifacts and metadata tables via configuration

-- Conversations (Task wrapper - always created)
CREATE TABLE IF NOT EXISTS a2a_conversations (
    conversation_id VARCHAR(255) PRIMARY KEY,
    status_state VARCHAR(50) NOT NULL DEFAULT 'submitted',
    status_message JSONB,  -- TaskStatus.message as JSON
    status_timestamp TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    finalized_at TIMESTAMPTZ,
    
    CONSTRAINT chk_status CHECK (status_state IN (
        'submitted', 'working', 'input-required', 'auth-required',
        'completed', 'canceled', 'failed', 'rejected', 'unknown'
    ))
);

-- Messages (conversation history - always created)
CREATE TABLE IF NOT EXISTS a2a_messages (
    message_id SERIAL PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES a2a_conversations(conversation_id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,  -- 'USER' or 'AGENT'
    content_json JSONB NOT NULL,  -- Array of Part objects
    metadata_json JSONB,          -- Message metadata JSON object
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    sequence_num INTEGER NOT NULL,  -- Message order in conversation
    
    CONSTRAINT chk_role CHECK (role IN ('USER', 'AGENT'))
);

ALTER TABLE a2a_messages
    ADD COLUMN IF NOT EXISTS metadata_json JSONB;

-- Artifacts table (optional - controlled by configuration)
-- Uncomment or create conditionally based on a2a.taskstore.store-artifacts=true
CREATE TABLE IF NOT EXISTS a2a_artifacts (
    artifact_id SERIAL PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES a2a_conversations(conversation_id) ON DELETE CASCADE,
    artifact_json JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- Metadata table (optional - controlled by configuration)
-- Uncomment or create conditionally based on a2a.taskstore.store-metadata=true
CREATE TABLE IF NOT EXISTS a2a_metadata (
    metadata_id SERIAL PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES a2a_conversations(conversation_id) ON DELETE CASCADE,
    key VARCHAR(255) NOT NULL,
    value_json JSONB,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE(conversation_id, key)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_conversations_status ON a2a_conversations(status_state);
CREATE INDEX IF NOT EXISTS idx_conversations_finalized ON a2a_conversations(finalized_at) WHERE finalized_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON a2a_messages(conversation_id, sequence_num);
CREATE INDEX IF NOT EXISTS idx_artifacts_conversation ON a2a_artifacts(conversation_id);
CREATE INDEX IF NOT EXISTS idx_metadata_conversation ON a2a_metadata(conversation_id, key);

-- Trigger to auto-update updated_at on conversations
CREATE OR REPLACE FUNCTION update_a2a_conversations_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_a2a_conversations_updated_at ON a2a_conversations;
CREATE TRIGGER trigger_a2a_conversations_updated_at
    BEFORE UPDATE ON a2a_conversations
    FOR EACH ROW
    EXECUTE FUNCTION update_a2a_conversations_updated_at();

-- Trigger to auto-update updated_at on metadata
CREATE OR REPLACE FUNCTION update_a2a_metadata_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_a2a_metadata_updated_at ON a2a_metadata;
CREATE TRIGGER trigger_a2a_metadata_updated_at
    BEFORE UPDATE ON a2a_metadata
    FOR EACH ROW
    EXECUTE FUNCTION update_a2a_metadata_updated_at();
