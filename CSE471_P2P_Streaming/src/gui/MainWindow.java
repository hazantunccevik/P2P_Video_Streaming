package gui;

import network.ChunkListener;
import network.Peer;
import network.PeerDiscovery;
import network.PeerListener;
import network.SearchListener;
import network.VideoCatalog;
import network.VideoListener;
import network.VideoMeta;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

import buffer.ChunkBuffer;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;


public class MainWindow extends JFrame implements PeerListener, VideoListener, SearchListener, ChunkListener {

    private final PeerDiscovery discovery;
    private final int port;
    
    // Search debounce + mode 
    private javax.swing.Timer searchDoneTimer;
    private int searchHitCount = 0;
    private String lastQuery = "";
    private boolean searchMode = false;   // Do not mix with catalog 

    //Download State
    private int rrIndex = 0;
    private buffer.ChunkBuffer currentBuffer;
    private String currentHash;
    private int currentTotalChunks;
    
    // By UI set: default 
    private java.nio.file.Path bufferDirPath = java.nio.file.Paths.get("buffer"); 

    // UI: Top status Labels
    private final JLabel statusValue = new JLabel("DISCONNECTED");
    private final JLabel rootFolderValue = new JLabel("-");
    private final JLabel bufferFolderValue = new JLabel("-");

    //UI: Left side (search + available videos) 
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<String> availableVideosModel = new DefaultListModel<>();
    private final JList<String> availableVideosList = new JList<>(availableVideosModel);
    
    // Video indexing (hash based)
    private final Map<String, Integer> hashToIndex = new HashMap<>();
    private final Map<String, Integer> hashToSources = new HashMap<>();
    private final Map<String, String> hashToName = new HashMap<>();
    private final Map<String, Integer> hashToChunks = new HashMap<>();

    // Resources (hash -> peers)
    private final Map<String, Set<Peer>> hashToPeers = new HashMap<>();
    
    //UI: Right side (active streams table + video preview) 
    private final DefaultTableModel streamsTableModel = new DefaultTableModel(
            new Object[]{"Video", "Source Peer", "Progress %", "Status"}, 0
    ) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable activeStreamsTable = new JTable(streamsTableModel);
    private final JPanel videoPreviewPanel = new JPanel(new BorderLayout());

    // Bottom log 
    private final JTextArea eventLog = new JTextArea();
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    //Helpers for parsing list liners 
    private static final Pattern RE_HASH = Pattern.compile("hash=([0-9a-fA-F]{8,})");
    private static final Pattern RE_CHUNKS = Pattern.compile("chunks=(\\d+)");
    
    // VLCJ preview
    private EmbeddedMediaPlayerComponent mediaPlayerComponent;
    private MediaPlayerFactory mediaPlayerFactory;
    private MediaPlayer mediaPlayer;
    
    private volatile boolean previewStarted = false;

    // 3MB buffer by chunk size calculation
    private static final long MIN_BUFFER_BYTES = 3L * 1024 * 1024;

    // requestedChunks: chunkIndex -> lastRequestTimeMillis
    private final ConcurrentHashMap<Integer, Long> requestedChunks = new ConcurrentHashMap<>();

    private static final long CHUNK_REQ_TIMEOUT_MS = 1500; 

    private static final int  MAX_INFLIGHT   = 40; 
    
