"""Wait for a usable memory worker, including after restarting the coordinator."""

import json
import time
import urllib.request


def query(sql, deadline):
    request = urllib.request.Request(
        "http://127.0.0.1:8080/v1/statement",
        data=sql.encode("utf-8"),
        headers={"X-Presto-User": "presto", "Content-Type": "text/plain; charset=utf-8"},
    )
    rows = []
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Presto memory worker readiness timed out")
        with urllib.request.urlopen(request, timeout=min(5, remaining)) as response:
            result = json.load(response)
        if result.get("error"):
            raise RuntimeError(result["error"].get("message", str(result["error"])))
        rows.extend(result.get("data") or [])
        if not result.get("nextUri"):
            return rows
        request = urllib.request.Request(result["nextUri"], headers={"X-Presto-User": "presto"})
        time.sleep(0.05)


def main():
    deadline = time.monotonic() + 120
    last_error = None
    while time.monotonic() < deadline:
        try:
            query("CREATE SCHEMA IF NOT EXISTS memory.sqlancer_ci_readiness", deadline)
            table = "memory.sqlancer_ci_readiness.probe"
            query("DROP TABLE IF EXISTS " + table, deadline)
            query("CREATE TABLE " + table + " (v BIGINT)", deadline)
            query("INSERT INTO " + table + " VALUES (1)", deadline)
            if query("SELECT v FROM " + table, deadline) != [[1]]:
                raise RuntimeError("Memory worker did not return the inserted readiness row")
            query("DROP TABLE " + table, deadline)
            query("DROP SCHEMA memory.sqlancer_ci_readiness", deadline)
            print("Presto memory worker is ready: create, insert and read succeeded", flush=True)
            return
        except (OSError, ValueError, RuntimeError) as error:
            last_error = error
            print("Waiting for Presto memory worker: " + str(error)[:300], flush=True)
            time.sleep(min(2, max(0, deadline - time.monotonic())))
    raise RuntimeError("Presto memory worker never became ready") from last_error


if __name__ == "__main__":
    main()
