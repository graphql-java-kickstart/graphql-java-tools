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

setReleaseVersion() {
  if [[ ! ${RELEASE_VERSION} =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Release version must look like 14.3.0: '${RELEASE_VERSION}'"
    exit 1
  fi
  sed -i "0,/<version>.*<\/version>/s//<version>${RELEASE_VERSION}<\/version>/" pom.xml
  local PREVIOUS_VERSION=${PREVIOUS_TAG#v}
  sed -i -e "s/graphql-java-tools:${PREVIOUS_VERSION}'/graphql-java-tools:${RELEASE_VERSION}'/" \
    -e "s/<version>${PREVIOUS_VERSION}<\/version>/<version>${RELEASE_VERSION}<\/version>/" README.md
  grep -q "<version>${RELEASE_VERSION}</version>" README.md || echo "::warning::README.md still shows another version, update it manually"
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
PREVIOUS_TAG=$(gh release view --json tagName --jq .tagName)

echo "Deploying release to Maven Central"
setReleaseVersion

mvn --batch-mode -Prelease deploy

commitRelease
publishTag
bumpVersion
commitNextVersion
