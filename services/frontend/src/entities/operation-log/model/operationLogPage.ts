import type { OperationLogItem } from "./types";

/**
 * 최신순 로그 목록의 다음 페이지를 이어붙인다.
 * 커서 경계에서 같은 operation_id가 다시 오면 한 번만 남긴다.
 */
export function appendLogPage(
  previous: OperationLogItem[],
  incoming: OperationLogItem[]
): OperationLogItem[] {
  const seen = new Set(previous.map((item) => item.operation_id));
  const added = incoming.filter((item) => !seen.has(item.operation_id));
  return added.length ? [...previous, ...added] : previous;
}

/**
 * 목록에서 상세로 띄울 작업 하나를 고른다.
 * 이미 고른 작업이 목록에 남아 있으면 유지하고, 없으면 가장 최근 작업을 고른다.
 */
export function pickSelectedOperationId(
  items: OperationLogItem[],
  current: string | null
): string | null {
  if (current && items.some((item) => item.operation_id === current)) return current;
  return items[0]?.operation_id ?? null;
}
