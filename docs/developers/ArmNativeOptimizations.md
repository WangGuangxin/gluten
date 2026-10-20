<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# ARM native optimization experiments

These changes provide independently selectable paths for Vera experiments.
They do not claim an end-to-end speedup or change the benchmark resource plan.
Use the modified local Velox checkout when building Gluten. Rebuild both native
libraries together: the join result iterator's internal layout has changed.

## SVE gather and hash tails

Add these CMake options to the existing Velox native build:

```text
-DVELOX_ENABLE_ARM_SVE_GATHER=ON
-DVELOX_ENABLE_ARM_HASH_TAIL=ON
```

Both options default to `OFF`. Set the same `VELOX_ENABLE_ARM_SVE_GATHER`
option in Gluten's `cpp` CMake build so inline Velox headers are consistent.
Compile with the appropriate CPU target and vector length. On the current Vera
host, GCC 15 accepts `-mcpu=olympus -msve-vector-bits=128 -DSVE_BITS=128`.
An SVE vector-length option alone does not enable the SVE architecture feature.

The gather path handles 32/64-bit integer and floating-point bit patterns,
signed indices, and masked lanes. Masked lanes preserve their original value
and do not read inactive source addresses. Non-natural scales retain the
generic implementation. The hash path uses a zeroing predicated byte load
for short strings and residual bytes; the existing CRC composition and hash
outputs are unchanged. Builds without SVE retain their original paths.

## Spark runtime controls

| Spark property | Default | Meaning |
| --- | --- | --- |
| `spark.gluten.sql.columnar.backend.velox.hashShuffle.sveGather.enabled` | `true` | Enable the existing fixed-width hash shuffle SVE gather; `false` selects scalar copying. |
| `spark.gluten.sql.columnar.backend.velox.hashJoin.numParallelChains` | `8` | Number of interleaved duplicate-key join chains, from 1 to 32. |
| `spark.gluten.sql.columnar.backend.velox.hashJoin.aheadWindow` | `8` | Buffered hits per chain, from 1 to 32. |
| `spark.gluten.sql.columnar.backend.velox.hashJoin.prefetch.enabled` | `true` | Prefetch row data and next pointers during duplicate-key enumeration. |

The join properties are copied into each query's Velox `QueryConfig`, then
captured by its iterator. They do not change process-global flags. Defaults
preserve the previous 8-chain/8-hit prefetch policy with a separate constant
hot loop. They are not tuned
performance recommendations. Probe-row order, duplicate multiplicity, miss
handling, output row/byte limits, and cross-batch resume state are retained.

For isolated experiments, compare chains 4/8/16/32 while keeping the ahead
window fixed, then vary the window separately. Keep NUMA placement, resource
allocation, batch size, SQL, and dataset unchanged.

## Batched string materialization

The application-static property
`spark.gluten.sql.columnar.backend.velox.rowContainer.batchStringExtraction.enabled`
defaults to `false`. Set it before Spark starts; changing it with `SET` in a
running session is not supported. Gluten initializes the corresponding Velox
flag once during native backend initialization. Rebuild both native libraries
from the matching source trees before enabling it.

When enabled, RowContainer string extraction reserves output string storage
once per bounded block, instead of once per long string. A block contains at
most 128 rows and stops gathering after reaching 64 KiB of payload; a single
large string may exceed this byte target. Batches below 32 rows retain the
original path. Inline strings, null rows, selected row numbers, VARCHAR and
VARBINARY, output offsets, and fragmented source strings are supported.

The output owns its copied bytes and remains valid after the source container
is cleared or destroyed. This path does not borrow source memory. It is shared
by joins and aggregations and does not require an ARM-specific instruction set.
Compare `false` and `true` in the same rebuilt JAR before drawing conclusions
about query or full-suite performance.

## Adaptive Value-ID analysis during hash build

These query-level settings control append-only hash join builds:

