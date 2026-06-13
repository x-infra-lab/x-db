package io.github.xinfra.lab.xdb.parser;

import io.github.xinfra.lab.xdb.parser.ast.Statement;
import org.antlr.v4.runtime.*;

import java.util.List;

/**
 * Facade for parsing SQL strings into AST {@link Statement} objects.
 */
public class SQLParser {

    /**
     * Parse a single SQL statement.
     *
     * @param sql the SQL string
     * @return the parsed Statement
     * @throws ParseException if the SQL is syntactically invalid
     */
    public static Statement parse(String sql) {
        List<Statement> stmts = parseMulti(sql);
        if (stmts.isEmpty()) {
            throw new ParseException("No SQL statements found in input");
        }
        return stmts.get(0);
    }

    /**
     * Parse one or more SQL statements separated by semicolons.
     *
     * @param sql the SQL string containing one or more statements
     * @return a list of parsed Statements
     * @throws ParseException if the SQL is syntactically invalid
     */
    @SuppressWarnings("unchecked")
    public static List<Statement> parseMulti(String sql) {
        CharStream charStream = CharStreams.fromString(sql);
        MySQLLexer lexer = new MySQLLexer(charStream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySQLParser parser = new MySQLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        MySQLParser.SqlStatementsContext tree = parser.sqlStatements();
        AstBuilder builder = new AstBuilder();
        return (List<Statement>) builder.visit(tree);
    }

    /**
     * Error listener that throws a {@link ParseException} on any syntax error.
     */
    private static class ThrowingErrorListener extends BaseErrorListener {
        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            throw new ParseException("SQL syntax error at line " + line + ":" + charPositionInLine + " - " + msg);
        }
    }
}
