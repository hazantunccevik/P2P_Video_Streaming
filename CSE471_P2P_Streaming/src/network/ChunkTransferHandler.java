package network;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


//CHUNK_REQ + RES_PART
public class ChunkTransferHandler {

	private final PeerDiscovery core;
    private final ChunkAssembler chunkAssembler = new ChunkAssembler(30_000);
    private static final int PART_BYTES = 900;

    public ChunkTransferHandler(PeerDiscovery core) {
        this.core = core;
    }

    public void requestChunk(String hash, int index, String ip, int port) {
    	String payload = "CHUNK_REQ|" + UUID.randomUUID() + "|" +
                hash + "|" + index + "|" + core.localIp() + "|" + core.listenPort();
        core.sendUnicast(payload, ip, port);
    }

    public void handleChunkReq(String[] parts) {
    	if (parts.length < 6) return;
    	
    	System.out.println("HANDLE CHUNK_REQ ENTERED from origin=" + parts[4] + ":" + parts[5] +
                " hash=" + parts[2] + " idx=" + parts[3]);

        String hash = parts[2];
        int idx;
        String ip = parts[4];
        int port;

        try {
            idx = Integer.parseInt(parts[3]);
            port = Integer.parseInt(parts[5]);
        } catch (Exception e) {
            System.out.println("Bad CHUNK_REQ numbers: " + e);
            return;
        }
        
        CatalogHandler.LocalVideoInfo v = core.catalog().getLocal(hash);
        System.out.println("getLocal(hash) => " + (v == null ? "NULL" : v.path.toString()));
        if (v == null) return;

        try {
            byte[] chunk = VideoCatalog.readChunk(v.path, idx);
            if (chunk.length == 0) {
                System.out.println("CHUNK empty! idx=" + idx + " file=" + v.path);
                return;
            }
            int total = (int)Math.ceil(chunk.length / (double)PART_BYTES);
            if (total <= 0) total = 1;

            for (int i = 0; i < total; i++) {
                int s = i * PART_BYTES;
                int e = Math.min(s + PART_BYTES, chunk.length);
                byte[] part = Arrays.copyOfRange(chunk, s, e);

                String msg = "CHUNK_RES_PART|" + UUID.randomUUID() + "|" +
                        hash + "|" + idx + "|" + v.chunks + "|" +
                        i + "|" + total + "|" +
                        Base64.getEncoder().encodeToString(part) + "|" +
                        core.localIp() + "|" + core.listenPort();
                
                System.out.println("TX CHUNK_RES_PART to " + ip + ":" + port +
                        " bytes=" + msg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

                core.sendUnicast(msg, ip, port);
                
            }
        } catch (Exception ex) {
            System.out.println("Chunk send failed: " + ex);
        }
    }

    //CHUNK_RES_PART|reqId|hash|chunkIndex|totalChunks|partIndex|partTotal|b64|srcIp|srcPort
    public void handleChunkResPart(String[] parts) {
        if (parts.length < 10) return;

        String hash = parts[2];
        int idx, totalChunks, partIdx, partTotal, srcPort;
        
        try {
            idx = Integer.parseInt(parts[3]);
            totalChunks = Integer.parseInt(parts[4]);
            partIdx = Integer.parseInt(parts[5]);
            partTotal = Integer.parseInt(parts[6]);
            srcPort = Integer.parseInt(parts[9]);
        } catch (Exception e) {
            System.out.println("Bad CHUNK_RES_PART numbers: " + e);
            return;
        }
        
        byte[] data = Base64.getDecoder().decode(parts[7]);
        try {
            data = Base64.getDecoder().decode(parts[7]);
        } catch (IllegalArgumentException ex) {
            System.out.println("BAD BASE64 in CHUNK_RES_PART: " + ex);
            return;
        }

        boolean ok = chunkAssembler.addPart(hash, idx, partIdx, partTotal, data);
        if (ok && chunkAssembler.isChunkComplete(hash, idx)) {
            byte[] full = chunkAssembler.assemble(hash, idx);
            Peer src = new Peer(parts[8],srcPort);
            
            System.out.println("CHUNK COMPLETE hash=" + hash + 
            		" idx=" + idx + " bytes=" + full.length);

            for (ChunkListener l : core.chunksOut()) {
                l.onChunkReceived(hash, idx, Integer.parseInt(parts[4]), full, src);
            }
        }
    }

    public void cleanup() {
        chunkAssembler.cleanupOld();
    }
}
