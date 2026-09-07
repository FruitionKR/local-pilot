"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { disableSkill, enableSkill, fetchSkills, type SkillResponse } from "@/entities/skill";
import { getSelectedWorkspaceId } from "@/shared/lib/auth";
import { getErrorMessage } from "@/shared/lib/errors";
import { menuSearchIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import modalStyles from "../SettingsModal.module.css";
import styles from "./SkillsPanel.module.css";

const SKILLS_QUERY_KEY = ["skills"] as const;

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

/** 스킬 패널 (Figma 981:10091). 목록·사용 상태 토글은 실제 API, 작성·필터는 미배선(비활성). */
export function SkillsPanel() {
  const queryClient = useQueryClient();
  const workspaceId = getSelectedWorkspaceId();
  const [toggleError, setToggleError] = useState<string | null>(null);

  const { data: skills, isLoading, error } = useQuery({
    queryKey: SKILLS_QUERY_KEY,
    queryFn: () => fetchSkills(workspaceId ?? ""),
    enabled: workspaceId != null
  });

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

  return (
    <div className={modalStyles.detail}>
      <div className={modalStyles.title}>
        <div className={modalStyles["title-row"]}>
          <h2>스킬</h2>
        </div>
        <p>반복 작업을 안전한 실행 규칙으로 만들어 Fruition Agent에서 재사용합니다.</p>
      </div>

      {/* 필터·검색·생성 툴바: 작성(author/publish) 흐름 미배선이라 비활성 */}
      <div className={styles.toolbar}>
        <div className={styles["toolbar-group"]}>
          <button type="button" className={styles["filter-btn"]} disabled>
            <span className={styles["filter-accent"]}>저장범위 : 전체</span>
            <span aria-hidden>⌄</span>
          </button>
          <button type="button" className={styles["filter-btn"]} disabled>
            상태 <span aria-hidden>⌄</span>
          </button>
        </div>
        <div className={styles["toolbar-group"]}>
          <button type="button" className={styles["search-btn"]} aria-label="스킬 검색" disabled>
            <SvgIcon src={menuSearchIcon} className={styles["search-icon"]} />
          </button>
          <button type="button" className={styles["create-btn"]} disabled>
            새 스킬 만들기 <span aria-hidden>⌄</span>
          </button>
        </div>
      </div>

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
        {!isLoading && error == null && (skills?.length ?? 0) === 0 && (
          <p className={styles.empty}>등록된 스킬이 없습니다.</p>
        )}
        {skills?.map((skill) => {
          const { command, description } = skillLabel(skill);
          const enabled = isSkillEnabled(skill);
          return (
            <div key={skill.id} className={styles.row}>
              <span className={styles.checkbox} aria-hidden />
              <span className={styles.command}>{command}</span>
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
          );
        })}
      </div>
    </div>
  );
}
