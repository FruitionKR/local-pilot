"use client";

import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import {
  fetchAiModels,
  fetchWorkspaceAiModelSettings,
  isSameSelection,
  resolveProviderModel,
  updateWorkspaceAiModelSettings,
  type AiModel,
  type AiModelSelection
} from "@/entities/ai";
import { useMe, useUserPreferences } from "@/entities/user";
import { useWorkspaceName } from "@/entities/workspace/model/useWorkspaceName";
import { getErrorMessage } from "@/shared/lib/errors";
import { useEscapeKey } from "@/shared/lib/useEscapeKey";
import {
  bellIcon,
  lightningIcon,
  plusIcon,
  settingIcon,
  SvgIcon,
  userCircleOutlineIcon
} from "@/shared/ui/SvgIcon";
import { AccountPanel } from "./panels/AccountPanel";
import { MembersPanel } from "./panels/MembersPanel";
import { NotificationsPanel } from "./panels/NotificationsPanel";
import { SkillsPanel } from "./panels/SkillsPanel";
import { WorkspacePanel } from "./panels/WorkspacePanel";
import styles from "./SettingsModal.module.css";

type SettingsSection = "account" | "notifications" | "general" | "members" | "skills";

/** 설정 모달 (Figma 963:8660 / 963:8257 / 771:18800 / 981:10091). */
export function SettingsModal({ onClose }: { onClose: () => void }) {
  const [mfaNavigationLocked, setMfaNavigationLocked] = useState(false);
  const workspaceName = useWorkspaceName();
  const { preferences, updatePreferences } = useUserPreferences();
  const [activeSection, setActiveSection] = useState<SettingsSection>("account");
  const { data: me } = useMe();
  const [aiModels, setAiModels] = useState<AiModel[]>([]);
  const [aiModelSelection, setAiModelSelection] = useState<AiModelSelection | null>(null);
  const [aiModelError, setAiModelError] = useState<string | null>(null);
  const [isAiModelSaving, setIsAiModelSaving] = useState(false);
  // PUT /ai-model-settings는 OWNER 전용이다. 설정 로드 전에는 안전하게 읽기 전용으로 둔다.
  const [canUpdateAiModel, setCanUpdateAiModel] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchAiModels()
      .then((models) => {
        if (!cancelled) setAiModels(models);
      })
      .catch((error: unknown) => {
        if (!cancelled) setAiModelError(getErrorMessage(error, "LLM Provider 목록을 불러오지 못했습니다."));
      });
    fetchWorkspaceAiModelSettings()
      .then((settings) => {
        if (cancelled) return;
        setAiModelSelection(settings.ingest_lint);
        setCanUpdateAiModel(settings.can_update === true);
      })
      .catch((error: unknown) => {
        if (!cancelled) setAiModelError(getErrorMessage(error, "LLM Provider 설정을 불러오지 못했습니다."));
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEscapeKey(!mfaNavigationLocked, onClose);

  const name = me?.display_name || "사용자";
  const wsName = workspaceName ?? "워크스페이스";

  async function selectAiProvider(provider: string) {
    if (!canUpdateAiModel) return;
    const selected = resolveProviderModel(aiModels, provider, aiModelSelection);
    // provider만 같고 model이 catalog에서 빠진 경우에도 유효 조합으로 복구해야 하므로 전체를 비교한다.
    if (!selected || isSameSelection(selected, aiModelSelection) || isAiModelSaving) return;
    setIsAiModelSaving(true);
    setAiModelError(null);
    try {
      const settings = await updateWorkspaceAiModelSettings({
        provider: selected.provider,
        model: selected.model
      });
      setAiModelSelection(settings.ingest_lint);
      setCanUpdateAiModel(settings.can_update === true);
    } catch (error: unknown) {
      setAiModelError(getErrorMessage(error, "LLM Provider 설정을 저장하지 못했습니다."));
    } finally {
      setIsAiModelSaving(false);
    }
  }

  // 사이드바(z-index 스태킹 컨텍스트) 내부에 렌더되면 편집기 등에 가려지므로 body로 portal한다.
  return createPortal(
    <div className={styles.overlay} onClick={() => { if (!mfaNavigationLocked) onClose(); }}>
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
            <button
              type="button"
              className={`${styles["nav-row"]} ${activeSection === "account" ? styles["is-active"] : ""}`}
              disabled={mfaNavigationLocked}
              onClick={() => setActiveSection("account")}
            >
              <span className={styles["nav-avatar"]} aria-hidden>{name.charAt(0)}</span>
              <span>{name}</span>
            </button>
            <button
              type="button"
              className={`${styles["nav-row"]} ${activeSection === "notifications" ? styles["is-active"] : ""}`}
              disabled={mfaNavigationLocked}
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
              disabled={mfaNavigationLocked}
              onClick={() => setActiveSection("general")}
            >
              <SvgIcon src={settingIcon} className={styles["nav-icon"]} />
              <span>기본 설정</span>
            </button>
            <button
              type="button"
              className={`${styles["nav-row"]} ${activeSection === "members" ? styles["is-active"] : ""}`}
              disabled={mfaNavigationLocked}
              onClick={() => setActiveSection("members")}
            >
              <SvgIcon src={userCircleOutlineIcon} className={styles["nav-icon"]} />
              <span>멤버 관리</span>
            </button>
            <button
              type="button"
              className={`${styles["nav-row"]} ${activeSection === "skills" ? styles["is-active"] : ""}`}
              disabled={mfaNavigationLocked}
              onClick={() => setActiveSection("skills")}
            >
              <SvgIcon src={lightningIcon} className={styles["nav-icon"]} />
              <span>스킬</span>
            </button>
          </div>
        </nav>

        <div className={styles.content} data-section={activeSection}>
          {activeSection === "account" && <AccountPanel onMfaNavigationLockChange={setMfaNavigationLocked} />}
          {activeSection === "notifications" && (
            <NotificationsPanel
              notifications={preferences.notifications}
              updatePreferences={updatePreferences}
            />
          )}
          {activeSection === "general" && (
            <WorkspacePanel
              wsName={wsName}
              isAutoSaveOn={preferences.autoSave}
              onToggleAutoSave={() =>
                updatePreferences((current) => ({ ...current, autoSave: !current.autoSave }))
              }
              aiModels={aiModels}
              aiModelSelection={aiModelSelection}
              aiModelError={aiModelError}
              isAiModelSaving={isAiModelSaving}
              canUpdateAiModel={canUpdateAiModel}
              onSelectProvider={(provider) => void selectAiProvider(provider)}
            />
          )}
          {activeSection === "members" && <MembersPanel onLeave={onClose} />}
          {activeSection === "skills" && <SkillsPanel />}
          <button type="button" className={styles.close} aria-label="설정 닫기" disabled={mfaNavigationLocked} onClick={onClose}>
            <SvgIcon src={plusIcon} className={styles["close-icon"]} />
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
