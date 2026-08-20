"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { LogIn, RefreshCw, WifiOff } from "lucide-react";
import { clearSessionCache } from "@/entities/chat";
import { fetchMe, useSignOut } from "@/entities/user";
import { createWorkspace, fetchWorkspaces } from "@/entities/workspace";
import { ERROR_MESSAGES } from "@/shared/api/client";
import { setSelectedWorkspaceId } from "@/shared/lib/auth";
import { getErrorMessage, isErrorMessage } from "@/shared/lib/errors";
import { LoadingOverlay } from "@/shared/ui/LoadingOverlay";
import styles from "./WorkspacesPage.module.css";

const DEFAULT_WORKSPACE_NAME = "나의 워크스페이스";

export default function WorkspacesPage() {
  const router = useRouter();
  const { signOut } = useSignOut();
  const hasStarted = useRef(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const prepareWorkspace = useCallback(async () => {
    setErrorMessage(null);
    try {
      await fetchMe();
      const response = await fetchWorkspaces();
      const workspace = response.workspaces?.[0] ?? await createWorkspace(DEFAULT_WORKSPACE_NAME);
      clearSessionCache();
      setSelectedWorkspaceId(workspace.id);
      router.replace("/home");
    } catch (error: unknown) {
      if (isErrorMessage(error, ERROR_MESSAGES.loginRequired)) {
        void signOut();
        return;
      }
      setErrorMessage(getErrorMessage(error, "워크스페이스를 준비하지 못했습니다."));
    }
  }, [router, signOut]);

  useEffect(() => {
    if (hasStarted.current) return;
    hasStarted.current = true;
    void prepareWorkspace();
  }, [prepareWorkspace]);

  if (!errorMessage) return <LoadingOverlay message="워크스페이스 준비 중…" />;

  return (
    <main className={styles["error-screen"]}>
      <section className={styles["error-card"]} aria-labelledby="workspace-error-title">
        <div className={styles["error-icon"]} aria-hidden>
          <WifiOff size={28} strokeWidth={1.8} />
        </div>
        <p className={styles["error-eyebrow"]}>CONNECTION ERROR</p>
        <h1 id="workspace-error-title">워크스페이스를 불러오지 못했어요</h1>
        <p className={styles["error-description"]}>
          일시적인 연결 문제일 수 있습니다. 잠시 후 다시 시도해 주세요.
          계정과 작업 내용은 그대로 유지됩니다.
        </p>
        <p className={styles["error-detail"]} role="alert">{errorMessage}</p>

        <div className={styles["error-actions"]}>
          <button
            type="button"
            className={styles["retry-button"]}
            onClick={() => void prepareWorkspace()}
          >
            <RefreshCw size={16} aria-hidden />
            다시 시도
          </button>
          <button
            type="button"
            className={styles["login-button"]}
            onClick={() => router.replace("/login")}
          >
            <LogIn size={16} aria-hidden />
            로그인 화면으로
          </button>
        </div>
      </section>

      <p className={styles["error-support"]}>
        문제가 계속되면 잠시 후 다시 접속하거나 관리자에게 문의해 주세요.
      </p>
    </main>
  );
}
