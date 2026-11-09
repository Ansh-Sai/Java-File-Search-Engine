package fileindexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSearchEngineTest {

    @TempDir
    Path root;

    private FileSearchEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        write("report.txt", "quarterly revenue grew");
        write("docs/Report_2024_final.txt", "Annual report. Revenue and profit.");
        write("docs/notes.md", "meeting notes about the Revenue forecast");
        write("src/FileSearchEngine.java", "public class FileSearchEngine { }");
        write("src/Main.java", "public static void main");
        write("big.log", new String(new char[(int) FileSearchEngine.MAX_CONTENT_BYTES + 10]).replace('\0', 'x') + " needle");
        write("image.png", "pngmarker");                       // extension not in the text whitelist
        Files.write(root.resolve("data.txt"), new byte[]{'h', 'i', 0, 'b', 'i', 'n', 'm', 'a', 'r', 'k'}); // NUL byte -> binary
        write(".git/config", "gitsecret");
        write("node_modules/lib/index.js", "modulesecret");

        engine = buildEngine(new FileIndexer(new HashSet<String>(Arrays.asList(".git", "node_modules"))));
    }

    private FileSearchEngine buildEngine(FileIndexer indexer) throws Exception {
        FileSearchEngine e = new FileSearchEngine();
        e.buildIndex(indexer.scan(root));
        e.indexContents(4);
        return e;
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> names(List<FileRecord> records) {
        List<String> out = new ArrayList<String>();
        for (FileRecord r : records) out.add(r.getName());
        return out;
    }

    // ---------------------------------------------------------------- scanning

    @Test
    void scanFindsNestedFilesAndSkipsIgnoredDirectories() {
        assertEquals(8, engine.getIndexedFileCount());           // 10 files minus .git and node_modules
        assertTrue(engine.searchByName("config").isEmpty());
        assertTrue(engine.searchByName("index").isEmpty());
    }

    @Test
    void scanWithoutIgnoresSeesEverything() throws Exception {
        FileSearchEngine all = buildEngine(new FileIndexer());
        assertEquals(10, all.getIndexedFileCount());
    }

    // ---------------------------------------------------------------- name search

    @Test
    void nameSearchIsCaseInsensitive() {
        assertEquals(names(engine.searchByName("REPORT")), names(engine.searchByName("report")));
        assertEquals(2, engine.searchByName("report").size());
    }

    @Test
    void nameSearchMatchesWordPrefixes() {
        assertEquals(2, engine.searchByName("rep").size());
    }

    @Test
    void nameSearchRequiresAllWords() {
        assertEquals(Arrays.asList("Report_2024_final.txt"), names(engine.searchByName("report 2024")));
    }

    @Test
    void nameSearchSplitsCamelCase() {
        assertEquals(Arrays.asList("FileSearchEngine.java"), names(engine.searchByName("search engine")));
    }

    @Test
    void exactFilenameRanksFirst() {
        assertEquals("report.txt", engine.searchByName("report.txt").get(0).getName());
        assertEquals("report.txt", engine.searchByName("report").get(0).getName()); // shorter name wins the tie
    }

    @Test
    void emptyOrUnknownQueryReturnsNothing() {
        assertTrue(engine.searchByName("").isEmpty());
        assertTrue(engine.searchByName("   ").isEmpty());
        assertTrue(engine.searchByName("zzzzz").isEmpty());
    }

    // ---------------------------------------------------------------- extension

    @Test
    void extensionLookupAcceptsLeadingDot() {
        assertEquals(2, engine.searchByExtension("java").size());
        assertEquals(2, engine.searchByExtension(".JAVA").size());
        assertTrue(engine.searchByExtension("xyz").isEmpty());
    }

    // ---------------------------------------------------------------- content search

    @Test
    void contentSearchFindsWordsInsideFiles() {
        List<String> found = names(engine.searchByContent("revenue"));
        assertEquals(3, found.size());
        assertTrue(found.containsAll(Arrays.asList("report.txt", "Report_2024_final.txt", "notes.md")));
    }

    @Test
    void contentSearchRequiresAllWords() {
        assertEquals(Arrays.asList("Report_2024_final.txt"), names(engine.searchByContent("revenue profit")));
    }

    @Test
    void contentSearchIsCaseInsensitive() {
        assertEquals(3, engine.searchByContent("REVENUE").size());
    }

    @Test
    void contentSearchSkipsBinaryOversizedAndNonTextFiles() {
        assertTrue(engine.searchByContent("binmark").isEmpty());   // binary (NUL byte)
        assertTrue(engine.searchByContent("needle").isEmpty());    // over the size cap
        assertTrue(engine.searchByContent("pngmarker").isEmpty()); // extension not whitelisted
        assertTrue(engine.searchByContent("gitsecret").isEmpty()); // ignored directory
    }

    @Test
    void snippetShowsMatchingLine() {
        FileRecord notes = engine.searchByName("notes").get(0);
        String snippet = engine.findSnippet(notes, "forecast");
        assertTrue(snippet.startsWith("L1:"));
        assertTrue(snippet.contains("forecast"));
    }

    @Test
    void multithreadedAndSingleThreadedIndexingAgree() throws InterruptedException {
        FileSearchEngine single = new FileSearchEngine();
        FileSearchEngine multi = new FileSearchEngine();
        try {
            FileIndexer indexer = new FileIndexer();
            single.buildIndex(indexer.scan(root));
            multi.buildIndex(indexer.scan(root));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        single.indexContents(1);
        multi.indexContents(8);

        assertEquals(single.getContentTermCount(), multi.getContentTermCount());
        assertEquals(single.getContentIndexedFileCount(), multi.getContentIndexedFileCount());
        assertEquals(names(single.searchByContent("revenue")), names(multi.searchByContent("revenue")));
    }

    @Test
    void contentIndexCountsSkippedFiles() {
        // data.txt is whitelisted by extension but binary -> skipped at read time
        assertTrue(engine.getContentSkippedFileCount() >= 1);
        assertFalse(engine.getContentIndexedFileCount() == 0);
    }
}
