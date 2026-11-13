#!/usr/bin/env python3
"""autopilot-replay.py — replay recorded decisions (/var/log/autopilot traces) against another backend.

Every trace record holds the exact `state` and `criteria` that produced a decision plus the
answer it got. Re-asking a second backend the same questions gives a device-free comparison:
agreement with the recorded choice, latency, confidence, and the done-question probability.

    autopilot-replay.py --backend ollama [--model nimble] [--url http://host:11434/v1/systemone]
                  [--since-minutes N] [--limit N] [--max-options 26] [--json out.json]
    autopilot-replay.py --backend typesafe            # re-run TypeSafe itself (consistency check; needs the key)

Records with more options than --max-options are skipped (counted). Output: a summary table on
stdout and, with --json, the per-record rows.
"""
import argparse
import glob
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, os.environ.get("AUTOPILOT_DIR", "/opt/hs-mcp"))
import autopilot as J  # noqa: E402


def load_records(log_dir: Path, since_minutes: float | None):
    cutoff = time.time() - since_minutes * 60 if since_minutes else 0
    for f in sorted(glob.glob(str(log_dir / "*.jsonl"))):
        if cutoff and os.path.getmtime(f) < cutoff:
            continue
        for line in open(f, encoding="utf-8"):
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "response" in r and "state" in r and "criteria" in r:
                r["_file"] = os.path.basename(f)
                yield r


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--backend", choices=["ollama", "typesafe"], required=True)
    ap.add_argument("--model", default=None)
    ap.add_argument("--url", default=None)
    ap.add_argument("--log-dir", default=os.environ.get("AUTOPILOT_LOG_DIR", "/var/log/autopilot"))
    ap.add_argument("--since-minutes", type=float, default=None)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--max-options", type=int, default=J.OLLAMA_MAX_OPTIONS)
    ap.add_argument("--json", default=None)
    a = ap.parse_args()

    rows, skipped, errors = [], 0, 0
    for r in load_records(Path(a.log_dir), a.since_minutes):
        crit = r["criteria"]
        if len(crit) > a.max_options:
            skipped += 1
            continue
        rec_ans = r["response"]["answers"]["next"]
        rec_choice, rec_conf = rec_ans["choice"], rec_ans.get("confidence", 0)
        rec_done = (r["response"]["answers"].get("done") or {}).get("noul")
        cands = [J.Candidate(k, "terminal" if k in J.TERMINAL else "meta" if k in J.META else "tap", v) for k, v in crit.items()]
        q = J.build_question(cands, a.backend)
        q["next"]["criteria"] = crit  # exact recorded option texts
        kw = {"backend": a.backend, "stub": False}
        if a.model:
            kw["model"] = a.model
        if a.url:
            kw["url"] = a.url
        t0 = time.time()
        try:
            d = J.ask_model(r["state"], q, **kw)
        except J.AutopilotError as e:
            errors += 1
            rows.append({"file": r["_file"], "goal": r.get("goal"), "error": str(e)[:200]})
            print("ERR", r.get("goal", "")[:40], str(e)[:120], file=sys.stderr)
            continue
        kind = lambda k: "terminal" if k in J.TERMINAL else "meta" if k in J.META else "tap"  # noqa: E731
        rows.append({
            "file": r["_file"], "goal": r.get("goal"), "step": r.get("step"), "n_options": len(crit),
            "recorded": rec_choice, "recorded_conf": round(rec_conf, 3), "recorded_done": rec_done,
            "recorded_outcome": r.get("outcome"),
            "replay": d.choice, "replay_conf": round(d.confidence, 3), "replay_done": d.done,
            "replay_prob_of_recorded": round(d.probabilities.get(rec_choice, 0.0), 3),
            "agree": d.choice == rec_choice, "agree_kind": kind(d.choice) == kind(rec_choice),
            "latency_ms": d.latency_ms, "wall_ms": int((time.time() - t0) * 1000), "model": d.model,
            "recorded_desc": crit.get(rec_choice, "")[:50], "replay_desc": crit.get(d.choice, "")[:50],
        })
        if a.limit and len([x for x in rows if "error" not in x]) >= a.limit:
            break

    ok = [x for x in rows if "error" not in x]
    n = len(ok)
    if n:
        agree = sum(x["agree"] for x in ok)
        agree_kind = sum(x["agree_kind"] for x in ok)
        accepted = [x for x in ok if x["recorded_outcome"] in ("acted", "goal_done")]
        agree_acc = sum(x["agree"] for x in accepted)
        lat = sorted(x["latency_ms"] for x in ok)
        print(f"backend={a.backend} model={ok[0]['model']} records={n} skipped(>{a.max_options} options)={skipped} errors={errors}")
        print(f"agreement with recorded choice: {agree}/{n} = {agree / n:.1%}   same kind (tap/meta/terminal): {agree_kind}/{n} = {agree_kind / n:.1%}")
        if accepted:
            print(f"agreement on decisions the autopilot acted on / finished with: {agree_acc}/{len(accepted)} = {agree_acc / len(accepted):.1%}")
        print(f"latency ms: median {lat[n // 2]}  p90 {lat[int(n * 0.9)]}  max {lat[-1]}   mean replay conf {sum(x['replay_conf'] for x in ok) / n:.2f} vs recorded {sum(x['recorded_conf'] for x in ok) / n:.2f}")
        dis = [x for x in ok if not x["agree"]]
        if dis:
            print("disagreements:")
            for x in dis[:40]:
                print(f"  {x['goal'][:38]:38s} rec={x['recorded']:>9s} {x['recorded_desc'][:28]:28s} | rep={x['replay']:>9s} {x['replay_desc'][:28]:28s} p(rec)={x['replay_prob_of_recorded']}")
    else:
        print("no records replayed", "errors:", errors, "skipped:", skipped)
    if a.json:
        Path(a.json).write_text(json.dumps({"backend": a.backend, "rows": rows, "skipped": skipped, "errors": errors}, ensure_ascii=False, indent=1))
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(main())
