// PreToolUse 가드: .env 등 시크릿 파일의 평문 값이 툴 출력에 찍히는 것을 차단한다.
// - Read: .env 파일 열기 차단(.env.example/sample/template/dist 제외)
// - Bash: cat/grep/head/tail 등으로 .env 파일을 덤프하는 명령 차단.
//         전체 명령이 안전한 키 추출 또는 값 마스킹 형식과 일치할 때만 허용.
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

  // .env 계열 파일인지(예시/샘플 파일은 제외)
  const isEnvFile = (s) => {
    const envFiles = String(s).match(/\.env(?:\.[A-Za-z0-9_-]+)*/gi) || [];
    return envFiles.some(
      (file) => !/^\.env\.(example|sample|template|dist)(?:\.|$)/i.test(file)
    );
  };

  const envPathPattern = String.raw`[A-Za-z0-9_./-]*\.env(?:\.[A-Za-z0-9_-]+)*`;
  const keyPattern = String.raw`(?:\^\[A-Za-z_\]\+=|\^\[A-Za-z_\]\[A-Za-z0-9_\]\*=)`;
  const safeKeyExtraction = new RegExp(
    String.raw`^(?:grep\s+-oE|rg\s+-o)\s+(['"])${keyPattern}\1\s+${envPathPattern}$`,
    "i"
  );
  const safeMaskedOutput = new RegExp(
    String.raw`^grep\s+-E\s+(['"])${keyPattern}\1\s+${envPathPattern}\s*\|\s*sed\s+(['"])s/=\.\*/=\*\*\*/\2$`,
    "i"
  );
  const isSafeInspectionCommand = (command) => {
    const normalized = command.trim();
    return safeKeyExtraction.test(normalized) || safeMaskedOutput.test(normalized);
  };

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

  if (tool === "Bash") {
    const cmd = (input.tool_input && input.tool_input.command) || "";
    const dumper = /\b(cat|bat|tac|nl|less|more|head|tail|xxd|od|strings|hexdump|grep|egrep|fgrep|rg|ag|ack|awk|sed|view)\b/i;
    if (isEnvFile(cmd) && dumper.test(cmd) && !isSafeInspectionCommand(cmd)) {
      deny(
        "시크릿 평문 출력 차단: .env 파일을 그대로 덤프하는 명령입니다. 키만 추출하거나(grep -oE '^[A-Za-z_]+=' 파일), " +
        "정해진 형식으로 값을 마스킹하세요(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' 파일 | sed 's/=.*/=***/'). " +
        ".env.example 은 허용됩니다."
      );
    }
    process.exit(0);
  }
});
