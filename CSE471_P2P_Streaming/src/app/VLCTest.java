package app;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;

public class VLCTest {
    public static void main(String[] args) {
        try {
            MediaPlayerFactory factory = new MediaPlayerFactory();
            System.out.println("libVLC bulundu");
            factory.release();
        } catch (Throwable t) {
            System.err.println("❌ libVLC bulunamadı");
            t.printStackTrace();
        }
    }
}
