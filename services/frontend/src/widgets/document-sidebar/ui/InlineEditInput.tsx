import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import styles from "./DocumentSidebar.module.css";

export function InlineEditInput({
  value,
  onCommit,
  onCancel,
  onChange
}: {
  value: string;
  onCommit: () => void;
  onCancel: () => void;
  onChange: (value: string) => void;
}) {
  function handleKeyDown(event: ReactKeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") onCommit();
    if (event.key === "Escape") onCancel();
  }

  return (
    <input
      className={styles["tree-edit-input"]}
      value={value}
      autoFocus
      onChange={(event) => onChange(event.target.value)}
      onBlur={onCommit}
      onClick={(event) => event.stopPropagation()}
      onKeyDown={handleKeyDown}
    />
  );
}
