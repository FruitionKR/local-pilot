import { apiFetch, parseJsonOrThrow, ERROR_MESSAGES } from "@/shared/api/client";
import { getSessionContext } from "@/entities/chat/api/chat";
import type { ChatWikiExportStatus } from "../lib/exportStatus";

export type ChatWikiExportResponse = {
  exportDocumentId: string;
  status: ChatWikiExportStatus;
};

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
