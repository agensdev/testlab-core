#!/usr/bin/env python3
"""autopilot — let a decision model (TypeSafe AI's Jev) pick the next tap from the `hs ui` tree.

Jev is a "System One" decision model: it gets a `state` and a typed `choice`
question with named options and answers with ONE option key plus calibrated
probabilities, in ~100-500 ms. It cannot see images or generate text, so
the loop here is the droidjev / agent-device pattern:

    hs ui (accessibility table)  →  numbered candidates  →  the model picks one
        →  hs tap X Y  →  verify the screen changed  →  repeat

Only nodes that really exist on screen are offered; the model can never
issue a raw command. Purchase/destructive words are filtered out unless
`allow_risky` is set; typing is not offered at all in v1.

Pure logic lives here (parse → candidates → state → request → decision →
verify); the hs runner is injected so the MCP server, the CLI and the tests
share it. `AUTOPILOT_STUB=1` replaces the HTTP call with a deterministic
word-overlap heuristic so the whole loop runs offline (no key, no network).

Env: TYPESAFE_API_KEY (required unless AUTOPILOT_STUB=1), TYPESAFE_URL, AUTOPILOT_MODEL,
AUTOPILOT_TIMEOUT, AUTOPILOT_STUB, AUTOPILOT_STUB_CONFIDENCE, AUTOPILOT_LOG_DIR, AUTOPILOT_MAX_TEXTS,
AUTOPILOT_DEADLINE_S, AUTOPILOT_STATE_CANDIDATES, AUTOPILOT_DUMP_FORM.

CLI: python -m autopilot pick|step|do --goal "..." [--serial S | --dump FILE]
     python -m autopilot selftest
"""

from __future__ import annotations

import argparse
import functools
import hashlib
import json
import os
import re
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Literal

# ----------------------------------------------------------------- config --

TYPESAFE_URL = os.environ.get("TYPESAFE_URL", "https://api.typesafe.ai/v1/systemone")
def _key_from_file() -> str:
    """launchd/macOS has no EnvironmentFile: read KEY=value lines from $TYPESAFE_ENV_FILE if the env is unset."""
    f = os.environ.get("TYPESAFE_ENV_FILE", "")
    try:
        for line in Path(f).read_text().splitlines() if f else []:
            k, _, v = line.strip().partition("=")
            if k == "TYPESAFE_API_KEY" and v:
                return v.strip().strip('"')
    except OSError:
        pass
    return ""


TYPESAFE_API_KEY = os.environ.get("TYPESAFE_API_KEY", "") or _key_from_file()
AUTOPILOT_MODEL = os.environ.get("AUTOPILOT_MODEL", "jev-latest")
# Backend: "typesafe" (hosted Jev) or "ollama" (Ollama >= 0.35 serves the same System One API locally at
# /v1/systemone with decision models such as `nimble` (9B) / `tev1`). Ollama allows 2-26 options per
# question and a 64 KiB body, so candidates are capped harder on that backend.
AUTOPILOT_BACKEND = os.environ.get("AUTOPILOT_BACKEND", "typesafe")
OLLAMA_SYSTEMONE_URL = os.environ.get("OLLAMA_SYSTEMONE_URL", "http://localhost:11434/v1/systemone")
# Failover: comma-separated list tried in order (e.g. a fast GPU node first, an always-on
# fallback). A host that fails to connect, times out or 404s (Ollama < 0.35) is skipped for
# OLLAMA_BACKOFF seconds — a simple primary/fallback pair.
# Entries may carry their own model: "http://host:11434/v1/systemone|nimble:9b" (default model otherwise).
OLLAMA_SYSTEMONE_URLS = [u.strip() for u in os.environ.get("OLLAMA_SYSTEMONE_URLS", OLLAMA_SYSTEMONE_URL).split(",") if u.strip()]


def _split_entry(entry: str) -> tuple[str, str | None]:
    url, _, model = entry.partition("|")
    return url.strip(), (model.strip() or None)
OLLAMA_CONNECT_TIMEOUT = float(os.environ.get("OLLAMA_CONNECT_TIMEOUT", "3"))
OLLAMA_BACKOFF = float(os.environ.get("OLLAMA_BACKOFF", "60"))
_ollama_down: dict[str, float] = {}  # url -> epoch until which it is skipped
AUTOPILOT_OLLAMA_MODEL = os.environ.get("AUTOPILOT_OLLAMA_MODEL", "nimble:9b-q4_K_M")  # q4 fits the 2080 Ti ~83 % on GPU: 1.1 s vs 2.7 s, same agreement
AUTOPILOT_OLLAMA_TIMEOUT = float(os.environ.get("AUTOPILOT_OLLAMA_TIMEOUT", "120"))
OLLAMA_MAX_OPTIONS = 26
AUTOPILOT_TIMEOUT = float(os.environ.get("AUTOPILOT_TIMEOUT", "10"))
AUTOPILOT_STUB = os.environ.get("AUTOPILOT_STUB", "") not in ("", "0", "false")
AUTOPILOT_STUB_CONFIDENCE = float(os.environ.get("AUTOPILOT_STUB_CONFIDENCE", "0.8"))
AUTOPILOT_LOG_DIR = Path(os.environ.get("AUTOPILOT_LOG_DIR", "/var/log/autopilot"))
AUTOPILOT_MAX_TEXTS = int(os.environ.get("AUTOPILOT_MAX_TEXTS", "40"))
AUTOPILOT_DEADLINE_S = float(os.environ.get("AUTOPILOT_DEADLINE_S", "90"))
AUTOPILOT_STATE_CANDIDATES = os.environ.get("AUTOPILOT_STATE_CANDIDATES", "") not in ("", "0", "false")
AUTOPILOT_DONE_THRESHOLD = float(os.environ.get("AUTOPILOT_DONE_THRESHOLD", "0.6"))  # noul "done" prob → goal_done
AUTOPILOT_DONE_VETO = float(os.environ.get("AUTOPILOT_DONE_VETO", "0.25"))  # choice goal_done is vetoed when noul < this
AUTOPILOT_FALLBACK_MIN = float(os.environ.get("AUTOPILOT_FALLBACK_MIN", "0.2"))  # act on best tap when goal_done was vetoed
AUTOPILOT_HARMLESS_MIN = float(os.environ.get("AUTOPILOT_HARMLESS_MIN", "0.3"))  # non-risky tap acts on the argmax down to this
AUTOPILOT_KB_HIDE_XY = tuple(int(v) for v in os.environ.get("AUTOPILOT_KB_HIDE_XY", "195,2990").split(","))  # IME chevron (Pixel 6 Pro)
LABEL_DY = 450  # px: how far above an unlabelled input to look for its label
AUTOPILOT_DUMP_FORM = os.environ.get("AUTOPILOT_DUMP_FORM", "tree")  # tree|table (json == table in hs 0.1.38)

# Generic core vocabulary (the Kotlin defaults with an empty profile): a project profile adds
# its own risky/leave words, glossary and examples on top.
RISKY_RE = re.compile(r"(?i)uninstall|delete|pay\b|buy|purchase")
# Elements that leave the current screen are not "harmless" for a low-confidence tap.
LEAVE_RE = re.compile(r"(?i)^(go back|back|close( sheet)?|cancel|home)$")
MAX_CRITERIA = 255
TEXT_CUT = 80
DESC_CUT = 60
HISTORY_TAIL = 5
JACCARD_SAME = 0.9
BACKOFF_S = (0.5, 1.0, 2.0, 4.0)

META: dict[str, str] = {
    "scroll_down": "Scroll the screen down to reveal content further below",
    "scroll_up": "Scroll the screen up to reveal content further above",
    "back": "Press the Android back button (leave this screen / close a dialog)",
}
TERMINAL: dict[str, str] = {
    "goal_done": "The goal is ALREADY achieved: the screen/section the goal asks for is the one currently open",
    "blocked": "None of the listed elements would lead toward the goal from this screen",
}

