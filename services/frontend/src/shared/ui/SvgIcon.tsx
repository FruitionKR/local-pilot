import type { StaticImageData } from "next/image";
import Image from "next/image";
import type { ReactElement } from "react";
import { cx } from "@/shared/lib/classNames";
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
import graphSelectIcon from "../../../svg/navigation/graph_select.svg";
import homeIcon from "../../../svg/navigation/menu_home.svg";
import homeSelectIcon from "../../../svg/navigation/home_select.svg";
import logSelectIcon from "../../../svg/navigation/log_select.svg";
import rawPageIcon from "../../../svg/graph/raw.svg";
import sideboxIcon from "../../../svg/workspace/sidebox.svg";
import sourceIcon from "../../../svg/document/source.svg";
import sourcePageIcon from "../../../svg/graph/source_page.svg";
import shareIcon from "../../../svg/navigation/menu_graph.svg";
import profileToggleIcon from "../../../svg/workspace/profile_toggle.svg";
import toggleIcon from "../../../svg/workspace/toggle.svg";
import userCircleIcon from "../../../svg/workspace/UserCircle.svg";

// svg 파일 없이 인라인 SVG로만 렌더링하는 아이콘 식별자
const bellIcon = { inlineIcon: "bell" } as const;
const chatBubbleIcon = { inlineIcon: "chatBubble" } as const;
const lightningIcon = { inlineIcon: "lightning" } as const;
const menuSearchIcon = { inlineIcon: "menuSearch" } as const;
const plusIcon = { inlineIcon: "plus" } as const;
const settingIcon = { inlineIcon: "setting" } as const;

export type SvgAsset =
  | StaticImageData
  | typeof bellIcon
  | typeof chatBubbleIcon
  | typeof lightningIcon
  | typeof menuSearchIcon
  | typeof plusIcon
  | typeof settingIcon;

export {
  arrowIcon,
  bellIcon,
  chatBubbleIcon,
  folderPlusIcon,
  graphSelectIcon,
  homeSelectIcon,
  logSelectIcon,
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
  sideboxIcon,
  sourceIcon,
  sourcePageIcon,
  settingIcon,
  toggleIcon,
  userCircleIcon
};

