package ru.open.cu.student.catalog.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class ColumnDefinition {
    private final int oid;
    private final int tableOid;
    private final int typeOid;
    private final String name;
    private final int position;

    public ColumnDefinition(int oid, int tableOid, int typeOid, String name, int position) {
        if (oid < 0) throw new IllegalArgumentException("oid must be >= 0");
        if (tableOid < 0) throw new IllegalArgumentException("tableOid must be >= 0");
        if (typeOid < 0) throw new IllegalArgumentException("typeOid must be >= 0");
        this.name = Objects.requireNonNull(name, "name");
        if (this.name.isEmpty()) throw new IllegalArgumentException("name must not be empty");
        if (position < 0) throw new IllegalArgumentException("position must be >= 0");

        this.oid = oid;
        this.tableOid = tableOid;
        this.typeOid = typeOid;
        this.position = position;
    }

    public ColumnDefinition(int typeOid, String name, int position) {
        this(0, 0, typeOid, name, position);
    }

    public static ColumnDefinition fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 4 + 4 + 4 + 4 + 2) {
            throw new IllegalArgumentException("payload is too small for ColumnDefinition");
        }
        int oid = buffer.getInt();
        int tableOid = buffer.getInt();
        int typeOid = buffer.getInt();
        int position = buffer.getInt();
        int nameLen = buffer.getShort() & 0xFFFF;
        if (buffer.remaining() != nameLen) {
            throw new IllegalArgumentException("invalid payload for ColumnDefinition");
        }
        byte[] nameBytes = new byte[nameLen];
        buffer.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        return new ColumnDefinition(oid, tableOid, typeOid, name, position);
    }

    public int getOid() {
        return oid;
    }

    public int getTableOid() {
        return tableOid;
    }

    public int getTypeOid() {
        return typeOid;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public byte[] toBytes() {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > Short.MAX_VALUE) {
            throw new IllegalStateException("column name is too long");
        }

        ByteBuffer buffer = ByteBuffer
                .allocate(4 + 4 + 4 + 4 + 2 + nameBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(oid);
        buffer.putInt(tableOid);
        buffer.putInt(typeOid);
        buffer.putInt(position);
        buffer.putShort((short) nameBytes.length);
        buffer.put(nameBytes);
        return buffer.array();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnDefinition that)) return false;
        return oid == that.oid
                && tableOid == that.tableOid
                && typeOid == that.typeOid
                && position == that.position
                && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oid, tableOid, typeOid, name, position);
    }
}
