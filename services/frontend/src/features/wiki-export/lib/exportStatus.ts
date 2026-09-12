export type ChatWikiExportStatus = "saved" | "skipped";

export function getChatExportSuccessMessage(status: ChatWikiExportStatus): string {
  return status === "skipped"
    ? "동일한 내용의 원문 문서가 있어 기존 문서를 열었습니다."
    : "채팅을 원문 문서로 저장했습니다. 위키에 반영하려면 Ingest에서 문서를 선택해 주세요.";
}
