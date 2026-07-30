export type PendingNoteSave = {
  markdown: string;
  revision: number;
  source?: "agent";
};

export function mergePendingNoteSave(
  current: PendingNoteSave | null,
  next: PendingNoteSave
): PendingNoteSave {
  if (current?.source !== "agent" && next.source !== "agent") return next;
  return { ...next, source: "agent" };
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

export function applyRequiredAgentSource(
  candidate: PendingNoteSave,
  required: boolean
): PendingNoteSave {
  return required ? { ...candidate, source: "agent" } : candidate;
}
