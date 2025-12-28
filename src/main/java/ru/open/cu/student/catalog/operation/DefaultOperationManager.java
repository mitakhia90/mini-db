package ru.open.cu.student.catalog.operation;

import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.memory.page.HeapPage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class DefaultOperationManager implements OperationManager {
    private static final int PAGE_SIZE = 8192;
    private final CatalogManager catalogManager;

    public DefaultOperationManager(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
    }

    @Override
    public void insert(String tableName, List<Object> values) {
        TableDefinition table = catalogManager.getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }

        List<ColumnDefinition> columns = getTableColumns(table);
        if (values.size() != columns.size()) {
            throw new IllegalArgumentException("Parameter count mismatch");
        }

        byte[] rowData = serializeRow(values, columns);

        byte[] pageBytes = readPage(table);
        HeapPage page = new HeapPage(0, pageBytes);
        if (!page.isValid()) {
            page = new HeapPage(0);
            pageBytes = page.bytes();
        }

        try {
            page.write(rowData);
        } catch (IllegalArgumentException e) {
            // сохраняем внешний контракт/сообщение для CLI
            throw new IllegalStateException("No space in page");
        }

        // ВАЖНО: HeapPage.write() изменяет внутренний ByteBuffer, который обёрнут над pageBytes,
        // поэтому pageBytes теперь содержит актуальные данные. Писать нужно именно его.
        writePage(table, pageBytes);
    }

    private byte[] serializeRow(List<Object> values, List<ColumnDefinition> columns) {
        ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            TypeDefinition type = getType(columns.get(i).getTypeOid());

            switch (type.name().toLowerCase()) {
                case "integer":
                    buffer.putInt((Integer) value);
                    break;
                case "bigint":
                    buffer.putLong((Long) value);
                    break;
                case "boolean":
                    buffer.put((byte) ((Boolean) value ? 1 : 0));
                    break;
                case "varchar":
                    String str = (String) value;
                    byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
                    buffer.putShort((short) strBytes.length);
                    buffer.put(strBytes);
                    break;
            }
        }

        byte[] result = new byte[buffer.position()];
        System.arraycopy(buffer.array(), 0, result, 0, buffer.position());
        return result;
    }

    private List<Object> deserializeRow(ByteBuffer buffer, List<ColumnDefinition> columns) {
        List<Object> row = new ArrayList<>();

        for (ColumnDefinition column : columns) {
            TypeDefinition type = getType(column.getTypeOid());

            switch (type.name().toLowerCase()) {
                case "integer":
                    row.add(buffer.getInt());
                    break;
                case "bigint":
                    row.add(buffer.getLong());
                    break;
                case "boolean":
                    row.add(buffer.get() != 0);
                    break;
                case "varchar":
                    int len = buffer.getShort() & 0xFFFF;
                    byte[] strBytes = new byte[len];
                    buffer.get(strBytes);
                    row.add(new String(strBytes, StandardCharsets.UTF_8));
                    break;
            }
        }

        return row;
    }

    private List<Object> readHeapPageRows(HeapPage page, List<ColumnDefinition> selectedColumns,
                                         List<ColumnDefinition> allColumns) {
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            byte[] rowBytes = page.read(i);
            ByteBuffer rowBuf = ByteBuffer.wrap(rowBytes).order(ByteOrder.LITTLE_ENDIAN);
            List<Object> row = deserializeRow(rowBuf, allColumns);

            if (!selectedColumns.equals(allColumns)) {
                List<Object> filteredRow = new ArrayList<>();
                for (int c = 0; c < allColumns.size(); c++) {
                    if (selectedColumns.contains(allColumns.get(c))) {
                        filteredRow.add(row.get(c));
                    }
                }
                rows.add(filteredRow);
            } else {
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public List<Object> select(String tableName, List<String> columnNames) {
        TableDefinition table = catalogManager.getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }

        List<Object> result = new ArrayList<>();
        List<ColumnDefinition> allColumns = getTableColumns(table);
        List<ColumnDefinition> selectedColumns = columnNames.isEmpty() ?
                allColumns : getSelectedColumns(allColumns, columnNames);

        byte[] pageBytes = readPage(table);
        HeapPage page = new HeapPage(0, pageBytes);
        if (page.isValid()) {
            result.addAll(readHeapPageRows(page, selectedColumns, allColumns));
        } else {
            // fallback на старый формат (int size + payload)*
            result.addAll(readPageRows(pageBytes, selectedColumns, allColumns));
        }


        return result;
    }

    private List<Object> readPageRows(byte[] page, List<ColumnDefinition> selectedColumns,
                                      List<ColumnDefinition> allColumns) {
        List<Object> rows = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);

        while (buffer.position() < PAGE_SIZE - 4) {
            int currentPos = buffer.position();
            int rowSize = buffer.getInt();

            if (rowSize == 0 || buffer.position() + rowSize > PAGE_SIZE) {
                break;
            }

            List<Object> row = deserializeRow(buffer, allColumns);

            if (!selectedColumns.equals(allColumns)) {
                List<Object> filteredRow = new ArrayList<>();
                for (int i = 0; i < allColumns.size(); i++) {
                    if (selectedColumns.contains(allColumns.get(i))) {
                        filteredRow.add(row.get(i));
                    }
                }
                rows.add(filteredRow);
            } else {
                rows.add(row);
            }

            // Ensure we read exactly rowSize bytes
            if (buffer.position() != currentPos + 4 + rowSize) {
                buffer.position(currentPos + 4 + rowSize);
            }
        }

        return rows;
    }

    private byte[] readPage(TableDefinition table) {
        Path path = resolveDataPath(table);
        File file = path.toFile();
        if (!file.exists()) {
            return createEmptyHeapPageBytes();
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (0 >= raf.length()) {
                // page beyond file size -> return empty initialized page
                return createEmptyHeapPageBytes();
            }

            raf.seek(0);
            byte[] page = new byte[PAGE_SIZE];
            int bytesRead = raf.read(page);

            if (bytesRead < PAGE_SIZE) {
                // Fill remaining with zeros
                Arrays.fill(page, bytesRead, PAGE_SIZE, (byte) 0);
            }

            // If page doesn't have valid HeapPage signature, initialize header so later readers (HeapPage) won't fail
            ByteBuffer buf = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
            int sig = buf.getInt(0);
            if (sig != 0xDBDB01) {
                // initialize header fields similarly to HeapPage constructor:
                buf.putInt(0, 0xDBDB01);
                buf.putShort(4, (short) 0);             // size = 0 (no records yet)
                buf.putShort(6, (short) 10);            // lower = HEADER_SIZE (10)
                buf.putShort(8, (short) PAGE_SIZE);     // upper = PAGE_SIZE
                // copy back
                System.arraycopy(buf.array(), 0, page, 0, PAGE_SIZE);
            }


            return page;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read page", e);
        }
    }

    private byte[] createEmptyHeapPageBytes() {
        byte[] page = new byte[PAGE_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, 0xDBDB01);
        buf.putShort(4, (short) 0);          // size = 0
        buf.putShort(6, (short) 10);         // lower = HEADER_SIZE (10)
        buf.putShort(8, (short) PAGE_SIZE);  // upper = PAGE_SIZE
        return page;
    }

    private void writePage(TableDefinition table, byte[] page) {
        Path path = resolveDataPath(table);

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(0);
            raf.write(page);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write page", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ColumnDefinition> getTableColumns(TableDefinition table) {
        try {
            java.lang.reflect.Method method = catalogManager.getClass()
                    .getMethod("getTableColumns", TableDefinition.class);
            return (List<ColumnDefinition>) method.invoke(catalogManager, table);
        } catch (Exception e) {
            throw new RuntimeException("Cannot get table columns", e);
        }
    }

    private TypeDefinition getType(int typeOid) {
        try {
            java.lang.reflect.Method method = catalogManager.getClass()
                    .getMethod("getType", int.class);
            return (TypeDefinition) method.invoke(catalogManager, typeOid);
        } catch (Exception e) {
            return new TypeDefinition(typeOid, "integer", 4);
        }
    }

    private List<ColumnDefinition> getSelectedColumns(List<ColumnDefinition> allColumns,
                                                      List<String> columnNames) {
        List<ColumnDefinition> selected = new ArrayList<>();
        for (String colName : columnNames) {
            for (ColumnDefinition col : allColumns) {
                if (col.getName().equalsIgnoreCase(colName)) {
                    selected.add(col);
                    break;
                }
            }
        }
        return selected;
    }

    private Path resolveDataPath(TableDefinition table) {
        String fileNode = table.getFileNode();
        if (fileNode == null || fileNode.isBlank()) {
            fileNode = table.getOid() + ".dat";
        }
        return Paths.get(fileNode).toAbsolutePath();
    }
}
