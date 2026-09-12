"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { changeEmail, confirmEmailVerification, requestEmailVerification, ME_QUERY_KEY, SESSIONS_QUERY_KEY } from "@/entities/user";
import { getErrorMessage } from "@/shared/lib/errors";
import styles from "../SettingsModal.module.css";
import panelStyles from "./AccountPanel.module.css";

export function EmailChangeForm({ onSaved }: { onSaved: () => void }) {
  const queryClient = useQueryClient();
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [verificationId, setVerificationId] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [retryAt, setRetryAt] = useState(0);
  const [now, setNow] = useState(Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const retrySeconds = Math.max(0, Math.ceil((retryAt - now) / 1000));

  useEffect(() => {
    if (!retryAt) return;
    const timer = window.setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= retryAt) window.clearInterval(timer);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [retryAt]);

  async function sendCode() {
    if (busy || Date.now() < retryAt) return;
    setBusy(true);
    setError(null);
    try {
      const result = await requestEmailVerification(email.trim(), "email_change");
      setVerificationId(result.verification_id);
      setToken(null);
      setCode("");
      setNow(Date.now());
      setRetryAt(Date.now() + result.retry_after * 1000);
    } catch (cause: unknown) {
      setError(getErrorMessage(cause, "인증번호를 보내지 못했습니다."));
    } finally {
      setBusy(false);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (!verificationId) { await sendCode(); return; }
    setBusy(true);
    setError(null);
    try {
      // 확정 요청만 실패했다면 이미 확인한 토큰으로 재시도한다.
      const verifiedToken = token ?? (await confirmEmailVerification(verificationId, code.trim())).verification_token;
      setToken(verifiedToken);
      const updated = await changeEmail(email.trim(), verifiedToken);
      queryClient.setQueryData(ME_QUERY_KEY, updated);
      void queryClient.invalidateQueries({ queryKey: SESSIONS_QUERY_KEY });
      void queryClient.invalidateQueries({ queryKey: ["workspace-members"] });
      onSaved();
    } catch (cause: unknown) {
      setError(getErrorMessage(cause, "이메일을 변경하지 못했습니다."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className={panelStyles["password-form"]} onSubmit={(event) => void submit(event)}>
      <div className={styles.field}>
        <label htmlFor="account-new-email">새 이메일</label>
        <input id="account-new-email" type="email" autoComplete="email" maxLength={255} required
          value={email} disabled={busy || Boolean(verificationId)} onChange={(event) => setEmail(event.target.value)} />
      </div>
      {verificationId && <>
        <small role="status">{email.trim()} 주소로 인증번호를 보냈습니다.</small>
        <div className={styles.field}>
          <label htmlFor="account-email-code">인증번호</label>
          <input id="account-email-code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]+" maxLength={6} required
            value={code} disabled={busy || Boolean(token)} onChange={(event) => setCode(event.target.value)} />
        </div>
        <button type="button" className={styles.btn} disabled={busy || retrySeconds > 0} onClick={() => void sendCode()}>
          {retrySeconds > 0 ? `${retrySeconds}초 후 재전송` : "인증번호 재전송"}
        </button>
        <button type="button" className={styles.btn} disabled={busy} onClick={() => { setVerificationId(null); setToken(null); setCode(""); setError(null); }}>다른 이메일 입력</button>
      </>}
      <small>이메일을 변경하면 다른 기기의 로그인 갱신이 해제됩니다.</small>
      {error && <small className={styles["model-error"]} role="alert">{error}</small>}
      <button type="submit" className={styles.btn} disabled={busy || (!verificationId && retrySeconds > 0)}>
        {busy ? "처리 중…" : verificationId ? "인증하고 이메일 변경" : retrySeconds > 0 ? `${retrySeconds}초 후 발송 가능` : "인증번호 발송"}
      </button>
    </form>
  );
}
