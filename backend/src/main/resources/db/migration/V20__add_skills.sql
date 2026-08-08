CREATE TABLE skills (
    id character varying(255) PRIMARY KEY,
    workspace_id character varying(255) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    scope_type character varying(16) NOT NULL CHECK (scope_type IN ('personal', 'team')),
    owner_user_id character varying(255) REFERENCES users(id),
    command character varying(63) NOT NULL,
    auto_routing_enabled boolean NOT NULL DEFAULT true,
    deleted_at timestamp with time zone,
    deleted_by character varying(255) REFERENCES users(id),
    created_by character varying(255) NOT NULL REFERENCES users(id),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CHECK ((scope_type = 'personal' AND owner_user_id IS NOT NULL)
        OR (scope_type = 'team' AND owner_user_id IS NULL))
);

CREATE TABLE skill_versions (
    id character varying(255) PRIMARY KEY,
    skill_id character varying(255) NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    version integer NOT NULL CHECK (version > 0),
    name character varying(63) NOT NULL,
    description text,
    instructions_markdown text NOT NULL,
    capabilities jsonb NOT NULL DEFAULT '[]'::jsonb,
    allowed_tools jsonb NOT NULL DEFAULT '[]'::jsonb,
    reference_documents jsonb NOT NULL DEFAULT '[]'::jsonb,
    safety_result jsonb NOT NULL DEFAULT '{}'::jsonb,
    definition_hash character varying(64) NOT NULL,
    created_by character varying(255) NOT NULL REFERENCES users(id),
    created_at timestamp with time zone NOT NULL,
    UNIQUE (skill_id, version)
);

CREATE INDEX idx_skills_workspace_visible
    ON skills(workspace_id, scope_type, command) WHERE deleted_at IS NULL;
CREATE INDEX idx_skills_owner_visible
    ON skills(workspace_id, owner_user_id, command) WHERE deleted_at IS NULL;
CREATE INDEX idx_skill_versions_latest ON skill_versions(skill_id, version DESC);
