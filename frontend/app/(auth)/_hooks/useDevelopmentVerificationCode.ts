"use client";

import { useEffect, useRef } from "react";

const DEVELOPMENT_VERIFICATION_CODE = "9700";

export function useDevelopmentVerificationCode(
  code: string,
  onDetected: (code: string) => void | Promise<void>
) {
  const onDetectedRef = useRef(onDetected);
  const detectedRef = useRef(false);

  useEffect(() => {
    onDetectedRef.current = onDetected;
  }, [onDetected]);

  useEffect(() => {
    if (process.env.NODE_ENV === "production" || code !== DEVELOPMENT_VERIFICATION_CODE) {
      detectedRef.current = false;
      return;
    }

    if (detectedRef.current) return;

    detectedRef.current = true;
    void onDetectedRef.current(code);
  }, [code]);
}
