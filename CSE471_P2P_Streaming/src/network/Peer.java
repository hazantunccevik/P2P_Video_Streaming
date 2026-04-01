package network;

import java.util.Objects;

public class Peer {
    public final String IP;
    public final int Port;

    public Peer(String IP, int Port) {
        this.IP = IP;
        this.Port = Port;
    }

    @Override
    public String toString() {
        return IP + ":" + Port;
    }

    @Override
    public boolean equals(Object o) {
    	if (this == o) return true;
        if (!(o instanceof Peer)) return false;
        Peer p = (Peer) o;
        return Port == p.Port && java.util.Objects.equals(IP, p.IP);
    }

    @Override
    public int hashCode() {
        return Objects.hash(IP, Port);
    }
}
