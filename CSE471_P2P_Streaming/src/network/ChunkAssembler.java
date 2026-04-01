package network;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal chunk assembler:
 * - Same videoHash, chunkIndex based assembling
 * - partTotal finished assemble the chunks
 * - Completed chunk number for process
 * - Process function
 */
public class ChunkAssembler {
	private final long ttlMs;
	
    // videoHash -> (chunkIndex -> chunkParts)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, ChunkParts>> map = new ConcurrentHashMap<>();
    
    public ChunkAssembler(long ttlMs) {
        this.ttlMs = ttlMs;
    }
    
    //Adding CHUN_RE_PART
    public boolean addPart(String hash, int chunkIndex, int partIndex, int partTotal, byte[] data) {
        if (hash == null || data == null) return false;
        ConcurrentHashMap<Integer, ChunkParts> byChunk =
                map.computeIfAbsent(hash, 
                		k -> new ConcurrentHashMap<>());

        ChunkParts parts = byChunk.computeIfAbsent(chunkIndex, 
        		k -> new ChunkParts(partTotal));
        
        return parts.add(partIndex, data);
    }
    
    // Completion check
    public boolean isChunkComplete(String hash, int chunkIndex) {
    	ConcurrentHashMap<Integer, ChunkParts> byChunk = map.get(hash);
        if (byChunk == null) return false;

        ChunkParts parts = byChunk.get(chunkIndex);
        
        return parts != null && parts.isComplete();
    }
    
    //Assemble completed chunks
    public byte[] assemble(String hash, int chunkIndex) {
    	ConcurrentHashMap<Integer, ChunkParts> byChunk = map.get(hash);
        if (byChunk == null) return null;

        ChunkParts parts = byChunk.remove(chunkIndex);
        if (parts == null) return null;

        return parts.merge();
    }
    
    //Process percentage calculation
    public int getProgressPercent(String hash, int totalChunks) {
    	if (totalChunks <= 0) return 0;

        ConcurrentHashMap<Integer, ChunkParts> byChunk = map.get(hash);
        if (byChunk == null) return 0;

        int touched = byChunk.size();
        int pct = (int) Math.round(100.0 * touched / totalChunks);
        
        return Math.max(0, Math.min(100, pct));
    }
    
    //Clean halfed old ones 
    public void cleanupOld() {
        long now = System.currentTimeMillis();
        
        for (Map.Entry<String, ConcurrentHashMap<Integer, ChunkParts>> e : map.entrySet()) {
        	ConcurrentHashMap<Integer, ChunkParts> byChunk = e.getValue();

            for (Map.Entry<Integer, ChunkParts> ce : byChunk.entrySet()) {
                if (now - ce.getValue().createdAt > ttlMs) {
                    byChunk.remove(ce.getKey());
                }
            }

            if (byChunk.isEmpty()) {
                map.remove(e.getKey());
            }
        }
    }
    
    // Single Chunk Parts
    private static class ChunkParts {
        final byte[][] parts;
        final boolean[] received;
        int receivedCount = 0;
        final long createdAt = System.currentTimeMillis();

        ChunkParts(int totalParts) {
            if (totalParts <= 0) totalParts = 1;
            this.parts = new byte[totalParts][];
            this.received = new boolean[totalParts];
        }

        boolean add(int idx, byte[] data) {
            if (idx < 0 || idx >= parts.length) return false;
            if (received[idx]) return false;
            
            parts[idx] = data;
            received[idx] = true;
            receivedCount++;
            return true;
        }

        boolean isComplete() {
            return receivedCount == parts.length;
        }

        byte[] merge() {
            int len = 0;
            for (byte[] p : parts) len += (p == null ? 0 : p.length);
            
            byte[] out = new byte[len];
            int off = 0;
            
            for (byte[] p : parts) {
                if (p == null) continue;
                System.arraycopy(p, 0, out, off, p.length);
                off += p.length;
            }
            return out;
        }
    }
}

