import { apiFetch, getWorkspaceId, parseJsonOrThrow } from "@/shared/api/client";

/**
 * 위키 다듬기(lint) 요청. dryRun이면 실제 변경 없이 바뀔 페이지 수만 계산한다.
 * (backend는 dry_run을 명시적으로 false로 주지 않으면 dry-run으로 처리한다)
 */
export type WikiMaintenanceStatus = {
  needs_lint: boolean;
  last_lint_at?: string;
  last_wiki_change_at?: string;
};

/** 위키 유지보수 상태: 마지막 lint 이후 위키가 변경됐으면 needs_lint = true. */
export async function fetchWikiMaintenanceStatus(): Promise<WikiMaintenanceStatus> {
  const response = await apiFetch(
    `/api/workspaces/${getWorkspaceId()}/wiki/maintenance/status`,
    { cache: "no-store" }
  );
  return parseJsonOrThrow<WikiMaintenanceStatus>(response, "위키 상태를 불러오지 못했습니다.");
}

export async function requestWikiLint(dryRun: boolean): Promise<{ changedPageCount: number }> {
  const response = await apiFetch(
    `/api/workspaces/${getWorkspaceId()}/wiki/maintenance/lint`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ dry_run: dryRun })
    }
  );
  const queued = await parseJsonOrThrow<{ run_id: string }>(
    response,
    "위키 다듬기 요청에 실패했습니다."
  );
  for (let attempt = 0; attempt < 300; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    const polled = await apiFetch(
      `/api/workspaces/${getWorkspaceId()}/wiki/maintenance/runs/${encodeURIComponent(queued.run_id)}`,
      { cache: "no-store" }
    );
    if (polled.status === 404) continue;
    const run = await parseJsonOrThrow<{
      status: string;
      error?: string;
      manifest?: { task_result?: { changed_pages?: unknown[] } };
    }>(polled, "위키 다듬기 상태를 불러오지 못했습니다.");
    if (run.status === "succeeded") {
      return { changedPageCount: run.manifest?.task_result?.changed_pages?.length ?? 0 };
    }
    if (run.status === "failed") throw new Error(run.error || "위키 다듬기에 실패했습니다.");
  }
  throw new Error("위키 다듬기 처리 시간이 초과되었습니다.");
}
