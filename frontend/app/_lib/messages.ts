import type { ChatMessageResponse } from "./types";

/**
 * 메시지 목록에서 마지막 user 역할 메시지를 반환합니다.
 * AgentPanel과 AgentBody에서 공통으로 사용합니다.
 */
export function findLastUserMessage(messages: ChatMessageResponse[]): ChatMessageResponse | undefined {
  return [...messages].reverse().find((message) => message.role === "user");
}
