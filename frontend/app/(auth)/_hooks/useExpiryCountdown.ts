"use client";

import { useEffect, useState } from "react";

function remainingSeconds(expiresAt: number): number {
  return Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
}

function formatRemainingTime(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export function useExpiryCountdown(expiresAt: number): string {
  const [seconds, setSeconds] = useState(() => remainingSeconds(expiresAt));

  useEffect(() => {
    setSeconds(remainingSeconds(expiresAt));
    const timerId = window.setInterval(() => {
      setSeconds(remainingSeconds(expiresAt));
    }, 1000);
    return () => window.clearInterval(timerId);
  }, [expiresAt]);

  return formatRemainingTime(seconds);
}
