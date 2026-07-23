import type { ContextMenuState } from "@/entities/tree";
import styles from "./DocumentSidebar.module.css";

export function ContextMenu({
  contextMenu,
  onRenameContextTarget,
  onAddProject,
  onAddMarkdownFromContext,
  onDeleteContextTarget
}: {
  contextMenu: ContextMenuState;
  onRenameContextTarget: () => void;
  onAddProject: () => void;
  onAddMarkdownFromContext: () => void;
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
      <button type="button" className={styles.danger} onClick={onDeleteContextTarget}>삭제</button>
    </div>
  );
}
