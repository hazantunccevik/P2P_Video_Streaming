package network;

public interface ChunkListener {
	void onChunkReceived(
			String videoHash, 
			int chunkIndex, 
			int totalChunks, 
			byte[] data, 
			Peer source
	);
}
