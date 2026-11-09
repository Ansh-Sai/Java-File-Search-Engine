package fileindexer;

import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Filename-search benchmark on a synthetic corpus, so the index can be tested at sizes
 * (100k - 1M files) you may not have on disk. No real files are created or read.
 *
 * Usage: java -Xmx2g -cp out fileindexer.ScaleBenchmark [fileCount]   (default 300000)
 */
public class ScaleBenchmark {

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 300_000;
        Random rnd = new Random(1);

        String[] vocab = new String[8000];
        for (int i = 0; i < vocab.length; i++) {
            vocab[i] = ("zq" + Long.toString(rnd.nextLong() & Long.MAX_VALUE, 36)).substring(0, 4 + rnd.nextInt(4));
        }
        String[] exts = {"txt", "java", "pdf", "png", "md", "json", "log", "py"};

        List<FileRecord> records = new ArrayList<FileRecord>(n);
        for (int i = 0; i < n; i++) {
            // skewed word choice (gaussian) so some words are common and some rare, like real names
            String first = vocab[(int) Math.abs(rnd.nextGaussian() * 1500) % vocab.length];
            String name = first + "_" + vocab[rnd.nextInt(vocab.length)]
                    + (rnd.nextBoolean() ? "-" + vocab[rnd.nextInt(vocab.length)] : "")
                    + "." + exts[rnd.nextInt(exts.length)];
            records.add(new FileRecord(Paths.get("/dir" + (i % 500), name), 100, FileTime.fromMillis(0)));
        }

        FileSearchEngine engine = new FileSearchEngine();
        long t = System.nanoTime();
        engine.buildIndex(records);
        System.out.printf("Files: %d, name index built in %.0f ms%n%n", n, (System.nanoTime() - t) / 1e6);

        run(engine, records, rnd, false);
        run(engine, records, rnd, true);
    }

    private static void run(FileSearchEngine engine, List<FileRecord> records, Random rnd, boolean twoWords) {
        List<String> queries = new ArrayList<String>();
        for (int i = 0; i < 300; i++) {
            List<String> tokens = Tokenizer.tokenize(records.get(rnd.nextInt(records.size())).getName());
            queries.add(twoWords ? tokens.get(0) + " " + tokens.get(1) : tokens.get(0));
        }
        for (String q : queries) {                 // warm-up
            engine.searchByName(q);
            engine.searchByNameLinear(q);
        }

        long sink = 0;
        long t = System.nanoTime();
        for (String q : queries) sink += engine.searchByNameLinear(q).size();
        long linear = System.nanoTime() - t;
        t = System.nanoTime();
        for (String q : queries) sink += engine.searchByName(q).size();
        long indexed = System.nanoTime() - t;
        if (sink < 0) System.out.println(sink);

        System.out.printf("%-11s linear scan %.3f ms/query | inverted index %.3f ms/query | %.1fx faster%n",
                twoWords ? "two-word:" : "one-word:",
                linear / 1e6 / queries.size(), indexed / 1e6 / queries.size(), (double) linear / indexed);
    }
}
