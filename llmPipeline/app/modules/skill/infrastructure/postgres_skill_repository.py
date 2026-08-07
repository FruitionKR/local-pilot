from __future__ import annotations

from typing import Any

from psycopg.types.json import Json

from app.modules.skill.application.ports import ManageSkillRepositoryPort, SkillRepositoryPort
from app.modules.skill.domain.entities import Skill, SkillVersion
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


SKILL_SELECT = """
    SELECT
        s.*,
        ev.id AS ev_id,
        ev.skill_id AS ev_skill_id,
        ev.version AS ev_version,
        ev.name AS ev_name,
        ev.description AS ev_description,
        ev.instructions_markdown AS ev_instructions_markdown,
        ev.capabilities AS ev_capabilities,
        ev.allowed_tools AS ev_allowed_tools,
        ev.lint_result AS ev_lint_result,
        ev.status AS ev_status,
        ev.created_by AS ev_created_by,
        ev.created_at AS ev_created_at,
        ev.published_at AS ev_published_at,
        lv.id AS lv_id,
        lv.skill_id AS lv_skill_id,
        lv.version AS lv_version,
        lv.name AS lv_name,
        lv.description AS lv_description,
        lv.instructions_markdown AS lv_instructions_markdown,
        lv.capabilities AS lv_capabilities,
        lv.allowed_tools AS lv_allowed_tools,
        lv.lint_result AS lv_lint_result,
        lv.status AS lv_status,
        lv.created_by AS lv_created_by,
        lv.created_at AS lv_created_at,
        lv.published_at AS lv_published_at
    FROM skills s
    LEFT JOIN skill_versions ev ON ev.id = s.enabled_version_id
    LEFT JOIN LATERAL (
        SELECT * FROM skill_versions candidate
        WHERE candidate.skill_id = s.id
        ORDER BY candidate.version DESC
        LIMIT 1
    ) lv ON TRUE
"""


