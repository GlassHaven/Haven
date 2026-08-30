#!/usr/bin/env python3
"""Export every Android UI string (English source + all translations) to one
JSON file the GitHub Pages translation contribution page fetches:
``docs/i18n/strings.json``.

Reuses the same ``<string name="…">…</string>`` shape parsing as
``i18n_drift.py`` / ``i18n_backfill.py``. The nearest preceding ``<!-- … -->``
is attached to each string as translator context, and ``%1$s``-style format
args are surfaced as hints.

Rerun after editing any strings.xml — CI (lint job) regenerates and fails if
``docs/i18n/strings.json`` drifts (``git diff --exit-code``).
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "docs" / "i18n" / "strings.json"

# The shipped translation locales (English ``values/`` is the source). ``sr``
# is in progress — partial coverage is fine here (missing keys just stay out
# of ``t``); it joins check-i18n-coverage.py's list once it reaches 100%.
LOCALES = ["ar", "bn", "de", "es", "fr", "hi", "ja", "ko", "pt", "ru", "sr", "zh"]

STRING_RE = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.DOTALL)
# Comments and strings interleaved, in document order, so we can attach the
# most recent comment (a section header) as context to the strings that follow.
TOKEN_RE = re.compile(
    r'<!--(.*?)-->|<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.DOTALL
)
ARG_RE = re.compile(r'%(?:\d+\$)?[sd]')


def parse_source(text):
    """Ordered [{name, en, comment, args}] from a source strings.xml."""
    out = []
    last_comment = ""
    for m in TOKEN_RE.finditer(text):
        if m.group(1) is not None:  # a comment
            last_comment = " ".join(m.group(1).split())
        else:
            name, val = m.group(2), m.group(3)
            out.append({
                "name": name,
                "en": val,
                "comment": last_comment,
                "args": sorted(set(ARG_RE.findall(val))),
            })
    return out


def parse_translations(path):
    """{name: value} from a locale strings.xml (empty if the file is absent)."""
    if not path.exists():
        return {}
    return {m.group(1): m.group(2)
            for m in STRING_RE.finditer(path.read_text(encoding="utf-8"))}


def discover_modules():
    """First-party app/feature/core modules that own a source strings.xml.

    Only these three trees are scanned: their strings.xml are tracked in the
    main repo, so the export is byte-identical in CI. Submodule-provided
    resources (e.g. termlib) are deliberately excluded — their checkout state
    varies, which would make the staleness check (git diff) false-fail.
    """
    mods = []
    for base in ("app", "feature", "core"):
        for src in (REPO / base).glob("**/src/main/res/values/strings.xml"):
            rel = src.relative_to(REPO).as_posix()
            if "/build/" in rel or "test-app/" in rel:
                continue
            module = rel.split("/src/main/res/")[0]
            mods.append((module, src))
    return sorted(mods)


def main():
    modules = []
    total = 0
    for module, src in discover_modules():
        strings = parse_source(src.read_text(encoding="utf-8"))
        translations = {
            loc: parse_translations(
                src.parent.parent / f"values-{loc}" / "strings.xml")
            for loc in LOCALES
        }
        for s in strings:
            t = {}
            for loc in LOCALES:
                v = translations[loc].get(s["name"])
                if v is not None:
                    t[loc] = v
            s["t"] = t
        total += len(strings)
        modules.append({
            "module": module,
            "path": f"{module}/src/main/res/values/strings.xml",
            "strings": strings,
        })

    # No HEAD-sha / timestamp stamp: the output must depend only on the
    # strings.xml sources so CI can regenerate and `git diff --exit-code` it
    # (a self-referential HEAD sha would differ pre- vs post-commit).
    data = {"locales": LOCALES, "modules": modules}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    print(f"wrote {OUT.relative_to(REPO)}: "
          f"{len(modules)} modules, {len(LOCALES)} locales, {total} strings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
