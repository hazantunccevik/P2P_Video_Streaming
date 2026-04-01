package network;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import network.ChunkAssembler;

/**
 * With UDP limited-scope flooding -> discovery + catalog + search.
 *
 * HELLO:
 *   HELLO|<msgId>|<originIp>|<originPort>|<ttl>
 *
 * CAT:
 *   CAT|<msgId>|<originIp>|<originPort>|<ttl>|<encName>|<hash>|<size>|<chunks>
 *
 * SEARCH:
 *   SEARCH|<reqId>|<originIp>|<originPort>|<ttl>|<encQuery>
 *
 * SEARCH_RES:
 *   SEARCH_RES|<reqId>|<resId>|<srcIp>|<srcPort>|<encName>|<hash>|<size>|<chunks>
 *   
 * CHUNK_REQ:
 * 	 CHUNK_REQ|reqId|videoHash|chunkIndex|originIp|originPort
 * 
 * CHUNK_RES:
 * 	 CHUNK_RES|reqId|videoHash|chunkIndex|totalChunks|dataB64|srcIp|srcPort
 */
public class PeerDiscovery {
	
	// 3 peer testing 
    public static final int[] DISCOVERY_PORTS = {7777, 8888, 9999}; 
    
    // Peers we know (for overlay unicast forwarding )
    private final ConcurrentHashMap<Peer, Long> knownPeers =
            new ConcurrentHashMap<>();

    // Configuration
    private final int listenPort;
    
    //Network
    private DatagramSocket socket;
    private final String localIp;
    private final InetAddress broadcastAddr;
    
    //State
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Executors
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService receiverExec = Executors.newSingleThreadExecutor();
    private final ExecutorService workerExec = Executors.newSingleThreadExecutor();

    // Handlers
    private final DiscoveryHandler discoveryHandler;
    private final CatalogHandler catalogHandler;
    private final SearchHandler searchHandler;
    private final ChunkTransferHandler chunkHandler;
    
    // listeners
    private final List<PeerListener> peerListeners = new CopyOnWriteArrayList<>();
    private final List<VideoListener> videoListeners = new CopyOnWriteArrayList<>();
    private final List<SearchListener> searchListeners = new CopyOnWriteArrayList<>();
    private final List<ChunkListener> chunkListeners = new CopyOnWriteArrayList<>();    
    
    
    public PeerDiscovery(int listenPort) {
        this.listenPort = listenPort;
        this.localIp = resolveLocalIp();
        this.broadcastAddr = resolveBroadcast();
        
        // init handlers
        this.discoveryHandler = new DiscoveryHandler(this);
        this.catalogHandler = new CatalogHandler(this, workerExec);
        this.searchHandler = new SearchHandler(this);
        this.chunkHandler = new ChunkTransferHandler(this);
        
        System.out.println("PeerDiscovery created. localIp=" + localIp + " port=" + listenPort + " broadcast=" + broadcastAddr);
    }
    
