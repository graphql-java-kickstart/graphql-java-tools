#!/bin/bash
set -ev
echo "current git hash:"
git rev-parse --short HEAD

if [ "${TRAVIS_PULL_REQUEST}" = "false" ] && [ "${TRAVIS_BRANCH}" = "master" ] && [ "${RELEASE}" = "true" ]; then
    echo "Building on master"
    echo ${MAVEN_SETTINGS} >> $HOME/.m2/settings.xml
    mvn release:clean release:prepare release:perform -B -e -Pbintray
else
    echo "Building branch"
    mvn -B clean verify
fi
