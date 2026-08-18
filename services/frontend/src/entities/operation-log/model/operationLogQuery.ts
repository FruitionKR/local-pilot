import type { OperationStatus, OperationType } from "./types";

export type OperationLogQuery = {
  cursor?: string;
  /** 백엔드 type 파라미터. 비우면 모든 유형을 받는다. */
  type?: OperationType;
  /** 백엔드 status 파라미터. 비우면 모든 상태를 받는다. */
  status?: OperationStatus;
  /** 백엔드 size 파라미터. 비우면 기본 20건이며 최대는 100건이다. */
  size?: number;
};

/** 작업 로그 목록 쿼리스트링. 값이 없는 조건은 아예 보내지 않는다. */
export function buildOperationLogQuery({
  cursor,
  type,
  status,
  size
}: OperationLogQuery = {}): string {
  const params = new URLSearchParams();
  if (type) params.set("type", type);
  if (status) params.set("status", status);
  if (cursor) params.set("cursor", cursor);
  if (size !== undefined) params.set("size", String(size));
  const query = params.toString();
  return query ? `?${query}` : "";
}
