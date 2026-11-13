#!/usr/bin/env python3
"""Generate parity goldens from the pinned Python autopilot.py (tools/golden/autopilot.py).

The Kotlin engine is a port of autopilot.py; these goldens let a Kotlin test check that, for the
same inputs, it produces the same parsed nodes, candidates, request state and question, screen
fingerprints and step-policy outcomes. Inputs: the lab's 7 fixtures plus every tree/table dump
captured from the emulator.

    python3 tools/golden/gen.py            # writes core/src/jvmTest/resources/goldens/autopilot/

Source: the original Python autopilot.py (2026-10-01), with the core's generic vocabulary,
sha256 recorded in core/src/jvmTest/resources/goldens/autopilot/SOURCE.
"""
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
os.environ["AUTOPILOT_LOG_DIR"] = "/tmp/tl-golden-traces"
import autopilot as J  # noqa: E402

RES = ROOT / "core/src/jvmTest/resources"
OUT = RES / "goldens/autopilot"
GOAL = "Open the Sport tab"
HISTORY = ["1: Tap View \"Shop\" [#fragment_games] -> no change", "2: Tap Button \"Books\" -> screen changed"]


def node_dict(n):
    return {"verb": n.verb, "widget": n.widget, "text": n.text, "rid": n.rid, "x": n.x, "y": n.y,
            "flags": n.flags, "desc": n.desc, "depth": n.depth}


def inputs():
    for f in sorted((RES / "fixtures").iterdir()):
        if f.suffix in (".table", ".tree") and f.stat().st_size:
            yield "fixture-" + f.stem + f.suffix.replace(".", "-"), f.read_text()
    for f in sorted((RES / "goldens/dumps").iterdir()):
        if f.suffix in (".table", ".tree"):
            yield "emu-" + f.stem + f.suffix.replace(".", "-"), f.read_text()


def screen_golden(dump):
    nodes, unparsed = J.parse_dump(dump)
    out = {"nodes": [node_dict(n) for n in nodes], "unparsed": unparsed,
           "fingerprint": J.fingerprint(nodes), "title": J.screen_title(nodes),
           "selected": J.selected_labels(nodes), "visible_texts": J.visible_texts(nodes), "variants": {}}
    for name, backend, risky in (("typesafe", "typesafe", False), ("typesafe-risky", "typesafe", True), ("ollama", "ollama", False)):
        cands, stats = J.build_candidates(nodes, max_candidates=J.max_options_for(backend, 60), allow_risky=risky)
        state = J.build_state(GOAL, "com.example.shop/.Main", nodes, cands, HISTORY, 2, 5)
        out["variants"][name] = {
            "criteria": {c.key: c.desc for c in cands},
            "risky": [c.key for c in cands if c.risky],
            "stats": stats,
            "state": state,
            "question": J.build_question(cands, backend, allow_risky=risky),
        }
    return out


# --- step-policy matrix -------------------------------------------------------

MATRIX_DUMP = "\n".join([
    'tap  View  "Go back"  98,257',
    'tap  Button  "Shop"  #fragment_games  324,2896',
    'tap  Button  "Sport"  #fragment_sport  540,2896',
    'tap  View  "Betal 50 kr"  700,2900',
    '-  TextView  "Totalt"  100,100',
])
AFTER_DUMP = (RES / "fixtures/shop-sport.table").read_text()


class Runner:
    def __init__(self):
        self.screens = [MATRIX_DUMP, AFTER_DUMP]
        self.i = 0
        self.calls = []

    def __call__(self, args):
        self.calls.append(list(args))
        if args[:1] == ["ui"]:
            return self.screens[self.i]
        if args[:2] == ["show", "top"]:
            return ""
        if args[0] in ("tap", "swipe", "go") and self.i < len(self.screens) - 1:
            self.i += 1
        return "ok"


def policy_cases():
    goals = ["Open the Sport tab", "Tap the Sport row"]
    choices = ["n01", "n03", "n04", "scroll_down", "goal_done", "blocked"]  # leave, safe, risky, meta, terminal ×2
    seconds = ["n03", "n01"]  # the runner-up a fallback would pick: a safe tap, or a leave-screen tap
    for goal in goals:
        for choice in choices:
            for conf in (0.25, 0.35, 0.49, 0.6, 0.9):
                for done in (None, 0.1, 0.3, 0.7):
                    for second in seconds:
                        if second == choice:
                            continue
                        yield {"goal": goal, "choice": choice, "confidence": conf, "done": done, "second": second}


def run_case(case):
    probs = {case["choice"]: case["confidence"], case["second"]: round((1 - case["confidence"]) * 0.6, 4), "blocked" if case["choice"] != "blocked" else "goal_done": round((1 - case["confidence"]) * 0.4, 4)}
    answers = {"next": {"type": "choice", "choice": case["choice"], "probabilities": probs, "confidence": case["confidence"]}}
    if case["done"] is not None:
        answers["done"] = {"type": "noul", "noul": case["done"]}
    body = json.dumps({"model": "jev-1.13.0", "answers": answers, "usage": {"input_tokens": 100, "output_tokens": 0}})
    post = lambda url, headers, b, timeout: (200, body)  # noqa: E731
    r = Runner()
    out = J.step(case["goal"], r, stub=False, api_key="k", post=post, sleep=lambda s: None, trace=None, allow_risky=True)
    return {"case": case, "probabilities": probs, "status": out["status"], "choice": out["choice"],
            "fallback_from": out.get("fallback_from"), "low_confidence_act": out.get("low_confidence_act", False),
            "executed": out["executed"], "calls": [c for c in r.calls if c[0] not in ("ui", "show")]}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    src = (Path(__file__).resolve().parent / "autopilot.py").read_bytes()
    (OUT / "SOURCE").write_text(f"autopilot.py sha256 {hashlib.sha256(src).hexdigest()}\n")
    n = 0
    for name, dump in inputs():
        (OUT / f"{name}.json").write_text(json.dumps({"dump": dump, **screen_golden(dump)}, ensure_ascii=False, indent=1))
        n += 1
    cases = [run_case(c) for c in policy_cases()]
    (OUT / "policy.json").write_text(json.dumps({"matrix_dump": MATRIX_DUMP, "after_dump": AFTER_DUMP, "cases": cases}, ensure_ascii=False, indent=1))
    print(f"{n} screen goldens, {len(cases)} policy cases -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
