import { fileIcon, SvgIcon } from "../SvgIcon";

export function AgentResultCard({ title, meta }: { title: string; meta: string }) {
  return (
    <button className="result-card">
      <span className="file-box"><SvgIcon src={fileIcon} /></span>
      <span><strong>{title}</strong><small>{meta}</small></span>
      <b>Source</b>
    </button>
  );
}
