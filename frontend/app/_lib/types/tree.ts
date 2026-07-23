export type TreeItem = {
  id: string;
  label: string;
  type?: "folder" | "file" | "wiki";
  wikiKind?: "source" | "concept";
  generated?: boolean;
  customLabel?: boolean;
  status?: "uploading" | DocumentStatus;
  errorMessage?: string;
  documentId?: string;
  mimeType?: string;
  byteSize?: number;
  sourceUri?: string;
  uploadedAt?: string;
  graphNodeId?: string;
  active?: boolean;
  children?: TreeItem[];
};

export type Project = {
  id: string;
  title: string;
  items: TreeItem[];
};

export type DropPosition = "before" | "inside" | "after";

export type DropTarget = {
  projectId: string;
  targetId: string | null;
  position: DropPosition;
};

export type ContextMenuState = {
  projectId: string;
  itemId: string | null;
  x: number;
  y: number;
};

export type EditingState = {
  projectId: string;
  itemId: string | null;
  label: string;
};

export type FileDropTarget = {
  projectId: string;
  folderId: string | null;
};

export type UploadPickerTarget = {
  projectId: string;
  folderId: string | null;
};

export type DocumentStatus = "uploaded" | "processing" | "completed" | "failed";

export type NoteSaveStatus = "saved" | "dirty" | "saving" | "error" | "conflict";

export type NoteEditState = {
  saveStatus: NoteSaveStatus;
  needsReview: boolean;
};
