import { apiFetch, parseJsonOrThrow, parseErrorResponse, getWorkspaceId, workspacePath, ERROR_MESSAGES } from "@/shared/api/client";
import { getSessionContext } from "@/entities/chat/api/chat";
import type { AiModelSelection } from "@/entities/ai";
import type { BackendData, QueryResponse, WikiGraphResponse, WikiPageDetailResponse } from "@/entities/wiki/model/wiki";
import type { DocumentListResponse } from "@/entities/document/model/document";

export async function fetchBackendData(): Promise<BackendData> {
  const workspaceId = getWorkspaceId();
  const [documentsResponse, graphResponse] = await Promise.all([
    apiFetch(workspacePath(workspaceId, "documents"), { cache: "no-store" }),
    apiFetch(workspacePath(workspaceId, "wiki", "graph"), { cache: "no-store" })
  ]);

  const documents = await parseJsonOrThrow<DocumentListResponse>(documentsResponse, ERROR_MESSAGES.documentsLoadFailed);
  const graph = await parseJsonOrThrow<WikiGraphResponse>(graphResponse, ERROR_MESSAGES.wikiGraphLoadFailed);
  return { documents: documents.documents ?? [], graph };
}

// SSE로 전달되는 질의 진행 단계 이벤트.
export type QueryStageEvent = { stage: string; message: string; sequence: number };

/** SSE 프레임(event/data 줄) 한 개를 파싱한다. heartbeat(':' 주석)는 무시한다. */
function parseSseFrame(raw: string): { event: string; data: unknown } | null {
  let event = "message";
  const dataLines: string[] = [];
  for (const line of raw.split("\n")) {
    if (line.startsWith(":")) continue;
    if (line.startsWith("event:")) event = line.slice("event:".length).trim();
    else if (line.startsWith("data:")) dataLines.push(line.slice("data:".length).replace(/^ /, ""));
  }
  if (dataLines.length === 0) return null;
  try {
    return { event, data: JSON.parse(dataLines.join("\n")) };
  } catch {
    return null;
  }
}

/**
 * 비동기 질의(run)를 시작하고 SSE로 진행 단계를 소비한 뒤 최종 결과를 반환한다.
 * 백엔드: POST /chat/sessions/{id}/query/runs → GET /api/query/runs/{id}/events(SSE) → GET /api/query/runs/{id}.
 * Authorization 헤더가 필요해 EventSource 대신 fetch 스트림으로 SSE를 읽는다.
 */
export async function runQueryStream(
  question: string,
  selection: AiModelSelection,
  handlers: { onStage: (event: QueryStageEvent) => void }
): Promise<QueryResponse> {
  const { workspaceId, sessionId } = await getSessionContext();

  const createResponse = await apiFetch(
    workspacePath(workspaceId, "chat", "sessions", sessionId, "query", "runs"),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      // provider/model은 백엔드 카탈로그 검증 대상이라 쌍으로 보내야 한다.
      // allow_web_search는 @NotNull 필수 필드이며, 웹 검색 토글 UI가 없으므로 안전 기본값 false를 명시한다.
      body: JSON.stringify({
        question,
        provider: selection.provider,
        model: selection.model,
        allow_web_search: false
      })
    }
  );
  const created = await parseJsonOrThrow<{ request_id: string; status: string }>(createResponse, ERROR_MESSAGES.queryFailed);
  const requestId = created.request_id;

  const eventsResponse = await apiFetch(`/api/query/runs/${encodeURIComponent(requestId)}/events`, {
    headers: { Accept: "text/event-stream" },
    cache: "no-store"
  });
  if (!eventsResponse.ok || !eventsResponse.body) {
    throw new Error(await parseErrorResponse(eventsResponse, ERROR_MESSAGES.queryFailed));
  }

  const reader = eventsResponse.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let failedError: string | null = null;
  let completed = false;

  try {
    while (!completed) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let separatorIndex = buffer.indexOf("\n\n");
      while (separatorIndex !== -1) {
        const frame = parseSseFrame(buffer.slice(0, separatorIndex));
        buffer = buffer.slice(separatorIndex + 2);
        if (frame?.event === "query.log") {
          const payload = frame.data as { stage?: string; message?: string; sequence?: number };
          handlers.onStage({ stage: payload.stage ?? "", message: payload.message ?? "", sequence: payload.sequence ?? 0 });
        } else if (frame?.event === "query.completed") {
          completed = true;
          break;
        } else if (frame?.event === "query.failed") {
          failedError = (frame.data as { error?: string }).error ?? null;
          completed = true;
          break;
        }
        separatorIndex = buffer.indexOf("\n\n");
      }
    }
  } finally {
    await reader.cancel().catch(() => undefined);
  }

  if (failedError) throw new Error(failedError);

  const statusResponse = await apiFetch(`/api/query/runs/${encodeURIComponent(requestId)}`, { cache: "no-store" });
  const status = await parseJsonOrThrow<{ result: QueryResponse | null }>(statusResponse, ERROR_MESSAGES.queryFailed);
  if (!status.result) throw new Error(ERROR_MESSAGES.queryFailed);
  return status.result;
}

export async function fetchWikiPage(pageId: string): Promise<WikiPageDetailResponse> {
  const workspaceId = getWorkspaceId();
  const response = await apiFetch(
    workspacePath(workspaceId, "wiki", "pages", pageId),
    { cache: "no-store" }
  );

  return parseJsonOrThrow<WikiPageDetailResponse>(response, ERROR_MESSAGES.wikiPageLoadFailed);
}
