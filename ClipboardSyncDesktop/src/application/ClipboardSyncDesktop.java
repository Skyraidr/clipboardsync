package application;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

public class ClipboardSyncDesktop extends JFrame {

    private JTextArea logArea;
    private ServerSocket serverSocket;
    private Clipboard systemClipboard;
    private JTextField ipField;
    
    // Target IP address
    private volatile String targetIp = null; 

    // Lock to prevent multiple threads from reading clipboard at once
    private AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // Deduplication Hashes
    private volatile String lastTextHash = "";
    private volatile int lastImageHash = 0;

    public ClipboardSyncDesktop() {
        setTitle("PC Clipboard Sync Server");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        logArea = new JTextArea();
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("My IP: " + getLocalIpAddress()));
        
        topPanel.add(new Box.Filler(new Dimension(20, 0), new Dimension(20, 0), new Dimension(20, 0)));
        topPanel.add(new JLabel("Target Phone IP:"));
        ipField = new JTextField(15);
        JButton connectBtn = new JButton("Set Target");
        
        connectBtn.addActionListener(e -> {
            String ip = ipField.getText().trim();
            if (!ip.isEmpty()) {
                targetIp = ip;
                log("Target IP manually set to: " + targetIp);
            }
        });
        
        topPanel.add(ipField);
        topPanel.add(connectBtn);
        
        add(topPanel, BorderLayout.NORTH);

        systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        
        // 1. Event Listener (Triggers instantly when OS notifies us)
        systemClipboard.addFlavorListener(e -> triggerSync());
        
        // 2. High-Speed Poller (Triggers every 200ms to catch what OS misses)
        startFastPoller();

        startServer();
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
                            ipField.setText(targetIp);
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
                // (File logic same as before, omitted for brevity, usually manual copy works fine)
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
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClipboardSyncDesktop().setVisible(true));
    }
}