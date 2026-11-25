#!/usr/bin/env bash
set -euo pipefail

tag="v$VERSION"
repository_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY"
api="repos/$GITHUB_REPOSITORY/releases"
out="$RUNNER_TEMP/prooftray-release"
apk="$out/ProofTray-$VERSION-release.apk"
source_archive="$out/ProofTray-$VERSION-source.tar.gz"

# The authenticated list includes drafts, so an interrupted upload can resume.
state=$(gh api --paginate "$api?per_page=100" | jq -r --arg tag "$tag" '.[] | select(.tag_name == $tag) | .draft')
if [[ "$state" == false && "$NEW_VERSION" == false ]]; then
  echo "$tag is already published." >> "$GITHUB_STEP_SUMMARY"
  exit 0
fi
if [[ -n "$state" && ( "$state" != true || "$NEW_VERSION" == true ) ]]; then
  echo "::error::An existing release conflicts with $tag."
  exit 1
fi

git ls-files --recurse-submodules -z |
  tar --create --gzip --file="$source_archive" --no-recursion --null --files-from=-
if [[ "$NEW_VERSION" == true ]]; then
  # A concurrent branch update rejects both refs. Never publish a tag for a rejected version commit.
  git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push --atomic origin \
    "HEAD:refs/heads/$GITHUB_REF_NAME" "refs/tags/$tag"
fi

if [[ -z "$state" ]]; then
  notes="$RUNNER_TEMP/prooftray-release-notes.md"
  sed -e "s|{{repository_url}}|$repository_url|g" -e "s|{{version}}|$VERSION|g" .github/release-notes.md > "$notes"
  gh release create "$tag" --verify-tag --draft --title "ProofTray $VERSION" --notes-file "$notes" --generate-notes
fi
gh release upload "$tag" "$apk" "$source_archive" --clobber
release_id=$(gh release view "$tag" --json databaseId --jq .databaseId)
gh api --method PATCH "$api/$release_id" -F draft=false -f make_latest=legacy --silent
echo "[ProofTray $VERSION]($repository_url/releases/tag/$tag)" >> "$GITHUB_STEP_SUMMARY"
