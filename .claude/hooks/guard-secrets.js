// PreToolUse 가드: .env 등 시크릿 파일의 평문 값이 툴 출력에 찍히는 것을 차단한다.
// - Read: .env 파일 열기 차단(.env.example/sample/template/dist 제외)
// - Grep: .env 파일을 content 모드로 검색해 값이 찍히는 것을 차단
// - Bash: 실제 .env 경로를 다루는 명령은 기본 차단(allowlist).
//         정해진 키 추출·값 마스킹 형식이거나, 내용을 출력하지 않는 명령 형태만 허용.
// 한계: 파일을 다른 경로로 복사·이동한 뒤 그 경로를 읽는 우회는 명령 단위 검사로 막을 수 없다.
let data = "";
process.stdin.on("data", (c) => (data += c));
process.stdin.on("end", () => {
  let input = {};
  try { input = JSON.parse(data); } catch (e) { process.exit(0); }

  const tool = input.tool_name || "";
  const deny = (reason) => {
    console.log(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
        permissionDecisionReason: reason,
      },
    }));
    process.exit(0);
  };

  // .env 계열 파일인지(예시/샘플 파일은 제외).
  // 확장자 체인 뒤에 경계를 둬 `app.environment.ts` 같은 이름은 걸리지 않게 한다.
  const isEnvFile = (s) => {
    const envFiles =
      String(s).match(/\.env(?:\.[A-Za-z0-9_-]+)*(?![A-Za-z0-9_-])/gi) || [];
    return envFiles.some(
      (file) => !/^\.env\.(example|sample|template|dist)(?:\.|$)/i.test(file)
    );
  };

  const envPathPattern = String.raw`['"]?[A-Za-z0-9_./-]*\.env(?:\.[A-Za-z0-9_-]+)*['"]?`;
  const keyPattern = String.raw`(?:\^\[A-Za-z_\]\+=|\^\[A-Za-z_\]\[A-Za-z0-9_\]\*=)`;
  const safeKeyExtraction = new RegExp(
    String.raw`^(?:grep\s+-oE|rg\s+-o)\s+(['"])${keyPattern}\1\s+${envPathPattern}$`,
    "i"
  );
  const safeMaskedOutput = new RegExp(
    String.raw`^grep\s+-E\s+(['"])${keyPattern}\1\s+${envPathPattern}\s*\|\s*sed\s+(['"])s/=\.\*/=\*\*\*/\2$`,
    "i"
  );
  // 파일 내용을 출력하지 않고 경로만 참조하는 명령 형태.
  // cp는 예시 파일을 원본으로 삼는 초기 설정(cp infra/.env.example infra/.env)만 허용한다.
  const safeNonReadingCommand = new RegExp(
    String.raw`^(?:docker\s+compose\b|ls\b|test\b|git\s+check-ignore\b|cp\s+['"]?[A-Za-z0-9_./-]*\.env\.(?:example|sample|template|dist)\b)`,
    "i"
  );
  // 명령 연결·리다이렉션이 있으면 뒤에 평문 출력을 붙일 수 있으므로 형태 허용을 적용하지 않는다.
  const hasChaining = /[;&|<>`]|\$\(/;

  const bashDenyReason =
    "시크릿 평문 출력 차단: 실제 .env 파일을 다루는 명령입니다. 키만 추출하거나(grep -oE '^[A-Za-z_]+=' 파일), " +
    "정해진 형식으로 값을 마스킹하세요(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' 파일 | sed 's/=.*/=***/'). " +
    "docker compose, ls, test, git check-ignore, cp <예시파일> <대상> 형태는 허용됩니다. .env.example 은 허용됩니다.";

  if (tool === "Read") {
    const p = (input.tool_input && input.tool_input.file_path) || "";
    if (isEnvFile(p)) {
      deny(
        "시크릿 보호: .env 파일은 Read로 열 수 없습니다. 값 없이 구조만 보려면 키만 추출(grep -oE '^[A-Za-z_]+=' 파일), " +
        "셸에 로드된 변수는 길이만(${#VAR}) 확인하세요. .env.example 은 허용됩니다."
      );
    }
    process.exit(0);
  }

  if (tool === "Grep") {
    const gi = input.tool_input || {};
    const target = `${gi.path || ""} ${gi.glob || ""}`;
    if (isEnvFile(target) && (gi.output_mode || "files_with_matches") === "content") {
      deny(
        "시크릿 보호: .env 파일은 Grep content 모드로 검색할 수 없습니다. 매칭된 줄 전체가 출력돼 값이 노출됩니다. " +
        "output_mode를 files_with_matches 또는 count로 쓰거나, 키만 추출(grep -oE '^[A-Za-z_]+=' 파일)하세요. .env.example 은 허용됩니다."
      );
    }
    process.exit(0);
  }

  if (tool === "Bash") {
    const cmd = String((input.tool_input && input.tool_input.command) || "").trim();
    if (!isEnvFile(cmd)) process.exit(0);
    if (safeKeyExtraction.test(cmd) || safeMaskedOutput.test(cmd)) process.exit(0);
    if (!hasChaining.test(cmd) && safeNonReadingCommand.test(cmd)) process.exit(0);
    deny(bashDenyReason);
  }
});
