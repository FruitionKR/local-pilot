"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { requestEmailVerification } from "../../_lib/api";
import { getErrorMessage } from "@/shared/lib/errors";
import { useAuthFlow } from "../AuthFlowContext";
import { AuthError, AuthField, AuthSubmitButton } from "../_components/AuthControls";

export default function SignupPage() {
  const router = useRouter();
  const { signupDraft, setSignupDraft } = useAuthFlow();
  const [nickname, setNickname] = useState(signupDraft?.nickname ?? "");
  const [email, setEmail] = useState(signupDraft?.email ?? "");
  const [password, setPassword] = useState(signupDraft?.password ?? "");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleVerificationRequest(event: React.FormEvent) {
    event.preventDefault();
    if (isSubmitting) return;

    if (password.length < 8) {
      setErrorMessage("비밀번호는 8자 이상 입력해주세요.");
      return;
    }

    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      const normalizedEmail = email.trim().toLowerCase();
      const response = await requestEmailVerification(normalizedEmail, "signup");
      setSignupDraft({
        nickname: nickname.trim(),
        email: normalizedEmail,
        password,
        verificationId: response.verification_id,
        expiresAt: Date.now() + response.expires_in * 1000
      });
      router.push("/signup/verify");
    } catch (error: unknown) {
      setErrorMessage(getErrorMessage(error, "인증번호 요청에 실패했습니다."));
      setIsSubmitting(false);
    }
  }

  return (
    <main className="auth-screen auth-screen--login">
      <section className="auth-shell" aria-labelledby="auth-title">
        <div className="auth-content">
          <h1 className="auth-title" id="auth-title">회원가입</h1>
          <div className="auth-main-section">
            <form className="auth-form" method="post" onSubmit={handleVerificationRequest}>
              <div className="auth-field-with-error">
                <div className="auth-field-stack">
                  <AuthField label="닉네임" name="nickname" onChange={(event) => setNickname(event.target.value)} placeholder="name" value={nickname} />
                  <AuthField autoComplete="email" label="이메일" name="email" onChange={(event) => setEmail(event.target.value)} placeholder="example@email.com" type="email" value={email} />
                  <AuthField autoComplete="new-password" label="비밀번호" name="password" onChange={(event) => setPassword(event.target.value)} placeholder="password" type="password" value={password} />
                </div>
                {errorMessage ? <AuthError>{errorMessage}</AuthError> : null}
              </div>
              <AuthSubmitButton disabled={isSubmitting}>인증 요청</AuthSubmitButton>
            </form>
            <p className="auth-prompt">이미 아이디가 있으신가요?<button onClick={() => router.push("/login")} type="button">로그인하기</button></p>
          </div>
        </div>
      </section>
    </main>
  );
}
