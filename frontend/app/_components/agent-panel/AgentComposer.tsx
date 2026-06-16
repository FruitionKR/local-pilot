import { Plus } from "lucide-react";
import { frameIcon, SvgIcon } from "../SvgIcon";

export function AgentComposer() {
  return (
    <div className="composer">
      <Plus size={18} />
      <input placeholder="메시지를 입력하세요..." />
      <button aria-label="전송"><SvgIcon src={frameIcon} /></button>
    </div>
  );
}
