package ru.open.cu.student.catalog.operation;

import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.memory.page.HeapPage;
import ru.open.cu.student.io.FileAccessor;
import ru.open.cu.student.io.DiskFileAccessor;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultOperationManager implements OperationManager {
    private static final int PAGE_SIZE = 8192;
    private final CatalogManager catalogManager;
    private final FileAccessor fileAccessor;

    // Cache last known writable page per table OID (to avoid scanning file each insert)
    private final Map<Integer, Integer> lastWritablePage = new HashMap<>(); // tableOid -> pageId
    private final Map<Integer, Integer> lastWritableFree = new HashMap<>(); // tableOid -> available bytes for payload

    // For tests/diagnostics: how many page checks (scans) we did per table
    private final Map<Integer, AtomicInteger> scanCounter = new HashMap<>();

    public DefaultOperationManager(CatalogManager catalogManager) {
        this(catalogManager, new DiskFileAccessor());
    }

    // New constructor for testability
    public DefaultOperationManager(CatalogManager catalogManager, FileAccessor fileAccessor) {
        this.catalogManager = catalogManager;
        this.fileAccessor = fileAccessor;
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

        Path path = resolveDataPath(table);

        // Ensure file exists (create initial empty page if needed)
        try {
            fileAccessor.ensureFileExistsWithEmptyPage(path, PAGE_SIZE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to ensure data file: " + path, e);
        }

        boolean written = false;
        int tableOid = table.getOid();
        scanCounter.computeIfAbsent(tableOid, k -> new AtomicInteger(0));

        try {
            int pagesCount = fileAccessor.getPageCount(path, PAGE_SIZE);

            // 1) Try cached page first (only if hint indicates it may fit)
            Integer cachedPage = lastWritablePage.get(tableOid);
            Integer cachedFree = lastWritableFree.get(tableOid);
            if (cachedPage != null && cachedPage >= 0 && cachedPage < Math.max(1, pagesCount)) {
                if (cachedFree != null && cachedFree >= rowData.length) {
                    int pid = cachedPage;
                    byte[] page = fileAccessor.readPage(path, pid, PAGE_SIZE);
                    HeapPage hp = new HeapPage(pid, page);
                    if (!hp.isValid()) hp = new HeapPage(pid);
                    try {
                        hp.write(rowData);
                        fileAccessor.writePage(path, pid, hp.bytes());
                        written = true;
                        // update free hint
                        int newFree = computeFreeSpace(hp.bytes());
                        lastWritableFree.put(tableOid, newFree);
                        lastWritablePage.put(tableOid, pid);
                    } catch (IllegalArgumentException ignored) {
                        // cache miss — will fallback to scan
                        lastWritablePage.remove(tableOid);
                        lastWritableFree.remove(tableOid);
                    }
                } else {
                    // Hint says it won't fit, skip direct attempt
                }
            }

            // 2) Scan pages if cache miss
            if (!written) {
                for (int pid = 0; pid < pagesCount; pid++) {
                    // count this page check
                    scanCounter.get(tableOid).incrementAndGet();

                    byte[] page = fileAccessor.readPage(path, pid, PAGE_SIZE);

                    HeapPage hp = new HeapPage(pid, page);
                    if (!hp.isValid()) {
                        hp = new HeapPage(pid);
                    }

                    try {
                        hp.write(rowData);
                        fileAccessor.writePage(path, pid, hp.bytes());
                        written = true;
                        // update cache
                        int free = computeFreeSpace(hp.bytes());
                        lastWritablePage.put(tableOid, pid);
                        lastWritableFree.put(tableOid, free);
                        break;
                    } catch (IllegalArgumentException ignored) {
                        // not enough space on this page, try next
                    }
                }
            }

            // 3) Append new page if still not written
            if (!written) {
                int newId;
                HeapPage newPage = new HeapPage(0);
                newPage.write(rowData); // should fit into empty page
                newId = fileAccessor.appendNewPage(path, newPage.bytes());
                written = true;
                int free = computeFreeSpace(newPage.bytes());
                lastWritablePage.put(tableOid, newId);
                lastWritableFree.put(tableOid, free);
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to write page for table " + tableName, e);
        }

        if (!written) {
            throw new IllegalStateException("No space in page");
        }
    }

    // helper: compute available payload bytes for a page
    private int computeFreeSpace(byte[] page) {
        ByteBuffer buf = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        int lower = buf.getShort(6) & 0xFFFF;
        int upper = buf.getShort(8) & 0xFFFF;
        int free = (upper - lower) - 4; // need 4 bytes for slot
        return Math.max(0, free);
    }

    // Expose scan counter for tests
    public int getScanCountForTable(int tableOid) {
        AtomicInteger ai = scanCounter.get(tableOid);
        return ai == null ? 0 : ai.get();
    }

    private byte[] serializeRow(List<Object> values, List<ColumnDefinition> columns) {
        // conservative maximum payload that can fit into an empty page:
        // HeapPage initial: lower=10, upper=PAGE_SIZE -> usable = PAGE_SIZE - 10
        // we also need 4 bytes for slot entry, so max payload ~= PAGE_SIZE - 14
        final int MAX_PAYLOAD = PAGE_SIZE - 14;

        // First pass: compute required payload size
        int required = 0;
        for (int i = 0; i < values.size(); i++) {
            TypeDefinition type = getType(columns.get(i).getTypeOid());
            String tn = type.name().toLowerCase();
            switch (tn) {
                case "integer": required += Integer.BYTES; break;
                case "bigint": required += Long.BYTES; break;
                case "boolean": required += 1; break;
                case "varchar": {
                    String s = values.get(i) == null ? "" : values.get(i).toString();
                    int len = s.getBytes(StandardCharsets.UTF_8).length;
                    required += 2 + len; // short length + bytes
                    break;
                }
                default: required += 0; break;
            }
        }

        if (required > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Row is too large to fit in a single page (" + required + " bytes)");
        }

        ByteBuffer buffer = ByteBuffer.allocate(required).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            TypeDefinition type = getType(columns.get(i).getTypeOid());
            String tn = type.name().toLowerCase();
            switch (tn) {
                case "integer":
                    buffer.putInt((Integer) value);
                    break;
                case "bigint":
                    buffer.putLong((Long) value);
                    break;
                case "boolean":
                    buffer.put((byte) ((Boolean) value ? 1 : 0));
                    break;
                case "varchar": {
                    String str = value == null ? "" : value.toString();
                    byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
                    buffer.putShort((short) strBytes.length);
                    buffer.put(strBytes);
                    break;
                }
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

        Path path = resolveDataPath(table);

        try {
            int pagesCount = fileAccessor.getPageCount(path, PAGE_SIZE);

            for (int pid = 0; pid < pagesCount; pid++) {
                byte[] pageBytes = fileAccessor.readPage(path, pid, PAGE_SIZE);

                HeapPage page = new HeapPage(pid, pageBytes);
                if (page.isValid()) {
                    result.addAll(readHeapPageRows(page, selectedColumns, allColumns));
                } else {
                    // fallback на старый формат (int size + payload)*
                    result.addAll(readPageRows(pageBytes, selectedColumns, allColumns));
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to read data file for table " + tableName, e);
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
        try {
            byte[] page = fileAccessor.readPage(path, 0, PAGE_SIZE);

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

        try {
            fileAccessor.writePage(path, 0, page);
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
