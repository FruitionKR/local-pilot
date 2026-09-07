import { apiFetch, parseJsonOrThrow, ERROR_MESSAGES } from "@/shared/api/client";
import type { WorkspaceListResponse, WorkspaceResponse } from "@/entities/workspace/model/workspace";

export async function fetchWorkspaces(): Promise<WorkspaceListResponse> {
  const response = await apiFetch("/api/workspaces", { cache: "no-store" });
  return parseJsonOrThrow<WorkspaceListResponse>(response, ERROR_MESSAGES.workspaceLoadFailed);
}

export async function renameWorkspace(workspaceId: string, name: string): Promise<WorkspaceResponse> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name })
  });
  return parseJsonOrThrow<WorkspaceResponse>(response, "워크스페이스 이름을 변경하지 못했습니다.");
}

export async function createWorkspace(name: string): Promise<WorkspaceResponse> {
  const response = await apiFetch("/api/workspaces", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name })
  });
  return parseJsonOrThrow<WorkspaceResponse>(response, ERROR_MESSAGES.workspaceCreateFailed);
}
