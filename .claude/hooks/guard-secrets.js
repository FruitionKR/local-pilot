// PreToolUse 가드: .env 등 시크릿 파일의 평문 값이 툴 출력에 찍히는 것을 차단한다.
// - Read: .env 파일 열기 차단(.env.example/sample/template/dist 제외)
// - Grep: .env 파일을 content 모드로 검색해 값이 찍히는 것을 차단
// - Bash: 실제 .env 경로를 다루는 명령은 기본 차단(allowlist).
//         정해진 키 추출·값 마스킹 형식이거나, 내용을 출력하지 않는 명령 형태만 허용.
// 이 hook은 실수로 값을 출력하는 것을 막는 안전장치이지, 의도적 우회를 막는 장치가 아니다.
// 한계: 파일을 다른 경로로 복사·이동한 뒤 그 경로를 읽는 우회는 명령 단위 검사로 막을 수 없다.
//       경로를 따옴표로 쪼개면(cat infra/.en"v") 문자열 매칭 자체를 빠져나간다.
//       셸 문법을 정규식으로 완전히 따라갈 수는 없어서, 이 경로는 막지 않는다.
//       또 명령 문자열 전체를 검사하므로, 파일을 읽지 않고 경로를 언급만 하는 명령도
//       허용 목록에 없으면 차단된다(오탐).
//       Grep 차단은 path/glob에 .env가 직접 적힌 경우만 걸린다. 상위 디렉터리를 대상으로 한
//       content 검색은 .gitignore에 .env가 있고 Grep이 이를 존중한다는 전제에 기대고 있다.
//
// 사용: 기본 비활성이다. 쓰려면 개인 설정(.claude/settings.local.json)에 등록한다.
//   {"hooks":{"PreToolUse":[{"matcher":"Bash|Read|Grep",
//     "hooks":[{"type":"command",
//       "command":"node \"$CLAUDE_PROJECT_DIR/.claude/hooks/guard-secrets.js\""}]}]}}
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
    String.raw`^(?:ls\b|test\b|git\s+check-ignore\b|cp\s+['"]?[A-Za-z0-9_./-]*\.env\.(?:example|sample|template|dist)\b)`,
    "i"
  );
  // 파일을 읽지 않고 텍스트 안에서 경로를 언급만 하는 명령.
  // git commit -F/--file 은 파일 내용을 메시지로 읽으므로 제외한다.
  // 인용 구간을 지운 문자열에 적용하므로, 커밋 메시지 본문에 나오는 --file 은 옵션으로 오인하지 않는다.
  const safeMentionOnlyCommand = new RegExp(
    String.raw`^(?:echo\b|printf\b|git\s+commit\b(?![\s\S]*\s(?:-F|--file)\b))`,
    "i"
  );
  // docker compose는 서브커맨드에 따라 해석된 값을 그대로 출력한다(config, exec, run 등).
  // 플래그를 건너뛴 첫 서브커맨드가 허용 목록에 있을 때만 통과시킨다.
  const dockerComposeSafeSubcommands = new Set([
    "up", "down", "start", "stop", "restart", "ps", "logs", "build", "pull", "push", "images", "top", "kill", "rm",
  ]);
  const isSafeDockerCompose = (cmd) => {
    const tokens = cmd.split(/\s+/);
    if (!/^docker$/i.test(tokens[0]) || !/^compose$/i.test(tokens[1] || "")) return false;
    for (let i = 2; i < tokens.length; i += 1) {
      const token = tokens[i];
      if (!token.startsWith("-")) return dockerComposeSafeSubcommands.has(token.toLowerCase());
      // `--env-file <path>` 처럼 값을 따로 받는 플래그는 다음 토큰까지 건너뛴다.
      // boolean 전역 플래그(--dry-run 등)는 서브커맨드를 건너뛰어 차단되지만, 안전한 쪽으로 실패한다.
      if (!token.includes("=")) i += 1;
    }
    return false;
  };
  // 따옴표 안의 내용은 셸이 인자 하나로 넘기므로 명령 구분자가 아니다.
  // 여러 줄 커밋 메시지 같은 인자를 오탐하지 않도록 인용 구간을 지운 뒤 구분자를 검사한다.
  // 닫히지 않은 따옴표는 매칭되지 않아 원문이 그대로 남고, 안전한 쪽으로 차단된다.
  const stripQuoted = (s) => s.replace(/'[^']*'|"[^"]*"/g, "");
  // 출력을 버리거나 stderr를 stdout에 합치는 리다이렉션은 값을 노출하지 않으므로 구분자로 보지 않는다.
  // 파일로 내보내는 리다이렉션은 그대로 남겨 차단한다.
  const stripHarmlessRedirect = (s) =>
    s.replace(/\s(?:\d?>>?|&>)\s*\/dev\/null\b/g, "").replace(/\s2>&1(?!\d)/g, "");
  // 명령 구분자·리다이렉션. 개행도 셸에서는 `;`와 같은 구분자다.
  const hasSeparator = /[;&|<>\n\r]/;
  // 치환·확장은 인용 안에서도 명령을 실행하므로 원문 기준으로 검사한다.
  const hasExpansion = /`|\$\(/;

  const bashDenyReason =
    "시크릿 평문 출력 차단: 실제 .env 파일을 다루는 명령입니다. 키만 추출하거나(grep -oE '^[A-Za-z_]+=' 파일), " +
    "정해진 형식으로 값을 마스킹하세요(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' 파일 | sed 's/=.*/=***/'). " +
    "docker compose <up|down|ps|logs 등>, ls, test, git check-ignore, cp <예시파일> <대상>, " +
    "그리고 파일을 읽지 않는 echo/printf/git commit 형태는 허용됩니다. .env.example 은 허용됩니다.";

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
    // 일부 환경은 다른 PreToolUse hook이 명령 앞에 프록시 래퍼를 붙인다(rtk git status 등).
    // 래퍼를 벗겨야 아래 allowlist가 실제 명령 기준으로 판정된다.
    // 벗긴 뒤에도 같은 allowlist를 적용하므로 rtk proxy cat 같은 우회는 그대로 차단된다.
    const cmd = String((input.tool_input && input.tool_input.command) || "")
      .trim()
      .replace(/^rtk\s+/i, "");
    if (!isEnvFile(cmd)) process.exit(0);
    if (safeKeyExtraction.test(cmd) || safeMaskedOutput.test(cmd)) process.exit(0);
    const unquoted = stripHarmlessRedirect(stripQuoted(cmd));
    if (
      !hasSeparator.test(unquoted) &&
      !hasExpansion.test(cmd) &&
      (safeNonReadingCommand.test(cmd) ||
        safeMentionOnlyCommand.test(unquoted) ||
        isSafeDockerCompose(cmd))
    ) {
      process.exit(0);
    }
    deny(bashDenyReason);
  }
});
