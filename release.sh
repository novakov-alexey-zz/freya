#!/bin/sh
RELEASE_VERSION_BUMP=true sbt -mem 2048 compile 'release with-defaults'
RELEASE_PUBLISH=true sbt 'release with-defaults'