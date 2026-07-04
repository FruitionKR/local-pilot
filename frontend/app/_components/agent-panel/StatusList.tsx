import { ChevronDown } from "lucide-react";
import { chatCheckIcon, rawPageIcon, SvgIcon } from "../SvgIcon";
import { buildStatusSteps } from "./agentData";

export function StatusList({ title, isLoading, hasResponse }: { title: string; isLoading: boolean; hasResponse: boolean }) {
  const steps = buildStatusSteps(isLoading, hasResponse);

  return (
    <div className="status-list">
      <button type="button">{title} <ChevronDown size={14} /></button>
      <div className="status-steps">
        {steps.map(([label, state]) => (
          <div className={`status-row ${state}`} key={`${title}-${label}`}>
            <span>{state === "done" && <SvgIcon src={chatCheckIcon} />}{state === "pending" && <SvgIcon src={rawPageIcon} />}</span>
            <p>{label}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
