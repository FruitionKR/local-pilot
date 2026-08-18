import { apiFetch, parseJsonOrThrow, ERROR_MESSAGES } from "@/shared/api/client";
import type { AiModel, AiModelsResponse } from "../model/aiModel";

/** 선택 가능한 provider/model 조합을 백엔드 카탈로그에서 가져온다. */
export async function fetchAiModels(): Promise<AiModel[]> {
  const response = await apiFetch("/api/ai-models", { cache: "no-store" });
  const body = await parseJsonOrThrow<AiModelsResponse>(response, ERROR_MESSAGES.aiModelsLoadFailed);
  return body.models ?? [];
}