| Spark property suffix under `spark.gluten.sql.columnar.backend.velox.hashJoin.` | Default | Meaning |
| --- | --- | --- |
| `adaptiveValueIds.enabled` | `false` | Select encoding-aware build analysis and stop high-cardinality dictionaries early. |
| `adaptiveValueIds.minRows` | `32768` | Minimum selected logical rows observed by a builder before considering abandonment. Must be positive. |
| `adaptiveValueIds.minDistinctPct` | `80` | Minimum distinct/observed-row percentage for abandonment, from 1 to 100. |

The analysis reuses adjacent equal keys and selected dictionary entries, so
repeated values do not require repeated F14 insert/lookups. Nulls and repeated
dictionary references still count as logical rows for the sampling denominator.
Flat strings without local repetition retain the existing analysis loop and
reuse its temporary ID storage; this avoids adding overhead to unordered,
low-cardinality strings.
Low-cardinality builds retain their exact dictionaries. High distinct ratios
permit discarding the dictionary while retaining exact numeric bounds; dense
integer batches then use a bounds reduction. Wide domains fall back to generic
hashing when neither range nor distinct encoding remains viable. Deduplicating
and counting builds, grouping, and probe-side equality retain their existing
paths. Sub-millisecond timestamps cannot use millisecond Value-IDs.

This is a prefix-based heuristic. A distinct-heavy prefix followed by repeated
keys can abandon an otherwise useful dictionary, so compare both settings on
the intended workload. Losing an exact dictionary can also change dynamic
filter selectivity; the remaining filter must never reject a matching key.
Rebuild both native libraries from matching source trees.

Native HashBuild runtime statistics report `valueIdAnalysisRows`,
`valueIdDictionaryLookups`, `valueIdLegacyAnalysisRows`, `valueIdObservedDistincts`,
`valueIdDictionaryGrowthBatches`, `valueIdAbandonedDictionaries`,
`valueIdRangeOnlyBatches`, and `valueIdFinalHashMode`. Distinct observations are
per-column batch-boundary samples; use their maximum rather than their sum as
an NDV observation. Growth counts describe capacity increases observed between
batch boundaries, rather than an exact F14 rehash count. Hash modes use the
native `BaseHashTable::HashMode` enum. These statistics remain local to each
builder and do not synchronize F14 dictionaries across worker threads.
`valueIdDictionaryLookups` counts calls on the new analysis paths; the retained
legacy string loop reports its logical row count separately and does not claim
an exact lookup count. Zero-activity counters may be absent.

## PGO and LTO

Velox's `CMake/VeloxPGO.cmake` supplies an opt-in GCC/Clang profiling helper.
Velox accepts `VELOX_PGO_MODE` and `VELOX_PGO_PROFILE`; Gluten's native CMake
build accepts `GLUTEN_PGO_MODE` and `GLUTEN_PGO_PROFILE`. Modes are `OFF`
(default), `GENERATE`, and `USE`.

For GCC, give each project its own absolute profile directory. Configure with
`GENERATE`, build and run representative training queries, then reconfigure
the **same build directory** with `USE` and rebuild. Keep compiler, CPU flags,
source, and object paths stable. Generation uses atomic counter updates for
multi-threaded training. Missing profile data is not silently disabled.

For Clang, `GENERATE` takes an output directory for `%m-%p.profraw` files.
Merge them with the matching `llvm-profdata merge`, then give `USE` the merged
`.profdata` file. PGO builds require the corresponding compiler runtime.

LTO already has separate options: `VELOX_ENABLE_LTO=ON` for Velox and
`ENABLE_LTO=ON` for Gluten's native build. Enable it independently when
comparing PGO, and retain the existing compiler support checks.

## Validation

`FixedWidthGatherTest` checks scalar/SVE equivalence and exact-sized buffers.
Velox `ArmNativeTest` checks software-CRC hash compatibility, non-aligned
inputs, signed gather indices, floating-point bit preservation, masked invalid
indices, non-natural gather scales, and guard pages. The HashTable join test
exercises long duplicate chains, misses, output capacity one, byte limits,
and resume behavior with several chain/window/prefetch configurations.

Run these tests with both compile-time options enabled and disabled. Sanitizer
checks should include the modified production sources. An ISA microbenchmark
result does not substitute for repeated unprofiled query and full-suite runs.
