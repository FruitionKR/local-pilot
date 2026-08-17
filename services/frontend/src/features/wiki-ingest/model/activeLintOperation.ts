import type { OperationLogItem } from "@/entities/operation-log";

/**
 * lint 진행 라벨.
 * 요청 직후에는 아직 로그가 안 보일 수 있어, 로컬 요청 상태만으로도 진행 중을 표시한다.
 */
export function formatLintProgressLabel(
  log: OperationLogItem | null,
  isRequested: boolean
): string | null {
  if (!log && !isRequested) return null;
  return "위키 다듬기 진행 중";
}
