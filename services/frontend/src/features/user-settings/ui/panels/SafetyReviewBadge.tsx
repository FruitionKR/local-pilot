import styles from "./SafetyReviewBadge.module.css";

/**
 * 안전 검토 상태 배지 (Figma 1033:8390 검토 중 / 1033:8429 통과).
 * Figma의 모션 컴포넌트를 인라인 SVG + CSS 애니메이션으로 재현한다.
 */
export function SafetyReviewBadge({ variant }: { variant: "loading" | "complete" }) {
  const isComplete = variant === "complete";
  return (
    <div className={styles.badge}>
      {/* 그라데이션 링은 두 상태가 공유한다 — 전환 시 리마운트되지 않아 회전이 끊기지 않는다 */}
      <div className={styles["ring-box"]} aria-hidden>
        <div className={styles["gradient-arc"]} />
        {isComplete && (
          <svg className={styles["complete-overlay"]} width="64" height="64" viewBox="0 0 64 64">
            <circle
              className={styles["outline-circle"]}
              cx="32"
              cy="32"
              r="29.6"
              fill="none"
              stroke="#00de5a"
              strokeWidth="4.8"
            />
            <path
              className={styles.checkmark}
              d="M17.4 34.3 L27.2 44.1 L45.9 25.4"
              fill="none"
              stroke="#00de5a"
              strokeWidth="4.8"
              strokeLinecap="round"
            />
          </svg>
        )}
      </div>
      <span className={isComplete ? styles["text-complete"] : styles["text-loading"]}>
        {isComplete ? "안전 검토를 통과했습니다." : "검토 중..."}
      </span>
    </div>
  );
}
