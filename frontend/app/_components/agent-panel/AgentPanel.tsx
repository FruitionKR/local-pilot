"use client";

import { useState } from "react";
import { AgentBody } from "./AgentBody";
import { AgentComposer } from "./AgentComposer";
import { AgentHeader } from "./AgentHeader";
import { useChatThread } from "./useChatThread";
import type { GraphNode, SourceBlockHighlight } from "../../_lib/types";

// AgentBody 등이 이 파일에서 ActiveAgentTurn을 import하므로 re-export 유지
export type { ActiveAgentTurn } from "./useChatThread";

export function AgentPanel({
  onClose,
  onOpenWikiPage,
  onOpenSourceBlocks,
  nodes
}: {
  onClose: () => void;
  onOpenWikiPage: (pageId: string, title: string, pageType: string) => void;
  onOpenSourceBlocks: (documentId: string, title: string, highlights: SourceBlockHighlight[]) => void;
  nodes?: GraphNode[];
}) {
  const [composerValue, setComposerValue] = useState("");
  const { messages, queryErrorMessage, chatLoadErrorMessage, animatedMessageId, activeTurn, isLoading, submitQuery } = useChatThread();

  function handleSubmit() {
    const question = composerValue.trim();
    if (!question) return;
    setComposerValue("");
    void submitQuery(question);
  }

  return (
    <aside className="agent-panel" onClick={(event) => event.stopPropagation()}>
      <AgentHeader onClose={onClose} />
      <AgentBody
        messages={messages}
        isLoading={isLoading}
        activeTurn={activeTurn}
        queryErrorMessage={queryErrorMessage}
        chatLoadErrorMessage={chatLoadErrorMessage}
        animatedMessageId={animatedMessageId}
        onOpenWikiPage={onOpenWikiPage}
        onOpenSourceBlocks={onOpenSourceBlocks}
        nodes={nodes}
      />
      <AgentComposer value={composerValue} isLoading={isLoading} onChange={setComposerValue} onSubmit={handleSubmit} />
    </aside>
  );
}
