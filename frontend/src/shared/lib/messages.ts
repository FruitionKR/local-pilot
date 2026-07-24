import type { ChatMessageResponse } from "@/entities/chat";

/**
 * 메시지 목록에서 마지막 user 역할 메시지를 반환합니다.
 * AgentPanel과 AgentBody에서 공통으로 사용합니다.
 */
export function findLastUserMessage(messages: ChatMessageResponse[]): ChatMessageResponse | undefined {
  return [...messages].reverse().find((message) => message.role === "user");
}

const ROLE_LABEL: Record<"user" | "assistant", string> = {
  user: "사용자",
  assistant: "어시스턴트"
};
// recent_conversation_summary 상한. 초과 시 오래된(앞쪽) 내용을 잘라 최근 맥락을 남긴다.
const MAX_SUMMARY_CHARS = 4000;

/**
 * 선택한 문답(pair)들의 메시지를 전사해 편집 요청 맥락 문자열로 만듭니다.
 * 선택이 없으면 빈 문자열을 반환합니다(맥락 미전송).
 */
export function buildSelectedConversationSummary(
  messages: ChatMessageResponse[],
  selectedPairIds: ReadonlySet<string>
): string {
  if (selectedPairIds.size === 0) return "";
  const summary = messages
    .filter((message) => message.pair_id !== undefined && selectedPairIds.has(message.pair_id))
    .map((message) => `${ROLE_LABEL[message.role]}: ${message.content}`)
    .join("\n");
  return summary.length > MAX_SUMMARY_CHARS ? summary.slice(summary.length - MAX_SUMMARY_CHARS) : summary;
}
