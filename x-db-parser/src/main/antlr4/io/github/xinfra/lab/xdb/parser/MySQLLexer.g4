lexer grammar MySQLLexer;

options { caseInsensitive = true; }

// Keywords
ADD:            'ADD';
ALL:            'ALL';
ALTER:          'ALTER';
ANALYZE:        'ANALYZE';
AND:            'AND';
AS:             'AS';
ASC:            'ASC';
AUTO_INCREMENT: 'AUTO_INCREMENT';
BETWEEN:        'BETWEEN';
BIGINT:         'BIGINT';
BINARY:         'BINARY';
BLOB:           'BLOB';
BOOLEAN:        'BOOLEAN';
BOOL:           'BOOL';
BY:             'BY';
CASCADE:        'CASCADE';
CASE:           'CASE';
CAST:           'CAST';
CHAR:           'CHAR';
CHARACTER:      'CHARACTER';
CHARSET:        'CHARSET';
COLLATE:        'COLLATE';
COLUMN:         'COLUMN';
COLUMNS:        'COLUMNS';
COMMENT:        'COMMENT';
COMMIT:         'COMMIT';
CONVERT:        'CONVERT';
CREATE:         'CREATE';
CROSS:          'CROSS';
DATABASE:       'DATABASE';
DATABASES:      'DATABASES';
DATE:           'DATE';
DATETIME:       'DATETIME';
DECIMAL:        'DECIMAL';
DEFAULT:        'DEFAULT';
DELETE:         'DELETE';
DESC:           'DESC';
DESCRIBE:       'DESCRIBE';
DISTINCT:       'DISTINCT';
DIV:            'DIV';
DOUBLE:         'DOUBLE';
DROP:           'DROP';
ELSE:           'ELSE';
END:            'END';
ENGINE:         'ENGINE';
EXCEPT:         'EXCEPT';
EXISTS:         'EXISTS';
EXPLAIN:        'EXPLAIN';
FALSE:          'FALSE';
FIELDS:         'FIELDS';
FIRST:          'FIRST';
FLOAT:          'FLOAT';
FOR:            'FOR';
FOREIGN:        'FOREIGN';
FROM:           'FROM';
FULL:           'FULL';
GLOBAL:         'GLOBAL';
GROUP:          'GROUP';
HAVING:         'HAVING';
IF:             'IF';
IN:             'IN';
INDEX:          'INDEX';
INNER:          'INNER';
INSERT:         'INSERT';
INT:            'INT';
INTEGER:        'INTEGER';
INTERSECT:      'INTERSECT';
INTO:           'INTO';
IS:             'IS';
JOIN:           'JOIN';
KEY:            'KEY';
LEFT:           'LEFT';
LIKE:           'LIKE';
LIMIT:          'LIMIT';
LONGBLOB:       'LONGBLOB';
LONGTEXT:       'LONGTEXT';
MEDIUMBLOB:     'MEDIUMBLOB';
MEDIUMINT:      'MEDIUMINT';
MEDIUMTEXT:     'MEDIUMTEXT';
MOD:            'MOD';
NOT:            'NOT';
NULL:           'NULL';
NUMERIC:        'NUMERIC';
OFFSET:         'OFFSET';
ON:             'ON';
OPTIMISTIC:     'OPTIMISTIC';
OR:             'OR';
ORDER:          'ORDER';
OUTER:          'OUTER';
PESSIMISTIC:    'PESSIMISTIC';
PRIMARY:        'PRIMARY';
REAL:           'REAL';
REFERENCES:     'REFERENCES';
RESTRICT:       'RESTRICT';
RIGHT:          'RIGHT';
ROLLBACK:       'ROLLBACK';
SCHEMA:         'SCHEMA';
SELECT:         'SELECT';
SESSION:        'SESSION';
SET:            'SET';
SHOW:           'SHOW';
SIGNED:         'SIGNED';
SMALLINT:       'SMALLINT';
START:          'START';
STATUS:         'STATUS';
TABLE:          'TABLE';
TABLES:         'TABLES';
TEXT:           'TEXT';
THEN:           'THEN';
TIME:           'TIME';
TIMESTAMP:      'TIMESTAMP';
TINYBLOB:       'TINYBLOB';
TINYINT:        'TINYINT';
TINYTEXT:       'TINYTEXT';
TRANSACTION:    'TRANSACTION';
TRUE:           'TRUE';
TRUNCATE:       'TRUNCATE';
UNION:          'UNION';
UNIQUE:         'UNIQUE';
UNSIGNED:       'UNSIGNED';
UPDATE:         'UPDATE';
USE:            'USE';
USING:          'USING';
VALUES:         'VALUES';
VARBINARY:      'VARBINARY';
VARCHAR:        'VARCHAR';
VARIABLES:      'VARIABLES';
WARNINGS:       'WARNINGS';
WHEN:           'WHEN';
WHERE:          'WHERE';
WITH:           'WITH';
WORK:           'WORK';
YEAR:           'YEAR';

BEGIN:          'BEGIN';
AFTER:         'AFTER';

// Operators
EQ:             '=';
NEQ:            '!=' | '<>';
LT:             '<';
LE:             '<=';
GT:             '>';
GE:             '>=';
PLUS:           '+';
MINUS:          '-';
STAR:           '*';
SLASH:          '/';
PERCENT:        '%';
LPAREN:         '(';
RPAREN:         ')';
COMMA:          ',';
SEMICOLON:      ';';
DOT:            '.';
AT:             '@';
DOUBLE_AT:      '@@';
ASSIGN:         ':=';

// Literals
INTEGER_LITERAL:    DIGIT+;
DECIMAL_LITERAL:    DIGIT+ '.' DIGIT*
                 |  '.' DIGIT+;

STRING_LITERAL:     '\'' (~['\\] | '\\' . | '\'\'' )* '\''
                 |  '"' (~["\\] | '\\' . | '""')* '"';

HEX_LITERAL:        '0' [xX] HEX_DIGIT+
                 |  [xX] '\'' HEX_DIGIT+ '\'';

// Identifiers
IDENTIFIER:         [a-zA-Z_] [a-zA-Z0-9_]*;
BACKTICK_IDENTIFIER: '`' (~'`' | '``')* '`';

// Whitespace and comments
WS:             [ \t\r\n]+ -> skip;
LINE_COMMENT:   '--' ~[\r\n]* -> skip;
BLOCK_COMMENT:  '/*' .*? '*/' -> skip;

fragment DIGIT:     [0-9];
fragment HEX_DIGIT: [0-9a-fA-F];
