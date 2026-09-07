import styles from "./SafetyReviewBadge.module.css";

/**
 * 안전 검토 상태 배지 (Figma 1033:8390 검토 중 / 1033:8429 통과).
 * Figma의 모션 컴포넌트를 인라인 SVG + CSS 애니메이션으로 재현한다.
 */
export function SafetyReviewBadge({ variant }: { variant: "loading" | "complete" }) {
  const isComplete = variant === "complete";
  return (
    <div className={styles.badge}>
      {isComplete ? (
        /* 통과 (Figma 1033:8429 asset 원본): 정지한 그라데이션 링 위에
           초록 아웃라인 원이 페이드 인되고 초록 체크가 그려진다 */
        <div className={styles["ring-box"]} aria-hidden>
          <div className={`${styles["gradient-arc"]} ${styles["is-static"]}`} />
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
        </div>
      ) : (
        /* 꼬리가 투명으로 사라지는 conic-gradient 초록 링 회전 (Figma 1033:8398 Ring Arc 원본과 동일) */
        <div className={styles["ring-box"]} aria-hidden>
          <div className={styles["gradient-arc"]} />
        </div>
      )}
      <span className={isComplete ? styles["text-complete"] : styles["text-loading"]}>
        {isComplete ? "안전 검토를 통과했습니다." : "검토 중..."}
      </span>
    </div>
  );
}
