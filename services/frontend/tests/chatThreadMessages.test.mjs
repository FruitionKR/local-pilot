import assert from "node:assert/strict";
import test from "node:test";
import { fetchMessagesForRequest } from "../src/features/agent-chat/lib/chatMessagesRequest.ts";

const MESSAGES = [
  { id: "m1", role: "user", content: "A 세션 질문", status: "completed" },
  { id: "m2", role: "assistant", content: "A 세션 답변", status: "completed" }
];

test("세션 전환으로 무효화된 요청은 빈 목록이 아니라 null을 반환한다", async () => {
  const requestRef = { current: 0 };
  let resolveLoad;
  const pending = fetchMessagesForRequest(
    requestRef,
    () => new Promise((resolve) => { resolveLoad = resolve; })
  );

  // A 세션 refresh가 응답을 기다리는 동안 B 세션 전환이 요청 토큰을 무효화한다.
  requestRef.current += 1;
  resolveLoad({ messages: MESSAGES });

  assert.equal(await pending, null);
});

test("최신 요청은 받은 메시지 목록을 그대로 반환한다", async () => {
  const requestRef = { current: 0 };

  const result = await fetchMessagesForRequest(requestRef, async () => ({ messages: MESSAGES }));

  assert.deepEqual(result, MESSAGES);
});

test("메시지가 없는 정상 응답은 null이 아니라 빈 배열을 반환한다", async () => {
  const requestRef = { current: 0 };

  const result = await fetchMessagesForRequest(requestRef, async () => ({ messages: [] }));

  assert.deepEqual(result, []);
});

test("messages 필드가 없는 응답도 빈 배열로 정규화한다", async () => {
  const requestRef = { current: 0 };

  const result = await fetchMessagesForRequest(requestRef, async () => ({}));

  assert.deepEqual(result, []);
});
