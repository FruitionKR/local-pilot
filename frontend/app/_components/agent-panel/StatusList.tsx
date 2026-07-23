import { ChevronDown } from "lucide-react";
import { chatCheckIcon, rawPageIcon, SvgIcon } from "../SvgIcon";
import { buildStatusSteps, type StatusStep } from "./agentData";

export function StatusList({
  title,
  isLoading,
  hasResponse,
  steps
}: {
  title: string;
  isLoading: boolean;
  hasResponse: boolean;
  steps?: StatusStep[];
}) {
  // steps가 주어지면(SSE 실단계) 그대로 쓰고, 없으면 기존 고정 3단계로 폴백한다.
  const resolvedSteps = steps ?? buildStatusSteps(isLoading, hasResponse);

  return (
    <div className="status-list">
      <button type="button">{title} <ChevronDown size={14} /></button>
      <div className="status-steps">
        {resolvedSteps.map(([label, state]) => (
          <div className={`status-row ${state}`} key={`${title}-${label}`}>
            <span>{state === "done" && <SvgIcon src={chatCheckIcon} />}{state === "pending" && <SvgIcon src={rawPageIcon} />}</span>
            <p>{label}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
