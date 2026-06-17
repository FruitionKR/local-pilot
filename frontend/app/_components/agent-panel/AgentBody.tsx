import { ChevronDown } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { MarkdownViewer } from "../MarkdownViewer";
import { AgentResultCard } from "./AgentResultCard";
import { StatusList } from "./StatusList";
import type { ChatMessageReferenceResponse, ChatMessageResponse } from "../../_lib/types";

export function AgentBody({
  messages,
  isLoading,
  queryErrorMessage,
  chatLoadErrorMessage,
  animatedMessageId,
  onOpenWikiPage
}: {
  messages: ChatMessageResponse[];
  isLoading: boolean;
  queryErrorMessage: string | null;
  chatLoadErrorMessage: string | null;
  animatedMessageId: string | null;
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
}) {
  const showAgentStatus = isLoading;
  const [visibleAnswerStage, setVisibleAnswerStage] = useState(3);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const hasScrolledInitialMessagesRef = useRef(false);
  const scrollAnimationRef = useRef<number | null>(null);

  function scrollToLatestMessage({ immediate = false } = {}) {
    const body = bodyRef.current;
    if (!body) return;
    const scrollBody = body;

    if (scrollAnimationRef.current !== null) {
      window.cancelAnimationFrame(scrollAnimationRef.current);
    }

    const startTop = scrollBody.scrollTop;
    const targetTop = scrollBody.scrollHeight - scrollBody.clientHeight;
    const distance = targetTop - startTop;

    if (distance <= 1) return;
    if (immediate) {
      scrollBody.scrollTop = targetTop;
      return;
    }

    const duration = 1100;
    const startTime = performance.now();

    function animateScroll(now: number) {
      const elapsed = Math.min((now - startTime) / duration, 1);
      const eased = elapsed < 0.5
        ? 4 * elapsed * elapsed * elapsed
        : 1 - Math.pow(-2 * elapsed + 2, 3) / 2;

      scrollBody.scrollTop = startTop + distance * eased;

      if (elapsed < 1) {
        scrollAnimationRef.current = window.requestAnimationFrame(animateScroll);
        return;
      }

      scrollAnimationRef.current = null;
    }

    scrollAnimationRef.current = window.requestAnimationFrame(animateScroll);
  }

  useEffect(() => {
    if (!animatedMessageId) {
      setVisibleAnswerStage(3);
      return;
    }

    setVisibleAnswerStage(1);
    const revealResults = window.setTimeout(() => setVisibleAnswerStage(2), 1000);
    const revealAnswer = window.setTimeout(() => setVisibleAnswerStage(3), 2000);

    return () => {
      window.clearTimeout(revealResults);
      window.clearTimeout(revealAnswer);
    };
  }, [animatedMessageId]);

  useEffect(() => {
    if (!animatedMessageId && !isLoading && !queryErrorMessage) return;

    const frameId = window.requestAnimationFrame(() => scrollToLatestMessage());
    return () => window.cancelAnimationFrame(frameId);
  }, [animatedMessageId, visibleAnswerStage, isLoading, queryErrorMessage]);

  useEffect(() => {
    if (messages.length === 0 || animatedMessageId || isLoading || queryErrorMessage) return;
    if (hasScrolledInitialMessagesRef.current) return;

    hasScrolledInitialMessagesRef.current = true;
    const frameId = window.requestAnimationFrame(() => scrollToLatestMessage({ immediate: true }));
    return () => window.cancelAnimationFrame(frameId);
  }, [messages.length, animatedMessageId, isLoading, queryErrorMessage]);

  useEffect(() => () => {
    if (scrollAnimationRef.current !== null) {
      window.cancelAnimationFrame(scrollAnimationRef.current);
    }
  }, []);

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
    <div className="agent-body" ref={bodyRef}>
      {messages.map((message) => (
        message.role === "user" ? (
          <div className="question-bubble" key={message.id}>{message.content}</div>
        ) : (
          <div className="agent-thread" key={message.id}>
            {(message.id !== animatedMessageId || visibleAnswerStage >= 1) && (
              <div className={message.id === animatedMessageId ? "agent-stage" : undefined}>
                <StatusList title="서치 명령 실행 중" isLoading={false} hasResponse />
              </div>
            )}

            {message.references?.length > 0 && (message.id !== animatedMessageId || visibleAnswerStage >= 2) && (
              <div className={`results ${message.id === animatedMessageId ? "agent-stage" : ""}`}>
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

            {(message.id !== animatedMessageId || visibleAnswerStage >= 3) && (
              <section className={`agent-answer ${message.id === animatedMessageId ? "agent-stage" : ""}`} aria-label="실행 중 발견 사항">
                <div className="answer-section-title">
                  <span>실행 중 발견 사항</span>
                  <ChevronDown size={8} />
                </div>
                <MarkdownViewer markdown={formatAnswerMarkdown(message.content)} />
              </section>
            )}
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
