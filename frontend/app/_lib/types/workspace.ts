export type WorkspaceResponse = {
  id: string;
  name: string;
  created_at: string;
  updated_at: string;
};

export type WorkspaceListResponse = {
  workspaces: WorkspaceResponse[];
};
