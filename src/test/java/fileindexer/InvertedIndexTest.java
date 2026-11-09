package fileindexer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class InvertedIndexTest {
    @Test
    void exactPostingsCannotBeMutatedByCaller() {
        InvertedIndex index = new InvertedIndex();
        index.add(7, Arrays.asList("report"));

        assertThrows(UnsupportedOperationException.class, () -> index.exact("report").clear());
        assertTrue(index.exact("report").contains(7));
    }
}
