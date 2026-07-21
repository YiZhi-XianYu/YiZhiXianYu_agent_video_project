#!/usr/bin/env python3
"""
LLM Story Plan Development Validator

Reuses existing SHOT_RANKING artifacts to test LLM compilation
without re-running the full pipeline (probe→proxy→shot→quality→ranking).

Usage:
    cd tool-service
    python dev/validate_llm.py                           # latest ranking, 30s target
    python dev/validate_llm.py --duration 60000           # 60s target
    python dev/validate_llm.py --ranking art_xxx          # specific ranking
    python dev/validate_llm.py --no-llm                   # deterministic only
    python dev/validate_llm.py --max-shots 20              # more shots
"""

import argparse
import json
import logging
import os
import sys
import time
from pathlib import Path

# Ensure tool-service is on the path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.core.models import ArtifactInput, ToolExecutionRequest
from app.tools.story_plan import StoryPlanTool

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger("validate-llm")

ARTIFACT_ROOT = Path(__file__).resolve().parent.parent / "runtime" / "artifacts"


def find_latest_ranking() -> Path | None:
    """Find the most recent SHOT_RANKING artifact."""
    rankings = sorted(
        ARTIFACT_ROOT.glob("*/shot-ranking.json"),
        key=os.path.getmtime,
    )
    return rankings[-1] if rankings else None


def load_ranking(path: Path) -> dict:
    """Load a SHOT_RANKING artifact and return the data."""
    with open(path) as f:
        return json.load(f)


def run_story_plan(
    ranking_path: Path,
    target_duration_ms: int = 30000,
    max_shots: int = 12,
) -> tuple[list, dict | None]:
    """Run StoryPlanTool with the given ranking artifact.

    Returns (artifacts, llm_audit_dict).
    """
    artifact_id = ranking_path.parent.name

    ranking_input = ArtifactInput(
        artifactId=artifact_id,
        uri=ranking_path.resolve().as_uri(),
        fileName="shot-ranking.json",
    )

    request = ToolExecutionRequest(
        tool="planning.story-template",
        version="1.0.0",
        idempotencyKey=f"dev-validate-{int(time.time())}",
        inputs={"ranking": ranking_input},
        parameters={
            "targetDurationMs": target_duration_ms,
            "maxShots": max_shots,
        },
    )

    tool = StoryPlanTool()
    start = time.monotonic()
    artifacts = tool.execute(request)
    elapsed = int((time.monotonic() - start) * 1000)

    # The llmAudit is embedded in the artifact's metadata
    llm_audit = None
    for art in artifacts:
        art_path = ARTIFACT_ROOT / art.artifact_id / "story-plan.json"
        if art_path.exists():
            with open(art_path) as f:
                data = json.load(f)
                llm_audit = data.get("llmAudit")

    return artifacts, llm_audit, elapsed


def print_audit(audit: dict, elapsed_ms: int):
    """Pretty-print the LLM audit record."""
    print()
    print("=" * 60)
    print("LLM STORY PLAN AUDIT")
    print("=" * 60)
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

    # Show beat-level summary
    raw = audit.get("rawResponse")
    if raw and raw.get("beats"):
        print(f"\n  Beat Summary:")
        for beat in raw["beats"]:
            role = beat["role"]
            shots = len(beat.get("shotIds", []))
            dur = beat.get("targetDurationMs", 0)
            reasons = beat.get("reasonCodes", [])[:2]
            print(f"    {role:10s} {dur:5d}ms  {shots} shots  {', '.join(reasons)}")

    print("=" * 60)


def main():
    parser = argparse.ArgumentParser(description="LLM Story Plan Dev Validator")
    parser.add_argument("--ranking", help="Specific ranking artifact ID (e.g., art_xxx)")
    parser.add_argument("--duration", type=int, default=30000, help="Target duration ms")
    parser.add_argument("--repeat", type=int, default=1, help="Run N iterations for batch testing")
    parser.add_argument("--max-shots", type=int, default=12, help="Max shots per beat")
    args = parser.parse_args()

    # Resolve ranking path
    if args.ranking:
        ranking_path = ARTIFACT_ROOT / args.ranking / "shot-ranking.json"
        if not ranking_path.exists():
            print(f"Error: ranking not found: {ranking_path}")
            sys.exit(1)
    else:
        ranking_path = find_latest_ranking()
        if not ranking_path:
            print("Error: no ranking artifacts found in runtime/artifacts/")
            sys.exit(1)

    print(f"Using ranking: {ranking_path.parent.name}")
    ranking = load_ranking(ranking_path)
    print(f"  Eligible shots: {ranking.get('eligibleShotCount', '?')}")
    print(f"  Target: {args.duration}ms, max {args.max_shots} shots")

    if hasattr(args, 'repeat') and args.repeat > 1:
        run_batch(ranking_path, args.duration, args.max_shots, args.repeat)
        return

    artifacts, llm_audit, elapsed = run_story_plan(
        ranking_path,
        target_duration_ms=args.duration,
        max_shots=args.max_shots,
    )

    if llm_audit:
        print_audit(llm_audit, elapsed)
    else:
        print("\nNo LLM audit record found in output.")


if __name__ == "__main__":
    main()

def run_batch(ranking_path, duration, max_shots, repeat):
    """Run multiple iterations and report aggregate stats."""
    llm_successes = 0
    total_time = 0
    print(f"\nRunning {repeat} iterations...")
    for i in range(repeat):
        _, audit, elapsed = run_story_plan(ranking_path, duration, max_shots)
        total_time += elapsed
        if audit and audit.get("finalSource") == "LLM":
            llm_successes += 1
        sys.stdout.write(f"\r  [{i+1}/{repeat}] LLM accepted: {llm_successes}, avg: {total_time/(i+1):.0f}ms")
        sys.stdout.flush()
    print()
    print(f"\nBatch Result: {llm_successes}/{repeat} LLM accepted ({llm_successes/repeat*100:.0f}%)")
    print(f"Average total time: {total_time/repeat:.0f}ms")
