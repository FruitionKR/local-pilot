"use client";

import { useState, type ReactNode } from "react";
import { RotateCcw } from "lucide-react";
import { useUserPreferences, type UserPreferences } from "@/entities/user";
import { cx } from "@/shared/lib/classNames";
import styles from "./SettingsPanel.module.css";

function SettingRow({
  title,
  description,
  children
}: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <div className={styles["settings-field"]}>
      <div className={styles["settings-label"]}>
        <p>{title}</p>
        <span>{description}</span>
      </div>
      {children}
    </div>
  );
}

function SettingSwitch({
  checked,
  label,
  onChange
}: {
  checked: boolean;
  label: string;
  onChange: (checked: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      className={cx(styles["settings-toggle"], checked && styles["is-on"])}
      onClick={() => onChange(!checked)}
    >
      <span />
    </button>
  );
}

export function SettingsPanel() {
  const { preferences, updatePreferences, resetPreferences } = useUserPreferences();
  const [browserNotificationMessage, setBrowserNotificationMessage] = useState<string | null>(null);

  function updateEditor(update: (editor: UserPreferences["editor"]) => UserPreferences["editor"]) {
    updatePreferences((current) => ({ ...current, editor: update(current.editor) }));
  }

  function updateGraphKind(kind: keyof UserPreferences["graph"]["visibleKinds"], visible: boolean) {
    updatePreferences((current) => {
      const visibleKinds = { ...current.graph.visibleKinds, [kind]: visible };
      if (!Object.values(visibleKinds).some(Boolean)) return current;
      return { ...current, graph: { ...current.graph, visibleKinds } };
    });
  }

  async function changeBrowserNotifications(enabled: boolean) {
    setBrowserNotificationMessage(null);
    if (!enabled) {
      updatePreferences((current) => ({
        ...current,
        notifications: { ...current.notifications, browser: false }
      }));
      return;
    }

    if (!("Notification" in window)) {
      setBrowserNotificationMessage("이 브라우저는 알림을 지원하지 않습니다.");
      return;
    }

    const permission = Notification.permission === "default"
      ? await Notification.requestPermission()
      : Notification.permission;
    if (permission !== "granted") {
      setBrowserNotificationMessage("브라우저 알림 권한이 필요합니다.");
      return;
    }

    updatePreferences((current) => ({
      ...current,
      notifications: { ...current.notifications, browser: true }
    }));
  }

  return (
    <section className={styles["settings"]} aria-label="개인 설정">
      <div className={styles["settings-inner"]}>
        <header className={styles["settings-header"]}>
          <div>
            <h2>개인 설정</h2>
            <p>변경 내용은 이 브라우저에 사용자별로 자동 저장됩니다.</p>
          </div>
          <button type="button" className={styles["settings-reset"]} onClick={resetPreferences}>
            <RotateCcw size={14} aria-hidden />
            기본값 복원
          </button>
        </header>

        <section className={styles["settings-section"]}>
          <h3>화면과 접근성</h3>
          <SettingRow
            title="Motion"
            description="전환·스크롤·그래프 움직임을 줄입니다."
          >
            <select
              aria-label="Motion"
              value={preferences.motion}
              onChange={(event) => updatePreferences((current) => ({
                ...current,
                motion: event.target.value as UserPreferences["motion"]
              }))}
            >
              <option value="system">시스템 설정</option>
              <option value="reduced">줄이기</option>
              <option value="full">모두 사용</option>
            </select>
          </SettingRow>
          <SettingRow
            title="문서 Font"
            description="문서 본문과 WYSIWYG 미리보기에 적용합니다."
          >
            <select
              aria-label="문서 Font"
              value={preferences.documentFont}
              onChange={(event) => updatePreferences((current) => ({
                ...current,
                documentFont: event.target.value as UserPreferences["documentFont"]
              }))}
            >
              <option value="system-sans">System Sans</option>
              <option value="readable-sans">Readable Sans</option>
              <option value="serif">Serif</option>
            </select>
          </SettingRow>
        </section>

        <section className={styles["settings-section"]}>
          <h3>편집기</h3>
          <SettingRow
            title="기본 편집 모드"
            description="WYSIWYG는 결과를 바로 편집하고, Markdown은 원문 문법을 편집합니다."
          >
            <select
              aria-label="기본 편집 모드"
              value={preferences.editor.defaultMode}
              onChange={(event) => updateEditor((editor) => ({
                ...editor,
                defaultMode: event.target.value as UserPreferences["editor"]["defaultMode"]
              }))}
            >
              <option value="last">마지막 사용 모드</option>
              <option value="wysiwyg">WYSIWYG</option>
              <option value="markdown">Markdown</option>
            </select>
          </SettingRow>
          <div className={styles["settings-subsection"]}>
            <p>Markdown 표시</p>
            <SettingRow title="줄 바꿈" description="긴 줄을 편집기 폭에 맞춰 다음 줄에 표시합니다.">
              <SettingSwitch
                checked={preferences.editor.markdown.lineWrapping}
                label="Markdown 줄 바꿈"
                onChange={(lineWrapping) => updateEditor((editor) => ({
                  ...editor,
                  markdown: { ...editor.markdown, lineWrapping }
                }))}
              />
            </SettingRow>
            <SettingRow title="줄 번호" description="원문 왼쪽에 행 번호를 표시합니다.">
              <SettingSwitch
                checked={preferences.editor.markdown.lineNumbers}
                label="Markdown 줄 번호"
                onChange={(lineNumbers) => updateEditor((editor) => ({
                  ...editor,
                  markdown: { ...editor.markdown, lineNumbers }
                }))}
              />
            </SettingRow>
            <SettingRow title="현재 줄 강조" description="커서가 있는 줄의 배경을 구분합니다.">
              <SettingSwitch
                checked={preferences.editor.markdown.highlightActiveLine}
                label="Markdown 현재 줄 강조"
                onChange={(highlightActiveLine) => updateEditor((editor) => ({
                  ...editor,
                  markdown: { ...editor.markdown, highlightActiveLine }
                }))}
              />
            </SettingRow>
          </div>
        </section>

        <section className={styles["settings-section"]}>
          <h3>Graph 기본 표시</h3>
          <p className={styles["settings-section-description"]}>
            그래프를 열 때 표시할 노드 종류입니다. 최소 한 종류는 유지됩니다.
          </p>
          {([
            ["raw", "Raw", "업로드한 원본 문서"],
            ["source", "Source", "원문에서 추출한 정보"],
            ["concept", "Concept", "연결된 개념"]
          ] as const).map(([kind, title, description]) => (
            <SettingRow key={kind} title={title} description={description}>
              <SettingSwitch
                checked={preferences.graph.visibleKinds[kind]}
                label={`${title} 노드 표시`}
                onChange={(visible) => updateGraphKind(kind, visible)}
              />
            </SettingRow>
          ))}
        </section>

        <section className={styles["settings-section"]}>
          <h3>문서 처리 알림</h3>
          <SettingRow title="처리 완료" description="문서 분석이 끝나면 앱 안에서 알립니다.">
            <SettingSwitch
              checked={preferences.notifications.completed}
              label="문서 처리 완료 알림"
              onChange={(completed) => updatePreferences((current) => ({
                ...current,
                notifications: { ...current.notifications, completed }
              }))}
            />
          </SettingRow>
          <SettingRow title="처리 실패" description="문서 분석에 실패하면 앱 안에서 알립니다.">
            <SettingSwitch
              checked={preferences.notifications.failed}
              label="문서 처리 실패 알림"
              onChange={(failed) => updatePreferences((current) => ({
                ...current,
                notifications: { ...current.notifications, failed }
              }))}
            />
          </SettingRow>
          <SettingRow
            title="Browser 알림"
            description="앱이 열린 상태에서 탭이 보이지 않을 때 브라우저로 알립니다."
          >
            <SettingSwitch
              checked={preferences.notifications.browser}
              label="Browser 알림"
              onChange={(enabled) => void changeBrowserNotifications(enabled)}
            />
          </SettingRow>
          {browserNotificationMessage && (
            <p className={styles["settings-message"]} role="status">{browserNotificationMessage}</p>
          )}
        </section>
      </div>
    </section>
  );
}
