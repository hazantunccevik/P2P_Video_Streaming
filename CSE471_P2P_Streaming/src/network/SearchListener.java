package network;

public interface SearchListener {
	void onSearchResult(
			String query, 
			VideoMeta meta
	);
}
