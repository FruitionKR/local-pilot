"use client";

// 임시 워크스페이스 선택 화면. 정식 디자인(Figma) 도입 시 교체 대상.
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { clearSessionCache, createWorkspace, fetchWorkspaces } from "../_lib/api";
import { clearAuth, getAccessToken, setSelectedWorkspaceId } from "../_lib/auth";
import { getErrorMessage } from "../_lib/errors";
import type { WorkspaceResponse } from "../_lib/types";

export default function WorkspacesPage() {
  const router = useRouter();
  const [workspaces, setWorkspaces] = useState<WorkspaceResponse[]>([]);
  const [newWorkspaceName, setNewWorkspaceName] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isCreating, setIsCreating] = useState(false);

  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
      return;
    }
    let cancelled = false;
    fetchWorkspaces()
      .then((response) => {
        if (!cancelled) setWorkspaces(response.workspaces ?? []);
      })
      .catch((error: unknown) => {
        if (!cancelled) setErrorMessage(getErrorMessage(error, "워크스페이스를 불러오지 못했습니다."));
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  function handleSelect(workspaceId: string) {
    setSelectedWorkspaceId(workspaceId);
    router.replace("/");
  }

  async function handleCreate(event: React.FormEvent) {
    event.preventDefault();
    const name = newWorkspaceName.trim();
    if (!name || isCreating) return;

    setErrorMessage(null);
    setIsCreating(true);
    try {
      const created = await createWorkspace(name);
      handleSelect(created.id);
    } catch (error) {
      setErrorMessage(getErrorMessage(error, "워크스페이스 생성에 실패했습니다."));
      setIsCreating(false);
    }
  }

  function handleLogout() {
    clearAuth();
    clearSessionCache();
    router.replace("/login");
  }

  return (
    <main className="auth-screen">
      <section className="auth-card">
        <h1 className="auth-title">워크스페이스 선택</h1>
        <p className="auth-subtitle">사용할 워크스페이스를 선택하거나 새로 만드세요.</p>

        {isLoading ? <p className="auth-subtitle">불러오는 중...</p> : null}

        {!isLoading && workspaces.length === 0 ? (
          <p className="auth-subtitle">워크스페이스가 없습니다. 새로 만들어주세요.</p>
        ) : null}

        <ul className="workspace-list">
          {workspaces.map((workspace) => (
            <li key={workspace.id}>
              <button type="button" className="workspace-item" onClick={() => handleSelect(workspace.id)}>
                <span className="workspace-item-name">{workspace.name}</span>
                <span className="workspace-item-meta">{new Date(workspace.created_at).toLocaleDateString("ko-KR")}</span>
              </button>
            </li>
          ))}
        </ul>

        <form className="auth-form" onSubmit={handleCreate}>
          <label className="auth-field">
            <span>새 워크스페이스</span>
            <input
              type="text"
              value={newWorkspaceName}
              onChange={(event) => setNewWorkspaceName(event.target.value)}
              placeholder="워크스페이스 이름"
              required
            />
          </label>

          {errorMessage ? <p className="auth-error" role="alert">{errorMessage}</p> : null}

          <button type="submit" className="auth-submit" disabled={isCreating}>
            {isCreating ? "생성 중..." : "만들고 시작하기"}
          </button>
        </form>

        <button type="button" className="auth-link" onClick={handleLogout}>
          다른 계정으로 로그인
        </button>
      </section>
    </main>
  );
}
