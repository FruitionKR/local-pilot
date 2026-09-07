import styles from "./SafetyReviewBadge.module.css";

/**
 * 안전 검토 상태 배지 (Figma 1033:8390 검토 중 / 1033:8429 통과).
 * Figma의 모션 컴포넌트를 인라인 SVG + CSS 애니메이션으로 재현한다.
 */
export function SafetyReviewBadge({ variant }: { variant: "loading" | "complete" }) {
  const isComplete = variant === "complete";
  return (
    <div className={styles.badge}>
      <svg className={styles.ring} width="80" height="80" viewBox="0 0 80 80" aria-hidden>
        {/* 링 트랙 */}
        <circle cx="40" cy="40" r="32" fill="none" stroke="#323232" strokeWidth="6" />
        {isComplete ? (
          <>
            {/* 초록 원이 차오르고 체크가 그려진다 */}
            <circle className={styles["fill-circle"]} cx="40" cy="40" r="32" fill="#00de5a" />
            <path
              className={styles.checkmark}
              d="M26 41l10 10 18-20"
              fill="none"
              stroke="#0a0a0a"
              strokeWidth="5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </>
        ) : (
          /* 노란 호가 회전하는 스피너 */
          <circle
            className={styles.arc}
            cx="40"
            cy="40"
            r="32"
            fill="none"
            stroke="#ffc117"
            strokeWidth="6"
            strokeLinecap="round"
            strokeDasharray="60 141"
          />
        )}
      </svg>
      <span className={isComplete ? styles["text-complete"] : styles["text-loading"]}>
        {isComplete ? "안전 검토를 통과했습니다." : "검토 중..."}
      </span>
    </div>
  );
}
