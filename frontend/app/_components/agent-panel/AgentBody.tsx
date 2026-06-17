import { ChevronDown } from "lucide-react";
import { MarkdownViewer } from "../MarkdownViewer";
import { AgentResultCard } from "./AgentResultCard";
import { StatusList } from "./StatusList";
import type { ChatMessageReferenceResponse, ChatMessageResponse } from "../../_lib/types";

export function AgentBody({
  messages,
  isLoading,
  queryErrorMessage,
  chatLoadErrorMessage,
  onOpenWikiPage
}: {
  messages: ChatMessageResponse[];
  isLoading: boolean;
  queryErrorMessage: string | null;
  chatLoadErrorMessage: string | null;
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
}) {
  const showAgentStatus = isLoading;

  function formatWikiPageTitle(pageId?: string, fallback = "근거") {
    if (!pageId) return fallback;
    const [, slug = pageId] = pageId.split(":");
    return slug
      .split("-")
      .filter(Boolean)
      .map((part) => part.slice(0, 1).toUpperCase() + part.slice(1))
      .join(" ");
  }

  function formatAnswerMarkdown(content: string) {
    return content.replace(/(?<!\d)\.(?!\d)\s+/g, ".\n\n");
  }

  function formatReferenceMeta(reference: ChatMessageReferenceResponse) {
    const pageLabel = typeof reference.page_number === "number" ? `p.${reference.page_number}` : null;
    const description = reference.quote || reference.page_role || "";

    return [pageLabel, description].filter(Boolean).join(" · ") || "관련 근거";
  }

  return (
    <div className="agent-body">
      {messages.map((message) => (
        message.role === "user" ? (
          <div className="question-bubble" key={message.id}>{message.content}</div>
        ) : (
          <div className="agent-thread" key={message.id}>
            <StatusList title="서치 명령 실행 중" isLoading={false} hasResponse />

            {message.references?.length > 0 && (
              <div className="results">
                <p>찾은 자료 {message.references.length}건</p>
                {message.references.slice(0, 3).map((reference) => {
                  const pageId = reference.wiki_page_id || (reference.document_id ? `source:${reference.document_id}` : null);
                  const pageType = pageId?.startsWith("concept:") ? "concept" : "source";
                  const title = formatWikiPageTitle(pageId ?? undefined, reference.document_id || "근거");

                  return (
                    <AgentResultCard
                      key={reference.id}
                      title={title}
                      meta={formatReferenceMeta(reference)}
                      pageType={pageType}
                      onClick={pageId ? () => onOpenWikiPage(pageId, title, pageType) : undefined}
                    />
                  );
                })}
              </div>
            )}

            <section className="agent-answer" aria-label="실행 중 발견 사항">
              <div className="answer-section-title">
                <span>실행 중 발견 사항</span>
                <ChevronDown size={8} />
              </div>
              <MarkdownViewer markdown={formatAnswerMarkdown(message.content)} />
            </section>
          </div>
        )
      ))}

      {showAgentStatus && <div className="agent-thread"><StatusList title="서치 명령 실행 중" isLoading={isLoading} hasResponse={false} /></div>}

      {queryErrorMessage && <p className="query-error">{queryErrorMessage}</p>}
      {chatLoadErrorMessage && <p className="query-error">{chatLoadErrorMessage}</p>}

      {isLoading && <div className="typing"><i /><i /><i /> 답변을 작성하고 있어요…</div>}
    </div>
  );
}
