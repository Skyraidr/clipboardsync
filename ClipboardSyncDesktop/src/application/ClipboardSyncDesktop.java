package application;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.io.File;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;

import io.nayuki.qrcodegen.QrCode;

public class ClipboardSyncDesktop extends JFrame {

    // --- PROTOCOL ---
    // Every message is: [int type][int length][length bytes of data], sent on a fresh TCP connection to port 8888.
    private static final int PORT = 8888;
    private static final int TYPE_TEXT = 1;
    private static final int TYPE_IMAGE = 2;
    private static final int TYPE_FILE = 4;
    private static final int TYPE_PAIR = 5;      // Phone -> Desktop after scanning the QR code. Data = phone name (UTF-8)
    private static final int TYPE_PAIR_ACK = 6;  // Desktop -> Phone to confirm the reverse direction works. Data = desktop name (UTF-8)

    // Content of the pairing QR code: clipsync://pair?v=1&n=<url-encoded desktop name>&ip=<ip1>,<ip2>,...
    private static final String PAIR_URI_PREFIX = "clipsync://pair";
    private static final int MAX_QR_IPS = 4;     // Keep the QR code small: fewer modules = faster scanning
    private static final int QR_TARGET_PX = 260;

    // --- AUTO-RECONNECT (presence beacons) ---
    // Every few seconds, and immediately when the network changes, each app broadcasts a tiny UDP message:
    //   CLIPSYNC1|<D=desktop or P=phone>|<permanent device id>|<name>
    // The sender's current IP is taken from the packet itself, so when a paired device shows up at a
    // new address the other side simply updates the IP it sends to. No restart or re-scan needed.
    private static final int PRESENCE_PORT = 8889;
    private static final String PRESENCE_MAGIC = "CLIPSYNC1";
    private static final long PRESENCE_INTERVAL_MS = 3000;
    private static final long PHONE_OFFLINE_AFTER_MS = 75000; // Phones announce every 30s while in the background
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    // Remembers this PC's id and the paired phone across restarts
    private final Preferences settings = Preferences.userNodeForPackage(ClipboardSyncDesktop.class);
    private final String deviceId = loadOrCreateDeviceId();

    private volatile String pairedPhoneId;
    private volatile String pairedPhoneName;
    private volatile String pairedPhoneIp;
    private volatile long lastPhoneSeenAt = 0;
    private volatile boolean phoneBeaconSeen = false; // Only phones with auto-reconnect send beacons
    private volatile boolean phoneOnline = false;

    private volatile DatagramSocket presenceSocket;
    private final Object presenceWake = new Object();
    private boolean presenceWakeRequested = false; // Guarded by presenceWake
    private volatile List<String> lastKnownIps = new ArrayList<>();

    private JTextArea logArea;
    private ServerSocket serverSocket;
    private Clipboard systemClipboard;
    private JComboBox<String> ipDropdown;
    private JLabel myIpLabel;
    private JLabel qrLabel;
    private JLabel qrInfoLabel;
    private JLabel pairedLabel;

    // Target IP address
    private volatile String targetIp = null;

    // Lock to prevent multiple threads from reading clipboard at once
    private AtomicBoolean isProcessing = new AtomicBoolean(false);

    // Deduplication Hashes
    private volatile String lastTextHash = "";
    private volatile int lastImageHash = 0;
    private volatile String lastFileHash = ""; // ADD THIS

