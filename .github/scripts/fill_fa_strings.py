#!/usr/bin/env python3
"""Keep values-fa/strings.xml complete after the original project adds new strings.

Every <string> and <plurals> that exists in values-en but not in values-fa is copied over with its
English text, so a Persian phone shows English for it (Android would fall back to Russian, the
default). The copied blocks are marked, and their names are printed, one per line, to stdout and
to the file given as the optional first argument, so someone can translate them later.
Replace the English text and delete the marker comment once a string is translated.
"""
import re
import sys
import xml.etree.ElementTree as ET

RES = "app/src/main/res"
EN, FA = f"{RES}/values-en/strings.xml", f"{RES}/values-fa/strings.xml"
BLOCK = re.compile(r'[ \t]*<(string|plurals)\s+name="([^"]+)"[^>]*?(?:/>|>.*?</\1>)[ \t]*\n?', re.S)
MARK = "    <!-- TODO: translate (copied from English) -->\n"


def names(path):
    return {e.get("name") for e in ET.parse(path).getroot() if e.tag in ("string", "plurals")}


def main():
    en_text = open(EN, encoding="utf-8").read()
    fa_text = open(FA, encoding="utf-8").read()
    have = names(FA)
    missing = []
    chunks = []
    for m in BLOCK.finditer(en_text):
        if m.group(2) not in have:
            if 'translatable="false"' in m.group(0):
                continue
            missing.append(m.group(2))
            chunks.append(m.group(0) if m.group(0).endswith("\n") else m.group(0) + "\n")
    if missing:
        end = fa_text.rindex("</resources>")
        fa_text = fa_text[:end].rstrip("\n") + "\n\n" + MARK + "".join(chunks) + fa_text[end:]
        open(FA, "w", encoding="utf-8").write(fa_text)
    ET.parse(FA)  # fail loudly if the result is not well-formed
    extra = sorted(have - names(EN))
    for n in missing:
        print(n)
    if len(sys.argv) > 1:
        open(sys.argv[1], "w", encoding="utf-8").write("\n".join(missing) + ("\n" if missing else ""))
    if extra:
        print(f"note: {len(extra)} key(s) only in values-fa (removed upstream?): {', '.join(extra)}", file=sys.stderr)


if __name__ == "__main__":
    main()
