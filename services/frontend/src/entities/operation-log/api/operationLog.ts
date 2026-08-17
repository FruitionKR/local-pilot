import { apiFetch, getWorkspaceId, parseJsonOrThrow } from "@/shared/api/client";
import type {
  OperationLogDetail,
  OperationLogListResponse,
  RestoreExecuteResponse,
  RestorePreviewResponse
} from "../model/types";
import { buildOperationLogQuery, type OperationLogQuery } from "../model/operationLogQuery";

/** AI 작업 로그 목록. 최신순, cursor 페이지네이션. */
export async function fetchOperationLogs(
  query: OperationLogQuery = {}
): Promise<OperationLogListResponse> {
  const response = await apiFetch(
    `/api/workspaces/${getWorkspaceId()}/ai-operation-logs${buildOperationLogQuery(query)}`
  );
  return parseJsonOrThrow(response, "로그를 불러오지 못했습니다.");
}

/** AI 작업 로그 상세. 변경 리소스와 diff hunk를 포함한다. */
export async function fetchOperationLogDetail(operationId: string): Promise<OperationLogDetail> {
  const response = await apiFetch(
    `/api/workspaces/${getWorkspaceId()}/ai-operation-logs/${encodeURIComponent(operationId)}`
  );
  return parseJsonOrThrow(response, "로그 상세를 불러오지 못했습니다.");
}

/** 복구 실행에 필요한 현재 계획과 서명 토큰을 가져온다. */
export async function fetchRestorePreview(operationId: string): Promise<RestorePreviewResponse> {
  const response = await apiFetch(
    `/api/workspaces/${getWorkspaceId()}/ai-operation-logs/${encodeURIComponent(operationId)}/restore-preview`
  );
  return parseJsonOrThrow(response, "롤백 대상을 확인하지 못했습니다.");
}

/** 미리보기에서 받은 토큰으로 작업을 롤백한다. */
export async function restoreOperation(operationId: string, previewToken: string): Promise<RestoreExecuteResponse> {
  const response = await apiFetch(
    `/api/workspaces/${getWorkspaceId()}/ai-operation-logs/${encodeURIComponent(operationId)}/restore`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ preview_token: previewToken })
    }
  );
  return parseJsonOrThrow(response, "롤백 요청에 실패했습니다.");
}
