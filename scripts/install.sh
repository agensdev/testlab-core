#!/usr/bin/env bash
# install.sh — installs the CLI from a downloaded release package (macOS on Apple silicon, Linux).
#
#   bash install.sh [PACKAGE.tar.gz | FOLDER] [--prefix DIR] [--bin DIR] [--no-modify-path] [--setup]
#
# With no argument it takes the newest <slug>-cli-*-<os>-<arch>.tar.gz for this machine from the
# script's own folder, the current folder or ~/Downloads. A SHA256SUMS file next to the package is
# checked. A first install unpacks to ~/.local/opt/<slug> and links ~/.local/bin/<slug>; when the
# CLI is already installed it runs `<slug> upgrade --from PACKAGE` instead (service stopped around
# the swap, the old version kept for `<slug> upgrade --rollback`).
set -euo pipefail

prefix="$HOME/.local/opt"
bindir="$HOME/.local/bin"
modify_path=1
run_setup=0
pkg=""

die() { echo "install.sh: $*" >&2; exit 1; }
say() { echo "==> $*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) prefix="${2:?--prefix needs a folder}"; shift ;;
    --bin) bindir="${2:?--bin needs a folder}"; shift ;;
    --no-modify-path) modify_path=0 ;;
    --setup) run_setup=1 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) die "unknown option: $1 (see --help)" ;;
    *) [ -z "$pkg" ] || die "one package at a time"; pkg="$1" ;;
  esac
  shift
done

# ---- this machine -----------------------------------------------------------------------------
case "$(uname -s)" in
  Darwin) os=macos ;;
  Linux) os=linux ;;
  *) die "this script is for macOS and Linux; on Windows unzip the package and run bin\\*.bat" ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64) arch=x64 ;;
  *) die "unsupported CPU: $(uname -m)" ;;
esac
[ "$os/$arch" = macos/x64 ] && die "Intel Macs are not supported (the macOS package is for Apple silicon)"
platform="$os-$arch"

# ---- the package --------------------------------------------------------------------------------
newest_in() {  # newest matching package in a folder, or nothing
  ls -t "$1"/*-cli-*-"$platform".tar.gz 2>/dev/null | head -1 || true
}
if [ -z "$pkg" ]; then
  for d in "$(cd "$(dirname "$0")" && pwd)" "$PWD" "$HOME/Downloads"; do
    pkg=$(newest_in "$d"); [ -n "$pkg" ] && break
  done
  [ -n "$pkg" ] || die "no *-cli-*-$platform.tar.gz found here or in ~/Downloads; download it from the newest release, or pass its path"
elif [ -d "$pkg" ]; then
  d="$pkg"; pkg=$(newest_in "$d")
  [ -n "$pkg" ] || die "no *-cli-*-$platform.tar.gz in $d"
fi
[ -f "$pkg" ] || die "not found: $pkg"
pkg="$(cd "$(dirname "$pkg")" && pwd)/$(basename "$pkg")"
name=$(basename "$pkg")
case "$name" in
  *-cli-*-"$platform".tar.gz) ;;
  *-cli-*.tar.gz) die "$name is not for this machine ($platform)" ;;
  *) die "$name doesn't look like a CLI package (<slug>-cli-<version>-$platform.tar.gz)" ;;
esac
slug="${name%%-cli-*}"
version=$(echo "$name" | sed -n 's/.*-cli-\([0-9][0-9.]*\)-.*/\1/p')
say "package: $name"

sums="$(dirname "$pkg")/SHA256SUMS"
if [ -f "$sums" ]; then
  want=$(awk -v f="$name" '$2 == f || $2 == "*"f {print $1}' "$sums")
  if [ -n "$want" ]; then
    if command -v sha256sum >/dev/null; then got=$(sha256sum "$pkg" | awk '{print $1}'); else got=$(shasum -a 256 "$pkg" | awk '{print $1}'); fi
    [ "$got" = "$want" ] || die "checksum mismatch for $name (expected $want, got $got): download it again"
    say "checksum OK (SHA256SUMS)"
  else
    say "SHA256SUMS has no line for $name; not checked"
  fi
fi

home="$prefix/$slug"
link="$bindir/$slug"
unquarantine() { [ "$os" = macos ] && xattr -dr com.apple.quarantine "$1" 2>/dev/null || true; }

# ---- already installed: let the CLI upgrade itself ----------------------------------------------
if [ -x "$home/bin/$slug" ]; then
  say "$slug is already installed in $home; upgrading with: $slug upgrade --from $name"
  unquarantine "$home"
  "$home/bin/$slug" upgrade --from "$pkg"
  unquarantine "$home"
else
  # ---- first install: unpack beside the target, check it runs, then move it in ---------------
  mkdir -p "$prefix"
  stage=$(mktemp -d "$prefix/.$slug-install.XXXXXX")
  trap 'rm -rf "$stage"' EXIT
  tar xzf "$pkg" -C "$stage"
  [ -x "$stage/$slug/bin/$slug" ] || die "$name has no $slug/bin/$slug inside"
  unquarantine "$stage/$slug"
  "$stage/$slug/bin/$slug" --version >/dev/null 2>&1 || die "the unpacked CLI does not start; run $stage/$slug/bin/$slug --version to see why"
  mv "$stage/$slug" "$home"
  say "installed ${version:+$version }in $home"
fi

# ---- on the PATH ------------------------------------------------------------------------------------
mkdir -p "$bindir"
ln -sf "$home/bin/$slug" "$link"
say "linked $link"

case ":$PATH:" in
  *":$bindir:"*) on_path=1 ;;
  *) on_path=0 ;;
esac
if [ "$on_path" = 0 ]; then
  shown="$bindir"; case "$bindir" in "$HOME"/*) shown="\$HOME${bindir#"$HOME"}" ;; esac
  line="export PATH=\"$shown:\$PATH\""
  case "$(basename "${SHELL:-}")" in
    zsh) rc="$HOME/.zshrc" ;;
    bash) if [ "$os" = macos ]; then rc="$HOME/.bash_profile"; else rc="$HOME/.bashrc"; fi ;;
    fish) rc=""; line="fish_add_path $bindir" ;;
    *) rc="$HOME/.profile" ;;
  esac
  if [ "$modify_path" = 1 ] && [ -n "$rc" ]; then
    if ! grep -qsF "$bindir" "$rc"; then
      printf '\n# added by the %s installer\n%s\n' "$slug" "$line" >> "$rc"
      say "added $bindir to PATH in $rc (open a new terminal, or run: $line)"
    fi
  else
    say "$bindir is not on your PATH; add this to your shell profile: $line"
  fi
  export PATH="$bindir:$PATH"
fi

"$link" version || true

if [ "$run_setup" = 1 ]; then
  exec "$link" setup
else
  echo
  echo "Next: $slug setup   (tokens, project profile, phones, Claude and the background service)"
fi
