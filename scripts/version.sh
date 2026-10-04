#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Вычисляет версию приложения и номер релиза.
# ---------------------------------------------------------------------------
# Схема: versionName = MAJOR.MINOR.<номер релиза>, т.е. patch == номер релиза.
# База (MAJOR/MINOR) и номер последнего релиза хранятся в version.properties,
# фактическим счётчиком релизов служит список тегов vX.Y.Z в репозитории.
#
# Режимы:
#   --mode next                 следующий релиз (push в main)      -> 1.0.2
#   --mode tag --tag v1.0.5     релиз по тегу                      -> 1.0.5
#   --mode manual --version 1.0.5   ручной запуск с версией        -> 1.0.5
#   --mode dev                  обычная сборка ветки               -> 1.0.2-dev
#
# Вывод — строки KEY=VALUE, пригодные для eval / $GITHUB_OUTPUT:
#   VERSION_NAME=1.0.2
#   RELEASE_NUMBER=2
#   TAG=v1.0.2
#   PRERELEASE_SUFFIX=            (или "-dev")
#
# Примеры:
#   eval "$(scripts/version.sh --mode next)"
#   scripts/version.sh --mode tag --tag v1.1.7
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="${VERSION_PROPERTIES:-$ROOT/version.properties}"
REMOTE="${VERSION_REMOTE:-origin}"

die() {
    echo "version.sh: error: $*" >&2
    exit 1
}

prop() {
    local key="$1" value
    value="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$PROPS" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
    [ -n "$value" ] || die "$key не найден в $PROPS"
    printf '%s' "$value"
}

# Все теги релизов (vMAJOR.MINOR.PATCH) — сначала с удалённого репозитория,
# иначе локальные. Теги вида auto-* / v1.2.3-rc1 счётчиком не считаются.
release_tags() {
    local tags=""
    if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
        tags="$(git -C "$ROOT" ls-remote --tags --refs "$REMOTE" 'v*' 2>/dev/null | awk '{print $2}' | sed 's#refs/tags/##')"
        if [ -z "$tags" ]; then
            tags="$(git -C "$ROOT" tag -l 'v*' 2>/dev/null || true)"
        fi
    fi
    printf '%s\n' "$tags" | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true
}

# Следующий номер релиза = max(кол-во релизов, max patch, LAST_RELEASE) + 1,
# с пропуском номеров, тег которых уже существует.
next_release_number() {
    local tags count maxpatch n
    tags="$(release_tags)"
    count="$(printf '%s\n' "$tags" | grep -c '^v' || true)"
    maxpatch="$(printf '%s\n' "$tags" | sed 's/^v//' | awk -F. 'NF==3 {print $3}' | sort -n | tail -1)"
    [ -n "$maxpatch" ] || maxpatch=0
    [ -n "$count" ] || count=0

    n="$LAST_RELEASE"
    if [ "$count" -gt "$n" ]; then n="$count"; fi
    if [ "$maxpatch" -gt "$n" ]; then n="$maxpatch"; fi
    n=$((n + 1))
    while printf '%s\n' "$tags" | grep -qx "v${MAJOR}.${MINOR}.${n}"; do
        n=$((n + 1))
    done
    printf '%s' "$n"
}

MODE="dev"
TAG_INPUT=""
VERSION_INPUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --mode) MODE="${2:-}"; shift 2 ;;
        --tag) TAG_INPUT="${2:-}"; shift 2 ;;
        --version) VERSION_INPUT="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) die "неизвестный аргумент: $1" ;;
    esac
done

MAJOR="$(prop VERSION_MAJOR)"
MINOR="$(prop VERSION_MINOR)"
LAST_RELEASE="$(prop LAST_RELEASE)"

SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+$'

case "$MODE" in
    tag)
        [ -n "$TAG_INPUT" ] || die "--mode tag требует --tag vX.Y.Z"
        VERSION="${TAG_INPUT#refs/tags/}"
        VERSION="${VERSION#v}"
        [[ "$VERSION" =~ $SEMVER_RE ]] || die "тег должен быть в формате vMAJOR.MINOR.PATCH (получено: $TAG_INPUT)"
        RELEASE="${VERSION##*.}"
        SUFFIX=""
        ;;
    manual)
        [ -n "$VERSION_INPUT" ] || die "--mode manual требует --version X.Y.Z"
        VERSION="${VERSION_INPUT#v}"
        [[ "$VERSION" =~ $SEMVER_RE ]] || die "версия должна быть в формате MAJOR.MINOR.PATCH (получено: $VERSION_INPUT)"
        RELEASE="${VERSION##*.}"
        SUFFIX=""
        ;;
    next)
        RELEASE="$(next_release_number)"
        VERSION="${MAJOR}.${MINOR}.${RELEASE}"
        SUFFIX=""
        ;;
    dev)
        RELEASE="$(next_release_number)"
        VERSION="${MAJOR}.${MINOR}.${RELEASE}-dev"
        SUFFIX="-dev"
        ;;
    *)
        die "неизвестный режим: $MODE (next|tag|manual|dev)"
        ;;
esac

echo "VERSION_NAME=${VERSION}"
echo "RELEASE_NUMBER=${RELEASE}"
echo "TAG=v${VERSION%-dev}"
echo "PRERELEASE_SUFFIX=${SUFFIX}"
