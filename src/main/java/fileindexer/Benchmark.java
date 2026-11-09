package fileindexer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Measures how much the indexes help.
 *
 * Usage: java -cp out fileindexer.Benchmark <directory> [threads]
 *
 * 1. Filename search: Level 1 substring scan vs. inverted index lookup.
 * 2. Content indexing: 1 thread vs N threads.
 * 3. Content search: naive "open every file" scan vs. inverted index lookup.
 */
public class Benchmark {

    private static final int NAME_QUERIES = 500;
    private static final int CONTENT_QUERIES = 5;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java -cp out fileindexer.Benchmark <directory> [threads]");
            return;
        }
        Path root = Paths.get(args[0]);
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        FileIndexer indexer = new FileIndexer();
        FileSearchEngine engine = new FileSearchEngine();

        long t = System.nanoTime();
        List<FileRecord> records = indexer.scan(root);
        double scanMs = elapsedMs(t);

        t = System.nanoTime();
        engine.buildIndex(records);
        double buildMs = elapsedMs(t);

        System.out.println("Directory:       " + root);
        System.out.println("Files:           " + records.size());
        System.out.println("Threads:         " + threads);
        System.out.printf("Scan time:       %.1f ms%n", scanMs);
        System.out.printf("Name index time: %.1f ms%n%n", buildMs);

        // ---- 1. filename search -------------------------------------------------
        List<String> queries = sampleNameQueries(records, NAME_QUERIES);
        for (String q : queries) {            // warm-up so the JIT has compiled both paths
            engine.searchByNameLinear(q);
            engine.searchByName(q);
        }
        long linearNs = timeQueries(engine, queries, true);
        long indexedNs = timeQueries(engine, queries, false);
        System.out.println("== Filename search (" + queries.size() + " queries) ==");
        System.out.printf("Linear scan:    %.2f ms total, %.4f ms/query%n", linearNs / 1e6, linearNs / 1e6 / queries.size());
        System.out.printf("Inverted index: %.2f ms total, %.4f ms/query%n", indexedNs / 1e6, indexedNs / 1e6 / queries.size());
        System.out.printf("Speed-up:       %.1fx%n%n", (double) linearNs / Math.max(1, indexedNs));

        // ---- 2. content indexing, 1 thread vs N threads -------------------------
        engine.indexContents(1);              // warm-up: fills the OS file cache, so both runs are fair
        t = System.nanoTime();
        engine.indexContents(1);
        double singleMs = elapsedMs(t);
        t = System.nanoTime();
        engine.indexContents(threads);
        double multiMs = elapsedMs(t);
        System.out.println("== Content indexing (" + engine.getContentIndexedFileCount() + " text files) ==");
        System.out.printf("1 thread:       %.1f ms%n", singleMs);
        System.out.printf("%d threads:      %.1f ms%n", threads, multiMs);
        System.out.printf("Speed-up:       %.1fx%n%n", singleMs / Math.max(1.0, multiMs));

        // ---- 3. content search: naive grep-style scan vs index ------------------
        List<String> words = sampleContentWords(engine, records, CONTENT_QUERIES);
        long naiveNs = 0;
        long idxNs = 0;
        for (String w : words) {
            t = System.nanoTime();
            engine.searchByContent(w);
            idxNs += System.nanoTime() - t;

            t = System.nanoTime();
            naiveContentScan(records, w);
            naiveNs += System.nanoTime() - t;
        }
        System.out.println("== Content search (" + words.size() + " queries) ==");
        System.out.printf("Naive scan (open every file): %.2f ms total%n", naiveNs / 1e6);
        System.out.printf("Inverted index:               %.4f ms total%n", idxNs / 1e6);
        System.out.printf("Speed-up:                     %.0fx%n", (double) naiveNs / Math.max(1, idxNs));
    }

    private static long timeQueries(FileSearchEngine engine, List<String> queries, boolean linear) {
        long start = System.nanoTime();
        long sink = 0;                         // keep the JIT from discarding the calls
        for (String q : queries) {
            sink += (linear ? engine.searchByNameLinear(q) : engine.searchByName(q)).size();
        }
        if (sink < 0) System.out.println(sink);
        return System.nanoTime() - start;
    }

    /** Picks real words from real file names so every query is realistic. */
    private static List<String> sampleNameQueries(List<FileRecord> records, int n) {
        Random rnd = new Random(42);
        List<String> out = new ArrayList<String>();
        if (records.isEmpty()) return out;
        for (int i = 0; i < n; i++) {
            List<String> tokens = Tokenizer.tokenize(records.get(rnd.nextInt(records.size())).getName());
            if (!tokens.isEmpty()) out.add(tokens.get(rnd.nextInt(tokens.size())));
        }
        return out;
    }

    /** Picks words that exist in some indexed text file (uses a few real files as the source). */
    private static List<String> sampleContentWords(FileSearchEngine engine, List<FileRecord> records, int n) {
        Random rnd = new Random(7);
        List<String> out = new ArrayList<String>();
        List<FileRecord> texts = new ArrayList<FileRecord>();
        for (FileRecord r : records) if (FileSearchEngine.isIndexableText(r)) texts.add(r);
        for (int tries = 0; tries < 200 && out.size() < n && !texts.isEmpty(); tries++) {
            FileRecord r = texts.get(rnd.nextInt(texts.size()));
            try {
                List<String> toks = Tokenizer.tokenize(
                        new String(java.nio.file.Files.readAllBytes(r.getPath()), java.nio.charset.StandardCharsets.UTF_8), 4, 20);
                if (!toks.isEmpty()) out.add(toks.get(rnd.nextInt(toks.size())));
            } catch (java.io.IOException ignored) { }
        }
        return out;
    }

    /** What you'd do without an index: open and tokenize every text file for every query. */
    private static int naiveContentScan(List<FileRecord> records, String word) {
        int hits = 0;
        for (FileRecord r : records) {
            if (!FileSearchEngine.isIndexableText(r)) continue;
            try {
                byte[] data = java.nio.file.Files.readAllBytes(r.getPath());
                Set<String> toks = new HashSet<String>(Tokenizer.tokenize(
                        new String(data, java.nio.charset.StandardCharsets.UTF_8), 2, 40));
                if (toks.contains(word)) hits++;
            } catch (java.io.IOException ignored) { }
        }
        return hits;
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }
}
