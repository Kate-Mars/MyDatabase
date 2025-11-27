package gui;

import model.ProductFileStorage;
import model.ProductEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class InventoryDashboard extends JFrame implements ProductFileStorage.DatabaseListener {

    // --- Цвета темы (космический неон) ---
    private static final Color BG_TOP = new Color(51, 51, 161);        // верхний градиент
    private static final Color BG_BOTTOM = new Color(82, 158, 79, 255);     // нижний градиент

    private static final Color CARD_BG = new Color(12, 19, 38, 210);  // фон карточек
    private static final Color CARD_BORDER = new Color(88, 28, 135);  // фиолетовый бордер

    private static final Color ACCENT = new Color(56, 189, 248);      // неоновый голубой
    private static final Color ACCENT_ALT = new Color(244, 63, 94);   // неоновый розовый
    private static final Color DANGER = new Color(239, 68, 68);

    private static final Color TEXT_PRIMARY = new Color(67, 128, 255);
    private static final Color TEXT_MUTED = new Color(148, 163, 184);

    private static final Color TABLE_ROW_ALT = new Color(15, 23, 42, 200);

    private ProductFileStorage db;
    private File currentFile;

    private DefaultTableModel tableModel;
    private JTable table;
    private WarehouseAnalyticsPanel monitoring;
    private QueryConsolePanel sqlConsole;
    private JTabbedPane tabs;

    public InventoryDashboard() {
        super("Учёт клиентов спортзала");

        // --- БД ---
        try {
            currentFile = new File("products.db");
            db = new ProductFileStorage(currentFile.getAbsolutePath());
            db.addDatabaseListener(this); // MainWindow слушает изменения БД
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Не удалось открыть/создать БД: " + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1400, 900);
        setLocationRelativeTo(null);

        // --- Градиентный корневой контейнер ---
        JPanel root = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                GradientPaint gp = new GradientPaint(
                        0, 0, BG_TOP,
                        getWidth(), getHeight(), BG_BOTTOM
                );
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        root.setLayout(new BorderLayout(16, 16));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        // --- Меню ---
        setJMenuBar(createMenuBar());

        // --- Таблица ---
        String[] columns = {"ID", "Название", "Кол-во", "Цена", "Поставщик"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        styleTable(table);

        JPanel mainPanel = createMainPanel();

        // --- Вкладки ---
        tabs = new JTabbedPane(JTabbedPane.TOP) {
            // лёгкий кастом отрисовки вкладок
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // фон уже рисует root, тут ничего не надо
            }
        };
        tabs.setOpaque(false);
        tabs.setBorder(new EmptyBorder(0, 0, 0, 0));
        tabs.setFont(new Font("Ostrovsky", Font.PLAIN, 14));
        tabs.setForeground(TEXT_MUTED);
        tabs.setBackground(new Color(0, 0, 0, 0));

        tabs.addTab("База данных", mainPanel);

        monitoring = new WarehouseAnalyticsPanel(db);
        tabs.addTab("Мониторинг", monitoring);

        sqlConsole = new QueryConsolePanel(db);
        tabs.addTab("SQL Console", sqlConsole);

        root.add(tabs, BorderLayout.CENTER);

        refreshTable();

        setVisible(true);
    }

    // ===================== МЕНЮ =====================

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        bar.setBorder(new EmptyBorder(4, 8, 4, 8));
        bar.setBackground(new Color(15, 23, 42, 230));

        JMenu fileMenu = new JMenu("Файл");
        fileMenu.setForeground(TEXT_PRIMARY);
        fileMenu.setFont(new Font("Ostrovsky", Font.PLAIN, 13));

        JMenuItem newItem = new JMenuItem("Создать БД...");
        JMenuItem openItem = new JMenuItem("Открыть БД...");
        JMenuItem saveItem = new JMenuItem("Сохранить БД");
        JMenuItem deleteItem = new JMenuItem("Удалить текущую БД");
        JMenuItem clearItem = new JMenuItem("Очистить БД");
        JMenuItem exportItem = new JMenuItem("Экспорт в CSV...");
        JMenuItem exitItem = new JMenuItem("Выход");

        for (JMenuItem mi : new JMenuItem[]{newItem, openItem, saveItem, deleteItem, clearItem, exportItem, exitItem}) {
            mi.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        }

        newItem.addActionListener(e -> onCreateDb());
        openItem.addActionListener(e -> onOpenDb());
        saveItem.addActionListener(e -> onSaveDb());
        deleteItem.addActionListener(e -> onDeleteDb());
        clearItem.addActionListener(e -> onClearDb());
        exportItem.addActionListener(e -> onExportCsv());
        exitItem.addActionListener(e -> dispose());

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.addSeparator();
        fileMenu.add(deleteItem);
        fileMenu.add(clearItem);
        fileMenu.addSeparator();
        fileMenu.add(exportItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        bar.add(fileMenu);
        return bar;
    }

    // ===================== ОСНОВНАЯ ПАНЕЛЬ =====================

    private JPanel createMainPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(12, 12)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // делаем фон слегка прозрачной тёмной карточкой
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(15, 23, 42, 160));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 26, 26);
                g2.dispose();
            }
        };
        mainPanel.setOpaque(false);
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        // --- Заголовок ---
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JLabel title = new JLabel("Панель управления");
        title.setFont(new Font("Ostrovsky", Font.PLAIN, 24));
        title.setForeground(TEXT_PRIMARY);

        JLabel subtitle = new JLabel("Учёт товаров: поставки, продажи, мониторинг");
        subtitle.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        subtitle.setForeground(TEXT_MUTED);

        JPanel titleBox = new JPanel();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.setOpaque(false);
        titleBox.add(title);
        titleBox.add(subtitle);

        JButton refreshBtn = createSecondaryButton("Обновить");
        refreshBtn.addActionListener(e -> refreshTable());

        header.add(titleBox, BorderLayout.WEST);
        header.add(refreshBtn, BorderLayout.EAST);

        mainPanel.add(header, BorderLayout.NORTH);

        // --- Карточка с таблицей ---
        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.setOpaque(false);
        tableCard.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel tableInner = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                // лёгкий градиент на карточке
                GradientPaint gp = new GradientPaint(
                        0, 0, new Color(15, 23, 42, 230),
                        getWidth(), getHeight(), new Color(15, 23, 42, 200)
                );
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(CARD_BORDER);
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                g2.dispose();
            }
        };
        tableInner.setOpaque(false);
        tableInner.setBorder(new EmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setOpaque(false);

        tableInner.add(scrollPane, BorderLayout.CENTER);
        tableCard.add(tableInner, BorderLayout.CENTER);

        mainPanel.add(tableCard, BorderLayout.CENTER);

        // --- Панель кнопок ---
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        buttons.setOpaque(false);

        JButton addBtn = createPrimaryButton("Добавить");
        JButton editBtn = createSecondaryButton("Редактировать");
        JButton supplyBtn = createSecondaryButton("Поставка");
        JButton sellBtn = createSecondaryButton("Продажа");
        JButton deleteBtn = createDangerButton("Удалить по ID");
        JButton deleteByFieldBtn = createDangerButton("Удалить по полю");
        JButton searchBtn = createSecondaryButton("Поиск");
        JButton backupBtn = createSecondaryButton("Backup");
        JButton restoreBtn = createSecondaryButton("Restore");

        buttons.add(addBtn);
        buttons.add(editBtn);
        buttons.add(supplyBtn);
        buttons.add(sellBtn);
        buttons.add(deleteBtn);
        buttons.add(deleteByFieldBtn);
        buttons.add(Box.createHorizontalStrut(20));
        buttons.add(searchBtn);
        buttons.add(Box.createHorizontalStrut(20));
        buttons.add(backupBtn);
        buttons.add(restoreBtn);

        mainPanel.add(buttons, BorderLayout.SOUTH);

        // --- Обработчики кнопок ---
        addBtn.addActionListener(e -> addRecord());
        editBtn.addActionListener(e -> editSelectedRecord());
        supplyBtn.addActionListener(e -> supply());
        sellBtn.addActionListener(e -> sell());
        deleteBtn.addActionListener(e -> deleteRecordById());
        deleteByFieldBtn.addActionListener(e -> deleteByField());
        searchBtn.addActionListener(e -> search());
        backupBtn.addActionListener(e -> backup());
        restoreBtn.addActionListener(e -> restore());

        return mainPanel;
    }

    // ===================== СТИЛИЗАЦИЯ ТАБЛИЦЫ И КНОПОК =====================

    private void styleTable(JTable table) {
        table.setFillsViewportHeight(true);
        table.setRowHeight(28);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFont(new Font("Ostrovsky", Font.PLAIN, 12));
        table.setBackground(new Color(15, 23, 42, 220));
        table.setForeground(TEXT_PRIMARY);
        table.setSelectionBackground(new Color(56, 189, 248, 130));
        table.setSelectionForeground(Color.WHITE);

        // полосатые строки
        table.setDefaultRenderer(Object.class, (tbl, value, isSelected, hasFocus, row, column) -> {
            Component c = new DefaultTableCellRenderer()
                    .getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                if (row % 2 == 0) {
                    c.setBackground(new Color(15, 23, 42, 230));
                } else {
                    c.setBackground(TABLE_ROW_ALT);
                }
                c.setForeground(TEXT_PRIMARY);
            }
            return c;
        });

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setFont(new Font("Ostrovsky", Font.PLAIN, 13));
        header.setBackground(new Color(24, 31, 56));
        header.setForeground(TEXT_PRIMARY);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, CARD_BORDER));
    }

    private JButton createPrimaryButton(String text) {
        return configureButton(text, ACCENT, new Color(15, 23, 42));
    }

    private JButton createSecondaryButton(String text) {
        return configureButton(text, new Color(31, 41, 55), TEXT_PRIMARY);
    }

    private JButton createDangerButton(String text) {
        return configureButton(text, DANGER, Color.WHITE);
    }

    private JButton configureButton(String text, Color bg, Color fg) {
        JButton button = new JButton(text);
        button.setFont(new Font("Ostrovsky", Font.PLAIN, 13));
        button.setForeground(fg);
        button.setBackground(bg);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setContentAreaFilled(false);
        button.setOpaque(false);

        // собственная отрисовка: «капсула»
        button = new JButton(text) {
            private boolean hovered = false;

            {
                setFont(new Font("Ostrovsky", Font.PLAIN, 13));
                setForeground(fg);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
                setFocusPainted(false);
                setContentAreaFilled(false);
                setOpaque(false);

                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseEntered(java.awt.event.MouseEvent e) {
                        hovered = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        hovered = false;
                        repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                Color base = bg;
                if (hovered) {
                    base = base.brighter();
                }

                g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 200));
                int arc = getHeight();
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

                // лёгкое свечение
                g2.setColor(new Color(255, 255, 255, hovered ? 80 : 40));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc, arc);

                super.paintComponent(g);
                g2.dispose();
            }
        };

        button.setText(text);
        button.setForeground(fg);
        return button;
    }

    // ===================== РАБОТА С БД (как раньше) =====================

    private void refreshTable() {
        tableModel.setRowCount(0);
        if (db == null) return;

        try {
            List<ProductEntry> all = db.getAll();
            for (ProductEntry r : all) {
                tableModel.addRow(new Object[]{r.id, r.name, r.quantity, r.price, r.supplier});
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка чтения БД: " + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void onDatabaseChanged() {
        SwingUtilities.invokeLater(() -> {
            refreshTable();
            if (monitoring != null) {
                monitoring.refresh();
            }
        });
    }

    // ---- Методы меню ----

    private void onCreateDb() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Создать новую БД");
        if (currentFile != null) chooser.setSelectedFile(currentFile);
        int res = chooser.showSaveDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) return;

        File f = chooser.getSelectedFile();
        try {
            db.createNew(f.getAbsolutePath());
            currentFile = f;
            setTitle("Учёт товаров магазина — " + f.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка создания БД: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onOpenDb() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Открыть БД");
        if (currentFile != null) chooser.setSelectedFile(currentFile);
        int res = chooser.showOpenDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) return;

        File f = chooser.getSelectedFile();
        try {
            db.openOrCreate(f.getAbsolutePath());
            currentFile = f;
            setTitle("Учёт товаров магазина — " + f.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка открытия БД: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onSaveDb() {
        try {
            db.save();
            JOptionPane.showMessageDialog(this, "БД сохранена");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка сохранения: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onDeleteDb() {
        if (currentFile == null) return;
        int ans = JOptionPane.showConfirmDialog(this,
                "Удалить файл БД " + currentFile.getName() + "?",
                "Подтверждение", JOptionPane.YES_NO_OPTION);
        if (ans != JOptionPane.YES_OPTION) return;

        try {
            db.deleteDatabase();
            tableModel.setRowCount(0);
            currentFile = null;
            setTitle("Учёт товаров магазина — <нет файла>");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка удаления БД: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onClearDb() {
        int ans = JOptionPane.showConfirmDialog(this,
                "Очистить все записи в текущей БД?",
                "Подтверждение", JOptionPane.YES_NO_OPTION);
        if (ans != JOptionPane.YES_OPTION) return;

        try {
            db.clear();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка очистки БД: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onExportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Экспорт в CSV");
        int res = chooser.showSaveDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) return;

        File f = chooser.getSelectedFile();
        try {
            db.exportToCsv(f.getAbsolutePath());
            JOptionPane.showMessageDialog(this, "Экспорт выполнен успешно");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка экспорта: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- Кнопки CRUD / операции ----
    // (всё как раньше, просто вызовы к db)

    private void addRecord() {
        JTextField id = new JTextField();
        JTextField name = new JTextField();
        JTextField qty = new JTextField();
        JTextField price = new JTextField();
        JTextField sup = new JTextField();

        Object[] fields = {
                "ID клиента:", id,
                "ФИО клиента:", name,
                "Остаток занятий:", qty,
                "Стоимость абонемента:", price,
                "Тип абонемента:", sup
        };

        int res = JOptionPane.showConfirmDialog(this, fields,
                "Добавить клиента", JOptionPane.OK_CANCEL_OPTION);

        if (res == JOptionPane.OK_OPTION) {
            try {
                ProductEntry r = new ProductEntry(
                        Integer.parseInt(id.getText()),
                        name.getText(),
                        Integer.parseInt(qty.getText()),
                        Double.parseDouble(price.getText()),
                        sup.getText()
                );

                if (!db.addRecord(r)) {
                    JOptionPane.showMessageDialog(this, "ID уже существует или данные некорректны!");
                    return;
                }

                db.save();

            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Ошибка в числовых полях!");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Ошибка сохранения: " + ex.getMessage());
            }
        }
    }

    private void editSelectedRecord() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Выберите строку для редактирования");
            return;
        }

        int idValue = (Integer) tableModel.getValueAt(row, 0);
        String nameValue = (String) tableModel.getValueAt(row, 1);
        int qtyValue = (Integer) tableModel.getValueAt(row, 2);
        double priceValue = (Double) tableModel.getValueAt(row, 3);
        String supplierValue = (String) tableModel.getValueAt(row, 4);

        JTextField id = new JTextField(String.valueOf(idValue));
        JTextField name = new JTextField(nameValue);
        JTextField qty = new JTextField(String.valueOf(qtyValue));
        JTextField price = new JTextField(String.valueOf(priceValue));
        JTextField sup = new JTextField(supplierValue);

        Object[] fields = {
                "ID клиента:", id,
                "ФИО клиента:", name,
                "Остаток занятий:", qty,
                "Стоимость абонемента:", price,
                "Тип абонемента:", sup
        };

        int res = JOptionPane.showConfirmDialog(this, fields,
                "Редактировать клиента", JOptionPane.OK_CANCEL_OPTION);

        if (res == JOptionPane.OK_OPTION) {
            try {
                int newId = Integer.parseInt(id.getText());
                String newName = name.getText();
                int newQty = Integer.parseInt(qty.getText());
                double newPrice = Double.parseDouble(price.getText());
                String newSup = sup.getText();

                db.update("id", String.valueOf(newId), "id", String.valueOf(idValue));
                db.update("name", newName, "id", String.valueOf(newId));
                db.update("quantity", String.valueOf(newQty), "id", String.valueOf(newId));
                db.update("price", String.valueOf(newPrice), "id", String.valueOf(newId));
                db.update("supplier", newSup, "id", String.valueOf(newId));

                db.save();
                refreshTable();

            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Ошибка в числовых полях!");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Ошибка сохранения: " + ex.getMessage());
            }
        }
    }

    private void supply() {
        JTextField id = new JTextField();
        JTextField amount = new JTextField();

        Object[] fields = {
                "ID товара:", id,
                "Количество поставки:", amount
        };

        int res = JOptionPane.showConfirmDialog(this, fields,
                "Поставка товара", JOptionPane.OK_CANCEL_OPTION);

        if (res == JOptionPane.OK_OPTION) {
            try {
                if (db.supply(Integer.parseInt(id.getText()),
                        Integer.parseInt(amount.getText()))) {
                    db.save();
                } else {
                    JOptionPane.showMessageDialog(this, "Товар не найден");
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Ошибка в числовых полях!");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Ошибка сохранения: " + ex.getMessage());
            }
        }
    }

    private void sell() {
        JTextField id = new JTextField();
        JTextField amount = new JTextField();

        Object[] fields = {
                "ID товара:", id,
                "Количество продажи:", amount
        };

        int res = JOptionPane.showConfirmDialog(this, fields,
                "Продажа товара", JOptionPane.OK_CANCEL_OPTION);

        if (res == JOptionPane.OK_OPTION) {
            try {
                if (!db.sell(Integer.parseInt(id.getText()),
                        Integer.parseInt(amount.getText()))) {
                    JOptionPane.showMessageDialog(this,
                            "Недостаточно товара или товар не найден");
                } else {
                    db.save();
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Ошибка в числовых полях!");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Ошибка сохранения: " + ex.getMessage());
            }
        }
    }

    private void deleteRecordById() {
        String idStr = JOptionPane.showInputDialog(this, "Введите ID для удаления:");
        if (idStr == null) return;

        try {
            int id = Integer.parseInt(idStr);

            if (!db.deleteById(id)) {
                JOptionPane.showMessageDialog(this, "Товар не найден");
                return;
            }

            db.save();

        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Неверный формат ID!");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Ошибка сохранения: " + ex.getMessage());
        }
    }

    private void deleteByField() {
        String field = JOptionPane.showInputDialog(this,
                "Поле (id/name/quantity/price/supplier)\n" +
                        "id – ID клиента\n" +
                        "name – ФИО\n" +
                        "quantity – остаток занятий\n" +
                        "price – цена абонемента\n" +
                        "supplier – тип абонемента:");
        String value = JOptionPane.showInputDialog(this, "Значение:");

        if (field == null || value == null) return;

        try {
            int deleted = db.deleteWhere(field, value);
            db.save();
            JOptionPane.showMessageDialog(this, "Удалено записей: " + deleted);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка удаления: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void search() {
        String field = JOptionPane.showInputDialog(this,
                "Поле (id/name/quantity/price/supplier)\n" +
                        "id – ID клиента\n" +
                        "name – ФИО\n" +
                        "quantity – остаток занятий\n" +
                        "price – цена абонемента\n" +
                        "supplier – тип абонемента:");
        String value = JOptionPane.showInputDialog(this, "Значение:");

        if (field == null || value == null) return;

        try {
            List<ProductEntry> results = db.search(field, value);

            tableModel.setRowCount(0);
            for (ProductEntry r : results) {
                tableModel.addRow(new Object[]{r.id, r.name, r.quantity, r.price, r.supplier});
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка поиска: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void backup() {
        try {
            db.backup("products_backup.db");
            JOptionPane.showMessageDialog(this, "Backup создан успешно");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ошибка backup: " + e.getMessage());
        }
    }

    private void restore() {
        try {
            db.restore("products_backup.db");
            JOptionPane.showMessageDialog(this, "Restore выполнен успешно");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ошибка restore: " + e.getMessage());
        }
    }
}