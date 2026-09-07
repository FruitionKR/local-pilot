import type { UserPreferences } from "@/entities/user";
import styles from "../SettingsModal.module.css";

type NotificationKey = keyof UserPreferences["notifications"];

interface NotificationsPanelProps {
  notifications: UserPreferences["notifications"];
  updatePreferences: (update: (current: UserPreferences) => UserPreferences) => void;
}

/** 알림 항목 정의 (Figma 963:8257). */
const NOTIFICATION_ROWS: { key: NotificationKey; label: string; description: string }[] = [
  { key: "completed", label: "문서 처리 완료", description: "문서(Ingest) 분석이 끝나면 알림 카드를 표시합니다." },
  { key: "failed", label: "문서 처리 실패", description: "문서 처리가 실패하면 알림 카드를 표시합니다." },
  { key: "lint", label: "위키 다듬기", description: "위키 다듬기(lint) 작업이 끝나면 알립니다." },
  { key: "restore", label: "복구(롤백)", description: "AI 작업 되돌리기가 끝나면 알립니다." },
  { key: "query", label: "질의 완료", description: "채팅 질의의 답변 도착•실패를 알립니다." },
  { key: "browser", label: "브라우저 알림", description: "탭이 백그라운드일 때 브라우저 알림으로도 보냅니다." }
];

/** 알림 설정 패널 (Figma 963:8257). */
export function NotificationsPanel({ notifications, updatePreferences }: NotificationsPanelProps) {
  async function toggleNotification(key: NotificationKey) {
    const nextValue = !notifications[key];
    // 브라우저 알림은 켤 때 권한 승인이 선행돼야 한다.
    if (key === "browser" && nextValue) {
      if (!("Notification" in window)) return;
      const permission = Notification.permission === "default"
        ? await Notification.requestPermission()
        : Notification.permission;
      if (permission !== "granted") return;
    }
    updatePreferences((current) => ({
      ...current,
      notifications: { ...current.notifications, [key]: nextValue }
    }));
  }

  return (
    <div className={styles.detail}>
      <div className={styles.title}>
        <div className={styles["title-row"]}>
          <h2>알림</h2>
        </div>
        <p>워크스페이스의 알림 권한을 관리합니다.</p>
      </div>

      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>알림 설정</span>
          <span className={styles["section-line"]} />
        </div>
        {NOTIFICATION_ROWS.map(({ key, label, description }) => (
          <div key={key} className={styles.row}>
            <div className={styles["row-title"]}>
              <strong>{label}</strong>
              <small>{description}</small>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={notifications[key]}
              aria-label={label}
              className={`${styles.switch} ${notifications[key] ? styles["is-on"] : ""}`}
              onClick={() => void toggleNotification(key)}
            >
              <span className={styles["switch-ball"]} />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
