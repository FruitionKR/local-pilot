"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { clearSessionCache } from "@/entities/chat";
import { createWorkspace, fetchWorkspaces } from "@/entities/workspace";
import { getAccessToken, setSelectedWorkspaceId } from "@/shared/lib/auth";
import { getErrorMessage } from "@/shared/lib/errors";

const DEFAULT_WORKSPACE_NAME = "나의 워크스페이스";

export default function WorkspacesPage() {
  const router = useRouter();
  const hasStarted = useRef(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    if (hasStarted.current) return;
    hasStarted.current = true;

    if (!getAccessToken()) {
      router.replace("/login");
      return;
    }

    fetchWorkspaces()
      .then(async (response) => {
        const workspace = response.workspaces?.[0] ?? await createWorkspace(DEFAULT_WORKSPACE_NAME);
        clearSessionCache();
        setSelectedWorkspaceId(workspace.id);
        router.replace("/home");
      })
      .catch((error: unknown) => {
        setErrorMessage(getErrorMessage(error, "워크스페이스를 준비하지 못했습니다."));
      });
  }, [router]);

  if (!errorMessage) return null;

  return <p className="auth-error" role="alert">{errorMessage}</p>;
}
