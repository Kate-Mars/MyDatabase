package gui;

import model.ProductFileStorage;
import model.ProductEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * SQL-подобная консоль для работы с БД.
 *
 * Поддерживаемые команды:
 *
 * 1) SELECT:
 *    - SELECT *
 *    - SELECT * WHERE field[=|<|>|<=|>=]value
 *    - SELECT * WHERE ... ORDER BY field [ASC|DESC]
 *    - SELECT * WHERE ... ORDER BY field [ASC|DESC] LIMIT N
 *    - SELECT * ORDER BY field [ASC|DESC]
 *    - SELECT * ORDER BY field [ASC|DESC] LIMIT N
 *    - SELECT * LIMIT N
 *
 * 2) DISTINCT:
 *    - SELECT DISTINCT field
 *    - SELECT DISTINCT field WHERE field2[op]value
 *    - SELECT DISTINCT field WHERE ... ORDER BY field [ASC|DESC]
 *    - SELECT DISTINCT field WHERE ... ORDER BY field [ASC|DESC] LIMIT N
 *
 * 3) Агрегаты:
 *    - SELECT COUNT
 *    - SELECT COUNT WHERE field[op]value
 *    - SELECT SUM field [WHERE ...]
 *    - SELECT AVG field [WHERE ...]
 *    - SELECT MIN field [WHERE ...]
 *    - SELECT MAX field [WHERE ...]
 *      (field ∈ {id, quantity, price})
 *
 * 4) DELETE:
 *    - DELETE *
 *    - DELETE * WHERE field=value   (только '=')
 *
 * 5) INSERT:
 *    - INSERT id=1 name="TV" quantity=10 price=49990 supplier="DNS"
 *
 * 6) UPDATE:
 *    - UPDATE SET field=newValue WHERE field2[op]value2
 *
 * 7) HELP:
 *    - HELP или ?
 */
public class QueryConsolePanel extends JPanel {

    private static final Color BG = new Color(0x050816);
    private static final Color CARD_BG = new Color(0x0F172A);
    private static final Color TEXT_PRIMARY = new Color(0xE5E7EB);
    private static final Color TEXT_MUTED = new Color(0x9CA3AF);
    private static final Color ACCENT = new Color(0x38BDF8);

    private static final String[] DEFAULT_COLUMNS =
            {"ID", "ИМЯ", "Остаток занятий", "Цена абонемента", "Тип абонемента"};

    private final ProductFileStorage db;
    private final JTextArea inputArea;
    private final DefaultTableModel tableModel;

    // WHERE field op value
    private static class Condition {
        String field;
        String operator;
        String value;
    }

    // Опции для SELECT (* или DISTINCT)
    private static class SelectOptions {
        Condition condition;    // WHERE ...
        String orderField;      // ORDER BY field
        boolean orderAsc = true;
        Integer limit;          // LIMIT N
    }

