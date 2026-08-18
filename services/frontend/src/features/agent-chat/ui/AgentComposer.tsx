import { ArrowUp, ChevronDown } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent as ReactKeyboardEvent } from "react";
import type { AiModel } from "@/entities/ai";
import { cx } from "@/shared/lib/classNames";
import styles from "./AgentChat.module.css";

export type AiModelCatalogStatus = "loading" | "ready" | "empty" | "error";

/** provider/model 쌍을 목록 key와 선택 비교에 쓰는 문자열로 만든다. */
function modelKey(model: AiModel) {
  return `${model.provider}/${model.model}`;
}

export function AgentComposer({
  value = "",
  isLoading,
  placeholder = "AI 에이전트에게 무엇이든 물어보세요.",
  models,
  selectedModel,
  modelCatalogStatus,
  canSubmit,
  onModelChange,
  onChange,
  onSubmit
}: {
  value: string;
  isLoading: boolean;
  placeholder?: string;
  models: AiModel[];
  selectedModel: AiModel | null;
  modelCatalogStatus: AiModelCatalogStatus;
  canSubmit: boolean;
  onModelChange: (model: AiModel) => void;
  onChange: (value: string) => void;
  onSubmit: () => void;
}) {
  const [isModelListOpen, setIsModelListOpen] = useState(false);
  const modelListRef = useRef<HTMLDivElement | null>(null);
  const selectedKey = selectedModel ? modelKey(selectedModel) : null;

  useEffect(() => {
    if (!isModelListOpen) return;
    function handleOutsidePointerDown(event: PointerEvent) {
      if (modelListRef.current && !modelListRef.current.contains(event.target as Node)) {
        setIsModelListOpen(false);
      }
    }
    document.addEventListener("pointerdown", handleOutsidePointerDown);
    return () => document.removeEventListener("pointerdown", handleOutsidePointerDown);
  }, [isModelListOpen]);

  useEffect(() => {
    if (isLoading) setIsModelListOpen(false);
  }, [isLoading]);

  function submitComposer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit) return;
    onSubmit();
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      if (!canSubmit) return;
      onSubmit();
    }
  }

  const emptyModelLabel = modelCatalogStatus === "empty"
    ? "사용 가능한 모델 없음"
    : modelCatalogStatus === "error"
      ? "모델 불러오기 실패"
      : "모델 불러오는 중";

  return (
    <form className={styles.composer} onSubmit={submitComposer}>
      <textarea
        value={value}
        placeholder={placeholder}
        disabled={isLoading}
        rows={3}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={handleKeyDown}
      />
      <div className={styles["composer-actions"]}>
        <div className={styles["composer-model"]} ref={modelListRef}>
          <button
            type="button"
            className={styles["composer-model-trigger"]}
            aria-label="모델 선택"
            aria-expanded={isModelListOpen}
            disabled={isLoading || !selectedModel}
            onClick={() => setIsModelListOpen((open) => !open)}
          >
            <span>{selectedModel?.display_name ?? emptyModelLabel}</span>
            <ChevronDown size={8} className={cx(isModelListOpen && styles["is-open"])} />
          </button>
          {isModelListOpen && (
            <div className={styles["composer-model-list"]} role="listbox" aria-label="모델 목록">
              {models.map((model) => (
                <button
                  key={modelKey(model)}
                  type="button"
                  role="option"
                  aria-selected={modelKey(model) === selectedKey}
                  className={cx(modelKey(model) === selectedKey && styles["is-selected"])}
                  onClick={() => {
                    onModelChange(model);
                    setIsModelListOpen(false);
                  }}
                >
                  {model.display_name}
                </button>
              ))}
            </div>
          )}
        </div>
        <button
          type="submit"
          className={styles["composer-send"]}
          aria-label="전송"
          disabled={isLoading || !canSubmit || value.trim().length === 0}
        >
          <ArrowUp size={15} />
        </button>
      </div>
    </form>
  );
}
