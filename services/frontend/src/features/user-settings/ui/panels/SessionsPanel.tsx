"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchSessions, revokeSession, SESSIONS_QUERY_KEY, useSignOut, type LoginSession } from "@/entities/user";
import { getErrorMessage } from "@/shared/lib/errors";
import styles from "../SettingsModal.module.css";
import panelStyles from "./AccountPanel.module.css";

export function SessionsPanel() {
  const { data: sessions, isPending, error: loadError, refetch } = useQuery({
    queryKey: SESSIONS_QUERY_KEY, queryFn: fetchSessions, retry: false
  });
  const { signOut } = useSignOut();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function remove(session: LoginSession) {
    if (busy || !window.confirm(session.current ? "현재 기기에서 로그아웃하시겠습니까?" : "이 기기를 로그아웃하시겠습니까?")) return;
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await revokeSession(session.session_id);
      if (session.current) {
        await signOut({ callLogout: true });
        return;
      }
      setMessage("기기의 로그인 갱신을 해제했습니다.");
      void refetch();
    } catch (cause: unknown) {
      setError(getErrorMessage(cause, "기기를 로그아웃하지 못했습니다."));
      void refetch();
    } finally {
      setBusy(false);
    }
  }

  return <>
    <div className={styles.row}>
      <div className={styles["row-title"]}>
        <strong>로그인된 기기</strong>
        <small>로그인한 기기를 확인하고 로그아웃할 수 있습니다.</small>
      </div>
      <button type="button" className={panelStyles.pill} aria-expanded={open} onClick={() => setOpen(!open)}>
        {isPending ? "불러오는 중…" : sessions ? `${sessions.length}개` : "다시 확인"}
      </button>
    </div>
    {open && <div className={panelStyles["session-list"]}>
      <button type="button" className={styles.btn} disabled={busy} onClick={() => void refetch()}>목록 새로고침</button>
      <small>로그아웃한 기기도 기존 인증이 만료될 때까지 잠시 접근할 수 있습니다.</small>
      {sessions?.map((session) => <div className={styles.row} key={session.session_id}>
        <div className={styles["row-title"]}>
          <strong>{session.current ? "현재 기기" : "로그인 기기"}</strong>
          <small className={panelStyles["user-agent"]}>{session.user_agent || "알 수 없는 기기"}</small>
          <small>최근 갱신: {new Date(session.created_at).toLocaleString("ko-KR")}</small>
        </div>
        <button type="button" className={styles.btn} disabled={busy} onClick={() => void remove(session)}>로그아웃</button>
      </div>)}
      {sessions?.length === 0 && <small>로그인된 기기가 없습니다.</small>}
      {message && <small role="status">{message}</small>}
    </div>}
    {(error || loadError) && <small className={styles["model-error"]} role="alert">{error || getErrorMessage(loadError, "로그인된 기기를 불러오지 못했습니다.")}</small>}
  </>;
}
