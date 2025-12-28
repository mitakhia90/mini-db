package ru.open.cu.student.parser.nodes;

import ru.open.cu.student.ast.AstNode;

public class DropTableStmt extends AstNode {
    public String schemaName;
    public String tableName;
    public boolean ifExists;

    public DropTableStmt(String schemaName, String tableName) {
        this(schemaName, tableName, false);
    }

    public DropTableStmt(String schemaName, String tableName, boolean ifExists) {
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.ifExists = ifExists;
    }

    @Override
    public String toString() {
        if (schemaName != null) return "DROP TABLE " + schemaName + "." + tableName + (ifExists ? " IF EXISTS" : "");
        return "DROP TABLE " + tableName + (ifExists ? " IF EXISTS" : "");
    }
}
