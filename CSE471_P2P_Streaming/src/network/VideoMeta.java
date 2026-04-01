package network;

public class VideoMeta {
    public final String name;
    public final String hash;
    public final long size;
    public final int totalChunks;
    public final Peer source; // which peer broadcast

    public VideoMeta(String name, String hash, long size, int totalChunks, Peer source) {
        this.name = name;
        this.hash = hash;
        this.size = size;
        this.totalChunks = totalChunks;
        this.source = source;
    }

    @Override
    public String toString() {
        return name + " (" + totalChunks + " chunks) [" + hash.substring(0, Math.min(12, hash.length())) + "...]";
    }
}
