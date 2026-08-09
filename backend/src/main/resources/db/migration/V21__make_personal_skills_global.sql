ALTER TABLE skills ALTER COLUMN workspace_id DROP NOT NULL;

UPDATE skills SET workspace_id = NULL WHERE scope_type = 'personal';

ALTER TABLE skills DROP CONSTRAINT skills_check;
ALTER TABLE skills ADD CONSTRAINT skills_scope_owner_workspace_check CHECK (
    (scope_type = 'personal' AND owner_user_id IS NOT NULL AND workspace_id IS NULL)
    OR (scope_type = 'team' AND owner_user_id IS NULL AND workspace_id IS NOT NULL)
);

DROP INDEX idx_skills_owner_visible;
CREATE INDEX idx_skills_owner_visible
    ON skills(owner_user_id, command) WHERE scope_type = 'personal' AND deleted_at IS NULL;
