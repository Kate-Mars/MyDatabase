package model;

public class ProductEntry {
    public int id;
    public String name;
    public int quantity;
    public double price;
    public String supplier;

    public ProductEntry(int id, String name, int quantity, double price, String supplier) {
        this.id = id;
        this.name = name;
        this.quantity = quantity;
        this.price = price;
        this.supplier = supplier;
    }

    /**
     * Простейший разбор CSV-строки вида:
     * id;name;quantity;price;supplier
     */
    public static ProductEntry fromCsv(String line) {
        String[] p = line.split(";");
        if (p.length < 5) {
            throw new IllegalArgumentException("Некорректная CSV-строка: " + line);
        }
        return new ProductEntry(
                Integer.parseInt(p[0]),
                p[1],
                Integer.parseInt(p[2]),
                Double.parseDouble(p[3]),
                p[4]
        );
    }

    @Override
    public String toString() {
        return id + ";" + name + ";" + quantity + ";" + price + ";" + supplier;
    }
}