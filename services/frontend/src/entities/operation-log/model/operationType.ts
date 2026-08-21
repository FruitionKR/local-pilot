import type { OperationType } from "./types";

/** 작업 유형 표시 문구. 로그 사이드바 항목과 상세 헤더가 함께 쓴다. */
export const OPERATION_TYPE_LABELS: Record<OperationType, string> = {
  document_edit: "AI 편집 반영",
  ingest: "위키 페이지 생성",
  lint: "Lint",
  restore: "롤백"
};

/** Ingest는 작업 종류 대신 시작 시점의 문서 이름을 제목으로 쓴다. */
export function formatOperationLogTitle(
  item: { operation_type: OperationType; target_display_name?: string | null }
): string {
  return item.operation_type === "ingest" && item.target_display_name?.trim()
    ? item.target_display_name
    : OPERATION_TYPE_LABELS[item.operation_type];
}
