import type { MouseEvent as ReactMouseEvent } from "react";
import type { DropTarget, EditingState, FileDropTarget } from "../../_lib/types";

export type TreeInteractionProps = {
  draggedItemId: string | null;
  selectedItemId: string | null;
  dropTarget: DropTarget | null;
  fileDropTarget: FileDropTarget | null;
  editing: EditingState | null;
  onMoveItem: (projectId: string, itemId: string, target: DropTarget) => void;
  onDropFiles: (projectId: string, folderId: string | null, files: File[]) => void;
  onDragStart: (projectId: string, itemId: string) => void;
  onDragOverItem: (target: DropTarget) => void;
  onFileDragOver: (target: FileDropTarget) => void;
  onFileDragLeave: () => void;
  onDragEnd: () => void;
  onContextMenuItem: (event: ReactMouseEvent<HTMLButtonElement>, projectId: string, itemId: string) => void;
  onSelectGraphNode: (nodeId: string, itemId: string) => void;
  onEditingChange: (label: string) => void;
  onCommitEditing: () => void;
  onCancelEditing: () => void;
};