    // Lifecycle - Start
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        try {
        	socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(listenPort));
        } catch (SocketException e) {
            throw new RuntimeException("DatagramSocket cannot bind port " + listenPort, e);
        }

        receiverExec.submit(this::receiveLoop);
        
        //Periodic hello 
        scheduler.scheduleAtFixedRate(
                () -> discoveryHandler.floodHello(),
                200, 1500, TimeUnit.MILLISECONDS
        );

        // Cache cleanup
        scheduler.scheduleAtFixedRate(
                () -> {
                    discoveryHandler.cleanup();
                    searchHandler.cleanup();
                    chunkHandler.cleanup();
                },
                2, 2, TimeUnit.SECONDS
        );

        System.out.println("PeerDiscovery started on port " + listenPort);
    }
    
    // Lifecycle - Stop
    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        receiverExec.shutdownNow();
        workerExec.shutdownNow();
        if (socket != null) socket.close();
        
        System.out.println("PeerDiscovery stopped on port " + listenPort);
    }
    
    // Receiver loop
    private void receiveLoop() {
        byte[] buf = new byte[65507];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                socket.receive(pkt);
                String message = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                dispatch(message, pkt.getAddress().getHostAddress(), pkt.getPort());

                System.out.println("RX FROM " + pkt.getAddress().getHostAddress() + ":" + pkt.getPort() + " => " +
                        message.split("\\|")[0] + " (len=" + pkt.getLength() + ")");

                
            } catch (SocketException se) {
                break;
            } catch (IOException ignored) {}
        }
    }

    // Dispatcher
    private void dispatch(String msg, String senderIp, int senderPort) {
        String[] parts = msg.split("\\|");
        if (parts.length < 2) return;

        String type = parts[0];
        switch (type) {
            case "HELLO" -> discoveryHandler.handleHello(parts, senderIp, senderPort);
            case "CAT" -> catalogHandler.handleCatalog(parts);
            case "SEARCH" -> searchHandler.handleSearch(parts);
            case "SEARCH_RES" -> searchHandler.handleSearchRes(parts);
            case "CHUNK_REQ" -> chunkHandler.handleChunkReq(parts);
            case "CHUNK_RES_PART" -> chunkHandler.handleChunkResPart(parts);
        }
    }
    
    // Outward API for GUI
    public void setRootFolder(String folderPath) { catalogHandler.setRootFolder(folderPath); }
    public void search(String query) { searchHandler.search(query); }
    public void requestChunk(String hash, int chunkIndex, String ip, int port) { chunkHandler.requestChunk(hash, chunkIndex, ip, port); }

    
    //UDP send helpers
    public void sendUdp(String payload, InetAddress targetIp, int targetPort) {
        try {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, targetIp, targetPort));
        } catch (Exception ignored) {}
    }

    public void sendUnicast(String payload, String ip, int port) {
        try {
        	byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(
                data, data.length,
                InetAddress.getByName(ip), port
            );
            socket.send(p);
        } catch (Exception e) {
        	System.out.println("sendUnicast failed: " + e);
        }
    }
    
    public void addSeedPeer(String ip, int port) {
        Peer p = new Peer(ip, port);
        knownPeers.put(p, System.currentTimeMillis());
        System.out.println("Seed added: " + p);
    }
    
    public void onPeerSeen(String ip, int port) {
        Peer p = new Peer(ip, port);

        // if not me add
        if (p.IP.equals(localIp) && p.Port == listenPort) return;

        boolean isNew = (knownPeers.putIfAbsent(p, System.currentTimeMillis()) == null);
        if (isNew) {
            System.out.println("NEW PEER: " + p);

            // GUI reach out 
            for (PeerListener l : peerListeners) {
                l.onPeerDiscovered(p);
            }

            // late-joiner’a catalog push 
            catalogHandler.sendCatalogToPeer(p);
        } else {
            //existing peer last-seen update 
            knownPeers.put(p, System.currentTimeMillis());
        }
    }


    public void forwardToKnownPeers(String payload, String senderIp, int senderPort) {
        for (Peer peer : knownPeers.keySet()) {
            //to sender 
            if (peer.IP.equals(senderIp) && peer.Port == senderPort) continue;
            // to me 
            if (peer.IP.equals(localIp) && peer.Port == listenPort) continue;

            sendUnicast(payload, peer.IP, peer.Port);
        }
    }
    
    public static String pickLocalIpv4() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;

                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }


    // Listener registration 
    public void addPeerListener(PeerListener l) { peerListeners.add(l); }
    public void addVideoListener(VideoListener l) { videoListeners.add(l); }
    public void addSearchListener(SearchListener l) { searchListeners.add(l); }
    public void addChunkListener(ChunkListener l) { chunkListeners.add(l); }
    
    // Handlers call these to emit events
    public CatalogHandler catalog() { return catalogHandler; }
    public List<PeerListener> peersOut() { return peerListeners; }
    public List<VideoListener> videosOut() { return videoListeners; }
    public List<SearchListener> searchesOut() { return searchListeners; }
    public List<ChunkListener> chunksOut() { return chunkListeners; }

    // Accessors
    public String localIp() { return localIp; }
    public int listenPort() { return listenPort; }
    public InetAddress broadcastAddr() { return broadcastAddr; }
    
    // Utils
    private static InetAddress resolveBroadcast() {
        try { 
        	return InetAddress.getByName("255.255.255.255"); 
        }
        catch (Exception e) { 
        	throw new RuntimeException(e); 
        }
    } 

    private static String resolveLocalIp() {
        return pickLocalIpv4();
    }

    

}
