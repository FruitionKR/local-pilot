import json
import os
from pathlib import Path
import subprocess
import sys

import pytest


PIPELINE_ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize(
    ("skill_enabled", "agent_enabled", "expected"),
    [
        (False, False, {"skill": False, "agent_run": False}),
        (True, False, {"skill": True, "agent_run": False}),
        (False, True, {"skill": False, "agent_run": True}),
        (True, True, {"skill": True, "agent_run": True}),
    ],
)
def test_skill_and_agent_run_routes_have_independent_flags(
    skill_enabled: bool,
    agent_enabled: bool,
    expected: dict[str, bool],
) -> None:
    environment = os.environ.copy()
    environment["SKILL_API_ENABLED"] = str(skill_enabled).lower()
    environment["AGENT_SKILLS_ENABLED"] = str(agent_enabled).lower()
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            (
                "import json, api; paths = api.app.openapi()['paths']; "
                "print(json.dumps({'skill': '/skills' in paths, "
                "'agent_run': any(path.startswith('/agent/runs/') for path in paths)}))"
            ),
        ],
        cwd=PIPELINE_ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )

    assert json.loads(result.stdout) == expected
