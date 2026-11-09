package fileindexer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recursively scans a directory and creates {@link FileRecord} objects.
 * Directories whose names are in the ignore set (e.g. ".git") are skipped entirely.
 */
public class FileIndexer {

    private final Set<String> ignoredDirectoryNames;

    public FileIndexer() {
        this(Collections.<String>emptySet());
    }

    public FileIndexer(Set<String> ignoredDirectoryNames) {
        this.ignoredDirectoryNames = new HashSet<String>(ignoredDirectoryNames);
    }

    public List<FileRecord> scan(Path rootDirectory) throws IOException {
        if (!Files.exists(rootDirectory)) {
            throw new IOException("The directory does not exist.");
        }
        if (!Files.isDirectory(rootDirectory)) {
            throw new IOException("The supplied path is not a directory.");
        }

        final List<FileRecord> records = new ArrayList<FileRecord>();

        Files.walkFileTree(rootDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                if (!dir.equals(rootDirectory) && name != null
                        && ignoredDirectoryNames.contains(name.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    records.add(new FileRecord(file, attrs.size(), attrs.lastModifiedTime()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                System.err.println("Skipped (cannot access): " + file);
                return FileVisitResult.CONTINUE;
            }
        });

        return records;
    }
}
