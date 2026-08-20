"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { checkEmailAvailability, requestEmailVerification } from "@/entities/user";
import { getErrorMessage } from "@/shared/lib/errors";
import { useAuthFlow } from "@/views/auth/model/AuthFlowContext";
import { AuthError, AuthField, AuthSubmitButton } from "@/shared/ui/AuthControls";
import { AuthScreen } from "@/shared/ui/AuthScreen";

export default function SignupPage() {
  const router = useRouter();
  const { signupDraft, setSignupDraft, isCurrentSignupVerificationRequest } = useAuthFlow();
  const [nickname, setNickname] = useState(signupDraft?.nickname ?? "");
  const [email, setEmail] = useState(signupDraft?.email ?? "");
  const [password, setPassword] = useState(signupDraft?.password ?? "");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const draftError = signupDraft?.email === email.trim().toLowerCase()
    ? signupDraft.verificationRequestError ?? null
    : null;
  const visibleError = errorMessage ?? draftError;

  async function handleVerificationRequest(event: React.FormEvent) {
    event.preventDefault();
    if (isSubmitting) return;

    if (password.length < 8) {
      setErrorMessage("비밀번호는 8자 이상 입력해주세요.");
      return;
    }

    setErrorMessage(null);
    setIsSubmitting(true);

    const normalizedEmail = email.trim().toLowerCase();
    try {
      const availability = await checkEmailAvailability(normalizedEmail);
      if (!availability.available) {
        setErrorMessage("이미 가입된 이메일입니다.");
        setIsSubmitting(false);
        return;
      }
    } catch (error: unknown) {
      setErrorMessage(getErrorMessage(error, "이메일 중복 확인에 실패했습니다."));
      setIsSubmitting(false);
      return;
    }

    const verificationRequestId = crypto.randomUUID();
    const pendingDraft = {
      nickname: nickname.trim(),
      email: normalizedEmail,
      password,
      verificationId: "",
      expiresAt: 0,
      verificationRequestId
    };
    setSignupDraft(pendingDraft);
    router.push("/signup/verify");

    try {
      const response = await requestEmailVerification(normalizedEmail, "signup");
      if (!isCurrentSignupVerificationRequest(verificationRequestId)) return;
      setSignupDraft({
        ...pendingDraft,
        verificationId: response.verification_id,
        expiresAt: Date.now() + response.expires_in * 1000
      });
    } catch (error: unknown) {
      if (!isCurrentSignupVerificationRequest(verificationRequestId)) return;
      setSignupDraft({
        ...pendingDraft,
        verificationRequestError: getErrorMessage(error, "인증번호 요청에 실패했습니다.")
      });
      setIsSubmitting(false);
      router.replace("/signup");
    }
  }

  return (
    <AuthScreen title="회원가입">
      <form className="auth-form" method="post" onSubmit={handleVerificationRequest}>
        <div className="auth-field-with-error">
          <div className="auth-field-stack">
            <AuthField label="닉네임" name="nickname" onChange={(event) => setNickname(event.target.value)} placeholder="name" value={nickname} />
            <AuthField autoComplete="email" label="이메일" name="email" onChange={(event) => setEmail(event.target.value)} placeholder="example@email.com" type="email" value={email} />
            <AuthField autoComplete="new-password" label="비밀번호" name="password" onChange={(event) => setPassword(event.target.value)} placeholder="password" type="password" value={password} />
          </div>
          {visibleError ? <AuthError>{visibleError}</AuthError> : null}
        </div>
        <AuthSubmitButton disabled={isSubmitting}>인증 요청</AuthSubmitButton>
      </form>
      <p className="auth-prompt">이미 아이디가 있으신가요?<button onClick={() => router.push("/login")} type="button">로그인하기</button></p>
    </AuthScreen>
  );
}