    public ClipboardSyncDesktop() {
        setTitle("PC Clipboard Sync Server");
        setSize(1000, 520);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        logArea = new JTextArea();
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        myIpLabel = new JLabel("My IP: " + getLocalIpAddress());
        topPanel.add(myIpLabel);

        topPanel.add(new Box.Filler(new Dimension(20, 0), new Dimension(20, 0), new Dimension(20, 0)));
        topPanel.add(new JLabel("Target Phone IP:"));

        // Replaced JTextField with JComboBox
        ipDropdown = new JComboBox<>();
        ipDropdown.setEditable(true); // Allow manual typing just in case
        ipDropdown.setPreferredSize(new Dimension(130, 25));
        topPanel.add(ipDropdown);

        JButton connectBtn = new JButton("Set Target");
        connectBtn.addActionListener(e -> {
            String ip = (String) ipDropdown.getSelectedItem();
            if (ip != null && !ip.trim().isEmpty()) {
                targetIp = ip.trim();
                log("Target IP manually set to: " + targetIp);
            }
        });
        topPanel.add(connectBtn);

        JButton clearBtn = new JButton("Clear Files");
        clearBtn.addActionListener(e -> clearSyncedFiles());
        topPanel.add(clearBtn);

        // Add note for the user
        JLabel noteLabel = new JLabel("(Press 'r' to refresh list)");
        noteLabel.setFont(new Font("Arial", Font.ITALIC, 11));
        noteLabel.setForeground(Color.GRAY);
        topPanel.add(noteLabel);

        add(topPanel, BorderLayout.NORTH);

        // --- QR PAIRING PANEL ---
        add(buildQrPanel(), BorderLayout.EAST);

        // Global Key Listener for the 'r' key
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_R) {
                // Ignore if the user is typing an IP address manually inside the text box
                Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focusOwner instanceof javax.swing.text.JTextComponent) {
                    return false;
                }
                scanNetworkForDevices();
                refreshQrCode(); // The network may have changed, so refresh the pairing code too
                return true;
            }
            return false;
        });

        systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();

        // 1. Event Listener (Triggers instantly when OS notifies us)
        systemClipboard.addFlavorListener(e -> triggerSync());

        // 2. High-Speed Poller (Triggers every 200ms to catch what OS misses)
        startFastPoller();

        startServer();

        // Show the pairing QR code
        refreshQrCode();

        // Do an initial scan on startup
        scanNetworkForDevices();

        // Auto-reconnect: announce ourselves, listen for paired phones, and watch for network changes
        loadPairedPhone();
        startPresence();
        new Thread(this::restorePairedPhone).start();
    }

    // --- QR PAIRING ---
    private JPanel buildQrPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(300, 0));

        JLabel title = new JLabel("Connect a phone");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel instructions = new JLabel("<html><body style='width:200px;text-align:center'>"
                + "Open Clipboard Sync on your phone, tap <b>Scan Desktop QR Code</b> and point it here."
                + "</body></html>");
        instructions.setHorizontalAlignment(SwingConstants.CENTER);
        instructions.setAlignmentX(Component.CENTER_ALIGNMENT);

        qrLabel = new JLabel();
        qrLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        qrLabel.setHorizontalAlignment(SwingConstants.CENTER);

        qrInfoLabel = new JLabel();
        qrInfoLabel.setFont(qrInfoLabel.getFont().deriveFont(Font.PLAIN, 11f));
        qrInfoLabel.setForeground(Color.GRAY);
        qrInfoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        qrInfoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton refreshQrBtn = new JButton("Refresh QR Code");
        refreshQrBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        refreshQrBtn.addActionListener(e -> refreshQrCode());

        pairedLabel = new JLabel("Paired phone: none");
        pairedLabel.setHorizontalAlignment(SwingConstants.CENTER);
        pairedLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        panel.add(title);
        panel.add(Box.createVerticalStrut(6));
        panel.add(instructions);
        panel.add(Box.createVerticalStrut(8));
        panel.add(qrLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(qrInfoLabel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(refreshQrBtn);
        panel.add(Box.createVerticalStrut(12));
        panel.add(pairedLabel);
        return panel;
    }

    // Regenerates the pairing QR code from the current network addresses. Safe to call from any thread.
    private void refreshQrCode() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshQrCode);
            return;
        }
        myIpLabel.setText("My IP: " + getLocalIpAddress());

        List<String> ips = getCandidateIpAddresses();
        if (ips.isEmpty()) {
            qrLabel.setIcon(null);
            qrLabel.setText("No network connection found");
            qrInfoLabel.setText("Connect to Wi-Fi, then press Refresh.");
            return;
        }
        try {
            String payload = buildPairingPayload(ips);
            QrCode qr = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM);
            qrLabel.setText(null);
            qrLabel.setIcon(new ImageIcon(renderQrCode(qr)));
            qrInfoLabel.setText("<html><body style='width:200px;text-align:center'>" + escapeHtml(getDesktopName()) + "<br>"
                    + String.join(", ", ips) + " : " + PORT + "</body></html>");
        } catch (Exception e) {
            qrLabel.setIcon(null);
            qrLabel.setText("Could not create QR code");
            log("QR Error: " + e.getMessage());
        }
    }

    private String buildPairingPayload(List<String> ips) throws UnsupportedEncodingException {
        // Encode spaces as %20 (not '+') so every URI decoder reads the name the same way
        String name = URLEncoder.encode(getDesktopName(), "UTF-8").replace("+", "%20");
        return PAIR_URI_PREFIX + "?v=1&n=" + name + "&id=" + deviceId + "&ip=" + String.join(",", ips);
    }

    private BufferedImage renderQrCode(QrCode qr) {
        int border = 4; // Quiet zone required by the QR spec
        int modules = qr.size + border * 2;
        int scale = Math.max(1, QR_TARGET_PX / modules);
        int px = modules * scale;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                boolean dark = qr.getModule(x / scale - border, y / scale - border);
                img.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
            }
        }
        return img;
    }

    private static volatile String cachedDesktopName;

    private static String getDesktopName() {
        String cached = cachedDesktopName;
        if (cached != null) return cached;
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.trim().isEmpty()) name = System.getenv("HOSTNAME");
        if (name == null || name.trim().isEmpty()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) { }
        }
        if (name == null || name.trim().isEmpty()) name = "Desktop";
        name = name.trim();
        name = name.length() > 40 ? name.substring(0, 40) : name;
        cachedDesktopName = name;
        return name;
    }

    // All IPv4 addresses a phone on the same network could reach us on, best guesses first.
    private List<String> getCandidateIpAddresses() {
        Set<String> preferred = new LinkedHashSet<>();
        Set<String> others = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (iface.isLoopback() || !iface.isUp()) continue;
                    String ifaceName = (iface.getName() + " " + iface.getDisplayName()).toLowerCase(Locale.ROOT);
                    boolean likelyVirtual = iface.isVirtual() || iface.isPointToPoint()
                            || ifaceName.contains("virtual") || ifaceName.contains("vmware")
                            || ifaceName.contains("vbox") || ifaceName.contains("vethernet")
                            || ifaceName.contains("hyper-v") || ifaceName.contains("docker")
                            || ifaceName.contains("wsl") || ifaceName.contains("tap")
                            || ifaceName.contains("tun") || ifaceName.contains("vpn")
                            || ifaceName.startsWith("br-") || ifaceName.startsWith("veth");
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (!(addr instanceof Inet4Address)) continue;
                        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                        if (addr.isSiteLocalAddress() && !likelyVirtual) preferred.add(addr.getHostAddress());
                        else others.add(addr.getHostAddress());
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception e) { e.printStackTrace(); }

        List<String> result = new ArrayList<>(preferred);
        for (String ip : others) {
            if (!result.contains(ip)) result.add(ip);
        }
        return result.size() > MAX_QR_IPS ? new ArrayList<>(result.subList(0, MAX_QR_IPS)) : result;
    }

    // --- NETWORK SCANNER ---
    private void scanNetworkForDevices() {
        log("Scanning local network for Clipboard Sync devices...");
        ipDropdown.removeAllItems();
        String localIp = getLocalIpAddress();
        if (localIp.equals("Unknown")) {
            log("Cannot determine local IP.");
            return;
        }

        // Our own addresses are not phones, so skip them
        final List<String> ownIps = getCandidateIpAddresses();

        // Get the subnet (e.g., 192.168.1.)
        String subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1);

        new Thread(() -> {
            // Use 50 threads to quickly check all 255 IPs on the subnet
            ExecutorService executor = Executors.newFixedThreadPool(50);
            for (int i = 1; i <= 254; i++) {
                final String testIp = subnet + i;
                if (testIp.equals(localIp) || ownIps.contains(testIp)) continue;
                executor.submit(() -> {
                    try (Socket s = new Socket()) {
                        // Check if the device is running our app (listening on port 8888)
                        s.connect(new InetSocketAddress(testIp, PORT), 300);
                        SwingUtilities.invokeLater(() -> {
                            boolean exists = false;
                            for(int j = 0; j < ipDropdown.getItemCount(); j++) {
                                if(ipDropdown.getItemAt(j).equals(testIp)) exists = true;
                            }
                            if(!exists) {
                                ipDropdown.addItem(testIp);
                                log("Found device: " + testIp);
                            }
                        });
                    } catch (Exception ignored) { }
                });
            }
            executor.shutdown();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
                SwingUtilities.invokeLater(() -> log("Network scan complete."));
            } catch (InterruptedException ignored) {}
        }).start();
    }

    private void clearSyncedFiles() {
        File syncFolder = new File(System.getProperty("user.home"), "Downloads" + File.separator + "ClipboardSync");
        if (syncFolder.exists() && syncFolder.isDirectory()) {
            File[] files = syncFolder.listFiles();
            if (files != null) {
                int count = 0;
                for (File f : files) {
                    if (f.delete()) count++;
                }
                log("Cleared " + count + " files from ClipboardSync folder.");
            }
        } else {
            log("No synced files found to clear.");
        }
    }

    private void startFastPoller() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(200); // Check 5 times per second (Feels instant)
                    triggerSync();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    // Wrapper to ensure we don't spawn threads if already processing
    private void triggerSync() {
        if (isProcessing.get()) return; // Skip if busy
        new Thread(this::checkAndSyncClipboard).start();
    }

    private String getLocalIpAddress() {
        // Prefer the real Wi-Fi/Ethernet address over virtual adapters (VPN, WSL, VirtualBox...)
        List<String> candidates = getCandidateIpAddresses();
        if (!candidates.isEmpty()) return candidates.get(0);
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return "Unknown";
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> logArea.append(msg + "\n"));
    }

    private void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                log("Ready! Listening on Port " + PORT);

                while (true) {
                    Socket socket = serverSocket.accept();
                    // The sender is registered as the target phone once a real message arrives
                    // (see receiveFromPhone), so empty probe connections are ignored.
                    new Thread(() -> receiveFromPhone(socket)).start();
                }
            } catch (IOException e) {
                log("Server Error: " + e.getMessage());
            }
        }).start();
    }

    // Makes the given phone the send target and selects it in the dropdown
    private void registerPeer(String connectedIp) {
        if (targetIp == null || !targetIp.equals(connectedIp)) {
            targetIp = connectedIp;
            SwingUtilities.invokeLater(() -> {
                // Automatically select the device in the dropdown
                boolean exists = false;
                for(int j = 0; j < ipDropdown.getItemCount(); j++) {
                    if(ipDropdown.getItemAt(j).equals(connectedIp)) exists = true;
                }
                if(!exists) ipDropdown.addItem(connectedIp);

                ipDropdown.setSelectedItem(connectedIp);
                log("Auto-detected Phone IP: " + connectedIp);
            });
        }
    }

    // --- SENDING LOGIC ---
    private void checkAndSyncClipboard() {
        if (!isProcessing.compareAndSet(false, true)) return; // Double check lock
        if (targetIp == null || targetIp.isEmpty()) {
            isProcessing.set(false);
            return;
        }

        try {
            Transferable t = null;

            // Fast retry loop for Snipping Tool lag
            for (int i = 0; i < 3; i++) {
                try {
                    t = systemClipboard.getContents(null);
                    if (t != null) break;
                    Thread.sleep(50);
                } catch (Exception e) {
                    try { Thread.sleep(50); } catch (Exception ignored){}
                }
            }

            if (t == null) {
                isProcessing.set(false);
                return;
            }

            // 1. FILE HANDLER
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    java.util.List<File> fileList = (java.util.List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    if (fileList != null && !fileList.isEmpty()) {
                        File file = fileList.get(0); // Send the first file copied

                        String filePath = file.getAbsolutePath();

                        // ONLY SEND IF IT'S A NEW FILE
                        if (!filePath.equals(lastFileHash)) {
                            lastFileHash = filePath; // Update the hash so it doesn't loop

                            byte[] fileBytes = Files.readAllBytes(file.toPath());
                            byte[] nameBytes = file.getName().getBytes("UTF-8");

                            // Build Payload
                            ByteBuffer buffer = ByteBuffer.allocate(4 + nameBytes.length + fileBytes.length);
                            buffer.putInt(nameBytes.length);
                            buffer.put(nameBytes);
                            buffer.put(fileBytes);

                            log("Sending File: " + file.getName() + "...");
                            sendDataToIp(targetIp, TYPE_FILE, buffer.array()); // Type 4 = Generic File

                            // Reset the other hashes
                            lastImageHash = 0;
                            lastTextHash = "";
                        }

                        isProcessing.set(false);
                        return;
                    }
                } catch (Exception e) {
                    log("File read failed: " + e.getMessage());
                }
            }

            // 2. IMAGE HANDLER (With Fast Hash Check)
            if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                try {
                    Object rawData = t.getTransferData(DataFlavor.imageFlavor);
                    if (rawData instanceof Image) {
                        BufferedImage image = toBufferedImage((Image) rawData);

                        // Ultra-lightweight hash: Dims + Center Pixel + Corner Pixel
                        // This is extremely fast (milliseconds)
                        int currentHash = computeImageHash(image);

                        if (currentHash != lastImageHash) {
                            lastImageHash = currentHash; // Update immediately

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(image, "png", baos);
                            log("Sending Image to Phone...");
                            sendDataToIp(targetIp, TYPE_IMAGE, baos.toByteArray());

                            lastTextHash = ""; // Reset text hash
                            lastFileHash = ""; // ADD THIS: Reset file hash
                        }
                        // If hash matches, do nothing (we already sent it)
                    }
                } catch (Exception e) {
                    // Image read failed (maybe clipboard cleared)
                }
            }

            // 3. TEXT HANDLER
            else if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    String text = (String) t.getTransferData(DataFlavor.stringFlavor);
                    if (text != null && !text.equals(lastTextHash)) {
                        lastTextHash = text;
                        log("Sending Text to Phone...");
                        sendDataToIp(targetIp, TYPE_TEXT, text.getBytes("UTF-8"));
                        lastImageHash = 0; // Reset image hash
                        lastFileHash = ""; // ADD THIS: Reset file hash
                    }
                } catch (Exception e) {
                    // Text read failed
                }
            }

        } catch (Exception e) {
            // General clipboard error
        } finally {
            isProcessing.set(false); // Release lock
        }
    }

    // Records what is currently on the clipboard as "already sent" WITHOUT sending it,
    // so a freshly paired phone only receives things copied after pairing.
    // Mirrors the File -> Image -> Text priority used in checkAndSyncClipboard().
    @SuppressWarnings("unchecked")
    private void primeHashesFromClipboard() {
        try {
            Transferable t = systemClipboard.getContents(null);
            if (t == null) return;

            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    java.util.List<File> fileList = (java.util.List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    if (fileList != null && !fileList.isEmpty()) {
                        lastFileHash = fileList.get(0).getAbsolutePath();
                        return;
                    }
                } catch (Exception ignored) { }
            }

            if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Object rawData = t.getTransferData(DataFlavor.imageFlavor);
                if (rawData instanceof Image) {
                    lastImageHash = computeImageHash(toBufferedImage((Image) rawData));
                }
            } else if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) t.getTransferData(DataFlavor.stringFlavor);
                if (text != null) lastTextHash = text;
            }
        } catch (Exception ignored) {
            // Clipboard busy/unreadable - nothing to prime
        }
    }

    private static int computeImageHash(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        return Objects.hash(
            w, h,
            image.getRGB(0, 0),
            image.getRGB(w/2, h/2),
            image.getRGB(w-1, h-1)
        );
    }

    private BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage) return (BufferedImage) img;
        ImageIcon icon = new ImageIcon(img);
        BufferedImage bimage = new BufferedImage(
            Math.max(1, icon.getIconWidth()),
            Math.max(1, icon.getIconHeight()),
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D bGr = bimage.createGraphics();
        bGr.drawImage(img, 0, 0, null);
        bGr.dispose();
        return bimage;
    }

    private void sendDataToIp(String ip, int type, byte[] data) {
        try (Socket socket = connectTo(ip);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            out.writeInt(type);
            out.writeInt(data.length);
            out.write(data);
            out.flush();
            log("Sent Successfully!");
        } catch (IOException e) {
            log("Send Failed: " + e.getMessage());
        }
    }

    // Fails after 3 seconds instead of the OS default (~20s) if the phone has gone away
    private static Socket connectTo(String ip) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, PORT), 3000);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    // --- RECEIVING ---
    private void receiveFromPhone(Socket socket) {
        String connectedIp = socket.getInetAddress().getHostAddress();
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            int type;
            try {
                type = in.readInt();
            } catch (EOFException probe) {
                // Connection opened and closed without data (e.g. a network scan) - not a phone message
                return;
            }
            int length = in.readInt();
            byte[] buffer = new byte[length];
            in.readFully(buffer);

            if (type == TYPE_PAIR) {
                handlePairRequest(connectedIp, new String(buffer, "UTF-8"));
                return;
            }

            // We must set isProcessing=true here so our own poller doesn't
            // try to send back what we just received!
            isProcessing.set(true);
            try {
                registerPeer(connectedIp);

                if (type == TYPE_TEXT) { // Text
                    String text = new String(buffer, "UTF-8");
                    lastTextHash = text;
                    systemClipboard.setContents(new StringSelection(text), null);
                    log("Received Text");
                }
                else if (type == TYPE_IMAGE) { // Image
                    ByteArrayInputStream bais = new ByteArrayInputStream(buffer);
                    BufferedImage image = ImageIO.read(bais);
                    if (image != null) {
                        // Update hash so poller ignores this new clipboard content
                        lastImageHash = computeImageHash(image);

                        systemClipboard.setContents(new ImageTransferable(image), null);
                        log("Received Image");
                    }
                }
                else if (type == TYPE_FILE) { // Generic File
                    ByteBuffer bufferWrapper = ByteBuffer.wrap(buffer);

                    // Extract Filename
                    int nameLen = bufferWrapper.getInt();
                    byte[] nameBytes = new byte[nameLen];
                    bufferWrapper.get(nameBytes);
                    String fileName = new String(nameBytes, "UTF-8");

                    // Extract File Data
                    byte[] fileBytes = new byte[buffer.length - 4 - nameLen];
                    bufferWrapper.get(fileBytes);

                    // Save to a DEDICATED subfolder
                    File downloadsFolder = new File(System.getProperty("user.home"), "Downloads" + File.separator + "ClipboardSync");
                    if (!downloadsFolder.exists()) {
                        downloadsFolder.mkdirs();
                    }

                    File outFile = new File(downloadsFolder, fileName);
                    Files.write(outFile.toPath(), fileBytes);

                 // ---> UPDATE THESE LINES <---
                    // 1. Update the hash so the poller ignores this new clipboard content
                    lastFileHash = outFile.getAbsolutePath();

                    // 2. Put the actual FILE on the clipboard, not just the string path
                    systemClipboard.setContents(new FileTransferable(outFile), null);

                    log("Received File: " + fileName + " (Saved to Downloads/ClipboardSync)");
                }
            } finally {
                // Wait a bit for clipboard to settle before letting poller run again
                Thread.sleep(500);
                isProcessing.set(false);
            }
        } catch (Exception e) {
            log("Receive Error: " + e.getMessage());
            isProcessing.set(false);
        }
    }

    // A phone scanned our QR code: make it the target, then prove the Desktop -> Phone direction works too.
    // Data = phone name, optionally followed by "\n" and the phone's permanent id (used for auto-reconnect).
    private void handlePairRequest(String phoneIp, String rawPayload) {
        String[] parts = rawPayload.split("\n", 2);
        String phoneName = cleanName(parts[0], "Phone");
        String phoneId = parts.length > 1 ? parts[1].trim() : "";
        final String displayName = phoneName;

        log("QR pairing request from " + displayName + " (" + phoneIp + ")");
        // Make sure whatever is ALREADY on the desktop clipboard isn't pushed to the phone the instant it pairs
        switchTargetWithoutResend(phoneIp);

        pairedPhoneId = phoneId.matches(ID_PATTERN) ? phoneId : null;
        pairedPhoneName = displayName;
        pairedPhoneIp = phoneIp;
        phoneBeaconSeen = false;
        lastPhoneSeenAt = System.currentTimeMillis();
        savePairedPhone();
        setPairedStatus("<i>Confirming...</i>");

        // Reply on a NEW connection to the phone's own server, exactly like clipboard data travels
        boolean reachable = false;
        String error = null;
        try (Socket socket = connectTo(phoneIp)) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            byte[] nameBytes = getDesktopName().getBytes("UTF-8");
            out.writeInt(TYPE_PAIR_ACK);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
            out.flush();
            reachable = true;
        } catch (IOException e) {
            error = e.getMessage();
        }

        phoneOnline = reachable;
        setPairedStatus(reachable ? "<font color='#2e7d32'>Connected both ways</font>"
                                  : "<font color='#c62828'>Phone not reachable from this PC</font>");
        if (reachable) {
            log("Paired with " + displayName + " (" + phoneIp + ") - connected both ways. Copy something to sync!");
        } else {
            log("Paired with " + displayName + " (" + phoneIp + "), but this PC could not reach the phone (" + error
                    + "). Keep the Clipboard Sync app open on the phone and make sure both are on the same Wi-Fi.");
        }
        wakePresence(); // Announce ourselves right away
    }

    // Changes the send target without pushing what is already on the desktop clipboard
    private void switchTargetWithoutResend(String ip) {
        // Hold the clipboard lock so the poller can't send between priming and switching
        long waitUntil = System.currentTimeMillis() + 5000;
        boolean gotLock = false;
        while (!gotLock && System.currentTimeMillis() < waitUntil) {
            gotLock = isProcessing.compareAndSet(false, true);
            if (!gotLock) {
                try { Thread.sleep(20); } catch (InterruptedException e) { break; }
            }
        }
        if (!gotLock) isProcessing.set(true);
        try {
            primeHashesFromClipboard();
            registerPeer(ip);
        } finally {
            isProcessing.set(false);
        }
    }

    private void setPairedStatus(String statusHtml) {
        final String name = pairedPhoneName;
        final String ip = pairedPhoneIp;
        SwingUtilities.invokeLater(() -> {
            if (name == null || ip == null) {
                pairedLabel.setText("Paired phone: none");
            } else {
                pairedLabel.setText("<html><body style='width:200px;text-align:center'>Paired phone:<br>"
                        + escapeHtml(name) + " (" + ip + ")"
                        + (statusHtml == null ? "" : "<br>" + statusHtml) + "</body></html>");
            }
        });
    }

    // --- AUTO-RECONNECT ---
    private String loadOrCreateDeviceId() {
        String id = settings.get("deviceId", null);
        if (id == null || !id.matches(ID_PATTERN)) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            settings.put("deviceId", id);
            flushSettings();
        }
        return id;
    }

    private void loadPairedPhone() {
        String ip = settings.get("pairedPhoneIp", null);
        if (ip == null || !isIpv4(ip)) return;
        String id = settings.get("pairedPhoneId", null);
        pairedPhoneId = (id != null && id.matches(ID_PATTERN)) ? id : null;
        pairedPhoneName = cleanName(settings.get("pairedPhoneName", null), "Phone");
        pairedPhoneIp = ip;
    }

    private void savePairedPhone() {
        if (pairedPhoneId != null) settings.put("pairedPhoneId", pairedPhoneId);
        else settings.remove("pairedPhoneId");
        if (pairedPhoneName != null) settings.put("pairedPhoneName", pairedPhoneName);
        if (pairedPhoneIp != null) settings.put("pairedPhoneIp", pairedPhoneIp);
        flushSettings();
    }

    private void flushSettings() {
        try {
            settings.flush();
        } catch (Exception e) {
            log("Could not save settings: " + e.getMessage());
        }
    }

    // After a restart, go straight back to the phone we were paired with (beacons correct the IP if it changed)
    private void restorePairedPhone() {
        String ip = pairedPhoneIp;
        if (ip == null) return;
        if (targetIp == null) switchTargetWithoutResend(ip);
        log("Remembered phone: " + pairedPhoneName + " (" + ip + "). It reconnects automatically, even if its IP changes.");
        setPairedStatus(pairedPhoneId != null ? "<i>Waiting for phone...</i>" : null);
    }

    private void startPresence() {
        try {
            DatagramSocket socket = new DatagramSocket(PRESENCE_PORT);
            socket.setBroadcast(true);
            presenceSocket = socket;
            Thread rx = new Thread(this::presenceReceiveLoop, "presence-receive");
            rx.setDaemon(true);
            rx.start();
        } catch (IOException e) {
            log("Auto-reconnect can't listen on UDP port " + PRESENCE_PORT + " (" + e.getMessage()
                    + "). Is another copy of this app running?");
            try {
                DatagramSocket sendOnly = new DatagramSocket();
                sendOnly.setBroadcast(true);
                presenceSocket = sendOnly;
            } catch (IOException ignored) { }
        }
        lastKnownIps = getCandidateIpAddresses();
        Thread tx = new Thread(this::presenceAnnounceLoop, "presence-announce");
        tx.setDaemon(true);
        tx.start();
    }

    // Asks the announce loop to run now (e.g. right after pairing or a phone moved)
    private void wakePresence() {
        synchronized (presenceWake) {
            presenceWakeRequested = true;
            presenceWake.notifyAll();
        }
    }

    private void presenceAnnounceLoop() {
        while (true) {
            try {
                // 1. Has OUR address changed? (Wi-Fi switched, laptop woke up, new DHCP lease...)
                List<String> ips = getCandidateIpAddresses();
                if (!ips.equals(lastKnownIps)) {
                    lastKnownIps = ips;
                    if (ips.isEmpty()) {
                        log("Network disconnected. Waiting for a connection...");
                    } else {
                        log("Network changed - this PC is now at " + String.join(", ", ips)
                                + ". Paired phones reconnect automatically.");
                    }
                    refreshQrCode();
                }

                // 2. Tell everyone on the network where we are
                sendPresenceBeacon();

                // 3. Mark the paired phone as away if it stopped announcing itself
                if (phoneBeaconSeen && phoneOnline
                        && System.currentTimeMillis() - lastPhoneSeenAt > PHONE_OFFLINE_AFTER_MS) {
                    phoneOnline = false;
                    setPairedStatus("<font color='gray'>Not seen recently - is the app open?</font>");
                }

                synchronized (presenceWake) {
                    if (!presenceWakeRequested) presenceWake.wait(PRESENCE_INTERVAL_MS);
                    presenceWakeRequested = false;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // Never let a network hiccup stop auto-reconnect
                try { Thread.sleep(PRESENCE_INTERVAL_MS); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void sendPresenceBeacon() {
        DatagramSocket socket = presenceSocket;
        if (socket == null) return;
        byte[] data = (PRESENCE_MAGIC + "|D|" + deviceId + "|" + cleanName(getDesktopName(), "Desktop"))
                .getBytes(StandardCharsets.UTF_8);

        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (iface.isLoopback() || !iface.isUp()) continue;
                    for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                        if (ia.getBroadcast() != null) targets.add(ia.getBroadcast()); // e.g. 192.168.1.255
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
            // Also send straight to the paired phone: works even where broadcasts are filtered
            String phoneIp = pairedPhoneIp;
            if (phoneIp != null) targets.add(InetAddress.getByName(phoneIp));
        } catch (Exception ignored) { }

        for (InetAddress target : targets) {
            try {
                socket.send(new DatagramPacket(data, data.length, target, PRESENCE_PORT));
            } catch (Exception ignored) { } // No route on that interface right now
        }
    }

    private void presenceReceiveLoop() {
        byte[] buf = new byte[512];
        while (true) {
            DatagramSocket socket = presenceSocket;
            if (socket == null || socket.isClosed()) return;
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                continue;
            }
            try {
                if (!(packet.getAddress() instanceof Inet4Address)) continue;
                String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                String[] f = msg.split("\\|", -1);
                if (f.length < 4 || !PRESENCE_MAGIC.equals(f[0]) || !"P".equals(f[1])) continue;
                String id = f[2];
                if (!id.matches(ID_PATTERN) || id.equals(deviceId)) continue;
                onPhoneSeen(id, cleanName(f[3], "Phone"), packet.getAddress().getHostAddress());
            } catch (Exception ignored) { }
        }
    }

    private void onPhoneSeen(String id, String name, String ip) {
        if (!id.equals(pairedPhoneId)) {
            // Not our paired phone: just offer it in the dropdown (replaces waiting for a network scan)
            SwingUtilities.invokeLater(() -> {
                for (int j = 0; j < ipDropdown.getItemCount(); j++) {
                    if (ip.equals(ipDropdown.getItemAt(j))) return;
                }
                ipDropdown.addItem(ip);
                log("Found device: " + name + " (" + ip + ")");
            });
            return;
        }

        lastPhoneSeenAt = System.currentTimeMillis();
        phoneBeaconSeen = true;
        String oldIp = pairedPhoneIp;
        boolean moved = !ip.equals(oldIp);
        boolean renamed = !name.equals(pairedPhoneName);
        if (moved || renamed) {
            pairedPhoneIp = ip;
            pairedPhoneName = name;
            savePairedPhone();
        }

        if (moved) {
            // Follow the phone, unless the user deliberately picked a different target
            String current = targetIp;
            boolean follow = current == null || current.equals(oldIp);
            if (follow) switchTargetWithoutResend(ip);
            log("Phone " + name + " is now at " + ip + (follow ? " - reconnected automatically." : "."));
            wakePresence(); // Let the phone know where we are right away too
        } else if (targetIp == null) {
            switchTargetWithoutResend(ip);
        }

        if (moved || renamed || !phoneOnline) {
            phoneOnline = true;
            setPairedStatus("<font color='#2e7d32'>Connected</font>");
        }
    }

    private static String cleanName(String raw, String fallback) {
        if (raw == null) return fallback;
        String name = raw.replaceAll("\\p{Cntrl}", "").replace('|', '-').trim();
        if (name.isEmpty()) return fallback;
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    private static boolean isIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (!p.matches("[0-9]{1,3}") || Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // Helper Classes
    static class ImageTransferable implements Transferable {
        private Image image;
        public ImageTransferable(Image image) { this.image = image; }
        public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }

    static class FileTransferable implements Transferable {
        private java.util.List<File> fileList;

        public FileTransferable(File file) {
            this.fileList = new java.util.ArrayList<>();
            this.fileList.add(file);
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return fileList;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClipboardSyncDesktop().setVisible(true));
    }
}
