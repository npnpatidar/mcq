#!/usr/bin/env bash
# Unit-test shard filter generator: prints `--tests` args for group $1 of $2.
# Usage: sh apk-build/test-shards.sh <index> <total> [durations-file]
#
# With a durations file (one "<fqcn><TAB><millis>" line per class, produced by
# `gradlew :app:writeTestDurations` from a prior JUnit XML run) classes are
# partitioned by longest-processing-time-first: the heaviest classes are placed
# first, each into the currently lightest shard. That replaces the old class-name
# ranges, which put every slow guard/Robolectric class in one alphabetical corner
# and left a different shard idle. Without the file (no manifest cached from a
# nightly or main run yet) the original balanced-by-count range partitioning is
# kept; CI calls the two-argument form explicitly in that case, and local runs
# always do.
#
# An empty manifest is the same case: the probe stages `|| true`, so a failed
# measurement can leave a 0-byte file cached. `-s` (not `-f`) keeps that file on
# the balanced-by-count path — with no rows every class gets the same fallback
# weight, the greedy split puts all of them in shard 0, and the other shards run
# Gradle's whole suite unfiltered.
set -eu
idx=$1
total=$2
durations=${3:-}

# CI calls this from the repository root; a JVM test runs with the app module
# as its working directory. Accept both without changing either caller.
if [ ! -d app/src/test/java/com/mcqapp ] && [ -d ../app/src/test/java/com/mcqapp ]; then
  cd ..
fi

# Discover recursively: test classes may live in subpackages (e.g.
# com.mcqapp.util.NumericInputTest). Derive the FQCN from the path relative to
# the test source root — taking the basename would drop the package and produce
# a --tests filter Gradle cannot match.
test_root=app/src/test/java
classes=$(find "$test_root" -type f -name '*Test.kt' \
  | sed "s|^$test_root/||; s|\.kt$||; s|/|.|g" \
  | sort)

if [ -n "$durations" ] && [ -s "$durations" ]; then
  tmp=$(mktemp)
  classfile=$(mktemp)
  trap 'rm -f "$tmp" "$classfile"' EXIT
  printf '%s\n' "$classes" > "$classfile"
  # Default weight for classes with no prior measurement (new tests, or ones
  # that were skipped last run): the median of the known durations, or 1000 ms
  # when there is no data at all. A new class must not land in a shard based on
  # an assumed zero cost.
  default=$(awk -F '\t' 'NF==2 && $2+0>0 {print $2}' "$durations" | sort -n | awk '{a[NR]=$1} END {if (NR==0) print 1000; else print a[int((NR+1)/2)]}')
  awk -F '\t' -v def="$default" '
    NR==FNR {
      # The manifest keys are FQCNs (writeTestDurations reads the JUnit testsuite
      # name), which now match the discovered classes verbatim — including
      # subpackages. Do not strip a package prefix or subpackage classes lose
      # their measured weight.
      if (NF==2 && $1!="") { w[$1]=$2+0 }
      next
    }
    { print $1 "\t" (($1 in w) ? w[$1] : def) }
  ' "$durations" "$classfile" > "$tmp"

  # Longest-processing-time-first: heaviest first, so a 10-minute class cannot
  # be "discovered late" into a shard that already holds the other heavy ones.
  sort -t "$(printf '\t')" -k2,2nr "$tmp" | awk -F '\t' -v total="$total" -v idx="$idx" '
    { cls[NR]=$1; weight[NR]=$2; }
    END {
      for (b=0; b<total; b++) load[b]=0
      for (i=1; i<=NR; i++) {
        best=0
        for (b=1; b<total; b++) if (load[b] < load[best]) best=b
        bucket[i]=best
        load[best]+=weight[i]
      }
      for (i=1; i<=NR; i++) if (bucket[i]==idx) print cls[i]
    }
  ' | sort
else
  count=$(echo "$classes" | wc -l)
  per=$(( (count + total - 1) / total ))
  start=$(( idx * per + 1 ))
  echo "$classes" | sed -n "${start},$((start + per - 1))p"
fi | sed 's/^/--tests /'
