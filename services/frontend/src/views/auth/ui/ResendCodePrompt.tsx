"use client";

type ResendCodePromptProps = {
  disabled: boolean;
  isResending: boolean;
  onResend: () => void;
};

// 인증번호 미수신 안내 문구와 재요청 버튼
export function ResendCodePrompt({ disabled, isResending, onResend }: ResendCodePromptProps) {
  return (
    <p className="auth-prompt">
      인증번호가 오지 않았나요?
      <button disabled={disabled} onClick={onResend} type="button">
        {isResending ? "재요청 중" : "재요청하기"}
      </button>
    </p>
  );
}
