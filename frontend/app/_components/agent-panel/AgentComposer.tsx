import { ArrowUp, Plus } from "lucide-react";
import type { FormEvent } from "react";

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
  function submitComposer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit();
  }

  return (
    <form className="composer" onSubmit={submitComposer}>
      <input
        value={value}
        placeholder={placeholder}
        disabled={isLoading}
        onChange={(event) => onChange(event.target.value)}
      />
      <div className="composer-actions">
        {/* 시안의 첨부 버튼 자리 — 동작은 아직 없음 */}
        <button type="button" className="composer-attach" aria-label="파일 첨부">
          <Plus size={16} />
        </button>
        <button
          type="submit"
          className="composer-send"
          aria-label="전송"
          disabled={isLoading || value.trim().length === 0}
        >
          <ArrowUp size={15} />
        </button>
      </div>
    </form>
  );
}
