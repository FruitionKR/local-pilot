export type WorkspaceResponse = {
  id: string;
  name: string;
  icon_emoji: string | null;
  icon_url: string | null;
  created_at: string;
  updated_at: string;
};

export type WorkspaceListResponse = {
  workspaces: WorkspaceResponse[];
};
