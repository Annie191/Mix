"""Validate paired real-run artifacts and archive their complete evidence without duplicate files."""

import gzip
import hashlib
import json
from pathlib import Path
import sys


def load(path):
    return json.loads(path.read_text())


def table_samples(packet):
    return {
        s["table"]: sorted(json.dumps(r, sort_keys=True) for r in s["rows"])
        for s in packet["samples"] if s["complete"]
    }


def validate(records):
    assert len(records) == 18, "Expected all six seeds and three strategies, including failures"
    for seed in range(6):
        paired = [r for r in records if r["seed"] == seed]
        assert {r["strategy"] for r in paired} == {"RANDOM", "SHARED", "AI"}
        assert len(paired) == 3
        assert len({r["sql"] for r in paired}) == 1, "Frozen query differs"
        assert len({json.dumps(r["initial_state"]["schema"], sort_keys=True) for r in paired}) == 1
        assert len({json.dumps(table_samples(r["initial_state"]), sort_keys=True) for r in paired}) == 1
        assert all(len(table_samples(r["initial_state"])) == 2 for r in paired)
        assert len({json.dumps(r["selected_engine_configurations"], sort_keys=True) for r in paired}) == 1
        assert len({json.dumps(r["initial_state"]["remaining_budget"], sort_keys=True) for r in paired}) == 1
        for report in paired:
            preparation = report["preparation"]
            assert len(preparation["rounds"]) <= 3
            attempted = sum(
                turn.get("append_feedback", {}).get("attempted_rows", 0)
                for turn in preparation["rounds"]
            )
            assert attempted <= 6
            assert len(report["api_audit"]) <= 3
            assert all(run["snapshot"] == report["engine_runs"][0]["snapshot"]
                       for run in report.get("engine_runs", []))
            assert not report.get("cleanup_error"), report.get("cleanup_error")
    print("Paired queries, schemas, initial rows, configurations, budgets and snapshots: OK")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("export.py /absolute/path/to/primary-output")
    source = Path(sys.argv[1])
    destination = Path(__file__).resolve().parent
    primary = load(source / "all.json")
    validate(primary)
    records = {
        "primary": primary,
        "exploratory_smoke": load(Path("/home/lwm/Mix-benchmark-pilot/all.json")),
        "aborted_build_overlap": [load(p) for p in sorted(Path("/home/lwm/Mix-benchmark-real").glob("seed_*.json"))],
        "oracle_before_zerofill_fix": [load(p) for p in sorted(
            Path("/home/lwm/Mix-live-oracle-before-zerofill-fix").rglob("case.json"))],
        "oracle_smoke": [load(p) for p in sorted(Path("/home/lwm/Mix-live-oracle-artifacts").rglob("case.json"))],
    }
    payload = json.dumps(records, ensure_ascii=False, separators=(",", ":")).encode()
    # Check the supplied runtime credential without ever printing it or copying it into the archive.
    credential = Path("/tmp/mix-deepseek-benchmark-key")
    if credential.exists():
        assert credential.read_bytes().strip() not in payload, "Credential found in experiment evidence"
    compressed = gzip.compress(payload, mtime=0)
    (destination / "records.json.gz").write_bytes(compressed)
    (destination / "summary.json").write_text(json.dumps(load(source / "summary.json"), ensure_ascii=False, indent=2) + "\n")
    manifest = {
        "primary_seeds": list(range(6)),
        "primary_strategies": ["RANDOM", "SHARED", "AI"],
        "primary_case_count": len(primary),
        "runner_jar_sha256": Path("/home/lwm/Mix-benchmark-runner.sha256").read_text().split()[0],
        "oracle_runner_jar_sha256": hashlib.sha256(Path("/home/lwm/Mix-oracle-runner.jar").read_bytes()).hexdigest(),
        "records_json_gz_sha256": hashlib.sha256(compressed).hexdigest(),
        "records_json_sha256": hashlib.sha256(payload).hexdigest(),
        "target_tidb_commit": "b1d4142f99a606aa3cd1ada6ee0caebaa84226dc",
        "source_base_commit": "0f8fe41a53e942d3b2c82216675234df3266467e",
        "primary_runner_scope": "Primary used BIGINT (no ZEROFILL); final source additionally canonicalizes integer observation strings after an Oracle smoke failure",
        "aborted_reason": "Maven replaced a jar while an experiment JVM was reading prompt resources; reran the complete seed set from an immutable jar",
        "preliminary_counts": {k: len(v) for k, v in records.items() if k != "primary"},
    }
    (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Archived complete evidence:", destination / "records.json.gz")


if __name__ == "__main__":
    main()
