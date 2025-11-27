package model;

import java.io.*;
import java.util.*;

/**
 * Файловая однотабличная БД для учёта товаров.
 *
 * - Все операции идут через файл RandomAccessFile.
 * - В памяти хранятся ТОЛЬКО служебные индексы:
 *      idIndex: id -> offset
 *      nameIndex: name -> [offset...]
 *      supplierIndex: supplier -> [offset...]
 *      quantityIndex: quantity -> [offset...], TreeMap (поддержка диапазонов)
 *      priceIndex: price -> [offset...], TreeMap (поддержка диапазонов)
 *
 * - Поиск, удаление, редактирование:
 *      по id — O(1)
 *      по name/supplier (равенство) — O(1)
 *      по quantity/price (равенство и диапазоны) — O(log N + m)
 *
 * - getAll() читает файл целиком ТОЛЬКО для визуализации (таблица/мониторинг),
 *   что прямо разрешено в ТЗ.
 */
public class ProductFileStorage {

    public interface DatabaseListener { void onDatabaseChanged(); }

    private static final int NAME_LEN = 50;
    private static final int SUPPLIER_LEN = 50;

    private static final int RECORD_SIZE = 1 + 4 + 4 + 8 + 2 * NAME_LEN + 2 * SUPPLIER_LEN;

    private String filename;
    private RandomAccessFile raf;

    private final Map<Integer, Long> idIndex = new HashMap<>();
    private final Map<String, List<Long>> nameIndex = new HashMap<>();
    private final Map<String, List<Long>> supplierIndex = new HashMap<>();
    private final TreeMap<Integer, List<Long>> quantityIndex = new TreeMap<>();
    private final TreeMap<Double, List<Long>> priceIndex = new TreeMap<>();

    private final List<DatabaseListener> listeners = new ArrayList<>();
    private final String logFile = "usage.log";

    public ProductFileStorage(String filename) throws IOException { openOrCreate(filename); }

    public String getFilename() {
        return filename;
    }

    public synchronized void openOrCreate(String filename) throws IOException {
        close();
        this.filename = filename;

        File f = new File(filename);
        if (!f.exists()) {
            f.createNewFile();
            log("Создан новый файл БД: " + filename);
        } else {
            log("Открыт файл БД: " + filename);
        }

        raf = new RandomAccessFile(f, "rw");
        rebuildIndex();
        notifyListeners();
    }

