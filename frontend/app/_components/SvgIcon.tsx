import type { StaticImageData } from "next/image";
import Image from "next/image";
import arrowIcon from "../../svg/arrow.svg";
import chatCheckIcon from "../../svg/chat_check.svg";
import collectionIcon from "../../svg/CollectionOutLine.svg";
import conceptPageIcon from "../../svg/conceptpage.svg";
import fileIcon from "../../svg/file.svg";
import frameIcon from "../../svg/Frame.svg";
import homeIcon from "../../svg/home.svg";
import lightningIcon from "../../svg/LightningBoltOutline.svg";
import rawPageIcon from "../../svg/raw.svg";
import searchIcon from "../../svg/search.svg";
import sideboxIcon from "../../svg/sidebox.svg";
import sourcePageIcon from "../../svg/source_page.svg";
import sparkleIcon from "../../svg/icon.svg";
import settingIcon from "../../svg/uil_setting.svg";
import switchIcon from "../../svg/switch.svg";
import toggleIcon from "../../svg/toggle.svg";
import userCircleIcon from "../../svg/UserCircle.svg";

export type SvgAsset = StaticImageData;

const archiveIcon = { src: "archive-icon", width: 16, height: 16 } as StaticImageData;

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
  searchIcon,
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

  if (src === userCircleIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 28 28" fill="none">
        <path fillRule="evenodd" clipRule="evenodd" d="M27.7667 13.8833C27.7667 17.5654 26.304 21.0967 23.7003 23.7003C21.0967 26.304 17.5654 27.7667 13.8833 27.7667C10.2012 27.7667 6.66996 26.304 4.06633 23.7003C1.4627 21.0967 0 17.5654 0 13.8833C0 10.2012 1.4627 6.66996 4.06633 4.06633C6.66996 1.4627 10.2012 0 13.8833 0C17.5654 0 21.0967 1.4627 23.7003 4.06633C26.304 6.66996 27.7667 10.2012 27.7667 13.8833Z" fill="currentColor" opacity="0.55" />
        <path d="M16.999 13.2058C17.7679 12.4369 18.1999 11.394 18.1999 10.3067C18.1999 9.21926 17.7679 8.17641 16.999 7.40751C16.2301 6.63861 15.1873 6.20665 14.0999 6.20665C13.0125 6.20665 11.9696 6.63861 11.2007 7.40751C10.4318 8.17641 9.99988 9.21926 9.99988 10.3067C9.99988 11.394 10.4318 12.4369 11.2007 13.2058C11.9696 13.9747 13.0125 14.4067 14.0999 14.4067C15.1873 14.4067 16.2301 13.9747 16.999 13.2058Z" fill="currentColor" />
        <path d="M8.65361 18.0829C10.249 17.049 12.1047 16.4997 14.0001 16.5001C15.8955 16.4997 17.7512 17.049 19.3466 18.0829C20.5706 18.876 21.602 19.6344 22.3726 20.7306C22.8182 21.3644 22.6896 22.2199 22.1277 22.7533C21.1937 23.6397 20.1216 24.3692 18.9516 24.911C17.3982 25.6304 15.709 26.0019 14.0001 26C12.2912 26.0019 10.6021 25.6304 9.04863 24.911C7.87868 24.3692 6.80652 23.6397 5.87259 22.7533C5.3106 22.2199 5.18207 21.3644 5.62768 20.7306C6.39828 19.6344 7.4296 18.876 8.65361 18.0829Z" fill="currentColor" />
      </svg>
    );
  }

  if (src === fileIcon) {
    return (
      <svg aria-hidden className={iconClassName} viewBox="0 0 18 18" fill="none">
        <path d="M10.09 2.33L14.42 6.65L14.6 6.83V14C14.6 14.49 14.41 14.96 14.06 15.31C13.71 15.66 13.24 15.85 12.75 15.85H5.25C4.76 15.85 4.29 15.66 3.94 15.31C3.59 14.96 3.4 14.49 3.4 14V4C3.4 3.51 3.59 3.04 3.94 2.69C4.29 2.34 4.76 2.15 5.25 2.15H9.91L10.09 2.33Z" fill="currentColor" opacity="0.3" />
        <path d="M10.47 2.5L14.22 6.25H10.47V2.5Z" fill="currentColor" opacity="0.85" />
        <path d="M10.09 2.33L14.42 6.65L14.6 6.83V14C14.6 14.49 14.41 14.96 14.06 15.31C13.71 15.66 13.24 15.85 12.75 15.85H5.25C4.76 15.85 4.29 15.66 3.94 15.31C3.59 14.96 3.4 14.49 3.4 14V4C3.4 3.51 3.59 3.04 3.94 2.69C4.29 2.34 4.76 2.15 5.25 2.15H9.91L10.09 2.33Z" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
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

  return <Image alt="" aria-hidden className={iconClassName} src={src} />;
}
