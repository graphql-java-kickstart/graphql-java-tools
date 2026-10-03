#!/bin/bash
set -ev

getVersion() {
  grep -m1 -o "<version>.*</version>$" pom.xml | awk -F'[><]' '{print $3}'
}

requireSnapshot() {
  local APP_VERSION=$(getVersion)
  if [[ ${APP_VERSION} != *-SNAPSHOT ]]; then
    echo "Version in pom.xml must end with -SNAPSHOT to be released: '${APP_VERSION}'"
    exit 1
  fi
}

removeSnapshots() {
  sed -i 's/-SNAPSHOT//' pom.xml
}

commitRelease() {
  local APP_VERSION=$(getVersion)
  git commit -a -m "Update version for release"
  git tag -a "v${APP_VERSION}" -m "Tag release version"
}

publishTag() {
  local APP_VERSION=$(getVersion)
  git push origin "v${APP_VERSION}"
  # Release tags aren't on master's first-parent history, so GitHub can't find the previous one on its own
  local PREVIOUS_TAG=$(gh release view --json tagName --jq .tagName)
  gh release create "v${APP_VERSION}" --verify-tag --draft --generate-notes --notes-start-tag "${PREVIOUS_TAG}" --title "v${APP_VERSION}" \
    || echo "::warning::Draft release for v${APP_VERSION} was not created, create it manually"
}

bumpVersion() {
  echo "Bump version number"
  local APP_VERSION=$(getVersion | xargs)
  local SEMANTIC_REGEX='^([0-9]+)\.([0-9]+)(\.([0-9]+))?$'
  if [[ ${APP_VERSION} =~ ${SEMANTIC_REGEX} ]]; then
    if [[ ${BASH_REMATCH[4]} ]]; then
      nextVersion=$((BASH_REMATCH[4] + 1))
      nextVersion="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${nextVersion}-SNAPSHOT"
    else
      nextVersion=$((BASH_REMATCH[2] + 1))
      nextVersion="${BASH_REMATCH[1]}.${nextVersion}-SNAPSHOT"
    fi

    echo "Next version: ${nextVersion}"
    sed -i "0,/<version>.*<\/version>/s//<version>${nextVersion}<\/version>/" pom.xml

  else
    echo "No semantic version and therefore cannot publish to maven repository: '${APP_VERSION}'"
  fi
}

commitNextVersion() {
  git commit -a -m "Update version for release"
}

git config --global user.email "actions@github.com"
git config --global user.name "GitHub Actions"

requireSnapshot

echo "Deploying release to Maven Central"
removeSnapshots

mvn --batch-mode -Prelease deploy

commitRelease
publishTag
bumpVersion
commitNextVersion
