import type { OperationLogItem } from "../../../entities/operation-log/model/types";

export function formatElapsedMinutes(startedAt: string, now = Date.now()): string {
  const elapsedMinutes = Math.max(0, Math.floor((now - Date.parse(startedAt)) / 60_000));
  return `${elapsedMinutes}분째 실행 중`;
}

/**
 * lint 진행 라벨.
 * 요청 직후에는 아직 로그가 안 보일 수 있어, 로컬 요청 상태만으로도 진행 중을 표시한다.
 */
export function formatLintProgressLabel(
  log: OperationLogItem | null,
  isRequested: boolean,
  now = Date.now()
): string | null {
  if (!log && !isRequested) return null;
  return log
    ? `Lint · ${formatElapsedMinutes(log.created_at, now)}`
    : "Lint 진행 중";
}
