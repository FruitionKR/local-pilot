import { apiFetch, parseErrorResponse, parseJsonOrThrow, ERROR_MESSAGES } from "@/shared/api/client";
import { getSessionContext } from "@/entities/chat/api/chat";

export type ChatWikiExportResponse = {
  exportDocumentId: string;
  status: string;
};

/** 현재 채팅 세션 내용을 위키 문서로 내보내기 전 미리보기 Markdown을 받는다. */
export async function fetchChatWikiExportPreview(): Promise<string> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/wiki/preview`,
    { method: "POST" }
  );
  if (!response.ok) {
    throw new Error(await parseErrorResponse(response, ERROR_MESSAGES.wikiExportFailed));
  }
  return response.text();
}

/**
 * 미리보기를 수락하면 채팅 내용을 위키 문서로 내보낸다.
 * pairIds가 비어 있으면 세션 전체(full), 있으면 선택 문답만(partial) 편입한다.
 */
export async function exportChatWiki(pairIds: string[] = []): Promise<ChatWikiExportResponse> {
  const { workspaceId, sessionId } = await getSessionContext();
  const isPartial = pairIds.length > 0;
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/wiki`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        selection_mode: isPartial ? "partial" : "full",
        pair_ids: isPartial ? pairIds : []
      })
    }
  );
  return parseJsonOrThrow<ChatWikiExportResponse>(response, ERROR_MESSAGES.wikiExportFailed);
}
