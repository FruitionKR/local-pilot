import type { StaticImageData } from "next/image";
import Image from "next/image";
import archiveIcon from "../../svg/Archive.svg";
import arrowIcon from "../../svg/arrow.svg";
import chatCheckIcon from "../../svg/chat_check.svg";
import collectionIcon from "../../svg/Collection.svg";
import conceptPageIcon from "../../svg/conceptpage.svg";
import fileIcon from "../../svg/file.svg";
import frameIcon from "../../svg/Frame.svg";
import homeIcon from "../../svg/home.svg";
import lightningIcon from "../../svg/LightningBoltOutline.svg";
import rawPageIcon from "../../svg/Ellipse.svg";
import sideboxIcon from "../../svg/sidebox.svg";
import sourcePageIcon from "../../svg/source_page.svg";
import sparkleIcon from "../../svg/icon.svg";
import settingIcon from "../../svg/uil_setting.svg";
import switchIcon from "../../svg/switch.svg";
import toggleIcon from "../../svg/toggle.svg";
import userCircleIcon from "../../svg/UserCircle.svg";

export type SvgAsset = StaticImageData;

export {
  archiveIcon,
  arrowIcon,
  chatCheckIcon,
  collectionIcon,
  conceptPageIcon,
  fileIcon,
  frameIcon,
  homeIcon,
  lightningIcon,
  rawPageIcon,
  sideboxIcon,
  sourcePageIcon,
  sparkleIcon,
  settingIcon,
  switchIcon,
  toggleIcon,
  userCircleIcon
};

