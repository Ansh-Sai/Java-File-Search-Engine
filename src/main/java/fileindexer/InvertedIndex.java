package fileindexer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Inverted index: token -> set of document ids containing that token.
 *
 * Backed by a TreeMap (sorted keys) so prefix queries ("rep" matching "report")
 * are a range lookup instead of a scan over every token.
 *
 * Not thread-safe: build it from a single thread (the engine merges worker results
 * on the calling thread).
 */
public class InvertedIndex {

    private final TreeMap<String, Set<Integer>> postings = new TreeMap<String, Set<Integer>>();

    public void add(int docId, Collection<String> tokens) {
        for (String token : tokens) {
            Set<Integer> docs = postings.get(token);
            if (docs == null) {
                docs = new HashSet<Integer>();
                postings.put(token, docs);
            }
            docs.add(docId);
        }
    }

    public void clear() {
        postings.clear();
    }

    public int termCount() {
        return postings.size();
    }

    /** Documents containing exactly this token. */
    public Set<Integer> exact(String token) {
        Set<Integer> docs = postings.get(token);
        return docs == null
                ? Collections.<Integer>emptySet()
                : Collections.unmodifiableSet(docs);
    }

    /** Documents containing any token that starts with the given prefix. */
    public Set<Integer> prefix(String prefix) {
        Set<Integer> union = new HashSet<Integer>();
        for (Set<Integer> docs : postings.subMap(prefix, true, prefix + Character.MAX_VALUE, true).values()) {
            union.addAll(docs);
        }
        return union;
    }

    /**
     * Documents matching ALL query tokens (set intersection).
     *
     * @param prefixMatch if true each query token matches by prefix, otherwise exactly
     */
    public Set<Integer> searchAll(List<String> queryTokens, boolean prefixMatch) {
        if (queryTokens.isEmpty()) {
            return Collections.emptySet();
        }

        List<Set<Integer>> candidates = new ArrayList<Set<Integer>>();
        for (String token : queryTokens) {
            Set<Integer> docs = prefixMatch ? prefix(token) : exact(token);
            if (docs.isEmpty()) {
                return Collections.emptySet();          // one missing term -> no result
            }
            candidates.add(docs);
        }

        // Intersect starting from the smallest set so the work stays small.
        Collections.sort(candidates, new Comparator<Set<Integer>>() {
            @Override
            public int compare(Set<Integer> a, Set<Integer> b) {
                return Integer.compare(a.size(), b.size());
            }
        });
        Set<Integer> result = new HashSet<Integer>(candidates.get(0));
        for (int i = 1; i < candidates.size() && !result.isEmpty(); i++) {
            result.retainAll(candidates.get(i));
        }
        return result;
    }
}
