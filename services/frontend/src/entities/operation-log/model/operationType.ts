import type { OperationType } from "./types";

/** 작업 유형 표시 문구. 로그 사이드바 항목과 상세 헤더가 함께 쓴다. */
export const OPERATION_TYPE_LABELS: Record<OperationType, string> = {
  document_edit: "AI 편집 반영",
  ingest: "위키 페이지 생성",
  lint: "위키 다듬기",
  restore: "롤백"
};
