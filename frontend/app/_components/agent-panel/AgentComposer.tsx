import { Plus } from "lucide-react";
import type { FormEvent } from "react";
import { frameIcon, SvgIcon } from "../SvgIcon";

export function AgentComposer({
  value = "",
  isLoading,
  onChange,
  onSubmit
}: {
  value: string;
  isLoading: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
}) {
  function submitComposer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit();
  }

  return (
    <form className="composer" onSubmit={submitComposer}>
      <Plus size={18} />
      <input
        value={value}
        placeholder="메시지를 입력하세요..."
        disabled={isLoading}
        onChange={(event) => onChange(event.target.value)}
      />
      <button type="submit" aria-label="전송" disabled={isLoading || value.trim().length === 0}>
        <SvgIcon src={frameIcon} />
      </button>
    </form>
  );
}
