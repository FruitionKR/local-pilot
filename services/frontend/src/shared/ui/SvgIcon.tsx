import type { StaticImageData } from "next/image";
import Image from "next/image";
import type { ReactElement } from "react";
import arrowIcon from "../../../svg/document/arrow.svg";
import chatCheckIcon from "../../../svg/agent/chat_check.svg";
import claudeIcon from "../../../svg/llm/claude.svg";
import geminiIcon from "../../../svg/llm/gemini.svg";
import gptIcon from "../../../svg/llm/gpt.svg";
import collectionIcon from "../../../svg/navigation/menu_log.svg";
import conceptPageIcon from "../../../svg/graph/conceptpage.svg";
import fileIcon from "../../../svg/document/file.svg";
import fruitionLogo from "../../../svg/brand/fruition-logo.svg";
import folderPlusIcon from "../../../svg/navigation/menu_new.svg";
import homeIcon from "../../../svg/navigation/menu_home.svg";
import lightningIcon from "../../../svg/navigation/menu_schema.svg";
import rawPageIcon from "../../../svg/graph/raw.svg";
import searchIcon from "../../../svg/workspace/search.svg";
import sideboxIcon from "../../../svg/workspace/sidebox.svg";
import sourceIcon from "../../../svg/document/source.svg";
import sourcePageIcon from "../../../svg/graph/source_page.svg";
import settingIcon from "../../../svg/navigation/menu_setting.svg";
import shareIcon from "../../../svg/navigation/menu_graph.svg";
import profileToggleIcon from "../../../svg/workspace/profile_toggle.svg";
import toggleIcon from "../../../svg/workspace/toggle.svg";
import userCircleIcon from "../../../svg/workspace/UserCircle.svg";

// svg 파일 없이 인라인 SVG로만 렌더링하는 아이콘 식별자
const archiveIcon = { inlineIcon: "archive" } as const;
const bellIcon = { inlineIcon: "bell" } as const;
const chatBubbleIcon = { inlineIcon: "chatBubble" } as const;
const menuArchiveIcon = { inlineIcon: "menuArchive" } as const;
const menuSearchIcon = { inlineIcon: "menuSearch" } as const;
const plusIcon = { inlineIcon: "plus" } as const;

export type SvgAsset =
  | StaticImageData
  | typeof archiveIcon
  | typeof bellIcon
  | typeof chatBubbleIcon
  | typeof menuArchiveIcon
  | typeof menuSearchIcon
  | typeof plusIcon;

export {
  archiveIcon,
  arrowIcon,
  bellIcon,
  chatBubbleIcon,
  folderPlusIcon,
  menuArchiveIcon,
  menuSearchIcon,
  plusIcon,
  shareIcon,
  chatCheckIcon,
  claudeIcon,
  collectionIcon,
  geminiIcon,
  gptIcon,
  conceptPageIcon,
  fileIcon,
  fruitionLogo,
  homeIcon,
  lightningIcon,
  profileToggleIcon,
  rawPageIcon,
  searchIcon,
  sideboxIcon,
  sourceIcon,
  sourcePageIcon,
  settingIcon,
  toggleIcon,
  userCircleIcon
};

