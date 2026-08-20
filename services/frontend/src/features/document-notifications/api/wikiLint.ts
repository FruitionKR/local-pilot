import { apiFetch, getWorkspaceId, parseJsonOrThrow, workspacePath } from "@/shared/api/client";
import { pollUntil } from "@/shared/lib/polling";

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
    workspacePath(getWorkspaceId(), "wiki", "maintenance", "status"),
    { cache: "no-store" }
  );
  return parseJsonOrThrow<WikiMaintenanceStatus>(response, "위키 상태를 불러오지 못했습니다.");
}

export async function requestWikiLint(dryRun: boolean): Promise<{ changedPageCount: number }> {
  const response = await apiFetch(
    workspacePath(getWorkspaceId(), "wiki", "maintenance", "lint"),
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
  return pollUntil({
    poll: async () => {
      const polled = await apiFetch(
        workspacePath(getWorkspaceId(), "wiki", "maintenance", "runs", queued.run_id),
        { cache: "no-store" }
      );
      // run 레코드가 아직 생성되지 않았을 수 있으므로 404는 계속 폴링한다.
      if (polled.status === 404) return null;
      const run = await parseJsonOrThrow<{
        status: string;
        error?: string;
        manifest?: { task_result?: { changed_pages?: unknown[] } };
      }>(polled, "위키 다듬기 상태를 불러오지 못했습니다.");
      if (run.status === "succeeded") {
        return { changedPageCount: run.manifest?.task_result?.changed_pages?.length ?? 0 };
      }
      if (run.status === "failed") throw new Error(run.error || "위키 다듬기에 실패했습니다.");
      return null;
    },
    timeoutMessage: "위키 다듬기 처리 시간이 초과되었습니다."
  });
}
