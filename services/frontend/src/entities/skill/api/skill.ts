import { apiFetch, parseJsonOrThrow } from "@/shared/api/client";
import type {
  SkillAuthoringRequest,
  SkillAuthoringResult,
  SkillPublishRequest,
  SkillResponse,
  SkillUpdateRequest
} from "@/entities/skill/model/skill";

export async function fetchSkills(workspaceId: string): Promise<SkillResponse[]> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills`, { cache: "no-store" });
  return parseJsonOrThrow<SkillResponse[]>(response, "스킬 목록을 불러오지 못했습니다.");
}

export async function authorSkill(workspaceId: string, body: SkillAuthoringRequest): Promise<SkillAuthoringResult> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills/author`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  return parseJsonOrThrow<SkillAuthoringResult>(response, "스킬 초안을 생성하지 못했습니다.");
}

export async function publishSkill(workspaceId: string, body: SkillPublishRequest): Promise<SkillAuthoringResult> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills/author/publish`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  return parseJsonOrThrow<SkillAuthoringResult>(response, "스킬을 게시하지 못했습니다.");
}

export async function updateSkill(
  workspaceId: string,
  skillId: string,
  body: SkillUpdateRequest
): Promise<SkillAuthoringResult> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills/${skillId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  return parseJsonOrThrow<SkillAuthoringResult>(response, "스킬 정의를 수정하지 못했습니다.");
}

export async function enableSkill(workspaceId: string, skillId: string): Promise<SkillResponse> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills/${skillId}/enable`, { method: "POST" });
  return parseJsonOrThrow<SkillResponse>(response, "스킬을 활성화하지 못했습니다.");
}

export async function disableSkill(workspaceId: string, skillId: string): Promise<SkillResponse> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills/${skillId}/disable`, { method: "POST" });
  return parseJsonOrThrow<SkillResponse>(response, "스킬을 비활성화하지 못했습니다.");
}
