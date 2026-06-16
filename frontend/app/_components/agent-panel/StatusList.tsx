import { ChevronDown } from "lucide-react";
import { chatCheckIcon, rawPageIcon, SvgIcon } from "../SvgIcon";
import { buildStatusSteps } from "./agentData";

export function StatusList({ title, timed = false }: { title: string; timed?: boolean }) {
  const steps = buildStatusSteps(timed);

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
