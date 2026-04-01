package network;

import java.util.*;
import java.util.concurrent.*;
import java.net.*;

//setRootFolder + CAT
public class CatalogHandler {

    private final PeerDiscovery core;
    private final ExecutorService workerExec;

    private final int initialTtl = 3;
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Peer>> sourcesByHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalVideoInfo> localCatalog = new ConcurrentHashMap<>();

    public CatalogHandler(PeerDiscovery core, ExecutorService workerExec) {
        this.core = core;
        this.workerExec = workerExec;
    }

    public void setRootFolder(String folderPath) {
        workerExec.submit(() -> {
            try {
            	localCatalog.clear();
                List<VideoCatalog.LocalVideo> files = VideoCatalog.scanFolder(folderPath);

                for (VideoCatalog.LocalVideo lv : files) {
                    String name = lv.path.getFileName().toString();
                    long size = lv.path.toFile().length();
                    int chunks = VideoCatalog.totalChunks(size);
                    String hash = VideoCatalog.sha256Hex(lv.path);

                    localCatalog.put(hash, new LocalVideoInfo(name, hash, size, chunks, lv.path));
                    broadcast(name, hash, size, chunks, initialTtl);
                }
            } catch (Exception e) {
                System.out.println("Catalog error: " + e.getMessage());
            }
        });
    }

    private void broadcast(String name, String hash, long size, int chunks, int ttl) {
        String msgId = UUID.randomUUID().toString();
        String payload = "CAT|" + msgId + "|" + 
        		core.localIp() + "|" + core.listenPort() + "|" + ttl+ "|" + 
        		urlEncode(name) + "|" + hash + "|" + size + "|" + chunks;

        // Local subnet: broadcast / directed broadcast
        for (int dp : PeerDiscovery.DISCOVERY_PORTS) {
            core.sendUdp(payload, core.broadcastAddr(), dp);
        }

        // Multi-subnet: overlay unicast over router 
        core.forwardToKnownPeers(payload, core.localIp(), core.listenPort());
    }

    public void handleCatalog(String[] parts) {
        if (parts.length < 9) return;

        String msgId = parts[1];
        if (seen.putIfAbsent("C:" + msgId, System.currentTimeMillis()) != null) return;
        
        String ip = parts[2];
        int port = Integer.parseInt(parts[3]);
        String hash = parts[6];

        if (ip.equals(core.localIp()) && port == core.listenPort()) return;
        
        Peer src = new Peer(ip, port);
        sourcesByHash.computeIfAbsent(hash, k -> ConcurrentHashMap.newKeySet()).add(src);

        VideoMeta meta = new VideoMeta(
                urlDecode(parts[5]), hash,
                Long.parseLong(parts[7]),
                Integer.parseInt(parts[8]),
                src
        );

        for (VideoListener l : core.videosOut()) {
            l.onVideoDiscovered(meta);
        }
        
        System.out.println("RX CAT from " + parts[2] + ":" + parts[3]);

    }

    public LocalVideoInfo getLocal(String hash) {
        return localCatalog.get(hash);
    }

    public Collection<LocalVideoInfo> localValues() {
        return localCatalog.values();
    }


    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    public static class LocalVideoInfo {
        public final String name;
        public final String hash;
        public final long size;
        public final int chunks;
        public final java.nio.file.Path path;

        public LocalVideoInfo(String name, String hash, long size, int chunks, java.nio.file.Path path) {
            this.name = name;
            this.hash = hash;
            this.size = size;
            this.chunks = chunks;
            this.path = path;
        }
    }
    
    // New peer connected  
    // for every video in localCatalog send CAT message 
    public void sendCatalogToPeer(Peer target) {
        for (LocalVideoInfo v : localCatalog.values()) {
            String msgId = UUID.randomUUID().toString();
            String payload = "CAT|" + msgId + "|" +
                    core.localIp() + "|" + core.listenPort() + "|" + 1 + "|" +
                    urlEncode(v.name) + "|" + v.hash + "|" + v.size + "|" + v.chunks;

            // late-joiner send unicast 
            core.sendUnicast(payload, target.IP, target.Port);
        }
    }

}
