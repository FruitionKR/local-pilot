import { apiFetch, parseJsonOrThrow, throwIfNotOk, workspacePath, ERROR_MESSAGES } from "@/shared/api/client";
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

export async function updateWorkspaceIcon(workspaceId: string, emoji: string | null): Promise<WorkspaceResponse> {
  const response = await apiFetch(workspacePath(workspaceId, "icon"), {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ icon_emoji: emoji })
  });
  return parseJsonOrThrow<WorkspaceResponse>(response, "아이콘을 변경하지 못했습니다.");
}

export async function uploadWorkspaceIcon(workspaceId: string, file: File): Promise<WorkspaceResponse> {
  const body = new FormData();
  body.append("file", file);
  const response = await apiFetch(workspacePath(workspaceId, "icon", "image"), { method: "PUT", body });
  return parseJsonOrThrow<WorkspaceResponse>(response, "아이콘 이미지를 업로드하지 못했습니다.");
}

export async function fetchWorkspaceIcon(workspaceId: string): Promise<Blob> {
  const response = await apiFetch(workspacePath(workspaceId, "icon", "image"), { cache: "no-store" });
  await throwIfNotOk(response, "아이콘 이미지를 불러오지 못했습니다.");
  return response.blob();
}
