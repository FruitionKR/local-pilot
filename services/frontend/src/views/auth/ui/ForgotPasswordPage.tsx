"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { confirmEmailVerification, requestEmailVerification } from "@/entities/user";
import { getErrorMessage } from "@/shared/lib/errors";
import { useAuthFlow } from "@/views/auth/model/AuthFlowContext";
import { AuthError, AuthField, AuthSubmitButton } from "@/shared/ui/AuthControls";
import { AuthScreen } from "@/shared/ui/AuthScreen";
import { useDevelopmentVerificationCode } from "@/views/auth/lib/useDevelopmentVerificationCode";
import { useExpiryCountdown } from "@/views/auth/lib/useExpiryCountdown";
import { useVerificationResend } from "@/views/auth/lib/useVerificationResend";
import { ResendCodePrompt } from "@/views/auth/ui/ResendCodePrompt";

export default function ForgotPasswordPage() {
  const router = useRouter();
  const { passwordResetDraft, setPasswordResetDraft } = useAuthFlow();
  const [email, setEmail] = useState(passwordResetDraft?.email ?? "");
  const [verificationCode, setVerificationCode] = useState("");
  const [isCodeStep, setIsCodeStep] = useState(Boolean(passwordResetDraft?.verificationId));
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const countdown = useExpiryCountdown(passwordResetDraft?.expiresAt ?? 0);
  const { isResending, resend } = useVerificationResend({
    email: passwordResetDraft?.email ?? "",
    purpose: "password_reset",
    setErrorMessage,
    onSuccess: ({ verificationId, expiresAt }) => {
      if (!passwordResetDraft) return;
      setPasswordResetDraft({
        ...passwordResetDraft,
        verificationId,
        expiresAt
      });
      setVerificationCode("");
    }
  });

  function restartVerification() {
    setPasswordResetDraft(null);
    setVerificationCode("");
    setIsCodeStep(false);
    setErrorMessage(null);
  }

  async function requestVerification() {
    if (isSubmitting) return;

    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      const normalizedEmail = email.trim().toLowerCase();
      const response = await requestEmailVerification(normalizedEmail, "password_reset");
      setPasswordResetDraft({
        email: normalizedEmail,
        verificationId: response.verification_id,
        expiresAt: Date.now() + response.expires_in * 1000
      });
      setIsCodeStep(true);
    } catch (error: unknown) {
      setErrorMessage(getErrorMessage(error, "이메일 인증에 실패했습니다."));
    } finally {
      setIsSubmitting(false);
    }
  }

  async function completeVerification(code: string) {
    if (isSubmitting) return;

    if (!passwordResetDraft) {
      setIsCodeStep(false);
      return;
    }

    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      const confirmation = await confirmEmailVerification(
        passwordResetDraft.verificationId,
        code.trim()
      );
      setPasswordResetDraft({
        ...passwordResetDraft,
        verificationToken: confirmation.verification_token
      });
      router.push("/reset-password");
    } catch (error: unknown) {
      setErrorMessage(getErrorMessage(error, "이메일 인증에 실패했습니다."));
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleVerificationResend() {
    if (!passwordResetDraft) return;
    await resend();
  }

  useDevelopmentVerificationCode(
    isCodeStep ? verificationCode : "",
    completeVerification
  );

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();

    if (isCodeStep) {
      await completeVerification(verificationCode);
      return;
    }

    await requestVerification();
  }

  return (
    <AuthScreen shellModifier="password" title="비밀번호 찾기">
      <form className="auth-form" method="post" onSubmit={handleSubmit}>
        <div className="auth-field-with-error">
          <div className="auth-field-stack">
            <AuthField
              autoComplete="email"
              label="이메일 인증"
              name="email"
              onChange={(event) => setEmail(event.target.value)}
              placeholder="example@email.com"
              readOnly={isCodeStep}
              type="email"
              value={email}
            />
            {isCodeStep ? (
              <AuthField
                label="인증번호"
                name="verificationCode"
                onChange={(event) => setVerificationCode(event.target.value)}
                placeholder="code"
                timer={countdown.label}
                value={verificationCode}
              />
            ) : null}
          </div>
          {errorMessage ? <AuthError>{errorMessage}</AuthError> : null}
        </div>
        <AuthSubmitButton disabled={isSubmitting}>
          {isCodeStep ? "인증 확인" : "인증 요청"}
        </AuthSubmitButton>
      </form>
      {isCodeStep ? (
        <>
          <ResendCodePrompt
            disabled={!countdown.isExpired || isResending}
            isResending={isResending}
            onResend={handleVerificationResend}
          />
          <p className="auth-prompt">이메일을 다시 입력하시겠어요?<button onClick={restartVerification} type="button">이전으로</button></p>
        </>
      ) : (
        <p className="auth-prompt">로그인으로 돌아가시겠어요?<button onClick={() => router.push("/login")} type="button">로그인하기</button></p>
      )}
    </AuthScreen>
  );
}
