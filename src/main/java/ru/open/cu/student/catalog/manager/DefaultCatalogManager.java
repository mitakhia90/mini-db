package ru.open.cu.student.catalog.manager;

import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.memory.page.HeapPage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class DefaultCatalogManager implements CatalogManager {
    private static final int PAGE_SIZE = 8192;
    private static final String TABLE_FILE = "table_definitions.dat";
    private static final String COLUMN_FILE = "column_definitions.dat";
    private static final String TYPE_FILE = "types_definitions.dat";

    private final AtomicInteger nextTableOid = new AtomicInteger(1);
    private final AtomicInteger nextColumnOid = new AtomicInteger(1);
    private final AtomicInteger nextTypeOid = new AtomicInteger(1);

    private final Map<Integer, TableDefinition> tables = new ConcurrentHashMap<>();
    private final Map<Integer, List<ColumnDefinition>> tableColumns = new ConcurrentHashMap<>();
    private final Map<Integer, TypeDefinition> types = new ConcurrentHashMap<>();
    private final Map<String, Integer> tableNameToOid = new ConcurrentHashMap<>();

    public DefaultCatalogManager() {
        loadCatalog();
        initializeDefaultTypes();
    }

    @Override
    public TableDefinition createTable(String name, List<ColumnDefinition> columns) {
        if (tableNameToOid.containsKey(name.toLowerCase())) {
            throw new IllegalArgumentException("Table already exists: " + name);
        }

        int tableOid = nextTableOid.getAndIncrement();

        // Создаем определение таблицы
        TableDefinition table = new TableDefinition(
                tableOid, name, "table", tableOid + ".dat", 0
        );

        // Сохраняем таблицу
        tables.put(tableOid, table);
        tableNameToOid.put(name.toLowerCase(), tableOid);
        saveRecord(TABLE_FILE, table.toBytes());

        // Сохраняем колонки
        List<ColumnDefinition> tableColumnsList = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition originalColumn = columns.get(i);
            ColumnDefinition column = new ColumnDefinition(
                    nextColumnOid.getAndIncrement(),
                    tableOid,
                    originalColumn.getTypeOid(),
                    originalColumn.getName(),
                    i
            );
            tableColumnsList.add(column);
            saveRecord(COLUMN_FILE, column.toBytes());
        }

        tableColumns.put(tableOid, tableColumnsList);
        createDataFile(tableOid);

        return table;
    }

    @Override
    public TableDefinition getTable(String tableName) {
        Integer oid = tableNameToOid.get(tableName.toLowerCase());
        return oid != null ? tables.get(oid) : null;
    }

    @Override
    public ColumnDefinition getColumn(TableDefinition table, String columnName) {
        if (table == null) return null;

        List<ColumnDefinition> columns = tableColumns.get(table.getOid());
        if (columns == null) return null;

        return columns.stream()
                .filter(col -> col.getName().equalsIgnoreCase(columnName))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<TableDefinition> listTables() {
        return tables.values().stream()
                .sorted(Comparator.comparing(TableDefinition::getName))
                .collect(Collectors.toList());
    }

    private void loadCatalog() {
        loadFromFile(TABLE_FILE, this::loadTable);
        loadFromFile(COLUMN_FILE, this::loadColumn);
        loadFromFile(TYPE_FILE, this::loadType);

        // Обновляем счетчики OID
        nextTableOid.set(tables.keySet().stream().max(Integer::compareTo).orElse(0) + 1);
        nextColumnOid.set(tableColumns.values().stream()
                .flatMap(List::stream)
                .mapToInt(ColumnDefinition::getOid)
                .max().orElse(0) + 1);
        nextTypeOid.set(types.keySet().stream().max(Integer::compareTo).orElse(0) + 1);
    }

    private void loadFromFile(String filename, RecordLoader loader) {
        File file = new File(filename);
        if (!file.exists()) return;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] page = new byte[PAGE_SIZE];

            while (raf.read(page) != -1) {
                loadRecordsFromPage(page, loader);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load catalog from " + filename, e);
        }
    }

    private void loadRecordsFromPage(byte[] page, RecordLoader loader) {
        ByteBuffer buffer = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);

        while (buffer.remaining() >= 4) {
            int recordSize = buffer.getInt();
            if (recordSize == 0 || recordSize > buffer.remaining()) break;

            byte[] recordData = new byte[recordSize];
            buffer.get(recordData);

            try {
                loader.load(recordData);
            } catch (Exception e) {
                System.err.println("Failed to load record: " + e.getMessage());
            }
        }
    }

    public TypeDefinition getType(int typeOid) {
        return types.get(typeOid);
    }

    private void saveRecord(String filename, byte[] record) {
        byte[] recordWithSize = addRecordSize(record);

        try (RandomAccessFile raf = new RandomAccessFile(filename, "rw")) {
            long position = findFreeSpace(raf, recordWithSize.length);
            raf.seek(position);
            raf.write(recordWithSize);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save record to " + filename, e);
        }
    }

    private byte[] addRecordSize(byte[] record) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + record.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(record.length)
                .put(record);
        return buffer.array();
    }

    private long findFreeSpace(RandomAccessFile raf, int requiredSize) throws IOException {
        long fileSize = raf.length();
        long lastPageStart = fileSize - (fileSize % PAGE_SIZE);

        if (fileSize % PAGE_SIZE + requiredSize <= PAGE_SIZE) {
            return fileSize;
        }

        for (long pos = 0; pos < fileSize; pos += PAGE_SIZE) {
            raf.seek(pos);
            byte[] page = new byte[PAGE_SIZE];
            int read = raf.read(page);
            if (read == -1) break;

            long freePos = findFreeInPage(page, requiredSize);
            if (freePos != -1) return pos + freePos;
        }

        return lastPageStart + PAGE_SIZE;
    }

    private long findFreeInPage(byte[] page, int requiredSize) {
        ByteBuffer buffer = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        long currentPos = 0;

        while (currentPos + 4 <= PAGE_SIZE) {
            buffer.position((int) currentPos);
            int recordSize = buffer.getInt();

            if (recordSize == 0) {
                if (PAGE_SIZE - currentPos >= requiredSize) {
                    return currentPos;
                }
                break;
            }

            currentPos += 4 + recordSize;
            if (currentPos > PAGE_SIZE) break;
        }

        return -1;
    }

    private void createDataFile(int oid) {
        String filename = oid + ".dat";
        File file = new File(filename);
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                if (raf.length() == 0) {
                    raf.write(createEmptyHeapPage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data file: " + filename, e);
        }
    }

    private byte[] createEmptyHeapPage() {
        byte[] page = new byte[HeapPage.PAGE_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, 0xDBDB01);
        buf.putShort(4, (short) 0);
        buf.putShort(6, (short) 10);
        buf.putShort(8, (short) HeapPage.PAGE_SIZE);
        return page;
    }

    private void loadTable(byte[] data) {
        TableDefinition table = TableDefinition.fromBytes(data);
        tables.put(table.getOid(), table);
        tableNameToOid.put(table.getName().toLowerCase(), table.getOid());
    }

    private void loadColumn(byte[] data) {
        ColumnDefinition column = ColumnDefinition.fromBytes(data);
        tableColumns.computeIfAbsent(column.getTableOid(), k -> new ArrayList<>())
                .add(column);
    }

    private void loadType(byte[] data) {
        TypeDefinition type = TypeDefinition.fromBytes(data);
        types.put(type.oid(), type);
    }

    private void initializeDefaultTypes() {
        // Всегда проверяем и создаем (если отсутствуют) стандартные типы с OID-ами,
        // которые использует DefaultParser.mapTypeNameToOid
        // OID: INTEGER=23, VARCHAR/TEXT=25, BOOLEAN=16, BIGINT=20
        createTypeIfMissing(23, "integer", 4);
        createTypeIfMissing(25, "varchar", -1);
        createTypeIfMissing(16, "boolean", 1);
        createTypeIfMissing(20, "bigint", 8);
    }

    private void createTypeIfMissing(int oid, String name, int byteLength) {
        if (!types.containsKey(oid)) {
            createType(oid, name, byteLength);
        }
    }

    /**
     * Создает тип с автоматически назначаемым OID (как раньше).
     */
    private void createType(String name, int byteLength) {
        createType(nextTypeOid.getAndIncrement(), name, byteLength);
    }

    /**
     * Создает тип с указанным OID. Если OID уже существует — метод возвращает сразу.
     * После создания OID-ы nextTypeOid корректируются, чтобы не пересекаться с явно заданными OID.
     */
    private void createType(int oid, String name, int byteLength) {
        if (types.containsKey(oid)) {
            return;
        }

        TypeDefinition type = new TypeDefinition(oid, name, byteLength);
        types.put(type.oid(), type);
        saveRecord(TYPE_FILE, type.toBytes());

        // Обновим nextTypeOid, чтобы он всегда был > всех существующих OID
        nextTypeOid.updateAndGet(current -> Math.max(current, oid + 1));
    }

    // Вспомогательный метод для получения типа по имени
    public TypeDefinition getType(String typeName) {
        return types.values().stream()
                .filter(type -> type.name().equalsIgnoreCase(typeName))
                .findFirst()
                .orElse(null);
    }
    @Override
    // Вспомогательный метод для получения колонок таблицы
    public List<ColumnDefinition> getTableColumns(TableDefinition table) {
        return table != null ? tableColumns.get(table.getOid()) : Collections.emptyList();
    }

    @FunctionalInterface
    private interface RecordLoader {
        void load(byte[] data);
    }
}