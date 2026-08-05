#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$ROOT_DIR/erii-distribution"
PKG_DIR="$DIST_DIR/packages"
GRADLEW="$ROOT_DIR/gradlew"

# ---- helpers ----
dim()   { echo -e "\033[2m$*\033[0m"; }
green() { echo -e "\033[0;32m$*\033[0m"; }
yellow(){ echo -e "\033[1;33m$*\033[0m"; }
red()   { echo -e "\033[0;31m$*\033[0m"; }
cyan()  { echo -e "\033[0;36m$*\033[0m"; }
bold()  { echo -e "\033[1m$*\033[0m"; }

CORE_JAR_BASES=(
    erii-common erii-core erii-spi-annotation erii-spi-core
    onebot-core onebot-lib onebot-mock onebot-sdk
)

# ---- flags ----
DRY_RUN=false
SKIP_CLI=false
SKIP_CORE=false
SKIP_PLUGINS=false
MODE="full"

for arg in "$@"; do
    case "$arg" in
        --dry-run)   DRY_RUN=true ;;
        --skip-cli)  SKIP_CLI=true ;;
        --skip-core) SKIP_CORE=true ;;
        --skip-plugins) SKIP_PLUGINS=true ;;
        build)       MODE="build" ;;
        version)     MODE="version" ;;
    esac
done

# ================================================================
#  Phase 1: Build & Copy
# ================================================================