class PostgresSkillRepository(SkillRepositoryPort, ManageSkillRepositoryPort):
    def list_accessible_enabled(self, workspace_id: str, user_id: str) -> list[Skill]:
        with database.connect() as conn:
            rows = conn.execute(
                SKILL_SELECT
                + """
                WHERE s.status = 'enabled'
                  AND s.enabled_version_id IS NOT NULL
                  AND (
                      (s.scope_type = 'personal' AND s.owner_user_id = %s)
                      OR (
                          s.scope_type = 'team'
                          AND s.workspace_id = %s
                          AND EXISTS (
                              SELECT 1 FROM workspace_members member
                              WHERE member.workspace_id = s.workspace_id AND member.user_id = %s
                          )
                      )
                  )
                ORDER BY s.scope_type, COALESCE(ev.name, ''), s.id
                """,
                (user_id, workspace_id, user_id),
            ).fetchall()
        return [_row_to_skill(row) for row in rows]

    def list_accessible(self, workspace_id: str, user_id: str) -> list[Skill]:
        with database.connect() as conn:
            rows = conn.execute(
                SKILL_SELECT
                + """
                WHERE (
                      (s.scope_type = 'personal' AND s.owner_user_id = %s)
                      OR (
                          s.scope_type = 'team'
                          AND s.workspace_id = %s
                          AND EXISTS (
                              SELECT 1 FROM workspace_members member
                              WHERE member.workspace_id = s.workspace_id AND member.user_id = %s
                          )
                      )
                  )
                ORDER BY s.scope_type, COALESCE(ev.name, lv.name, ''), s.id
                """,
                (user_id, workspace_id, user_id),
            ).fetchall()
        return [_row_to_skill(row) for row in rows]

    def get_accessible(self, workspace_id: str, user_id: str, skill_id: str) -> Skill | None:
        return self._get_with_access_filter(workspace_id, user_id, "s.id = %s", skill_id)

    def get_accessible_by_slug(self, workspace_id: str, user_id: str, slug: str) -> Skill | None:
        return self._get_with_access_filter(workspace_id, user_id, "s.slug = %s", slug)

    def get_manageable(self, workspace_id: str | None, user_id: str, skill_id: str) -> Skill | None:
        with database.connect() as conn:
            row = conn.execute(
                SKILL_SELECT
                + """
                WHERE s.id = %s
                  AND (
                      (s.scope_type = 'personal' AND s.owner_user_id = %s)
                      OR (
                          s.scope_type = 'team'
                          AND s.workspace_id = %s
                          AND EXISTS (
                              SELECT 1 FROM workspace_members member
                              WHERE member.workspace_id = s.workspace_id
                                AND member.user_id = %s
                                AND member.role = 'OWNER'
                          )
                      )
                  )
                """,
                (skill_id, user_id, workspace_id, user_id),
            ).fetchone()
        return _row_to_skill(row) if row else None

    def create_published(self, skill: Skill, version: SkillVersion) -> Skill:
        with database.connect() as conn:
            _require_manage_scope(conn, skill.workspace_id, version.created_by or "", skill.scope_type)
            _ensure_slug_available(conn, skill, skill.slug)
            conn.execute(
                """
                INSERT INTO skills (id, workspace_id, scope_type, owner_user_id, slug, status)
                VALUES (%s, %s, %s, %s, %s, 'enabled')
                """,
                (skill.id, skill.workspace_id, skill.scope_type, skill.owner_user_id, skill.slug),
            )
            _insert_version(conn, version)
            conn.execute(
                "UPDATE skills SET enabled_version_id = %s, updated_at = now() WHERE id = %s",
                (version.id, skill.id),
            )
        saved = self.get_manageable(skill.workspace_id, version.created_by or "", skill.id)
        if saved is None:
            raise ValueError("Published Skill could not be loaded.")
        return saved

    def save_published_version(self, skill: Skill, version: SkillVersion) -> Skill:
        with database.connect() as conn:
            if _lock_manageable(conn, skill.workspace_id, version.created_by or "", skill.id) is None:
                raise ValueError("Skill not found or not manageable.")
            _ensure_slug_available(conn, skill, version.name, exclude_skill_id=skill.id)
            _insert_version(conn, version)
            conn.execute(
                """
                UPDATE skills
                SET slug = %s, enabled_version_id = %s, updated_at = now()
                WHERE id = %s
                """,
                (version.name, version.id, skill.id),
            )
        saved = self.get_manageable(skill.workspace_id, version.created_by or "", skill.id)
        if saved is None:
            raise ValueError("Updated Skill could not be loaded.")
        return saved

    def set_enabled(self, workspace_id: str, user_id: str, skill_id: str, enabled: bool) -> Skill:
        with database.connect() as conn:
            manageable = _lock_manageable(conn, workspace_id, user_id, skill_id)
            if manageable is None:
                raise ValueError("Skill not found or not manageable.")
            row = conn.execute(
                """
                UPDATE skills
                SET status = %s, updated_at = now()
                WHERE id = %s AND (%s = false OR enabled_version_id IS NOT NULL)
                RETURNING id
                """,
                ("enabled" if enabled else "disabled", skill_id, enabled),
            ).fetchone()
            if row is None:
                raise ValueError("Publish a Skill version before enabling it.")
        saved = self.get_manageable(workspace_id, user_id, skill_id)
        if saved is None:
            raise ValueError("Updated Skill could not be loaded.")
        return saved

    def _get_with_access_filter(
        self,
        workspace_id: str | None,
        user_id: str,
        reference_clause: str,
        reference: str,
    ) -> Skill | None:
        with database.connect() as conn:
            row = conn.execute(
                SKILL_SELECT
                + f"""
                WHERE {reference_clause}
                  AND (
                      (s.scope_type = 'personal' AND s.owner_user_id = %s)
                      OR (
                          s.scope_type = 'team'
                          AND s.workspace_id = %s
                          AND EXISTS (
                              SELECT 1 FROM workspace_members member
                              WHERE member.workspace_id = s.workspace_id AND member.user_id = %s
                          )
                      )
                  )
                ORDER BY CASE WHEN s.scope_type = 'personal' THEN 0 ELSE 1 END, s.id
                """,
                (reference, user_id, workspace_id, user_id),
            ).fetchone()
        return _row_to_skill(row) if row else None


