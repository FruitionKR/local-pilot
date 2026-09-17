"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { loginWithMfa } from "@/entities/user";
import { saveAccessToken } from "@/shared/lib/auth";
import { getErrorMessage } from "@/shared/lib/errors";
import { AuthError, AuthField, AuthSubmitButton } from "@/shared/ui/AuthControls";

export function MfaLoginForm({ token, onCancel }: { token: string; onCancel: () => void }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || !code.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const tokens = await loginWithMfa(token, code.trim());
      saveAccessToken(tokens.access_token);
      queryClient.clear();
      setCode("");
      router.replace("/workspaces");
    } catch (cause: unknown) {
      setError(getErrorMessage(cause, "다단계 인증에 실패했습니다."));
      setBusy(false);
    }
  }

  return <>
    <p className="auth-prompt">인증 앱의 6자리 코드 또는 사용하지 않은 복구 코드를 입력해 주세요.</p>
    <form className="auth-form" onSubmit={(event) => void submit(event)}>
      <AuthField label="인증 코드 또는 복구 코드" name="mfaCode" autoComplete="one-time-code"
        placeholder="인증 코드" value={code} readOnly={busy} onChange={(event) => setCode(event.target.value)} />
      {error && <AuthError>{error}</AuthError>}
      <AuthSubmitButton disabled={busy || !code.trim()}>{busy ? "확인 중…" : "인증하고 로그인"}</AuthSubmitButton>
    </form>
    <p className="auth-prompt"><button type="button" disabled={busy} onClick={onCancel}>로그인부터 다시 시작</button></p>
  </>;
}
