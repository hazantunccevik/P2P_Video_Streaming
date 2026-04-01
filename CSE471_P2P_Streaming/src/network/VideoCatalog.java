package network;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class VideoCatalog {

    // 256KB chunk 
    public static final int CHUNK_BYTES = 256 * 1024;

    // video ext filter 
    private static final Set<String> EXT = Set.of(
            "mp4","avi","mkv","mov","mpeg","mpg","webm","wmv"
    );

    public static class LocalVideo {
        public final Path path;

        public LocalVideo(Path path) {
            this.path = path;
        }
    }

    /** Root folder altındaki video dosyalarını listeler */
    public static List<LocalVideo> scanFolder(String folderPath) throws IOException {
        Path root = Paths.get(folderPath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new FileNotFoundException("Folder not found: " + folderPath);
        }

        List<LocalVideo> out = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString().toLowerCase();
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    String ext = name.substring(dot + 1);
                    if (EXT.contains(ext)) out.add(new LocalVideo(p));
                }
            });
        }
        return out;
    }

    // total chunk size 
    public static int totalChunks(long sizeBytes) {
        if (sizeBytes <= 0) return 0;
        return (int) Math.ceil(sizeBytes / (double) CHUNK_BYTES);
    }


    public static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return bytesToHex(md.digest());
    }

    // chunk read (out-of-order can be done ) 
    public static byte[] readChunk(Path file, int chunkIndex) throws IOException {
        if (chunkIndex < 0) throw new IllegalArgumentException("chunkIndex < 0");

        long offset = (long) chunkIndex * CHUNK_BYTES;
        long fileSize = Files.size(file);
        if (offset >= fileSize) return new byte[0];

        int toRead = (int) Math.min(CHUNK_BYTES, fileSize - offset);
        byte[] out = new byte[toRead];

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(out);
        }
        return out;
    }
    
    public static void writeChunk(Path outFile, int chunkIndex, byte[] data) throws IOException {
        if (chunkIndex < 0) throw new IllegalArgumentException("chunkIndex < 0");
        if (data == null) throw new IllegalArgumentException("data is null");

        // If not create a file 
        Path parent = outFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        long offset = (long) chunkIndex * CHUNK_BYTES;

        // With RandomAccessFile write  offset'e (out-of-order helper)
        try (RandomAccessFile raf = new RandomAccessFile(outFile.toFile(), "rw")) {
            raf.seek(offset);
            raf.write(data);
        }
    }


    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