    public QueryConsolePanel(ProductFileStorage db) {
        this.db = db;

        setLayout(new BorderLayout(8, 8));
        setBackground(BG);
        setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel infoLabel = new JLabel("SQL-консоль (HELP — список команд)");
        infoLabel.setForeground(TEXT_PRIMARY);
        infoLabel.setFont(new Font("Ostrovsky", Font.BOLD, 13));
        add(infoLabel, BorderLayout.NORTH);

        // Таблица результатов
        tableModel = new DefaultTableModel(DEFAULT_COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setBackground(CARD_BG);
        table.setForeground(TEXT_PRIMARY);
        table.setSelectionBackground(ACCENT.darker());
        table.setSelectionForeground(Color.WHITE);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.getViewport().setBackground(CARD_BG);
        add(tableScroll, BorderLayout.CENTER);

        // Нижняя панель
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.setOpaque(false);

        inputArea = new JTextArea(3, 40);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBackground(CARD_BG);
        inputArea.setForeground(TEXT_PRIMARY);
        inputArea.setCaretColor(TEXT_PRIMARY);
        inputArea.setFont(new Font("Ostrovsky", Font.PLAIN, 12));

        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setBorder(BorderFactory.createTitledBorder("SQL-команда"));

        bottomPanel.add(inputScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setOpaque(false);

        JButton runBtn = new JButton("Выполнить");
        JButton showAllBtn = new JButton("Показать всё");
        JButton helpBtn = new JButton("HELP");

        buttons.add(showAllBtn);
        buttons.add(helpBtn);
        buttons.add(runBtn);

        bottomPanel.add(buttons, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);

        runBtn.addActionListener(e -> runCommand());
        showAllBtn.addActionListener(e -> {
            try {
                refreshTable();
            } catch (IOException ex) {
                showError("Ошибка чтения БД: " + ex.getMessage());
            }
        });
        helpBtn.addActionListener(e -> showHelp());
    }

    // --------------------- Роутер команд ---------------------

    private void runCommand() {
        String cmd = inputArea.getText().trim();
        if (cmd.isEmpty()) return;

        // убираем возможный ';' в конце
        if (cmd.endsWith(";")) {
            cmd = cmd.substring(0, cmd.length() - 1).trim();
        }

        try {
            String upper = cmd.toUpperCase(Locale.ROOT);

            if (upper.startsWith("SELECT COUNT")) {
                runCount(cmd);
            } else if (upper.startsWith("SELECT SUM")) {
                runAggregate(cmd, "SUM");
            } else if (upper.startsWith("SELECT AVG")) {
                runAggregate(cmd, "AVG");
            } else if (upper.startsWith("SELECT MIN")) {
                runAggregate(cmd, "MIN");
            } else if (upper.startsWith("SELECT MAX")) {
                runAggregate(cmd, "MAX");
            } else if (upper.startsWith("SELECT DISTINCT")) {
                runSelectDistinct(cmd);
            } else if (upper.startsWith("SELECT")) {
                runSelect(cmd);
            } else if (upper.startsWith("DELETE")) {
                runDelete(cmd);
            } else if (upper.startsWith("INSERT")) {
                runInsert(cmd);
            } else if (upper.startsWith("UPDATE")) {
                runUpdate(cmd);
            } else if (upper.startsWith("HELP") || upper.equals("?")) {
                showHelp();
            } else {
                showError("Неизвестная команда. Наберите HELP.");
            }
        } catch (Exception ex) {
            showError("Ошибка: " + ex.getMessage());
        }
    }

    // --------------------- SELECT * с WHERE / ORDER BY / LIMIT ---------------------

    private void runSelect(String cmd) throws IOException {
        tableModel.setColumnIdentifiers(DEFAULT_COLUMNS);
        tableModel.setRowCount(0);

        String upper = cmd.toUpperCase(Locale.ROOT);
        int idxSelect = upper.indexOf("SELECT");
        if (idxSelect < 0) {
            showError("SELECT: неверный синтаксис");
            return;
        }

        String afterSelect = cmd.substring(idxSelect + "SELECT".length()).trim();
        if (!afterSelect.startsWith("*")) {
            showError("Поддерживается только SELECT * (агрегаты и DISTINCT отдельно)");
            return;
        }

        String tail = afterSelect.substring(1).trim(); // всё после '*'
        SelectOptions opts = parseSelectOptions(tail);

        List<ProductEntry> records;
        if (opts.condition == null) {
            records = db.getAll();
        } else {
            records = db.search(opts.condition.field,
                    opts.condition.value,
                    opts.condition.operator);
        }

        // ORDER BY
        if (opts.orderField != null) {
            sortRecords(records, opts.orderField, opts.orderAsc);
        }

        // LIMIT
        if (opts.limit != null && opts.limit >= 0 && records.size() > opts.limit) {
            records = new ArrayList<>(records.subList(0, opts.limit));
        }

        for (ProductEntry r : records) {
            addRow(r);
        }
    }

    private void refreshTable() throws IOException {
        tableModel.setColumnIdentifiers(DEFAULT_COLUMNS);
        tableModel.setRowCount(0);
        for (ProductEntry r : db.getAll()) {
            addRow(r);
        }
    }

    // --------------------- SELECT DISTINCT ---------------------

    /**
     * SELECT DISTINCT field [WHERE ...] [ORDER BY field [ASC|DESC]] [LIMIT N]
     */
    private void runSelectDistinct(String cmd) throws IOException {
        String upper = cmd.toUpperCase(Locale.ROOT);
        int idx = upper.indexOf("SELECT DISTINCT");
        if (idx < 0) {
            showError("SELECT DISTINCT: неверный синтаксис");
            return;
        }

        String rest = cmd.substring(idx + "SELECT DISTINCT".length()).trim();
        if (rest.isEmpty()) {
            showError("После DISTINCT нужно указать имя поля");
            return;
        }

        String upperRest = rest.toUpperCase(Locale.ROOT);
        int whereIdx = upperRest.indexOf("WHERE");
        int orderIdx = upperRest.indexOf("ORDER BY");
        int limitIdx = upperRest.indexOf("LIMIT");
        int len = rest.length();

        int endField = len;
        for (int pos : new int[]{whereIdx, orderIdx, limitIdx}) {
            if (pos >= 0 && pos < endField) endField = pos;
        }

        String field = rest.substring(0, endField).trim();
        String tail = rest.substring(endField).trim();

        if (field.isEmpty()) {
            showError("Не удалось определить поле для DISTINCT");
            return;
        }

        SelectOptions opts = parseSelectOptions(tail);

        List<ProductEntry> base;
        if (opts.condition == null) {
            base = db.getAll();
        } else {
            base = db.search(opts.condition.field,
                    opts.condition.value,
                    opts.condition.operator);
        }

        // собираем уникальные значения по выбранному полю
        LinkedHashSet<Comparable<?>> distinctSet = new LinkedHashSet<>();
        for (ProductEntry r : base) {
            Comparable<?> v = getFieldAsComparable(r, field);
            if (v != null) distinctSet.add(v);
        }

        List<Comparable<?>> values = new ArrayList<>(distinctSet);

        // ORDER BY (только по тому же полю, иначе игнор)
        if (opts.orderField != null && opts.orderField.equals(field)) {
            values.sort((a, b) -> {
                @SuppressWarnings("unchecked")
                int cmp = ((Comparable<Object>) a).compareTo(b);
                return opts.orderAsc ? cmp : -cmp;
            });
        }

        // LIMIT
        if (opts.limit != null && opts.limit >= 0 && values.size() > opts.limit) {
            values = new ArrayList<>(values.subList(0, opts.limit));
        }

        // рисуем таблицу с одним столбцом
        tableModel.setRowCount(0);
        tableModel.setColumnIdentifiers(new String[]{"DISTINCT " + field});

        for (Comparable<?> val : values) {
            tableModel.addRow(new Object[]{val.toString()});
        }
    }

    private Comparable<?> getFieldAsComparable(ProductEntry r, String field) {
        switch (field) {
            case "id":
                return r.id;
            case "quantity":
                return r.quantity;
            case "price":
                return r.price;
            case "name":
                return r.name;
            case "supplier":
                return r.supplier;
            default:
                return null;
        }
    }

    // --------------------- COUNT ---------------------

    private void runCount(String cmd) throws IOException {
        String upper = cmd.toUpperCase(Locale.ROOT);
        int whereIndex = upper.indexOf("WHERE");

        int count;
        if (whereIndex < 0) {
            count = db.getTotalRecords();
        } else {
            String condition = cmd.substring(whereIndex + 5).trim();
            Condition c = parseCondition(condition);
            if (c == null) {
                showError("Неверное условие WHERE");
                return;
            }
            List<ProductEntry> found = db.search(c.field, c.value, c.operator);
            count = found.size();
        }

        JOptionPane.showMessageDialog(this, "COUNT = " + count);
    }

    // --------------------- SUM / AVG / MIN / MAX ---------------------

    private void runAggregate(String cmd, String type) throws IOException {
        String upper = cmd.toUpperCase(Locale.ROOT);
        int idxType = upper.indexOf(type);
        if (idxType < 0) {
            showError("Неверная команда " + type);
            return;
        }

        String rest = cmd.substring(idxType + type.length()).trim();
        if (rest.isEmpty()) {
            showError("Укажите поле для " + type);
            return;
        }

        String upperRest = rest.toUpperCase(Locale.ROOT);
        String field;
        Condition cond = null;

        if (upperRest.contains("WHERE")) {
            int idxWhere = upperRest.indexOf("WHERE");
            field = rest.substring(0, idxWhere).trim();
            String condStr = rest.substring(idxWhere + 5).trim();
            cond = parseCondition(condStr);
            if (cond == null) {
                showError("Неверное условие WHERE");
                return;
            }
        } else {
            field = rest.trim();
        }

        if (field.isEmpty()) {
            showError("Не указано поле для " + type);
            return;
        }

        if (!(field.equals("id") || field.equals("quantity") || field.equals("price"))) {
            showError("Агрегаты поддерживаются только для полей id, quantity, price");
            return;
        }

        List<ProductEntry> records;
        if (cond == null) {
            records = db.getAll();
        } else {
            records = db.search(cond.field, cond.value, cond.operator);
        }

        if (records.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Результат пустой");
            return;
        }

        double resultDouble;
        switch (type) {
            case "SUM" -> {
                double sum = 0;
                for (ProductEntry r : records) {
                    sum += getNumericField(r, field);
                }
                resultDouble = sum;
            }
            case "AVG" -> {
                double sum = 0;
                for (ProductEntry r : records) {
                    sum += getNumericField(r, field);
                }
                resultDouble = sum / records.size();
            }
            case "MIN" -> {
                double min = Double.POSITIVE_INFINITY;
                for (ProductEntry r : records) {
                    double v = getNumericField(r, field);
                    if (v < min) min = v;
                }
                resultDouble = min;
            }
            case "MAX" -> {
                double max = Double.NEGATIVE_INFINITY;
                for (ProductEntry r : records) {
                    double v = getNumericField(r, field);
                    if (v > max) max = v;
                }
                resultDouble = max;
            }
            default -> {
                showError("Неизвестный агрегат: " + type);
                return;
            }
        }

        String formatted;
        if (field.equals("price")) {
            formatted = String.format(Locale.US, "%.2f", resultDouble);
        } else {
            formatted = (Math.floor(resultDouble) == resultDouble)
                    ? String.valueOf((long) resultDouble)
                    : String.format(Locale.US, "%.2f", resultDouble);
        }

        JOptionPane.showMessageDialog(this,
                type + "(" + field + ") = " + formatted);
    }

    private double getNumericField(ProductEntry r, String field) {
        return switch (field) {
            case "id" -> r.id;
            case "quantity" -> r.quantity;
            case "price" -> r.price;
            default -> 0;
        };
    }

    // --------------------- DELETE ---------------------

    private void runDelete(String cmd) throws IOException {
        if (cmd.equalsIgnoreCase("DELETE *")) {
            db.deleteAll();
            try {
                db.save();
            } catch (Exception ignored) {}
            refreshTable();
            return;
        }

        String upper = cmd.toUpperCase(Locale.ROOT);
        if (!upper.contains("WHERE")) {
            showError("Формат: DELETE * WHERE field=value");
            return;
        }

        String condition = cmd.substring(upper.indexOf("WHERE") + 5).trim();
        int idxEq = condition.indexOf("=");
        if (idxEq < 0) {
            showError("Неверное условие (нужен '=')");
            return;
        }

        String field = condition.substring(0, idxEq).trim();
        String value = condition.substring(idxEq + 1).trim().replace("\"", "");

        int deleted = db.deleteWhere(field, value);

        try {
            db.save();
        } catch (Exception ignored) {}

        JOptionPane.showMessageDialog(this, "Удалено записей: " + deleted);
        refreshTable();
    }

    // --------------------- INSERT ---------------------

    private void runInsert(String cmd) throws Exception {
        cmd = cmd.substring("INSERT".length()).trim();

        String[] tokens = cmd.split("\\s+");
        int id = 0, quantity = 0;
        double price = 0;
        String name = "", supplier = "";

        for (String t : tokens) {
            String[] p = t.split("=");
            if (p.length < 2) continue;
            String key = p[0].trim();
            String val = p[1].trim().replace("\"", "");

            switch (key) {
                case "id" -> id = Integer.parseInt(val);
                case "name" -> name = val;
                case "quantity" -> quantity = Integer.parseInt(val);
                case "price" -> price = Double.parseDouble(val);
                case "supplier" -> supplier = val;
            }
        }

        db.addRecord(new ProductEntry(id, name, quantity, price, supplier));
        JOptionPane.showMessageDialog(this, "Добавлено");

        try {
            db.save();
        } catch (Exception ignored) {}
    }

    // --------------------- UPDATE ---------------------

    private void runUpdate(String cmd) throws IOException {
        String upper = cmd.toUpperCase(Locale.ROOT);

        if (!upper.contains("SET") || !upper.contains("WHERE")) {
            showError("Ошибка синтаксиса UPDATE");
            return;
        }

        String setPart = cmd.substring(upper.indexOf("SET") + 3,
                upper.indexOf("WHERE")).trim();
        String[] setSplit = setPart.split("=");
        if (setSplit.length < 2) {
            showError("Ошибка синтаксиса SET");
            return;
        }
        String field = setSplit[0].trim();
        String newValue = setSplit[1].trim().replace("\"", "");

        String condPart = cmd.substring(upper.indexOf("WHERE") + 5).trim();
        Condition c = parseCondition(condPart);
        if (c == null) {
            showError("Неверное условие WHERE");
            return;
        }

        int updated = db.update(field, newValue, c.field, c.value);

        refreshTable();

        try {
            db.save();
        } catch (Exception ignored) {}

        JOptionPane.showMessageDialog(this, "Обновлено записей: " + updated);
    }

    // --------------------- Парсинг WHERE / ORDER / LIMIT ---------------------

    /**
     * tail: строка после SELECT * или после DISTINCT field.
     * Пример: "WHERE price>1000 ORDER BY name DESC LIMIT 10"
     */
    private SelectOptions parseSelectOptions(String tail) {
        SelectOptions opts = new SelectOptions();
        if (tail == null) return opts;

        tail = tail.trim();
        if (tail.isEmpty()) return opts;

        String upper = tail.toUpperCase(Locale.ROOT);
        int whereIdx = upper.indexOf("WHERE");
        int orderIdx = upper.indexOf("ORDER BY");
        int limitIdx = upper.indexOf("LIMIT");
        int len = tail.length();

        // WHERE
        if (whereIdx >= 0) {
            int whereStart = whereIdx + "WHERE".length();
            int whereEnd = len;
            if (orderIdx >= 0 && orderIdx > whereIdx && orderIdx < whereEnd)
                whereEnd = orderIdx;
            if (limitIdx >= 0 && limitIdx > whereIdx && limitIdx < whereEnd)
                whereEnd = limitIdx;
            String condStr = tail.substring(whereStart, whereEnd).trim();
            if (!condStr.isEmpty()) {
                opts.condition = parseCondition(condStr);
            }
        }

        // ORDER BY
        if (orderIdx >= 0) {
            int orderStart = orderIdx + "ORDER BY".length();
            int orderEnd = len;
            if (limitIdx >= 0 && limitIdx > orderIdx && limitIdx < orderEnd)
                orderEnd = limitIdx;
            String orderStr = tail.substring(orderStart, orderEnd).trim();
            if (!orderStr.isEmpty()) {
                String[] parts = orderStr.split("\\s+");
                opts.orderField = parts[0].trim();
                if (parts.length > 1 && parts[1].equalsIgnoreCase("DESC")) {
                    opts.orderAsc = false;
                }
            }
        }

        // LIMIT
        if (limitIdx >= 0) {
            int limitStart = limitIdx + "LIMIT".length();
            String limitStr = tail.substring(limitStart).trim();
            if (limitStr.endsWith(";")) {
                limitStr = limitStr.substring(0, limitStr.length() - 1).trim();
            }
            if (!limitStr.isEmpty()) {
                try {
                    opts.limit = Integer.parseInt(limitStr);
                } catch (NumberFormatException ignored) {}
            }
        }

        return opts;
    }

    /**
     * Разбор строки вида: field [op] value
     */
    private Condition parseCondition(String condition) {
        condition = condition.trim();
        String op;
        if (condition.contains(">=")) op = ">=";
        else if (condition.contains("<=")) op = "<=";
        else if (condition.contains(">")) op = ">";
        else if (condition.contains("<")) op = "<";
        else if (condition.contains("=")) op = "=";
        else return null;

        int idx = condition.indexOf(op);
        if (idx < 0) return null;

        Condition c = new Condition();
        c.field = condition.substring(0, idx).trim();
        c.operator = op;
        c.value = condition.substring(idx + op.length()).trim().replace("\"", "");
        return c;
    }

    // --------------------- Сортировка записей ---------------------

    private void sortRecords(List<ProductEntry> records, String field, boolean asc) {
        Comparator<ProductEntry> cmp;
        switch (field) {
            case "id" -> cmp = Comparator.comparingInt(r -> r.id);
            case "name" -> cmp = Comparator.comparing(r -> r.name, Comparator.nullsFirst(String::compareTo));
            case "quantity" -> cmp = Comparator.comparingInt(r -> r.quantity);
            case "price" -> cmp = Comparator.comparingDouble(r -> r.price);
            case "supplier" -> cmp = Comparator.comparing(r -> r.supplier, Comparator.nullsFirst(String::compareTo));
            default -> {
                // неизвестное поле — просто не сортируем
                return;
            }
        }
        if (!asc) {
            cmp = cmp.reversed();
        }
        records.sort(cmp);
    }

    // --------------------- Вспомогательное ---------------------

    private void addRow(ProductEntry r) {
        tableModel.addRow(new Object[]{r.id, r.name, r.quantity, r.price, r.supplier});
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(this,
                """
                Доступные команды:
                1) SELECT:
                   SELECT *
                   SELECT * WHERE field[=|<|>|<=|>=]value
                   SELECT * WHERE ... ORDER BY field [ASC|DESC]
                   SELECT * ORDER BY field [ASC|DESC]
                   SELECT * WHERE ... LIMIT N
                   SELECT * ORDER BY field LIMIT N
                   Примеры:
                   - SELECT *
                   - SELECT * WHERE price>1000
                   - SELECT * WHERE supplier="DNS" ORDER BY price DESC
                   - SELECT * ORDER BY quantity ASC LIMIT 10
                2) DISTINCT:
                   SELECT DISTINCT field
                   SELECT DISTINCT field WHERE field2[op]value
                   SELECT DISTINCT field WHERE ... ORDER BY field [ASC|DESC]
                   SELECT DISTINCT field WHERE ... ORDER BY field [ASC|DESC] LIMIT N
                   Примеры:
                   - SELECT DISTINCT supplier
                   - SELECT DISTINCT supplier WHERE quantity>0 ORDER BY supplier
                3) Агрегатные функции:
                   SELECT COUNT
                   SELECT COUNT WHERE field[op]value
                   SELECT SUM field [WHERE ...]
                   SELECT AVG field [WHERE ...]
                   SELECT MIN field [WHERE ...]
                   SELECT MAX field [WHERE ...]
                   (field ∈ {id, quantity, price})
                   Примеры:
                   - SELECT COUNT
                   - SELECT COUNT WHERE quantity>10
                   - SELECT SUM quantity
                   - SELECT AVG price WHERE supplier="OZON"
                   - SELECT MAX price
                4) DELETE:
                   DELETE *                    — удалить все записи
                   DELETE * WHERE field=value  — удалить по значению поля (=)
                5) INSERT:
                   INSERT id=1 name="TV" quantity=10 price=49990 supplier="DNS"
                6) UPDATE:
                   UPDATE SET field=newValue WHERE field2[op]value2
                   Примеры:
                   - UPDATE SET price=900 WHERE name="TV"
                   - UPDATE SET supplier="OZON" WHERE id=5
                7) HELP:
                   HELP   или   ?
                """);
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }
}