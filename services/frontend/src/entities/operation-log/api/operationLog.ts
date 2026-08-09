import { apiFetch, getWorkspaceId, parseJsonOrThrow } from "@/shared/api/client";
import type { OperationLogDetail, OperationLogListResponse } from "../model/types";

/** AI 작업 로그 목록. 최신순, cursor 페이지네이션. */
export async function fetchOperationLogs(cursor?: string): Promise<OperationLogListResponse> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  const response = await apiFetch(`/api/workspaces/${getWorkspaceId()}/ai-operation-logs${query}`);
  return parseJsonOrThrow(response, "로그를 불러오지 못했습니다.");
}

/** AI 작업 로그 상세. 변경 리소스와 diff hunk를 포함한다. */
export async function fetchOperationLogDetail(operationId: string): Promise<OperationLogDetail> {
  const response = await apiFetch(
    `/api/workspaces/${getWorkspaceId()}/ai-operation-logs/${encodeURIComponent(operationId)}`
  );
  return parseJsonOrThrow(response, "로그 상세를 불러오지 못했습니다.");
}
