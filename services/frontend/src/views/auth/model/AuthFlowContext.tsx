"use client";

import { createContext, useCallback, useContext, useRef, useState, type SetStateAction } from "react";

type SignupDraft = {
  nickname: string;
  email: string;
  password: string;
  verificationId: string;
  expiresAt: number;
  verificationRequestId?: string;
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
  setSignupDraft: (draft: SetStateAction<SignupDraft | null>) => void;
  isCurrentSignupVerificationRequest: (requestId: string) => boolean;
  passwordResetDraft: PasswordResetDraft | null;
  setPasswordResetDraft: (draft: PasswordResetDraft | null) => void;
};

const AuthFlowContext = createContext<AuthFlowValue | null>(null);

export function AuthFlowProvider({ children }: { children: React.ReactNode }) {
  const [signupDraft, setSignupDraftState] = useState<SignupDraft | null>(null);
  const signupDraftRef = useRef<SignupDraft | null>(null);
  const [passwordResetDraft, setPasswordResetDraft] = useState<PasswordResetDraft | null>(null);

  const setSignupDraft = useCallback((draft: SetStateAction<SignupDraft | null>) => {
    if (typeof draft !== "function") {
      signupDraftRef.current = draft;
      setSignupDraftState(draft);
      return;
    }
    setSignupDraftState((current) => {
      const next = draft(current);
      signupDraftRef.current = next;
      return next;
    });
  }, []);

  const isCurrentSignupVerificationRequest = useCallback(
    (requestId: string) => signupDraftRef.current?.verificationRequestId === requestId,
    []
  );

  return (
    <AuthFlowContext.Provider
      value={{
        signupDraft,
        setSignupDraft,
        isCurrentSignupVerificationRequest,
        passwordResetDraft,
        setPasswordResetDraft
      }}
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
