UPDATE workspace_members
SET role = UPPER(role);

ALTER TABLE workspace_members
    ADD CONSTRAINT workspace_members_role_check
        CHECK (role IN ('OWNER', 'MEMBER'));
