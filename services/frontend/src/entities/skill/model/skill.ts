// Skill API 계약 타입 (docs/api/document/skills.md)
export interface SkillVersion {
  id: string;
  name: string;
  description: string;
  status: string;
  version: number;
  allowed_tools: string[];
  capabilities: string[];
  instructions_markdown: string;
}

// POST /skills/author 요청 (SkillAuthoringRequest)
export interface SkillAuthoringRequest {
  instruction: string;
  authoring_mode?: string;
  name?: string;
  description?: string;
  scope_type?: string;
  reference_document_ids?: string[];
}

// author / publish / PATCH 공통 응답 (초안·게시 결과)
export interface SkillAuthoringResult {
  skill_id: string;
  version_id: string;
  name: string;
  description: string;
  instructions_markdown: string;
  skill_markdown: string;
  scope_type: string;
  status: string;
  allowed_tools: string[];
  capabilities: string[];
  issues: unknown[];
  question: string | null;
}

// POST /skills/author/publish 요청 (SkillPublishRequest)
export interface SkillPublishRequest {
  name: string;
  description: string;
  instructions_markdown: string;
  scope_type: string;
  allowed_tools?: string[];
  capabilities?: string[];
}

// PATCH /skills/{skill_id} 요청 (SkillUpdateRequest)
export interface SkillUpdateRequest {
  name?: string;
  description?: string;
  instructions_markdown?: string;
}

export interface SkillResponse {
  id: string;
  workspace_id: string;
  owner_user_id: string;
  slug: string;
  scope_type: string; // "personal" | 워크스페이스 공용
  status: string;
  enabled_version: SkillVersion | null;
  latest_version: SkillVersion | null;
}
