"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { confirmEmailVerification, loginWithEmail, signupWithEmail } from "@/entities/user";
import { saveAccessToken } from "@/shared/lib/auth";
import { getErrorMessage } from "@/shared/lib/errors";
import { useAuthFlow } from "@/views/auth/model/AuthFlowContext";
import { AuthError, AuthField, AuthSubmitButton } from "@/shared/ui/AuthControls";
import { useDevelopmentVerificationCode } from "@/views/auth/lib/useDevelopmentVerificationCode";
import { useExpiryCountdown } from "@/views/auth/lib/useExpiryCountdown";

export default function SignupVerificationPage() {
  const router = useRouter();
  const { signupDraft } = useAuthFlow();
  const [verificationCode, setVerificationCode] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const countdown = useExpiryCountdown(signupDraft?.expiresAt ?? 0);
  const draft = signupDraft;
  const isRequestingVerification = Boolean(draft && !draft.verificationId && !draft.verificationRequestError);

  useEffect(() => {
    if (!signupDraft) router.replace("/signup");
  }, [router, signupDraft]);

  useDevelopmentVerificationCode(verificationCode, completeVerification);

  if (!draft) {
    return <main className="auth-screen auth-screen--login" />;
  }

  async function completeVerification(code: string) {
    if (isSubmitting || !draft?.verificationId) return;

    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      const confirmation = await confirmEmailVerification(
        draft.verificationId,
        code.trim()
      );
      await signupWithEmail(
        draft.email,
        draft.password,
        draft.nickname,
        confirmation.verification_token
      );
      const tokens = await loginWithEmail(draft.email, draft.password);
      saveAccessToken(tokens.access_token);
      router.replace("/workspaces");
    } catch (error: unknown) {
      setErrorMessage(getErrorMessage(error, "회원가입에 실패했습니다."));
      setIsSubmitting(false);
    }
  }

  async function handleVerificationConfirm(event: React.FormEvent) {
    event.preventDefault();
    await completeVerification(verificationCode);
  }

  return (
    <main className="auth-screen auth-screen--login">
      <section className="auth-shell auth-shell--verification" aria-labelledby="auth-title">
        <div className="auth-content">
          <h1 className="auth-title" id="auth-title">회원가입</h1>
          <div className="auth-main-section">
            <form className="auth-form" method="post" onSubmit={handleVerificationConfirm}>
              <div className="auth-field-with-error">
                <AuthField
                  label="인증번호"
                  name="verificationCode"
                  onChange={(event) => setVerificationCode(event.target.value)}
                  placeholder="code"
                  readOnly={isRequestingVerification || Boolean(draft.verificationRequestError)}
                  timer={draft.expiresAt ? countdown : undefined}
                  value={verificationCode}
                />
                {isRequestingVerification ? <p className="auth-prompt" role="status">인증번호를 발송하고 있습니다.</p> : null}
                {draft.verificationRequestError ? <AuthError>{draft.verificationRequestError}</AuthError> : null}
                {errorMessage ? <AuthError>{errorMessage}</AuthError> : null}
              </div>
              <AuthSubmitButton disabled={isSubmitting || !draft.verificationId}>
                {isRequestingVerification ? "발송 중" : "인증 확인"}
              </AuthSubmitButton>
            </form>
            <p className="auth-prompt">이메일을 다시 입력하시겠어요?<button onClick={() => router.push("/signup")} type="button">이전으로</button></p>
          </div>
        </div>
      </section>
    </main>
  );
}
