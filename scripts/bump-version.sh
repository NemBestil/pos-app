#!/bin/bash
set -euo pipefail

# Usage: ./scripts/bump-version.sh <major|minor|patch>
# Bumps the version in package.json and android/app/build.gradle

BUMP_TYPE="${1:-}"

if [[ -z "$BUMP_TYPE" || ! "$BUMP_TYPE" =~ ^(major|minor|patch)$ ]]; then
  echo "Usage: $0 <major|minor|patch>"
  echo "  major: 1.0.0 -> 2.0.0"
  echo "  minor: 1.0.0 -> 1.1.0"
  echo "  patch: 1.0.0 -> 1.0.1"
  exit 1
fi

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE_JSON="$PROJECT_ROOT/package.json"
BUILD_GRADLE="$PROJECT_ROOT/android/app/build.gradle"

# Read current version from package.json
CURRENT_VERSION=$(node -p "require('$PACKAGE_JSON').version")
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

# Read current versionCode from build.gradle
CURRENT_CODE=$(grep 'versionCode' "$BUILD_GRADLE" | head -1 | sed 's/[^0-9]//g')

# Bump version
case "$BUMP_TYPE" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"
NEW_CODE=$((CURRENT_CODE + 1))

echo "Bumping version: $CURRENT_VERSION -> $NEW_VERSION"
echo "Bumping versionCode: $CURRENT_CODE -> $NEW_CODE"

# Update package.json
node -e "
const fs = require('fs');
const pkg = JSON.parse(fs.readFileSync('$PACKAGE_JSON', 'utf8'));
pkg.version = '$NEW_VERSION';
fs.writeFileSync('$PACKAGE_JSON', JSON.stringify(pkg, null, 2) + '\n');
"

# Update build.gradle
sed -i '' "s/versionCode $CURRENT_CODE/versionCode $NEW_CODE/" "$BUILD_GRADLE"
sed -i '' "s/versionName \"$CURRENT_VERSION\"/versionName \"$NEW_VERSION\"/" "$BUILD_GRADLE"

echo "Done! Updated to v$NEW_VERSION (build $NEW_CODE)"
