package zelisline.ub.storeroom.domain;

/**
 * What a store-room movement did to stock.
 *
 * <p>The distinction is the whole point of the reason model: "restock to shelf"
 * and "spoilage" are both *take out*, but only one of them changes what the shop
 * owns. Treating Class A reasons as stock decrements would drain inventory every
 * time a shelf was filled.
 */
public enum StoreRoomStockEffect {

    /** The goods are still the shop's — a location memo. No stock write. */
    NONE("none"),

    /** Goods left the shop (lost, eaten, stolen, damaged). Decrements stock. */
    DECREASE("decrease"),

    /** Reserved for a future put-in that genuinely brings new stock into inventory. */
    INCREASE("increase");

    private final String wireValue;

    StoreRoomStockEffect(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
