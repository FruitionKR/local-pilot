import type { DocumentItemResponse } from "@/entities/document";
import type { NoticePayload } from "./noticeBus";

export type FailedDocumentSummary = Pick<DocumentItemResponse, "filename" | "error_message">;

// 요약 메시지에 나열하는 파일명 상한. 넘치는 문서는 "외 N개"로 줄인다.
const MAX_LISTED_FILENAMES = 3;

// 대량 실패 시 문서 수만큼 카드가 쌓여 화면 밖으로 밀리지 않도록 실패 문서들을 알림 하나로 묶는다.
export function buildFailedDocumentsNotice(failedDocuments: FailedDocumentSummary[]): NoticePayload {
  if (failedDocuments.length === 1) {
    const [failed] = failedDocuments;
    return {
      kind: "failed",
      title: "문서 처리 실패",
      message: `"${failed.filename}" 처리에 실패했습니다. ${failed.error_message ?? "실패 사유를 확인해 주세요."}`
    };
  }

  const listed = failedDocuments
    .slice(0, MAX_LISTED_FILENAMES)
    .map((failed) => `"${failed.filename}"`)
    .join(", ");
  const restCount = failedDocuments.length - MAX_LISTED_FILENAMES;
  const names = restCount > 0 ? `${listed} 외 ${restCount}개` : listed;
  return {
    kind: "failed",
    title: "문서 처리 실패",
    message: `${failedDocuments.length}개 문서를 처리하지 못했습니다. (${names}) 실패 사유는 각 문서에서 확인해 주세요.`
  };
}
