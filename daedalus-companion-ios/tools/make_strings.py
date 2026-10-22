#!/usr/bin/env python3
"""Builds the string catalogs from the Android apps' strings (EN + FR):
- Daedalus/Resources/Localizable.xcstrings (iPhone) from daedalus-companion, plus tools/ios_strings.json
- DaedalusWatch/Resources/Localizable.xcstrings (Apple Watch) from daedalus-watch, plus tools/watch_strings.json
Run from daedalus-companion-ios:

    python3 tools/make_strings.py

Keys stay the Android names, so both apps share wording. Android formats (%1$s, %d) become
iOS formats (%1$@, %lld)."""
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
TARGETS = [
    (HERE.parent / "daedalus-companion/app/src/main/res", HERE / "Daedalus/Resources/Localizable.xcstrings", HERE / "tools/ios_strings.json"),
    (HERE.parent / "daedalus-watch/app/src/main/res", HERE / "DaedalusWatch/Resources/Localizable.xcstrings", HERE / "tools/watch_strings.json"),
]


def to_ios(text: str) -> str:
    text = text.strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    text = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@")
    text = re.sub(r"%(\d+\$)?s", lambda m: f"%{m.group(1) or ''}@", text)
    text = re.sub(r"%(\d+\$)?d", lambda m: f"%{m.group(1) or ''}lld", text)
    return text


def inner_text(el) -> str:
    return (el.text or "") + "".join(ET.tostring(c, encoding="unicode") for c in el)


def read(android, lang_dir: str):
    path = android / lang_dir / "strings.xml"
    if not path.exists():
        return {}, {}
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for el in root:
        name = el.get("name")
        if el.get("translatable") == "false" and lang_dir != "values":
            continue
        if el.tag == "string":
            strings[name] = to_ios(inner_text(el))
        elif el.tag == "plurals":
            plurals[name] = {i.get("quantity"): to_ios(inner_text(i)) for i in el.findall("item")}
    return strings, plurals


def unit(value: str):
    return {"stringUnit": {"state": "translated", "value": value}}


def build(android, out_path, extra_path):
    langs = {"en": read(android, "values"), "fr": read(android, "values-fr")}
    extra = json.loads(extra_path.read_text()) if extra_path.exists() else {}
    catalog = {"sourceLanguage": "en", "version": "1.0", "strings": {}}
    out = catalog["strings"]

    for lang, (strings, plurals) in langs.items():
        for key, value in strings.items():
            out.setdefault(key, {"extractionState": "manual", "localizations": {}})["localizations"][lang] = unit(value)
        for key, forms in plurals.items():
            out.setdefault(key, {"extractionState": "manual", "localizations": {}})["localizations"][lang] = {
                "variations": {"plural": {q: unit(v) for q, v in forms.items()}}
            }
    for key, per_lang in extra.items():
        entry = out.setdefault(key, {"extractionState": "manual", "localizations": {}})
        for lang, value in per_lang.items():
            entry["localizations"][lang] = unit(value)

    out_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    missing = [k for k, v in out.items() if "fr" not in v["localizations"]]
    print(f"{len(out)} keys -> {out_path.relative_to(HERE)}" + (f" (no French: {', '.join(missing)})" if missing else ""))


def main():
    for android, out_path, extra_path in TARGETS:
        build(android, out_path, extra_path)


if __name__ == "__main__":
    main()
