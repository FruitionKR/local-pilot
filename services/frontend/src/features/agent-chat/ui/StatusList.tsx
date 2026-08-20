import { ChevronDown } from "lucide-react";
import { useState } from "react";
import { chatCheckIcon, rawPageIcon, SvgIcon } from "@/shared/ui/SvgIcon";
import { cx } from "@/shared/lib/classNames";
import { buildStatusSteps, type StatusStep } from "../lib/agentData";
import styles from "./AgentChat.module.css";

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
  const [isExpanded, setIsExpanded] = useState(true);
  // steps가 주어지면(SSE 실단계) 그대로 쓰고, 없으면 기존 고정 3단계로 폴백한다.
  const resolvedSteps = steps ?? buildStatusSteps(isLoading, hasResponse);

  return (
    <div className={styles["status-list"]}>
      <button type="button" aria-expanded={isExpanded} onClick={() => setIsExpanded((current) => !current)}>
        {title} <ChevronDown size={14} className={isExpanded ? undefined : styles["is-collapsed"]} />
      </button>
      {isExpanded && <div className={styles["status-steps"]}>
        {resolvedSteps.map(([label, state]) => (
          <div className={cx(styles["status-row"], styles[state])} key={`${title}-${label}`}>
            <span>{state === "done" && <SvgIcon src={chatCheckIcon} />}{state === "pending" && <SvgIcon src={rawPageIcon} />}</span>
            <p>{label}</p>
          </div>
        ))}
      </div>}
    </div>
  );
}