# App-specific glossary sent with every question; empty in the generic core (a profile sets it).
AUTOPILOT_GLOSSARY = os.environ.get("AUTOPILOT_GLOSSARY", "")

DONE_INSTRUCTIONS = (
    "Is `goal` ALREADY accomplished, i.e. is the screen/section the goal asks for the one currently "
    "open? Judge from `screen.title` (the page heading - if it names what the goal asks to open, the "
    "goal is accomplished), `screen.selected` (the selected tab), `screen.visible_texts` (its content) "
    "and `history` (the last action). A tab or section label merely being visible in a navigation "
    "bar or menu does NOT mean it is open. Tab names must match exactly. Answer no if the screen is still where the goal started."
)

QUESTION_INSTRUCTIONS = (
    "Choose the single next action on an Android phone so that `goal` is accomplished. "
    "Each option is either one tappable element currently on screen (its label is quoted) or a meta action. Pick the element most likely "
    "to lead toward the goal. Do NOT require a literal word match: section names, tabs, tiles and "
    "synonyms count (e.g. the goal 'Open Settings' is served by a 'More' tab when no 'Settings' element is visible). Pick the option stating the goal "
    "is ALREADY achieved when `screen.selected` / `screen.visible_texts` show that - do not act again. "
    "Pick the option stating that none of the elements lead toward the goal when nothing can plausibly "
    "advance it, or when progress would require typing text, paying, or confirming a purchase. Pick a "
    "scroll option only if the needed element is probably off-screen. Never repeat an action that `history` says produced no change. "
    "`screen.selected` names the currently selected tab; a tab label being visible in the bottom "
    "bar does NOT mean that tab is open - tap it to open it. Tapping a tab or section is free and "
    "reversible." + (" Glossary: " + AUTOPILOT_GLOSSARY if AUTOPILOT_GLOSSARY.strip() else "")
)


# Goals phrased as a single action resolve as soon as that action changed the screen;
# state goals ("Open Books") keep looping until the done-question agrees.
ACTION_GOAL_RE = re.compile(r"^\s*(tap|press|click|choose|select|pick|dismiss|skip|accept|confirm|continue|"
                            r"close|focus|toggle|check|uncheck|swipe|scroll)\b", re.I)


def is_action_goal(goal: str) -> bool:
    return bool(ACTION_GOAL_RE.match(goal))


class AutopilotError(RuntimeError):
    """Configuration / API / parse problems on the model side (not device errors)."""


# ------------------------------------------------------------------- data --

@dataclass
class Node:
    verb: str
    widget: str
    text: str
    rid: str
    x: int
    y: int
    raw: str = ""
    flags: str = ""  # table: "long", "long scroll"; tree: "click,focusable,selected"…
    desc: str = ""   # contentDescription (tree form only)
    depth: int = 0   # tree form only

    @property
    def actionable(self) -> bool:
        return self.verb == "tap"

    @property
    def label(self) -> str:
        return self.text or (f"#{self.rid}" if self.rid else "")


@dataclass
class Candidate:
    key: str
    kind: Literal["tap", "meta", "terminal"]
    desc: str
    node: Node | None = None
    risky: bool = False

    @property
    def ident(self) -> tuple[str, str]:
        return (self.node.text, self.node.rid) if self.node else (self.key, "")


@dataclass
class Decision:
    choice: str
    confidence: float
    probabilities: dict[str, float]
    model: str = ""
    usage: dict = field(default_factory=dict)
    latency_ms: int = 0
    raw: dict = field(default_factory=dict)
    done: float | None = None  # fan-out noul: P(goal already accomplished)

    def top(self, n: int = 5) -> list[tuple[str, float]]:
        return sorted(self.probabilities.items(), key=lambda kv: -kv[1])[:n]


# ------------------------------------------------------------------ parse --

# Anchored on the trailing "x,y" so unknown middle columns can't break it.
# Real rows may end with flags after the coordinates: `720,346  [long scroll]`.
LINE_RE = re.compile(r"^\s*(?P<verb>\S+)\s+(?P<widget>\S+)\s+(?P<mid>.*?)\s*(?P<x>\d+),(?P<y>\d+)"
                     r"(?P<flags>(?:\s+\[[^\]]*\])*)\s*$")
_WS_RE = re.compile(r"[\s ]+")


def _norm(s: str) -> str:
    return _WS_RE.sub(" ", s).strip()


def _strip_pkg(rid: str) -> str:
    rid = rid.lstrip("#")
    if ":id/" in rid:
        rid = rid.split(":id/", 1)[1]
    return rid


def parse_table(dump: str) -> tuple[list[Node], list[str]]:
    """Parse the `hs ui` verb-led table. Returns (nodes, unparsed_lines)."""
    nodes: list[Node] = []
    unparsed: list[str] = []
    for line in dump.splitlines():
        if not line.strip():
            continue
        m = LINE_RE.match(line)
        if not m:
            unparsed.append(line)
            continue
        mid = m.group("mid")
        text, rest = "", mid
        if '"' in mid:
            a, b = mid.find('"'), mid.rfind('"')
            if b > a:
                text = mid[a + 1:b]
                rest = mid[:a] + " " + mid[b + 1:]
        rm = re.search(r"(?:^|\s)#(\S+)", rest)
        rid = _strip_pkg(rm.group(1)) if rm else ""
        flags = " ".join(re.findall(r"\[([^\]]*)\]", m.group("flags") or ""))
        nodes.append(Node(m.group("verb"), m.group("widget"), _norm(text), rid,
                          int(m.group("x")), int(m.group("y")), line.rstrip(), flags))
    return nodes, unparsed


# `hs ui --tree` lines: <indent>Widget [#id] [desc="…"] ["text"] [[flags]] @(x,y)
# The tree is the ground truth for clickability: Compose buttons/tabs are label-less
# `View [click]` wrappers whose text sits in a child TextView — the flat table hides
# them (shows `- Button` with no label), so candidates are built from the tree.
TREE_RE = re.compile(r"^(?P<indent>\s*)(?P<widget>\S+)(?P<mid>.*?)\s*@\((?P<x>-?\d+),(?P<y>-?\d+)\)\s*$")
_TREE_FLAGS = re.compile(r"\s\[([\w,]+)\]$")
_TREE_RID = re.compile(r"^\s+#(\S+)")
_TREE_DESC_TEXT = re.compile(r'^\s+desc="(?P<d>.*?)"(?:\s+"(?P<t>.*)")?$')
_TREE_TEXT = re.compile(r'^\s+"(?P<t>.*)"$')
LABEL_PARTS = 3


def parse_tree(dump: str) -> tuple[list[Node], list[str]]:
    """Parse `hs ui --tree`; verb=tap for nodes flagged click. Label-less clickable
    wrappers borrow the texts of their descendants (Compose buttons/tabs/dialog Ok)."""
    nodes: list[Node] = []
    unparsed: list[str] = []
    for line in dump.splitlines():
        if not line.strip():
            continue
        m = TREE_RE.match(line)
        if not m:
            unparsed.append(line)
            continue
        mid = m.group("mid")
        flags = ""
        fm = _TREE_FLAGS.search(mid)
        if fm:
            flags, mid = fm.group(1), mid[:fm.start()]
        mid = mid.rstrip()
        rid = ""
        rm = _TREE_RID.match(mid)
        if rm:
            rid, mid = _strip_pkg(rm.group(1)), mid[rm.end():]
        text = desc = ""
        dm = _TREE_DESC_TEXT.match(mid)
        if dm:
            desc, text = dm.group("d"), dm.group("t") or ""
        else:
            tm = _TREE_TEXT.match(mid)
            if tm:
                text = tm.group("t")
        verb = "tap" if "click" in flags.split(",") else "-"
        nodes.append(Node(verb, m.group("widget"), _norm(text), rid, int(m.group("x")), int(m.group("y")),
                          line.rstrip(), flags.replace(",", " "), _norm(desc), len(m.group("indent"))))
    # borrow labels for label-less clickable wrappers from their descendants
    for i, n in enumerate(nodes):
        if not n.actionable or n.text:
            continue
        parts: list[str] = [n.desc] if n.desc else []
        for d in nodes[i + 1:]:
            if d.depth <= n.depth:
                break
            for t in (d.text, d.desc):
                if t and t not in parts:
                    parts.append(t)
            if len(parts) >= LABEL_PARTS:
                break
        n.text = " / ".join(parts[:LABEL_PARTS])
    return nodes, unparsed


