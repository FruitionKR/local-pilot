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
  const normalizedPageType = ["raw", "source", "concept"].includes(pageType.toLowerCase())
    ? pageType.toLowerCase()
    : "source";
  const label = normalizedPageType.slice(0, 1).toUpperCase() + normalizedPageType.slice(1);

  return (
    <button className={`result-card ${normalizedPageType}`} type="button" onClick={onClick}>
      <span className={`file-box ${normalizedPageType}`}><SvgIcon src={fileIcon} /></span>
      <span><strong>{title}</strong><small>{meta}</small></span>
      <b className={normalizedPageType}>{label}</b>
    </button>
  );
}
