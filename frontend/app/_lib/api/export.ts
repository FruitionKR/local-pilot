import { apiFetch, parseErrorResponse, parseJsonOrThrow, ERROR_MESSAGES } from "./client";
import { getSessionContext } from "./chat";

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

/** 미리보기를 수락하면 채팅 내용을 위키 문서로 내보낸다. */
export async function exportChatWiki(): Promise<ChatWikiExportResponse> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/wiki`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ selection_mode: "full", pair_ids: [] })
    }
  );
  return parseJsonOrThrow<ChatWikiExportResponse>(response, ERROR_MESSAGES.wikiExportFailed);
}
