package app;

import javax.swing.SwingUtilities;
import java.net.*;

import gui.MainWindow;
import network.PeerDiscovery;

public class MainApp {
    public static void main(String[] args) {
    	
    	//Subnet Info
    	logNetworkInfo();  
    	
    	//Port selection if not 8888
        int port = 8888;
        if (args != null && args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        
        int finalPort = port;
        
        // Discovery
        PeerDiscovery discovery = new PeerDiscovery(finalPort);


        //GUI
        SwingUtilities.invokeLater(() -> new MainWindow(finalPort, discovery));

    }
    
    private static void logNetworkInfo() {
        try {
            InetAddress local = InetAddress.getLocalHost();
            System.out.println("Local IP: " + local.getHostAddress());

            NetworkInterface ni = NetworkInterface.getByInetAddress(local);
            if (ni == null) {
                System.out.println("No NetworkInterface found for local IP.");
                return;
            }

            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                InetAddress addr = ia.getAddress();
                if (addr instanceof Inet4Address) {
                    short prefix = ia.getNetworkPrefixLength();
                    System.out.println("IPv4: " + addr.getHostAddress() + "/" + prefix);
                }
            }
        } catch (Exception e) {
            System.out.println("logNetworkInfo failed: " + e);
        }
    }

  
}

