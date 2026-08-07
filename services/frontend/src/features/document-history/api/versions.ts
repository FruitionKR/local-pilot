import { apiFetch, parseErrorResponse, parseJsonOrThrow, getWorkspaceId } from "@/shared/api/client";
import type { ServerDiffHunk } from "../lib/versionDiff";

/** 복원 기준 버전(base_version)이 이미 지나가 서버가 409를 반환한 경우. */
export class VersionRestoreConflictError extends Error {}

export type DocumentVersionItem = {
  version: number;
  content_hash: string;
  created_by: string;
  created_at: string;
};

export type DocumentVersionListResponse = {
  document_id: string;
  current_version: number;
  versions: DocumentVersionItem[];
};

export type DocumentVersionDiffResponse = {
  document_id: string;
  from_version: number;
  to_version: number;
  additions: number;
  deletions: number;
  hunks: ServerDiffHunk[];
};

type DocumentContentSaveResponse = {
  document_id: string;
  current_version: number;
  content_hash: string;
  updated_at: string;
  changed: boolean;
};

function documentPath(documentId: string): string {
  const workspaceId = getWorkspaceId();
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/documents/${encodeURIComponent(documentId)}`;
}

/** 서버에 저장된 문서 버전 이력 목록(메타데이터만, 최신 순)을 조회한다. */
export async function fetchDocumentVersions(documentId: string): Promise<DocumentVersionListResponse> {
  const response = await apiFetch(`${documentPath(documentId)}/versions`, { cache: "no-store" });
  return parseJsonOrThrow<DocumentVersionListResponse>(response, "버전 이력을 불러오지 못했습니다.");
}

/** 두 저장 버전 사이의 서버 계산 diff를 조회한다. */
export async function fetchDocumentVersionDiff(
  documentId: string,
  fromVersion: number,
  toVersion: number
): Promise<DocumentVersionDiffResponse> {
  const query = `from_version=${fromVersion}&to_version=${toVersion}`;
  const response = await apiFetch(`${documentPath(documentId)}/diff?${query}`, { cache: "no-store" });
  return parseJsonOrThrow<DocumentVersionDiffResponse>(response, "버전 비교 결과를 불러오지 못했습니다.");
}

/**
 * 과거 버전을 새 버전으로 비파괴 복원한다.
 * base_version이 현재 버전과 다르면 서버가 409를 반환하며 VersionRestoreConflictError를 던진다.
 */
export async function restoreDocumentVersion(
  documentId: string,
  version: number,
  baseVersion: number
): Promise<DocumentContentSaveResponse> {
  const response = await apiFetch(`${documentPath(documentId)}/versions/${version}/restore`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base_version: baseVersion })
  });
  if (response.status === 409) {
    throw new VersionRestoreConflictError(
      await parseErrorResponse(response, "다른 편집 내용이 먼저 저장되어 복원하지 못했습니다.")
    );
  }
  return parseJsonOrThrow<DocumentContentSaveResponse>(response, "버전 복원에 실패했습니다.");
}
