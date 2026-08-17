"use client";

import { createContext, useContext, useState } from "react";

type SignupDraft = {
  nickname: string;
  email: string;
  password: string;
  verificationId: string;
  expiresAt: number;
  verificationRequestError?: string;
};

type PasswordResetDraft = {
  email: string;
  verificationId: string;
  expiresAt: number;
  verificationToken?: string;
};

type AuthFlowValue = {
  signupDraft: SignupDraft | null;
  setSignupDraft: (draft: SignupDraft | null) => void;
  passwordResetDraft: PasswordResetDraft | null;
  setPasswordResetDraft: (draft: PasswordResetDraft | null) => void;
};

const AuthFlowContext = createContext<AuthFlowValue | null>(null);

export function AuthFlowProvider({ children }: { children: React.ReactNode }) {
  const [signupDraft, setSignupDraft] = useState<SignupDraft | null>(null);
  const [passwordResetDraft, setPasswordResetDraft] = useState<PasswordResetDraft | null>(null);

  return (
    <AuthFlowContext.Provider
      value={{ signupDraft, setSignupDraft, passwordResetDraft, setPasswordResetDraft }}
    >
      {children}
    </AuthFlowContext.Provider>
  );
}

export function useAuthFlow(): AuthFlowValue {
  const context = useContext(AuthFlowContext);
  if (!context) {
    throw new Error("인증 흐름은 AuthFlowProvider 안에서 사용해야 합니다.");
  }
  return context;
}