def looks_like_tree(dump: str) -> bool:
    head = [l for l in dump.splitlines()[:5] if l.strip()]
    return bool(head) and all(re.search(r"@\(-?\d+,-?\d+\)\s*$", l) for l in head)


def parse_json_dump(dump: str) -> tuple[list[Node], list[str]]:
    """Best-effort reader for `hs ui --json` (schema undocumented; kept behind AUTOPILOT_DUMP_FORM=json)."""
    try:
        data = json.loads(dump)
    except json.JSONDecodeError as e:
        raise AutopilotError(f"hs ui --json did not return JSON: {e}") from e
    items = data.get("result", data) if isinstance(data, dict) else data
    if isinstance(items, dict):
        items = items.get("nodes") or items.get("items") or []
    nodes: list[Node] = []
    for it in items or []:
        if not isinstance(it, dict):
            continue
        b = it.get("bounds") or it.get("rect") or {}
        cx = it.get("x") or it.get("cx") or (b.get("cx") if isinstance(b, dict) else None)
        cy = it.get("y") or it.get("cy") or (b.get("cy") if isinstance(b, dict) else None)
        if cx is None or cy is None:
            continue
        clickable = it.get("clickable") or it.get("actionable") or it.get("verb") == "tap"
        text = it.get("text") or it.get("label") or it.get("desc") or it.get("contentDescription") or ""
        nodes.append(Node("tap" if clickable else "-", str(it.get("class") or it.get("widget") or "?"),
                          _norm(str(text)), _strip_pkg(str(it.get("id") or it.get("resourceId") or "")),
                          int(cx), int(cy), json.dumps(it)[:200]))
    return nodes, []


def parse_dump(dump: str, form: str = "auto") -> tuple[list[Node], list[str]]:
    """Auto-detects tree vs table so saved dumps of either form work."""
    if form == "auto":
        form = "tree" if looks_like_tree(dump) else "table"
    if form == "tree":
        nodes, unparsed = parse_tree(dump)
    elif form == "json":
        nodes, unparsed = parse_json_dump(dump)
    else:
        nodes, unparsed = parse_table(dump)
    total = len(nodes) + len(unparsed)
    if total and len(unparsed) > total / 2:
        raise AutopilotError("ui table format not recognised (>50% unparsed lines) - check hs version; "
                       f"first unparsed: {unparsed[0][:120]!r}")
    return nodes, unparsed


def is_blank_dump(nodes: list[Node]) -> bool:
    return not nodes


# ------------------------------------------------------------- candidates --

def build_candidates(nodes: list[Node], *, max_candidates: int = 60, allow_risky: bool = False,
                     exclude: set[tuple[str, str]] | frozenset = frozenset()) -> tuple[list[Candidate], dict]:
    """Turn actionable nodes into model options (+ meta/terminal). Returns (candidates, stats)."""
    stats = {"dropped_risky": [], "dropped_unlabeled": 0, "dropped_excluded": 0, "truncated": False,
             "n_actionable": 0}
    seen: set[tuple[str, str]] = set()
    picked: list[Node] = []
    for n in sorted((n for n in nodes if n.actionable), key=lambda n: (n.y, n.x)):
        stats["n_actionable"] += 1
        if not n.text and not n.rid:
            stats["dropped_unlabeled"] += 1
            continue
        ident = (n.text, n.rid)
        if ident in seen:
            continue
        if ident in exclude:
            stats["dropped_excluded"] += 1
            continue
        seen.add(ident)
        picked.append(n)

    def risky(n: Node) -> bool:
        return bool(RISKY_RE.search(f"{n.text} {n.rid}"))

    if not allow_risky:
        keep = []
        for n in picked:
            if risky(n):
                stats["dropped_risky"].append(n.label)
            else:
                keep.append(n)
        picked = keep

    if len(picked) > max_candidates:
        stats["truncated"] = True
        # drop id-only nodes first, then the lowest on screen
        with_text = [n for n in picked if n.text]
        id_only = [n for n in picked if not n.text]
        picked = (with_text + id_only)[:max_candidates]
        picked.sort(key=lambda n: (n.y, n.x))

    cands: list[Candidate] = []
    for i, n in enumerate(picked, 1):
        desc = f"Tap {n.widget}"
        if n.text:
            desc += f' "{n.text[:DESC_CUT]}"'
        if n.rid:
            desc += f" [#{n.rid}]"
        if not n.text:
            near = _nearby_label(n, nodes)
            if near:
                desc += f' near "{near[:DESC_CUT]}"'
        r = risky(n)  # kept on the candidate for the threshold policy; no text marker (it biased picks)
        cands.append(Candidate(f"n{i:02d}", "tap", desc, n, r))
    for k, d in META.items():
        cands.append(Candidate(k, "meta", d))
    for k, d in TERMINAL.items():
        cands.append(Candidate(k, "terminal", d))
    assert len(cands) <= MAX_CRITERIA, "too many criteria for one choice"
    return cands, stats


# ------------------------------------------------------- state + question --

_HAS_WORD = re.compile(r"[^\W_]", re.UNICODE)


def _nearby_label(n: Node, nodes: list[Node]) -> str:
    """Nearest label above an unlabelled input (web forms put the label in a sibling)."""
    best, best_dy = "", LABEL_DY + 1
    for m in nodes:
        t = m.text or m.desc
        if not t or m is n or m.actionable:
            continue
        dy = n.y - m.y
        if 0 <= dy < best_dy and abs(m.x - n.x) < 600:
            best, best_dy = t, dy
    return best


def visible_texts(nodes: list[Node], cap: int = AUTOPILOT_MAX_TEXTS) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    for n in sorted(nodes, key=lambda n: (n.y, n.x)):
        for t in (n.text[:TEXT_CUT], n.desc[:TEXT_CUT]):
            if t and t not in seen and _HAS_WORD.search(t):
                seen.add(t)
                out.append(t)
        if len(out) >= cap:
            break
    return out[:cap]


def selected_labels(nodes: list[Node]) -> list[str]:
    """Labels of nodes flagged `selected` in the tree (the open tab); label-less ones borrow like taps."""
    out: list[str] = []
    for i, n in enumerate(nodes):
        if "selected" not in n.flags.split():
            continue
        label = n.text or n.desc
        if not label:
            for d in nodes[i + 1:]:
                if d.depth <= n.depth:
                    break
                if d.text or d.desc:
                    label = d.text or d.desc
                    break
        if label and label not in out:
            out.append(label)
    return out[:5]


def screen_title(nodes: list[Node]) -> str:
    """The page title: the first short non-actionable text in the top band (below the status bar)."""
    for n in sorted((n for n in nodes if not n.actionable and n.text and 150 < n.y < 700), key=lambda n: (n.y, n.x)):
        if 2 <= len(n.text) <= 40 and _HAS_WORD.search(n.text):
            return n.text
    return ""


