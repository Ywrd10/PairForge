-- The API owns schema changes. No application credentials belong in migrations.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL,
    password_hash varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_email_normalized CHECK (email = lower(btrim(email)) AND length(email) > 0),
    CONSTRAINT users_password_hash_nonempty CHECK (length(password_hash) > 0)
);

CREATE TABLE rooms (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    language varchar(16) NOT NULL CHECK (language IN ('JAVA', 'PYTHON')),
    invitation_token_hash varchar(64) NOT NULL CHECK (invitation_token_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT rooms_timestamp_order CHECK (updated_at >= created_at)
);
CREATE INDEX rooms_owner_idx ON rooms(owner_id);

CREATE TABLE room_members (
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    joined_at timestamptz NOT NULL,
    PRIMARY KEY (room_id, user_id)
);
CREATE INDEX room_members_user_idx ON room_members(user_id, room_id);

CREATE TABLE executions (
    id uuid PRIMARY KEY,
    room_id uuid NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
    submitted_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    language varchar(16) NOT NULL CHECK (language IN ('JAVA', 'PYTHON')),
    source_code text NOT NULL CHECK (octet_length(source_code) <= 65536),
    status varchar(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')),
    stdout text NOT NULL DEFAULT '',
    stderr text NOT NULL DEFAULT '',
    exit_code integer,
    duration_ms bigint CHECK (duration_ms >= 0),
    created_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    deadline_at timestamptz,
    state_revision bigint NOT NULL DEFAULT 0 CHECK (state_revision >= 0),
    failure_reason varchar(32) CHECK (failure_reason IN (
        'DISPATCH_FAILED', 'DISPATCH_UNCONFIRMED', 'COMPILATION_ERROR', 'RUNTIME_ERROR',
        'MEMORY_LIMIT', 'OUTPUT_LIMIT', 'INFRASTRUCTURE_INTERRUPTION')),
    output_truncated boolean NOT NULL DEFAULT false,
    CONSTRAINT executions_output_size CHECK (octet_length(stdout) + octet_length(stderr) <= 65536),
    CONSTRAINT executions_started_order CHECK (started_at >= created_at),
    CONSTRAINT executions_completed_order CHECK (completed_at >= coalesce(started_at, created_at)),
    CONSTRAINT executions_deadline_order CHECK (deadline_at >= coalesce(started_at, created_at))
);
CREATE INDEX executions_room_history_idx ON executions(room_id, created_at DESC, id DESC);
CREATE INDEX executions_status_deadline_idx ON executions(status, deadline_at);
CREATE INDEX executions_submitted_by_idx ON executions(submitted_by);
