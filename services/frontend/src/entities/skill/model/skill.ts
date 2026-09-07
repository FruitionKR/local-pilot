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
