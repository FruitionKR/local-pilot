export type PendingNoteSave = {
  markdown: string;
  revision: number;
  source?: "agent";
  applyOperationId?: string;
};

export function mergePendingNoteSave(
  current: PendingNoteSave | null,
  next: PendingNoteSave
): PendingNoteSave {
  if (current?.source !== "agent" && next.source !== "agent") return next;
  const applyOperationId = next.applyOperationId ?? current?.applyOperationId;
  return {
    ...next,
    source: "agent",
    ...(applyOperationId ? { applyOperationId } : {})
  };
}

export function recoverPendingNoteSaveAfterAgentFailure(
  pending: PendingNoteSave | null
): { pending: PendingNoteSave | null; retryRequired: boolean } {
  if (!pending) return { pending: null, retryRequired: true };
  return {
    pending: { ...pending, source: "agent" },
    retryRequired: false
  };
}

/**
 * AI 저장이 실패했을 때 스스로 다시 보낼지 판단한다.
 *
 * <p>뒤에 밀린 저장이 있으면 그쪽이 실어 가므로 여기서 또 보내지 않는다.
 * 밀린 저장이 없으면 아무도 다시 보내지 않아, 편집은 화면에만 남고 서버에는 없는 상태가 된다.
 */
export function planAgentRetryAfterFailure(
  recovered: { pending: PendingNoteSave | null; retryRequired: boolean },
  attempts: number,
  maxAttempts: number,
  baseDelayMs: number
): { shouldRetry: boolean; delayMs: number; attempts: number } {
  if (recovered.pending || attempts >= maxAttempts) {
    return { shouldRetry: false, delayMs: 0, attempts };
  }
  return {
    shouldRetry: true,
    delayMs: baseDelayMs * 2 ** attempts,
    attempts: attempts + 1
  };
}

export function applyRequiredAgentSource(
  candidate: PendingNoteSave,
  required: boolean,
  applyOperationId?: string
): PendingNoteSave {
  if (!required) return candidate;
  const resolvedApplyOperationId = candidate.applyOperationId ?? applyOperationId;
  return {
    ...candidate,
    source: "agent",
    ...(resolvedApplyOperationId ? { applyOperationId: resolvedApplyOperationId } : {})
  };
}
