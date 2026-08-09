ALTER TABLE skills ADD COLUMN slug character varying(63);
UPDATE skills SET slug = command WHERE slug IS NULL;
ALTER TABLE skills ALTER COLUMN slug SET NOT NULL;
ALTER TABLE skills ADD COLUMN status character varying(16) NOT NULL DEFAULT 'enabled';
ALTER TABLE skills ADD COLUMN enabled_version_id character varying(255);
ALTER TABLE skills ALTER COLUMN command DROP NOT NULL;
ALTER TABLE skills ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE skills ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE skills ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE skill_versions ADD COLUMN lint_result jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE skill_versions ADD COLUMN status character varying(16) NOT NULL DEFAULT 'published';
ALTER TABLE skill_versions ADD COLUMN published_at timestamp with time zone;
ALTER TABLE skill_versions ALTER COLUMN definition_hash SET DEFAULT '';
ALTER TABLE skill_versions ALTER COLUMN created_at SET DEFAULT now();
UPDATE skill_versions SET published_at = created_at WHERE published_at IS NULL;

UPDATE skills skill
SET enabled_version_id = latest.id
FROM (
    SELECT DISTINCT ON (skill_id) id, skill_id
    FROM skill_versions
    ORDER BY skill_id, version DESC
) latest
WHERE latest.skill_id = skill.id;

ALTER TABLE skills ADD CONSTRAINT skills_enabled_version_fk
    FOREIGN KEY (enabled_version_id) REFERENCES skill_versions(id);

CREATE UNIQUE INDEX uq_skills_personal_slug
    ON skills(owner_user_id, slug) WHERE scope_type = 'personal';
CREATE UNIQUE INDEX uq_skills_team_slug
    ON skills(workspace_id, slug) WHERE scope_type = 'team';