step_cli() {
    cyan "--- Step 1: erii-cli ---"

    if $DRY_RUN; then
        yellow "  erii-cli  $(dim '(dry-run)')"
        return 0
    fi

    # --- .conf -> erii-config/.conf ---
    local src="$ROOT_DIR/erii-cli/.conf"
    local dst="$PKG_DIR/erii-config/.conf"
    rm -rf "$dst" && mkdir -p "$dst"
    if [ -d "$src" ]; then cp -R "$src"/* "$dst"/; fi
    dim "  .conf -> erii-config/.conf"

    # --- conf/ -> erii-config/conf ---
    src="$ROOT_DIR/erii-cli/conf"
    dst="$PKG_DIR/erii-config/conf"
    rm -rf "$dst" && mkdir -p "$dst"
    if [ -d "$src" ]; then cp -R "$src"/* "$dst"/; fi
    dim "  conf/ -> erii-config/conf"

    # --- build ---
    dim "  Building erii-cli (go run mage) ..."
    (cd "$ROOT_DIR/erii-cli" && go run github.com/magefile/mage)

    # --- copy platform binaries --–-
    local build="$ROOT_DIR/erii-cli/build"
    if [ -d "$build" ]; then
        for os_dir in "$build"/*/; do
            [ -d "$os_dir" ] || continue
            local os_name
            os_name=$(basename "$os_dir")
            for arch_dir in "$os_dir"/*/; do
                [ -d "$arch_dir" ] || continue
                local arch_name
                arch_name=$(basename "$arch_dir")
                local dest="$PKG_DIR/erii-cli/$os_name/$arch_name"
                mkdir -p "$dest"
                local f
                for f in "$arch_dir"/*; do
                    case "$(basename "$f")" in
                        erii-cli)
                            cp "$f" "$dest/erii-cli"
                            chmod 755 "$dest/erii-cli"
                            ;;
                        erii-cli.exe)
                            cp "$f" "$dest/erii-cli.exe"
                            ;;
                    esac
                done
            done
        done
    fi

    # --- opts/ -> erii-core/opts ---
    src="$ROOT_DIR/erii-cli/opts"
    dst="$PKG_DIR/erii-core/opts"
    rm -rf "$dst" && mkdir -p "$dst"
    if [ -d "$src" ]; then cp -R "$src"/* "$dst"/; fi
    dim "  opts/ -> erii-core/opts"

    # git-add changed paths in distribution repo
    git -C "$DIST_DIR" add \
        packages/erii-config/.conf \
        packages/erii-config/conf \
        packages/erii-cli \
        packages/erii-core/opts \
        >/dev/null 2>&1 || true
    dim "  git add (erii-config, erii-cli, erii-core/opts)"

    green "  ✓ erii-cli"
}

strip_jar_version() {
    local name="$1"
    name="${name%.jar}"
    # match trailing -digits.digits.digits... and remove
    sed -E 's/-[0-9]+\.[0-9]+\.[0-9].*$//' <<< "$name"
}

is_core_jar() {
    local base="$1"
    for c in "${CORE_JAR_BASES[@]}"; do
        [ "$base" = "$c" ] && return 0
    done
    return 1
}

step_core() {
    cyan "--- Step 2: erii-core ---"

    if $DRY_RUN; then
        yellow "  erii-core  $(dim '(dry-run)')"
        return 0
    fi

    # --- build ---
    dim "  Building :erii-core:installDist ..."
    "$GRADLEW" :erii-core:installDist

    local lib_src="$ROOT_DIR/erii-core/build/install/erii-core/lib"
    local lib_dst="$PKG_DIR/erii-core/lib"

    if [ ! -d "$lib_src" ]; then
        red "  ✗ Build output not found: $lib_src"
        return 1
    fi

    mkdir -p "$lib_dst"
    rm -f "$lib_dst"/*.jar

    local copied=0 excluded=0
    for jar in "$lib_src"/*.jar; do
        local jarname
        jarname=$(basename "$jar")
        case "$jarname" in
            driver-*|driver-bundle*) ((excluded++)); continue ;;
        esac
        cp "$jar" "$lib_dst/"
        ((copied++))
    done
    dim "  ${copied} jars copied, ${excluded} excluded (driver*)"

    # --- split ---
    # Move core jars aside so split.mjs only sees 3rd-party deps
    local tmp="$PKG_DIR/erii-core/.tmp-core-jars"
    mkdir -p "$tmp"
    for jar in "$lib_dst"/*.jar; do
        local base
        base=$(strip_jar_version "$(basename "$jar")")
        if is_core_jar "$base"; then
            mv "$jar" "$tmp/"
        fi
    done

    node "$PKG_DIR/erii-deps/split.mjs" "$lib_dst"

    # Clean dependency jars and restore core jars
    rm -f "$lib_dst"/*.jar
    for jar in "$tmp"/*.jar; do
        mv "$jar" "$lib_dst/"
    done
    rmdir "$tmp"

    git -C "$DIST_DIR" add packages/erii-core/lib packages/erii-deps >/dev/null 2>&1 || true
    dim "  git add (erii-core/lib, erii-deps)"

    green "  ✓ erii-core"
}

step_plugins() {
    cyan "--- Step 3: erii-plugins ---"

    if $DRY_RUN; then
        yellow "  erii-plugins  $(dim '(dry-run)')"
        return 0
    fi

    dim "  Building assembleAllPlugins ..."
    "$GRADLEW" -p erii-plugins assembleAllPlugins

    local plugins_build="$ROOT_DIR/erii-plugins/build/plugins"
    if [ ! -d "$plugins_build" ]; then
        red "  ✗ Build output not found: $plugins_build"
        return 1
    fi

    local postinstall_template="$PKG_DIR/create-erii-plugin/postinstall.template.js"

    for plugin_dir in "$plugins_build"/*/; do
        [ -d "$plugin_dir" ] || continue
        local plugin_id
        plugin_id=$(basename "$plugin_dir")
        local dest="$PKG_DIR/erii-plugins/$plugin_id"
        mkdir -p "$dest"
        rm -f "$dest"/*.zip

        # 复制 zip 和 README
        cp -R "$plugin_dir"* "$dest"/

        # 新插件：创建 package.json + 复制 postinstall
        if [ ! -f "$dest/package.json" ]; then
            node -e "
                const fs = require('fs');
                const pkg = {
                    name: '@spcookie/erii-plugin-${plugin_id}',
                    version: '1.0.0',
                    description: 'Erii ${plugin_id} plugin',
                    scripts: { postinstall: 'node postinstall.js' },
                    dependencies: { 'adm-zip': '^0.5.16' },
                    files: ['*.zip', 'postinstall.js'],
                    author: 'spcookie',
                    license: 'MIT',
                    repository: {
                        type: 'git',
                        url: 'git+https://github.com/spcookie/erii.git',
                        directory: 'erii-distribution/packages/erii-plugins/${plugin_id}'
                    },
                    bugs: { url: 'https://github.com/spcookie/erii/issues' },
                    homepage: 'https://github.com/spcookie/erii#readme',
                    publishConfig: { access: 'public' },
                    keywords: ['erii', 'plugin', '${plugin_id}']
                };
                fs.writeFileSync('${dest}/package.json', JSON.stringify(pkg, null, 2) + '\n');
            "
            cp "$postinstall_template" "$dest/postinstall.js"
            git -C "$DIST_DIR" add "packages/erii-plugins/${plugin_id}" >/dev/null 2>&1 || true
            dim "  ${plugin_id} -> erii-plugins/${plugin_id}  $(green '[new]')"
        else
            dim "  ${plugin_id} -> erii-plugins/${plugin_id}"
        fi
    done

    git -C "$DIST_DIR" add packages/erii-plugins >/dev/null 2>&1 || true
    dim "  git add (erii-plugins)"

    green "  ✓ erii-plugins"
}

build_phase() {
    bold "$(cyan '--- Build Phase ---')"

    if ! $SKIP_CLI;    then step_cli;    else dim "  erii-cli (skipped)"; fi
    if ! $SKIP_CORE;   then step_core;   else dim "  erii-core (skipped)"; fi
    if ! $SKIP_PLUGINS; then step_plugins; else dim "  erii-plugins (skipped)"; fi

    green "Build complete."
}

# ================================================================
#  Phase 2: Version Detection & Auto Bump
# ================================================================

# Check git changes in erii-distribution for a path
has_changes() {
    local out
    out=$(git -C "$DIST_DIR" status --porcelain -- "$1" 2>/dev/null)
    [ -n "$out" ]
}

# Check if package.json version already differs from HEAD
already_bumped() {
    local pkg="$1"
    if [ ! -f "$pkg" ]; then return 1; fi
    local head_ver
    head_ver=$(git -C "$DIST_DIR" show "HEAD:${pkg#$DIST_DIR/}" 2>/dev/null | node -e "
        const chunks = [];
        process.stdin.on('data', c => chunks.push(c));
        process.stdin.on('end', () => {
            try { console.log(JSON.parse(chunks.join('')).version); }
            catch { console.log(''); }
        });
    " 2>/dev/null)
    if [ -z "$head_ver" ]; then return 1; fi
    local cur_ver
    cur_ver=$(node -p "require('$pkg').version" 2>/dev/null)
    [ "$head_ver" != "$cur_ver" ]
}

get_version() {
    local pkg="$1"
    if [ -f "$pkg" ]; then
        node -p "require('$pkg').version" 2>/dev/null || echo "0.0.0"
    else
        echo "0.0.0"
    fi
}

bump_patch() {
    local ver="$1"
    ver="${ver%%-*}"
    IFS='.' read -r major minor patch <<< "$ver"
    : "${patch:=0}"
    echo "${major}.${minor}.$((patch + 1))"
}

set_pkg_version() {
    local pkg="$1" ver="$2"
    if $DRY_RUN; then return; fi
    node -e "
        const fs = require('fs');
        const p = require('$pkg');
        p.version = '$ver';
        fs.writeFileSync('$pkg', JSON.stringify(p, null, 2) + '\n');
    "
}

auto_bump_one() {
    local label="$1" pkg="$2" changed="$3"
    local cur
    cur=$(get_version "$pkg")

    if already_bumped "$pkg"; then
        echo -e "  ${label}  $(dim "${cur}")  $(yellow '(version already bumped)')"
        return 1  # already bumped, don't trigger meta
    fi

    if [ "$changed" = "true" ]; then
        local next
        next=$(bump_patch "$cur")
        set_pkg_version "$pkg" "$next"
        echo -e "  ${label}  $(dim "${cur}") $(green "→ ${next}")  $(yellow '[changed]')"
        return 0
    else
        echo -e "  ${label}  $(dim "${cur} (unchanged)")"
        return 1
    fi
}

version_phase() {
    bold "$(cyan '--- Version Phase (auto) ---')"
    echo ""

    local any_bumped=false

    # --- [1] deps ---
    local changed=false
    has_changes "packages/erii-deps" && changed=true
    local cur cur2
    cur=$(get_version "$PKG_DIR/erii-deps/deps-haiku/package.json")

    if already_bumped "$PKG_DIR/erii-deps/deps-haiku/package.json"; then
        echo -e "  [1] deps      $(dim "${cur}")  $(yellow '(version already bumped)')"
    elif $changed; then
        cur2=$(bump_patch "$cur")
        if ! $DRY_RUN; then
            node "$PKG_DIR/erii-deps/set-version.mjs" "$cur2" >/dev/null
        fi
        echo -e "  [1] deps      $(dim "${cur}") $(green "→ ${cur2}")  $(yellow '[changed]')"
        any_bumped=true
    else
        echo -e "  [1] deps      $(dim "${cur} (unchanged)")"
    fi

    # --- [2] cli ---
    changed=false
    has_changes "packages/erii-cli" && changed=true
    cur=$(get_version "$PKG_DIR/erii-cli/erii-darwin/amd64/package.json")

    if already_bumped "$PKG_DIR/erii-cli/erii-darwin/amd64/package.json"; then
        echo -e "  [2] cli       $(dim "${cur}")  $(yellow '(version already bumped)')"
    elif $changed; then
        cur2=$(bump_patch "$cur")
        if ! $DRY_RUN; then
            node "$PKG_DIR/erii-cli/set-version.mjs" "$cur2" >/dev/null
        fi
        echo -e "  [2] cli       $(dim "${cur}") $(green "→ ${cur2}")  $(yellow '[changed]')"
        any_bumped=true
    else
        echo -e "  [2] cli       $(dim "${cur} (unchanged)")"
    fi

    # --- [3] config ---
    changed=false
    has_changes "packages/erii-config" && changed=true
    auto_bump_one "[3] config  " "$PKG_DIR/erii-config/package.json" "$changed" && any_bumped=true || true

    # --- [4] core ---
    changed=false
    has_changes "packages/erii-core" && changed=true
    auto_bump_one "[4] core    " "$PKG_DIR/erii-core/package.json" "$changed" && any_bumped=true || true

    # --- [5] plugins ---
    local plugins_dir="$PKG_DIR/erii-plugins"
    if [ -d "$plugins_dir" ]; then
        for pkg in "$plugins_dir"/*/package.json; do
            [ -f "$pkg" ] || continue
            local pname
            pname=$(basename "$(dirname "$pkg")")
            changed=false
            has_changes "packages/erii-plugins/$pname" && changed=true
            auto_bump_one "    ${pname}" "$pkg" "$changed" && any_bumped=true || true
        done
    fi

    # --- [6] meta (erii + create-erii) ---
    cur=$(get_version "$PKG_DIR/erii/package.json")
    local meta_changed=false

    # meta follows if any depended group was bumped (deps/cli/config/core, not plugins)
    local core_groups_bumped=false
    has_changes "packages/erii-deps" && core_groups_bumped=true
    has_changes "packages/erii-cli" && core_groups_bumped=true
    has_changes "packages/erii-config" && core_groups_bumped=true
    has_changes "packages/erii-core" && core_groups_bumped=true

    if already_bumped "$PKG_DIR/erii/package.json"; then
        echo -e "  [6] meta      $(dim "${cur}")  $(yellow '(version already bumped)')"
    elif $core_groups_bumped; then
        cur2=$(bump_patch "$cur")
        set_pkg_version "$PKG_DIR/erii/package.json" "$cur2"
        set_pkg_version "$PKG_DIR/create-erii/package.json" "$cur2"
        echo -e "  [6] meta      $(dim "${cur}") $(green "→ ${cur2}")  $(yellow '[follows bumped groups]')"
        any_bumped=true
    elif $any_bumped; then
        # Only plugins bumped — meta bump is optional; still follow for consistency
        cur2=$(bump_patch "$cur")
        set_pkg_version "$PKG_DIR/erii/package.json" "$cur2"
        set_pkg_version "$PKG_DIR/create-erii/package.json" "$cur2"
        echo -e "  [6] meta      $(dim "${cur}") $(green "→ ${cur2}")  $(yellow '[follows bumped groups]')"
    else
        echo -e "  [6] meta      $(dim "${cur} (unchanged)")"
    fi

    # --- sync erii deps ---
    echo ""
    dim "  Syncing erii dependencies..."
    if ! $DRY_RUN; then
        node "$PKG_DIR/erii/sync-versions.mjs"
    else
        yellow "  sync-versions  $(dim '[dry-run]')"
    fi
}

# ================================================================
#  Main
# ================================================================

main() {
    bold "Erii Distribution Workflow"
    echo ""

    case "$MODE" in
        version)
            version_phase
            ;;
        build)
            build_phase
            ;;
        *)
            build_phase
            echo ""
            version_phase
            ;;
    esac

    if $DRY_RUN; then
        echo ""
        yellow "No files modified (dry-run)."
    else
        echo ""
        green "Done."
    fi
}

main