export function SvgIcon({ src, className }: { src: SvgAsset; className?: string }) {
  const iconClassName = className || "svg-icon";

  if (src === archiveIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 16 16" fill="none">
        <path d="M2.7501 1.87491C2.28597 1.87491 1.84085 2.05928 1.51266 2.38747C1.18447 2.71566 1.0001 3.16078 1.0001 3.62491C1.0001 4.08904 1.18447 4.53416 1.51266 4.86235C1.84085 5.19053 2.28597 5.37491 2.7501 5.37491H13.2501C13.7142 5.37491 14.1593 5.19053 14.4875 4.86235C14.8157 4.53416 15.0001 4.08904 15.0001 3.62491C15.0001 3.16078 14.8157 2.71566 14.4875 2.38747C14.1593 2.05928 13.7142 1.87491 13.2501 1.87491H2.7501Z" fill="currentColor" opacity="0.55" />
        <path fillRule="evenodd" clipRule="evenodd" d="M1.87498 5.1064H14.125V12.1207C14.125 12.6522 13.9406 13.162 13.6124 13.5378C13.2842 13.9137 12.8391 14.1248 12.375 14.1248H3.62498C3.16086 14.1248 2.71574 13.9137 2.38755 13.5378C2.05936 13.162 1.87498 12.6522 1.87498 12.1207V5.1064Z" fill="currentColor" opacity="0.55" />
        <path d="M6.50627 8.25621C6.34217 8.4203 6.24998 8.64286 6.24998 8.87492C6.24998 9.10699 6.34217 9.32955 6.50627 9.49364C6.67036 9.65774 6.89292 9.74992 7.12498 9.74992H8.87498C9.10705 9.74992 9.32961 9.65774 9.4937 9.49364C9.6578 9.32955 9.74998 9.10699 9.74998 8.87492C9.74998 8.64286 9.6578 8.4203 9.4937 8.25621C9.32961 8.09211 9.10705 7.99992 8.87498 7.99992H7.12498C6.89292 7.99992 6.67036 8.09211 6.50627 8.25621Z" fill="currentColor" />
        <rect x="1.87498" y="5.15607" width="12.2499" height="0.796814" fill="currentColor" />
      </svg>
    );
  }

  if (src === collectionIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 23 23" fill="none">
        <path d="M7.6875 2.9375C7.37256 2.9375 7.07051 3.06261 6.84781 3.28531C6.62511 3.50801 6.5 3.81006 6.5 4.125C6.5 4.43994 6.62511 4.74199 6.84781 4.96469C7.07051 5.18739 7.37256 5.3125 7.6875 5.3125H14.8125C15.1274 5.3125 15.4295 5.18739 15.6522 4.96469C15.8749 4.74199 16 4.43994 16 4.125C16 3.81006 15.8749 3.50801 15.6522 3.28531C15.4295 3.06261 15.1274 2.9375 14.8125 2.9375H7.6875ZM4.125 7.6875C4.125 7.37256 4.25011 7.07051 4.47281 6.84781C4.69551 6.62511 4.99756 6.5 5.3125 6.5H17.1875C17.5024 6.5 17.8045 6.62511 18.0272 6.84781C18.2499 7.07051 18.375 7.37256 18.375 7.6875C18.375 8.00244 18.2499 8.30449 18.0272 8.52719C17.8045 8.74989 17.5024 8.875 17.1875 8.875H5.3125C4.99756 8.875 4.69551 8.74989 4.47281 8.52719C4.25011 8.30449 4.125 8.00244 4.125 7.6875ZM1.75 12.4375C1.75 11.8076 2.00022 11.2035 2.44562 10.7581C2.89102 10.3127 3.49511 10.0625 4.125 10.0625H18.375C19.0049 10.0625 19.609 10.3127 20.0544 10.7581C20.4998 11.2035 20.75 11.8076 20.75 12.4375V17.1875C20.75 17.8174 20.4998 18.4215 20.0544 18.8669C19.609 19.3123 19.0049 19.5625 18.375 19.5625H4.125C3.49511 19.5625 2.89102 19.3123 2.44562 18.8669C2.00022 18.4215 1.75 17.8174 1.75 17.1875V12.4375Z" fill="currentColor" />
      </svg>
    );
  }

  if (src === lightningIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 23 23" fill="none">
        <path d="M12.1875 9.375V2.8125L3.74998 13.125H10.3125V19.6875L18.75 9.375H12.1875Z" fill="currentColor" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }

  if (src === userCircleIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 28 28" fill="none">
        <path fillRule="evenodd" clipRule="evenodd" d="M27.7667 13.8833C27.7667 17.5654 26.304 21.0967 23.7003 23.7003C21.0967 26.304 17.5654 27.7667 13.8833 27.7667C10.2012 27.7667 6.66996 26.304 4.06633 23.7003C1.4627 21.0967 0 17.5654 0 13.8833C0 10.2012 1.4627 6.66996 4.06633 4.06633C6.66996 1.4627 10.2012 0 13.8833 0C17.5654 0 21.0967 1.4627 23.7003 4.06633C26.304 6.66996 27.7667 10.2012 27.7667 13.8833Z" fill="currentColor" opacity="0.55" />
        <path d="M16.999 13.2058C17.7679 12.4369 18.1999 11.394 18.1999 10.3067C18.1999 9.21926 17.7679 8.17641 16.999 7.40751C16.2301 6.63861 15.1873 6.20665 14.0999 6.20665C13.0125 6.20665 11.9696 6.63861 11.2007 7.40751C10.4318 8.17641 9.99988 9.21926 9.99988 10.3067C9.99988 11.394 10.4318 12.4369 11.2007 13.2058C11.9696 13.9747 13.0125 14.4067 14.0999 14.4067C15.1873 14.4067 16.2301 13.9747 16.999 13.2058Z" fill="currentColor" />
        <path d="M8.65361 18.0829C10.249 17.049 12.1047 16.4997 14.0001 16.5001C15.8955 16.4997 17.7512 17.049 19.3466 18.0829C20.5706 18.876 21.602 19.6344 22.3726 20.7306C22.8182 21.3644 22.6896 22.2199 22.1277 22.7533C21.1937 23.6397 20.1216 24.3692 18.9516 24.911C17.3982 25.6304 15.709 26.0019 14.0001 26C12.2912 26.0019 10.6021 25.6304 9.04863 24.911C7.87868 24.3692 6.80652 23.6397 5.87259 22.7533C5.3106 22.2199 5.18207 21.3644 5.62768 20.7306C6.39828 19.6344 7.4296 18.876 8.65361 18.0829Z" fill="currentColor" />
      </svg>
    );
  }

  if (src === conceptPageIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 10 10" fill="none">
        <circle cx="5" cy="5" r="5" fill="currentColor" />
      </svg>
    );
  }

  if (src === fileIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 18 18" fill="none">
        <path d="M10.25 2.75H5.25C4.91848 2.75 4.60054 2.8817 4.36612 3.11612C4.1317 3.35054 4 3.66848 4 4V14C4 14.3315 4.1317 14.6495 4.36612 14.8839C4.60054 15.1183 4.91848 15.25 5.25 15.25H12.75C13.0815 15.25 13.3995 15.1183 13.6339 14.8839C13.8683 14.6495 14 14.3315 14 14V6.5L10.25 2.75Z" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M10.25 6.5V2.75L14 6.5H10.25Z" fill="currentColor" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }

  if (src === homeIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 23 23" fill="none">
        <path d="M11.25 2.53125C11.4829 2.53125 11.6832 2.60792 11.8174 2.71191L18.9316 10.0156C19.5965 10.6983 19.9687 11.6134 19.9688 12.5664V19.4844C19.9687 19.558 19.9348 19.6677 19.8057 19.7754C19.6719 19.8868 19.4623 19.9687 19.2188 19.9688H16.0312C15.7877 19.9688 15.5781 19.8868 15.4443 19.7754C15.3152 19.6677 15.2813 19.558 15.2812 19.4844V16.8281C15.2812 16.1973 14.9792 15.6307 14.5107 15.2402C14.0468 14.8537 13.4454 14.6563 12.8438 14.6562H9.65625C9.0546 14.6562 8.45324 14.8537 7.98926 15.2402C7.52075 15.6307 7.21875 16.1973 7.21875 16.8281V19.4844C7.2187 19.558 7.18483 19.6678 7.05566 19.7754C6.92191 19.8868 6.71235 19.9688 6.46875 19.9688H3.28125C3.03765 19.9687 2.82809 19.8868 2.69434 19.7754C2.56517 19.6678 2.5313 19.558 2.53125 19.4844V12.5664C2.53126 11.6134 2.90349 10.6983 3.56836 10.0156L10.6816 2.71191C10.8158 2.60767 11.0167 2.53125 11.25 2.53125Z" fill="currentColor" stroke="currentColor" strokeWidth="1.6875" />
      </svg>
    );
  }

  if (src === sparkleIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 16 16" fill="none">
        <path d="M4.64443 0.327192C5.26616 0.175049 5.8942 0.55616 6.04677 1.17778L6.79677 4.24223L11.7645 1.39164C12.3199 1.07278 13.0297 1.26499 13.3485 1.82036C13.667 2.37564 13.475 3.0846 12.9198 3.40336L9.1288 5.57914L11.9257 6.49028C12.5345 6.68871 12.8672 7.34432 12.6688 7.95317C12.4703 8.56169 11.8156 8.89451 11.2069 8.69633L7.60048 7.52055L9.25478 14.2706C9.40688 14.8922 9.02657 15.5192 8.40517 15.6719C7.78329 15.8243 7.15441 15.4441 7.00185 14.8223L5.27821 7.78911L2.19423 9.56157C1.63901 9.88012 0.930117 9.68787 0.611222 9.13286C0.292662 8.57764 0.483935 7.86875 1.03896 7.54985L3.44521 6.16801L1.75185 5.61625C1.14328 5.4178 0.810602 4.76303 1.00868 4.15434C1.20707 3.54566 1.86185 3.2121 2.4706 3.4102L4.35536 4.02348L3.79384 1.72954C3.64174 1.10777 4.02273 0.479682 4.64443 0.327192Z" fill="currentColor" />
      </svg>
    );
  }

  if (src === sideboxIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 20 20" fill="none">
        <rect x="4" y="4.5" width="12" height="11" rx="1.81333" stroke="currentColor" />
        <path d="M10.9558 4.50054L10.9558 6.75116C10.9558 6.91028 11.0191 7.06286 11.1316 7.17536L12.58 8.62376C12.6925 8.73626 12.7558 8.88884 12.7558 9.04796L12.7558 10.9512C12.7558 11.1103 12.6925 11.2629 12.58 11.3754L11.1316 12.8238C11.0191 12.9363 10.9558 13.0888 10.9558 13.248L10.9558 15.4998" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }

  if (src === sourcePageIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 10 10" fill="none">
        <circle cx="5" cy="5" r="5" fill="currentColor" />
      </svg>
    );
  }

  if (src === settingIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 23 23" fill="none">
        <path d="M18.4046 13.3636L19.25 14.3147L17.8973 16.6608L16.6503 16.4072C15.8891 16.2516 15.0974 16.3809 14.4253 16.7705C13.7532 17.1601 13.2475 17.783 13.0043 18.5208L12.6027 19.7044H9.89729L9.51684 18.4997C9.27362 17.7618 8.76797 17.139 8.09587 16.7494C7.42377 16.3598 6.632 16.2305 5.87087 16.3861L4.62384 16.6397L3.25 14.3042L4.09544 13.353C4.61534 12.7718 4.90277 12.0193 4.90277 11.2394C4.90277 10.4596 4.61534 9.70708 4.09544 9.12581L3.25 8.17469L4.60271 5.84972L5.84974 6.10336C6.61087 6.25894 7.40263 6.12965 8.07473 5.74003C8.74684 5.3504 9.25249 4.72757 9.49571 3.98975L9.89729 2.79556H12.6027L13.0043 4.00032C13.2475 4.73813 13.7532 5.36097 14.4253 5.75059C15.0974 6.14022 15.8891 6.26951 16.6503 6.11393L17.8973 5.86029L19.25 8.2064L18.4046 9.15752C17.8905 9.73745 17.6066 10.4856 17.6066 11.2606C17.6066 12.0355 17.8905 12.7837 18.4046 13.3636Z" fill="currentColor" stroke="currentColor" strokeWidth="2.4" opacity="0.72" />
        <circle cx="11.2498" cy="11.2502" r="3.1" fill="currentColor" stroke="currentColor" />
      </svg>
    );
  }

  return <Image alt="" aria-hidden className={iconClassName} src={src} />;
}
