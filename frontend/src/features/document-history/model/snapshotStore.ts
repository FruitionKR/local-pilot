import { createClientId } from "@/entities/tree/lib/guards";

// 문서 편집 시점별 클라이언트측 스냅샷.
// 백엔드 영속 저장(document_content_versions)은 미구현이라, 임시로 localStorage에 저장한다.
// 상호참조: docs/issue/backend/2026-07-23.md, docs/issue/frontend/2026-07-23.md
export type DocumentSnapshot = {
  id: string;
  documentId: string;
  label: string;
  markdown: string;
  createdAt: number;
};

const STORAGE_PREFIX = "fruition.snapshots.v1.";
// 무라벨 무한 목록은 anti-pattern이므로 문서별 최근 30건만 유지한다.
const MAX_SNAPSHOTS_PER_DOCUMENT = 30;

function storageKey(documentId: string): string {
  return `${STORAGE_PREFIX}${documentId}`;
}

export function readSnapshots(documentId: string): DocumentSnapshot[] {
  if (typeof window === "undefined" || !documentId) return [];
  try {
    const raw = window.localStorage.getItem(storageKey(documentId));
    if (!raw) return [];
    const parsed = JSON.parse(raw) as DocumentSnapshot[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    // 손상된 저장값은 조용히 무시하고 빈 목록으로 시작한다.
    return [];
  }
}

function persist(documentId: string, snapshots: DocumentSnapshot[]): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(storageKey(documentId), JSON.stringify(snapshots));
  } catch {
    // 저장 용량 초과 등은 무시한다. 화면 동작은 메모리 상태로 유지된다.
  }
}

// 새 스냅샷을 앞쪽(최신)에 추가한 새 배열을 반환한다. 기존 배열은 변경하지 않는다.
export function appendSnapshot(
  current: DocumentSnapshot[],
  documentId: string,
  markdown: string,
  label: string
): DocumentSnapshot[] {
  const snapshot: DocumentSnapshot = {
    id: createClientId("snap"),
    documentId,
    label,
    markdown,
    createdAt: Date.now()
  };
  const next = [snapshot, ...current].slice(0, MAX_SNAPSHOTS_PER_DOCUMENT);
  persist(documentId, next);
  return next;
}
