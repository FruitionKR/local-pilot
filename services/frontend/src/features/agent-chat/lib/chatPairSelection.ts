export type ChatPairRangeSelection = {
  anchorPairId: string | null;
  endPairId: string | null;
  selectedPairIds: string[];
};

export const EMPTY_CHAT_PAIR_RANGE_SELECTION: ChatPairRangeSelection = {
  anchorPairId: null,
  endPairId: null,
  selectedPairIds: []
};

type ChatPairMessage = {
  pair_id?: string;
  role: "user" | "assistant";
  content: string;
  status: string;
  action?: string;
};

export type ChatExportPairClassification = {
  selectablePairIds: string[];
  excludedPairIds: string[];
};

/** 완결된 문답 중 일반 대화만 편입 후보로 두고 문서 명령은 별도로 표시한다. */
export function classifyChatExportPairs(messages: ChatPairMessage[]): ChatExportPairClassification {
  const pairs = new Map<string, {
    hasQuestion: boolean;
    hasCompletedAnswer: boolean;
    isDocumentCommand: boolean;
  }>();

  for (const message of messages) {
    if (!message.pair_id) continue;
    const pair = pairs.get(message.pair_id) ?? {
      hasQuestion: false,
      hasCompletedAnswer: false,
      isDocumentCommand: false
    };
    if (message.role === "user" && message.content.trim()) pair.hasQuestion = true;
    if (message.role === "assistant" && message.status === "completed") {
      pair.hasCompletedAnswer = true;
      pair.isDocumentCommand = pair.isDocumentCommand
        || (message.action !== undefined
          && message.action !== "chat_answer"
          && message.action !== "conversation_reply");
    }
    pairs.set(message.pair_id, pair);
  }

  const selectablePairIds: string[] = [];
  const excludedPairIds: string[] = [];
  for (const [pairId, pair] of pairs) {
    if (!pair.hasQuestion || !pair.hasCompletedAnswer) continue;
    (pair.isDocumentCommand ? excludedPairIds : selectablePairIds).push(pairId);
  }
  return { selectablePairIds, excludedPairIds };
}

/** 첫 선택을 기준점으로 유지하고, 마지막 선택까지의 문답을 시간순으로 반환한다. */
export function selectChatPairRange(
  pairIds: string[],
  current: ChatPairRangeSelection,
  clickedPairId: string
): ChatPairRangeSelection {
  const clickedIndex = pairIds.indexOf(clickedPairId);
  if (clickedIndex < 0) return current;

  const anchorPairId = current.anchorPairId ?? clickedPairId;
  const anchorIndex = pairIds.indexOf(anchorPairId);
  if (anchorIndex < 0) {
    return {
      anchorPairId: clickedPairId,
      endPairId: clickedPairId,
      selectedPairIds: [clickedPairId]
    };
  }

  const startIndex = Math.min(anchorIndex, clickedIndex);
  const endIndex = Math.max(anchorIndex, clickedIndex);
  return {
    anchorPairId,
    endPairId: clickedPairId,
    selectedPairIds: pairIds.slice(startIndex, endIndex + 1)
  };
}
