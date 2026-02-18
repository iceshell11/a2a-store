-- A2A TaskStore Schema for H2 (compatible with PostgreSQL schema)

CREATE TABLE IF NOT EXISTS a2a_conversations (
    conversation_id VARCHAR(255) PRIMARY KEY,
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

CREATE TABLE IF NOT EXISTS a2a_messages (
    message_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL REFERENCES a2a_conversations(conversation_id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content_json JSON NOT NULL,  -- Array of Part objects
    metadata_json JSON,          -- Message metadata JSON object
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    sequence_num INTEGER NOT NULL,

    PRIMARY KEY (message_id, conversation_id),
    CONSTRAINT chk_role CHECK (role IN ('USER', 'AGENT'))
);

CREATE TABLE IF NOT EXISTS a2a_artifacts (
    artifact_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL REFERENCES a2a_conversations(conversation_id) ON DELETE CASCADE,
    name VARCHAR(500),
    description TEXT,
    content_json JSON NOT NULL,
    metadata_json JSON,
    extensions_json JSON,
    sequence_num INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    PRIMARY KEY (artifact_id, conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_conversations_status ON a2a_conversations(status_state);
CREATE INDEX IF NOT EXISTS idx_conversations_finalized ON a2a_conversations(finalized_at);
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON a2a_messages(conversation_id, sequence_num);
CREATE INDEX IF NOT EXISTS idx_artifacts_conversation ON a2a_artifacts(conversation_id, sequence_num);
