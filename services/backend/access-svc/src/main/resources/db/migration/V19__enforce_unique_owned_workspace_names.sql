-- 소유자가 여러 명일 수 있으므로 각 소유자의 활성 워크스페이스 이름을 예약한다.
-- 생성·이름 변경·복구·OWNER 추가 모두 같은 unique index를 통과한다.
CREATE TABLE workspace_name_reservations (
    workspace_id varchar(255) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id varchar(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    normalized_name text NOT NULL,
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT uq_workspaces_owner_active_name UNIQUE (user_id, normalized_name)
);

INSERT INTO workspace_name_reservations (workspace_id, user_id, normalized_name)
SELECT w.id, m.user_id, lower(normalize(btrim(w.name), NFC))
FROM workspaces w JOIN workspace_members m ON m.workspace_id = w.id
WHERE w.deleted_at IS NULL AND m.role = 'OWNER';

CREATE FUNCTION sync_workspace_name_reservations(target_id varchar) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    current_workspace workspaces%ROWTYPE;
BEGIN
    -- 이름 변경과 소유권 변경이 동시에 일어나도 같은 workspace를 기준으로 직렬화한다.
    SELECT * INTO current_workspace FROM workspaces WHERE id = target_id FOR UPDATE;
    DELETE FROM workspace_name_reservations WHERE workspace_id = target_id;
    IF current_workspace.id IS NOT NULL AND current_workspace.deleted_at IS NULL THEN
        INSERT INTO workspace_name_reservations (workspace_id, user_id, normalized_name)
        SELECT target_id, user_id, lower(normalize(btrim(current_workspace.name), NFC))
        FROM workspace_members WHERE workspace_id = target_id AND role = 'OWNER';
    END IF;
END;
$$;

CREATE FUNCTION workspace_names_changed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM sync_workspace_name_reservations(NEW.id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_names_changed
AFTER UPDATE OF name, deleted_at ON workspaces
FOR EACH ROW EXECUTE FUNCTION workspace_names_changed();

CREATE FUNCTION workspace_owner_names_changed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM sync_workspace_name_reservations(OLD.workspace_id);
        RETURN OLD;
    END IF;
    PERFORM sync_workspace_name_reservations(NEW.workspace_id);
    IF TG_OP = 'UPDATE' AND OLD.workspace_id <> NEW.workspace_id THEN
        PERFORM sync_workspace_name_reservations(OLD.workspace_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_owner_names_changed
AFTER INSERT OR UPDATE OR DELETE ON workspace_members
FOR EACH ROW EXECUTE FUNCTION workspace_owner_names_changed();
