ALTER TABLE workspaces
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by varchar(255);

CREATE INDEX idx_workspaces_deleted_at
    ON workspaces(deleted_at);
