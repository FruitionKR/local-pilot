"use client";

import { useState } from "react";
import { requestEmailVerification } from "@/entities/user";
import { getErrorMessage } from "@/shared/lib/errors";

type VerificationResendSuccess = {
  verificationId: string;
  expiresAt: number;
};

type UseVerificationResendOptions = {
  email: string;
  purpose: "signup" | "password_reset";
  onSuccess: (result: VerificationResendSuccess) => void;
  setErrorMessage: (message: string | null) => void;
};

// 인증번호 재요청 공통 로직. 성공 시 갱신된 verificationId/expiresAt을 onSuccess로 전달한다.
export function useVerificationResend({ email, purpose, onSuccess, setErrorMessage }: UseVerificationResendOptions) {
  const [isResending, setIsResending] = useState(false);

  async function resend() {
    if (isResending) return;

    setErrorMessage(null);
    setIsResending(true);

    try {
      const response = await requestEmailVerification(email, purpose);
      onSuccess({
        verificationId: response.verification_id,
        expiresAt: Date.now() + response.expires_in * 1000
      });
    } catch (error: unknown) {
      setErrorMessage(getErrorMessage(error, "인증번호 재요청에 실패했습니다."));
    } finally {
      setIsResending(false);
    }
  }

  return { isResending, resend };
}
