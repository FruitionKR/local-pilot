import { AgentBody } from "./AgentBody";
import { AgentComposer } from "./AgentComposer";
import { AgentHeader } from "./AgentHeader";

export function AgentPanel({ onClose }: { onClose: () => void }) {
  return (
    <aside className="agent-panel">
      <AgentHeader onClose={onClose} />
      <AgentBody />
      <AgentComposer />
    </aside>
  );
}
