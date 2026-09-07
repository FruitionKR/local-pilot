"use client";

import { useState } from "react";
import type { AiModel, AiModelSelection } from "@/entities/ai";
import { claudeIcon, geminiIcon, gptIcon, SvgIcon, type SvgAsset } from "@/shared/ui/SvgIcon";
import styles from "../SettingsModal.module.css";
import panelStyles from "./WorkspacePanel.module.css";

// provider id → 표시 라벨·아이콘
const providerMeta: Record<string, { label: string; icon: SvgAsset }> = {
  openai: { label: "OpenAI", icon: gptIcon },
  gemini: { label: "Gemini", icon: geminiIcon },
  claude: { label: "Claude", icon: claudeIcon }
};

interface WorkspacePanelProps {
  wsName: string;
  isAutoSaveOn: boolean;
  onToggleAutoSave: () => void;
  aiModels: AiModel[];
  aiModelSelection: AiModelSelection | null;
  aiModelError: string | null;
  isAiModelSaving: boolean;
  canUpdateAiModel: boolean;
  onSelectProvider: (provider: string) => void;
}

/** 워크스페이스 설정 패널 (Figma 771:18800). */
export function WorkspacePanel({
  wsName,
  isAutoSaveOn,
  onToggleAutoSave,
  aiModels,
  aiModelSelection,
  aiModelError,
  isAiModelSaving,
  canUpdateAiModel,
  onSelectProvider
}: WorkspacePanelProps) {
  // "모델 변경" 클릭 시에만 provider 선택 목록을 펼친다.
  const [isProviderListOpen, setIsProviderListOpen] = useState(false);

  // 카탈로그에 실제로 존재하는 provider만 노출한다.
  const providers = Array.from(new Set(aiModels.map((model) => model.provider)));
  const selectedMeta = aiModelSelection ? providerMeta[aiModelSelection.provider] : undefined;

  return (
    <div className={styles.detail}>
      <div className={styles.title}>
        <div className={styles["title-row"]}>
          <h2>워크스페이스 설정</h2>
        </div>
        <p>워크스페이스 설정입니다.</p>
      </div>

      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>워크스페이스 이름</span>
          <span className={styles["section-line"]} />
        </div>
        <div className={styles.field}>
          <label htmlFor="workspace-name">워크스페이스 이름</label>
          <small>워크스페이스 이름은 최대 65자까지 입력할 수 있습니다.</small>
          <input id="workspace-name" type="text" value={wsName} readOnly />
        </div>
      </div>

      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>워크스페이스 아이콘</span>
          <span className={styles["section-line"]} />
        </div>
        <div className={styles.field}>
          <span className={styles["field-label"]}>워크스페이스 아이콘</span>
          <small>이미지를 업로드하거나 이모티콘을 선택하세요 (100*100 사이즈를 추천드립니다.)</small>
          <div className={styles["ws-icon-outline"]}>
            <span className={styles["ws-icon"]} aria-hidden>{wsName.charAt(0)}</span>
          </div>
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
            onClick={onToggleAutoSave}
          >
            <span className={styles["switch-ball"]} />
          </button>
        </div>
      </div>

      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>AI 모델</span>
          <span className={styles["section-line"]} />
        </div>
        <div className={styles.row}>
          <div className={styles["row-title"]}>
            <strong>LLM Provider</strong>
            <small>위키 생성•편집에 사용하는 모델</small>
          </div>
          <div className={panelStyles["model-controls"]}>
            <div className={panelStyles["model-actions"]}>
              {aiModelSelection && (
                <span className={panelStyles.pill}>
                  {selectedMeta && <SvgIcon src={selectedMeta.icon} className={panelStyles["pill-icon"]} />}
                  {selectedMeta?.label ?? aiModelSelection.provider} • {aiModelSelection.model}
                </span>
              )}
              <button
                type="button"
                className={styles.btn}
                disabled={!canUpdateAiModel}
                aria-expanded={isProviderListOpen}
                onClick={() => setIsProviderListOpen((open) => !open)}
              >
                모델 변경
              </button>
            </div>
            {!canUpdateAiModel && (
              <small className={styles["provider-readonly"]}>
                Provider 변경은 워크스페이스 OWNER만 할 수 있습니다.
              </small>
            )}
            {isProviderListOpen && canUpdateAiModel && (
              <div className={styles["provider-list"]}>
                {providers.map((provider) => {
                  const meta = providerMeta[provider];
                  const isSelected = aiModelSelection?.provider === provider;
                  return (
                    <button
                      key={provider}
                      type="button"
                      className={`${styles["provider-button"]} ${isSelected ? styles["is-selected"] : ""}`}
                      aria-pressed={isSelected}
                      disabled={isAiModelSaving || !canUpdateAiModel}
                      onClick={() => onSelectProvider(provider)}
                    >
                      {meta && <SvgIcon src={meta.icon} className={styles["provider-icon"]} />}
                      <span>{meta?.label ?? provider}</span>
                    </button>
                  );
                })}
              </div>
            )}
            {aiModelError && (
              <small className={styles["model-error"]} role="alert">
                {aiModelError}
              </small>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
