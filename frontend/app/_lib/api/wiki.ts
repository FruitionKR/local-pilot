import { apiFetch, parseJsonOrThrow, getWorkspaceId, ERROR_MESSAGES } from "./client";
import { getSessionContext } from "./chat";
import type { BackendData, DocumentListResponse, QueryResponse, WikiGraphResponse, WikiPageDetailResponse } from "../types";

export async function fetchBackendData(): Promise<BackendData> {
  const workspaceId = getWorkspaceId();
  const [documentsResponse, graphResponse] = await Promise.all([
    apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/documents`, { cache: "no-store" }),
    apiFetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/wiki/graph`, { cache: "no-store" })
  ]);

  const documents = await parseJsonOrThrow<DocumentListResponse>(documentsResponse, ERROR_MESSAGES.documentsLoadFailed);
  const graph = await parseJsonOrThrow<WikiGraphResponse>(graphResponse, ERROR_MESSAGES.wikiGraphLoadFailed);
  return { documents: documents.documents ?? [], graph };
}

export async function queryWiki(question: string): Promise<QueryResponse> {
  const { workspaceId, sessionId } = await getSessionContext();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/chat/sessions/${encodeURIComponent(sessionId)}/query`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question })
    }
  );

  return parseJsonOrThrow<QueryResponse>(response, ERROR_MESSAGES.queryFailed);
}

export async function fetchWikiPage(pageId: string): Promise<WikiPageDetailResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    `/api/workspaces/${encodeURIComponent(workspaceId)}/wiki/pages/${encodeURIComponent(pageId)}`,
    { cache: "no-store" }
  );

  return parseJsonOrThrow<WikiPageDetailResponse>(response, ERROR_MESSAGES.wikiPageLoadFailed);
}