    public MainWindow(int port, PeerDiscovery discovery) {
    	this.port = port;
        this.discovery = discovery;

        System.out.println("MainWindow constructor called: " + this);

        // UI basics
        setTitle("P2P Video Streaming Application- PORT" + port);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 650));
        setLayout(new BorderLayout());

        // Menu + Top status bar
        setJMenuBar(createMenuBar());
        add(createTopStatusBar(), BorderLayout.NORTH);
        add(createCenterSplitPane(), BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);

        // Wire discovery listener
        if (this.discovery != null) {
            this.discovery.addPeerListener(this);
            this.discovery.addVideoListener(this);
            this.discovery.addSearchListener(this);
            this.discovery.addChunkListener(this);
        }

        availableVideosList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Double click to start download 
        availableVideosList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) startDownloadSelected();
            }
        });


        // Make table 
        activeStreamsTable.setFillsViewportHeight(true);
        activeStreamsTable.setRowHeight(22);
        setupVideoPreviewPlaceholder();

        // Finalize
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        // Start status
        setConnected(false);
        log("UI ready. Listening on port: " + port);
        log("Click Stream > Connect to start discovery.");

        System.out.println("MainWindow READY: " + this);
        
    }

    // UI Builders
    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu streamMenu = new JMenu("Stream");
        JMenu helpMenu = new JMenu("Help");

        JMenuItem connect = new JMenuItem("Connect");
        connect.addActionListener(e -> {
        	try {
        		String seed = JOptionPane.showInputDialog(this,
        			    "Optional seed peer ip:port (example 192.168.56.101:8888)\nLeave empty for local subnet only:",
        			    "");

        			if (seed != null && !seed.isBlank() && seed.contains(":")) {
        			    String[] sp = seed.trim().split(":");
        			    discovery.addSeedPeer(sp[0], Integer.parseInt(sp[1]));
        			    log("Seed set: " + seed);
        			}

        			discovery.start();

        		setConnected(true);
                log("Connected to overlay network.");
        	}catch (Exception ex) {
        		log("Connection failed: " + ex.getMessage());
        	}
            
        });

        JMenuItem disconnect = new JMenuItem("Disconnect");
        disconnect.addActionListener(e -> {
        	try {
                discovery.stop();
                setConnected(false);
                log("Discovery stopped.");
            } catch (Exception ex) {
                log("Disconnect failed: " + ex.getMessage());
            }
        });

        JMenuItem setRoot = new JMenuItem("Set Root Video Folder");
        setRoot.addActionListener(e -> chooseRootFolder());

        JMenuItem setBuffer = new JMenuItem("Set Buffer Folder");
        setBuffer.addActionListener(e -> chooseFolderAndSet(bufferFolderValue, "Buffer folder set"));

        streamMenu.add(connect);
        streamMenu.add(disconnect);
        streamMenu.addSeparator();
        streamMenu.add(setRoot);
        streamMenu.add(setBuffer);

        JMenuItem about = new JMenuItem("About Developer");
        about.addActionListener(e -> JOptionPane.showMessageDialog(
                this,
                "P2P Video Streaming Application\nDeveloper: Hazan Tunççevik",
                "About",
                JOptionPane.INFORMATION_MESSAGE
        ));

        helpMenu.add(about);

        menuBar.add(streamMenu);
        menuBar.add(helpMenu);
        return menuBar;
    }

    private JPanel createTopStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(new EmptyBorder(6, 8, 6, 8));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(new JLabel("Status:"));
        statusValue.setFont(statusValue.getFont().deriveFont(Font.BOLD));
        left.add(statusValue);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.add(new JLabel("Root Video Folder:"));
        right.add(rootFolderValue);
        right.add(new JLabel("Buffer Folder:"));
        right.add(bufferFolderValue);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(bar, BorderLayout.CENTER);
        wrapper.add(new JSeparator(), BorderLayout.SOUTH);
        return wrapper;
    }

    private JSplitPane createCenterSplitPane() {
        JPanel left = createLeftPanel();
        JPanel right = createRightPanel();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.30);   
        split.setDividerLocation(320);
        split.setOneTouchExpandable(true);
        return split;
    }

    private JPanel createLeftPanel() {
        JPanel left = new JPanel(new BorderLayout());
        left.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Search group
        JPanel searchGroup = new JPanel();
        searchGroup.setLayout(new BoxLayout(searchGroup, BoxLayout.Y_AXIS));

        JLabel searchTitle = new JLabel("Search Videos");
        searchTitle.setFont(searchTitle.getFont().deriveFont(Font.BOLD));
        searchGroup.add(searchTitle);
        searchGroup.add(Box.createVerticalStrut(6));

        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.add(searchField, BorderLayout.CENTER);
        JButton searchBtn = new JButton("Search");
        searchBtn.addActionListener(e -> onSearchClicked());
        searchRow.add(searchBtn, BorderLayout.EAST);

        searchGroup.add(searchRow);
        searchGroup.add(Box.createVerticalStrut(10));

        // Available videos group
        JLabel availTitle = new JLabel("Available Videos in Network");
        availTitle.setFont(availTitle.getFont().deriveFont(Font.BOLD));
        searchGroup.add(availTitle);
        searchGroup.add(Box.createVerticalStrut(6));

        JScrollPane listScroll = new JScrollPane(availableVideosList);
        listScroll.setPreferredSize(new Dimension(280, 350));

        left.add(searchGroup, BorderLayout.NORTH);
        left.add(listScroll, BorderLayout.CENTER);

        return left;
    }

    private JPanel createRightPanel() {
        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Active streams group (top)
        JPanel top = new JPanel(new BorderLayout());
        JLabel streamsTitle = new JLabel("Active Streams");
        streamsTitle.setFont(streamsTitle.getFont().deriveFont(Font.BOLD));
        top.add(streamsTitle, BorderLayout.NORTH);

        JScrollPane tableScroll = new JScrollPane(activeStreamsTable);
        tableScroll.setPreferredSize(new Dimension(600, 140));
        top.add(tableScroll, BorderLayout.CENTER);

        // Video preview (center)
        JPanel videoWrapper = new JPanel(new BorderLayout());
        videoWrapper.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        videoWrapper.add(videoPreviewPanel, BorderLayout.CENTER);

        right.add(top, BorderLayout.NORTH);
        right.add(videoWrapper, BorderLayout.CENTER);

        return right;
    }

    private JPanel createBottomPanel() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(new EmptyBorder(0, 8, 8, 8));

        bottom.add(new JSeparator(), BorderLayout.NORTH);

        JLabel bufferStatus = new JLabel("Global Buffer Status", SwingConstants.CENTER);
        bufferStatus.setBorder(new EmptyBorder(6, 0, 6, 0));
        bottom.add(bufferStatus, BorderLayout.CENTER);

        // event log area
        eventLog.setRows(5);
        eventLog.setEditable(false);
        eventLog.setLineWrap(true);
        eventLog.setWrapStyleWord(true);

        JScrollPane logScroll = new JScrollPane(eventLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Event Log"));

        bottom.add(logScroll, BorderLayout.SOUTH);
        return bottom;
    }

    private void setupVideoPreviewPlaceholder() {
        videoPreviewPanel.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        JLabel placeholder = new JLabel("Video Preview Area", SwingConstants.CENTER);
        placeholder.setFont(placeholder.getFont().deriveFont(Font.PLAIN, 16f));
        videoPreviewPanel.add(placeholder, BorderLayout.CENTER);
        initVlcPreview();

    }
    
    private void initVlcPreview() {
        videoPreviewPanel.removeAll();
        videoPreviewPanel.setLayout(new BorderLayout());

        mediaPlayerComponent = new EmbeddedMediaPlayerComponent();
        mediaPlayerFactory = new MediaPlayerFactory();
        mediaPlayer = mediaPlayerComponent.mediaPlayer();

        videoPreviewPanel.add(mediaPlayerComponent, BorderLayout.CENTER);

        // Control Bar
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        JButton play = new JButton("Play");
        JButton pause = new JButton("Pause");
        JButton stop = new JButton("Stop");

        play.addActionListener(e -> mediaPlayer.controls().play());
        pause.addActionListener(e -> mediaPlayer.controls().pause());
        stop.addActionListener(e -> mediaPlayer.controls().stop());

        controls.add(play);
        controls.add(pause);
        controls.add(stop);

        videoPreviewPanel.add(controls, BorderLayout.SOUTH);

        videoPreviewPanel.revalidate();
        videoPreviewPanel.repaint();

        // After closing -> release
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cleanupVlc();
            }
        });
    }

    
    private void cleanupVlc() {
        try { if (mediaPlayer != null) mediaPlayer.controls().stop(); } catch (Exception ignored) {}
        try { if (mediaPlayerComponent != null) mediaPlayerComponent.release(); } catch (Exception ignored) {}
        try { if (mediaPlayerFactory != null) mediaPlayerFactory.release(); } catch (Exception ignored) {}
    }


    // ACTIONS 
    private void onSearchClicked() {
    	String q = searchField.getText().trim();
    	if (q.isEmpty()) {
    		log("Search ignored (empty query).");
    	    return;
    	}
    	
    	lastQuery = q;
    	searchHitCount = 0;
    	searchMode = true;

    	// Search results view: clean list + indexes + sources
    	availableVideosModel.clear();
    	hashToIndex.clear();
    	hashToSources.clear();
    	hashToName.clear();
        hashToChunks.clear();
        hashToPeers.clear();

    	// debounce timer: show returned results 
    	if (searchDoneTimer != null) searchDoneTimer.stop();
    	searchDoneTimer = new javax.swing.Timer(650, ev -> {
    	    log("Search for \"" + lastQuery + "\" returned " + searchHitCount + " result(s).");
    	    searchMode = false; // search done
    	});
    	searchDoneTimer.setRepeats(false);
    	searchDoneTimer.start();

    	log("Searching for \"" + q + "\" ...");
    	discovery.search(q);

    }

    private void startDownloadSelected() {
        int idx = availableVideosList.getSelectedIndex();
        if (idx < 0) { log("Select a video first."); return; }
       
        // Whole hash in map
        //Fallback parse for list model -> only string 
        String line = availableVideosModel.get(idx);
        String hash = extractHashFromLine(line); 
        int totalChunks = extractChunksFromLine(line);
        
        if (hash == null || hash.isBlank() || totalChunks <= 0) {
            log("Cannot start download: missing hash/chunks in selected line.");
            return;
        }

        currentHash = hash;
        currentTotalChunks = totalChunks;
        
        try {
            Files.createDirectories(bufferDirPath);
        } catch (Exception e) {
            log("Buffer folder create failed: " + e.getMessage());
            return;
        }

        java.nio.file.Path out = bufferDirPath.resolve(hash + ".bin");
        currentBuffer = new ChunkBuffer(hash, totalChunks, out);
        requestedChunks.clear();
        previewStarted = false;

        log("Download started: hash=" + shortHash(hash) + " totalChunks=" + totalChunks);
        rrIndex = 0;

        // Show in Active Streams table 
        streamsTableModel.addRow(new Object[]{
                hashToName.getOrDefault(hash, "Unknown"),
                "(multiple)",
                "0",
                "DOWNLOADING"
        });

        for (int i = 0; i < 4; i++) requestNextChunksRoundRobin();
    }
    
    private void addVideoToList(VideoMeta meta) {
        String hash = meta.hash;

        hashToName.putIfAbsent(hash, meta.name);
        hashToChunks.putIfAbsent(hash, meta.totalChunks);
        hashToPeers.computeIfAbsent(hash, k -> new HashSet<>()).add(meta.source);

        int sources = hashToPeers.get(hash).size();

        String line = meta.name +
                " | chunks=" + meta.totalChunks +
                " | sources=" + sources +
                " | hash=" + hash;

        if (hashToIndex.containsKey(hash)) {
            availableVideosModel.set(hashToIndex.get(hash), line);
        } else {
            int idx = availableVideosModel.size();
            availableVideosModel.addElement(line);
            hashToIndex.put(hash, idx);
        }
    }
    

    private void chooseRootFolder() {
    	JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            String path = chooser.getSelectedFile().getAbsolutePath();
            rootFolderValue.setText(path);
            log("Root video folder set: " + path);

            // For catalog scan + broadcast
            try {
                discovery.setRootFolder(path);
                log("Scanning folder and broadcasting video catalog...");
            } catch (Exception ex) {
                log("Catalog broadcast failed: " + ex.getMessage());
            }
        }
    }

    private void chooseFolderAndSet(JLabel label, String logPrefix) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            String path = chooser.getSelectedFile().getAbsolutePath();
            label.setText(path);
            log(logPrefix + ": " + path);
        }
    }
    
    private void setConnected(boolean connected) {
        statusValue.setText(connected ? "CONNECTED" : "DISCONNECTED");
        statusValue.setForeground(connected ? new Color(0, 128, 0) : Color.DARK_GRAY);
    }

    private void log(String msg) {
        String line = "[" + LocalDateTime.now().format(dtf) + "] " + msg;
        eventLog.append(line + "\n");
        eventLog.setCaretPosition(eventLog.getDocument().getLength());
        System.out.println(line);
    }

    // Listener Callback

    @Override
    public void onPeerDiscovered(Peer peer) {
        SwingUtilities.invokeLater(() -> {
            log("Discovered peer: " + peer);
        });
    }
    
    @Override
    public void onVideoDiscovered(VideoMeta meta) {
    	//During search CAT messages do not fill the list 
    	if (searchMode) return;
        SwingUtilities.invokeLater(() -> {
        	addOrUpdateVideoLine(meta, false);
            log("Video discovered: " + meta.name + " from " + meta.source);
        });
    }
    
    @Override
    public void onSearchResult(String query, VideoMeta meta) {
        SwingUtilities.invokeLater(() -> {
            searchHitCount++;
            
            // Write search results
            addVideoToList(meta);

            // After every result, reset timer
            if (searchDoneTimer != null) searchDoneTimer.restart();
        });
    }   
    
    @Override
    public void onChunkReceived(String videoHash, int chunkIndex, int totalChunks, byte[] data, Peer source) {
    	 if (currentBuffer == null) return;
    	 if (!videoHash.equals(currentHash)) return; // Ignore other video 

 	    try {
	        currentBuffer.put(chunkIndex, data);
	        requestedChunks.remove(chunkIndex);

	        int got = currentBuffer.getReceivedCount();
	        int tot = (totalChunks > 0) ? totalChunks : currentBuffer.getTotalChunks();
	        if (tot <= 0) tot = 1;
	        int pct = (int)Math.round(100.0 * got / tot);

	        log("Chunk " + chunkIndex + " received from " + source + "  (" + pct + "%)");

	        // Update active streams row 0 
            int row = streamsTableModel.getRowCount() - 1;
            if (row >= 0) {
                streamsTableModel.setValueAt(String.valueOf(pct), row, 2);
                streamsTableModel.setValueAt((pct == 100) ? "DONE" : "DOWNLOADING", row, 3);
            }

            if (got >= tot) {
                log("Download complete: " + currentBuffer.getOutFile());
                if (!previewStarted) {
                    previewStarted = true;
                    startPreview(currentBuffer.getOutFile().toString());
                }
            } else {
            	if (!previewStarted) {
            	    int prefix = currentBuffer.contiguousChunksFromStart();
            	    long bufferedBytes = (long) prefix * VideoCatalog.CHUNK_BYTES;

            	    if (bufferedBytes >= MIN_BUFFER_BYTES) {
            	        previewStarted = true;
            	        startPreview(currentBuffer.getOutFile().toString());
            	        log("▶ Preview started with buffer=" + bufferedBytes + " bytes");
            	    }
            	}

            	//For UDP pipeline 2 new chunks safer than 4
                for(int i = 0; i < 2; i++) {
                	requestNextChunksRoundRobin();
                }
            }

	    } catch (Exception e) {
	        log("Chunk write failed: " + e.getMessage());
	    }
    }
    
    private void startPreview(String filePath) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                if (mediaPlayer == null) {
                    log("Preview player not ready (mediaPlayer == null)");
                    return;
                }
                mediaPlayer.media().play(filePath);
                log("▶ Playing: " + filePath);
            } catch (Exception ex) {
                log("Preview failed: " + ex.getMessage());
            }
        });
    }

    
    //Helpers
    
    private void addOrUpdateVideoLine(VideoMeta meta, boolean fromSearch) {
        String hash = meta.hash;
        String name = meta.name;
        int chunks = meta.totalChunks;

        hashToName.putIfAbsent(hash, name);
        hashToChunks.putIfAbsent(hash, chunks);

        // sources + peers
        hashToPeers.computeIfAbsent(hash, k -> new HashSet<>()).add(meta.source);
        int sources = hashToPeers.get(hash).size();
        hashToSources.put(hash, sources);

        String line = hashToName.get(hash)
                + "  | chunks=" + hashToChunks.get(hash)
                + " | sources=" + sources
                + " | hash=" + hash; // Save full hash 

        if (hashToIndex.containsKey(hash)) {
            availableVideosModel.set(hashToIndex.get(hash), line);
        } else {
            int idx = availableVideosModel.size();
            availableVideosModel.addElement(line);
            hashToIndex.put(hash, idx);
        }

        if (fromSearch) {
            log("Search hit: " + name + " from " + meta.source);
        }
    }
    
    private void requestMissingIfAny(ChunkBuffer buf) {
        for (int i = 0; i < 8; i++) {
        	requestNextChunksRoundRobin();    // sende var olan request fonksiyonun
        }
    }
    

    // ROUND ROBIN + TIMEOUT
    public void requestNextChunksRoundRobin() {
        if (currentBuffer == null) return;

        // Inflight limit
        if (requestedChunks.size() >= MAX_INFLIGHT) return;

        // First missing chunk
        int need = -1;
        for (int i = 0; i < currentTotalChunks; i++) {
            if (!currentBuffer.hasChunk(i)) { need = i; break; }
        }
        if (need < 0) return;

        long now = System.currentTimeMillis();

        // Requested before? Do not request before timeout
        Long last = requestedChunks.get(need);
        if (last != null && (now - last) < CHUNK_REQ_TIMEOUT_MS) return;

        // timestamp update
        requestedChunks.put(need, now);

        List<Peer> sources = getSourcesForHash(currentHash);
        if (sources == null || sources.isEmpty()) {
            log("No sources for this video.");
            return;
        }

        Peer chosen = sources.get(rrIndex % sources.size());
        rrIndex++;

        discovery.requestChunk(currentHash, need, chosen.IP, chosen.Port);
        log("Requested chunk " + need + " from " + chosen + (last == null ? "" : " (retry)"));
    }


    
    private static String extractHashFromLine(String line) {
        Matcher m = RE_HASH.matcher(line);
        if (m.find()) return m.group(1);
        int i = line.indexOf("hash=");
        if (i >= 0) return line.substring(i + 5).trim();
        return null;
    }
    
    private static int extractChunksFromLine(String line) {
        Matcher m = RE_CHUNKS.matcher(line);
        if (m.find()) return Integer.parseInt(m.group(1));
        return -1;
    }

    private static String shortHash(String h) {
        if (h == null) return "";
        return (h.length() <= 12) ? h : (h.substring(0, 12) + "...");
    }
    
    private List<Peer> getSourcesForHash(String hash) {
        Set<Peer> set = hashToPeers.get(hash);
        if (set == null || set.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(set);
    }


 // -------------------- MAIN (optional test) --------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            int port = 8888;
            PeerDiscovery discovery = new PeerDiscovery(port);
            new MainWindow(port, discovery);
        });
    }

}
