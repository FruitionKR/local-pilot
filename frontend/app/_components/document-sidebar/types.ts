import type { MouseEvent as ReactMouseEvent } from "react";
import type { DropTarget, EditingState, FileDropTarget, NoteEditState } from "../../_lib/types";

/** 트리에서 선택 가능한 항목(그래프 노드/문서 연결 정보 포함) */
export type SelectableTreeItem = {
  id: string;
  label: string;
  documentId?: string;
  graphNodeId?: string;
};

export type TreeInteractionProps = {
  draggedItemId: string | null;
  selectedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  noteEditStates: Record<string, NoteEditState>;
  onMoveItem: (target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDragEnd: () => void;
  onContextMenuItem: (event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) => void;
  onSelectGraphNode: (item: SelectableTreeItem) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
};