def build_state(goal: str, activity: str, nodes: list[Node], candidates: list[Candidate],
                history: list[str], step_idx: int = 1, max_steps: int = 1) -> dict:
    state: dict = {
        "goal": goal,
        "screen": {"activity": activity, "title": screen_title(nodes), "selected": selected_labels(nodes),
                   "visible_texts": visible_texts(nodes)},
        "step": {"index": step_idx, "max": max_steps},
    }
    if history:
        state["history"] = history[-HISTORY_TAIL:]
    if AUTOPILOT_STATE_CANDIDATES:
        state["candidates"] = {c.key: c.desc for c in candidates if c.kind == "tap"}
    return state


def risky_allowed_instructions(text: str = None) -> str:
    """The question for a step the plan explicitly allows to pay: the "paying / confirming a purchase means
    blocked" clause contradicts it (benchmark 8: the right «Pay» button got 0.44-0.48 on OpenAI)."""
    t = QUESTION_INSTRUCTIONS if text is None else text
    return t.replace("would require typing text, paying, or confirming a purchase", "would require typing text "
                     "(paying or confirming a purchase IS allowed in this step: it is what the goal asks for)")


def build_question(candidates: list[Candidate], backend: str | None = None, allow_risky: bool = False) -> dict:
    """Two questions per call (fan-out is free): the action choice + a yes/no "already done?".
    Ollama's noul wants explicit false/true criteria; TypeSafe's takes none."""
    q = {"next": {"type": "choice", "instructions": risky_allowed_instructions() if allow_risky else QUESTION_INSTRUCTIONS,
                  "criteria": {c.key: c.desc for c in candidates}},
         "done": {"type": "noul", "instructions": DONE_INSTRUCTIONS}}
    if (backend or AUTOPILOT_BACKEND) == "ollama":
        q["done"]["criteria"] = {"false": "No - the goal is not accomplished yet on this screen",
                                 "true": "Yes - the screen/section the goal asks for is already open"}
    return q


def max_options_for(backend: str | None, max_candidates: int) -> int:
    """Tap candidates that fit next to the meta/terminal options on this backend."""
    if (backend or AUTOPILOT_BACKEND) == "ollama":
        return max(2, min(max_candidates, OLLAMA_MAX_OPTIONS - len(META) - len(TERMINAL)))
    return max_candidates


# ------------------------------------------------------------ the network --

HttpPost = Callable[[str, dict, bytes, float], tuple[int, str]]


def _http_post(url: str, headers: dict, body: bytes, timeout: float, connect_timeout: float | None = None) -> tuple[int, str]:
    try:
        import httpx  # present in the hs-mcp venv; optional elsewhere
    except ImportError:
        httpx = None
    if httpx is not None:
        t = httpx.Timeout(timeout, connect=connect_timeout) if connect_timeout else timeout
        r = httpx.post(url, headers=headers, content=body, timeout=t)
        return r.status_code, r.text
    import urllib.error
    import urllib.request
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def ask_model(state: dict, question: dict, *, api_key: str | None = None, model: str | None = None,
            timeout: float | None = None, url: str | None = None, post: HttpPost | None = None,
            stub: bool | None = None, sleep: Callable[[float], None] = time.sleep,
            backend: str | None = None) -> Decision:
    """The ONLY network call. Returns a Decision; raises AutopilotError on config/API problems.
    backend "typesafe" (default) or "ollama" (local System One API, no key)."""
    if stub if stub is not None else AUTOPILOT_STUB:
        return _stub_decide(state, question)
    backend = backend or AUTOPILOT_BACKEND
    n_opts = max(len(q.get("criteria") or {}) for q in question.values() if isinstance(q, dict))
    if backend == "ollama":
        if n_opts > OLLAMA_MAX_OPTIONS:
            raise AutopilotError(f"ollama backend allows at most {OLLAMA_MAX_OPTIONS} options per question, got {n_opts}")
        if url is None:
            return _ask_ollama_failover(state, question, model=model, timeout=timeout, post=post, sleep=sleep)
        model = model or AUTOPILOT_OLLAMA_MODEL
        timeout = timeout if timeout is not None else AUTOPILOT_OLLAMA_TIMEOUT
        key = ""
    elif backend == "typesafe":
        url = url or TYPESAFE_URL
        model = model or AUTOPILOT_MODEL
        timeout = timeout if timeout is not None else AUTOPILOT_TIMEOUT
        key = api_key if api_key is not None else TYPESAFE_API_KEY
    else:
        raise AutopilotError(f"unknown AUTOPILOT_BACKEND {backend!r} (typesafe|ollama)")
    if backend == "typesafe" and not key:
        raise AutopilotError("TYPESAFE_API_KEY not set - set it in the environment or in the file named by "
                       "TYPESAFE_ENV_FILE, or export AUTOPILOT_STUB=1")
    body = json.dumps({"state": state, "model": model, "questions": question}, ensure_ascii=False).encode()
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    if post is None:
        post = (functools.partial(_http_post, connect_timeout=OLLAMA_CONNECT_TIMEOUT) if backend == "ollama" else _http_post)
    t0 = time.time()
    status, text = 0, ""
    for attempt, wait in enumerate((0.0,) + BACKOFF_S):
        if wait:
            sleep(wait)
        try:
            status, text = post(url, headers, body, timeout)
        except Exception as e:  # connect/timeout errors from either client
            if attempt == len(BACKOFF_S):
                raise AutopilotError(f"Decision request failed: {e}") from e
            continue
        if status in (429, 529):
            continue
        break
    latency = int((time.time() - t0) * 1000)
    if status in (429, 529):
        raise AutopilotError(f"Decision API {status} after {len(BACKOFF_S) + 1} attempts (rate limit / overloaded)")
    if status == 401:
        raise AutopilotError("Decision API 401: TYPESAFE_API_KEY missing or invalid")
    if status != 200:
        raise AutopilotError(f"Decision API HTTP {status}: {text[:300]}")
    try:
        data = json.loads(text)
        ans = data["answers"]["next"]
        probs = {k: float(v) for k, v in ans.get("probabilities", {}).items()}
        done_ans = data["answers"].get("done") or {}
        done = float(done_ans["noul"]) if "noul" in done_ans else None
        data["_backend"] = backend
        return Decision(str(ans["choice"]), float(ans.get("confidence", max(probs.values(), default=0.0))),
                        probs, str(data.get("model", model)), data.get("usage", {}), latency, data, done)
    except (KeyError, ValueError, TypeError, json.JSONDecodeError) as e:
        raise AutopilotError(f"Decision response not understood: {e}: {text[:300]}") from e


def _ask_ollama_failover(state: dict, question: dict, *, model=None, timeout=None, post=None, sleep=time.sleep,
                         urls: list[str] | None = None, now=time.time) -> Decision:
    """Try each System One URL in order; connect failures, timeouts and 404s mark the host down for OLLAMA_BACKOFF s."""
    urls = urls or OLLAMA_SYSTEMONE_URLS
    errors = []
    for entry in urls:
        u, entry_model = _split_entry(entry)
        if _ollama_down.get(u, 0) > now():
            errors.append(f"{u}: skipped (down until {int(_ollama_down[u] - now())}s)")
            continue
        try:
            d = ask_model(state, question, model=model or entry_model, timeout=timeout, url=u, post=post, sleep=sleep,
                        backend="ollama", stub=False)
            d.raw["_url"] = u
            return d
        except AutopilotError as e:
            msg = str(e)
            transient = ("request failed" in msg or "HTTP 404" in msg or "after" in msg and "attempts" in msg)
            errors.append(f"{u}: {msg[:120]}")
            if transient:
                _ollama_down[u] = now() + OLLAMA_BACKOFF
            else:
                raise
    raise AutopilotError("no System One backend reachable: " + " | ".join(errors))


_STOP = {"the", "a", "an", "to", "go", "open", "tab", "on", "in", "og", "til", "på", "en", "et",
         "åpne", "gå", "then", "and", "screen", "page", "button", "click", "tap", "press", "view",
         "show", "me", "into", "from", "back"}


