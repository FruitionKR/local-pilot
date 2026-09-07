"use client";

import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { fetchDocuments, type DocumentItemResponse } from "@/entities/document";
import { authorSkill, publishSkill, type SkillAuthoringResult } from "@/entities/skill";
import { DocumentPickerModal } from "./DocumentPickerModal";
import { SafetyReviewBadge } from "./SafetyReviewBadge";
import { AlertModal } from "@/shared/ui/AlertModal";
import { getErrorMessage } from "@/shared/lib/errors";
import { useWorkspaceName } from "@/entities/workspace";
import { useEscapeKey } from "@/shared/lib/useEscapeKey";
import { menuSearchIcon, questionMarkIcon, settingScrollIcon, skillBackIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import styles from "./SkillCreateWizard.module.css";

const NAME_MAX = 63;

const REFERENCE_DOC_MAX = 3;

// 서버 name 검증 패턴 (docs/api/document/skills.md SkillAuthoringRequest)
const COMMAND_PATTERN = /^[a-z0-9][a-z0-9-]{0,62}$/;

/** 파일 크기를 kB/MB 문자열로 표시한다. */
function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
  return `${(bytes / 1024).toFixed(1)}kB`;
}

/** 확장자 뱃지 텍스트 (예: MD, PDF) */
function fileExtension(filename: string): string {
  const ext = filename.split(".").pop();
  return ext && ext !== filename ? ext.toUpperCase() : "DOC";
}

/** 지침 텍스트에서 커맨드명 추천값을 만든다(영문·숫자 단어를 하이픈으로 연결). */
function suggestCommand(instruction: string): string {
  const words = instruction
    .toLowerCase()
    .match(/[a-z0-9]+/g)
    ?.slice(0, 3);
  return words?.length ? words.join("-").slice(0, NAME_MAX) : "meeting-summary";
}
const PASS_ADVANCE_MS = 2000;

// 저장 범위 선택지
const SCOPE_OPTIONS = ["personal", "team"] as const;
type ScopeType = (typeof SCOPE_OPTIONS)[number];
const SCOPE_LABELS: Record<ScopeType, string> = { personal: "개인", team: "팀" };

// 안전 검토 issue category 한글 라벨
const ISSUE_CATEGORY_LABELS: Record<string, string> = {
  approval_bypass: "권한 우회 표현",
  sensitive_info: "민감정보로 보이는 내용",
  tool_policy: "허용되지 않은 도구 사용"
};

