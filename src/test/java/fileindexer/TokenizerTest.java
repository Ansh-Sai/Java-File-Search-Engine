package fileindexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void splitsOnPunctuationAndLowercases() {
        assertEquals(Arrays.asList("my", "report", "2024", "final", "pdf"),
                Tokenizer.tokenize("My_Report-2024 (final).PDF"));
    }

    @Test
    void splitsCamelCase() {
        assertEquals(Arrays.asList("file", "search", "engine", "java"),
                Tokenizer.tokenize("FileSearchEngine.java"));
    }

    @Test
    void splitsAcronymsFromWords() {
        assertEquals(Arrays.asList("html", "parser"), Tokenizer.tokenize("HTMLParser"));
    }

    @Test
    void appliesLengthLimits() {
        assertEquals(Arrays.asList("abc"), Tokenizer.tokenize("a abc abcdefgh", 2, 4));
    }

    @Test
    void handlesNullAndEmpty() {
        assertTrue(Tokenizer.tokenize(null).isEmpty());
        assertTrue(Tokenizer.tokenize("").isEmpty());
        assertTrue(Tokenizer.tokenize("---").isEmpty());
    }
}