// currentColor 적용이 필요해 next/image 대신 인라인 SVG로 렌더링하는 아이콘 목록
const inlineIconRenderers = new Map<SvgAsset, (iconClassName: string) => ReactElement>([
  // 규칙(schema) 번개: 기존 menu_schema.svg 패스를 currentColor 인라인으로 이관
  [lightningIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 36 36" fill="none">
      <path d="M17.7139 9.87988C18.4156 9.14947 19.6871 9.63613 19.6875 10.6826V15.375H24.6562C25.6261 15.3753 26.16 16.5021 25.5459 17.2529L18.3525 26.0449C17.6683 26.8813 16.3129 26.3978 16.3125 25.3174V20.625H11.3438C10.3739 20.6247 9.84002 19.4979 10.4541 18.7471L17.6475 9.95508L17.7139 9.87988Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )],
  // 설정 톱니: 기존 menu_setting.svg 패스를 currentColor 인라인으로 이관
  [settingIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 36 36" fill="none">
      <path d="M19.3145 9.04541C19.8094 9.04543 20.2487 9.36209 20.4053 9.83154L20.7158 10.7632L20.7168 10.7651C20.9025 11.3284 21.2886 11.8046 21.8018 12.1021C22.3147 12.3993 22.9191 12.4981 23.5 12.3794L23.501 12.3784L24.4658 12.1821L24.6475 12.1606C25.0096 12.1456 25.3584 12.3031 25.5869 12.5845L25.6914 12.7349L26.7559 14.5815C27.0032 15.0106 26.9482 15.5503 26.6191 15.9204L25.9648 16.6538L25.9658 16.6548C25.5734 17.0975 25.3565 17.6687 25.3564 18.2603C25.3564 18.8514 25.573 19.4222 25.9648 19.8647L26.6191 20.6011C26.9478 20.9712 27.003 21.5101 26.7559 21.939L25.6914 23.7856C25.4444 24.2141 24.9505 24.4368 24.4658 24.3384L23.501 24.1421H23.5C22.919 24.0233 22.3148 24.1221 21.8018 24.4194C21.2887 24.7169 20.9025 25.1922 20.7168 25.7554L20.7148 25.7612L20.4053 26.6733C20.247 27.1398 19.809 27.4544 19.3164 27.4546H17.1904C16.6888 27.4546 16.2449 27.1292 16.0938 26.6509L15.8047 25.7339L15.7256 25.5278C15.5203 25.0552 15.1688 24.6583 14.7197 24.3979C14.2068 24.1006 13.6024 24.002 13.0215 24.1206H13.0205L12.0527 24.3179C11.5718 24.4155 11.082 24.1969 10.833 23.7739L9.75098 21.9351C9.49817 21.5051 9.55152 20.9612 9.88281 20.5884L10.5352 19.855L10.5361 19.853C10.9329 19.4094 11.1522 18.8349 11.1523 18.2397C11.1523 17.6444 10.933 17.0692 10.5361 16.6255L10.5352 16.6235L9.88184 15.8892C9.55191 15.5179 9.49749 14.9768 9.74707 14.5474L10.8105 12.7202L10.9141 12.5708C11.1809 12.2443 11.6104 12.0855 12.0332 12.1714L12.999 12.3687H13C13.5809 12.4874 14.1852 12.3886 14.6982 12.0913C15.2114 11.7939 15.5975 11.3177 15.7832 10.7544L15.7852 10.7505L16.0947 9.82861C16.2522 9.36068 16.6909 9.0455 17.1846 9.04541H19.3145Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="18.2499" cy="18.2504" r="3.1" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
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
  )],
  // 사이드바 메뉴 홈/그래프/로그: <img>는 currentColor를 못 받아 hover·활성 색이 죽는다.
  // 자산 패스를 그대로 옮기고 하드코딩된 #8A8A8A만 currentColor로 바꾼다.
  [homeIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 36 36" fill="none">
      <path d="M18.25 10.7607C18.4338 10.7607 18.5904 10.8203 18.6943 10.8994L25.1455 17.5225C25.3728 17.7558 25.5 18.0688 25.5 18.3945V24.8994C25.5 24.9465 25.4782 25.0266 25.3789 25.1094C25.2757 25.1952 25.111 25.2607 24.917 25.2607H22.25C22.0556 25.2607 21.8904 25.1954 21.7871 25.1094C21.6878 25.0266 21.667 24.9465 21.667 24.8994V22.6768C21.6668 22.1346 21.4066 21.6494 21.0059 21.3154C20.6091 20.9849 20.0957 20.8164 19.583 20.8164H16.917C16.4043 20.8164 15.8909 20.9849 15.4941 21.3154C15.0934 21.6494 14.8332 22.1346 14.833 22.6768V24.8994C14.833 24.9465 14.8122 25.0266 14.7129 25.1094C14.6096 25.1954 14.4444 25.2607 14.25 25.2607H11.583C11.389 25.2607 11.2243 25.1952 11.1211 25.1094C11.0218 25.0266 11 24.9465 11 24.8994V18.3945C11 18.0688 11.1272 17.7558 11.3545 17.5225L17.8047 10.8994C17.9086 10.8201 18.0659 10.7607 18.25 10.7607Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )],
  [shareIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 36 36" fill="none">
      <path d="M15.0517 19.1928C15.2312 18.8337 15.3326 18.4283 15.3326 17.9999C15.3326 17.5714 15.2312 17.1661 15.0517 16.807M15.0517 19.1928C14.783 19.73 14.3407 20.1608 13.7966 20.4153C13.2525 20.6699 12.6384 20.7332 12.0538 20.5952C11.4692 20.4571 10.9483 20.1257 10.5756 19.6547C10.2028 19.1837 10 18.6006 10 17.9999C10 17.3992 10.2028 16.8161 10.5756 16.345C10.9483 15.874 11.4692 15.5426 12.0538 15.4046C12.6384 15.2665 13.2525 15.3299 13.7966 15.5844C14.3407 15.839 14.783 16.2697 15.0517 16.807M15.0517 19.1928L20.9468 22.1403M15.0517 16.807L20.9468 13.8594M20.9468 22.1403C20.6304 22.7731 20.5784 23.5056 20.8021 24.1767C21.0258 24.8479 21.507 25.4026 22.1397 25.719C22.7725 26.0354 23.505 26.0875 24.1761 25.8637C24.8472 25.64 25.402 25.1589 25.7184 24.5261C26.0348 23.8934 26.0868 23.1609 25.8631 22.4897C25.6394 21.8186 25.1582 21.2638 24.5255 20.9474C24.2122 20.7908 23.8711 20.6974 23.5217 20.6725C23.1723 20.6477 22.8214 20.692 22.4891 20.8027C21.818 21.0264 21.2632 21.5076 20.9468 22.1403ZM20.9468 13.8594C21.1035 14.1727 21.3203 14.452 21.5849 14.6814C21.8495 14.9109 22.1567 15.086 22.489 15.1967C22.8212 15.3074 23.1721 15.3516 23.5214 15.3268C23.8708 15.3019 24.2118 15.2085 24.5251 15.0519C24.8383 14.8952 25.1176 14.6784 25.3471 14.4138C25.5765 14.1492 25.7516 13.842 25.8624 13.5097C25.9731 13.1774 26.0173 12.8266 25.9924 12.4773C25.9676 12.1279 25.8742 11.7869 25.7175 11.4736C25.4011 10.841 24.8464 10.3599 24.1753 10.1363C23.5043 9.91268 22.7719 9.96479 22.1393 10.2812C21.5066 10.5975 21.0256 11.1523 20.802 11.8233C20.5783 12.4944 20.6304 13.2268 20.9468 13.8594Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )],
  [collectionIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 36 36" fill="none">
      <path fillRule="evenodd" clipRule="evenodd" d="M10.8711 15.4981C10.8711 15.2772 11.0502 15.0981 11.2711 15.0981H24.7292C24.9501 15.0981 25.1292 15.2772 25.1292 15.4981V23.6537C25.1292 24.302 24.9146 24.9238 24.5326 25.3822C24.1506 25.8406 23.6325 26.0981 23.0923 26.0981H12.908C12.3677 26.0981 11.8497 25.8406 11.4677 25.3822C11.0857 24.9238 10.8711 24.302 10.8711 23.6537V15.4981Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M16.1279 18.2558C15.9222 18.4615 15.8066 18.7405 15.8066 19.0313C15.8066 19.3222 15.9222 19.6012 16.1279 19.8069C16.3336 20.0126 16.6125 20.1281 16.9034 20.1281H19.097C19.3878 20.1281 19.6668 20.0126 19.8725 19.8069C20.0782 19.6012 20.1937 19.3222 20.1937 19.0313C20.1937 18.7405 20.0782 18.4615 19.8725 18.2558C19.6668 18.0501 19.3878 17.9346 19.097 17.9346H16.9034C16.6125 17.9346 16.3336 18.0501 16.1279 18.2558Z" fill="currentColor" />
      <path d="M11.625 10.9302C11.0614 10.9302 10.5209 11.1497 10.1224 11.5405C9.72388 11.9313 9.5 12.4614 9.5 13.014C9.5 13.5667 9.72388 14.0968 10.1224 14.4876C10.5209 14.8784 11.0614 15.0979 11.625 15.0979H24.375C24.9386 15.0979 25.4791 14.8784 25.8776 14.4876C26.2761 14.0968 26.5 13.5667 26.5 13.014C26.5 12.4614 26.2761 11.9313 25.8776 11.5405C25.4791 11.1497 24.9386 10.9302 24.375 10.9302H11.625Z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )],
  [homeSelectIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 16 16" fill="none">
      <path d="M8 0C8.35355 0 8.69263 0.117019 8.94267 0.325323L15.4327 6.98844C15.7964 7.36189 16 7.86261 16 8.38393V14.8887C16 15.1834 15.8595 15.4661 15.6095 15.6744C15.3594 15.8828 15.0203 15.9999 14.6667 15.9999H12C11.6464 15.9999 11.3072 15.8828 11.0572 15.6744C10.8071 15.4661 10.6667 15.1834 10.6667 14.8887V12.6665C10.6667 12.3718 10.5262 12.0892 10.2761 11.8808C10.0261 11.6724 9.68695 11.5554 9.33333 11.5554H6.66667C6.31305 11.5554 5.97391 11.6724 5.72386 11.8808C5.47381 12.0892 5.33333 12.3718 5.33333 12.6665V14.8887C5.33333 15.1834 5.19286 15.4661 4.94281 15.6744C4.69276 15.8828 4.35362 15.9999 4 15.9999H1.33333C0.979712 15.9999 0.640573 15.8828 0.390525 15.6744C0.140476 15.4661 0 15.1834 0 14.8887V8.38393C0 7.86261 0.203555 7.36189 0.567301 6.98844L7.05733 0.325323C7.30737 0.117019 7.64645 0 8 0Z" fill="currentColor" />
    </svg>
  )],
  [graphSelectIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 20 20" fill="none">
      <path d="M7.0625 8.81543L12.8989 5.92383" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12.8989 14.1113L7.0625 11.2197" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12.8019 16.1769C12.5782 15.5058 12.6303 14.7733 12.9466 14.1405C13.263 13.5078 13.8178 13.0266 14.4889 12.8029C14.8212 12.6921 15.1721 12.6479 15.5215 12.6727C15.8709 12.6976 16.212 12.791 16.5253 12.9476C17.1581 13.264 17.6392 13.8188 17.8629 14.4899C18.0867 15.161 18.0346 15.8936 17.7182 16.5263C17.4019 17.1591 16.8471 17.6402 16.1759 17.8639C15.5048 18.0876 14.7723 18.0356 14.1395 17.7192C13.5068 17.4028 13.0256 16.848 12.8019 16.1769Z" fill="currentColor" />
      <path d="M13.5848 6.68143C13.3202 6.45198 13.1033 6.17265 12.9467 5.85941C12.6303 5.22677 12.5782 4.49437 12.8018 3.82332C13.0255 3.15227 13.5065 2.59754 14.1391 2.28117C14.7718 1.96479 15.5042 1.91268 16.1752 2.13631C16.8463 2.35994 17.401 2.84098 17.7174 3.47362C17.874 3.78686 17.9675 4.1279 17.9923 4.47725C18.0172 4.8266 17.973 5.17743 17.8622 5.5097C17.7515 5.84197 17.5764 6.14918 17.347 6.41378C17.1175 6.67839 16.8382 6.8952 16.5249 7.05186C16.2117 7.20851 15.8706 7.30193 15.5213 7.32678C15.1719 7.35164 14.8211 7.30744 14.4888 7.19671C14.1566 7.08598 13.8494 6.91089 13.5848 6.68143Z" fill="currentColor" />
      <path d="M7.33256 9.99969C7.33256 10.4281 7.23123 10.8335 7.05167 11.1926C6.78296 11.7298 6.34074 12.1606 5.79664 12.4151C5.25254 12.6697 4.63844 12.733 4.05383 12.595C3.46922 12.4569 2.94834 12.1256 2.57557 11.6545C2.20281 11.1835 2 10.6004 2 9.99969C2 9.399 2.20281 8.8159 2.57557 8.34486C2.94834 7.87383 3.46922 7.54244 4.05383 7.40439C4.63844 7.26634 5.25254 7.32971 5.79664 7.58424C6.34074 7.83878 6.78296 8.26956 7.05167 8.8068C7.23123 9.16591 7.33256 9.57124 7.33256 9.99969Z" fill="currentColor" />
    </svg>
  )],
  // 자산의 #323232 컷아웃은 활성 pill 배경색이다. 토큰(--soft)으로 옮겨 배경과 계속 일치시킨다.
  [logSelectIcon, (iconClassName) => (
    <svg aria-hidden className={iconClassName} viewBox="0 0 20 20" fill="none">
      <path fillRule="evenodd" clipRule="evenodd" d="M2.5625 6.18848H17.4375V14.9377C17.4375 15.6007 17.2136 16.2365 16.8151 16.7053C16.4166 17.1741 15.8761 17.4374 15.3125 17.4374H4.6875C4.12391 17.4374 3.58341 17.1741 3.1849 16.7053C2.78638 16.2365 2.5625 15.6007 2.5625 14.9377V6.18848Z" fill="currentColor" />
      <path d="M8.1862 10.047C7.98694 10.2463 7.875 10.5165 7.875 10.7983C7.875 11.0801 7.98694 11.3504 8.1862 11.5496C8.38546 11.7489 8.65571 11.8608 8.9375 11.8608H11.0625C11.3443 11.8608 11.6145 11.7489 11.8138 11.5496C12.0131 11.3504 12.125 11.0801 12.125 10.7983C12.125 10.5165 12.0131 10.2463 11.8138 10.047C11.6145 9.84778 11.3443 9.73584 11.0625 9.73584H8.9375C8.65571 9.73584 8.38546 9.84778 8.1862 10.047Z" fill="var(--soft)" />
      <path d="M3.625 2.5625C3.06141 2.5625 2.52091 2.77276 2.1224 3.14704C1.72388 3.52131 1.5 4.02893 1.5 4.55823C1.5 5.08753 1.72388 5.59516 2.1224 5.96943C2.52091 6.3437 3.06141 6.55396 3.625 6.55396H16.375C16.9386 6.55396 17.4791 6.3437 17.8776 5.96943C18.2761 5.59516 18.5 5.08753 18.5 4.55823C18.5 4.02893 18.2761 3.52131 17.8776 3.14704C17.4791 2.77276 16.9386 2.5625 16.375 2.5625H3.625Z" fill="currentColor" />
      <rect x="2.5625" y="6.28662" width="14.8749" height="1.275" fill="var(--soft)" />
    </svg>
  )]
]);

export function SvgIcon({ src, className }: { src: SvgAsset; className?: string }) {
  // className을 넘겨도 .svg-icon의 object-fit/flex 기본 동작은 유지한다
  const iconClassName = cx("svg-icon", className ?? "svg-icon-fill");
  const renderInline = inlineIconRenderers.get(src);

  if (renderInline) return renderInline(iconClassName);

  // 인라인 렌더러가 없는 자산은 항상 정적 이미지다
  return <Image alt="" aria-hidden className={iconClassName} src={src as StaticImageData} />;
}
