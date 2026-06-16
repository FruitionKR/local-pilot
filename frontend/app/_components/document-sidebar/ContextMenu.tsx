import type { ContextMenuState } from "../../_lib/types";

export function ContextMenu({
  contextMenu,
  onRenameContextTarget,
  onAddFolderFromContext,
  onDeleteContextTarget
}: {
  contextMenu: ContextMenuState;
  onRenameContextTarget: () => void;
  onAddFolderFromContext: () => void;
  onDeleteContextTarget: () => void;
}) {
  return (
    <div
      className="folder-context-menu"
      style={{ left: contextMenu.x, top: contextMenu.y }}
      onClick={(event) => event.stopPropagation()}
    >
      <button type="button" onClick={onRenameContextTarget}>이름 변경</button>
      <button type="button" onClick={onAddFolderFromContext}>새 폴더</button>
      {contextMenu.itemId !== null && <button type="button" className="danger" onClick={onDeleteContextTarget}>삭제</button>}
    </div>
  );
}
