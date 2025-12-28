package ru.open.cu.student.execution.executors;

import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TableDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.memory.buffer.BufferPoolManager;
import ru.open.cu.student.memory.page.HeapPage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Исполнитель последовательного сканирования таблицы.
 */
public class SeqScanExecutor implements Executor {
    private final BufferPoolManager bufferPool;
    private final CatalogManager catalogManager;
    private final TableDefinition tableDefinition;
    private List<ColumnDefinition> columns;

    private int currentPageId;
    private int currentRowIndex;
    private boolean isOpen;

    public SeqScanExecutor(BufferPoolManager bufferPool, TableDefinition tableDefinition, CatalogManager catalogManager) {
        this.bufferPool = bufferPool;
        this.tableDefinition = tableDefinition;
        this.catalogManager = catalogManager;
        this.columns = catalogManager.getTableColumns(tableDefinition);
    }

    @Override
    public void open() {
        currentPageId = 0;
        currentRowIndex = 0;
        isOpen = true;
    }

    @Override
    public Object next() {
        if (!isOpen) return null;

        while (true) {
            var bufferSlot = bufferPool.getPage(currentPageId);
            if (bufferSlot == null) return null; // no more pages

            HeapPage page = (HeapPage) bufferSlot.getPage();

            if (currentRowIndex < page.size()) {
                byte[] rowData = page.read(currentRowIndex);
                currentRowIndex++;
                // deserialize according to columns
                return deserializeRow(rowData);
            } else {
                currentPageId++;
                currentRowIndex = 0;
            }
        }
    }

    private List<Object> deserializeRow(byte[] rowData) {
        List<Object> row = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(rowData).order(ByteOrder.LITTLE_ENDIAN);

        if (columns == null) return List.of();

        for (ColumnDefinition col : columns) {
            TypeDefinition type = catalogManager.getType(col.getTypeOid());
            String tn = type != null ? type.name().toLowerCase() : "integer";
            switch (tn) {
                case "integer": row.add(buf.getInt()); break;
                case "bigint": row.add(buf.getLong()); break;
                case "boolean": row.add(buf.get() != 0); break;
                case "varchar": {
                    int len = buf.getShort() & 0xFFFF;
                    byte[] s = new byte[len];
                    buf.get(s);
                    row.add(new String(s, StandardCharsets.UTF_8));
                    break;
                }
                default: row.add(null); break;
            }
        }

        return row;
    }

    @Override
    public void close() {
        isOpen = false;
        currentPageId = 0;
        currentRowIndex = 0;
    }
}