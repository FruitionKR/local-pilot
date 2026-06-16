import { sparkleIcon, SvgIcon } from "../SvgIcon";
import { agentResults } from "./agentData";
import { AgentResultCard } from "./AgentResultCard";
import { StatusList } from "./StatusList";

export function AgentBody() {
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
