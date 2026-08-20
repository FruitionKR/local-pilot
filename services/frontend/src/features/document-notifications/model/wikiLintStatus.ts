export const WIKI_UP_TO_DATE_NOTICE = {
  title: "Wiki 최신 상태",
  message: "Wiki가 이미 최신 상태입니다."
} as const;

export function shouldRequestWikiLint(needsLint: boolean): boolean {
  return needsLint;
}
