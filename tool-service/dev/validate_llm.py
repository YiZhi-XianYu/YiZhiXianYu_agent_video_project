#!/usr/bin/env python3
"""
LLM Story Plan Development Validator

Reuses existing SHOT_RANKING artifacts to test LLM compilation
without re-running the full pipeline (probe→proxy→shot→quality→ranking).

Usage:
    cd tool-service
    python dev/validate_llm.py                           # last 5 rankings, 30s target
    python dev/validate_llm.py --batch 3                  # last 3 rankings
    python dev/validate_llm.py --ranking art_xxx          # specific ranking
    python dev/validate_llm.py --repeat 5                 # same ranking 5 times
    python dev/validate_llm.py --duration 60000           # 60s target
"""

import argparse
import json
import logging
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.core.models import ArtifactInput, ToolExecutionRequest
from app.tools.story_plan import StoryPlanTool

logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
logger = logging.getLogger("validate-llm")

ARTIFACT_ROOT = Path(__file__).resolve().parent.parent / "runtime" / "artifacts"


def find_last_n_rankings(n: int) -> list[Path]:
    """Return the N most recent SHOT_RANKING artifact paths (deduplicated by mtime)."""
    rankings = sorted(
        ARTIFACT_ROOT.glob("*/shot-ranking.json"),
        key=os.path.getmtime,
        reverse=True,
    )
    return rankings[:n]


def load_ranking(path: Path) -> dict:
    with open(path) as f:
        return json.load(f)


def run_story_plan(
    ranking_path: Path,
    target_duration_ms: int = 30000,
    max_shots: int = 12,
) -> tuple[dict | None, int]:
    """Run StoryPlanTool, return (llm_audit, elapsed_ms)."""
    artifact_id = ranking_path.parent.name

    ranking_input = ArtifactInput(
        artifactId=artifact_id,
        uri=ranking_path.resolve().as_uri(),
        fileName="shot-ranking.json",
    )

    request = ToolExecutionRequest(
        tool="planning.story-template",
        version="1.0.0",
        idempotencyKey=f"dev-validate-{int(time.time() * 1000)}",
        inputs={"ranking": ranking_input},
        parameters={"targetDurationMs": target_duration_ms, "maxShots": max_shots},
    )

    tool = StoryPlanTool()
    start = time.monotonic()
    artifacts = tool.execute(request)
    elapsed = int((time.monotonic() - start) * 1000)

    llm_audit = None
    for art in artifacts:
        art_path = ARTIFACT_ROOT / art.artifact_id / "story-plan.json"
        if art_path.exists():
            with open(art_path) as f:
                llm_audit = json.load(f).get("llmAudit")
    return llm_audit, elapsed


def print_audit(audit: dict, elapsed_ms: int, ranking_label: str = ""):
    """Pretty-print a single LLM audit record."""
    header = f"LLM STORY PLAN AUDIT"
    if ranking_label:
        header += f" — {ranking_label}"
    print(f"\n{'='*60}")
    print(header)
    print(f"{'='*60}")
    print(f"  Provider:       {audit.get('provider', '?')}")
    print(f"  Model:          {audit.get('model', '?')}")
    print(f"  Duration:       {audit.get('durationMs', '?')}ms (LLM) / {elapsed_ms}ms (total)")
    print(f"  Candidates:     {audit.get('inputCandidateCount', '?')}")
    print(f"  Final Source:   {audit.get('finalSource', '?')}")
    print(f"  Request ID:     {audit.get('requestId', '?')}")

    errors = audit.get("validationErrors", [])
    if errors:
        print(f"\n  Validation Errors ({len(errors)}):")
        for e in errors:
            print(f"    ❌ {e}")
    elif audit.get("finalSource") == "LLM":
        print(f"\n  ✅ LLM proposal ACCEPTED")
    else:
        print(f"\n  ⚠️  Fallback to deterministic")

    raw = audit.get("rawResponse")
    if raw and raw.get("beats"):
        print(f"\n  Beat Summary:")
        for beat in raw["beats"]:
            role = beat["role"]
            shots = len(beat.get("shotIds", []))
            dur = beat.get("targetDurationMs", 0)
            reasons = beat.get("reasonCodes", [])[:2]
            print(f"    {role:10s} {dur:5d}ms  {shots} shots  {', '.join(reasons)}")
    print(f"{'='*60}")


