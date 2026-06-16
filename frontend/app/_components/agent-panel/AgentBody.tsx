import { sparkleIcon, SvgIcon } from "../SvgIcon";
import { MarkdownViewer } from "../MarkdownViewer";
import { AgentResultCard } from "./AgentResultCard";
import { StatusList } from "./StatusList";
import type { ChatMessageResponse } from "../../_lib/types";

export function AgentBody({
  messages,
  isLoading,
  errorMessage,
  onOpenWikiPage
}: {
  messages: ChatMessageResponse[];
  isLoading: boolean;
  errorMessage: string | null;
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
}) {
  const showAgentStatus = Boolean(isLoading || errorMessage);

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

  return (
    <div className="agent-body">
      {messages.map((message) => (
        message.role === "user" ? (
          <div className="question-bubble" key={message.id}>{message.content}</div>
        ) : (
          <div className="agent-thread" key={message.id}>
            <div className="agent-message">
              <div className="mini-mark"><SvgIcon src={sparkleIcon} /></div>
              <div>
                <strong>Fruition Agent</strong>
                <p>Wiki page 근거로 답변을 생성했어요</p>
              </div>
            </div>
            <StatusList title="서치 명령 실행 중" isLoading={false} hasResponse />
            <div className="agent-answer">
              <MarkdownViewer markdown={formatAnswerMarkdown(message.content)} />
            </div>

            {message.references?.length > 0 && (
              <div className="results">
                <p>근거 {message.references.length}건</p>
                {message.references.slice(0, 3).map((reference) => {
                  const pageId = reference.wiki_page_id || (reference.document_id ? `source:${reference.document_id}` : null);
                  const pageType = pageId?.startsWith("concept:") ? "concept" : "source";
                  const title = formatWikiPageTitle(pageId ?? undefined, reference.document_id || "근거");

                  return (
                    <AgentResultCard
                      key={reference.id}
                      title={title}
                      meta={reference.quote || reference.page_role || ""}
                      pageType={pageType}
                      onClick={pageId ? () => onOpenWikiPage(pageId, title, pageType) : undefined}
                    />
                  );
                })}
              </div>
            )}
          </div>
        )
      ))}

      {showAgentStatus && (
        <div className="agent-message">
          <div className="mini-mark"><SvgIcon src={sparkleIcon} /></div>
          <div>
            <strong>Fruition Agent</strong>
            <p>{isLoading ? "요청을 분석하고 자료를 검색하고 있어요" : "질의 처리 중 문제가 발생했습니다"}</p>
          </div>
        </div>
      )}

      {showAgentStatus && <StatusList title="서치 명령 실행 중" isLoading={isLoading} hasResponse={false} />}

      {errorMessage && <p className="query-error">{errorMessage}</p>}

      {isLoading && <div className="typing"><i /><i /><i /> 답변을 작성하고 있어요...</div>}
    </div>
  );
}
