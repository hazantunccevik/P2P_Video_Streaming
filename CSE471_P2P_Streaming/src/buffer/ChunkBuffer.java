package buffer;

import network.VideoCatalog;

import java.nio.file.Path;
import java.util.BitSet;

import gui.MainWindow;

public class ChunkBuffer {
    private final String videoHash;
    private final int totalChunks;
    private final Path outFile;

    private final BitSet received;
    private int receivedCount = 0;

    public ChunkBuffer(String videoHash, int totalChunks, Path outFile) {
        this.videoHash = videoHash;
        this.totalChunks = totalChunks;
        this.outFile = outFile;
        this.received = new BitSet(totalChunks);
    }

    public synchronized boolean hasChunk(int idx) {
        return received.get(idx);
    }

    public synchronized void put(int idx, byte[] data) throws Exception {
        if (idx < 0 || idx >= totalChunks) return;
        if (received.get(idx)) return; // duplicate ignore

        VideoCatalog.writeChunk(outFile, idx, data);
        received.set(idx);
        receivedCount++;
    }

    public synchronized int getReceivedCount() { return receivedCount; }
    public int getTotalChunks() { return totalChunks; }
    public synchronized double progress() { return totalChunks == 0 ? 0 : (receivedCount * 1.0 / totalChunks); }
    public Path getOutFile() { return outFile; }
    
    //Which chunks are missing 
    public synchronized int nextMissingFrom(int startIdx) {
        if (startIdx < 0) startIdx = 0;
        int i = received.nextClearBit(startIdx);
        return (i >= totalChunks) ? -1 : i;
    }

    public synchronized int[] missingUpTo(int limit) {
        // limit: how many missing
        int[] tmp = new int[Math.max(0, limit)];
        int count = 0;
        int i = received.nextClearBit(0);
        while (i >= 0 && i < totalChunks && count < limit) {
            tmp[count++] = i;
            i = received.nextClearBit(i + 1);
        }
        if (count == tmp.length) return tmp;
        int[] out = new int[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }

    public synchronized int contiguousChunksFromStart() {
        int i = received.nextClearBit(0);
        return (i < 0 || i > totalChunks) ? totalChunks : i; 
    }

    public int getChunkBytes() {
        return VideoCatalog.CHUNK_BYTES; 
    }
    
    

}
