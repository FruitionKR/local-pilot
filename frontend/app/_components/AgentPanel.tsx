import { ChevronDown, Plus } from "lucide-react";
import {
  chatCheckIcon,
  fileIcon,
  frameIcon,
  rawPageIcon,
  sideboxIcon,
  sparkleIcon,
  SvgIcon
} from "./SvgIcon";

type StatusStepState = "done" | "active" | "pending";
type StatusStep = [label: string, state: StatusStepState, time: string];

const agentResults = [
  ["또래 관계 연구.pdf", "p.14-17 · 정서 발달 상관관계 분석"],
  ["정서 발달 보고서.pdf", "p.3, p.21 · 사회적 상호작용 사례"]
] as const;

export function AgentPanel({ onClose }: { onClose: () => void }) {
  return (
    <aside className="agent-panel">
      <AgentHeader onClose={onClose} />
      <AgentBody />
      <AgentComposer />
    </aside>
  );
}

function AgentHeader({ onClose }: { onClose: () => void }) {
  return (
    <div className="agent-header">
      <div className="agent-mark"><SvgIcon src={sparkleIcon} /></div>
      <div>
        <h2>Fruition Agent</h2>
        <p>자료 검색 에이전트 · 부산대 워크스페이스</p>
      </div>
      <button className="panel-action" aria-label="Agent 패널 숨기기" onClick={onClose}>
        <SvgIcon src={sideboxIcon} />
      </button>
    </div>
  );
}

function AgentBody() {
  return (
    <div className="agent-body">
      <div className="question-bubble">
        이번 학기 &apos;장애 아동 통합 교육&apos; 수업 발표를 준비 중이야. 또래 관계가 정서 발달에 미치는 영향에 관한 수업 자료랑 관련 논문 좀 찾아줄 수 있을까?
      </div>

      <div className="agent-message">
        <div className="mini-mark"><SvgIcon src={sparkleIcon} /></div>
        <div>
          <strong>Fruition Agent</strong>
          <p>요청을 분석하고 자료를 검색하고 있어요</p>
        </div>
      </div>

      <StatusList title="서치 명령 실행 중" />
      <StatusList title="서치 명령 실행 중" timed />

      <div className="results">
        <p>찾은 자료 2건</p>
        {agentResults.map(([title, meta]) => (
          <AgentResultCard key={title} title={title} meta={meta} />
        ))}
      </div>

      <div className="typing"><i /><i /><i /> 답변을 작성하고 있어요...</div>
    </div>
  );
}

function AgentResultCard({ title, meta }: { title: string; meta: string }) {
  return (
    <button className="result-card">
      <span className="file-box"><SvgIcon src={fileIcon} /></span>
      <span><strong>{title}</strong><small>{meta}</small></span>
      <b>Source</b>
    </button>
  );
}

function AgentComposer() {
  return (
    <div className="composer">
      <Plus size={18} />
      <input placeholder="메시지를 입력하세요..." />
      <button aria-label="전송"><SvgIcon src={frameIcon} /></button>
    </div>
  );
}

function StatusList({ title, timed = false }: { title: string; timed?: boolean }) {
  const steps: StatusStep[] = [
    ["업로드된 문서 12건 스캔 완료", "done", timed ? "14:22:24" : ""],
    ["'또래 관계' 관련 페이지 8건 추출", "done", timed ? "14:24:04" : ""],
    ["정서 발달 연관 논문 분석 중", "active", timed ? "14:24:58" : ""],
    ["발표용 핵심 자료 정리", "pending", timed ? "14:25:07" : ""]
  ];

  return (
    <div className={`status-list ${timed ? "is-timed" : ""}`}>
      <button>{title} <ChevronDown size={14} /></button>
      <div className="status-steps">
        {steps.map(([label, state, time]) => (
          <div className={`status-row ${state}`} key={`${title}-${label}`}>
            <span>{state === "done" && <SvgIcon src={chatCheckIcon} />}{state === "pending" && <SvgIcon src={rawPageIcon} />}</span>
            <p>{label}</p>
            {time && <time>{time}</time>}
          </div>
        ))}
      </div>
    </div>
  );
}
