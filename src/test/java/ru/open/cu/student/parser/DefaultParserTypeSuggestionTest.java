package ru.open.cu.student.parser;

import org.junit.jupiter.api.Test;
import ru.open.cu.student.lexer.DefaultLexer;
import ru.open.cu.student.lexer.Token;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultParserTypeSuggestionTest {
    private final DefaultLexer lexer = new DefaultLexer();
    private final DefaultParser parser = new DefaultParser();

    @Test
    void parseCreateWithMisspelledTypeShouldProduceSuggestion() {
        String sql = "CREATE TABLE users (name varchar, id unt)";
        List<Token> tokens = lexer.tokenize(sql);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parse(tokens));
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("Unknown type"), () -> "Expected message to contain 'Unknown type', was: " + msg);
        assertTrue(msg.contains("Valid types:"), () -> "Expected message to contain 'Valid types:', was: " + msg);
        // suggestions may vary (int, bigint), just ensure list of valid types is present
        assertTrue(msg.contains("integer") && msg.contains("bigint") && msg.contains("varchar"), () -> "Expected message to list common valid types, was: " + msg);
    }
}

