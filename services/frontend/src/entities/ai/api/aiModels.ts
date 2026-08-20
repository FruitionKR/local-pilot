import { apiFetch, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "@/shared/api/client";
import type { AiModel, AiModelsResponse, AiModelSelection, WorkspaceAiModelSettings } from "../model/aiModel";

/** 선택 가능한 provider/model 조합을 백엔드 카탈로그에서 가져온다. */
export async function fetchAiModels(): Promise<AiModel[]> {
  const response = await apiFetch("/api/ai-models", { cache: "no-store" });
  const body = await parseJsonOrThrow<AiModelsResponse>(response, ERROR_MESSAGES.aiModelsLoadFailed);
  return body.models ?? [];
}

/** ingest·lint에 사용하는 워크스페이스 모델 설정을 조회한다. */
export async function fetchWorkspaceAiModelSettings(): Promise<WorkspaceAiModelSettings> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/ai-model-settings`,
    { cache: "no-store" }
  );
  return parseJsonOrThrow<WorkspaceAiModelSettings>(response, "LLM Provider 설정을 불러오지 못했습니다.");
}

/** OWNER가 고른 ingest·lint provider/model 설정을 저장한다. */
export async function updateWorkspaceAiModelSettings(
  selection: AiModelSelection
): Promise<WorkspaceAiModelSettings> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/ai-model-settings`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ingest_lint: selection })
    }
  );
  return parseJsonOrThrow<WorkspaceAiModelSettings>(response, "LLM Provider 설정을 저장하지 못했습니다.");
}
