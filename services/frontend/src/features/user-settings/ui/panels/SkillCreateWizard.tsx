"use client";

import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useMutation } from "@tanstack/react-query";
import { authorSkill, publishSkill, type SkillAuthoringResult } from "@/entities/skill";
import { getErrorMessage } from "@/shared/lib/errors";
import { useEscapeKey } from "@/shared/lib/useEscapeKey";
import { menuSearchIcon, settingScrollIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import styles from "./SkillCreateWizard.module.css";

const NAME_MAX = 63;
const PASS_ADVANCE_MS = 2000;

// 저장 범위 선택지
const SCOPE_OPTIONS = ["personal", "team"] as const;
type ScopeType = (typeof SCOPE_OPTIONS)[number];
const SCOPE_LABELS: Record<ScopeType, string> = { personal: "개인", team: "팀" };

/** issues 항목은 문서상 스키마가 느슨해 문자열·객체 모두 방어적으로 렌더한다. */
function issueTexts(issue: unknown): { title: string; detail: string } {
  if (typeof issue === "string") return { title: issue, detail: "" };
  if (issue != null && typeof issue === "object") {
    const record = issue as Record<string, unknown>;
    const title = record.title ?? record.code ?? record.type ?? "안전 검토 지적";
    const detail = record.detail ?? record.message ?? record.description ?? "";
    return { title: String(title), detail: String(detail) };
  }
  return { title: String(issue), detail: "" };
}

/**
 * 새 스킬 만들기 3단계 위저드 (Figma 1011:9407 / 1011:9321 / 1014:10593 / 1016:10806).
 * STEP1 입력 → STEP2 안전 검토(author) → STEP3 게시(publish). 게시 전에는 서버에 저장되지 않는다.
 */
export function SkillCreateWizard({
  workspaceId,
  onClose,
  onPublished
}: {
  workspaceId: string;
  onClose: () => void;
  onPublished: () => void;
}) {
  const [step, setStep] = useState<1 | 2 | 3>(1);

  // STEP 1 입력
  const [scopeType, setScopeType] = useState<ScopeType>("personal");
  const [scopeMenuOpen, setScopeMenuOpen] = useState(false);
  const [command, setCommand] = useState("");
  const [instruction, setInstruction] = useState("");

  // STEP 2~3 초안 (author 결과, 로컬 편집 허용)
  const [draft, setDraft] = useState<SkillAuthoringResult | null>(null);

  useEscapeKey(true, onClose);

  const authorMutation = useMutation({
    mutationFn: (body: { instruction: string }) =>
      authorSkill(workspaceId, { instruction: body.instruction, name: command, scope_type: scopeType }),
    onSuccess: (result) => {
      setDraft(result);
      setStep(2);
    }
  });

  const publishMutation = useMutation({
    mutationFn: () => {
      if (draft == null) throw new Error("게시할 초안이 없습니다.");
      return publishSkill(workspaceId, {
        name: command || draft.name,
        description: draft.description,
        instructions_markdown: draft.instructions_markdown,
        scope_type: scopeType,
        allowed_tools: draft.allowed_tools,
        capabilities: draft.capabilities
      });
    },
    onSuccess: () => {
      onPublished();
      onClose();
    }
  });

  const issues = draft?.issues ?? [];
  const passed = step === 2 && draft != null && issues.length === 0 && !authorMutation.isPending;

  // 검토 통과 시 2초 뒤 STEP 3으로 자동 진행
  useEffect(() => {
    if (!passed) return;
    const timer = setTimeout(() => setStep(3), PASS_ADVANCE_MS);
    return () => clearTimeout(timer);
  }, [passed]);

  const authorError =
    authorMutation.error != null ? getErrorMessage(authorMutation.error, "스킬 초안을 생성하지 못했습니다.") : null;

  const instructionLines = (draft?.instructions_markdown ?? "").split("\n").length;

  function renderStep1() {
    return (
      <>
        <div className={styles.fields}>
          {/* 저장 범위 */}
          <div className={styles["field-row"]}>
            <div className={styles["field-text"]}>
              <span className={styles["field-label"]}>저장 범위</span>
              <span className={styles["field-desc"]}>스킬을 적용할 범위를 지정합니다.</span>
            </div>
            <div className={styles["scope-wrap"]}>
              <button
                type="button"
                className={styles["scope-chip"]}
                aria-expanded={scopeMenuOpen}
                onClick={() => setScopeMenuOpen(!scopeMenuOpen)}
              >
                {SCOPE_LABELS[scopeType]}
                <SvgIcon src={settingScrollIcon} className={styles["chev-icon"]} />
              </button>
              {scopeMenuOpen && (
                <div className={styles["scope-menu"]} role="listbox" aria-label="저장 범위 선택">
                  {SCOPE_OPTIONS.map((option) => (
                    <button
                      key={option}
                      type="button"
                      role="option"
                      aria-selected={scopeType === option}
                      className={styles["scope-option"]}
                      onClick={() => {
                        setScopeType(option);
                        setScopeMenuOpen(false);
                      }}
                    >
                      {SCOPE_LABELS[option]}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* 커맨드 */}
          <div className={styles.field}>
            <span className={styles["field-label"]}>커맨드</span>
            <span className={styles["field-desc"]}>프로젝트에 적용할 커맨드명을 지정해주세요.</span>
            <div className={styles["input-wrap"]}>
              <input
                type="text"
                className={styles.input}
                maxLength={NAME_MAX}
                value={command}
                onChange={(event) => setCommand(event.target.value)}
              />
              <span className={styles.counter}>{command.length}/{NAME_MAX}</span>
            </div>
          </div>

          {/* 스킬 지침 */}
          <div className={styles.field}>
            <span className={styles["field-label"]}>스킬 지침 설정</span>
            <span className={styles["field-desc"]}>스킬이 수행할 반복 작업을 자연어로 설명해주세요.</span>
            <textarea
              className={styles.textarea}
              rows={8}
              placeholder="예: 회의록을 요약해서 액션 아이템 문서를 만들어 줘"
              value={instruction}
              onChange={(event) => setInstruction(event.target.value)}
            />
          </div>

          {/* 참고 문서 — 문서 선택 UI는 아직 배선되지 않아 비활성 상태로 둔다 */}
          <div className={styles["field-row"]}>
            <div className={styles["field-text"]}>
              <span className={styles["field-label"]}>참고 문서</span>
              <span className={styles["field-desc"]}>최대 3개까지 가능합니다.</span>
            </div>
            <button type="button" className={styles["doc-search-btn"]} disabled aria-label="참고 문서 검색">
              <SvgIcon src={menuSearchIcon} className={styles["doc-search-icon"]} />
            </button>
          </div>
        </div>

        {authorError && (
          <small className={styles.error} role="alert">{authorError}</small>
        )}

        <div className={styles.footer}>
          <span />
          <button
            type="button"
            className={styles["btn-primary"]}
            disabled={instruction.trim().length === 0 || authorMutation.isPending}
            onClick={() => authorMutation.mutate({ instruction })}
          >
            {authorMutation.isPending ? "안전 검토 중…" : "안전 검토 들어가기 ›"}
          </button>
        </div>
      </>
    );
  }

  function renderStep2() {
    if (draft == null) return null;
    return (
      <>
        <div className={styles.fields}>
          {issues.length > 0 && (
            <div className={styles.field}>
              <div className={styles["issues-head"]}>
                <span className={styles["field-label"]}>발견된 문제</span>
                <span className={styles["danger-badge"]}>미안전 표기 {issues.length}건</span>
              </div>
              <ul className={styles["issue-list"]}>
                {issues.map((issue, index) => {
                  const { title, detail } = issueTexts(issue);
                  return (
                    <li key={index} className={styles["issue-row"]}>
                      <span className={styles["issue-dot"]} aria-hidden />
                      <span className={styles["issue-body"]}>
                        <span className={styles["issue-title"]}>{title}</span>
                        {detail && <span className={styles["issue-detail"]}>{detail}</span>}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          <div className={styles.field}>
            <span className={styles["field-label"]}>스킬 내용 설정</span>
            <div className={styles["code-editor"]}>
              <div className={styles.gutter} aria-hidden>
                {Array.from({ length: instructionLines }, (_, i) => (
                  <span key={i}>{i + 1}</span>
                ))}
              </div>
              <textarea
                className={styles["code-textarea"]}
                rows={Math.max(instructionLines, 8)}
                value={draft.instructions_markdown}
                onChange={(event) => setDraft({ ...draft, instructions_markdown: event.target.value })}
              />
            </div>
          </div>
        </div>

        {authorError && (
          <small className={styles.error} role="alert">{authorError}</small>
        )}

        <div className={styles.footer}>
          <button type="button" className={styles["btn-ghost"]} onClick={() => setStep(1)}>
            ‹ 이전
          </button>
          <div className={styles["footer-group"]}>
            <button
              type="button"
              className={styles["btn-regen"]}
              disabled={authorMutation.isPending}
              onClick={() => authorMutation.mutate({ instruction })}
            >
              ✦ AI로 안전하게 다시 만들기
            </button>
            <button
              type="button"
              className={styles["btn-primary"]}
              disabled={authorMutation.isPending}
              onClick={() => authorMutation.mutate({ instruction: draft.instructions_markdown })}
            >
              {authorMutation.isPending ? "검토 중…" : "다시 검토하기 ›"}
            </button>
          </div>
        </div>

        {/* 검토 통과 오버레이 (Figma 1014:10593) */}
        {passed && (
          <button type="button" className={styles["pass-overlay"]} onClick={() => setStep(3)}>
            <svg width="72" height="72" viewBox="0 0 72 72" aria-hidden>
              <circle cx="36" cy="36" r="34" fill="none" stroke="#00de5a" strokeWidth="4" />
              <path d="M22 37l10 10 18-20" fill="none" stroke="#00de5a" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span className={styles["pass-text"]}>안전 검토를 통과했습니다.</span>
          </button>
        )}
      </>
    );
  }

  function renderStep3() {
    if (draft == null) return null;
    return (
      <>
        <div className={styles.fields}>
          <div className={styles.field}>
            <span className={styles["field-label"]}>커맨드</span>
            <div className={styles["input-wrap"]}>
              <input
                type="text"
                className={styles.input}
                maxLength={NAME_MAX}
                value={command}
                onChange={(event) => setCommand(event.target.value)}
              />
              <span className={styles.counter}>{command.length}/{NAME_MAX}</span>
            </div>
          </div>

          <div className={styles.field}>
            <span className={styles["field-label"]}>설명</span>
            <input
              type="text"
              className={styles.input}
              value={draft.description}
              onChange={(event) => setDraft({ ...draft, description: event.target.value })}
            />
          </div>

          <div className={styles.field}>
            <span className={styles["field-label"]}>스킬 내용</span>
            <textarea
              className={styles.textarea}
              rows={8}
              value={draft.instructions_markdown}
              onChange={(event) => setDraft({ ...draft, instructions_markdown: event.target.value })}
            />
          </div>

          {draft.allowed_tools.length > 0 && (
            <div className={styles.field}>
              <span className={styles["field-label"]}>실행 권한</span>
              <div className={styles["tool-chips"]}>
                {draft.allowed_tools.map((tool) => (
                  <span key={tool} className={styles["tool-chip"]}>{tool}</span>
                ))}
              </div>
            </div>
          )}
        </div>

        {publishMutation.error != null && (
          <small className={styles.error} role="alert">
            {getErrorMessage(publishMutation.error, "스킬을 게시하지 못했습니다.")}
          </small>
        )}

        <div className={styles.footer}>
          <button type="button" className={styles["btn-ghost"]} onClick={() => setStep(2)}>
            ‹ 이전
          </button>
          <div className={styles["footer-group"]}>
            <button
              type="button"
              className={styles["btn-regen"]}
              disabled={authorMutation.isPending}
              onClick={() => authorMutation.mutate({ instruction })}
            >
              ✦ AI로 안전하게 다시 만들기
            </button>
            <button
              type="button"
              className={styles["btn-primary"]}
              disabled={publishMutation.isPending || command.trim().length === 0}
              onClick={() => publishMutation.mutate()}
            >
              {publishMutation.isPending ? "게시 중…" : "최종 게시 ›"}
            </button>
          </div>
        </div>
      </>
    );
  }

  const titles: Record<1 | 2 | 3, { title: string; sub: string }> = {
    1: {
      title: "새 스킬 만들기",
      sub: "반복 작업과 참고 문서를 바탕으로 안전한 스킬을 만듭니다. 게시 전에는 서버에 저장되지 않습니다."
    },
    2: { title: "스킬 안전 검토", sub: "발견된 문제를 수정하거나 안전한 표현으로 다시 생성하세요." },
    3: { title: "스킬 게시 준비", sub: "최종 게시 시, 서버가 내용을 한 번 더 검토합니다." }
  };

  return createPortal(
    <div className={styles.overlay} onClick={onClose}>
      <div
        className={styles.modal}
        role="dialog"
        aria-modal="true"
        aria-label={titles[step].title}
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles.header}>
          <div className={styles["header-text"]}>
            <span className={styles.step}>STEP {step}/3</span>
            <h2 className={styles.title}>{titles[step].title}</h2>
            <p className={styles.sub}>{titles[step].sub}</p>
          </div>
          <button type="button" className={styles.close} aria-label="닫기" onClick={onClose}>
            ✕
          </button>
        </div>
        {step === 1 && renderStep1()}
        {step === 2 && renderStep2()}
        {step === 3 && renderStep3()}
      </div>
    </div>,
    document.body
  );
}
