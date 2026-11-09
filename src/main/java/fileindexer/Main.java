package fileindexer;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;

/**
 * Console interface for the File Search & Indexing Engine.
 */
public class Main {

    private static final int MAX_DISPLAYED = 20;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        FileIndexer indexer = new FileIndexer(new HashSet<String>(Arrays.asList(".git", "node_modules")));
        FileSearchEngine engine = new FileSearchEngine();

        System.out.println("======================================");
        System.out.println("     FILE SEARCH & INDEXING ENGINE");
        System.out.println("======================================");

        String directoryInput;
        if (args.length > 0) {
            directoryInput = args[0];
        } else {
            System.out.print("Enter the directory path to index: ");
            directoryInput = scanner.nextLine().trim();
        }
        if (directoryInput.length() >= 2 && directoryInput.startsWith("\"") && directoryInput.endsWith("\"")) {
            directoryInput = directoryInput.substring(1, directoryInput.length() - 1);
        }

        try {
            Path directory = Paths.get(directoryInput);
            int threads = Runtime.getRuntime().availableProcessors();

            System.out.println("\nScanning directory. Please wait...");
            long t0 = System.nanoTime();
            List<FileRecord> records = indexer.scan(directory);
            engine.buildIndex(records);
            long t1 = System.nanoTime();

            System.out.println("Indexing file contents using " + threads + " threads...");
            engine.indexContents(threads);
            long t2 = System.nanoTime();

            System.out.println("\nIndexing completed.");
            System.out.println("Files indexed:        " + engine.getIndexedFileCount()
                    + "  (" + ms(t0, t1) + " ms)");
            System.out.println("Text files read:      " + engine.getContentIndexedFileCount()
                    + "  (" + ms(t1, t2) + " ms)");

            runMenu(scanner, engine);
        } catch (IOException | SecurityException e) {
            System.out.println("Could not index this directory: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Content indexing was interrupted; no partial content index was kept.");
        } catch (InvalidPathException e) {
            System.out.println("Invalid directory path: " + e.getMessage());
        } finally {
            scanner.close();
        }
    }

    private static void runMenu(Scanner scanner, FileSearchEngine engine) {
        while (true) {
            System.out.println("\n------------- MENU -------------");
            System.out.println("1. Search by filename");
            System.out.println("2. Search inside file contents");
            System.out.println("3. Filter by file extension");
            System.out.println("4. Show indexing statistics");
            System.out.println("5. Exit");
            System.out.print("Choose an option: ");

            if (!scanner.hasNextLine()) {
                return;
            }
            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1": {
                    System.out.print("Enter filename words (e.g. \"report 2024\"): ");
                    long start = System.nanoTime();
                    List<FileRecord> results = engine.searchByName(scanner.nextLine());
                    displayResults(results, null, null, start);
                    break;
                }
                case "2": {
                    System.out.print("Enter words to find inside files: ");
                    String query = scanner.nextLine();
                    long start = System.nanoTime();
                    List<FileRecord> results = engine.searchByContent(query);
                    displayResults(results, engine, query, start);
                    break;
                }
                case "3": {
                    System.out.print("Enter extension (for example: pdf or .java): ");
                    long start = System.nanoTime();
                    displayResults(engine.searchByExtension(scanner.nextLine()), null, null, start);
                    break;
                }
                case "4":
                    System.out.println("Total files indexed:     " + engine.getIndexedFileCount());
                    System.out.println("Unique filenames:        " + engine.getUniqueFileNameCount());
                    System.out.println("Filename terms:          " + engine.getNameTermCount());
                    System.out.println("Text files content-indexed: " + engine.getContentIndexedFileCount());
                    System.out.println("Text files skipped (binary/unreadable): " + engine.getContentSkippedFileCount());
                    System.out.println("Content terms:           " + engine.getContentTermCount());
                    break;
                case "5":
                    System.out.println("Exiting File Search & Indexing Engine.");
                    return;
                default:
                    System.out.println("Invalid option. Please choose 1-5.");
            }
        }
    }

    /** engine/query are only passed for content searches, to show a matching line per file. */
    private static void displayResults(List<FileRecord> results, FileSearchEngine engine,
                                       String query, long startNanos) {
        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        if (results.isEmpty()) {
            System.out.printf("No matching files found. (%.2f ms)%n", elapsedMs);
            return;
        }

        System.out.printf("%nFound %d file(s) in %.2f ms", results.size(), elapsedMs);
        if (results.size() > MAX_DISPLAYED) {
            System.out.print(" - showing the first " + MAX_DISPLAYED);
        }
        System.out.println(":");
        System.out.println("------------------------------------------------------------");

        for (int i = 0; i < Math.min(results.size(), MAX_DISPLAYED); i++) {
            FileRecord record = results.get(i);
            System.out.println("Name:      " + record.getName());
            System.out.println("Size:      " + record.getFormattedSize());
            System.out.println("Path:      " + record.getPath());
            System.out.println("Modified:  " + record.getLastModified());
            if (engine != null) {
                String snippet = engine.findSnippet(record, query);
                if (snippet != null) {
                    System.out.println("Match:     " + snippet);
                }
            }
            System.out.println("------------------------------------------------------------");
        }
    }

    private static long ms(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000;
    }
}
