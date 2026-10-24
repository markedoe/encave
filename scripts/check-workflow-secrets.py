#!/usr/bin/env python3
"""Fails when a workflow gives a release secret to code that may not come from main.

Release secrets: secrets.ASC_*, secrets.ENCAVE_KEY* and secrets.APPLE_TEAM_ID. A
manual run can start a workflow on any branch, whose code would then get them. Each
use must be under a main-ref condition, `github.ref == 'refs/heads/main'`, in one of:
  - the job's `if`;
  - the step's `if` (for a use in that step);
  - the `${{ }}` expression itself, like
    `${{ github.ref == 'refs/heads/main' && secrets.ASC_KEY_ID || '' }}`.

Usage: scripts/check-workflow-secrets.py [workflow files] (default: .github/workflows/*.yml)
"""

import glob
import re
import sys

import yaml

SECRET = re.compile(r"secrets\.(ASC_\w+|ENCAVE_KEY\w*|APPLE_TEAM_ID)")
MAIN = re.compile(r"github\.ref\s*==\s*'refs/heads/main'")
EXPRESSION = re.compile(r"\$\{\{(.*?)\}\}", re.S)


def strings(node):
    """Every string under node (values only: keys never hold expressions here)."""
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for value in node.values():
            yield from strings(value)
    elif isinstance(node, list):
        for value in node:
            yield from strings(value)


def problems(text, gated):
    """The secrets used in text outside a main-ref condition."""
    if gated:
        return []
    found = []
    for match in SECRET.finditer(text):
        expressions = [e for e in EXPRESSION.finditer(text) if e.start() <= match.start() < e.end()]
        if not any(MAIN.search(e.group(1)) for e in expressions):
            found.append(match.group(0))
    return found


def check(path):
    errors = []
    workflow = yaml.safe_load(open(path, encoding="utf-8")) or {}
    for job_id, job in (workflow.get("jobs") or {}).items():
        job_gated = bool(MAIN.search(str(job.get("if", ""))))
        rest = {key: value for key, value in job.items() if key not in ("steps", "if")}
        for text in strings(rest):
            for secret in problems(text, job_gated):
                errors.append(f"{path}: job {job_id}: {secret} without a main-ref condition")
        for index, step in enumerate(job.get("steps") or []):
            step_gated = job_gated or bool(MAIN.search(str(step.get("if", ""))))
            label = step.get("name") or step.get("uses") or f"step {index + 1}"
            body = {key: value for key, value in step.items() if key != "if"}
            for text in strings(body):
                for secret in problems(text, step_gated):
                    errors.append(f"{path}: job {job_id}, {label}: {secret} without a main-ref condition")
    return errors


def main():
    paths = sys.argv[1:] or sorted(glob.glob(".github/workflows/*.yml") + glob.glob(".github/workflows/*.yaml"))
    errors = [error for path in paths for error in check(path)]
    for error in errors:
        print(f"::error::{error}")
    if errors:
        print("Release secrets go only to code from main: add `if: github.ref == 'refs/heads/main'` to the job "
              "or step, or use `${{ github.ref == 'refs/heads/main' && secrets.NAME || '' }}`.")
        return 1
    print(f"Release secrets: main only in {len(paths)} workflows.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
