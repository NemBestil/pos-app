#!/bin/bash
set -euo pipefail

# Usage: ./scripts/tag-apk.sh
# Tags the current version with apk-x.y.z and pushes it.

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE_JSON="$PROJECT_ROOT/package.json"

# Read current version from package.json
CURRENT_VERSION=$(node -p "require('$PACKAGE_JSON').version")
TAG="apk-$CURRENT_VERSION"

echo "🏷️  Creating tag: $TAG"

# Check if tag already exists locally
if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "⚠️  Tag $TAG already exists locally."
else
    git tag "$TAG"
fi

# Push tag to origin
echo "🚀 Pushing tag: $TAG to origin..."
if git push origin "$TAG"; then
    echo "✅ Done! Tag $TAG created and pushed."
else
    echo "❌ Error: Failed to push tag $TAG."
    exit 1
fi
