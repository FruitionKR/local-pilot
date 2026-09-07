import { apiFetch, parseJsonOrThrow } from "@/shared/api/client";
import type { SkillResponse } from "@/entities/skill/model/skill";

export async function fetchSkills(workspaceId: string): Promise<SkillResponse[]> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills`, { cache: "no-store" });
  return parseJsonOrThrow<SkillResponse[]>(response, "스킬 목록을 불러오지 못했습니다.");
}

export async function enableSkill(workspaceId: string, skillId: string): Promise<SkillResponse> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills/${skillId}/enable`, { method: "POST" });
  return parseJsonOrThrow<SkillResponse>(response, "스킬을 활성화하지 못했습니다.");
}

export async function disableSkill(workspaceId: string, skillId: string): Promise<SkillResponse> {
  const response = await apiFetch(`/api/workspaces/${workspaceId}/skills/${skillId}/disable`, { method: "POST" });
  return parseJsonOrThrow<SkillResponse>(response, "스킬을 비활성화하지 못했습니다.");
}
