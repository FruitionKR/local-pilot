"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { QRCodeSVG } from "qrcode.react";
import { activateMfa, disableMfa, fetchMfaStatus, registerMfa, MFA_QUERY_KEY, type MfaRegistration } from "@/entities/user";
import { getErrorMessage } from "@/shared/lib/errors";
import styles from "../SettingsModal.module.css";
import panelStyles from "./AccountPanel.module.css";

type Step = "connect" | "verify" | "recovery" | "disable" | null;

export function MfaPanel({ onFlowChange, onNavigationLockChange }: {
  onFlowChange: (active: boolean) => void;
  onNavigationLockChange: (locked: boolean) => void;
}) {
  const { data: status, error: loadError, refetch, isFetching } = useQuery({
    queryKey: MFA_QUERY_KEY, queryFn: fetchMfaStatus, retry: false
  });
  // 등록 키와 복구 코드는 진행 중인 화면의 메모리에만 보관한다.
  const [registration, setRegistration] = useState<MfaRegistration | null>(null);
  const [step, setStep] = useState<Step>(null);
  const [codesSaved, setCodesSaved] = useState(false);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => { onFlowChange(step !== null); headingRef.current?.focus(); }, [step, onFlowChange]);
  useEffect(() => { onNavigationLockChange(busy || step === "recovery"); }, [busy, step, onNavigationLockChange]);
  useEffect(() => () => onNavigationLockChange(false), [onNavigationLockChange]);

  function finish() {
    setRegistration(null);
    setStep(null);
    setCode("");
    setCodesSaved(false);
    setError(null);
    setMessage(null);
  }

  async function begin() {
    if (!status || busy || isFetching) return;
    setError(null);
    setMessage(null);
    if (status.enabled) { setStep("disable"); return; }
    setBusy(true);
    try {
      setRegistration(await registerMfa());
      setStep("connect");
    } catch (cause: unknown) {
      setError(getErrorMessage(cause, "다단계 인증 등록을 시작하지 못했습니다."));
      void refetch();
    } finally { setBusy(false); }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || !code.trim()) return;
    setBusy(true);
    setError(null);
    try {
      if (step === "disable") {
        await disableMfa(code.trim());
        finish();
      } else {
        await activateMfa(code.trim());
        setCode("");
        setStep("recovery");
      }
      await refetch();
    } catch (cause: unknown) {
      setError(getErrorMessage(cause, "다단계 인증 설정을 변경하지 못했습니다."));
    } finally { setBusy(false); }
  }

  async function copyCodes() {
    if (!registration) return;
    try {
      await navigator.clipboard.writeText(registration.recovery_codes.join("\n"));
      setMessage("복구 코드를 복사했습니다.");
      setError(null);
    } catch { setError("복사하지 못했습니다. 다운로드하거나 직접 보관해 주세요."); }
  }

  function downloadCodes() {
    if (!registration) return;
    const url = URL.createObjectURL(new Blob(["Fruition 복구 코드\n\n", registration.recovery_codes.join("\n")], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "fruition-recovery-codes.txt";
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    setMessage("복구 코드 다운로드를 시작했습니다.");
  }

  if (!step) return <>
    <div className={styles.row}>
      <div className={styles["row-title"]}>
        <strong>다단계 인증</strong>
        <small>{status?.enabled ? "다단계 인증 사용 중" : "로그인할 때 인증 앱의 코드를 추가로 확인합니다."}</small>
      </div>
      <button type="button" role="switch" aria-checked={status?.enabled ?? false} aria-label="다단계 인증"
        className={`${styles.switch} ${status?.enabled ? styles["is-on"] : ""}`}
        disabled={!status || busy || isFetching} onClick={() => void begin()}>
        <span className={styles["switch-ball"]} />
      </button>
    </div>
    {(error || loadError) && <small className={styles["model-error"]} role="alert">{error || getErrorMessage(loadError, "다단계 인증 상태를 불러오지 못했습니다.")}</small>}
    {loadError && <button type="button" className={styles.btn} disabled={isFetching} onClick={() => void refetch()}>다시 확인</button>}
  </>;

  const titles = { connect: "인증 앱 연결", verify: "인증번호 확인", recovery: "복구 코드 보관", disable: "다단계 인증 해제" };
  return <section className={panelStyles["mfa-flow"]} aria-labelledby="mfa-step-title">
    <div className={styles.title}>
      {step !== "disable" && <p className={styles["title-note"]}>{step === "connect" ? "1" : step === "verify" ? "2" : "3"} / 3 단계</p>}
      <div className={styles["title-row"]}><h2 id="mfa-step-title" ref={headingRef} tabIndex={-1}>{titles[step]}</h2></div>
      <p>{step === "connect" ? "Google Authenticator 등 인증 앱에서 QR코드를 스캔해 주세요." : step === "verify" ? "인증 앱에 표시된 6자리 코드를 입력해 주세요." : step === "recovery" ? "인증 앱을 사용할 수 없을 때 로그인할 수 있도록 복구 코드를 보관해 주세요." : "인증 앱의 코드 또는 복구 코드로 본인임을 확인해 주세요."}</p>
    </div>
    {step === "connect" && registration && <>
      <QRCodeSVG value={registration.otpauth_uri} size={224} marginSize={4} level="M" role="img" aria-label="인증 앱 등록 QR코드" className={panelStyles["mfa-qr"]} />
      <details className={panelStyles["mfa-manual"]}>
        <summary>직접 입력하기</summary>
        <div className={styles.field}>
          <label htmlFor="mfa-secret">인증 앱 설정 키</label>
          <input id="mfa-secret" readOnly value={registration.secret} onFocus={(event) => event.target.select()} />
          <small>인증 앱에서 계정을 추가하고, 키 유형을 시간 기반(TOTP)으로 선택해 주세요.</small>
        </div>
      </details>
      <div className={panelStyles["mfa-actions"]}>
        <button type="button" className={styles.btn} onClick={finish}>취소</button>
        <button type="button" className={styles.btn} onClick={() => setStep("verify")}>다음</button>
      </div>
    </>}
    {(step === "verify" || step === "disable") && <form className={panelStyles["password-form"]} onSubmit={(event) => void submit(event)}>
      <div className={styles.field}>
        <label htmlFor="mfa-code">{step === "disable" ? "인증 코드 또는 복구 코드" : "인증 앱의 6자리 코드"}</label>
        <input id="mfa-code" autoComplete="one-time-code" required maxLength={step === "disable" ? 64 : 6}
          pattern={step === "disable" ? undefined : "[0-9]{6}"} inputMode={step === "disable" ? "text" : "numeric"}
          value={code} disabled={busy} onChange={(event) => setCode(event.target.value)} />
        <small>방금 사용한 코드라면 다음 코드가 표시된 뒤 입력해 주세요.</small>
      </div>
      <div className={panelStyles["mfa-actions"]}>
        <button type="button" className={styles.btn} disabled={busy} onClick={() => { setError(null); step === "disable" ? finish() : setStep("connect"); }}>이전</button>
        <button type="submit" className={styles.btn} disabled={busy || !code.trim()}>{busy ? "확인 중…" : step === "disable" ? "다단계 인증 해제" : "인증하기"}</button>
      </div>
    </form>}
    {step === "recovery" && registration && <>
      <ul className={panelStyles["recovery-codes"]}>{registration.recovery_codes.map((item) => <li key={item}><code>{item}</code></li>)}</ul>
      <small>각 코드는 한 번만 사용할 수 있습니다. 이 화면을 닫으면 다시 볼 수 없습니다.</small>
      <div className={panelStyles["mfa-actions"]}>
        <button type="button" className={styles.btn} onClick={() => void copyCodes()}>복사</button>
        <button type="button" className={styles.btn} onClick={downloadCodes}>다운로드</button>
      </div>
      <label className={panelStyles["recovery-confirm"]}><input type="checkbox" checked={codesSaved} onChange={(event) => setCodesSaved(event.target.checked)} />복구 코드를 안전한 곳에 보관했습니다.</label>
      <div className={panelStyles["mfa-actions"]}><button type="button" className={styles.btn} disabled={!codesSaved || busy} onClick={finish}>설정 완료</button></div>
    </>}
    {error && <small className={styles["model-error"]} role="alert">{error}</small>}
    {message && <small role="status">{message}</small>}
  </section>;
}
