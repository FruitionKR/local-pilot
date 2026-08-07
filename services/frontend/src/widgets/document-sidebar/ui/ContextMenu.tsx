import type { ContextMenuState } from "@/entities/tree";
import styles from "./DocumentSidebar.module.css";

export function ContextMenu({
  contextMenu,
  convertTarget,
  onRenameContextTarget,
  onAddProject,
  onAddMarkdownFromContext,
  onConvertContextTarget,
  onDeleteContextTarget
}: {
  contextMenu: ContextMenuState;
  /** PDF 원본 문서일 때만 값이 있고, 처리 중이면 isDisabled로 비활성화한다. */
  convertTarget: { isDisabled: boolean } | null;
  onRenameContextTarget: () => void;
  onAddProject: () => void;
  onAddMarkdownFromContext: () => void;
  onConvertContextTarget: () => void;
  onDeleteContextTarget: () => void;
}) {
  return (
    <div
      className={styles["folder-context-menu"]}
      style={{ left: contextMenu.x, top: contextMenu.y }}
      onClick={(event) => event.stopPropagation()}
    >
      <button type="button" onClick={onAddProject}>새 폴더</button>
      <button type="button" onClick={onAddMarkdownFromContext}>새 노트</button>
      <button type="button" onClick={onRenameContextTarget}>이름 변경</button>
      {convertTarget && (
        <button type="button" disabled={convertTarget.isDisabled} onClick={onConvertContextTarget}>
          Markdown으로 변환
        </button>
      )}
      <button type="button" className={styles.danger} onClick={onDeleteContextTarget}>삭제</button>
    </div>
  );
}