def _tokens(s: str) -> list[str]:
    return [w for w in re.findall(r"[\w'’-]+", s.lower()) if len(w) >= 3 and w not in _STOP]


def _stub_decide(state: dict, question: dict) -> Decision:
    """Deterministic offline stand-in: goal/candidate word overlap. Plumbing only, not intelligence."""
    crit: dict[str, str] = question["next"]["criteria"]
    goal_toks = _tokens(state.get("goal", ""))
    hist = state.get("history") or []
    conf = AUTOPILOT_STUB_CONFIDENCE

    def overlap(desc: str) -> int:
        d = desc.lower()
        # exact quoted label ("spill") beats a substring hit ("my shop")
        return sum(2 if f'"{t}"' in d else 1 if t in d else 0 for t in goal_toks)

    # goal_done: the last action's label matched the goal and the screen changed
    if hist and "screen changed" in hist[-1] and overlap(hist[-1]) > 0:
        choice = "goal_done"
    else:
        scored = [(overlap(d), k) for k, d in crit.items() if k.startswith("n") and not d.startswith("RISKY")]
        best = max(scored, default=(0, ""))
        choice = best[1] if best[0] > 0 else "blocked"
    rest = [k for k in crit if k != choice]
    probs = {choice: conf, **{k: round((1 - conf) / max(len(rest), 1), 4) for k in rest}}
    return Decision(choice, conf, probs, "stub", {"input_tokens": 0, "output_tokens": 0}, 0, {"stub": True},
                    0.9 if choice == "goal_done" else 0.1)


# ------------------------------------------------------- pure orchestration --

def decide(goal: str, dump: str, activity: str, history: list[str], *, max_candidates: int = 60,
           allow_risky: bool = False, exclude: set[tuple[str, str]] | frozenset = frozenset(),
           step_idx: int = 1, max_steps: int = 1, **ask_kw) -> tuple[Decision, list[Candidate], dict, dict]:
    nodes, unparsed = parse_dump(dump)
    if is_blank_dump(nodes):
        raise AutopilotError("empty ui dump (FLAG_SECURE screen or app not loaded) - refusing to guess")
    backend = ask_kw.get("backend") or AUTOPILOT_BACKEND
    cap = max_options_for(backend, max_candidates)
    cands, stats = build_candidates(nodes, max_candidates=cap, allow_risky=allow_risky, exclude=exclude)
    stats["unparsed"] = len(unparsed)
    stats["backend"] = backend
    state = build_state(goal, activity, nodes, cands, history, step_idx, max_steps)
    question = build_question(cands, backend, allow_risky)
    decision = ask_model(state, question, **ask_kw)
    if decision.choice not in question["next"]["criteria"]:
        raise AutopilotError(f"The model chose unknown key {decision.choice!r}")
    return decision, cands, state, stats


def to_hs_args(c: Candidate) -> list[str]:
    if c.kind == "terminal":
        return []
    if c.kind == "meta":
        return {"scroll_down": ["swipe", "up"], "scroll_up": ["swipe", "down"], "back": ["go", "back"]}[c.key]
    n = c.node
    assert n is not None
    if n.x or n.y:
        return ["tap", str(n.x), str(n.y)]
    return ["tap", f"#{n.rid}"] if n.rid else ["tap", n.text]


_DIGITS = re.compile(r"\d+")


def _sig(nodes: list[Node]) -> set[tuple[str, str, str]]:
    return {(n.widget, n.rid, _DIGITS.sub("#", n.text)) for n in nodes}


def fingerprint(nodes: list[Node]) -> str:
    return hashlib.sha1("|".join(sorted(f"{w}/{r}/{t}" for w, r, t in _sig(nodes))).encode()).hexdigest()[:12]


def changed(before: list[Node], after: list[Node], before_act: str = "", after_act: str = "") -> tuple[bool, dict]:
    sb, sa = _sig(before), _sig(after)
    union = sb | sa
    jac = len(sb & sa) / len(union) if union else 1.0
    added = [t for (_, _, t) in (sa - sb) if t][:8]
    removed = [t for (_, _, t) in (sb - sa) if t][:8]
    act_changed = bool(before_act and after_act and before_act != after_act)
    return (act_changed or jac < JACCARD_SAME), {"jaccard": round(jac, 3), "activity_changed": act_changed,
                                                 "added": added, "removed": removed}


# ------------------------------------------------------------- device side --

HsRunner = Callable[[list[str]], str]


def read_screen(run_hs: HsRunner) -> tuple[str, str, list[Node]]:
    flag = {"tree": ["--tree"], "json": ["--json"]}.get(AUTOPILOT_DUMP_FORM, [])
    dump = run_hs(["ui", *flag]) or ""
    try:
        activity = _norm(run_hs(["show", "top"]) or "")[:160]
    except Exception:
        activity = ""
    nodes, _ = parse_dump(dump)
    return dump, activity, nodes


def _verify(run_hs: HsRunner, before: list[Node], before_act: str, sleep=time.sleep,
            polls: int = 4) -> tuple[bool, dict, list[Node], str]:
    sleep(0.3)
    after, after_act, ch, diff = before, before_act, False, {}
    for i in range(polls):
        try:
            _, after_act, after = read_screen(run_hs)
        except AutopilotError:
            after = []
        ch, diff = changed(before, after, before_act, after_act)
        if ch:
            break
        if i < polls - 1:
            sleep(0.5)
    return ch, diff, after, after_act


def _hint(msg: str) -> str:
    if "no forwarded daemon" in msg:
        msg += " -> call use_device(serial) first (starts the hs daemon on the device)"
    return msg


def _pick_dict(decision: Decision, cands: list[Candidate], stats: dict, activity: str, trace) -> dict:
    by_key = {c.key: c for c in cands}
    chosen = by_key.get(decision.choice)
    return {
        "choice": decision.choice,
        "confidence": round(decision.confidence, 3),
        "done_prob": None if decision.done is None else round(decision.done, 3),
        "action": ({"hs": to_hs_args(chosen), "desc": chosen.desc} if chosen and chosen.kind != "terminal" else None),
        "top": [[k, round(p, 3), by_key[k].desc if k in by_key else "?"] for k, p in decision.top()],
        "n_candidates": sum(1 for c in cands if c.kind == "tap"),
        "dropped_risky": stats.get("dropped_risky", []),
        "truncated": stats.get("truncated", False),
        "activity": activity,
        "model": decision.model,
        "backend": decision.raw.get("_backend", "stub" if decision.raw.get("stub") else AUTOPILOT_BACKEND),
        "usage": decision.usage,
        "latency_ms": decision.latency_ms,
        "trace": str(trace.path) if trace else None,
    }


def pick(goal: str, run_hs: HsRunner, *, serial: str = "", max_candidates: int = 60,
         allow_risky: bool = False, trace: "Trace | None" = None, **ask_kw) -> dict:
    """Ask the model which node advances the goal. No device action."""
    trace = trace or new_trace(serial, "pick")
    dump, activity, _ = read_screen(run_hs)
    decision, cands, state, stats = decide(goal, dump, activity, [], max_candidates=max_candidates,
                                           allow_risky=allow_risky, **ask_kw)
    out = _pick_dict(decision, cands, stats, activity, trace)
    if trace:
        trace.write({"tool": "pick", "goal": goal, "activity": activity, "state": state, "stats": stats,
                     "criteria": {c.key: c.desc for c in cands}, "response": decision.raw,
                     "latency_ms": decision.latency_ms, "choice": decision.choice})
    return out


