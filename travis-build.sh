#!/bin/bash
set -ev
echo "current git hash:"
git rev-parse --short HEAD

saveMavenSettings() {
    cat >$HOME/.m2/settings.xml <<EOL
<?xml version='1.0' encoding='UTF-8'?>
<settings xsi:schemaLocation='http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd'
          xmlns='http://maven.apache.org/SETTINGS/1.0.0' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>
    <servers>
        <server>
            <id>bintray</id>
            <username>${BINTRAY_USER}</username>
            <password>${BINTRAY_PASS}</password>
        </server>
        <server>
            <id>github</id>
            <username>${GITHUB_USER}</username>
            <password>${GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
EOL
}

mvn -B verify

if [ "${TRAVIS_PULL_REQUEST}" = "false" ] && [ "${TRAVIS_BRANCH}" = "master" ]; then
    saveMavenSettings
    git checkout -f ${TRAVIS_BRANCH}

    if [ "${RELEASE}" = "true" ]; then
        echo "Deploying release to Bintray"
        mvn release:clean release:prepare release:perform -B -e -Pbintray
    else
        echo "Deploy snapshot to Bintray"
        mvn deploy -B -Pbintray
    fi
fi
