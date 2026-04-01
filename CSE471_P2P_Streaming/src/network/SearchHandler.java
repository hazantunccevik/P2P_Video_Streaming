package network;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


//SEARCH+ SEARCH_RES
public class SearchHandler {

    private final PeerDiscovery core;
    //reqId -> query
    private final ConcurrentHashMap<String, String> active = new ConcurrentHashMap<>();
    // duplicate cache: key -> timestamp
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

 // config
    private static final int TTL = 3;
    private static final int SEEN_TTL_MS = 15_000;     // duplicate cache
    private static final int ACTIVE_TTL_MS = 30_000;  
    
    public SearchHandler(PeerDiscovery core) {
        this.core = core;
    }

    public void search(String query) {
    	if (query == null || query.isBlank()) return;

    	String q = query.trim();
        String reqId = UUID.randomUUID().toString();
        active.put(reqId, q);

        String payload = "SEARCH|" + reqId + "|" +
                core.localIp() + "|" + core.listenPort() + "|" + 
        		TTL + "|" + urlEncode(query);

        for (int dp : PeerDiscovery.DISCOVERY_PORTS) {
            core.forwardToKnownPeers(payload, reqId, core.listenPort());
        }
    }

    // SEARCH|reqId|originIp|originPort|ttl|encQuery
    public void handleSearch(String[] parts) {
        if (parts.length < 6) return;

        String reqId = parts[1];
        // duplicate search request
        if (seen.putIfAbsent("S:" + reqId, System.currentTimeMillis()) != null) return;
        
        String ip = parts[2];
        int port;
        int ttl;
        String query;

        try {
            port = Integer.parseInt(parts[3]);
            ttl = Integer.parseInt(parts[4]);
            query = urlDecode(parts[5]).toLowerCase();
        } catch (Exception e) {
            return;
        }

        // self ignore
        if (ip.equals(core.localIp()) && port == core.listenPort()) return;

        // local match -> unicast SEARCH_RES
        for (CatalogHandler.LocalVideoInfo v : core.catalog().localValues()) {
            if (v.name.toLowerCase().contains(query)) {
                String res = "SEARCH_RES|" + reqId + "|" + UUID.randomUUID() + "|" +
                        core.localIp() + "|" + core.listenPort() + "|" +
                        urlEncode(v.name) + "|" + v.hash + "|" + v.size + "|" + v.chunks;
                core.forwardToKnownPeers(res, ip, port);
            }
        }

        // TTL forward 
        if (ttl > 1) {
            String fwd = "SEARCH|" + reqId + "|" + ip + "|" + port + "|" + (ttl - 1) + "|" + parts[5];
            for (int dp : PeerDiscovery.DISCOVERY_PORTS) {
                core.forwardToKnownPeers(fwd, ip, dp);
            }
        }

    }

 // SEARCH_RES|reqId|resId|srcIp|srcPort|encName|hash|size|chunks
    public void handleSearchRes(String[] parts) {
    	if (parts.length < 9) return;

        String reqId = parts[1];
        String query = active.get(reqId);
        if (query == null) return;

        long size;
        int chunks;
        int srcPort;

        try {
            srcPort = Integer.parseInt(parts[4]);
            size = Long.parseLong(parts[7]);
            chunks = Integer.parseInt(parts[8]);
        } catch (Exception e) {
            return;
        }

        VideoMeta meta = new VideoMeta(
                urlDecode(parts[5]),
                parts[6],
                size,
                chunks,
                new Peer(parts[3], srcPort)
        );

        for (SearchListener l : core.searchesOut()) {
            l.onSearchResult(query, meta);
        }
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
    
    public void cleanup() {
    	long now = System.currentTimeMillis();

        boolean removeIf = seen.entrySet().removeIf(e -> now - e.getValue() > SEEN_TTL_MS);
    }
}
