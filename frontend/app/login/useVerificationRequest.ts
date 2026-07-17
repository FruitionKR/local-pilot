"use client";

import { useCallback, useEffect, useState } from "react";

const VERIFICATION_TIME_LIMIT_SECONDS = 5 * 60;
const LOCAL_VERIFICATION_CODE = "9700";

function formatRemainingTime(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export function useVerificationRequest(isActive: boolean) {
  const [remainingSeconds, setRemainingSeconds] = useState(0);

  useEffect(() => {
    if (!isActive || remainingSeconds <= 0) return;

    const timeoutId = window.setTimeout(() => {
      setRemainingSeconds((current) => Math.max(0, current - 1));
    }, 1000);

    return () => window.clearTimeout(timeoutId);
  }, [isActive, remainingSeconds]);

  const startRequest = useCallback(() => {
    setRemainingSeconds(VERIFICATION_TIME_LIMIT_SECONDS);
  }, []);

  const clearRequest = useCallback(() => {
    setRemainingSeconds(0);
  }, []);

  const isTemporaryCode = useCallback((code: string) => {
    return process.env.NODE_ENV !== "production" && code === LOCAL_VERIFICATION_CODE;
  }, []);

  return {
    buttonLabel: remainingSeconds > 0
      ? `인증 요청 ${formatRemainingTime(remainingSeconds)}`
      : "인증 요청",
    clearRequest,
    isTemporaryCode,
    isWaiting: remainingSeconds > 0,
    startRequest
  };
}
