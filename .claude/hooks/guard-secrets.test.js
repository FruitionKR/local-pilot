const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");
const test = require("node:test");

const hookPath = path.join(__dirname, "guard-secrets.js");

function runHook(toolName, toolInput) {
  const result = spawnSync(process.execPath, [hookPath], {
    encoding: "utf8",
    input: JSON.stringify({
      tool_name: toolName,
      tool_input: toolInput,
    }),
  });

  assert.equal(result.status, 0);
  assert.equal(result.stderr, "");
  return result.stdout.trim();
}

function assertDenied(output) {
  const parsed = JSON.parse(output);
  assert.equal(parsed.hookSpecificOutput.permissionDecision, "deny");
}

test("실제 .env Read를 차단한다", () => {
  assertDenied(runHook("Read", { file_path: "infra/.env" }));
});

test("예시 .env 파일 Read는 허용한다", () => {
  assert.equal(runHook("Read", { file_path: "infra/.env.example" }), "");
});

test("평문 .env 출력 명령을 차단한다", () => {
  assertDenied(runHook("Bash", { command: "cat infra/.env" }));
});

test("실제 파일과 예시 파일을 함께 출력하는 명령을 차단한다", () => {
  assertDenied(
    runHook("Bash", {
      command: "cat infra/.env infra/.env.example",
    })
  );
});

test("출력을 제한하기만 하는 sed 우회를 차단한다", () => {
  assertDenied(runHook("Bash", { command: "cat infra/.env | sed -n 1p" }));
});

test("정해진 키 추출 명령은 허용한다", () => {
  assert.equal(
    runHook("Bash", {
      command: "grep -oE '^[A-Za-z_]+=' infra/.env",
    }),
    ""
  );
});

test("정해진 값 마스킹 명령은 허용한다", () => {
  assert.equal(
    runHook("Bash", {
      command:
        "grep -E '^[A-Za-z_][A-Za-z0-9_]*=' infra/.env | sed 's/=.*/=***/'",
    }),
    ""
  );
});

test("불완전한 값 마스킹 명령을 차단한다", () => {
  assertDenied(
    runHook("Bash", {
      command: "grep -E '.*' infra/.env | sed 's/=.*/=***/'",
    })
  );
});

test("안전한 명령 뒤에 추가한 평문 출력을 차단한다", () => {
  assertDenied(
    runHook("Bash", {
      command:
        "grep -E '^[A-Za-z_][A-Za-z0-9_]*=' infra/.env | sed 's/=.*/=***/'; cat infra/.env",
    })
  );
});

// allowlist 반전 이전에 통과하던 우회 명령들
for (const command of [
  "cut -d= -f2 infra/.env",
  "sort infra/.env",
  "base64 infra/.env",
  "tr -d '' < infra/.env",
  "dd if=infra/.env",
  "diff /dev/null infra/.env",
  "paste infra/.env",
  "python3 -c \"print(open('infra/.env').read())\"",
  "while read l; do echo $l; done < infra/.env",
  "cp infra/.env /tmp/leak",
]) {
  test(`목록에 없던 출력 명령을 차단한다: ${command}`, () => {
    assertDenied(runHook("Bash", { command }));
  });
}

test("Grep content 모드로 .env를 읽는 것을 차단한다", () => {
  assertDenied(
    runHook("Grep", {
      pattern: ".",
      path: "infra/.env",
      output_mode: "content",
    })
  );
});

test("Grep 파일 목록 모드는 허용한다", () => {
  assert.equal(
    runHook("Grep", { pattern: ".", path: "infra/.env" }),
    ""
  );
});

test("예시 파일에 대한 Grep content 모드는 허용한다", () => {
  assert.equal(
    runHook("Grep", {
      pattern: ".",
      path: "infra/.env.example",
      output_mode: "content",
    }),
    ""
  );
});

test("경로를 인용한 키 추출 명령은 허용한다", () => {
  assert.equal(
    runHook("Bash", {
      command: "grep -oE '^[A-Za-z_]+=' \"infra/.env\"",
    }),
    ""
  );
});

test("이름에 env가 이어지는 파일 Read는 허용한다", () => {
  assert.equal(runHook("Read", { file_path: "src/app.environment.ts" }), "");
});

test("docker compose --env-file 실행은 허용한다", () => {
  assert.equal(
    runHook("Bash", {
      command:
        "docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up -d",
    }),
    ""
  );
});

test("docker compose down도 허용한다", () => {
  assert.equal(
    runHook("Bash", { command: "docker compose --env-file infra/.env down" }),
    ""
  );
});

// 값을 그대로 출력하는 docker compose 서브커맨드
for (const command of [
  "docker compose --env-file infra/.env config",
  "docker compose --env-file infra/.env -f infra/docker-compose.dev.yml config",
  "docker compose --env-file infra/.env exec app env",
  "docker compose --env-file infra/.env run app printenv",
]) {
  test(`값을 출력하는 docker compose 서브커맨드를 차단한다: ${command}`, () => {
    assertDenied(runHook("Bash", { command }));
  });
}

test("커밋 메시지에 .env를 언급하는 명령은 허용한다", () => {
  assert.equal(
    runHook("Bash", { command: 'git commit -m "fix: .env 처리 로직 수정"' }),
    ""
  );
});

test("경로를 안내만 하는 echo는 허용한다", () => {
  assert.equal(
    runHook("Bash", { command: 'echo "infra/.env 를 먼저 만드세요"' }),
    ""
  );
});

test("파일 내용을 커밋 메시지로 읽는 명령은 차단한다", () => {
  assertDenied(runHook("Bash", { command: "git commit -F infra/.env" }));
});

test("프록시 래퍼가 붙어도 허용 명령을 통과시킨다", () => {
  assert.equal(
    runHook("Bash", { command: 'rtk git commit -m "fix: .env 처리"' }),
    ""
  );
});

test("프록시 래퍼로 감싼 출력 명령은 차단한다", () => {
  assertDenied(runHook("Bash", { command: "rtk cat infra/.env" }));
});

test("프록시 래퍼의 raw 실행 우회는 차단한다", () => {
  assertDenied(runHook("Bash", { command: "rtk proxy cat infra/.env" }));
});

// 허용 명령을 첫 줄에 두고 다음 줄에 출력 명령을 붙이는 우회
for (const command of [
  'echo "setup"\ncat infra/.env',
  'git commit -m "x .env"\ncat infra/.env',
  "ls infra/.env\ncat infra/.env",
  'git commit -m "x" \\\n  -F infra/.env',
]) {
  test(`개행으로 이어붙인 출력 명령을 차단한다: ${JSON.stringify(command)}`, () => {
    assertDenied(runHook("Bash", { command }));
  });
}

test("인용 구간 안의 치환은 차단한다", () => {
  assertDenied(runHook("Bash", { command: 'echo "$(cat infra/.env)"' }));
});

test("여러 줄 커밋 메시지는 허용한다", () => {
  assert.equal(
    runHook("Bash", {
      command: 'git commit -m "fix: .env 처리" -m "본문 첫 줄\n본문 둘째 줄"',
    }),
    ""
  );
});

test("커밋 메시지 안의 구분자 문자는 옵션으로 보지 않는다", () => {
  assert.equal(
    runHook("Bash", {
      command: 'git commit -m "fix: .env 처리; --file 옵션 설명 포함"',
    }),
    ""
  );
});

test("예시 파일에서 .env를 만드는 초기 설정은 허용한다", () => {
  assert.equal(
    runHook("Bash", { command: "cp infra/.env.example infra/.env" }),
    ""
  );
});
