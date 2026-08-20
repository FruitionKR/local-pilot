export const ACTIVE_WIKI_WORK_POLL_INTERVAL_MS = 3_000;
export const IDLE_WIKI_WORK_POLL_INTERVAL_MS = 15_000;

export function getWikiWorkPollInterval(hasActiveWork: boolean): number {
  return hasActiveWork
    ? ACTIVE_WIKI_WORK_POLL_INTERVAL_MS
    : IDLE_WIKI_WORK_POLL_INTERVAL_MS;
}
