package ru.open.cu.student.parser;

import ru.open.cu.student.ast.AExpr;
import ru.open.cu.student.ast.AstNode;
import ru.open.cu.student.ast.ColumnRef;
import ru.open.cu.student.catalog.model.ColumnDefinition;
import ru.open.cu.student.lexer.Token;
import ru.open.cu.student.parser.nodes.*;

import java.util.ArrayList;
import java.util.List;

public class DefaultParser implements Parser{
    private List<Token> tokens;
    private int curPosition;

    @Override
    public AstNode parse(List<Token> tokens) {
        this.tokens = tokens;
        this.curPosition = 0;

        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Empty token list");
        }

        String firstToken = currentToken().getType();

        return switch (firstToken) {
            case "SELECT" -> parseSelect();
            case "CREATE" -> parseCreate();
            case "INSERT" -> parseInsert();
            case "DROP" -> parseDrop();
            default -> throw new IllegalArgumentException("Unsupported SQL statement: " + firstToken);
        };
    }

    private AstNode parseSelect() {
        match("SELECT");

        List<ResTarget> targetList = parseTargetList();

        match("FROM");

        List<RangeVar> fromClause = parseFromClause();

        AstNode whereClause = null;
        if (curPosition < tokens.size() && currentToken().getType().equals("WHERE")) {
            match("WHERE");
            whereClause = parseWhereClause();
        }

        return new SelectStmt(targetList, fromClause, whereClause);
    }

    private AstNode parseCreate() {
        match("CREATE");
        match("TABLE");

        // Получаем имя таблицы (возможно с schema)
        String tableName;
        String schemaName = null;

        Token tableToken = expectToken("IDENT");
        if (curPosition < tokens.size() && currentToken().getType().equals("DOT")) {
            // Есть схема: schema.table
            schemaName = tableToken.getValue();
            match("DOT");
            tableToken = expectToken("IDENT");
            tableName = tableToken.getValue();
        } else {
            tableName = tableToken.getValue();
        }

        match("LPAREN");

        List<ColumnDefinition> columns = parseColumnDefinitions();

        match("RPAREN");

        return new CreateTableStmt(schemaName, tableName, columns);
    }

    private List<ColumnDefinition> parseColumnDefinitions() {
        List<ColumnDefinition> columns = new ArrayList<>();
        int position = 0;

        while (curPosition < tokens.size()) {
            Token columnNameToken = expectToken("IDENT");
            String columnName = columnNameToken.getValue();

            Token typeToken = expectToken("IDENT");
            String typeName = typeToken.getValue();

            // Преобразуем имя типа в typeOid (можно сделать маппинг)
            int typeOid = mapTypeNameToOid(typeName);

            columns.add(new ColumnDefinition(typeOid, columnName, position++));

            // Проверяем, есть ли следующая колонка
            if (curPosition < tokens.size() && currentToken().getType().equals("COMMA")) {
                match("COMMA");
            } else {
                break;
            }
        }

        return columns;
    }

    private int mapTypeNameToOid(String typeName) {
        // Простой маппинг типов на OID
        String tn = typeName.toUpperCase();
        final String VALID_TYPES = "integer,int,bigint,varchar,boolean";
        return switch (tn) {
            case "INTEGER", "INT" -> 23;    // Пример OID для integer
            case "BIGINT" -> 20;            // Пример OID для bigint
            case "VARCHAR", "TEXT" -> 25;   // Пример OID для text
            case "BOOLEAN", "BOOL" -> 16;   // Пример OID для boolean
            default -> {
                java.util.List<String> suggestions = suggestTypes(typeName);
                if (!suggestions.isEmpty()) {
                    throw new IllegalArgumentException("Unknown type: " + typeName + ". Did you mean: " + String.join(", ", suggestions) + "? Valid types: " + VALID_TYPES);
                }
                throw new IllegalArgumentException("Unknown type: " + typeName + ". Valid types: " + VALID_TYPES);
            }
        };
    }

    private AstNode parseInsert() {
        match("INSERT");
        match("INTO");

        // Получаем имя таблицы
        String tableName;
        String schemaName = null;

        Token tableToken = expectToken("IDENT");
        if (curPosition < tokens.size() && currentToken().getType().equals("DOT")) {
            schemaName = tableToken.getValue();
            match("DOT");
            tableToken = expectToken("IDENT");
            tableName = tableToken.getValue();
        } else {
            tableName = tableToken.getValue();
        }

        // Опциональный список колонок или сразу values в скобках (эвристика)
        List<String> columns = null;
        List<String> values = null;
        if (curPosition < tokens.size() && currentToken().getType().equals("LPAREN")) {
            match("LPAREN");
            // Если внутри скобок первый токен — STRING/NUMBER или IDENT(NULL/TRUE/FALSE) — считаем это списком значений
            if (curPosition < tokens.size()) {
                Token firstInside = currentToken();
                String t = firstInside.getType();
                boolean looksLikeValue = t.equals("STRING") || t.equals("NUMBER") || (t.equals("IDENT") && (
                        firstInside.getValue().equalsIgnoreCase("NULL") ||
                        firstInside.getValue().equalsIgnoreCase("TRUE") ||
                        firstInside.getValue().equalsIgnoreCase("FALSE")
                ));
                if (looksLikeValue) {
                    // Это values без ключевого слова VALUES
                    values = parseValues();
                    match("RPAREN");
                    // done — no explicit VALUES keyword
                    return new InsertStmt(schemaName, tableName, null, values);
                } else {
                    // Парсим имена колонок
                    columns = parseColumnNames();
                    match("RPAREN");
                }
            } else {
                throw new IllegalArgumentException("Empty parentheses after table name in INSERT");
            }
        }

        // Ожидаем VALUES если мы ещё не прочитали values
        match("VALUES");
        match("LPAREN");

        values = parseValues();

        match("RPAREN");

        return new InsertStmt(schemaName, tableName, columns, values);
    }

    private List<String> parseColumnNames() {
        List<String> columns = new ArrayList<>();

        while (curPosition < tokens.size()) {
            Token token = currentToken();
            if (!token.getType().equals("IDENT")) {
                // Была встречена строка/число там, где ожидалось имя колонки — подсказка
                if (token.getType().equals("STRING")) {
                    throw new IllegalArgumentException("Expected column name but got STRING (" + token.getValue() + ").\n" +
                            "If you intended to insert values, either use the VALUES keyword before the values list,\n" +
                            "or remove quotes to specify column names. To insert a string literal use quotes: '...'.");
                } else if (token.getType().equals("NUMBER")) {
                    throw new IllegalArgumentException("Expected column name but got NUMBER (" + token.getValue() + ").\n" +
                            "If you intended to insert values, either use the VALUES keyword before the values list,\n" +
                            "or provide valid column identifiers.");
                } else {
                    throw new IllegalArgumentException("Expected column name but got " + token.getType() + " (" + token.getValue() + ")");
                }
            }

            Token columnToken = match("IDENT");
            columns.add(columnToken.getValue());

            if (curPosition < tokens.size() && currentToken().getType().equals("COMMA")) {
                match("COMMA");
            } else {
                break;
            }
        }

        return columns;
    }

    private List<String> parseValues() {
        List<String> values = new ArrayList<>();

        while (curPosition < tokens.size()) {
            Token valueToken = currentToken();
            String value;

            if (valueToken.getType().equals("STRING")) {
                value = valueToken.getValue();
            } else if (valueToken.getType().equals("NUMBER")) {
                value = valueToken.getValue();
            } else if (valueToken.getType().equals("IDENT") &&
                    (valueToken.getValue().equalsIgnoreCase("NULL") ||
                            valueToken.getValue().equalsIgnoreCase("TRUE") ||
                            valueToken.getValue().equalsIgnoreCase("FALSE"))) {
                value = valueToken.getValue();
            } else if (valueToken.getType().equals("IDENT")) {
                // IDENT встретился в списке значений, но это не NULL/TRUE/FALSE — возможно пользователь забыл кавычки
                throw new IllegalArgumentException("Unexpected IDENT value: " + valueToken.getValue() + ".\n" +
                        "If you intended a string literal, surround it with single or double quotes, e.g. '" + valueToken.getValue() + "'.");
            } else {
                throw new IllegalArgumentException("Unexpected value token: " + valueToken + ". Expected STRING, NUMBER or NULL/TRUE/FALSE.");
            }

            values.add(value);
            curPosition++;

            if (curPosition < tokens.size() && currentToken().getType().equals("COMMA")) {
                match("COMMA");
            } else {
                break;
            }
        }

        return values;
    }

    // Вспомогательные методы для токенов (должны быть в вашем классе)
    private Token expectToken(String expectedType) {
        if (curPosition >= tokens.size()) {
            throw new IllegalArgumentException("Expected token " + expectedType + " but reached end of input");
        }
        Token token = tokens.get(curPosition);
        if (!token.getType().equals(expectedType)) {
            throw new IllegalArgumentException("Expected token " + expectedType +
                    " but got " + token.getType() + " (" + token.getValue() + ")");
        }
        curPosition++;
        return token;
    }





    private List<ResTarget> parseTargetList() {
        List<ResTarget> targets = new ArrayList<>();

        targets.add(parseResTarget());

        while (currentToken().getType().equals("COMMA")) {
            match("COMMA");
            targets.add(parseResTarget());
        }

        return targets;
    }

    private ResTarget parseResTarget() {
        Token token = currentToken();

        if (token.getType().equals("ASTERISK")) {
            match("ASTERISK");
            ColumnRef columnRef = new ColumnRef("*");
            return new ResTarget(columnRef, null);
        } else if (token.getType().equals("IDENT")) {
            match("IDENT");
            ColumnRef columnRef = new ColumnRef(token.getValue());
            return new ResTarget(columnRef, null);
        } else {
            throw new RuntimeException("Ожидался идентификатор колонки или *: " + token);
        }
    }

    private List<RangeVar> parseFromClause() {
        List<RangeVar> tables = new ArrayList<>();

        tables.add(parseRangeVar());

        while (currentToken().getType().equals("COMMA")) {
            match("COMMA");
            tables.add(parseRangeVar());
        }
        return tables;
    }

    private AstNode parseWhereClause() {
        // Левый операнд
        Token left = match("IDENT");
        ColumnRef leftRef = new ColumnRef(left.getValue());

        // Оператор
        String operator = parseOperator();

        // Правый операнд
        AstNode right = parseExpression();

        return new AExpr(operator, leftRef, right);
    }

    private AstNode parseExpression() {
        Token token = currentToken();

        if (token.getType().equals("NUMBER")) {
            match("NUMBER");
            // Для простоты используем ColumnRef для чисел
            return new ColumnRef(null, token.getValue());
        }
        else if (token.getType().equals("IDENT")) {
            match("IDENT");
            return new ColumnRef(token.getValue());
        }
        else {
            throw new RuntimeException("Ожидалось выражение: " + token);
        }
    }

    private String parseOperator() {
        Token token = currentToken();
        switch (token.getType()) {
            case "GT": match("GT"); return ">";
            case "LT": match("LT"); return "<";
            case "EQ": match("EQ"); return "=";
            case "NEQ": match("NEQ"); return "!=";
            default: throw new RuntimeException("Неизвестный оператор: " + token);
        }
    }

    private RangeVar parseRangeVar() {
        Token tableToken = match("IDENT");
        return new RangeVar(null, tableToken.getValue(), null);
    }

    //функция проверки текущего элемента
    private Token currentToken() {
        if (curPosition >= tokens.size()) return new Token("EOF", "");
        return tokens.get(curPosition);
    }

    private Token match(String expectedType) {
        Token token = currentToken();
        if (token.getType().equals(expectedType)) {
            curPosition++;
            return token;
        }
        throw new RuntimeException("Ожидался токен: " + expectedType + " , но получен токен:  " + token.getType());
    }

    // Suggest similar known types for a friendlier error message
    private java.util.List<String> suggestTypes(String typeName) {
        String[] known = {"integer","int","bigint","varchar","text","boolean","bool"};
        java.util.List<String> res = new ArrayList<>();
        String s = typeName.toLowerCase();
        for (String k : known) {
            String kl = k.toLowerCase();
            if (kl.equals(s)) continue;
            // prefix match is a good hint
            if (kl.startsWith(s) || s.startsWith(kl) || kl.startsWith(s.replaceAll("[^a-z]", ""))) {
                res.add(k);
                continue;
            }
            // small Levenshtein distance
            int d = levenshtein(s, kl);
            if (d <= 2) res.add(k);
        }
        return res;
    }

    // Standard Levenshtein distance (iterative, O(n*m) time, O(min(n,m)) space)
    private int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.length() == 0) return b.length();
        if (b.length() == 0) return a.length();
        if (a.length() < b.length()) {
            // ensure a is longer
            String tmp = a; a = b; b = tmp;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private AstNode parseDrop() {
        match("DROP");
        match("TABLE");

        boolean ifExists = false;
        if (curPosition < tokens.size() && currentToken().getType().equals("IDENT") && currentToken().getValue().equalsIgnoreCase("IF")) {
            // accept IF EXISTS
            match("IDENT"); // IF
            Token next = expectToken("IDENT");
            if (next.getValue().equalsIgnoreCase("EXISTS")) {
                ifExists = true;
            } else {
                throw new IllegalArgumentException("Expected EXISTS after IF in DROP TABLE");
            }
        }

        String tableName;
        String schemaName = null;
        Token tableToken = expectToken("IDENT");
        if (curPosition < tokens.size() && currentToken().getType().equals("DOT")) {
            schemaName = tableToken.getValue();
            match("DOT");
            tableToken = expectToken("IDENT");
            tableName = tableToken.getValue();
        } else {
            tableName = tableToken.getValue();
        }

        return new DropTableStmt(schemaName, tableName, ifExists);
    }
}
