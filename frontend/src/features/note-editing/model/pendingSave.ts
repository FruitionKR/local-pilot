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
