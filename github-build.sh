#!/bin/bash
set -ev

getVersion() {
  grep -m1 -o "<version>.*</version>$" pom.xml | awk -F'[><]' '{print $3}'
}

removeSnapshots() {
  sed -i 's/-SNAPSHOT//' pom.xml
}

commitRelease() {
  local APP_VERSION=$(getVersion)
  git commit -a -m "Update version for release"
  git tag -a "v${APP_VERSION}" -m "Tag release version"
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
      sed -i "'0,/<version>.*<\/version>/s//<version>${nextVersion}<\/version>/'" pom.xml

  else
    echo "No semantic version and therefore cannot publish to maven repository: '${APP_VERSION}'"
  fi
}

commitNextVersion() {
  git commit -a -m "Update version for release"
}

git config --global user.email "actions@github.com"
git config --global user.name "GitHub Actions"

echo "Deploying release to Bintray"
removeSnapshots

mvn --batch-mode -Pbintray deploy

commitRelease
bumpVersion
commitNextVersion
git push --follow-tags
