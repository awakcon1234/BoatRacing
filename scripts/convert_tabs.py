from __future__ import annotations

import os
from pathlib import Path


def should_skip_path(path: Path) -> bool:
    p = str(path).replace("/", "\\").lower()
    return any(
        part in p
        for part in (
            "\\build\\",
            "\\target\\",
            "\\.gradle\\",
            "\\.git\\",
        )
    )


# Tabs are desired for these code/build files.
INCLUDE_SUFFIXES = {
    ".java",
    ".gradle",
    ".groovy",
}

# YAML forbids tabs; markdown alignment is often space-sensitive.
EXCLUDE_SUFFIXES = {
    ".yml",
    ".yaml",
    ".md",
}


def convert_leading_indent(line: str) -> str:
    # Convert only leading indent made of *whole* 4-space groups.
    # Any remainder spaces (non-multiple-of-4) are preserved.
    i = 0
    n = len(line)
    while i < n and line[i] == " ":
        i += 1

    if i == 0:
        return line

    tabs = i // 4
    rem = i % 4
    if tabs == 0:
        return line

    return ("\t" * tabs) + (" " * rem) + line[i:]


def process_file(path: Path) -> bool:
    raw = path.read_bytes()

    # Detect newline style for round-trip stability.
    text = raw.decode("utf-8")
    eol = "\r\n" if "\r\n" in text else "\n"

    lines = text.splitlines(keepends=False)
    out_lines = [convert_leading_indent(l) for l in lines]
    out_text = eol.join(out_lines)

    # Preserve trailing newline if present.
    if text.endswith("\r\n"):
        out_text += "\r\n"
    elif text.endswith("\n"):
        out_text += "\n"

    if out_text == text:
        return False

    path.write_text(out_text, encoding="utf-8", newline="")
    return True


def main() -> int:
    root = Path(__file__).resolve().parents[1]

    changed = 0
    total = 0

    for dirpath, dirnames, filenames in os.walk(root):
        dp = Path(dirpath)
        if should_skip_path(dp):
            dirnames[:] = []
            continue

        for name in filenames:
            p = dp / name
            if should_skip_path(p):
                continue

            suf = p.suffix.lower()
            if suf in EXCLUDE_SUFFIXES:
                continue
            if suf not in INCLUDE_SUFFIXES:
                continue

            total += 1
            try:
                if process_file(p):
                    changed += 1
            except UnicodeDecodeError:
                # Skip non-utf8 files.
                continue

    print(f"Updated files: {changed} / {total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
