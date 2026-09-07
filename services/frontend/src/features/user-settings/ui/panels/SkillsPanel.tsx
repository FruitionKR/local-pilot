"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  authorSkill,
  disableSkill,
  enableSkill,
  fetchSkills,
  publishSkill,
  updateSkill,
  type SkillAuthoringResult,
  type SkillResponse
} from "@/entities/skill";
import { getSelectedWorkspaceId } from "@/shared/lib/auth";
import { getErrorMessage } from "@/shared/lib/errors";
import { menuSearchIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import modalStyles from "../SettingsModal.module.css";
import styles from "./SkillsPanel.module.css";

const SKILLS_QUERY_KEY = ["skills"] as const;

// 저장범위·상태 필터 순환 순서
const SCOPE_FILTERS = ["all", "personal", "team"] as const;
const STATE_FILTERS = ["all", "enabled", "disabled"] as const;
type ScopeFilter = (typeof SCOPE_FILTERS)[number];
type StateFilter = (typeof STATE_FILTERS)[number];

const SCOPE_LABELS: Record<ScopeFilter, string> = { all: "전체", personal: "개인", team: "팀" };
const STATE_LABELS: Record<StateFilter, string> = { all: "전체", enabled: "사용 중", disabled: "사용 안 함" };

/** enabled_version이 있으면 Agent 실행 대상에 포함된 상태다. */
function isSkillEnabled(skill: SkillResponse): boolean {
  return skill.enabled_version != null;
}

function skillLabel(skill: SkillResponse): { command: string; description: string } {
  const version = skill.enabled_version ?? skill.latest_version;
  return {
    command: `/${skill.slug}`,
    description: version?.description ?? ""
  };
}

/** 인라인 편집 폼 상태 */
interface EditDraft {
  description: string;
  instructionsMarkdown: string;
}

/** 스킬 패널 (Figma 981:10091). 목록·토글·작성(author/publish)·수정(PATCH)·필터·검색 배선. */
export function SkillsPanel() {
  const queryClient = useQueryClient();
  const workspaceId = getSelectedWorkspaceId();
  const [toggleError, setToggleError] = useState<string | null>(null);

  // 필터·검색 상태 (전부 클라이언트 필터링)
  const [scopeFilter, setScopeFilter] = useState<ScopeFilter>("all");
  const [stateFilter, setStateFilter] = useState<StateFilter>("all");
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchText, setSearchText] = useState("");

  // 새 스킬 작성 폼 상태
  const [createOpen, setCreateOpen] = useState(false);
  const [instruction, setInstruction] = useState("");
  const [draft, setDraft] = useState<SkillAuthoringResult | null>(null);

  // 행 인라인 편집 상태
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState<EditDraft>({ description: "", instructionsMarkdown: "" });
  const [editError, setEditError] = useState<string | null>(null);

  const { data: skills, isLoading, error } = useQuery({
    queryKey: SKILLS_QUERY_KEY,
    queryFn: () => fetchSkills(workspaceId ?? ""),
    enabled: workspaceId != null
  });

  const filteredSkills = useMemo(() => {
    const keyword = searchText.trim().toLowerCase();
    return (skills ?? []).filter((skill) => {
      if (scopeFilter === "personal" && skill.scope_type !== "personal") return false;
      if (scopeFilter === "team" && skill.scope_type === "personal") return false;
      if (stateFilter === "enabled" && !isSkillEnabled(skill)) return false;
      if (stateFilter === "disabled" && isSkillEnabled(skill)) return false;
      if (keyword.length > 0) {
        const { description } = skillLabel(skill);
        const haystack = `${skill.slug} ${description}`.toLowerCase();
        if (!haystack.includes(keyword)) return false;
      }
      return true;
    });
  }, [skills, scopeFilter, stateFilter, searchText]);

  const toggleMutation = useMutation({
    mutationFn: ({ skill }: { skill: SkillResponse }) =>
      isSkillEnabled(skill)
        ? disableSkill(skill.workspace_id, skill.id)
        : enableSkill(skill.workspace_id, skill.id),
    onSuccess: (updated) => {
      setToggleError(null);
      // 서버 응답으로 해당 행만 교체해 목록 재조회를 생략한다.
      queryClient.setQueryData<SkillResponse[]>(SKILLS_QUERY_KEY, (current) =>
        current?.map((skill) => (skill.id === updated.id ? updated : skill))
      );
    },
    onError: (mutationError: unknown) => {
      setToggleError(getErrorMessage(mutationError, "스킬 사용 상태를 변경하지 못했습니다."));
    }
  });

  const authorMutation = useMutation({
    mutationFn: () => authorSkill(workspaceId ?? "", { instruction }),
    onSuccess: (result) => setDraft(result)
  });

  const publishMutation = useMutation({
    mutationFn: () => {
      if (draft == null) throw new Error("게시할 초안이 없습니다.");
      return publishSkill(workspaceId ?? "", {
        name: draft.name,
        description: draft.description,
        instructions_markdown: draft.instructions_markdown,
        scope_type: draft.scope_type,
        allowed_tools: draft.allowed_tools,
        capabilities: draft.capabilities
      });
    },
    onSuccess: () => {
      // 게시 후 목록을 재조회하고 작성 폼을 초기화한다.
      queryClient.invalidateQueries({ queryKey: SKILLS_QUERY_KEY });
      closeCreateForm();
    }
  });

  const updateMutation = useMutation({
    mutationFn: ({ skill }: { skill: SkillResponse }) =>
      updateSkill(skill.workspace_id, skill.id, {
        description: editDraft.description,
        instructions_markdown: editDraft.instructionsMarkdown
      }),
    onSuccess: (result, { skill }) => {
      setEditError(null);
      setEditingId(null);
      // PATCH 응답 필드로 캐시 행의 버전 정보를 교체한다.
      queryClient.setQueryData<SkillResponse[]>(SKILLS_QUERY_KEY, (current) =>
        current?.map((item) => {
          if (item.id !== skill.id) return item;
          const patchVersion = (version: SkillResponse["latest_version"]) =>
            version == null
              ? version
              : {
                  ...version,
                  name: result.name,
                  description: result.description,
                  instructions_markdown: result.instructions_markdown
                };
          return {
            ...item,
            enabled_version: patchVersion(item.enabled_version),
            latest_version: patchVersion(item.latest_version)
          };
        })
      );
    },
    onError: (mutationError: unknown) => {
      setEditError(getErrorMessage(mutationError, "스킬 정의를 수정하지 못했습니다."));
    }
  });

  function closeCreateForm() {
    setCreateOpen(false);
    setInstruction("");
    setDraft(null);
    authorMutation.reset();
    publishMutation.reset();
  }

  function cycleScopeFilter() {
    setScopeFilter((current) => SCOPE_FILTERS[(SCOPE_FILTERS.indexOf(current) + 1) % SCOPE_FILTERS.length]);
  }

  function cycleStateFilter() {
    setStateFilter((current) => STATE_FILTERS[(STATE_FILTERS.indexOf(current) + 1) % STATE_FILTERS.length]);
  }

  function openEditForm(skill: SkillResponse) {
    const version = skill.enabled_version ?? skill.latest_version;
    setEditingId(skill.id);
    setEditError(null);
    setEditDraft({
      description: version?.description ?? "",
      instructionsMarkdown: version?.instructions_markdown ?? ""
    });
  }

  return (
    <div className={modalStyles.detail}>
      <div className={modalStyles.title}>
        <div className={modalStyles["title-row"]}>
          <h2>스킬</h2>
        </div>
        <p>반복 작업을 안전한 실행 규칙으로 만들어 Fruition Agent에서 재사용합니다.</p>
      </div>

      {/* 필터·검색·생성 툴바 */}
      <div className={styles.toolbar}>
        <div className={styles["toolbar-group"]}>
          <button type="button" className={styles["filter-btn"]} onClick={cycleScopeFilter}>
            {scopeFilter === "all" ? (
              <>저장범위 : 전체</>
            ) : (
              <span className={styles["filter-accent"]}>저장범위 : {SCOPE_LABELS[scopeFilter]}</span>
            )}
            <span aria-hidden>⌄</span>
          </button>
          <button type="button" className={styles["filter-btn"]} onClick={cycleStateFilter}>
            {stateFilter === "all" ? (
              <>상태</>
            ) : (
              <span className={styles["filter-accent"]}>상태 : {STATE_LABELS[stateFilter]}</span>
            )}
            <span aria-hidden>⌄</span>
          </button>
        </div>
        <div className={styles["toolbar-group"]}>
          {searchOpen && (
            <input
              type="text"
              className={styles["search-input"]}
              placeholder="커맨드·설명 검색"
              value={searchText}
              autoFocus
              onChange={(event) => setSearchText(event.target.value)}
            />
          )}
          <button
            type="button"
            className={styles["search-btn"]}
            aria-label="스킬 검색"
            onClick={() => {
              if (searchOpen) setSearchText("");
              setSearchOpen(!searchOpen);
            }}
          >
            <SvgIcon src={menuSearchIcon} className={styles["search-icon"]} />
          </button>
          <button
            type="button"
            className={styles["create-btn"]}
            onClick={() => (createOpen ? closeCreateForm() : setCreateOpen(true))}
          >
            새 스킬 만들기 <span aria-hidden>⌄</span>
          </button>
        </div>
      </div>

      {/* 새 스킬 작성 폼 (author → publish) */}
      {createOpen && (
        <div className={styles["create-form"]}>
          <label className={styles["form-label"]} htmlFor="skill-instruction">
            어떤 작업을 스킬로 만들까요?
          </label>
          <textarea
            id="skill-instruction"
            className={styles["form-textarea"]}
            rows={3}
            placeholder="예: 회의록을 요약해서 액션 아이템 문서를 만들어 줘"
            value={instruction}
            onChange={(event) => setInstruction(event.target.value)}
          />
          <div className={styles["form-actions"]}>
            <button
              type="button"
              className={styles["form-primary"]}
              disabled={instruction.trim().length === 0 || authorMutation.isPending}
              onClick={() => authorMutation.mutate()}
            >
              {authorMutation.isPending ? "초안 생성 중…" : "초안 생성"}
            </button>
            <button type="button" className={styles["form-secondary"]} onClick={closeCreateForm}>
              취소
            </button>
          </div>
          {authorMutation.error != null && (
            <small className={modalStyles["model-error"]} role="alert">
              {getErrorMessage(authorMutation.error, "스킬 초안을 생성하지 못했습니다.")}
            </small>
          )}

          {draft != null && (
            <div className={styles["draft-preview"]}>
              {draft.question && <p className={styles["draft-question"]}>{draft.question}</p>}
              <label className={styles["form-label"]} htmlFor="draft-name">이름</label>
              <input
                id="draft-name"
                type="text"
                className={styles["form-input"]}
                value={draft.name}
                onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              />
              <label className={styles["form-label"]} htmlFor="draft-description">설명</label>
              <textarea
                id="draft-description"
                className={styles["form-textarea"]}
                rows={2}
                value={draft.description}
                onChange={(event) => setDraft({ ...draft, description: event.target.value })}
              />
              <label className={styles["form-label"]} htmlFor="draft-instructions">실행 지침</label>
              <textarea
                id="draft-instructions"
                className={styles["form-textarea"]}
                rows={6}
                value={draft.instructions_markdown}
                onChange={(event) => setDraft({ ...draft, instructions_markdown: event.target.value })}
              />
              <div className={styles["form-actions"]}>
                <button
                  type="button"
                  className={styles["form-primary"]}
                  disabled={publishMutation.isPending}
                  onClick={() => publishMutation.mutate()}
                >
                  {publishMutation.isPending ? "게시 중…" : "게시"}
                </button>
              </div>
              {publishMutation.error != null && (
                <small className={modalStyles["model-error"]} role="alert">
                  {getErrorMessage(publishMutation.error, "스킬을 게시하지 못했습니다.")}
                </small>
              )}
            </div>
          )}
        </div>
      )}

      {error != null && (
        <small className={modalStyles["model-error"]} role="alert">
          {getErrorMessage(error, "스킬 목록을 불러오지 못했습니다.")}
        </small>
      )}
      {toggleError && (
        <small className={modalStyles["model-error"]} role="alert">
          {toggleError}
        </small>
      )}

      {/* 스킬 테이블 */}
      <div className={styles.table}>
        <div className={`${styles.row} ${styles["row-head"]}`}>
          <span className={styles.checkbox} aria-hidden />
          <span>커맨드</span>
          <span>설명</span>
          <span className={styles["cell-scope"]}>저장 범위</span>
          <span className={styles["cell-state"]}>사용 상태</span>
        </div>
        {isLoading && <p className={styles.empty}>스킬 목록을 불러오는 중…</p>}
        {!isLoading && error == null && filteredSkills.length === 0 && (
          <p className={styles.empty}>
            {(skills?.length ?? 0) === 0 ? "등록된 스킬이 없습니다." : "조건에 맞는 스킬이 없습니다."}
          </p>
        )}
        {filteredSkills.map((skill) => {
          const { command, description } = skillLabel(skill);
          const enabled = isSkillEnabled(skill);
          const isEditing = editingId === skill.id;
          return (
            <div key={skill.id}>
              <div className={styles.row}>
                <span className={styles.checkbox} aria-hidden />
                <button
                  type="button"
                  className={styles.command}
                  onClick={() => (isEditing ? setEditingId(null) : openEditForm(skill))}
                >
                  {command}
                </button>
                <span className={styles.description}>{description}</span>
                <span className={styles["cell-scope"]}>
                  <span className={styles["scope-chip"]}>
                    {skill.scope_type === "personal" ? "개인" : "팀"}
                  </span>
                </span>
                <span className={styles["cell-state"]}>
                  <button
                    type="button"
                    className={`${modalStyles.switch} ${enabled ? modalStyles["is-on"] : ""}`}
                    role="switch"
                    aria-checked={enabled}
                    aria-label={`${command} 사용 상태`}
                    disabled={toggleMutation.isPending}
                    onClick={() => toggleMutation.mutate({ skill })}
                  >
                    <span className={modalStyles["switch-ball"]} />
                  </button>
                </span>
              </div>

              {/* 인라인 정의 수정 영역 */}
              {isEditing && (
                <div className={styles["edit-form"]}>
                  <label className={styles["form-label"]} htmlFor={`edit-description-${skill.id}`}>설명</label>
                  <textarea
                    id={`edit-description-${skill.id}`}
                    className={styles["form-textarea"]}
                    rows={2}
                    value={editDraft.description}
                    onChange={(event) => setEditDraft({ ...editDraft, description: event.target.value })}
                  />
                  <label className={styles["form-label"]} htmlFor={`edit-instructions-${skill.id}`}>실행 지침</label>
                  <textarea
                    id={`edit-instructions-${skill.id}`}
                    className={styles["form-textarea"]}
                    rows={6}
                    value={editDraft.instructionsMarkdown}
                    onChange={(event) => setEditDraft({ ...editDraft, instructionsMarkdown: event.target.value })}
                  />
                  <div className={styles["form-actions"]}>
                    <button
                      type="button"
                      className={styles["form-primary"]}
                      disabled={updateMutation.isPending}
                      onClick={() => updateMutation.mutate({ skill })}
                    >
                      {updateMutation.isPending ? "저장 중…" : "저장"}
                    </button>
                    <button type="button" className={styles["form-secondary"]} onClick={() => setEditingId(null)}>
                      취소
                    </button>
                  </div>
                  {editError && (
                    <small className={modalStyles["model-error"]} role="alert">
                      {editError}
                    </small>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
