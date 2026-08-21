import type { ChatMessageReferenceResponse } from "@/entities/chat/model/chat";
import type { GraphNode } from "@/entities/wiki/model/wiki";

/** 첫 글자만 대문자로 바꾼다. */
export function capitalize(value: string): string {
  return value.slice(0, 1).toUpperCase() + value.slice(1);
}

/**
 * wiki page id로 표시용 제목을 만든다.
 * 그래프 노드 라벨이 있으면 우선 사용하고, 없으면 slug를 Title Case로 변환한다.
 */
export function formatWikiPageTitle(
  pageId: string | undefined,
  nodes: GraphNode[] | undefined,
  fallback = "근거"
): string {
  if (!pageId) return fallback;
  if (nodes) {
    const node = nodes.find((n) => n.id === pageId);
    if (node?.label) return node.label;
  }
  const [, slug = pageId] = pageId.split(":");
  return slug
    .split("-")
    .filter(Boolean)
    .map(capitalize)
    .join(" ");
}

// 답변에 새어 나오는 내부 블록 참조. `doc:B0001` 또는 `session_…:uuid` 형식만 매칭해
// 숫자 citation([1])과 wikilink([[…]])는 건드리지 않는다.
const BLOCK_REF_SOURCE =
  "(?:[A-Za-z0-9_.-]+:)?(?:B\\d{4}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";
const BLOCK_REF_TOKEN = new RegExp(`\\s*\\[${BLOCK_REF_SOURCE}(?:\\s*,\\s*${BLOCK_REF_SOURCE})*\\]`, "g");

/** 문장 끝 마침표 뒤에 빈 줄을 넣어 답변 가독성을 높인다(소수점은 제외). */
export function formatAnswerMarkdown(content: string): string {
  return content.replace(BLOCK_REF_TOKEN, "").replace(/(?<!\d)\.(?!\d)\s+/g, ".\n\n");
}

/** citation 근거의 block id와 본문을 합쳐 메타 문자열을 만든다. */
export function formatReferenceMeta(reference: ChatMessageReferenceResponse): string {
  const blockLabel = reference.source_block_ids?.length ? reference.source_block_ids.join(", ") : null;
  const description = reference.text || "";

  return [blockLabel, description].filter(Boolean).join(" · ") || "관련 근거";
}

/** 답변 본문에 등장하는 `[1, 2]` 형태의 인용 rank 집합을 추출한다. */
export function citedRanks(content: string): Set<number> {
  const ranks = new Set<number>();
  for (const match of content.matchAll(/\[((?:\d+)(?:\s*,\s*\d+)*)\]/g)) {
    match[1].split(",").forEach((value) => {
      const rank = Number(value.trim());
      if (Number.isFinite(rank)) ranks.add(rank);
    });
  }
  return ranks;
}
