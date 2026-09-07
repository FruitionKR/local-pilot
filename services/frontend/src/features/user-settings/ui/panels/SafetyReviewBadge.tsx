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
        <svg className={styles.ring} width="80" height="80" viewBox="0 0 80 80" aria-hidden>
          {/* 초록 원이 차오르고 흰 체크가 그려진다 (Figma 1033:8429) */}
          <circle className={styles["fill-circle"]} cx="40" cy="40" r="32" fill="#00de5a" />
          <path
            className={styles.checkmark}
            d="M26 41l10 10 18-20"
            fill="none"
            stroke="#ffffff"
            strokeWidth="5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
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
