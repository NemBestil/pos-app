#!/bin/bash
set -euo pipefail

# Usage: ./scripts/tag-apk.sh <pre|full>
# Opens a release-details document, tags the current version with apk-x.y.z-pre
# (prerelease) or apk-x.y.z (full release), and pushes it.

RELEASE_TYPE="${1:-}"
if [[ "$RELEASE_TYPE" != "pre" && "$RELEASE_TYPE" != "full" ]]; then
    echo "Usage: ./scripts/tag-apk.sh <pre|full>"
    exit 1
fi

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE_JSON="$PROJECT_ROOT/package.json"

# Check for uncommitted changes
if [[ -n $(git status --porcelain) ]]; then
    echo "❌ Error: There are uncommitted changes. Please commit or stash them first."
    exit 1
fi

# Read current version from package.json
CURRENT_VERSION=$(node -p "require('$PACKAGE_JSON').version")
if [[ "$RELEASE_TYPE" == "pre" ]]; then
    TAG="apk-$CURRENT_VERSION-pre"
else
    TAG="apk-$CURRENT_VERSION"
fi

RELEASE_METADATA_FILE="$(mktemp "${TMPDIR:-/tmp}/release-apk.XXXXXX")"
trap 'rm -f "$RELEASE_METADATA_FILE"' EXIT

printf '[release_title]\n\n[release_notes]\n\n' > "$RELEASE_METADATA_FILE"
vim "$RELEASE_METADATA_FILE"

echo "🏷️  Creating tag: $TAG"

# Check if tag already exists locally
if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "⚠️  Tag $TAG already exists locally."
else
    git tag -a "$TAG" -F "$RELEASE_METADATA_FILE"
fi

# Push tag to origin
echo "🚀 Pushing tag: $TAG to origin..."
if git push origin "$TAG"; then
    echo "✅ Done! Tag $TAG created and pushed."
else
    echo "❌ Error: Failed to push tag $TAG."
    exit 1
fi
