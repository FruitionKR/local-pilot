import { menuSearchIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import modalStyles from "../SettingsModal.module.css";
import styles from "./SkillsPanel.module.css";

// 스킬 목록 정적 데이터 (Figma 981:10091). 백엔드 미지원이라 표시 전용이다.
const SKILL_ROWS = [
  {
    command: "/meeting-summary",
    description: "회의 내용을 정해진 형식으로 정리합니다.",
    scope: "개인",
    isOn: true
  },
  {
    command: "/weekly-report",
    description: "주간 업무 보고서를 자동으로 작성합니다.",
    scope: "팀",
    isOn: false
  },
  {
    command: "/todo-extract",
    description: "대화와 문서에서 해야할 일을 추출합니다.",
    scope: "팀",
    isOn: true
  },
  {
    command: "/division-log",
    description: "의사결정 내용과 근거를 기록합니다.",
    scope: "개인",
    isOn: true
  }
] as const;

/** 스킬 패널 (Figma 981:10091). 백엔드 미지원이라 전부 비활성 정적 UI다. */
export function SkillsPanel() {
  return (
    <div className={modalStyles.detail}>
      <div className={modalStyles.title}>
        <div className={modalStyles["title-row"]}>
          <h2>스킬</h2>
        </div>
        <p>반복 작업을 안전한 실행 규칙으로 만들어 Fruition Agent에서 재사용합니다.</p>
      </div>

      {/* 필터·검색·생성 툴바: 전부 비활성 */}
      <div className={styles.toolbar}>
        <div className={styles["toolbar-group"]}>
          <button type="button" className={styles["filter-btn"]} disabled>
            <span className={styles["filter-accent"]}>저장범위 : 일부</span>
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

      {/* 스킬 테이블 */}
      <div className={styles.table}>
        <div className={`${styles.row} ${styles["row-head"]}`}>
          <span className={styles.checkbox} aria-hidden />
          <span>커맨드</span>
          <span>설명</span>
          <span className={styles["cell-scope"]}>저장 범위</span>
          <span className={styles["cell-state"]}>사용 상태</span>
        </div>
        {SKILL_ROWS.map((skill) => (
          <div key={skill.command} className={styles.row}>
            <span className={styles.checkbox} aria-hidden />
            <span className={styles.command}>{skill.command}</span>
            <span className={styles.description}>{skill.description}</span>
            <span className={styles["cell-scope"]}>
              <span className={styles["scope-chip"]}>
                {skill.scope} <span aria-hidden>⌄</span>
              </span>
            </span>
            <span className={styles["cell-state"]}>
              <span
                className={`${modalStyles.switch} ${skill.isOn ? modalStyles["is-on"] : ""}`}
                role="switch"
                aria-checked={skill.isOn}
                aria-disabled
                aria-label={`${skill.command} 사용 상태`}
              >
                <span className={modalStyles["switch-ball"]} />
              </span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
