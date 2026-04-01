package network;

public class Message {
	public String type;
	public String IP;
	public int Port;
	public int TTL;
	
	public Message(String type, String IP, int Port, int TTL) {
        this.type = type;
        this.IP = IP;
        this.Port = Port;
        this.TTL = TTL;
    }
	
	public String serialize() {
        return type + ";" + IP + ";" + Port + ";" + TTL;
    }

    public static Message deserialize(String data) {
        String[] parts = data.split(";");
        return new Message(
                parts[0],
                parts[1],
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])
        );
    }
}
