#!/usr/bin/env python3
"""Фиксирует выпущенный релиз в репозитории:

* добавляет (или обновляет) строку в таблице «История релизов» в CHANGELOG.md;
* поднимает `LAST_RELEASE` в version.properties.

Запускается CI сразу после публикации GitHub Release
(см. .github/workflows/android.yml, шаг «Record release»), поэтому номера
версий в репозитории всегда соответствуют фактическим релизам.

Скрипт идемпотентен: повторный запуск с тем же номером релиза лишь обновит
существующую строку.

Пример:
    python3 scripts/record_release.py \
        --release 2 --version 1.0.2 --build 38 --tag v1.0.2 \
        --repo zigorminsk-debug/IP-TV-player
"""
from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SECTION_TITLE = "## История релизов"
TABLE_HEADER = "| Релиз | Версия | Сборка | Дата (UTC) | GitHub Release |"
TABLE_SEPARATOR = "|---:|---|---:|---|---|"
SECTION_NOTE = (
    "<!-- Таблица ниже обновляется автоматически (scripts/record_release.py). -->"
)
ROW_RE = re.compile(r"^\|\s*#(\d+)\s*\|")


def build_row(release: int, version: str, build: int, date: str, tag: str, repo: str) -> str:
    link = f"[{tag}](https://github.com/{repo}/releases/tag/{tag})"
    return f"| #{release} | {version} | {build} | {date} | {link} |"


def update_changelog(path: Path, row: str, release: int) -> bool:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()

    if SECTION_TITLE not in text:
        # Создаём секцию перед первым «## [» (обычно ## [Unreleased]).
        anchor = next(
            (i for i, line in enumerate(lines) if line.startswith("## [")),
            len(lines),
        )
        block = [SECTION_TITLE, "", SECTION_NOTE, "", TABLE_HEADER, TABLE_SEPARATOR, ""]
        lines[anchor:anchor] = block

    try:
        sep_idx = next(
            i for i, line in enumerate(lines) if line.strip() == TABLE_SEPARATOR
        )
    except StopIteration:
        print("error: таблица истории релизов не найдена и не создана", file=sys.stderr)
        return False

    # Собираем текущие строки таблицы.
    start = sep_idx + 1
    end = start
    while end < len(lines) and lines[end].lstrip().startswith("|"):
        end += 1
    rows = [line for line in lines[start:end] if line.strip()]

    rows = [r for r in rows if not (ROW_RE.match(r) and int(ROW_RE.match(r).group(1)) == release)]
    rows.append(row)

    def key(r: str) -> int:
        m = ROW_RE.match(r)
        return int(m.group(1)) if m else -1

    rows.sort(key=key, reverse=True)
    lines[start:end] = rows

    new_text = "\n".join(lines).rstrip("\n") + "\n"
    if new_text == text:
        return False
    path.write_text(new_text, encoding="utf-8")
    return True


def update_properties(path: Path, release: int) -> bool:
    text = path.read_text(encoding="utf-8")
    match = re.search(r"^(\s*LAST_RELEASE\s*=\s*)(\d+)\s*$", text, flags=re.MULTILINE)
    if not match:
        print(f"warning: LAST_RELEASE не найден в {path}", file=sys.stderr)
        return False
    if int(match.group(2)) >= release:
        return False
    new_text = (
        text[: match.start()] + f"{match.group(1)}{release}" + text[match.end():]
    )
    path.write_text(new_text, encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--release", required=True, type=int, help="порядковый номер релиза")
    parser.add_argument("--version", required=True, help="versionName, например 1.0.2")
    parser.add_argument("--build", required=True, type=int, help="versionCode (номер сборки CI)")
    parser.add_argument("--tag", required=True, help="тег релиза, например v1.0.2")
    parser.add_argument("--repo", required=True, help="owner/name на GitHub")
    parser.add_argument("--date", default=datetime.now(timezone.utc).strftime("%Y-%m-%d"))
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--properties", default="version.properties")
    args = parser.parse_args()

    if args.release <= 0 or args.build <= 0:
        print("error: номер релиза и сборки должны быть положительными", file=sys.stderr)
        return 1

    row = build_row(args.release, args.version, args.build, args.date, args.tag, args.repo)

    changed = False
    changelog = Path(args.changelog)
    if changelog.is_file():
        changed |= update_changelog(changelog, row, args.release)
    else:
        print(f"warning: {changelog} не найден — пропускаю", file=sys.stderr)

    props = Path(args.properties)
    if props.is_file():
        changed |= update_properties(props, args.release)
    else:
        print(f"warning: {props} не найден — пропускаю", file=sys.stderr)

    print(f"release #{args.release} ({args.version}, build {args.build}): "
          f"{'записан' if changed else 'уже актуален'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
