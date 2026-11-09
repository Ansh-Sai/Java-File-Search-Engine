package fileindexer;

import java.nio.file.Path;
import java.util.Locale;
import java.nio.file.attribute.FileTime;

/**
 * Immutable metadata about one indexed file.
 */
public class FileRecord {
    private final String name;
    private final String extension;
    private final Path path;
    private final long sizeBytes;
    private final FileTime lastModified;

    public FileRecord(Path path, long sizeBytes, FileTime lastModified) {
        this.path = path;
        this.name = path.getFileName().toString();
        this.extension = extractExtension(name);
        this.sizeBytes = sizeBytes;
        this.lastModified = lastModified;
    }

    private static String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "(no extension)";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public String getName() { return name; }
    public String getExtension() { return extension; }
    public Path getPath() { return path; }
    public long getSizeBytes() { return sizeBytes; }
    public FileTime getLastModified() { return lastModified; }

    public String getFormattedSize() {
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024 * 1024) return String.format("%.2f KB", sizeBytes / 1024.0);
        if (sizeBytes < 1024L * 1024L * 1024L)
            return String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0));
    }
}