def step(goal: str, run_hs: HsRunner, *, serial: str = "", min_confidence: float = 0.5,
         max_candidates: int = 60, allow_risky: bool = False, history: list[str] | None = None,
         trace: "Trace | None" = None, sleep=time.sleep, step_idx: int = 1, max_steps: int = 1,
         exclude: set[tuple[str, str]] | frozenset = frozenset(), **ask_kw) -> dict:
    """Pick + execute one action + verify the screen changed."""
    trace = trace or new_trace(serial, "step")
    history = history if history is not None else []
    tm: dict[str, int] = {}  # ms per phase: read (tree dump) / decide (model round trip) / act / verify
    t = time.time()
    dump, activity, nodes = read_screen(run_hs)
    tm["read_ms"] = int((time.time() - t) * 1000); t = time.time()
    decision, cands, state, stats = decide(goal, dump, activity, history, max_candidates=max_candidates,
                                           allow_risky=allow_risky, exclude=exclude, step_idx=step_idx,
                                           max_steps=max_steps, **ask_kw)
    tm["decide_ms"] = int((time.time() - t) * 1000)
    out = _pick_dict(decision, cands, stats, activity, trace)
    out["timing"] = tm
    by_key = {c.key: c for c in cands}
    chosen = by_key[decision.choice]
    out.update({"executed": False, "changed": None, "diff": None, "after_activity": activity,
                "fingerprint": fingerprint(nodes)})
    rec = {"tool": "step", "step": step_idx, "goal": goal, "activity": activity, "state": state, "stats": stats,
           "criteria": {c.key: c.desc for c in cands}, "response": decision.raw,
           "latency_ms": decision.latency_ms, "choice": decision.choice}
    done = decision.done if decision.done is not None else -1.0
    conf = decision.confidence

    def harmless(c: Candidate) -> bool:
        return c.kind == "tap" and not c.risky and not (c.node and LEAVE_RE.match(c.node.text or c.node.desc or ""))

    def best_harmless(floor: float) -> Candidate | None:
        for k, p in decision.top(len(cands)):
            c = by_key[k]
            if c.kind == "tap":
                return c if p >= floor and harmless(c) else None
        return None

    def switch_to(c: Candidate, why: str) -> None:
        nonlocal chosen
        chosen = c
        out["choice"], out["fallback_from"] = c.key, why
        out["action"] = {"hs": to_hs_args(c), "desc": c.desc}

    action = is_action_goal(goal)  # "Tap X" / "Press X": the done-question is meaningless, never gate on it
    if done >= AUTOPILOT_DONE_THRESHOLD and not action:
        out["status"] = "goal_done"  # the fan-out yes/no question is the authority on completion
    elif chosen.key == "goal_done" and action:
        alt = best_harmless(AUTOPILOT_FALLBACK_MIN)  # an action step must act: take the best real candidate
        if alt:
            switch_to(alt, "goal_done")
        else:
            out["status"] = "needs_decision"
    elif chosen.key == "goal_done":
        # "which option is best" → judge by the option's probability (confidence only measures spread)
        p_done = max(conf, decision.probabilities.get("goal_done", 0.0))
        if p_done >= min_confidence and (decision.done is None or done >= AUTOPILOT_DONE_VETO):
            out["status"] = "goal_done"
        else:  # the choice says done but the yes/no disagrees -> best real candidate, else hand back
            alt = best_harmless(AUTOPILOT_FALLBACK_MIN)
            if alt:
                switch_to(alt, "goal_done")
            else:
                out["status"] = "needs_decision"
    elif chosen.key == "blocked":
        if conf >= min_confidence:
            out["status"] = "blocked"
        else:  # a weak "none of these" loses to a plausible harmless tap
            alt = best_harmless(AUTOPILOT_HARMLESS_MIN)
            if alt:
                switch_to(alt, "blocked")
                out["low_confidence_act"] = True
            else:
                out["status"] = "needs_decision"
    elif chosen.kind == "meta":
        if conf < min_confidence:
            out["status"] = "needs_decision"
    elif chosen.kind == "tap":
        if chosen.risky and conf < min_confidence:
            out["status"] = "needs_decision"  # payments/deletes never act below the threshold
        elif conf < min_confidence:
            if conf >= AUTOPILOT_HARMLESS_MIN and harmless(chosen):
                out["low_confidence_act"] = True  # reversible tap on the argmax (TypeSafe: harmless preference)
            else:
                out["status"] = "needs_decision"
    if "status" in out:
        pass
    else:
        args = to_hs_args(chosen)
        t = time.time()
        run_hs(args)
        tm["act_ms"] = int((time.time() - t) * 1000); t = time.time()
        ch, diff, after, after_act = _verify(run_hs, nodes, activity, sleep)
        tm["verify_ms"] = int((time.time() - t) * 1000)
        out.update({"executed": True, "status": "acted", "changed": ch, "diff": diff, "after_activity": after_act,
                    "after_nodes": after})
        rec["action"] = {"hs": args, "desc": chosen.desc}
        rec["verify"] = {"changed": ch, **diff}
    rec["outcome"] = out["status"]
    rec["timing"] = tm
    if trace:
        trace.write(rec)
    out["_chosen"] = chosen  # internal (stripped by do()/server)
    return out


def do(goal: str, run_hs: HsRunner, *, serial: str = "", max_steps: int = 12, min_confidence: float = 0.5,
       max_candidates: int = 60, allow_risky: bool = False, trace: "Trace | None" = None,
       sleep=time.sleep, deadline_s: float = AUTOPILOT_DEADLINE_S, **ask_kw) -> dict:
    """Loop step() until goal_done / blocked / needs_decision / stuck / max_steps / deadline / error."""
    trace = trace or new_trace(serial, "do")
    t0 = time.time()
    history: list[str] = []
    steps: list[dict] = []
    usage = Counter()
    tried: dict[str, set[tuple[str, str]]] = {}
    seen_fp: Counter = Counter()
    nochange = 0
    status, extra = "max_steps", {}
    final_activity = ""
    timing: Counter = Counter()
    for i in range(1, max_steps + 1):
        if time.time() - t0 > deadline_s:
            status = "deadline"
            break
        try:
            t = time.time()
            fp_probe = read_screen(run_hs)[2]
            timing["read_ms"] += int((time.time() - t) * 1000)
        except AutopilotError as e:
            status, extra = "error", {"error": str(e)}
            break
        except Exception as e:
            status, extra = "error", {"error": _hint(str(e))}
            break
        fp = fingerprint(fp_probe)
        seen_fp[fp] += 1
        if seen_fp[fp] >= 3:
            status, extra = "stuck", {"reason": "same screen seen 3 times"}
            break
        try:
            r = step(goal, run_hs, serial=serial, min_confidence=min_confidence, max_candidates=max_candidates,
                     allow_risky=allow_risky, history=history, trace=trace, sleep=sleep, step_idx=i,
                     max_steps=max_steps, exclude=tried.get(fp, set()), **ask_kw)
        except AutopilotError as e:
            status, extra = "error", {"error": str(e)}
            break
        except Exception as e:
            status, extra = "error", {"error": _hint(str(e))}
            break
        for k, v in (r.get("usage") or {}).items():
            if isinstance(v, (int, float)):
                usage[k] += v
        chosen: Candidate = r.pop("_chosen")
        r.pop("after_nodes", None)
        timing.update(r.get("timing") or {})
        final_activity = r.get("after_activity") or r.get("activity") or final_activity
        steps.append({"i": i, "choice": r["choice"], "desc": chosen.desc, "confidence": r["confidence"],
                      "changed": r["changed"], "status": r["status"]})
        if r["status"] in ("goal_done", "blocked"):
            status = r["status"]
            break
        if r["status"] == "needs_decision":
            status, extra = "needs_decision", {"candidates": r["top"], "n_candidates": r["n_candidates"]}
            break
        history.append(f"{i}: {chosen.desc} -> {'screen changed' if r['changed'] else 'no change'}")
        if r["changed"] and is_action_goal(goal) and chosen.kind == "tap":
            status, extra = "goal_done", {"note": "action goal: done after the tap changed the screen"}
            break
        if r["changed"]:
            nochange = 0
        else:
            nochange += 1
            tried.setdefault(fp, set()).add(chosen.ident)
            if nochange >= 2:
                status, extra = "stuck", {"reason": "two consecutive actions changed nothing"}
                break
    out = {"status": status, "goal": goal, "steps": steps, "final_activity": final_activity,
           "usage_total": dict(usage), "elapsed_s": round(time.time() - t0, 1), "timing": dict(timing),
           "trace": str(trace.path) if trace else None, **extra}
    if trace:
        trace.write({"summary": out})
    return out