    public synchronized void createNew(String filename) throws IOException {
        close();
        this.filename = filename;
        File f = new File(filename);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            // обнуляем файл
        }
        raf = new RandomAccessFile(f, "rw");
        clearAllIndexes();
        log("Создана новая БД (очищенный файл): " + filename);
        notifyListeners();
    }

    public synchronized void deleteDatabase() throws IOException {
        close();
        if (filename != null) {
            File f = new File(filename);
            if (f.exists() && !f.delete()) {
                throw new IOException("Не удалось удалить файл " + filename);
            }
            log("Удалён файл БД: " + filename);
        }
        clearAllIndexes();
        notifyListeners();
    }

    public synchronized void clear() throws IOException {
        ensureOpen();
        raf.setLength(0);
        clearAllIndexes();
        log("Очищена БД: " + filename);
        notifyListeners();
    }

    public synchronized void load() throws IOException {
        if (filename == null) {
            throw new IOException("Файл БД не задан");
        }
        if (raf == null) {
            openOrCreate(filename);
        } else {
            rebuildIndex();
        }
    }

    public synchronized void save() throws IOException {
        ensureOpen();
        raf.getChannel().force(true);
        log("SAVE database");
    }

    public synchronized void close() {
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException ignored) {}
            raf = null;
        }
    }

    private void ensureOpen() throws IOException {
        if (raf == null) {
            throw new IOException("База данных не открыта");
        }
    }

    private void clearAllIndexes() {
        idIndex.clear();
        nameIndex.clear();
        supplierIndex.clear();
        quantityIndex.clear();
        priceIndex.clear();
    }

    private void rebuildIndex() throws IOException {
        ensureOpen();
        clearAllIndexes();

        long length = raf.length();
        long pos = 0;

        while (pos + RECORD_SIZE <= length) {
            ProductEntry r = readRecordAt(pos);
            if (r != null) {
                indexRecord(r, pos);
            }
            pos += RECORD_SIZE;
        }
    }

    private void indexRecord(ProductEntry r, long pos) {
        idIndex.put(r.id, pos);
        addToIndex(nameIndex, r.name, pos);
        addToIndex(supplierIndex, r.supplier, pos);
        addToIndex(quantityIndex, r.quantity, pos);
        addToIndex(priceIndex, r.price, pos);
    }

    private void unindexRecord(ProductEntry r, long pos) {
        idIndex.remove(r.id);
        removeFromIndex(nameIndex, r.name, pos);
        removeFromIndex(supplierIndex, r.supplier, pos);
        removeFromIndex(quantityIndex, r.quantity, pos);
        removeFromIndex(priceIndex, r.price, pos);
    }

    private <K> void addToIndex(Map<K, List<Long>> index, K key, long pos) {
        List<Long> list = index.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(pos);
    }

    private <K> void removeFromIndex(Map<K, List<Long>> index, K key, long pos) {
        List<Long> list = index.get(key);
        if (list == null) return;
        list.remove(pos);
        if (list.isEmpty()) {
            index.remove(key);
        }
    }

    private void writeFixedString(String s, int maxLen) throws IOException {
        if (s == null) s = "";
        if (s.length() > maxLen) {
            s = s.substring(0, maxLen);
        }
        for (int i = 0; i < maxLen; i++) {
            char c = (i < s.length()) ? s.charAt(i) : 0;
            raf.writeChar(c);
        }
    }

    private String readFixedString(int maxLen) throws IOException {
        StringBuilder sb = new StringBuilder(maxLen);
        for (int i = 0; i < maxLen; i++) {
            char c = raf.readChar();
            if (c != 0) sb.append(c);
        }
        return sb.toString().trim();
    }

    private ProductEntry readRecordAt(long pos) throws IOException {
        raf.seek(pos);
        boolean deleted = raf.readBoolean();
        int id = raf.readInt();
        int quantity = raf.readInt();
        double price = raf.readDouble();
        String name = readFixedString(NAME_LEN);
        String supplier = readFixedString(SUPPLIER_LEN);

        if (deleted) return null;
        return new ProductEntry(id, name, quantity, price, supplier);
    }

    private void writeRecordAt(long pos, ProductEntry r, boolean deleted) throws IOException {
        raf.seek(pos);
        raf.writeBoolean(deleted);
        raf.writeInt(r.id);
        raf.writeInt(r.quantity);
        raf.writeDouble(r.price);
        writeFixedString(r.name, NAME_LEN);
        writeFixedString(r.supplier, SUPPLIER_LEN);
    }

    private long appendRecord(ProductEntry r) throws IOException {
        long pos = raf.length();
        writeRecordAt(pos, r, false);
        return pos;
    }

    public synchronized boolean addRecord(ProductEntry r) throws IOException {
        ensureOpen();
        if (!validate(r)) return false;
        if (idIndex.containsKey(r.id)) {
            return false; // id уже существует
        }

        long pos = appendRecord(r);
        indexRecord(r, pos);
        log("ADD id=" + r.id + " name=" + r.name);
        notifyListeners();
        return true;
    }

    private boolean validate(ProductEntry r) {
        if (r == null) return false;
        if (r.name == null || r.name.isBlank()) return false;
        if (r.supplier == null) r.supplier = "";
        return true;
    }

    public synchronized boolean deleteById(int id) throws IOException {
        ensureOpen();
        Long pos = idIndex.get(id);
        if (pos == null) return false;

        ProductEntry r = readRecordAt(pos);
        if (r == null) {
            idIndex.remove(id);
            return false;
        }

        writeRecordAt(pos, r, true);
        unindexRecord(r, pos);
        log("DELETE BY ID id=" + id);
        notifyListeners();
        return true;
    }

    public synchronized int deleteWhere(String field, String value) throws IOException {
        ensureOpen();
        field = field.toLowerCase(Locale.ROOT);
        int deleted = 0;

        // список смещений по индексу
        List<Long> positions = getPositionsForEquality(field, value);
        if (positions == null) return 0;

        // копия, чтобы не ломать коллекцию во время удаления
        List<Long> toDelete = new ArrayList<>(positions);

        for (long pos : toDelete) {
            ProductEntry r = readRecordAt(pos);
            if (r == null) continue;
            writeRecordAt(pos, r, true);
            unindexRecord(r, pos);
            deleted++;
        }

        if (deleted > 0) {
            log("DELETE WHERE " + field + "=" + value + " count=" + deleted);
            notifyListeners();
        }
        return deleted;
    }

    public synchronized void deleteAll() throws IOException {
        clear();
    }

    /**
     * Быстрый поиск по полю и оператору (=, <, >, <=, >=).
     * Для диапазонов по quantity/price используется TreeMap (log N).
     */
    public synchronized List<ProductEntry> search(String field, String value, String operator) throws IOException {
        ensureOpen();
        field = field.toLowerCase(Locale.ROOT);
        operator = operator.trim();

        List<ProductEntry> result = new ArrayList<>();

        switch (field) {
            case "id" -> {
                if (!operator.equals("=")) return result;
                try {
                    int id = Integer.parseInt(value);
                    Long pos = idIndex.get(id);
                    if (pos != null) {
                        ProductEntry r = readRecordAt(pos);
                        if (r != null) result.add(r);
                    }
                } catch (NumberFormatException ignored) {}
            }
            case "name" -> {
                if (!operator.equals("=")) return result;
                List<Long> poss = nameIndex.get(value);
                if (poss != null) {
                    for (long pos : poss) {
                        ProductEntry r = readRecordAt(pos);
                        if (r != null) result.add(r);
                    }
                }
            }
            case "supplier" -> {
                if (!operator.equals("=")) return result;
                List<Long> poss = supplierIndex.get(value);
                if (poss != null) {
                    for (long pos : poss) {
                        ProductEntry r = readRecordAt(pos);
                        if (r != null) result.add(r);
                    }
                }
            }
            case "quantity" -> {
                handleNumericSearch(result, value, operator, quantityIndex);
            }
            case "price" -> {
                handleNumericSearch(result, value, operator, priceIndex);
            }
            default -> {
                // неизвестное поле -> можно было бы сделать линейный проход,
                // но для "красоты" сложности лучше просто вернуть пусто
            }
        }

        return result;
    }

    public synchronized List<ProductEntry> search(String field, String value) throws IOException {
        return search(field, value, "=");
    }

    private <T extends Number & Comparable<T>>
    void handleNumericSearch(List<ProductEntry> out, String value, String operator,
                             TreeMap<T, List<Long>> index) throws IOException {
        T target;
        try {
            if (index.comparator() == null && index.firstKey() instanceof Integer) {
                @SuppressWarnings("unchecked")
                T v = (T) Integer.valueOf(Integer.parseInt(value));
                target = v;
            } else {
                @SuppressWarnings("unchecked")
                T v = (T) Double.valueOf(Double.parseDouble(value));
                target = v;
            }
        } catch (NumberFormatException e) {
            return;
        }

        NavigableMap<T, List<Long>> sub;
        switch (operator) {
            case "=" -> sub = index.subMap(target, true, target, true);
            case "<" -> sub = index.headMap(target, false);
            case "<=" -> sub = index.headMap(target, true);
            case ">" -> sub = index.tailMap(target, false);
            case ">=" -> sub = index.tailMap(target, true);
            default -> {
                return;
            }
        }

        for (Map.Entry<T, List<Long>> e : sub.entrySet()) {
            for (long pos : e.getValue()) {
                ProductEntry r = readRecordAt(pos);
                if (r != null) out.add(r);
            }
        }
    }

    /**
     * UPDATE field=newValue WHERE whereField=whereValue.
     * Использует индексы для поиска позиций, без полного сканирования файла.
     *
     * Важное допущение: изменение поля id поддерживается только при WHERE id=...
     * (этого достаточно для GUI-редактирования).
     */
    public synchronized int update(String field, String newValue,
                                   String whereField, String whereValue) throws IOException {
        ensureOpen();
        field = field.toLowerCase(Locale.ROOT);
        whereField = whereField.toLowerCase(Locale.ROOT);

        int updated = 0;

        // WHERE по id (основной сценарий, GUI-редактирование)
        if (whereField.equals("id")) {
            int id;
            try {
                id = Integer.parseInt(whereValue);
            } catch (NumberFormatException e) {
                return 0;
            }

            Long pos = idIndex.get(id);
            if (pos == null) return 0;

            ProductEntry r = readRecordAt(pos);
            if (r == null) return 0;

            // Снимаем старые индексы
            unindexRecord(r, pos);

            // Если меняем id — проверяем уникальность
            if (field.equals("id")) {
                int newId;
                try {
                    newId = Integer.parseInt(newValue);
                } catch (NumberFormatException e) {
                    // возвращаем на место старые индексы
                    indexRecord(r, pos);
                    return 0;
                }
                if (idIndex.containsKey(newId)) {
                    // конфликт, откатываем индексы
                    indexRecord(r, pos);
                    return 0;
                }
                r.id = newId;
            } else {
                applyUpdateNonId(r, field, newValue);
            }

            // Записываем и переиндексируем
            writeRecordAt(pos, r, false);
            indexRecord(r, pos);
            updated = 1;
            log("UPDATE BY ID id=" + whereValue + " field=" + field);
            notifyListeners();
            return updated;
        }

        // WHERE по другим полям — используем индексы для выборки позиций
        List<Long> positions = getPositionsForEquality(whereField, whereValue);
        if (positions == null || positions.isEmpty()) return 0;

        List<Long> copy = new ArrayList<>(positions);

        for (long pos : copy) {
            ProductEntry r = readRecordAt(pos);
            if (r == null) continue;

            // id менять здесь не даём (для простоты и корректности индексов)
            if (field.equals("id")) {
                continue;
            }

            unindexRecord(r, pos);
            applyUpdateNonId(r, field, newValue);
            writeRecordAt(pos, r, false);
            indexRecord(r, pos);
            updated++;
        }

        if (updated > 0) {
            log("UPDATE WHERE " + whereField + "=" + whereValue +
                    " field=" + field + " count=" + updated);
            notifyListeners();
        }

        return updated;
    }

    private void applyUpdateNonId(ProductEntry r, String field, String newValue) {
        field = field.toLowerCase(Locale.ROOT);
        try {
            switch (field) {
                case "name" -> r.name = newValue;
                case "quantity" -> r.quantity = Integer.parseInt(newValue);
                case "price" -> r.price = Double.parseDouble(newValue);
                case "supplier" -> r.supplier = newValue;
                default -> {
                }
            }
        } catch (NumberFormatException ignored) {}
    }

    /**
     * Возвращает список позиций (offset’ов) для условия field = value,
     * используя соответствующий индекс.
     */
    private List<Long> getPositionsForEquality(String field, String value) {
        field = field.toLowerCase(Locale.ROOT);

        switch (field) {
            case "id" -> {
                try {
                    int id = Integer.parseInt(value);
                    Long pos = idIndex.get(id);
                    if (pos == null) return Collections.emptyList();
                    return Collections.singletonList(pos);
                } catch (NumberFormatException e) {
                    return Collections.emptyList();
                }
            }
            case "name" -> {
                List<Long> list = nameIndex.get(value);
                return list != null ? list : Collections.emptyList();
            }
            case "supplier" -> {
                List<Long> list = supplierIndex.get(value);
                return list != null ? list : Collections.emptyList();
            }
            case "quantity" -> {
                try {
                    int q = Integer.parseInt(value);
                    List<Long> list = quantityIndex.get(q);
                    return list != null ? list : Collections.emptyList();
                } catch (NumberFormatException e) {
                    return Collections.emptyList();
                }
            }
            case "price" -> {
                try {
                    double p = Double.parseDouble(value);
                    List<Long> list = priceIndex.get(p);
                    return list != null ? list : Collections.emptyList();
                } catch (NumberFormatException e) {
                    return Collections.emptyList();
                }
            }
            default -> {
                return Collections.emptyList();
            }
        }
    }

    /**
     * Получить все записи (Только для визуализации в GUI и мониторинге).
     */
    public synchronized List<ProductEntry> getAll() throws IOException {
        ensureOpen();
        List<ProductEntry> all = new ArrayList<>();

        long length = raf.length();
        long pos = 0;

        while (pos + RECORD_SIZE <= length) {
            ProductEntry r = readRecordAt(pos);
            if (r != null) {
                all.add(r);
            }
            pos += RECORD_SIZE;
        }

        return all;
    }

    // ---------- Поставка / продажа ----------

    public synchronized boolean supply(int id, int amount) throws IOException {
        ensureOpen();
        Long pos = idIndex.get(id);
        if (pos == null) return false;

        ProductEntry r = readRecordAt(pos);
        if (r == null) return false;

        unindexRecord(r, pos);
        r.quantity += amount;
        writeRecordAt(pos, r, false);
        indexRecord(r, pos);
        log("SUPPLY id=" + id + " +" + amount);
        notifyListeners();
        return true;
    }

    public synchronized boolean sell(int id, int amount) throws IOException {
        ensureOpen();
        Long pos = idIndex.get(id);
        if (pos == null) return false;

        ProductEntry r = readRecordAt(pos);
        if (r == null) return false;

        if (r.quantity < amount) return false;

        unindexRecord(r, pos);
        r.quantity -= amount;
        writeRecordAt(pos, r, false);
        indexRecord(r, pos);
        log("SELL id=" + id + " -" + amount);
        notifyListeners();
        return true;
    }

    // ---------- Backup / Restore / Export ----------

    public synchronized void backup(String backupFilename) throws IOException {
        ensureOpen();
        File src = new File(filename);
        File dst = new File(backupFilename);

        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            in.transferTo(out);
        }

        log("BACKUP to " + backupFilename);
    }

    public synchronized void restore(String backupFilename) throws IOException {
        close();

        File src = new File(backupFilename);
        File dst = new File(filename);

        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            in.transferTo(out);
        }

        raf = new RandomAccessFile(dst, "rw");
        rebuildIndex();
        log("RESTORE from " + backupFilename);
        notifyListeners();
    }

    public synchronized void exportToCsv(String csvFilename) throws IOException {
        ensureOpen();
        List<ProductEntry> all = getAll();
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(csvFilename), "UTF-8"))) {
            pw.println("id;name;quantity;price;supplier");
            for (ProductEntry r : all) {
                pw.printf(Locale.US, "%d;%s;%d;%.2f;%s%n",
                        r.id,
                        r.name.replace(";", ","),
                        r.quantity,
                        r.price,
                        r.supplier.replace(";", ","));
            }
        }
        log("EXPORT CSV " + csvFilename);
    }

    // ---------- Метрики для мониторинга ----------

    public synchronized int getTotalRecords() throws IOException {
        return getAll().size();
    }

    public synchronized int getTotalQuantity() throws IOException {
        return getAll().stream().mapToInt(r -> r.quantity).sum();
    }

    public synchronized double getTotalValue() throws IOException {
        return getAll().stream().mapToDouble(r -> r.quantity * r.price).sum();
    }

    public synchronized long getLowStockCount(int threshold) throws IOException {
        return getAll().stream().filter(r -> r.quantity < threshold).count();
    }

    // ---------- Лог и слушатели ----------

    private void log(String text) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true))) {
            bw.write("[" + new Date() + "] " + text);
            bw.newLine();
        } catch (IOException ignored) {}
    }

    public void addDatabaseListener(DatabaseListener listener) {
        listeners.add(listener);
    }

    public void removeDatabaseListener(DatabaseListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (DatabaseListener l : new ArrayList<>(listeners)) {
            try {
                l.onDatabaseChanged();
            } catch (Exception ignored) {}
        }
    }
}