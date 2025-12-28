package ru.open.cu.student.catalog.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class TableDefinition {
    private final int oid;
    private final String name;
    private final String type;
    private final String fileNode;
    private int pagesCount;
    private final List<ColumnDefinition> columns = new ArrayList<>();

    public TableDefinition(int oid, String name, String type, String fileNode, int pagesCount) {
        if (oid < 0) throw new IllegalArgumentException("oid must be >= 0");
        this.name = Objects.requireNonNull(name, "name");
        if (this.name.isEmpty()) throw new IllegalArgumentException("name must not be empty");
        this.type = Objects.requireNonNull(type, "type");
        if (this.type.isEmpty()) throw new IllegalArgumentException("type must not be empty");
        this.fileNode = Objects.requireNonNull(fileNode, "fileNode");
        if (this.fileNode.isEmpty()) throw new IllegalArgumentException("fileNode must not be empty");
        if (pagesCount < 0) throw new IllegalArgumentException("pagesCount must be >= 0");

        this.oid = oid;
        this.pagesCount = pagesCount;
    }

    public static TableDefinition fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 4 + 2 + 2 + 2 + 4) {
            throw new IllegalArgumentException("payload is too small for TableDefinition");
        }

        int oid = buffer.getInt();

        int nameLen = buffer.getShort() & 0xFFFF;
        if (buffer.remaining() < nameLen + 2) {
            throw new IllegalArgumentException("invalid name length in TableDefinition");
        }
        byte[] nameBytes = new byte[nameLen];
        buffer.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);

        int typeLen = buffer.getShort() & 0xFFFF;
        if (buffer.remaining() < typeLen + 2) {
            throw new IllegalArgumentException("invalid type length in TableDefinition");
        }
        byte[] typeBytes = new byte[typeLen];
        buffer.get(typeBytes);
        String type = new String(typeBytes, StandardCharsets.UTF_8);

        int fileNodeLen = buffer.getShort() & 0xFFFF;
        if (buffer.remaining() < fileNodeLen + 4) {
            throw new IllegalArgumentException("invalid fileNode length in TableDefinition");
        }
        byte[] fileNodeBytes = new byte[fileNodeLen];
        buffer.get(fileNodeBytes);
        String fileNode = new String(fileNodeBytes, StandardCharsets.UTF_8);

        int pagesCount = buffer.getInt();

        return new TableDefinition(oid, name, type, fileNode, pagesCount);
    }

    public int getOid() {
        return oid;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getFileNode() {
        return fileNode;
    }

    public int getPagesCount() {
        return pagesCount;
    }

    public void setPagesCount(int pagesCount) {
        this.pagesCount = pagesCount;
    }

    public List<ColumnDefinition> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public void setColumns(List<ColumnDefinition> columnDefinitions) {
        columns.clear();
        columns.addAll(columnDefinitions);
    }

    public byte[] toBytes() {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        byte[] fileNodeBytes = fileNode.getBytes(StandardCharsets.UTF_8);

        if (nameBytes.length > Short.MAX_VALUE) {
            throw new IllegalStateException("table name is too long");
        }
        if (typeBytes.length > Short.MAX_VALUE) {
            throw new IllegalStateException("table type is too long");
        }
        if (fileNodeBytes.length > Short.MAX_VALUE) {
            throw new IllegalStateException("fileNode is too long");
        }

        ByteBuffer buffer = ByteBuffer
                .allocate(4 + 2 + nameBytes.length + 2 + typeBytes.length + 2 + fileNodeBytes.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(oid);
        buffer.putShort((short) nameBytes.length);
        buffer.put(nameBytes);
        buffer.putShort((short) typeBytes.length);
        buffer.put(typeBytes);
        buffer.putShort((short) fileNodeBytes.length);
        buffer.put(fileNodeBytes);
        buffer.putInt(pagesCount);

        return buffer.array();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableDefinition that)) return false;
        return oid == that.oid
                && pagesCount == that.pagesCount
                && name.equals(that.name)
                && type.equals(that.type)
                && fileNode.equals(that.fileNode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oid, name, type, fileNode, pagesCount);
    }
}
