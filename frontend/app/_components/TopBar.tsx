import { searchIcon, SvgIcon, toggleIcon, userCircleIcon } from "./SvgIcon";

export function TopBar() {
  return (
    <header className="topbar">
      <div className="brand">
        <div className="workspace-mark" aria-label="부산대학교">부</div>
        <button className="school">부산대학교 <SvgIcon src={toggleIcon} className="school-toggle-icon" /></button>
      </div>
      <label className="search-box">
        <SvgIcon src={searchIcon} className="search-icon" />
        <input placeholder="자료명, 관련 내용 검색" />
      </label>
      <div className="profile">
        <div>
          <strong>메타몽</strong>
          <span>온라인</span>
        </div>
        <SvgIcon src={userCircleIcon} className="profile-icon" />
      </div>
    </header>
  );
}
