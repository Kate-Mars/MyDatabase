package gui;

import model.ProductFileStorage;
import model.ProductEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class WarehouseAnalyticsPanel extends JPanel implements ProductFileStorage.DatabaseListener {

    // Палитра под твой неоновый стиль
    private static final Color BG = new Color(0x050816);
    private static final Color CARD_BG = new Color(0x0F172A);
    private static final Color CARD_BORDER = new Color(0x581C87);
    private static final Color TEXT_PRIMARY = new Color(0x4882E3);
    private static final Color TEXT_MUTED = new Color(0x9CA3AF);
    private static final Color ACCENT = new Color(0x38BDF8);
    private static final Color ACCENT_ALT = new Color(0xF97316);

    // --- Журнал операций ---
    private DefaultTableModel logTableModel;
    private JTable logTable;
    private JComboBox<String> logFilter;

    private final ProductFileStorage db;

    // Лейблы для сводки
    private JLabel totalItemsLabel;
    private JLabel totalQuantityLabel;
    private JLabel totalValueLabel;
    private JLabel avgPriceLabel;
    private JLabel suppliersCountLabel;
    private JLabel lowStockLabel;
    private JLabel maxQtyItemLabel;
    private JLabel mostValuableItemLabel;

    // Таблица ТОП-5
    private DefaultTableModel topTableModel;

    // График
    private BarChartPanel chartPanel;

    public WarehouseAnalyticsPanel(ProductFileStorage db) {
        this.db = db;
        db.addDatabaseListener(this);

        setLayout(new BorderLayout(12, 12));
        setBackground(BG);
        setBorder(new EmptyBorder(12, 12, 12, 12));

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("Ostrovsky", Font.PLAIN, 13));
        tabs.setBackground(BG);
        tabs.setForeground(TEXT_MUTED);

        tabs.addTab("Сводка", createSummaryPanel());
        tabs.addTab("Журнал операций", createLogPanel());

        add(tabs, BorderLayout.CENTER);

        // первый пересчёт
        refresh();
    }

    // ===================== ПАНЕЛЬ СВОДКИ =====================

    private JPanel createSummaryPanel() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setOpaque(false);

        // Верх – заголовок
        JLabel title = new JLabel("Мониторинг склада");
        title.setFont(new Font("Ostrovsky", Font.PLAIN, 22));
        title.setForeground(TEXT_PRIMARY);

        JLabel subtitle = new JLabel("Сводка по товарам, поставщикам и ценам");
        subtitle.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        subtitle.setForeground(TEXT_MUTED);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(title);
        header.add(subtitle);

        root.add(header, BorderLayout.NORTH);

        // Центр – сводка + график + таблица
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BorderLayout(12, 12));

        // --- Карточки-сводка ---
        JPanel cards = new JPanel(new GridLayout(2, 4, 10, 10));
        cards.setOpaque(false);

        totalItemsLabel = createMetricLabel();
        totalQuantityLabel = createMetricLabel();
        totalValueLabel = createMetricLabel();
        avgPriceLabel = createMetricLabel();
        suppliersCountLabel = createMetricLabel();
        lowStockLabel = createMetricLabel();
        maxQtyItemLabel = createMetricLabel();
        mostValuableItemLabel = createMetricLabel();

        cards.add(createMetricCard("Уникальных товаров", totalItemsLabel));
        cards.add(createMetricCard("Единиц на складе", totalQuantityLabel));
        cards.add(createMetricCard("Общая стоимость", totalValueLabel));
        cards.add(createMetricCard("Средняя цена", avgPriceLabel));
        cards.add(createMetricCard("Поставщиков", suppliersCountLabel));
        cards.add(createMetricCard("Товаров < 5 шт.", lowStockLabel));
        cards.add(createMetricCard("Макс. остаток", maxQtyItemLabel));
        cards.add(createMetricCard("Самый дорогой (сумма)", mostValuableItemLabel));

        center.add(cards, BorderLayout.NORTH);

        // --- Центр – график ---
        chartPanel = new BarChartPanel();
        chartPanel.setPreferredSize(new Dimension(400, 260));
        JPanel chartCard = wrapInCard(chartPanel, "ТОП товаров по количеству");

        // --- Низ – таблица ТОП-5 ---
        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.setOpaque(false);
        tableCard.setBorder(new EmptyBorder(8, 0, 0, 0));

        JPanel tableInner = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(15, 23, 42, 230));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(CARD_BORDER);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                g2.dispose();
            }
        };
        tableInner.setOpaque(false);
        tableInner.setBorder(new EmptyBorder(10, 12, 12, 12));

        JLabel topTitle = new JLabel("ТОП-5 товаров по стоимости на складе");
        topTitle.setFont(new Font("Ostrovsky", Font.PLAIN, 14));
        topTitle.setForeground(TEXT_PRIMARY);

        String[] cols = {"ID", "Название", "Кол-во", "Цена", "Сумма"};
        topTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable topTable = new JTable(topTableModel);
        styleTopTable(topTable);

        JScrollPane scroll = new JScrollPane(topTable);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);

        tableInner.add(topTitle, BorderLayout.NORTH);
        tableInner.add(scroll, BorderLayout.CENTER);
        tableCard.add(tableInner, BorderLayout.CENTER);

        // Разложим: слева график, справа — таблица
        JPanel middle = new JPanel(new GridLayout(1, 2, 12, 12));
        middle.setOpaque(false);
        middle.add(chartCard);
        middle.add(tableCard);

        center.add(middle, BorderLayout.CENTER);

        root.add(center, BorderLayout.CENTER);

        return root;
    }

    private JLabel createMetricLabel() {
        JLabel l = new JLabel("—");
        l.setFont(new Font("Ostrovsky", Font.PLAIN, 18));
        l.setForeground(TEXT_PRIMARY);
        return l;
    }

    private JPanel createMetricCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                GradientPaint gp = new GradientPaint(
                        0, 0, new Color(15, 23, 42, 230),
                        getWidth(), getHeight(), new Color(15, 23, 42, 200)
                );
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(new Color(88, 28, 135, 180));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BorderLayout());
        card.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        titleLabel.setForeground(TEXT_MUTED);

        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);

        return card;
    }

    private JPanel wrapInCard(JComponent content, String title) {
        JPanel card = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                GradientPaint gp = new GradientPaint(
                        0, 0, new Color(15, 23, 42, 230),
                        getWidth(), getHeight(), new Color(15, 23, 42, 200)
                );
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(CARD_BORDER);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(10, 12, 12, 12));

        JLabel lbl = new JLabel(title);
        lbl.setFont(new Font("Ostrovsky", Font.PLAIN, 14));
        lbl.setForeground(TEXT_PRIMARY);

        card.add(lbl, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);

        return card;
    }

    private void styleTopTable(JTable table) {
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        table.setBackground(new Color(15, 23, 42, 220));
        table.setForeground(TEXT_PRIMARY);
        table.setSelectionBackground(new Color(56, 189, 248, 130));
        table.setSelectionForeground(Color.WHITE);

        table.setDefaultRenderer(Object.class, (tbl, value, isSelected, hasFocus, row, column) -> {
            Component c = new DefaultTableCellRenderer()
                    .getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                c.setBackground(row % 2 == 0
                        ? new Color(15, 23, 42, 230)
                        : new Color(17, 24, 39, 230));
                c.setForeground(TEXT_PRIMARY);
            }
            return c;
        });

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        header.setBackground(new Color(24, 31, 56));
        header.setForeground(TEXT_PRIMARY);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, CARD_BORDER));
    }

    private void styleLogTable(JTable table) {
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        table.setBackground(new Color(15, 23, 42, 220));
        table.setForeground(TEXT_PRIMARY);
        table.setSelectionBackground(new Color(56, 189, 248, 130));
        table.setSelectionForeground(Color.WHITE);

        table.setDefaultRenderer(Object.class, (tbl, value, isSelected, hasFocus, row, column) -> {
            Component c = new DefaultTableCellRenderer()
                    .getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                c.setBackground(row % 2 == 0
                        ? new Color(15, 23, 42, 230)
                        : new Color(17, 24, 39, 230));
                c.setForeground(TEXT_PRIMARY);

                // лёгкий акцент по типам
                String type = (String) tbl.getValueAt(row, 1);
                if ("Удаление".equals(type)) {
                    c.setForeground(new Color(248, 113, 113)); // красный
                } else if ("Добавление".equals(type)) {
                    c.setForeground(new Color(52, 211, 153)); // зелёный
                } else if ("Поставка".equals(type) || "Продажа".equals(type)) {
                    c.setForeground(new Color(56, 189, 248)); // голубой
                }
            }
            return c;
        });

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        header.setBackground(new Color(24, 31, 56));
        header.setForeground(TEXT_PRIMARY);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, CARD_BORDER));
    }

    // ===================== ПАНЕЛЬ ЛОГА =====================

    private JPanel createLogPanel() {
            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setOpaque(false);
            root.setBorder(new EmptyBorder(8, 8, 8, 8));

            // Верхняя панель: заголовок + фильтр + кнопка
            JPanel top = new JPanel(new BorderLayout(8, 8));
            top.setOpaque(false);

            JLabel title = new JLabel("Журнал операций");
            title.setFont(new Font("Ostrovsky", Font.PLAIN, 18));
            title.setForeground(TEXT_PRIMARY);

            top.add(title, BorderLayout.WEST);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);

            logFilter = new JComboBox<>(new String[]{
                    "Все",
                    "Добавление",
                    "Удаление",
                    "Обновление",
                    "Поставка",
                    "Продажа",
                    "Сервис",
                    "Прочее"
            });
            logFilter.setFont(new Font("Ostrovsky", Font.PLAIN, 12));

            JButton reloadBtn = new JButton("Обновить");
            reloadBtn.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
            reloadBtn.setBackground(new Color(31, 41, 55));
            reloadBtn.setForeground(TEXT_PRIMARY);
            reloadBtn.setFocusPainted(false);

            reloadBtn.addActionListener(e -> loadLog((String) logFilter.getSelectedItem()));

            right.add(new JLabel("Фильтр:"));
            right.add(logFilter);
            right.add(reloadBtn);

            top.add(right, BorderLayout.EAST);

            root.add(top, BorderLayout.NORTH);

            // Таблица журнала
            String[] cols = {"Время", "Тип", "Описание"};
            logTableModel = new DefaultTableModel(cols, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };

            logTable = new JTable(logTableModel);
            styleLogTable(logTable);

            JScrollPane scroll = new JScrollPane(logTable);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getViewport().setBackground(CARD_BG);
            scroll.setOpaque(false);

            root.add(scroll, BorderLayout.CENTER);

            // первичная загрузка
            loadLog("Все");

            return root;
    }

    private static class LogEntry {
        String time;
        String type;
        String message;
    }

    private void loadLog(String filterType) {
        logTableModel.setRowCount(0);

        File logFile = new File("operations.log");
        if (!logFile.exists()) {
            logTableModel.addRow(new Object[]{"—", "—", "Журнал пуст."});
            return;
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                LogEntry entry = parseLogLine(line);
                if (entry == null) continue;

                if (!"Все".equals(filterType) && !entry.type.equals(filterType)) {
                    continue;
                }

                logTableModel.addRow(new Object[]{entry.time, entry.type, entry.message});
            }
        } catch (IOException e) {
            logTableModel.addRow(new Object[]{
                    "—", "Прочее", "Ошибка чтения журнала: " + e.getMessage()
            });
        }

        if (logTableModel.getRowCount() == 0) {
            logTableModel.addRow(new Object[]{
                    "—", "—", "Нет записей для выбранного фильтра"
            });
        }
    }

    /**
     * Парсим строку вида:
     * [Thu Nov 27 18:37:12 MSK 2025] ADD id=1 name=...
     */
    private LogEntry parseLogLine(String line) {
        int closeBracket = line.indexOf(']');
        if (closeBracket < 0 || !line.startsWith("[")) {
            return null;
        }

        String time = line.substring(1, closeBracket).trim();
        String rest = line.substring(closeBracket + 1).trim(); // "ADD id=..."

        if (rest.isEmpty()) return null;

        String typeText;
        String upper = rest.toUpperCase(Locale.ROOT);

        if (upper.startsWith("ADD")) typeText = "Добавление";
        else if (upper.startsWith("DELETE")) typeText = "Удаление";
        else if (upper.startsWith("UPDATE")) typeText = "Обновление";
        else if (upper.startsWith("SUPPLY")) typeText = "Поставка";
        else if (upper.startsWith("SELL")) typeText = "Продажа";
        else if (upper.startsWith("BACKUP") || upper.startsWith("RESTORE") || upper.startsWith("EXPORT"))
            typeText = "Сервис";
        else
            typeText = "Прочее";

        LogEntry entry = new LogEntry();
        entry.time = time;
        entry.type = typeText;
        entry.message = rest;

        return entry;
    }

    // ===================== ОБНОВЛЕНИЕ СВОДКИ =====================

    public void refresh() {
        try {
            List<ProductEntry> all = db.getAll();

            int totalItems = all.size();
            int totalQuantity = all.stream().mapToInt(r -> r.quantity).sum();
            double totalValue = all.stream()
                    .mapToDouble(r -> r.quantity * r.price)
                    .sum();
            double avgPrice = all.isEmpty()
                    ? 0.0
                    : all.stream().mapToDouble(r -> r.price).average().orElse(0.0);

            Set<String> suppliers = new HashSet<>();
            for (ProductEntry r : all) {
                if (r.supplier != null && !r.supplier.isBlank()) {
                    suppliers.add(r.supplier.trim());
                }
            }

            int lowStock = (int) all.stream().filter(r -> r.quantity < 5).count();

            // Товар с максимальным остатком
            ProductEntry maxQty = all.stream()
                    .max(Comparator.comparingInt(r -> r.quantity))
                    .orElse(null);

            // Самый дорогой по сумме (quantity * price)
            ProductEntry mostValuable = all.stream()
                    .max(Comparator.comparingDouble(r -> r.quantity * r.price))
                    .orElse(null);

            totalItemsLabel.setText(String.valueOf(totalItems));
            totalQuantityLabel.setText(String.valueOf(totalQuantity));
            totalValueLabel.setText(String.format(Locale.US, "%.2f ₽", totalValue));
            avgPriceLabel.setText(String.format(Locale.US, "%.2f ₽", avgPrice));
            suppliersCountLabel.setText(String.valueOf(suppliers.size()));
            lowStockLabel.setText(String.valueOf(lowStock));

            if (maxQty != null) {
                maxQtyItemLabel.setText(
                        maxQty.name + " (" + maxQty.quantity + " шт.)"
                );
            } else {
                maxQtyItemLabel.setText("—");
            }

            if (mostValuable != null) {
                double val = mostValuable.quantity * mostValuable.price;
                mostValuableItemLabel.setText(
                        mostValuable.name + String.format(Locale.US, " (%.2f ₽)", val)
                );
            } else {
                mostValuableItemLabel.setText("—");
            }

            // Обновляем таблицу ТОП-5
            updateTopTable(all);

            // Обновляем график
            chartPanel.setData(all);

        } catch (IOException e) {
            // можно вывести в лог, но UI не ломаем
        }
    }

    private void updateTopTable(List<ProductEntry> all) {
        // сортируем по quantity*price по убыванию
        List<ProductEntry> sorted = all.stream()
                .sorted((a, b) -> Double.compare(b.quantity * b.price, a.quantity * a.price))
                .limit(5)
                .collect(Collectors.toList());

        topTableModel.setRowCount(0);
        for (ProductEntry r : sorted) {
            double sum = r.quantity * r.price;
            topTableModel.addRow(new Object[]{
                    r.id,
                    r.name,
                    r.quantity,
                    r.price,
                    String.format(Locale.US, "%.2f", sum)
            });
        }
    }

    @Override
    public void onDatabaseChanged() {
        SwingUtilities.invokeLater(this::refresh);
    }

    // ===================== ВНУТРЕННИЙ КЛАСС: ГРАФИК =====================

    /**
     * Простой bar chart по количеству товаров.
     * Показывает до 7 товаров с максимальным quantity.
     */
    private static class BarChartPanel extends JPanel {
        private List<ProductEntry> data = Collections.emptyList();

        public BarChartPanel() {
            setOpaque(false);
        }

        public void setData(List<ProductEntry> all) {
            // берём ТОП-7 по количеству
            this.data = all.stream()
                    .sorted((a, b) -> Integer.compare(b.quantity, a.quantity))
                    .limit(7)
                    .collect(Collectors.toList());
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (data == null || data.isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(TEXT_MUTED);
                g2.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
                g2.drawString("Недостаточно данных для графика", 12, getHeight() / 2);
                g2.dispose();
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int padding = 24;
            int bottom = h - padding * 2;
            int left = padding * 2;

            // фон
            g2.setColor(new Color(15, 23, 42, 200));
            g2.fillRoundRect(0, 0, w, h, 18, 18);

            // ось
            g2.setColor(new Color(55, 65, 81));
            g2.drawLine(left, padding, left, bottom);
            g2.drawLine(left, bottom, w - padding, bottom);

            int maxQty = data.stream().mapToInt(r -> r.quantity).max().orElse(1);

            int barCount = data.size();
            int availableWidth = w - left - padding;
            int barWidth = Math.max(10, (int) (availableWidth / (barCount * 1.5)));

            int x = left + barWidth / 2;
            for (ProductEntry r : data) {
                double ratio = (double) r.quantity / maxQty;
                int barHeight = (int) (ratio * (bottom - padding));

                int yTop = bottom - barHeight;

                // градиент для столбца
                GradientPaint gp = new GradientPaint(
                        x, yTop, ACCENT,
                        x, bottom, ACCENT_ALT
                );
                g2.setPaint(gp);
                g2.fillRoundRect(x, yTop, barWidth, barHeight, 10, 10);

                // подпись значения
                g2.setColor(TEXT_PRIMARY);
                g2.setFont(new Font("Ostrovsky", Font.PLAIN, 11));
                String qtyStr = String.valueOf(r.quantity);
                int sw = g2.getFontMetrics().stringWidth(qtyStr);
                g2.drawString(qtyStr, x + (barWidth - sw) / 2, yTop - 4);

                // подпись снизу (обрезаем длинные имена)
                String name = r.name != null ? r.name : "";
                if (name.length() > 9) {
                    name = name.substring(0, 8) + "…";
                }
                int nw = g2.getFontMetrics().stringWidth(name);
                g2.setColor(TEXT_MUTED);
                g2.drawString(name, x + (barWidth - nw) / 2, bottom + 14);

                x += barWidth * 1.5;
            }

            g2.dispose();
        }
    }
}