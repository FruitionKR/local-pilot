import { ArrowUp } from "lucide-react";
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
