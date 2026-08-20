export type ChatWikiExportStatus = "processing" | "skipped";

export function getChatExportSuccessMessage(status: ChatWikiExportStatus): string {
  return status === "skipped"
    ? "동일한 내용의 원문 문서가 있어 기존 문서를 열었습니다."
    : "채팅을 문서로 편입해 AI 처리 파이프라인에 전달했습니다.";
}