# --------------------------------------------------------------- plan runner --
# A plan is a JSON list of steps; the runner executes it server-side and only returns to the
# agent on a hand-back (needs_decision / blocked / stuck / error). Step forms:
#   {"goal": "Open Books"}                      state goal -> do();  {"allow_risky": true, "max_steps": 5} optional
#   {"step": "Press Next"}                      single action -> step()
#   {"type": "1234"} | {"type": {"cred": "demo.nnin"}}   keystrokes (credential resolved server-side)
#   {"hide_keyboard": true}                     tap the IME chevron (AUTOPILOT_KB_HIDE_XY)
#   {"wait_for": "Spillet er", "timeout": 20}   hs wait (text or selector)
#   {"open": "com.example.shop.feature"} | {"key": "back"} | {"sleep": 3}
#   {"screenshot": "caption"}                   hs see -> AUTOPILOT_LOG_DIR/<ts>-<caption>.jpg (path in the result)
#   {"note": "..."}                             trace marker only
#   {"tap": "#id" | "Exact label" | "x,y"}      scripted tap, no model: resolve against the tree (first match in
#                                               reading order; "nth": 1 for the second), tap the centre, verify
#                                               the screen changed like step() does. {"verify": false} skips that.
AdbRunner = Callable[[list[str]], str]

TIMING_KEYS = ("read_ms", "decide_ms", "act_ms", "verify_ms", "wait_ms", "sleep_ms", "type_ms", "other_ms")


def find_node(nodes: list[Node], selector: str, nth: int = 0) -> Node:
    """Resolve a scripted selector: '#rid', 'x,y', or a label (exact text/desc, case-insensitive, clickable
    nodes first; then any node; then substring). Nodes are already in reading order."""
    sel = selector.strip()
    m = re.fullmatch(r"(\d+)\s*,\s*(\d+)", sel)
    if m:
        return Node("tap", "xy", sel, "", int(m[1]), int(m[2]))
    if sel.startswith("#"):
        rid = sel[1:]
        hits = [n for n in nodes if n.rid == rid or n.rid.endswith("/" + rid) or n.rid.endswith(":id/" + rid)]
    else:
        low = sel.lower()
        exact = lambda n: n.text.lower() == low or n.desc.lower() == low  # noqa: E731
        hits = [n for n in nodes if n.actionable and exact(n)] or [n for n in nodes if exact(n)] \
            or [n for n in nodes if n.actionable and (low in n.text.lower() or low in n.desc.lower())]
    if len(hits) <= nth:
        raise AutopilotError(f"tap {selector!r}: no such element on screen" + (f" (nth={nth}, {len(hits)} hits)" if hits else ""))
    return hits[nth]


def wait_for_text(run_hs: HsRunner, needle: str, timeout: float, sleep=time.sleep) -> float:
    """Poll the tree until some node text/desc contains `needle` (case-insensitive). Raises AutopilotError on timeout."""
    t0 = time.time()
    n = needle.lower()
    while True:
        try:
            _, _, nodes = read_screen(run_hs)
        except AutopilotError:
            nodes = []
        if any(n in (x.text + " " + x.desc + " #" + x.rid).lower() for x in nodes):  # text, desc or #resource-id
            return round(time.time() - t0, 1)
        if time.time() - t0 > timeout:
            raise AutopilotError(f"wait_for {needle!r}: not on screen after {timeout:.0f}s")
        sleep(0.5)


def run_plan(plan: list[dict], run_hs: HsRunner, *, run_adb: AdbRunner | None = None,
             cred: Callable[[str], str] | None = None, serial: str = "", start: int = 0,
             min_confidence: float = 0.5, trace: "Trace | None" = None, sleep=time.sleep,
             shot_dir: Path | None = None, **ask_kw) -> dict:
    trace = trace or new_trace(serial, "plan")
    t0 = time.time()
    results: list[dict] = []
    status, paused_at, hand_back = "completed", None, None
    usage = Counter()
    timing_total: Counter = Counter()
    decisions = 0
    for i, st in enumerate(plan):
        if i < start:
            continue
        ts = time.time()
        rec: dict = {"i": i, "step": st}
        try:
            if "goal" in st or "step" in st:
                g = st.get("goal") or st.get("step")
                kw = dict(serial=serial, min_confidence=min_confidence, allow_risky=bool(st.get("allow_risky")),
                          trace=trace, sleep=sleep, **ask_kw)
                if "step" in st:
                    r = step(g, run_hs, **kw)
                    r.pop("_chosen", None); r.pop("after_nodes", None)
                    ok = r["status"] in ("acted", "goal_done")
                else:
                    r = do(g, run_hs, max_steps=int(st.get("max_steps", 5)), **kw)
                    ok = r["status"] == "goal_done"
                for k, v in (r.get("usage_total") or r.get("usage") or {}).items():
                    if isinstance(v, (int, float)):
                        usage[k] += v
                rec.update({"status": r["status"], "detail": r.get("steps") or r.get("action"),
                            "low_confidence_act": r.get("low_confidence_act", False), "timing": r.get("timing") or {}})
                decisions += len(r.get("steps") or []) or 1
                if not ok:
                    status, paused_at = "paused", i
                    hand_back = {k: r.get(k) for k in ("status", "candidates", "top", "error", "reason", "choice", "confidence") if r.get(k) is not None}
            elif "type" in st:
                v = st["type"]
                if isinstance(v, dict) and "cred" in v:
                    if not cred:
                        raise AutopilotError("plan uses a credential but no credential lookup is available")
                    text = cred(v["cred"])
                    rec["step"] = {"type": {"cred": v["cred"]}}  # never echo the value
                else:
                    text = str(v)
                t = time.time()
                run_hs(["type", text]); rec["status"] = "ok"
                rec["timing"] = {"type_ms": int((time.time() - t) * 1000)}
            elif st.get("hide_keyboard"):
                if not run_adb:
                    raise AutopilotError("hide_keyboard needs an adb runner")
                x, y = AUTOPILOT_KB_HIDE_XY
                run_adb(["shell", "input", "tap", str(x), str(y)]); rec["status"] = "ok"
            elif "wait_for" in st:
                t = time.time()
                rec["waited_s"] = wait_for_text(run_hs, st["wait_for"], float(st.get("timeout", 15)), sleep)
                rec["status"] = "ok"
                rec["timing"] = {"wait_ms": int((time.time() - t) * 1000)}
            elif "open" in st:
                run_hs(["open", st["open"]]); rec["status"] = "ok"
            elif "key" in st:
                run_hs(["go", st["key"]]); rec["status"] = "ok"
            elif "sleep" in st:
                sleep(float(st["sleep"])); rec["status"] = "ok"
                rec["timing"] = {"sleep_ms": int(float(st["sleep"]) * 1000)}
            elif "screenshot" in st:
                caption = str(st["screenshot"])
                slug = re.sub(r"[^a-z0-9]+", "-", caption.lower()).strip("-")[:40]
                d = shot_dir or AUTOPILOT_LOG_DIR
                d.mkdir(parents=True, exist_ok=True)
                man_path = d / "manifest.json"
                if man_path.exists():  # a lab report dir: number the shot and register it (caption → chapter)
                    man = json.loads(man_path.read_text())
                    n = len(man.get("shots", [])) + 1
                    fn = d / f"{n:02d}-{slug}.jpg"
                    run_hs(["see", str(fn), "--size", "1080"])
                    man.setdefault("shots", []).append({"file": fn.name, "caption": caption, "t": time.time()})
                    man_path.write_text(json.dumps(man))
                    rec["markdown"] = f"![{caption}]({fn.name})"
                else:
                    fn = d / f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{slug}.jpg"
                    run_hs(["see", str(fn), "--size", "1080"])
                rec.update({"status": "ok", "file": str(fn)})
            elif "tap" in st:  # scripted: known element, no decision model
                tm: dict[str, int] = {}
                t = time.time()
                _, act0, nodes = read_screen(run_hs)
                tm["read_ms"] = int((time.time() - t) * 1000)
                n = find_node(nodes, str(st["tap"]), int(st.get("nth", 0)))
                args = ["tap", str(n.x), str(n.y)] if (n.x or n.y) else (["tap", f"#{n.rid}"] if n.rid else ["tap", n.text])
                t = time.time()
                run_hs(args)
                tm["act_ms"] = int((time.time() - t) * 1000)
                rec.update({"status": "ok", "action": {"hs": args, "desc": f"Tap {n.widget} {n.text or n.desc!r}"}})
                if st.get("verify", True):
                    t = time.time()
                    ch, diff, _, _ = _verify(run_hs, nodes, act0, sleep)
                    tm["verify_ms"] = int((time.time() - t) * 1000)
                    rec.update({"changed": ch, "diff": diff})
                rec["timing"] = tm
            elif "note" in st:
                rec["status"] = "ok"
            else:
                raise AutopilotError(f"unknown plan step: {st}")
        except AutopilotError as e:
            rec["status"], rec["error"] = "error", str(e)
            status, paused_at, hand_back = "error", i, {"error": str(e)}
        except Exception as e:  # device/hs errors
            rec["status"], rec["error"] = "error", _hint(str(e))
            status, paused_at, hand_back = "error", i, {"error": _hint(str(e))}
        rec["elapsed_s"] = round(time.time() - ts, 1)
        tm_step = rec.get("timing") or {}
        timing_total.update(tm_step)
        timing_total["other_ms"] += max(0, int((time.time() - ts) * 1000) - sum(tm_step.values()))
        results.append(rec)
        if trace:
            trace.write({"tool": "plan", "plan_step": rec})
        if status != "completed":
            break
    out = {"status": status, "paused_at": paused_at, "hand_back": hand_back, "steps": results,
           "n_steps": len(plan), "usage_total": dict(usage), "elapsed_s": round(time.time() - t0, 1),
           "timing_total": {k: int(timing_total.get(k, 0)) for k in TIMING_KEYS}, "decisions": decisions,
           "trace": str(trace.path) if trace else None}
    if status == "paused":
        out["resume_hint"] = f"act on the hand-back yourself, then call again with start={paused_at + 1}"
    if trace:
        trace.write({"summary": out})
    return out


