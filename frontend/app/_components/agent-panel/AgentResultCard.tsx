import { fileIcon, sourceIcon, SvgIcon } from "../SvgIcon";
import { capitalize } from "./agentFormatters";

// 카드 스타일이 정의된 page type 목록. 그 외 값은 source로 취급한다.
const KNOWN_PAGE_TYPES = ["raw", "source", "concept"] as const;

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
  const normalizedPageType = (KNOWN_PAGE_TYPES as readonly string[]).includes(pageType.toLowerCase())
    ? pageType.toLowerCase()
    : "source";
  const label = capitalize(normalizedPageType);
  const icon = normalizedPageType === "source" ? sourceIcon : fileIcon;

  return (
    <button className={`result-card ${normalizedPageType}`} type="button" onClick={onClick ? (e) => { e.stopPropagation(); onClick(); } : undefined}>
      <span className={`file-box ${normalizedPageType}`}><SvgIcon src={icon} /></span>
      <span><strong>{title}</strong><small>{meta}</small></span>
      <b className={normalizedPageType}>{label}</b>
    </button>
  );
}
