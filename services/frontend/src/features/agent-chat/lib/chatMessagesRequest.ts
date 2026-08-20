import type { ChatMessageResponse } from "@/entities/chat/model/chat";

export type ChatMessagesLoadResult = { messages?: ChatMessageResponse[] | null };

/**
 * 요청 토큰이 최신일 때만 메시지 목록을 돌려준다.
 * 세션 전환 등으로 무효화된 요청은 정상 빈 목록([])과 구분하도록 null을 반환한다.
 */
export async function fetchMessagesForRequest(
  requestRef: { current: number },
  load: () => Promise<ChatMessagesLoadResult>
): Promise<ChatMessageResponse[] | null> {
  const requestId = ++requestRef.current;
  const response = await load();
  if (requestId !== requestRef.current) return null;
  return response.messages ?? [];
}