/** author 응답 issues 항목({category, text, reason, ...})을 표시용 텍스트로 변환한다. */
function issueTexts(issue: unknown): { title: string; detail: string } {
  if (typeof issue === "string") return { title: issue, detail: "" };
  if (issue != null && typeof issue === "object") {
    const record = issue as Record<string, unknown>;
    const category = typeof record.category === "string" ? record.category : "";
    const title = ISSUE_CATEGORY_LABELS[category] ?? String(record.reason ?? category ?? "안전 검토 지적");
    const quoted = record.text ? `"${String(record.text)}"` : "";
    const reason = ISSUE_CATEGORY_LABELS[category] ? String(record.reason ?? "") : "";
    const detail = [quoted, reason].filter(Boolean).join(" — ");
    return { title, detail };
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
  const workspaceName = useWorkspaceName();

  // STEP 1 입력
  const [scopeType, setScopeType] = useState<ScopeType>("personal");
  const [scopeMenuOpen, setScopeMenuOpen] = useState(false);
  const [command, setCommand] = useState("");
  const [instruction, setInstruction] = useState("");
  // 참고 문서 선택 (최대 3개)
  const [selectedDocs, setSelectedDocs] = useState<DocumentItemResponse[]>([]);
  const [docPickerOpen, setDocPickerOpen] = useState(false);

  const { data: documents } = useQuery({
    queryKey: ["documents", workspaceId],
    queryFn: fetchDocuments,
    enabled: docPickerOpen
  });

  // STEP 2~3 초안 (author 결과, 로컬 편집 허용)
  const [draft, setDraft] = useState<SkillAuthoringResult | null>(null);

  useEscapeKey(true, onClose);

  // 서버 name 패턴에 맞을 때만 전달한다. 빈 값·비허용 문자를 보내면 400이 난다.
  const validCommand = COMMAND_PATTERN.test(command.trim()) ? command.trim() : undefined;
  // STEP 3 표시·게시용 최종 커맨드명: 사용자가 넣은 유효 커맨드가 없으면 AI 초안 name을 쓴다.
  const publishName = validCommand ?? draft?.name ?? "";

  // authoring_mode: enhance(LLM 구체화, 기본) / preserve(원문 유지 재검토) / regenerate(차단 구간 제거 후 안전 재작성)
  const authorMutation = useMutation({
    mutationFn: (body: { instruction: string; mode?: "enhance" | "preserve" | "regenerate" }) =>
      authorSkill(workspaceId, {
        instruction: body.instruction,
        ...(body.mode ? { authoring_mode: body.mode } : {}),
        ...(validCommand ? { name: validCommand } : {}),
        scope_type: scopeType,
        ...(selectedDocs.length > 0 ? { reference_document_ids: selectedDocs.map((doc) => doc.id) } : {})
      }),
    onSuccess: (result) => {
      setDraft(result);
      setJustPassed((result.issues ?? []).length === 0);
      setStep(2);
    }
  });

  const publishMutation = useMutation({
    mutationFn: () => {
      if (draft == null) throw new Error("게시할 초안이 없습니다.");
      return publishSkill(workspaceId, {
        name: publishName,
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
  // 통과 오버레이·자동 진행은 검토 직후 1회만 보여준다. STEP3에서 '이전'으로 돌아오면 재생하지 않는다.
  const [justPassed, setJustPassed] = useState(false);
  const passed = step === 2 && justPassed && !authorMutation.isPending;

  // 통과해도 자동으로 STEP 3으로 넘어가지 않는다. 오버레이는 잠시 보여준 뒤 STEP 2에 머문다.
  useEffect(() => {
    if (!passed) return;
    const timer = setTimeout(() => setJustPassed(false), PASS_ADVANCE_MS);
    return () => clearTimeout(timer);
  }, [passed]);

  // STEP 3 진입 시 커맨드가 비어 있으면 AI가 지은 이름을 채워 수정 가능하게 한다.
  useEffect(() => {
    if (step === 3 && command.trim() === "" && draft?.name) setCommand(draft.name);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step]);

  const authorError =
    authorMutation.error != null ? getErrorMessage(authorMutation.error, "스킬 초안을 생성하지 못했습니다.") : null;

  // blocked 응답이면 초안 내용이 null이라, STEP1에서 입력한 지침을 편집 대상으로 보여준다.
  const reviewContent = draft?.instructions_markdown ?? instruction;
  const instructionLines = reviewContent.split("\n").length;

  function renderStep1() {
    return (
      <>
        <div className={styles.fields}>
          {/* 저장 범위 */}
          <div className={styles["field-row"]}>
            <div className={styles["field-text"]}>
              <span className={styles["field-label-row"]}>
                <span className={styles["field-label"]}>저장 범위</span>
                {/* 물음표 hover 시 저장 범위 설명 툴팁 (Figma 1036:8568, 위치 1036:8567: 아이콘 위·좌측 정렬) */}
                <span className={styles["help-wrap"]} tabIndex={0}>
                  <SvgIcon src={questionMarkIcon} className={styles["help-icon"]} />
                  <span className={styles.tooltip} role="tooltip">
                    <span className={styles["tooltip-row"]}>
                      <span className={styles["tooltip-term"]}>개인</span>
                      <span>-</span>
                      <span>모든 워크스페이스에서 사용됩니다.</span>
                    </span>
                    <span className={styles["tooltip-row"]}>
                      <span className={styles["tooltip-term"]}>팀</span>
                      <span>-</span>
                      <span>
                        현재 워크스페이스에서 사용됩니다.
                        <br />
                        (OWNER만 관리 가능)
                      </span>
                    </span>
                  </span>
                </span>
              </span>
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
                placeholder={suggestCommand(instruction)}
                value={command}
                onChange={(event) => setCommand(event.target.value)}
                onFocus={() => {
                  // 추천 커맨드명이 placeholder로 보이다가, 비어 있는 필드를 선택하면 자동으로 채운다.
                  if (command === "") setCommand(suggestCommand(instruction));
                }}
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
              rows={6}
              placeholder="예: 회의록을 요약해서 액션 아이템 문서를 만들어 줘"
              value={instruction}
              onChange={(event) => setInstruction(event.target.value)}
            />
          </div>

          {/* 참고 문서 (최대 3개) — 검색 아이콘으로 워크스페이스 문서를 선택한다 */}
          <div className={styles.field}>
            <div className={styles["field-row"]}>
              <div className={styles["field-text"]}>
                <span className={styles["field-label"]}>참고 문서</span>
                <span className={styles["field-desc"]}>최대 3개까지 가능합니다.</span>
              </div>
              <button
                type="button"
                className={styles["doc-search-btn"]}
                aria-label="참고 문서 검색"
                onClick={() => setDocPickerOpen(true)}
              >
                <SvgIcon src={menuSearchIcon} className={styles["doc-search-icon"]} />
              </button>
            </div>
            {selectedDocs.length > 0 && (
              <div className={styles["doc-cards"]}>
                {selectedDocs.map((doc) => (
                  <div key={doc.id} className={styles["doc-card"]}>
                    <div className={styles["doc-card-text"]}>
                      <span className={styles["doc-card-name"]}>{doc.filename}</span>
                      <span className={styles["doc-card-size"]}>{formatBytes(doc.byte_size)}</span>
                    </div>
                    <span className={styles["doc-card-ext"]}>{fileExtension(doc.filename)}</span>
                    <button
                      type="button"
                      className={styles["doc-card-remove"]}
                      aria-label={`${doc.filename} 선택 해제`}
                      onClick={() => setSelectedDocs(selectedDocs.filter((item) => item.id !== doc.id))}
                    >
                      ✕
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

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
                value={reviewContent}
                onChange={(event) =>
                  draft.instructions_markdown != null
                    ? setDraft({ ...draft, instructions_markdown: event.target.value })
                    : setInstruction(event.target.value)
                }
              />
            </div>
          </div>
        </div>

        <div className={styles.footer}>
          <button type="button" className={styles["btn-ghost"]} onClick={() => setStep(1)}>
            <SvgIcon src={skillBackIcon} className={styles["back-icon"]} /> 이전
          </button>
          <div className={styles["footer-group"]}>
            <button
              type="button"
              className={styles["btn-regen"]}
              disabled={authorMutation.isPending}
              onClick={() => authorMutation.mutate({ instruction: reviewContent, mode: "regenerate" })}
            >
              {authorMutation.isPending ? "✦ 안전하게 다시 만드는 중…" : "✦ AI로 안전하게 다시 만들기"}
            </button>
            <button
              type="button"
              className={styles["btn-primary"]}
              disabled={authorMutation.isPending}
              onClick={() => authorMutation.mutate({ instruction: reviewContent, mode: "preserve" })}
            >
              {authorMutation.isPending ? "검토 중…" : "다시 검토하기 ›"}
            </button>
            {issues.length === 0 && draft.instructions_markdown != null && (
              <button type="button" className={styles["btn-primary"]} onClick={() => setStep(3)}>
                다음 ›
              </button>
            )}
          </div>
        </div>

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

          <div className={styles.field}>
            <span className={styles["field-label"]}>실행 가능한 워크스페이스</span>
            <div className={styles["tool-chips"]}>
              {/* 저장 범위에 따라 실제 적용 워크스페이스를 보여준다. 개인은 모든 워크스페이스에서 쓸 수 있다. */}
              <span className={styles["tool-chip"]}>
                {scopeType === "personal" ? "모든 워크스페이스" : `${workspaceName ?? "현재 워크스페이스"} (팀)`}
              </span>
            </div>
          </div>
        </div>

        {publishMutation.error != null && (
          <small className={styles.error} role="alert">
            {getErrorMessage(publishMutation.error, "스킬을 게시하지 못했습니다.")}
          </small>
        )}

        <div className={styles.footer}>
          <button type="button" className={styles["btn-ghost"]} onClick={() => setStep(2)}>
            <SvgIcon src={skillBackIcon} className={styles["back-icon"]} /> 이전
          </button>
          <div className={styles["footer-group"]}>
            <button
              type="button"
              className={styles["btn-regen"]}
              disabled={authorMutation.isPending}
              onClick={() => {
                // 초안을 원본 요구 기준으로 안전 재생성하고 STEP 2에서 다시 확인한다.
                authorMutation.mutate({ instruction, mode: "regenerate" });
              }}
            >
              {authorMutation.isPending ? "✦ 안전하게 다시 만드는 중…" : "✦ AI로 안전하게 다시 만들기"}
            </button>
            <button
              type="button"
              className={styles["btn-primary"]}
              disabled={publishMutation.isPending || publishName.length === 0}
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

        {/* 안전 검토 오버레이 (Figma 1033:8390 → 1033:8429) — 진행·통과가 한 오버레이를 공유해
            전환 시 화면이 끊기지 않는다. 통과 상태에서 클릭하면 즉시 STEP 3으로 진행한다. */}
        {(authorMutation.isPending || passed) && (
          <button
            type="button"
            className={styles["pass-overlay"]}
            disabled={authorMutation.isPending}
            onClick={() => setJustPassed(false)}
          >
            <SafetyReviewBadge variant={authorMutation.isPending ? "loading" : "complete"} />
          </button>
        )}

        {/* 검토·재생성 실패 사유 알림 (인라인 대신 알림 창으로 안내) */}
        {authorError && (
          <AlertModal
            titleId="skill-author-error-title"
            title="스킬 검토 요청이 거부되었습니다."
            description={
              <>
                {authorError}
                <br />
                위험한 표현(승인 우회·무확인 실행·민감정보 등)이 많으면 AI가 안전하게
                재작성하지 못합니다. 스킬 내용에서 해당 표현을 직접 고친 뒤 다시 검토해 주세요.
              </>
            }
            onClose={() => authorMutation.reset()}
          >
            <button type="button" className="modal-confirm-button" onClick={() => authorMutation.reset()}>
              확인
            </button>
          </AlertModal>
        )}

        {/* 참고 문서 선택 — 네비게이션 검색과 동일한 중앙 모달 */}
        {docPickerOpen && (
          <DocumentPickerModal
            documents={documents ?? []}
            selectedIds={selectedDocs.map((doc) => doc.id)}
            maxCount={REFERENCE_DOC_MAX}
            onToggle={(doc) =>
              setSelectedDocs((current) =>
                current.some((item) => item.id === doc.id)
                  ? current.filter((item) => item.id !== doc.id)
                  : [...current, doc]
              )
            }
            onClose={() => setDocPickerOpen(false)}
          />
        )}
      </div>
    </div>,
    document.body
  );
}
