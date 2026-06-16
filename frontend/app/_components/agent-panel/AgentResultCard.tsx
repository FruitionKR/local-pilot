import { fileIcon, SvgIcon } from "../SvgIcon";

export function AgentResultCard({
  title,
  meta,
  pageType,
  onClick
}: {
  title: string;
  meta: string;
  pageType: string;
  onClick?: () => void;
}) {
  const label = pageType.toLowerCase() === "concept" ? "Concept" : "Source";

  return (
    <button className="result-card" type="button" onClick={onClick}>
      <span className="file-box"><SvgIcon src={fileIcon} /></span>
      <span><strong>{title}</strong><small>{meta}</small></span>
      <b>{label}</b>
    </button>
  );
}