def _insert_version(conn: Any, version: SkillVersion) -> None:
    conn.execute(
        """
        INSERT INTO skill_versions (
            id, skill_id, version, name, description, instructions_markdown,
            capabilities, allowed_tools, lint_result, status, created_by, published_at
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                  CASE WHEN %s = 'published' THEN now() ELSE NULL END)
        """,
        (
            version.id,
            version.skill_id,
            version.version,
            version.name,
            version.description,
            version.instructions_markdown,
            list(version.capabilities),
            list(version.allowed_tools),
            Json(version.lint_result or {}),
            version.status,
            version.created_by,
            version.status,
        ),
    )


def _require_manage_scope(conn: Any, workspace_id: str | None, user_id: str, scope_type: str) -> None:
    if scope_type == "personal":
        return
    if not workspace_id:
        raise ValueError("Team Skill requires a workspace.")
    row = conn.execute(
        "SELECT role FROM workspace_members WHERE workspace_id = %s AND user_id = %s",
        (workspace_id, user_id),
    ).fetchone()
    if row is None or (scope_type == "team" and row["role"] != "OWNER"):
        raise ValueError("Workspace Skill management is not allowed.")


def _ensure_slug_available(
    conn: Any,
    skill: Skill,
    slug: str,
    *,
    exclude_skill_id: str | None = None,
) -> None:
    filters = ["s.slug = %s"]
    params: list[object] = [slug]
    if skill.scope_type == "personal":
        filters.extend(("s.scope_type = 'personal'", "s.owner_user_id = %s"))
        params.append(skill.owner_user_id)
    else:
        if skill.workspace_id is None:
            raise ValueError("Team Skill requires a workspace.")
        filters.extend(("s.scope_type = 'team'", "s.workspace_id = %s"))
        params.append(skill.workspace_id)
    if exclude_skill_id is not None:
        filters.append("s.id <> %s")
        params.append(exclude_skill_id)
    if conn.execute(
        "SELECT 1 FROM skills s WHERE " + " AND ".join(filters) + " LIMIT 1",
        tuple(params),
    ).fetchone():
        raise ValueError("Skill command already exists in this scope.")


def _lock_manageable(conn: Any, workspace_id: str | None, user_id: str, skill_id: str) -> dict[str, Any] | None:
    return conn.execute(
        """
        SELECT s.*
        FROM skills s
        WHERE s.id = %s
          AND (
              (s.scope_type = 'personal' AND s.owner_user_id = %s)
              OR (
                  s.scope_type = 'team'
                  AND s.workspace_id = %s
                  AND EXISTS (
                      SELECT 1 FROM workspace_members member
                      WHERE member.workspace_id = s.workspace_id
                        AND member.user_id = %s
                        AND member.role = 'OWNER'
                  )
              )
          )
        FOR UPDATE
        """,
        (skill_id, user_id, workspace_id, user_id),
    ).fetchone()


def _row_to_skill(row: dict[str, Any]) -> Skill:
    return Skill(
        id=row["id"],
        workspace_id=row["workspace_id"],
        scope_type=row["scope_type"],
        owner_user_id=row["owner_user_id"],
        slug=row["slug"],
        status=row["status"],
        enabled_version=_row_to_version(row, "ev"),
        latest_version=_row_to_version(row, "lv"),
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


def _row_to_version(row: dict[str, Any], prefix: str) -> SkillVersion | None:
    if row.get(f"{prefix}_id") is None:
        return None
    return SkillVersion(
        id=row[f"{prefix}_id"],
        skill_id=row[f"{prefix}_skill_id"],
        version=row[f"{prefix}_version"],
        name=row[f"{prefix}_name"],
        description=row[f"{prefix}_description"],
        instructions_markdown=row[f"{prefix}_instructions_markdown"],
        capabilities=tuple(row[f"{prefix}_capabilities"] or ()),
        allowed_tools=tuple(row[f"{prefix}_allowed_tools"] or ()),
        lint_result=row[f"{prefix}_lint_result"] or {},
        status=row[f"{prefix}_status"],
        created_by=row[f"{prefix}_created_by"],
        created_at=row[f"{prefix}_created_at"],
        published_at=row[f"{prefix}_published_at"],
    )