def run_batch(rankings: list[Path], duration: int, max_shots: int) -> dict:
    """Run validation against each ranking once, return aggregate stats."""
    successes = 0
    total_time = 0
    results = []

    print(f"\nRunning against {len(rankings)} different rankings...\n")
    for i, rp in enumerate(rankings):
        label = rp.parent.name.replace("art_", "")[:12]
        sys.stdout.write(f"  [{i+1}/{len(rankings)}] {label} ... ")
        sys.stdout.flush()

        audit, elapsed = run_story_plan(rp, duration, max_shots)
        total_time += elapsed
        is_llm = audit and audit.get("finalSource") == "LLM"
        if is_llm:
            successes += 1

        n_errors = len(audit.get("validationErrors", [])) if audit else 0
        status = "LLM ✅" if is_llm else f"fallback ({n_errors} errors)"
        print(f"{elapsed}ms  {status}")

        results.append((label, audit, elapsed))

    print(f"\n{'='*60}")
    print(f"AGGREGATE: {successes}/{len(rankings)} LLM accepted ({successes/len(rankings)*100:.0f}%)")
    print(f"Average time: {total_time/len(rankings):.0f}ms  |  Total: {total_time}ms")
    print(f"{'='*60}")

    # Print detail for failures
    failures = [(l, a, e) for l, a, e in results if a and a.get("finalSource") != "LLM"]
    if failures:
        print(f"\nFailure details:")
        for label, audit, elapsed in failures:
            errors = audit.get("validationErrors", [])
            print(f"  {label}: {errors}")

    return {"successes": successes, "total": len(rankings), "total_time": total_time,
            "results": results}


def run_repeat(ranking_path: Path, duration: int, max_shots: int, repeat: int):
    """Run validation N times against the same ranking."""
    successes = 0
    total_time = 0
    print(f"\nRunning {repeat} iterations on {ranking_path.parent.name.replace('art_','')[:12]}...")
    for i in range(repeat):
        audit, elapsed = run_story_plan(ranking_path, duration, max_shots)
        total_time += elapsed
        if audit and audit.get("finalSource") == "LLM":
            successes += 1
        sys.stdout.write(f"\r  [{i+1}/{repeat}] LLM: {successes}, avg: {total_time/(i+1):.0f}ms")
        sys.stdout.flush()
    print()
    print(f"\nResult: {successes}/{repeat} LLM accepted ({successes/repeat*100:.0f}%)")


def main():
    parser = argparse.ArgumentParser(description="LLM Story Plan Dev Validator")
    parser.add_argument("--ranking", help="Specific ranking artifact ID (e.g., art_xxx)")
    parser.add_argument("--duration", type=int, default=30000, help="Target duration ms")
    parser.add_argument("--max-shots", type=int, default=12, help="Max shots per beat")
    parser.add_argument("--batch", type=int, default=5, help="Run on last N distinct rankings")
    parser.add_argument("--repeat", type=int, default=1, help="Repeat N times on same ranking")
    args = parser.parse_args()

    # Single ranking mode
    if args.ranking:
        ranking_path = ARTIFACT_ROOT / args.ranking / "shot-ranking.json"
        if not ranking_path.exists():
            print(f"Error: ranking not found: {ranking_path}")
            sys.exit(1)
        print(f"Using ranking: {ranking_path.parent.name}")

        if args.repeat > 1:
            run_repeat(ranking_path, args.duration, args.max_shots, args.repeat)
        else:
            audit, elapsed = run_story_plan(ranking_path, args.duration, args.max_shots)
            if audit:
                print_audit(audit, elapsed, ranking_path.parent.name.replace("art_", "")[:12])
            else:
                print("\nNo LLM audit record found.")
        return

    # Batch mode: N distinct rankings
    rankings = find_last_n_rankings(args.batch)
    if not rankings:
        print("Error: no ranking artifacts found")
        sys.exit(1)

    run_batch(rankings, args.duration, args.max_shots)


if __name__ == "__main__":
    main()
