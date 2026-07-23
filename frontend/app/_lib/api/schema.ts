import { createClientId } from "../tree";
import type { SchemaFragments, SchemaIssue, WikiSchema, WikiSchemaPreview } from "../types/schema";

// [임시 목업] wiki-schema Java 프록시가 아직 없으므로 클라이언트 목업으로 화면을 구동한다.
// 실제 배선 시 이 파일의 함수 본문만 apiFetch 호출로 교체하면 된다(시그니처 유지).
// 상호참조: docs/issue/backend/2026-07-23.md(Java 프록시), docs/issue/ai/2026-07-23.md(agent 주입)
const STORAGE_KEY = "fruition.wiki_schema.mock.v1";
const MOCK_LATENCY_MS = 250;

// 프롬프트 인젝션/비밀정보 후보를 잡아내는 최소 규칙. 실제 organizer의 보안 분류를 흉내낸다.
const ISSUE_RULES: { pattern: RegExp; severity: SchemaIssue["severity"]; category: string; reason: string }[] = [
  { pattern: /ignore (all |the )?(previous|above)|이전 지시.*무시|시스템 프롬프트/i, severity: "blocked", category: "instruction_override", reason: "기존 지시를 무시하도록 유도합니다." },
  { pattern: /api[_ ]?key|secret|password|비밀번호|토큰/i, severity: "blocked", category: "secret", reason: "비밀 정보가 스킬 본문에 포함되어 있습니다." },
  { pattern: /you are now|act as|역할을.*변경|관리자 권한/i, severity: "blocked", category: "role_override", reason: "역할/권한을 임의로 바꾸려 합니다." },
  { pattern: /maybe|아마|가능하면|알아서/i, severity: "unclear", category: "unclear_preference", reason: "지시가 모호해 해석이 필요합니다." }
];

function delay(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, MOCK_LATENCY_MS));
}

function readStore(): WikiSchema[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? (JSON.parse(raw) as WikiSchema[]) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function writeStore(schemas: WikiSchema[]): void {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(schemas));
}

// 헤딩 텍스트에 feature 키워드가 있으면 해당 조각으로, 없으면 global로 분류한다.
function organize(rawMarkdown: string): SchemaFragments {
  const buckets: Record<keyof SchemaFragments, string[]> = {
    globalMarkdown: [],
    queryMarkdown: [],
    ingestMarkdown: [],
    editMarkdown: [],
    conceptMarkdown: [],
    templateMarkdown: []
  };
  let current: keyof SchemaFragments = "globalMarkdown";
  for (const line of rawMarkdown.split("\n")) {
    const heading = line.match(/^#{1,6}\s+(.*)$/);
    if (heading) {
      const title = heading[1].toLowerCase();
      if (/query|질의|검색/.test(title)) current = "queryMarkdown";
      else if (/ingest|수집|업로드/.test(title)) current = "ingestMarkdown";
      else if (/edit|편집|수정/.test(title)) current = "editMarkdown";
      else if (/concept|개념|용어/.test(title)) current = "conceptMarkdown";
      else if (/template|템플릿|형식/.test(title)) current = "templateMarkdown";
      else current = "globalMarkdown";
    }
    buckets[current].push(line);
  }
  return {
    globalMarkdown: buckets.globalMarkdown.join("\n").trim(),
    queryMarkdown: buckets.queryMarkdown.join("\n").trim(),
    ingestMarkdown: buckets.ingestMarkdown.join("\n").trim(),
    editMarkdown: buckets.editMarkdown.join("\n").trim(),
    conceptMarkdown: buckets.conceptMarkdown.join("\n").trim(),
    templateMarkdown: buckets.templateMarkdown.join("\n").trim()
  };
}

function detectIssues(rawMarkdown: string): SchemaIssue[] {
  const issues: SchemaIssue[] = [];
  for (const line of rawMarkdown.split("\n")) {
    const text = line.trim();
    if (!text) continue;
    for (const rule of ISSUE_RULES) {
      if (rule.pattern.test(text)) {
        issues.push({ severity: rule.severity, category: rule.category, text, reason: rule.reason, section: null });
        break;
      }
    }
  }
  return issues;
}

function buildPreview(rawMarkdown: string): WikiSchemaPreview {
  const fragments = organize(rawMarkdown);
  const issues = detectIssues(rawMarkdown);
  return {
    fragments,
    issues,
    previewMarkdown: fragments.globalMarkdown || rawMarkdown.trim(),
    hasBlockedIssues: issues.some((issue) => issue.severity === "blocked")
  };
}

export async function previewWikiSchema(rawMarkdown: string): Promise<WikiSchemaPreview> {
  await delay();
  return buildPreview(rawMarkdown);
}

export async function createWikiSchemaDraft(rawMarkdown: string, name: string): Promise<WikiSchema> {
  await delay();
  const preview = buildPreview(rawMarkdown);
  const now = new Date().toISOString();
  const draft: WikiSchema = {
    id: createClientId("schema"),
    name: name.trim() || "default",
    rawMarkdown,
    status: "draft",
    fragments: preview.fragments,
    issues: preview.issues,
    previewMarkdown: preview.previewMarkdown,
    hasBlockedIssues: preview.hasBlockedIssues,
    createdAt: now,
    updatedAt: now,
    activatedAt: null
  };
  writeStore([draft, ...readStore()]);
  return draft;
}

export async function activateWikiSchema(id: string): Promise<WikiSchema> {
  await delay();
  const now = new Date().toISOString();
  let activated: WikiSchema | null = null;
  // 한 워크스페이스에 활성 스킬은 하나. 대상만 active, 나머지 active는 draft로 되돌린다.
  const next = readStore().map((schema): WikiSchema => {
    if (schema.id === id) {
      activated = { ...schema, status: "active", activatedAt: now, updatedAt: now };
      return activated;
    }
    return schema.status === "active" ? { ...schema, status: "draft", updatedAt: now } : schema;
  });
  if (!activated) throw new Error("활성화할 스킬을 찾을 수 없습니다.");
  writeStore(next);
  return activated;
}

export async function listWikiSchemas(): Promise<WikiSchema[]> {
  await delay();
  return readStore();
}

export async function getActiveWikiSchema(): Promise<WikiSchema | null> {
  await delay();
  return readStore().find((schema) => schema.status === "active") ?? null;
}