// currentColor 적용이 필요해 next/image 대신 인라인 SVG로 렌더링하는 아이콘 목록
const inlineIconRenderers = new Map<SvgAsset, (iconClassName: string) => ReactElement>([
  [archiveIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 16 16" fill="none">
      <path d="M2.7501 1.87491C2.28597 1.87491 1.84085 2.05928 1.51266 2.38747C1.18447 2.71566 1.0001 3.16078 1.0001 3.62491C1.0001 4.08904 1.18447 4.53416 1.51266 4.86235C1.84085 5.19053 2.28597 5.37491 2.7501 5.37491H13.2501C13.7142 5.37491 14.1593 5.19053 14.4875 4.86235C14.8157 4.53416 15.0001 4.08904 15.0001 3.62491C15.0001 3.16078 14.8157 2.71566 14.4875 2.38747C14.1593 2.05928 13.7142 1.87491 13.2501 1.87491H2.7501Z" fill="currentColor" opacity="0.55" />
      <path fillRule="evenodd" clipRule="evenodd" d="M1.87498 5.1064H14.125V12.1207C14.125 12.6522 13.9406 13.162 13.6124 13.5378C13.2842 13.9137 12.8391 14.1248 12.375 14.1248H3.62498C3.16086 14.1248 2.71574 13.9137 2.38755 13.5378C2.05936 13.162 1.87498 12.6522 1.87498 12.1207V5.1064Z" fill="currentColor" opacity="0.55" />
      <path d="M6.50627 8.25621C6.34217 8.4203 6.24998 8.64286 6.24998 8.87492C6.24998 9.10699 6.34217 9.32955 6.50627 9.49364C6.67036 9.65774 6.89292 9.74992 7.12498 9.74992H8.87498C9.10705 9.74992 9.32961 9.65774 9.4937 9.49364C9.6578 9.32955 9.74998 9.10699 9.74998 8.87492C9.74998 8.64286 9.6578 8.4203 9.4937 8.25621C9.32961 8.09211 9.10705 7.99992 8.87498 7.99992H7.12498C6.89292 7.99992 6.67036 8.09211 6.50627 8.25621Z" fill="currentColor" />
      <rect x="1.87498" y="5.15607" width="12.2499" height="0.796814" fill="currentColor" />
    </svg>
  )],
  // 사이드바 메뉴용 Archive: 16 뷰박스 아트를 36 버튼 규격(8px 인셋, 20px 아트)으로 스케일
  [menuArchiveIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 36 36" fill="none">
      <g transform="translate(8 8) scale(1.25)">
        <path d="M2.7501 1.87491C2.28597 1.87491 1.84085 2.05928 1.51266 2.38747C1.18447 2.71566 1.0001 3.16078 1.0001 3.62491C1.0001 4.08904 1.18447 4.53416 1.51266 4.86235C1.84085 5.19053 2.28597 5.37491 2.7501 5.37491H13.2501C13.7142 5.37491 14.1593 5.19053 14.4875 4.86235C14.8157 4.53416 15.0001 4.08904 15.0001 3.62491C15.0001 3.16078 14.8157 2.71566 14.4875 2.38747C14.1593 2.05928 13.7142 1.87491 13.2501 1.87491H2.7501Z" fill="currentColor" opacity="0.55" />
        <path fillRule="evenodd" clipRule="evenodd" d="M1.87498 5.1064H14.125V12.1207C14.125 12.6522 13.9406 13.162 13.6124 13.5378C13.2842 13.9137 12.8391 14.1248 12.375 14.1248H3.62498C3.16086 14.1248 2.71574 13.9137 2.38755 13.5378C2.05936 13.162 1.87498 12.6522 1.87498 12.1207V5.1064Z" fill="currentColor" opacity="0.55" />
        <path d="M6.50627 8.25621C6.34217 8.4203 6.24998 8.64286 6.24998 8.87492C6.24998 9.10699 6.34217 9.32955 6.50627 9.49364C6.67036 9.65774 6.89292 9.74992 7.12498 9.74992H8.87498C9.10705 9.74992 9.32961 9.65774 9.4937 9.49364C9.6578 9.32955 9.74998 9.10699 9.74998 8.87492C9.74998 8.64286 9.6578 8.4203 9.4937 8.25621C9.32961 8.09211 9.10705 7.99992 8.87498 7.99992H7.12498C6.89292 7.99992 6.67036 8.09211 6.50627 8.25621Z" fill="currentColor" />
        <rect x="1.87498" y="5.15607" width="12.2499" height="0.796814" fill="currentColor" />
      </g>
    </svg>
  )],
  // 사이드바 메뉴용 검색: 36 버튼 규격, currentColor로 hover/활성 색 반영
  [menuSearchIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 36 36" fill="none">
      <path d="M26.3 26.3L20.4 20.4M22.4 15.6C22.4 19.36 19.36 22.4 15.6 22.4C11.84 22.4 8.8 19.36 8.8 15.6C8.8 11.84 11.84 8.8 15.6 8.8C19.36 8.8 22.4 11.84 22.4 15.6Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )],
  // 사이드바 메뉴용 +: Figma 747:5867 plain plus
  [plusIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 36 36" fill="none">
      <path d="M18 11V25M11 18H25" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )],
  // 설정 모달 좌측 내비 알림용 종 아이콘
  [bellIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 24 24" fill="none">
      <path d="M12 3.5A5.5 5.5 0 0 0 6.5 9v3.2L5 15.5h14L17.5 12.2V9A5.5 5.5 0 0 0 12 3.5Z" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
      <path d="M10 18.5a2 2 0 0 0 4 0" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )],
  [chatBubbleIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 16 16" fill="none">
      <path d="M8 2.2C4.6 2.2 1.9 4.5 1.9 7.4C1.9 8.9 2.6 10.2 3.8 11.1L3.2 13.6L5.9 12.2C6.6 12.4 7.3 12.5 8 12.5C11.4 12.5 14.1 10.2 14.1 7.4C14.1 4.5 11.4 2.2 8 2.2Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
    </svg>
  )],
  [userCircleIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 28 28" fill="none">
      <path fillRule="evenodd" clipRule="evenodd" d="M27.7667 13.8833C27.7667 17.5654 26.304 21.0967 23.7003 23.7003C21.0967 26.304 17.5654 27.7667 13.8833 27.7667C10.2012 27.7667 6.66996 26.304 4.06633 23.7003C1.4627 21.0967 0 17.5654 0 13.8833C0 10.2012 1.4627 6.66996 4.06633 4.06633C6.66996 1.4627 10.2012 0 13.8833 0C17.5654 0 21.0967 1.4627 23.7003 4.06633C26.304 6.66996 27.7667 10.2012 27.7667 13.8833Z" fill="currentColor" opacity="0.55" />
      <path d="M16.999 13.2058C17.7679 12.4369 18.1999 11.394 18.1999 10.3067C18.1999 9.21926 17.7679 8.17641 16.999 7.40751C16.2301 6.63861 15.1873 6.20665 14.0999 6.20665C13.0125 6.20665 11.9696 6.63861 11.2007 7.40751C10.4318 8.17641 9.99988 9.21926 9.99988 10.3067C9.99988 11.394 10.4318 12.4369 11.2007 13.2058C11.9696 13.9747 13.0125 14.4067 14.0999 14.4067C15.1873 14.4067 16.2301 13.9747 16.999 13.2058Z" fill="currentColor" />
      <path d="M8.65361 18.0829C10.249 17.049 12.1047 16.4997 14.0001 16.5001C15.8955 16.4997 17.7512 17.049 19.3466 18.0829C20.5706 18.876 21.602 19.6344 22.3726 20.7306C22.8182 21.3644 22.6896 22.2199 22.1277 22.7533C21.1937 23.6397 20.1216 24.3692 18.9516 24.911C17.3982 25.6304 15.709 26.0019 14.0001 26C12.2912 26.0019 10.6021 25.6304 9.04863 24.911C7.87868 24.3692 6.80652 23.6397 5.87259 22.7533C5.3106 22.2199 5.18207 21.3644 5.62768 20.7306C6.39828 19.6344 7.4296 18.876 8.65361 18.0829Z" fill="currentColor" />
    </svg>
  )]
]);

export function SvgIcon({ src, className }: { src: SvgAsset; className?: string }) {
  const iconClassName = className || "svg-icon";
  const renderInline = inlineIconRenderers.get(src);

  if (renderInline) return renderInline(iconClassName);

  // 인라인 렌더러가 없는 자산은 항상 정적 이미지다
  return <Image alt="" aria-hidden className={iconClassName} src={src as StaticImageData} />;
}
