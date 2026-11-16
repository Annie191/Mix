"""Export stable, reviewable traces from MixedPreparationTest's actual run artifacts."""

import json
from pathlib import Path


CASES = (
    "filtered_shared",
    "unique_blocks_duplicates",
    "already_sufficient",
    "samples_only",
    "consecutive_constraint_failures",
    "meaningful_empty",
)


def main():
    cases = []
    for name in CASES:
        result = json.loads(Path("target/mixed-preparation", name + ".json").read_text())
        before = result["rounds"][0]["state_before"]
        rounds = []
        for turn in result["rounds"]:
            rounds.append({
                "request_id": turn["decision_id"],
                "data_revision_before": turn["data_revision_before"],
                "decision": turn["decision"],
                "model_expectation": turn["model_expectation"],
                "writes": turn["writes"],
                "actual_observations": turn["actual_observations"],
                "actual_samples": turn["actual_samples"],
                "evaluation": turn["evaluation"],
                "data_revision_after": turn["data_revision_after"],
            })
        cases.append({
            "case": name,
            "query": before["query"],
            "schema": before["schema"],
            "rounds": rounds,
            "termination": result["termination"],
            "evaluation": result["evaluation"],
        })
    destination = Path("docs/data-preparation-loop/results.json")
    destination.write_text(json.dumps({
        "source": "Scripted responses; actual H2 writes and probes; TiDB catalog/session simulated",
        "outcomes": {"achieved": "已达成", "not_achieved": "未达成", "unverified": "未验证"},
        "cases": cases,
    }, ensure_ascii=False, indent=2) + "\n")
    print(destination)


if __name__ == "__main__":
    main()
