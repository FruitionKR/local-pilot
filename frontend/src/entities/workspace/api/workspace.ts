import { apiFetch, parseJsonOrThrow, ERROR_MESSAGES } from "@/shared/api/client";
import type { WorkspaceListResponse, WorkspaceResponse } from "@/entities/workspace/model/workspace";

export async function fetchWorkspaces(): Promise<WorkspaceListResponse> {
  const response = await apiFetch("/api/workspaces", { cache: "no-store" });
  return parseJsonOrThrow<WorkspaceListResponse>(response, ERROR_MESSAGES.workspaceLoadFailed);
}

export async function createWorkspace(name: string): Promise<WorkspaceResponse> {
  const response = await apiFetch("/api/workspaces", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name })
  });
  return parseJsonOrThrow<WorkspaceResponse>(response, ERROR_MESSAGES.workspaceCreateFailed);
}
