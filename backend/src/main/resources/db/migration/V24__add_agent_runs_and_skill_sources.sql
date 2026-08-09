CREATE TABLE agent_runs (
    id varchar(255) PRIMARY KEY,
    workspace_id varchar(255) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id varchar(255) NOT NULL REFERENCES users(id),
    action varchar(32) NOT NULL,
    skill_version_id varchar(255) REFERENCES skill_versions(id),
    status varchar(32) NOT NULL,
    request_summary varchar(1000) NOT NULL,
    current_plan_id varchar(255),
    error_code varchar(100),
    tool_call_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE agent_plans (
    id varchar(255) PRIMARY KEY,
    run_id varchar(255) NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    version integer NOT NULL,
    summary varchar(2000) NOT NULL,
    operation_hash varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, version)
);

ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_current_plan_fk
    FOREIGN KEY (current_plan_id) REFERENCES agent_plans(id);

CREATE TABLE agent_plan_operations (
    id varchar(255) PRIMARY KEY,
    plan_id varchar(255) NOT NULL REFERENCES agent_plans(id) ON DELETE CASCADE,
    sequence integer NOT NULL,
    tool_name varchar(64) NOT NULL,
    target_type varchar(32) NOT NULL,
    target_id varchar(255),
    base_version integer,
    source_parent_id varchar(255),
    destination_parent_id varchar(255),
    arguments jsonb NOT NULL DEFAULT '{}'::jsonb,
    reason varchar(2000) NOT NULL,
    depends_on text[] NOT NULL DEFAULT '{}',
    status varchar(32) NOT NULL,
    error_code varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (plan_id, sequence)
);

CREATE TABLE agent_approvals (
    id varchar(255) PRIMARY KEY,
    run_id varchar(255) NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    plan_id varchar(255) NOT NULL REFERENCES agent_plans(id) ON DELETE CASCADE,
    plan_version integer NOT NULL,
    operation_hash varchar(64) NOT NULL,
    user_id varchar(255) NOT NULL REFERENCES users(id),
    decision varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE agent_jobs (
    id varchar(255) PRIMARY KEY,
    run_id varchar(255) NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    job_type varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(255),
    lease_token varchar(255),
    leased_until timestamptz,
    heartbeat_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE agent_tool_executions (
    id varchar(255) PRIMARY KEY,
    run_id varchar(255) NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    plan_id varchar(255) NOT NULL REFERENCES agent_plans(id) ON DELETE CASCADE,
    operation_id varchar(255) NOT NULL REFERENCES agent_plan_operations(id) ON DELETE CASCADE,
    tool_name varchar(64) NOT NULL,
    idempotency_key varchar(255) NOT NULL UNIQUE,
    attempt integer NOT NULL,
    status varchar(32) NOT NULL,
    response_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE skill_version_sources (
    skill_version_id varchar(255) NOT NULL REFERENCES skill_versions(id) ON DELETE CASCADE,
    source_agent_run_id varchar(255) NOT NULL REFERENCES agent_runs(id) ON DELETE RESTRICT,
    source_type varchar(32) NOT NULL DEFAULT 'completed_agent_run',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (skill_version_id, source_agent_run_id)
);

CREATE INDEX idx_agent_runs_actor ON agent_runs(workspace_id, user_id, created_at DESC);
CREATE INDEX idx_agent_jobs_claim ON agent_jobs(status, available_at, created_at);
