"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { acceptInvitation, fetchInvitation } from "@/entities/workspace/api/invitations";
import { clearAuth, setSelectedWorkspaceId } from "@/shared/lib/auth";
import { getErrorMessage } from "@/shared/lib/errors";
import styles from "@/views/workspaces/ui/WorkspacesPage.module.css";

export default function InvitationPage({ token }: { token: string }) {
  const queryClient = useQueryClient();
  const { data: invitation, error: loadError, isPending } = useQuery({
    queryKey: ["invitation", token],
    queryFn: () => fetchInvitation(token),
    retry: false
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function accept() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      // 다른 탭에서 로그인한 계정을 현재 refresh 쿠키로 다시 확인한다.
      clearAuth();
      queryClient.clear();
      const accepted = await acceptInvitation(token);
      setSelectedWorkspaceId(accepted.workspace_id);
      window.location.assign("/home");
    } catch (cause: unknown) {
      setError(getErrorMessage(cause, "초대를 수락하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className={styles["error-screen"]}>
      <section className={styles["error-card"]} aria-labelledby="invitation-title">
        <h1 id="invitation-title">워크스페이스 초대</h1>
        {isPending && <p role="status">초대 정보를 불러오는 중…</p>}
        {invitation && <>
          <p className={styles["error-description"]}>{invitation.invited_by} 님이 {invitation.workspace_name}에 초대했습니다.</p>
          <p>{invitation.email} 계정으로 로그인한 뒤 수락해 주세요.</p>
          <p>권한: {invitation.role} · 만료: {new Date(invitation.expires_at).toLocaleString("ko-KR")}</p>
          <div className={styles["error-actions"]}>
            <button type="button" className={styles["retry-button"]} disabled={busy} onClick={() => void accept()}>{busy ? "수락 중…" : "초대 수락"}</button>
            <a className={styles["login-button"]} href="/login" target="_blank" rel="noopener noreferrer">로그인 / 회원가입</a>
          </div>
          <p className={styles["error-support"]}>새 탭에서 로그인한 뒤 이 화면으로 돌아와 초대를 수락해 주세요.</p>
        </>}
        {(error || loadError) && <p className={styles["error-detail"]} role="alert">{error || getErrorMessage(loadError, "초대 정보를 불러오지 못했습니다.")}</p>}
      </section>
    </main>
  );
}
