import { ArrowUp, ChevronDown } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { cx } from "@/shared/lib/classNames";
import { claudeIcon, geminiIcon, gptIcon, SvgIcon, type SvgAsset } from "@/shared/ui/SvgIcon";
import styles from "./AgentChat.module.css";

// 모델 표시 목록 (Figma 787:2312 model_list): 아이콘 먼저, 이름이 뒤따른다.
const LLM_MODELS: { name: string; icon: SvgAsset }[] = [
  { name: "Claude", icon: claudeIcon },
  { name: "Gemini", icon: geminiIcon },
  { name: "GPT", icon: gptIcon }
];

export function AgentComposer({
  value = "",
  isLoading,
  placeholder = "AI 에이전트에게 무엇이든 물어보세요.",
  onChange,
  onSubmit
}: {
  value: string;
  isLoading: boolean;
  placeholder?: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
}) {
  const [selectedModelName, setSelectedModelName] = useState(LLM_MODELS[0].name);
  const [isModelListOpen, setIsModelListOpen] = useState(false);
  const modelListRef = useRef<HTMLDivElement | null>(null);
  const selectedModel = LLM_MODELS.find((model) => model.name === selectedModelName) ?? LLM_MODELS[0];

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

  function submitComposer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit();
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      onSubmit();
    }
  }

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
            onClick={() => setIsModelListOpen((open) => !open)}
          >
            <SvgIcon src={selectedModel.icon} className={styles["composer-model-icon"]} />
            <span>{selectedModel.name}</span>
            <ChevronDown size={8} className={cx(isModelListOpen && styles["is-open"])} />
          </button>
          {isModelListOpen && (
            <div className={styles["composer-model-list"]} role="listbox" aria-label="모델 목록">
              {LLM_MODELS.map((model) => (
                <button
                  key={model.name}
                  type="button"
                  role="option"
                  aria-selected={model.name === selectedModelName}
                  className={cx(model.name === selectedModelName && styles["is-selected"])}
                  onClick={() => {
                    setSelectedModelName(model.name);
                    setIsModelListOpen(false);
                  }}
                >
                  <SvgIcon src={model.icon} className={styles["composer-model-icon"]} />
                  {model.name}
                </button>
              ))}
            </div>
          )}
        </div>
        <button
          type="submit"
          className={styles["composer-send"]}
          aria-label="전송"
          disabled={isLoading || value.trim().length === 0}
        >
          <ArrowUp size={15} />
        </button>
      </div>
    </form>
  );
}
