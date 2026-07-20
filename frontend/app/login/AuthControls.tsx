import Image from "next/image";
import type { ChangeEventHandler, HTMLInputAutoCompleteAttribute, HTMLInputTypeAttribute } from "react";
import errorIcon from "../../svg/auth/auth-error-circle.svg";
import googleLogo from "../../svg/auth/auth-google-logo.svg";
import kakaoLogo from "../../svg/auth/auth-kakao-logo.svg";
import naverLogo from "../../svg/auth/auth-naver-logo.svg";
import passwordHiddenIcon from "../../svg/auth/auth-password-hidden.svg";
import { getOAuthAuthorizationUrl } from "../_lib/api";

type AuthFieldProps = {
  autoComplete?: HTMLInputAutoCompleteAttribute;
  label: string;
  name: string;
  onChange: ChangeEventHandler<HTMLInputElement>;
  placeholder: string;
  required?: boolean;
  timer?: string;
  type?: HTMLInputTypeAttribute;
  value: string;
};

export function AuthField({
  autoComplete,
  label,
  name,
  onChange,
  placeholder,
  required = true,
  timer,
  type = "text",
  value
}: AuthFieldProps) {
  const isPassword = type === "password";

  return (
    <label className="auth-field">
      <span>{label}</span>
      <span className={timer || isPassword ? "auth-field-control has-adornment" : "auth-field-control"}>
        <input
          autoComplete={autoComplete}
          name={name}
          onChange={onChange}
          placeholder={placeholder}
          required={required}
          type={type}
          value={value}
        />
        {timer ? <span aria-hidden className="auth-field-timer">{timer}</span> : null}
        {isPassword ? (
          <Image alt="" aria-hidden className="auth-password-icon" src={passwordHiddenIcon} />
        ) : null}
      </span>
    </label>
  );
}

export function AuthError({ children }: { children: string }) {
  return (
    <p className="auth-error" role="alert">
      <Image alt="" aria-hidden src={errorIcon} />
      <span>{children}</span>
    </p>
  );
}

export function AuthSubmitButton({ children, disabled = false }: { children: string; disabled?: boolean }) {
  return (
    <button className="auth-submit" disabled={disabled} type="submit">
      {children}
    </button>
  );
}

export function SocialLoginButtons() {
  const providers = [
    { name: "카카오", provider: "kakao", logo: kakaoLogo },
    { name: "네이버", provider: "naver", logo: naverLogo },
    { name: "Google", provider: "google", logo: googleLogo }
  ] as const;

  return (
    <div className="auth-social">
      <div className="auth-social-divider">
        <span />
        <p>간편 로그인</p>
        <span />
      </div>
      <div className="auth-social-buttons">
        {providers.map(({ logo, name, provider }) => (
          <button
            aria-label={`${name}로 로그인`}
            className={`auth-social-button auth-social-button--${provider}`}
            key={provider}
            onClick={() => window.location.assign(getOAuthAuthorizationUrl(provider))}
            type="button"
          >
            <Image alt="" aria-hidden src={logo} />
          </button>
        ))}
      </div>
    </div>
  );
}
