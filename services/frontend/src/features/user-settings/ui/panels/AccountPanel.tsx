"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { changePassword, ME_QUERY_KEY, SESSIONS_QUERY_KEY, updateDisplayName, useMe } from "@/entities/user";
import { EmailChangeForm } from "./EmailChangeForm";
import { SessionsPanel } from "./SessionsPanel";
import { MfaPanel } from "./MfaPanel";
import { getErrorMessage } from "@/shared/lib/errors";
import styles from "../SettingsModal.module.css";
import panelStyles from "./AccountPanel.module.css";

/** 계정 설정 패널 (Figma 963:8660). */
export function AccountPanel({ onMfaNavigationLockChange }: { onMfaNavigationLockChange: (locked: boolean) => void }) {
  const [mfaFlow, setMfaFlow] = useState(false);
  const { data: me, error: loadError } = useMe();
  const queryClient = useQueryClient();
  const [nicknameDraft, setNicknameDraft] = useState<string | null>(null);
  const nickname = nicknameDraft ?? me?.display_name ?? "";
  const nameRequestPending = useRef(false);
  const [composingName, setComposingName] = useState(false);
  const [savingName, setSavingName] = useState(false);
  const [nameError, setNameError] = useState<string | null>(null);
  const [nameSaved, setNameSaved] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [savingPassword, setSavingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [passwordSaved, setPasswordSaved] = useState(false);
  const [showEmail, setShowEmail] = useState(false);
  const [emailSaved, setEmailSaved] = useState(false);

  const saveName = useCallback(async () => {
    if (!me || nameRequestPending.current || composingName || nicknameDraft === null || !nickname.trim() || nickname.trim() === me.display_name) return;
    nameRequestPending.current = true;
    setSavingName(true);
    setNameError(null);
    setNameSaved(false);
    try {
      const updated = await updateDisplayName(nickname.trim());
      // 설정 메뉴와 사이드바가 같은 사용자 캐시를 사용한다.
      queryClient.setQueryData(ME_QUERY_KEY, updated);
      void queryClient.invalidateQueries({ queryKey: ["workspace-members"] });
      setNicknameDraft((current) => current === nickname ? null : current);
      setNameSaved(true);
    } catch (error: unknown) {
      setNameError(getErrorMessage(error, "닉네임을 변경하지 못했습니다."));
    } finally {
      nameRequestPending.current = false;
      setSavingName(false);
    }
  }, [me, nickname, nicknameDraft, composingName, queryClient]);

  useEffect(() => {
    if (savingName || nameError || composingName || nicknameDraft === null) return;
    const timer = window.setTimeout(() => void saveName(), 600);
    return () => window.clearTimeout(timer);
  }, [saveName, savingName, nameError, composingName, nicknameDraft]);

  function clearPasswords() {
    setCurrentPassword("");
    setNewPassword("");
    setPasswordConfirm("");
  }

  async function savePassword(event: FormEvent) {
    event.preventDefault();
    if (savingPassword) return;
    setPasswordError(null);
    setPasswordSaved(false);
    if (newPassword !== passwordConfirm) {
      setPasswordError("새 비밀번호가 일치하지 않습니다.");
      return;
    }
    setSavingPassword(true);
    try {
      await changePassword(currentPassword, newPassword);
      void queryClient.invalidateQueries({ queryKey: SESSIONS_QUERY_KEY });
      clearPasswords();
      setShowPassword(false);
      setPasswordSaved(true);
    } catch (error: unknown) {
      setPasswordError(getErrorMessage(error, "비밀번호를 변경하지 못했습니다."));
    } finally {
      setSavingPassword(false);
    }
  }

  return (
    <div className={styles.detail}>
      {!mfaFlow && <>
      <div className={styles.title}>
        <div className={styles["title-row"]}>
          <h2>계정 설정</h2>
        </div>
        <p>개인 계정 설정을 관리합니다.</p>
      </div>

      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>프로필</span>
          <span className={styles["section-line"]} />
        </div>
        <div className={styles.field}>
          <label htmlFor="account-nickname">닉네임</label>
          <input id="account-nickname" type="text" value={nickname} maxLength={255} required
            disabled={!me} onChange={(event) => { setNicknameDraft(event.target.value); setNameSaved(false); setNameError(null); }}
            onCompositionStart={() => setComposingName(true)} onCompositionEnd={() => setComposingName(false)}
            onBlur={() => void saveName()} />
          {(nameError || loadError) && <small className={styles["model-error"]} role="alert">{nameError || getErrorMessage(loadError, "계정 정보를 불러오지 못했습니다.")}</small>}
          {savingName ? <small role="status">저장 중…</small> : nameSaved && nicknameDraft === null && <small role="status">닉네임을 변경했습니다.</small>}
        </div>
      </div>

      <div className={styles.section}>
        <div className={styles["section-header"]}>
          <span>계정 정보</span>
          <span className={styles["section-line"]} />
        </div>
        <div className={styles.row}>
          <div className={styles["row-title"]}>
            <strong>이메일</strong>
            <small>{me?.email || "이메일 정보를 불러오지 못했습니다."}</small>
          </div>
          <button type="button" className={styles.btn} disabled={!me} aria-expanded={showEmail}
            onClick={() => { setShowEmail(!showEmail); setEmailSaved(false); }}>
            {showEmail ? "닫기" : "이메일 변경"}
          </button>
        </div>
        {showEmail && <EmailChangeForm onSaved={() => { setShowEmail(false); setEmailSaved(true); }} />}
        {emailSaved && <small role="status">이메일을 변경했습니다.</small>}
        <div className={styles.row}>
          <div className={styles["row-title"]}>
            <strong>비밀번호</strong>
            <small>현재 비밀번호를 확인하고 새 비밀번호로 변경합니다.</small>
          </div>
          <button
            type="button"
            className={styles.btn}
            disabled={!me || savingPassword}
            aria-expanded={showPassword}
            onClick={() => { clearPasswords(); setPasswordError(null); setPasswordSaved(false); setShowPassword(!showPassword); }}
          >
            비밀번호 변경
          </button>
        </div>
        {showPassword && (
          <form className={panelStyles["password-form"]} onSubmit={(event) => void savePassword(event)}>
            <div className={styles.field}>
              <label htmlFor="current-password">현재 비밀번호</label>
              <input id="current-password" type="password" autoComplete="current-password" required
                value={currentPassword} disabled={savingPassword} onChange={(event) => setCurrentPassword(event.target.value)} />
            </div>
            <div className={styles.field}>
              <label htmlFor="new-password">새 비밀번호</label>
              <input id="new-password" type="password" autoComplete="new-password" required minLength={8} maxLength={72}
                value={newPassword} disabled={savingPassword} onChange={(event) => setNewPassword(event.target.value)} />
              <small>8~72자로 입력해 주세요. 변경하면 다른 기기의 로그인 갱신이 해제됩니다.</small>
            </div>
            <div className={styles.field}>
              <label htmlFor="confirm-password">새 비밀번호 확인</label>
              <input id="confirm-password" type="password" autoComplete="new-password" required minLength={8} maxLength={72}
                value={passwordConfirm} disabled={savingPassword} onChange={(event) => setPasswordConfirm(event.target.value)} />
            </div>
            {passwordError && <small className={styles["model-error"]} role="alert">{passwordError}</small>}
            <button type="submit" className={styles.btn} disabled={savingPassword}>
              {savingPassword ? "변경 중…" : "비밀번호 저장"}
            </button>
          </form>
        )}
        {passwordSaved && <small role="status">비밀번호를 변경했습니다.</small>}
      </div>

      </>}
      <div className={styles.section}>
        {!mfaFlow && <div className={styles["section-header"]}>
          <span>계정 보안</span>
          <span className={styles["section-line"]} />
        </div>}
        {me && <MfaPanel onFlowChange={setMfaFlow} onNavigationLockChange={onMfaNavigationLockChange} />}
        {me && !mfaFlow && <SessionsPanel />}
      </div>
    </div>
  );
}
