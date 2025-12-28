package ru.open.cu.student.execution.executors;

import ru.open.cu.student.ast.TargetEntry;
import ru.open.cu.student.ast.ColumnRef;
import ru.open.cu.student.ast.AConst;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.catalog.model.TypeDefinition;
import ru.open.cu.student.catalog.manager.CatalogManager;
import ru.open.cu.student.catalog.manager.DefaultCatalogManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Исполнитель SELECT-списка.
 */
public class ProjectExecutor implements Executor {
    private final Executor child;
    private final List<TargetEntry> targetList;
    private final List<ColumnDefinition> columns; // may be null
    private boolean isOpen;

    public ProjectExecutor(Executor child, List<TargetEntry> targetList, List<ColumnDefinition> columns) {
        this.child = child;
        this.targetList = targetList;
        this.columns = columns; // may be null if unknown
    }

    @Override
    public void open() {
        child.open();
        isOpen = true;
    }

    @Override
    public Object next() {
        if (!isOpen) return null;

        Object rowData = child.next();
        if (rowData == null) return null;

        // если targetList пуст — SELECT * — нужно десериализовать всю строку
        if (targetList.isEmpty()) {
            return deserializeRow((byte[]) rowData);
        }

        return extractProjectedFields((byte[]) rowData);
    }

    private List<Object> deserializeRow(byte[] rowData) {
        List<Object> row = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(rowData).order(ByteOrder.LITTLE_ENDIAN);

        if (columns == null) {
            // неизвестная схема — возвращаем байты
            return List.of((Object) rowData);
        }

        for (ColumnDefinition col : columns) {
            TypeDefinition type = lookupType(col.getTypeOid());
            String tname = type.name().toLowerCase();
            switch (tname) {
                case "integer": row.add(buffer.getInt()); break;
                case "bigint": row.add(buffer.getLong()); break;
                case "boolean": row.add(buffer.get() != 0); break;
                case "varchar": {
                    int len = buffer.getShort() & 0xFFFF;
                    byte[] s = new byte[len];
                    buffer.get(s);
                    row.add(new String(s, StandardCharsets.UTF_8));
                    break;
                }
                default: row.add(null); break;
            }
        }

        return row;
    }

    private Object extractProjectedFields(byte[] rowData) {
        List<Object> fullRow = deserializeRow(rowData);

        List<Object> result = new ArrayList<>();
        for (TargetEntry target : targetList) {
            if (target.expr instanceof ColumnRef) {
                String colName = ((ColumnRef) target.expr).column;
                // Handle SELECT * projection
                if ("*".equals(colName)) {
                    return fullRow;
                }
                int idx = findColumnIndex(colName);
                if (idx >= 0 && idx < fullRow.size()) {
                    result.add(fullRow.get(idx));
                } else {
                    result.add(null);
                }
            } else if (target.expr instanceof AConst) {
                result.add(((AConst) target.expr).value);
            } else {
                result.add(null);
            }
        }
        return result;
    }

    private int findColumnIndex(String name) {
        if (columns == null) return -1;
        for (ColumnDefinition c : columns) {
            if (c.getName().equalsIgnoreCase(name)) return c.getPosition();
        }
        return -1;
    }

    private TypeDefinition lookupType(int oid) {
        CatalogManager cm = new DefaultCatalogManager();
        TypeDefinition t = cm.getType(oid);
        if (t == null) return new TypeDefinition(oid, "integer", 4);
        return t;
    }

    @Override
    public void close() {
        child.close();
        isOpen = false;
    }
}