import type { DocumentItemResponse } from "@/entities/document/model/document";

/**
 * 문서가 위키에 반영된 정도.
 * - processing: 반영 작업이 진행 중이라 새 요청을 받을 수 없다.
 * - changed: 반영 이후 편집돼 재반영이 필요하다.
 * - not-included: 아직 한 번도 반영되지 않았다.
 * - retry: 직전 반영이 실패해 다시 시도할 수 있다.
 * - up-to-date: 최신 내용이 이미 반영돼 있다.
 */
export type WikiReflectState = "processing" | "changed" | "not-included" | "retry" | "up-to-date";

const WIKI_REFLECT_LABELS: Partial<Record<WikiReflectState, string>> = {
  changed: "수정됨",
  "not-included": "신규",
  retry: "재시도"
};

/**
 * 반영 작업이 아직 끝나지 않은 처리 단계.
 * stalled는 heartbeat가 60초 넘게 끊긴 상태일 뿐 실패 확정이 아니라서 진행 중으로 본다.
 * 진행 중으로 두어야 같은 문서에 반영 요청이 다시 나가지 않는다.
 */
const ACTIVE_PROCESSING_STATES = ["starting", "running", "stalled"] as const;

export function getWikiReflectState(document: DocumentItemResponse): WikiReflectState {
  // 진행 중 판정이 가장 앞선다. needs_reingest가 켜져 있어도 새 요청을 받을 수 없다.
  const isActive =
    document.status === "processing" ||
    (document.processing_state !== undefined &&
      (ACTIVE_PROCESSING_STATES as readonly string[]).includes(document.processing_state));
  if (isActive) return "processing";

  if (document.needs_reingest === true) return "changed";

  if (document.status === "uploaded") return "not-included";
  if (document.status === "failed") return "retry";

  return "up-to-date";
}

/**
 * 위키 반영이 실제로 돌고 있는 문서.
 * 문서 목록은 새로고침해도 백엔드에서 다시 내려오므로 진행 상태가 유실되지 않는다.
 */
export function selectActiveIngestDocuments(
  documents: DocumentItemResponse[]
): DocumentItemResponse[] {
  return documents.filter((document) => getWikiReflectState(document) === "processing");
}

export function isWikiReflectEligible(document: DocumentItemResponse): boolean {
  const state = getWikiReflectState(document);
  return state !== "processing" && state !== "up-to-date";
}

export function getWikiReflectLabel(document: DocumentItemResponse): string | null {
  return WIKI_REFLECT_LABELS[getWikiReflectState(document)] ?? null;
}

export function isLintActionEnabled({
  needsLint,
  isIngestActive,
  isLintActive
}: {
  needsLint: boolean;
  isIngestActive: boolean;
  isLintActive: boolean;
}): boolean {
  return needsLint && !isIngestActive && !isLintActive;
}
