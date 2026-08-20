// 스킬(스키마) 도메인 타입. llmPipeline wiki_schema 계약을 프론트 형태(camelCase)로 옮긴 것.
// 계약 원본: services/ai/pipeline/app/modules/wiki_schema/interfaces/http/schemas.py, domain/entities.py
export type SchemaFeature = "query" | "ingest" | "edit" | "concept" | "template";
export type SchemaStatus = "draft" | "active" | "rejected";
export type SchemaIssueSeverity = "blocked" | "unclear";

export type SchemaFragments = {
  globalMarkdown: string;
  queryMarkdown: string;
  ingestMarkdown: string;
  editMarkdown: string;
  conceptMarkdown: string;
  templateMarkdown: string;
};

export type SchemaIssue = {
  severity: SchemaIssueSeverity;
  category: string;
  text: string;
  reason: string;
  section: string | null;
};

export type WikiSchemaPreview = {
  fragments: SchemaFragments;
  issues: SchemaIssue[];
  previewMarkdown: string;
  hasBlockedIssues: boolean;
};

export type WikiSchema = {
  id: string;
  name: string;
  rawMarkdown: string;
  status: SchemaStatus;
  fragments: SchemaFragments;
  issues: SchemaIssue[];
  previewMarkdown: string;
  hasBlockedIssues: boolean;
  createdAt: string | null;
  updatedAt: string | null;
  activatedAt: string | null;
};
