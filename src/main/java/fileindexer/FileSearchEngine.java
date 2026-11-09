package fileindexer;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * In-memory search engine with two inverted indexes:
 *  - a filename index (token -> files), built by {@link #buildIndex(List)}
 *  - a file-content index for text files, built (optionally in parallel) by {@link #indexContents(int)}
 *
 * Filename queries are tokenized, matched by token prefix, AND-ed together and ranked.
 * Content queries are tokenized and AND-ed by exact token.
 */
public class FileSearchEngine {

    /** Only files with these extensions are read for content indexing. */
    public static final Set<String> TEXT_EXTENSIONS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "txt", "md", "log", "csv", "json", "xml", "yml", "yaml", "ini", "properties", "sql", "html", "css",
            "java", "py", "js", "ts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "kt", "rb", "php", "sh", "tex")));

    /** Files larger than this are skipped for content indexing. */
    public static final long MAX_CONTENT_BYTES = 1024L * 1024L;

    private static final int MIN_CONTENT_TOKEN = 2;
    private static final int MAX_CONTENT_TOKEN = 40;

    private List<FileRecord> docs = new ArrayList<FileRecord>();
    private final InvertedIndex nameIndex = new InvertedIndex();
    private final InvertedIndex contentIndex = new InvertedIndex();
    private final Map<String, List<FileRecord>> byExtension = new HashMap<String, List<FileRecord>>();
    private final Set<String> uniqueNames = new HashSet<String>();

    private int contentIndexedFiles;
    private int contentSkippedFiles;

    // ------------------------------------------------------------------ building

    public void buildIndex(List<FileRecord> records) {
        docs = new ArrayList<FileRecord>(records);
        nameIndex.clear();
        contentIndex.clear();
        byExtension.clear();
        uniqueNames.clear();
        contentIndexedFiles = 0;
        contentSkippedFiles = 0;

        for (int id = 0; id < docs.size(); id++) {
            FileRecord record = docs.get(id);
            nameIndex.add(id, Tokenizer.tokenize(record.getName()));
            uniqueNames.add(record.getName().toLowerCase(Locale.ROOT));

            List<FileRecord> sameExt = byExtension.get(record.getExtension());
            if (sameExt == null) {
                sameExt = new ArrayList<FileRecord>();
                byExtension.put(record.getExtension(), sameExt);
            }
            sameExt.add(record);
        }
    }

    public static boolean isIndexableText(FileRecord record) {
        return TEXT_EXTENSIONS.contains(record.getExtension())
                && record.getSizeBytes() <= MAX_CONTENT_BYTES;
    }

    /**
     * Reads every indexable text file and indexes the words inside it.
     * File reading + tokenizing runs on {@code threads} worker threads; the results are merged
     * into the (non-thread-safe) index on the calling thread, so no locking is needed.
     */
    public void indexContents(int threads) throws InterruptedException {
        contentIndex.clear();
        contentIndexedFiles = 0;
        contentSkippedFiles = 0;

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<Set<String>>> futures = new ArrayList<Future<Set<String>>>();
            List<Integer> ids = new ArrayList<Integer>();

            for (int id = 0; id < docs.size(); id++) {
                final FileRecord record = docs.get(id);
                if (!isIndexableText(record)) {
                    continue;
                }
                ids.add(id);
                futures.add(pool.submit(new Callable<Set<String>>() {
                    @Override
                    public Set<String> call() {
                        return extractTokens(record.getPath());
                    }
                }));
            }

            for (int i = 0; i < futures.size(); i++) {
                Set<String> tokens = null;
                try {
                    tokens = futures.get(i).get();
                } catch (ExecutionException e) {
                    // unreadable file: treated as skipped
                } catch (InterruptedException e) {
                    // Do not leave callers with an index that looks complete but is partial.
                    for (Future<Set<String>> future : futures) {
                        future.cancel(true);
                    }
                    contentIndex.clear();
                    contentIndexedFiles = 0;
                    contentSkippedFiles = 0;
                    throw e;
                }
                if (tokens == null) {
                    contentSkippedFiles++;
                } else {
                    contentIndex.add(ids.get(i), tokens);
                    contentIndexedFiles++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Returns distinct words in a text file, or null if it is unreadable, binary,
     * or grows beyond MAX_CONTENT_BYTES while being read. The limit is enforced
     * during the read, not only against the earlier FileRecord metadata.
     */
    private static Set<String> extractTokens(Path path) {
        try (InputStream input = Files.newInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_CONTENT_BYTES) {
                    return null;
                }
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == 0) {
                        return null; // NUL byte: treat as likely binary content
                    }
                }
                output.write(buffer, 0, count);
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            return new HashSet<String>(Tokenizer.tokenize(text, MIN_CONTENT_TOKEN, MAX_CONTENT_TOKEN));
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ searching

    /**
     * Filename search. Every query word must match the start of some word in the filename
     * (case-insensitive). Results are ranked: exact filename, then filename containing the whole
     * query, then more exact-word matches, then shorter names.
     */
    public List<FileRecord> searchByName(String query) {
        List<String> queryTokens = Tokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            return new ArrayList<FileRecord>();
        }

        final String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<Scored>();
        for (int id : nameIndex.searchAll(queryTokens, true)) {
            FileRecord record = docs.get(id);
            scored.add(new Scored(record, score(id, record, normalizedQuery, queryTokens)));
        }

        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                if (a.score != b.score) return Integer.compare(b.score, a.score);
                int byLength = Integer.compare(a.record.getName().length(), b.record.getName().length());
                if (byLength != 0) return byLength;
                return a.record.getPath().compareTo(b.record.getPath());
            }
        });

        List<FileRecord> results = new ArrayList<FileRecord>(scored.size());
        for (Scored s : scored) {
            results.add(s.record);
        }
        return results;
    }

    private int score(int id, FileRecord record, String normalizedQuery, List<String> queryTokens) {
        String lowerName = record.getName().toLowerCase(Locale.ROOT);
        int score = 0;
        if (lowerName.equals(normalizedQuery)) {
            score += 1000;
        } else if (lowerName.contains(normalizedQuery)) {
            score += 500;
        }
        // Bonus for query words that match a whole word in the name (not just a prefix).
        // Asking the index is O(1) per word, so ranking never re-tokenizes file names.
        for (String token : queryTokens) {
            if (nameIndex.exact(token).contains(id)) {
                score += 10;
            }
        }
        return score;
    }

    /** Files whose content contains every word of the query (exact word match). */
    public List<FileRecord> searchByContent(String query) {
        List<String> queryTokens = Tokenizer.tokenize(query, MIN_CONTENT_TOKEN, MAX_CONTENT_TOKEN);
        List<FileRecord> results = new ArrayList<FileRecord>();
        for (int id : contentIndex.searchAll(queryTokens, false)) {
            results.add(docs.get(id));
        }
        Collections.sort(results, new Comparator<FileRecord>() {
            @Override
            public int compare(FileRecord a, FileRecord b) {
                return a.getPath().compareTo(b.getPath());
            }
        });
        return results;
    }

    /** Average O(1) extension-map lookup plus O(k) to copy k matching records. */
    public List<FileRecord> searchByExtension(String extension) {
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        List<FileRecord> matches = byExtension.get(normalized);
        return matches == null ? new ArrayList<FileRecord>() : new ArrayList<FileRecord>(matches);
    }

    /**
     * Baseline used only for benchmarking: the original Level 1 approach,
     * a substring scan over every file name.
     */
    public List<FileRecord> searchByNameLinear(String query) {
        List<FileRecord> results = new ArrayList<FileRecord>();
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return results;
        }
        for (FileRecord record : docs) {
            if (record.getName().toLowerCase(Locale.ROOT).contains(normalized)) {
                results.add(record);
            }
        }
        return results;
    }

    /**
     * Returns the line with the greatest number of distinct query terms, or null
     * if the file cannot be read or none of the query terms appears in a line.
     * Content search itself still requires all query terms somewhere in the file;
     * a single line is not guaranteed to contain all of them.
     */
    public String findSnippet(FileRecord record, String query) {
        List<String> queryTokens = Tokenizer.tokenize(query, MIN_CONTENT_TOKEN, MAX_CONTENT_TOKEN);
        if (queryTokens.isEmpty()) {
            return null;
        }

        Set<String> querySet = new HashSet<String>(queryTokens);
        String bestLine = null;
        int bestMatches = 0;
        int bestLineNumber = -1;

        try (BufferedReader reader = Files.newBufferedReader(record.getPath(), StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Set<String> lineTokens = new HashSet<String>(
                        Tokenizer.tokenize(line, MIN_CONTENT_TOKEN, MAX_CONTENT_TOKEN));
                int matches = 0;
                for (String token : querySet) {
                    if (lineTokens.contains(token)) {
                        matches++;
                    }
                }
                if (matches > bestMatches) {
                    bestMatches = matches;
                    bestLine = line.trim();
                    bestLineNumber = lineNumber;
                }
            }
        } catch (IOException e) {
            // File may have changed or disappeared since indexing.
            return null;
        }

        if (bestLine == null) {
            return null;
        }
        if (bestLine.length() > 100) {
            bestLine = bestLine.substring(0, 100) + "...";
        }
        return "L" + bestLineNumber + ": " + bestLine;
    }

    // ------------------------------------------------------------------ stats

    public int getIndexedFileCount() { return docs.size(); }
    public int getUniqueFileNameCount() { return uniqueNames.size(); }
    public int getNameTermCount() { return nameIndex.termCount(); }
    public int getContentTermCount() { return contentIndex.termCount(); }
    public int getContentIndexedFileCount() { return contentIndexedFiles; }
    public int getContentSkippedFileCount() { return contentSkippedFiles; }

    private static final class Scored {
        final FileRecord record;
        final int score;

        Scored(FileRecord record, int score) {
            this.record = record;
            this.score = score;
        }
    }
}
