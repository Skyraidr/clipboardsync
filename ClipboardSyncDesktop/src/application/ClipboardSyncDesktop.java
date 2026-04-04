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
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

public class ClipboardSyncDesktop extends JFrame {

    private JTextArea logArea;
    private ServerSocket serverSocket;
    private Clipboard systemClipboard;
    private JComboBox<String> ipDropdown;
    
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
        setSize(750, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        logArea = new JTextArea();
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("My IP: " + getLocalIpAddress()));
        
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

        // Global Key Listener for the 'r' key
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_R) {
                // Ignore if the user is typing an IP address manually inside the text box
                Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focusOwner instanceof javax.swing.text.JTextComponent) {
                    return false;
                }
                scanNetworkForDevices();
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
        
        // Do an initial scan on startup
        scanNetworkForDevices();
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

        // Get the subnet (e.g., 192.168.1.)
        String subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1);

        new Thread(() -> {
            // Use 50 threads to quickly check all 255 IPs on the subnet
            ExecutorService executor = Executors.newFixedThreadPool(50);
            for (int i = 1; i <= 254; i++) {
                final String testIp = subnet + i;
                executor.submit(() -> {
                    try (Socket s = new Socket()) {
                        // Check if the device is running our app (listening on port 8888)
                        s.connect(new InetSocketAddress(testIp, 8888), 300);
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
                serverSocket = new ServerSocket(8888);
                log("Ready! Listening on Port 8888");
                
                while (true) {
                    Socket socket = serverSocket.accept();
                    String connectedIp = socket.getInetAddress().getHostAddress();
                    
                    if (targetIp == null || !targetIp.equals(connectedIp)) {
                        targetIp = connectedIp;
                        SwingUtilities.invokeLater(() -> {
                            // Automatically select the device in the dropdown
                            boolean exists = false;
                            for(int j = 0; j < ipDropdown.getItemCount(); j++) {
                                if(ipDropdown.getItemAt(j).equals(targetIp)) exists = true;
                            }
                            if(!exists) ipDropdown.addItem(targetIp);
                            
                            ipDropdown.setSelectedItem(targetIp);
                            log("Auto-detected Phone IP: " + targetIp);
                        });
                    }
                    
                    new Thread(() -> receiveFromPhone(socket)).start();
                }
            } catch (IOException e) {
                log("Server Error: " + e.getMessage());
            }
        }).start();
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
                            sendDataToIp(targetIp, 4, buffer.array()); // Type 4 = Generic File
                            
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
                        int w = image.getWidth();
                        int h = image.getHeight();
                        int currentHash = Objects.hash(
                            w, h, 
                            image.getRGB(0, 0), 
                            image.getRGB(w/2, h/2),
                            image.getRGB(w-1, h-1)
                        );

                        if (currentHash != lastImageHash) {
                            lastImageHash = currentHash; // Update immediately
                            
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(image, "png", baos);
                            log("Sending Image to Phone...");
                            sendDataToIp(targetIp, 2, baos.toByteArray());
                            
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
                        sendDataToIp(targetIp, 1, text.getBytes("UTF-8"));
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
        try (Socket socket = new Socket(ip, 8888);
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

    // --- RECEIVING ---
    private void receiveFromPhone(Socket socket) {
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            int type = in.readInt();
            int length = in.readInt();
            byte[] buffer = new byte[length];
            in.readFully(buffer);

            // We must set isProcessing=true here so our own poller doesn't
            // try to send back what we just received!
            isProcessing.set(true); 
            try {
                if (type == 1) { // Text
                    String text = new String(buffer, "UTF-8");
                    lastTextHash = text; 
                    systemClipboard.setContents(new StringSelection(text), null);
                    log("Received Text");
                } 
                else if (type == 2) { // Image
                    ByteArrayInputStream bais = new ByteArrayInputStream(buffer);
                    BufferedImage image = ImageIO.read(bais);
                    if (image != null) {
                        // Update hash so poller ignores this new clipboard content
                         int w = image.getWidth();
                         int h = image.getHeight();
                         lastImageHash = Objects.hash(w, h, image.getRGB(0,0), image.getRGB(w/2, h/2), image.getRGB(w-1, h-1));
                        
                        systemClipboard.setContents(new ImageTransferable(image), null);
                        log("Received Image");
                    }
                }
                else if (type == 4) { // Generic File
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