# ------------------------------------------------------------------- trace --

class Trace:
    def __init__(self, path: Path):
        self.path = path

    def write(self, rec: dict) -> None:
        rec = {"ts": datetime.now().isoformat(timespec="seconds"), **rec}
        try:
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False, default=str) + "\n")
        except OSError as e:
            print(f"[autopilot] trace write failed ({e}); {json.dumps(rec, default=str)[:400]}", file=sys.stderr)


def new_trace(serial: str, tool: str, log_dir: Path | None = None) -> Trace | None:
    d = log_dir or AUTOPILOT_LOG_DIR
    try:
        d.mkdir(parents=True, exist_ok=True)
    except OSError:
        return None
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    return Trace(d / f"{ts}-{serial or 'device'}-{tool}.jsonl")


# --------------------------------------------------------------------- CLI --

def _cli_runner(serial: str, hs_bin: str = "/usr/local/bin/hs") -> HsRunner:
    import subprocess

    def run(args: list[str]) -> str:
        cmd = [hs_bin] + (["--device", serial] if serial else []) + args
        r = subprocess.run(cmd, capture_output=True, timeout=30)
        if r.returncode != 0:
            raise RuntimeError(f"hs {' '.join(args)} failed ({r.returncode}): {r.stderr.decode(errors='replace').strip()}")
        return r.stdout.decode("utf-8", "replace").strip()
    return run


def _dump_runner(path: str, activity: str = "") -> HsRunner:
    dump = Path(path).read_text("utf-8")

    def run(args: list[str]) -> str:
        if args[:1] == ["ui"]:
            return dump
        if args[:2] == ["show", "top"]:
            return activity
        return ""
    return run


def _cli_cred(ref: str) -> str:
    """'account.field' from /etc/lab-credentials.json (or 'device_pin'); value never printed."""
    d = json.loads(Path(os.environ.get("LAB_CREDENTIALS", "/etc/lab-credentials.json")).read_text())
    if ref == "device_pin":
        return str(d["device_pin"])
    acct, _, field = ref.partition(".")
    return str(d["accounts"][acct][field])


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="autopilot", description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("pick", "step", "do"):
        s = sub.add_parser(name)
        s.add_argument("--goal", required=True)
        s.add_argument("--serial", default=os.environ.get("ADB_SERIAL", ""))
        s.add_argument("--dump", help="offline: read the ui table from this file instead of a device")
        s.add_argument("--activity", default="", help="activity string to use with --dump")
        s.add_argument("--stub", action="store_true", help="use the offline stub instead of the decision API")
        s.add_argument("--max-candidates", type=int, default=60)
        s.add_argument("--allow-risky", action="store_true")
        s.add_argument("--min-confidence", type=float, default=0.5)
        s.add_argument("--max-steps", type=int, default=12)
    sp = sub.add_parser("plan")
    sp.add_argument("--file", required=True, help="JSON list of plan steps")
    sp.add_argument("--serial", default=os.environ.get("ADB_SERIAL", ""))
    sp.add_argument("--start", type=int, default=0)
    sp.add_argument("--min-confidence", type=float, default=0.5)
    sp.add_argument("--stub", action="store_true")
    sub.add_parser("selftest")
    a = p.parse_args(argv)
    if a.cmd == "plan":
        import subprocess
        plan = json.loads(Path(a.file).read_text("utf-8"))
        run = _cli_runner(a.serial)
        adb = lambda args: subprocess.run(["adb"] + (["-s", a.serial] if a.serial else []) + args, capture_output=True, timeout=30).stdout.decode()  # noqa: E731
        out = run_plan(plan, run, run_adb=adb, cred=_cli_cred, serial=a.serial, start=a.start,
                       min_confidence=a.min_confidence, **({"stub": True} if a.stub else {}))
        print(json.dumps(out, ensure_ascii=False, indent=2, default=str))
        return 0 if out["status"] == "completed" else 1
    if a.cmd == "selftest":
        sys.path.insert(0, str(Path(__file__).resolve().parent / "tests"))
        import test_autopilot  # noqa: WPS433
        return test_autopilot.run_all()
    run = _dump_runner(a.dump, a.activity) if a.dump else _cli_runner(a.serial)
    kw = dict(serial=a.serial, max_candidates=a.max_candidates, allow_risky=a.allow_risky)
    if a.stub:
        kw["stub"] = True
    if a.cmd == "pick":
        out = pick(a.goal, run, **kw)
    elif a.cmd == "step":
        out = step(a.goal, run, min_confidence=a.min_confidence, **kw)
        out.pop("_chosen", None)
        out.pop("after_nodes", None)
    else:
        out = do(a.goal, run, max_steps=a.max_steps, min_confidence=a.min_confidence, **kw)
    print(json.dumps(out, ensure_ascii=False, indent=2, default=str))
    return 0 if out.get("status") not in ("error",) else 1


if __name__ == "__main__":
    sys.exit(main())
