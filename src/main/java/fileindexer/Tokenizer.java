package fileindexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns text into lowercase search tokens.
 *
 * Splits on any non-letter/digit character and on camelCase boundaries, so
 * "FileSearchEngine.java" becomes [file, search, engine, java] and
 * "HTMLParser" becomes [html, parser].
 */
public final class Tokenizer {

    // Compiled once: String.replaceAll/split would recompile these on every call.
    private static final Pattern ACRONYM_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private Tokenizer() { }

    public static List<String> tokenize(String text) {
        return tokenize(text, 1, Integer.MAX_VALUE);
    }

    /**
     * @param minLength tokens shorter than this are dropped
     * @param maxLength tokens longer than this are dropped (filters hashes, base64 blobs, etc.)
     */
    public static List<String> tokenize(String text, int minLength, int maxLength) {
        List<String> tokens = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }

        String spaced = ACRONYM_BOUNDARY.matcher(text).replaceAll("$1 $2");   // HTMLParser -> HTML Parser
        spaced = CAMEL_BOUNDARY.matcher(spaced).replaceAll("$1 $2");          // fileName   -> file Name

        for (String part : SEPARATORS.split(spaced.toLowerCase(Locale.ROOT))) {
            if (part.length() >= minLength && part.length() <= maxLength) {
                tokens.add(part);
            }
        }
        return tokens;
    }
}
