#!/usr/bin/env bash
# Unit-test shard filter generator: splits com.mcqapp test classes into N
# balanced groups by class-name range and prints `--tests` args for group $1.
# Usage: sh apk-build/test-shards.sh <index> <total>
set -eu
idx=$1
total=$2
classes=$(ls app/src/test/java/com/mcqapp/*Test.kt | sed 's|.*/||; s|\.kt$||' | sort)
count=$(echo "$classes" | wc -l)
per=$(( (count + total - 1) / total ))
start=$(( idx * per + 1 ))
echo "$classes" | sed -n "${start},$((start + per - 1))p" | sed 's/^/--tests com.mcqapp./'
