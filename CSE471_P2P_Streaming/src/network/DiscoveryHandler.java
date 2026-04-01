package network;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DiscoveryHandler {
    private final PeerDiscovery core;

    private final int initialTtl = 3;
    private final int peerExpireMs = 8000;
    private final int seenTtlMs = 15_000;

    private final Map<Peer, Long> knownPeers = new ConcurrentHashMap<>();
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public DiscoveryHandler(PeerDiscovery core) {
        this.core = core;
    }

    public void floodHello() {
    	String msgId = UUID.randomUUID().toString();
        String payload = "HELLO|" + msgId + "|" + core.localIp() + "|" + core.listenPort() + "|" + initialTtl;

        //Local subnet broadcast / directed broadcast
        for (int dp : PeerDiscovery.DISCOVERY_PORTS) {
            core.sendUdp(payload, core.broadcastAddr(), dp);

            //stabilize in the same pc testing 
            try {
                core.sendUdp(payload, java.net.InetAddress.getByName("127.0.0.1"), dp);
            } catch (Exception ignored) {}
        }

        // Overlay: known peer unicast forward 
        core.forwardToKnownPeers(payload, core.localIp(), core.listenPort());
    }

    public void handleHello(String[] parts, String senderIp, int senderPort) {
        if (parts.length < 5) return;

        String msgId = parts[1];
        String originIp = parts[2];
        int originPort, ttl;
        try {
            originPort = Integer.parseInt(parts[3]);
            ttl = Integer.parseInt(parts[4]);
        } catch (Exception e) { return; }

        String dupKey = "H:" + msgId;
        if (seen.putIfAbsent(dupKey, System.currentTimeMillis()) != null) return;

        if (originIp.equals(core.localIp()) && originPort == core.listenPort()) return;

        Peer p = new Peer(originIp, originPort);
        boolean isNew = (knownPeers.putIfAbsent(p, System.currentTimeMillis()) == null);
        if (!isNew) knownPeers.put(p, System.currentTimeMillis());

        if (isNew) {
        	System.out.println("NEW PEER DISCOVERED: " + p);
            for (PeerListener l : core.peersOut()) {
            	l.onPeerDiscovered(p);
            }
            
            // Late joiner, send CAT 
            core.catalog().sendCatalogToPeer(p);
        }

        if (ttl > 1) {
            int newTtl = ttl - 1;
            String forwarded = "HELLO|" + msgId + "|" + originIp + "|" + originPort + "|" + (ttl - 1);
            for (int dp : PeerDiscovery.DISCOVERY_PORTS) {
            	core.forwardToKnownPeers(forwarded, senderIp, senderPort);
            }
        }
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(
        		e -> now - e.getValue() > seenTtlMs);
        knownPeers.entrySet().removeIf(
        		e -> now - e.getValue() > peerExpireMs);
    }
}
