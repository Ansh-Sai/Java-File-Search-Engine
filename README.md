# File Search & Indexing Engine

A Java command-line search engine that scans a directory tree and builds **inverted indexes** over
file names and file contents, so searches are index lookups instead of scans over every file.

## Features

- **Filename search**: tokenized, case-insensitive, multi-word (AND) search with prefix matching and relevance ranking.
  `FileSearchEngine.java` is found by `search engine`, `fileSearch`, or `engine.java`.
- **Content search**: searches the words *inside* text files (`.txt`, `.md`, `.java`, `.json`, `.log`, ...).
  Shows the first matching line for each result.
- **Multithreaded content indexing**: files are read and tokenized in parallel with an `ExecutorService`;
  results are merged on one thread, so the index needs no locking.
- **O(1) extension filter** via a hash map.
- Skips binary files (NUL-byte check), files over 1 MB, and ignored folders (`.git`, `node_modules`).
- JUnit 5 test suite, plus benchmark tools to measure the speed-ups.

## How it works

| Class | Responsibility |
|---|---|
| `FileIndexer` | Walks the directory tree (`Files.walkFileTree`), builds `FileRecord`s, skips ignored folders |
| `Tokenizer` | Lowercases and splits text on punctuation and camelCase (`HTMLParser` -> `html`, `parser`) |
| `InvertedIndex` | `TreeMap<token, Set<docId>>`; exact lookup, prefix lookup (sorted-map range query), AND-intersection starting from the smallest posting set |
| `FileSearchEngine` | Owns the name index + content index, ranking, parallel content indexing, snippets |
| `Main` | Interactive console menu |
| `Benchmark`, `ScaleBenchmark` | Performance measurements |

**Ranking (filename search):** exact filename > filename containing the whole query > more whole-word matches > shorter name.

**Why a `TreeMap`?** Keys are sorted, so "every token starting with `rep`" is a range query (`subMap`) rather than a scan of all tokens.

## Build and run

Requires JDK 11+.

```bash
# plain javac
mkdir out
javac -d out src/main/java/fileindexer/*.java
java -cp out fileindexer.Main /path/to/directory     # or omit the path to be prompted

# or Maven
mvn test
mvn package && java -jar target/file-search-engine-2.0.0.jar /path/to/directory
```

## Tests

```bash
mvn test
```

Covers the tokenizer, nested scanning and ignored folders, name search (case, prefixes, multi-word, camelCase, ranking),
extension lookup, content search (multi-word, binary / oversized / non-text files skipped), snippets, and a check that
single-threaded and multithreaded indexing produce identical results.

## Benchmarks

```bash
# real directory: filename search, 1 vs N thread content indexing, content search vs. naive scan
java -cp out fileindexer.Benchmark /path/to/big/folder [threads]

# synthetic corpus for filename search at scale (no real files needed)
java -Xmx2g -cp out fileindexer.ScaleBenchmark 300000
```

Results on my machine (fill in after running the commands above):

| Measurement | Result |
|---|---|
| Filename search, linear scan vs inverted index (N files) | _ ms/query vs _ ms/query (_x) |
| Content indexing, 1 thread vs N threads | _ ms vs _ ms |
| Content search, scan every file vs inverted index | _ ms vs _ ms |

Notes: with only a few thousand files a linear scan of names is already very fast, so the index pays off as the
file count grows (and for multi-word queries). The multithreaded speed-up depends on core count and disk speed.

## Limitations / future work

- The index lives in memory only; it is rebuilt on every run (next step: persist it and re-index only files whose `lastModified` changed).
- Filename matching is word-prefix based (`rep` matches `report`, but `port` does not); the old substring behaviour is kept only as a benchmark baseline.
- Content search is exact-word and unranked (next step: TF-IDF ranking).
- Not updated live when files change while running (next step: `WatchService`).


## Robustness notes (v2.1)

- Content reads enforce the 1 MiB limit while streaming, even if a file grows after the initial scan.
- Interrupted content indexing cancels outstanding tasks, clears partial content-index state, and reports interruption to the CLI.
- InvertedIndex.exact() exposes an unmodifiable posting set.
- File extension normalization uses Locale.ROOT.
- Snippets select the line containing the greatest number of distinct query terms.
