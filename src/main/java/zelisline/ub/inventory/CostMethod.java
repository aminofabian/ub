package zelisline.ub.inventory;

public enum CostMethod {
    FIFO,
    LIFO;

    public static CostMethod fromApiValue(String raw) {
        return "LIFO".equalsIgnoreCase(String.valueOf(raw)) ? LIFO : FIFO;
    }
}
