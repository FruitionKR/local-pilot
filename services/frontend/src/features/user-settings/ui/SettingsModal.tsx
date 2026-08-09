"use client";

import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { fetchMe, useUserPreferences } from "@/entities/user";
import { useWorkspaceName } from "@/entities/workspace/model/useWorkspaceName";
import { bellIcon, lightningIcon, plusIcon, settingIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import styles from "./SettingsModal.module.css";

type SettingsSection = "general" | "notifications";

type NotificationKey = "completed" | "failed" | "lint" | "restore" | "query" | "browser";

const NOTIFICATION_ROWS: { key: NotificationKey; title: string; description: string }[] = [
  { key: "completed", title: "문서 처리 완료", description: "문서(ingest) 분석이 끝나면 알림 카드를 표시합니다." },
  { key: "failed", title: "문서 처리 실패", description: "문서 처리가 실패하면 알림 카드를 표시합니다." },
  { key: "lint", title: "위키 다듬기", description: "위키 다듬기(lint) 작업이 끝나면 알립니다." },
  { key: "restore", title: "복구(롤백)", description: "AI 작업 되돌리기가 끝나면 알립니다." },
  { key: "query", title: "질의 완료", description: "채팅 질의의 답변 도착·실패를 알립니다." },
  { key: "browser", title: "브라우저 알림", description: "탭이 백그라운드일 때 브라우저 알림으로도 보냅니다." }
];

/** 설정 모달 (Figma 771:19544). 닉네임·이메일·워크스페이스명은 실제 데이터, 변경 액션은 미리보기 단계. */
export function SettingsModal({ onClose }: { onClose: () => void }) {
  const workspaceName = useWorkspaceName();
  const { preferences, updatePreferences } = useUserPreferences();
  const [activeSection, setActiveSection] = useState<SettingsSection>("general");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [isAutoSaveOn, setIsAutoSaveOn] = useState(true);

  function toggleNotification(key: NotificationKey) {
    const nextValue = !preferences.notifications[key];
    // 브라우저 알림을 켤 때 권한이 미결정이면 요청한다.
    if (key === "browser" && nextValue && "Notification" in window && Notification.permission === "default") {
      void Notification.requestPermission();
    }
    updatePreferences((current) => ({
      ...current,
      notifications: { ...current.notifications, [key]: nextValue }
    }));
  }

  useEffect(() => {
    let cancelled = false;
    fetchMe()
      .then((me) => {
        if (cancelled) return;
        setDisplayName(me.display_name || "");
        setEmail(me.email || "");
      })
      .catch(() => {
        // 표시용 데이터라 실패 시 빈 값을 유지한다.
      });

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      cancelled = true;
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [onClose]);

  const name = displayName || "사용자";
  const wsName = workspaceName ?? "워크스페이스";

  // 사이드바(z-index 스태킹 컨텍스트) 내부에 렌더되면 편집기 등에 가려지므로 body로 portal한다.
  return createPortal(
    <div className={styles.overlay} onClick={onClose}>
      <div
        className={styles.modal}
        role="dialog"
        aria-modal="true"
        aria-label="설정"
        onClick={(event) => event.stopPropagation()}
      >
        <nav className={styles.nav} aria-label="설정 메뉴">
          <div className={styles["nav-group"]}>
            <p className={styles["nav-label"]}>계정</p>
            <div className={styles["nav-row"]}>
              <span className={styles["nav-avatar"]} aria-hidden>{name.charAt(0)}</span>
              <span>{name}</span>
            </div>
            <button
              type="button"
              className={`${styles["nav-row"]} ${activeSection === "notifications" ? styles["is-active"] : ""}`}
              onClick={() => setActiveSection("notifications")}
            >
              <SvgIcon src={bellIcon} className={styles["nav-icon"]} />
              <span>알림</span>
            </button>
          </div>
          <div className={styles["nav-group"]}>
            <p className={styles["nav-label"]}>설정</p>
            <button
              type="button"
              className={`${styles["nav-row"]} ${activeSection === "general" ? styles["is-active"] : ""}`}
              onClick={() => setActiveSection("general")}
            >
              <SvgIcon src={settingIcon} className={styles["nav-icon"]} />
              <span>기본 설정</span>
            </button>
            <div className={styles["nav-row"]}>
              <SvgIcon src={lightningIcon} className={styles["nav-icon"]} />
              <span>스킬</span>
            </div>
          </div>
        </nav>

        <div className={styles.content}>
          {activeSection === "notifications" ? (
            <div className={styles.detail}>
              <div className={styles.title}>
                <div className={styles["title-row"]}>
                  <h2>알림</h2>
                </div>
                <p>표시할 알림 종류를 선택하세요.</p>
              </div>
              <section className={styles.section}>
                <div className={styles["section-header"]}>
                  <span>문서 처리</span>
                  <span className={styles["section-line"]} aria-hidden />
                </div>
                {NOTIFICATION_ROWS.map((row) => (
                  <div key={row.key} className={styles.row}>
                    <div className={styles["row-title"]}>
                      <strong>{row.title}</strong>
                      <small>{row.description}</small>
                    </div>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={preferences.notifications[row.key]}
                      aria-label={row.title}
                      className={`${styles.switch} ${preferences.notifications[row.key] ? styles["is-on"] : ""}`}
                      onClick={() => toggleNotification(row.key)}
                    >
                      <span className={styles["switch-ball"]} />
                    </button>
                  </div>
                ))}
              </section>
            </div>
          ) : (
          <div className={styles.detail}>
            <div className={styles.title}>
              <div className={styles["title-row"]}>
                <h2>설정</h2>
                <span className={styles["preview-label"]}>미리보기</span>
              </div>
              <p>워크스페이스 및 계정 설정입니다.</p>
            </div>

            <section className={styles.section}>
              <div className={styles["section-header"]}>
                <span>프로필</span>
                <span className={styles["section-line"]} aria-hidden />
              </div>
              <div className={styles.field}>
                <label htmlFor="settings-nickname">닉네임</label>
                <input id="settings-nickname" type="text" value={name} readOnly />
              </div>
            </section>

            <section className={styles.section}>
              <div className={styles["section-header"]}>
                <span>계정 정보</span>
                <span className={styles["section-line"]} aria-hidden />
              </div>
              <div className={styles.row}>
                <div className={styles["row-title"]}>
                  <strong>이메일</strong>
                  <small>{email || "이메일 정보를 불러오지 못했습니다."}</small>
                </div>
                <button type="button" className={styles.btn} disabled>이메일 변경</button>
              </div>
              <div className={styles.row}>
                <div className={styles["row-title"]}>
                  <strong>비밀번호</strong>
                  <small>로그인에 사용하는 비밀번호를 변경하세요.</small>
                </div>
                <button type="button" className={styles.btn} disabled>비밀번호 변경</button>
              </div>
            </section>

            <section className={styles.section}>
              <div className={styles["section-header"]}>
                <span>워크스페이스</span>
                <span className={styles["section-line"]} aria-hidden />
              </div>
              <div className={styles.field}>
                <label htmlFor="settings-workspace-name">워크스페이스 이름</label>
                <small>워크스페이스 이름은 최대 65자까지 입력할 수 있습니다.</small>
                <input id="settings-workspace-name" type="text" value={wsName} readOnly />
              </div>
              <div className={styles.field}>
                <span className={styles["field-label"]}>워크스페이스 아이콘</span>
                <small>이미지를 업로드하거나 이모티콘을 선택하세요 (100*100 사이즈를 추천드립니다.)</small>
                <span className={styles["ws-icon-outline"]}>
                  <span className={styles["ws-icon"]} aria-hidden>{wsName.charAt(0)}</span>
                </span>
              </div>
              <div className={styles.row}>
                <div className={styles["row-title"]}>
                  <strong>자동 저장</strong>
                  <small>편집한 내용을 자동으로 저장합니다.</small>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={isAutoSaveOn}
                  aria-label="자동 저장"
                  className={`${styles.switch} ${isAutoSaveOn ? styles["is-on"] : ""}`}
                  onClick={() => setIsAutoSaveOn((on) => !on)}
                >
                  <span className={styles["switch-ball"]} />
                </button>
              </div>
            </section>

            <section className={styles.section}>
              <div className={styles["section-header"]}>
                <span>AI 모델</span>
                <span className={styles["section-line"]} aria-hidden />
              </div>
              <div className={styles.row}>
                <div className={styles["row-title"]}>
                  <strong>LLM Provider</strong>
                  <small>위키 생성·편집에 사용하는 모델</small>
                </div>
                <div className={styles["model-select"]}>
                  <span className={styles["model-pill"]}>Gemini • gemini-flash-latest</span>
                  <button type="button" className={styles.btn} disabled>모델 변경</button>
                </div>
              </div>
            </section>
          </div>
          )}
          <button type="button" className={styles.close} aria-label="설정 닫기" onClick={onClose}>
            <SvgIcon src={plusIcon} className={styles["close-icon"]} />
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
