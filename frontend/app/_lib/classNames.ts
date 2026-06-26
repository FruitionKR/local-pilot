/**
 * 조건부 className을 합성합니다.
 * falsy(빈 문자열/false/null/undefined) 값은 걸러내고 공백으로 join합니다.
 */
export function cx(...classes: Array<string | number | false | null | undefined>): string {
  return classes.filter(Boolean).join(" ");
}
