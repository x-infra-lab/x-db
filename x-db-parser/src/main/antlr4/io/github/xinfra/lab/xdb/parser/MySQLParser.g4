parser grammar MySQLParser;

options {
    tokenVocab = MySQLLexer;
}

// ============================================================
// Top-level rules
// ============================================================

sqlStatements
    : sqlStatement (SEMICOLON sqlStatement)* SEMICOLON? EOF
    ;

sqlStatement
    : ddlStatement
    | dmlStatement
    | utilityStatement
    ;

ddlStatement
    : createDatabaseStatement
    | dropDatabaseStatement
    | createTableStatement
    | dropTableStatement
    | alterTableStatement
    | truncateTableStatement
    | createIndexStatement
    ;

dmlStatement
    : selectStatement
    | insertStatement
    | updateStatement
    | deleteStatement
    ;

utilityStatement
    : useStatement
    | showStatement
    | explainStatement
    | beginStatement
    | commitStatement
    | rollbackStatement
    | setStatement
    | describeStatement
    | analyzeTableStatement
    ;

analyzeTableStatement
    : ANALYZE TABLE tableName
    ;

// ============================================================
// DDL statements
// ============================================================

createDatabaseStatement
    : CREATE (DATABASE | SCHEMA) (IF NOT EXISTS)? identifier
      createDatabaseOption*
    ;

createDatabaseOption
    : DEFAULT? (CHARACTER SET | CHARSET) EQ? identifier   # charsetOption
    | DEFAULT? COLLATE EQ? identifier                      # collateOption
    ;

dropDatabaseStatement
    : DROP (DATABASE | SCHEMA) (IF EXISTS)? identifier
    ;

createTableStatement
    : CREATE TABLE (IF NOT EXISTS)? tableName
      LPAREN
        tableElement (COMMA tableElement)*
      RPAREN
      tableOption*
    ;

tableElement
    : columnDefinition
    | tableConstraint
    ;

columnDefinition
    : columnName dataType columnConstraint*
    ;

dataType
    : TINYINT (LPAREN INTEGER_LITERAL RPAREN)?                          # tinyIntType
    | SMALLINT (LPAREN INTEGER_LITERAL RPAREN)?                         # smallIntType
    | MEDIUMINT (LPAREN INTEGER_LITERAL RPAREN)?                        # mediumIntType
    | (INT | INTEGER) (LPAREN INTEGER_LITERAL RPAREN)?                  # intType
    | BIGINT (LPAREN INTEGER_LITERAL RPAREN)?                           # bigIntType
    | FLOAT (LPAREN INTEGER_LITERAL (COMMA INTEGER_LITERAL)? RPAREN)?   # floatType
    | DOUBLE (LPAREN INTEGER_LITERAL (COMMA INTEGER_LITERAL)? RPAREN)?  # doubleType
    | REAL (LPAREN INTEGER_LITERAL (COMMA INTEGER_LITERAL)? RPAREN)?    # realType
    | (DECIMAL | NUMERIC) (LPAREN INTEGER_LITERAL (COMMA INTEGER_LITERAL)? RPAREN)? # decimalType
    | CHAR (LPAREN INTEGER_LITERAL RPAREN)?                             # charType
    | VARCHAR LPAREN INTEGER_LITERAL RPAREN                             # varcharType
    | TEXT                                                               # textType
    | TINYTEXT                                                          # tinyTextType
    | MEDIUMTEXT                                                        # mediumTextType
    | LONGTEXT                                                          # longTextType
    | BINARY (LPAREN INTEGER_LITERAL RPAREN)?                           # binaryType
    | VARBINARY LPAREN INTEGER_LITERAL RPAREN                           # varbinaryType
    | BLOB                                                              # blobType
    | TINYBLOB                                                          # tinyBlobType
    | MEDIUMBLOB                                                        # mediumBlobType
    | LONGBLOB                                                          # longBlobType
    | DATE                                                              # dateType
    | DATETIME (LPAREN INTEGER_LITERAL RPAREN)?                         # datetimeType
    | TIMESTAMP (LPAREN INTEGER_LITERAL RPAREN)?                        # timestampType
    | TIME (LPAREN INTEGER_LITERAL RPAREN)?                             # timeType
    | YEAR (LPAREN INTEGER_LITERAL RPAREN)?                             # yearType
    | (BOOLEAN | BOOL)                                                  # booleanType
    | SIGNED                                                            # signedType
    | UNSIGNED                                                          # unsignedType
    ;

columnConstraint
    : NOT NULL                                  # notNullConstraint
    | NULL                                      # nullConstraint
    | DEFAULT defaultValue                      # defaultConstraint
    | AUTO_INCREMENT                            # autoIncrementConstraint
    | PRIMARY KEY                               # primaryKeyConstraint
    | UNIQUE (KEY)?                             # uniqueKeyConstraint
    | COMMENT STRING_LITERAL                    # commentConstraint
    | UNSIGNED                                  # unsignedConstraint
    ;

defaultValue
    : literal
    | LPAREN expression RPAREN
    ;

tableConstraint
    : PRIMARY KEY indexName? LPAREN indexColumnList RPAREN                         # primaryKeyTableConstraint
    | UNIQUE (INDEX | KEY)? indexName? LPAREN indexColumnList RPAREN               # uniqueKeyTableConstraint
    | (INDEX | KEY) indexName? LPAREN indexColumnList RPAREN                       # indexTableConstraint
    ;

indexName
    : identifier
    ;

indexColumnList
    : indexColumn (COMMA indexColumn)*
    ;

indexColumn
    : columnName (LPAREN INTEGER_LITERAL RPAREN)?
    ;

tableOption
    : ENGINE EQ? identifier                                 # engineOption
    | DEFAULT? (CHARACTER SET | CHARSET) EQ? identifier     # tableCharsetOption
    | DEFAULT? COLLATE EQ? identifier                       # tableCollateOption
    | AUTO_INCREMENT EQ? INTEGER_LITERAL                    # tableAutoIncrementOption
    | COMMENT EQ? STRING_LITERAL                            # tableCommentOption
    ;

dropTableStatement
    : DROP TABLE (IF EXISTS)? tableName
    ;

alterTableStatement
    : ALTER TABLE tableName alterSpec (COMMA alterSpec)*
    ;

alterSpec
    : ADD COLUMN? columnDefinition (FIRST | AFTER columnName)?  # addColumnSpec
    | DROP COLUMN? columnName                                   # dropColumnSpec
    | ADD (INDEX | KEY) indexName? LPAREN indexColumnList RPAREN # addIndexSpec
    | DROP (INDEX | KEY) indexName                              # dropIndexSpec
    ;

truncateTableStatement
    : TRUNCATE TABLE? tableName
    ;

createIndexStatement
    : CREATE INDEX indexName ON tableName LPAREN indexColumnList RPAREN
    ;

// ============================================================
// DML statements
// ============================================================

selectStatement
    : selectBody (UNION ALL? selectBody)*
    ;

selectBody
    : SELECT (ALL | DISTINCT)? selectItem (COMMA selectItem)*
      (FROM tableRef (COMMA tableRef)*)?
      (WHERE whereExpr=expression)?
      (GROUP BY groupByItem (COMMA groupByItem)*)?
      (HAVING havingExpr=expression)?
      (ORDER BY orderByItem (COMMA orderByItem)*)?
      (LIMIT limitExpr=expression (OFFSET offsetExpr=expression)?
        | LIMIT offsetExpr2=expression COMMA limitExpr2=expression)?
      (FOR UPDATE)?
    ;

selectItem
    : STAR                                          # selectAll
    | tableName DOT STAR                            # selectTableAll
    | expression (AS? identifier)?                  # selectExpr
    ;

groupByItem
    : expression
    ;

orderByItem
    : expression (ASC | DESC)?
    ;

tableRef
    : simpleTableRef joinClause*
    ;

simpleTableRef
    : tableName (AS? identifier)?                       # simpleTable
    | LPAREN selectStatement RPAREN AS? identifier      # subqueryTable
    ;

joinClause
    : joinType? JOIN tableRef (ON expression)?
    ;

joinType
    : INNER
    | LEFT OUTER?
    | RIGHT OUTER?
    | CROSS
    ;

insertStatement
    : INSERT INTO? tableName
      (LPAREN columnName (COMMA columnName)* RPAREN)?
      VALUES valueRow (COMMA valueRow)*
    ;

valueRow
    : LPAREN expression (COMMA expression)* RPAREN
    ;

updateStatement
    : UPDATE tableRef SET assignment (COMMA assignment)*
      (WHERE expression)?
    ;

assignment
    : columnRef EQ expression
    ;

deleteStatement
    : DELETE tableName FROM tableRef (WHERE expression)?   # deleteMultiTable
    | DELETE FROM tableName (WHERE expression)?             # deleteSingleTable
    ;

// ============================================================
// Utility statements
// ============================================================

useStatement
    : USE identifier
    ;

showStatement
    : SHOW DATABASES                                      # showDatabases
    | SHOW TABLES                                         # showTables
    | SHOW FULL? (COLUMNS | FIELDS) FROM tableName        # showColumns
    | SHOW CREATE TABLE tableName                         # showCreateTable
    | SHOW VARIABLES                                      # showVariables
    | SHOW WARNINGS                                       # showWarnings
    | SHOW (GLOBAL | SESSION)? STATUS                     # showStatus
    ;

explainStatement
    : EXPLAIN selectStatement
    ;

beginStatement
    : BEGIN WORK?
    | START TRANSACTION transactionMode?
    ;

transactionMode
    : identifier identifier    // READ ONLY | READ WRITE
    ;

commitStatement
    : COMMIT
    ;

rollbackStatement
    : ROLLBACK
    ;

setStatement
    : SET setScope? identifier EQ expression        # setVariable
    | SET DOUBLE_AT identifier EQ expression         # setSystemVariable
    ;

setScope
    : SESSION
    | GLOBAL
    ;

describeStatement
    : (DESCRIBE | DESC) tableName
    ;

// ============================================================
// Expressions (with precedence)
// ============================================================

expression
    : primaryExpression                                                 # primaryExprAlt
    | op=(PLUS | MINUS) expression                                     # unaryExpr
    | NOT expression                                                   # notExpr
    | expression op=(STAR | SLASH | PERCENT) expression                # mulDivExpr
    | expression DIV expression                                        # intDivExpr
    | expression MOD expression                                        # modExpr
    | expression op=(PLUS | MINUS) expression                          # addSubExpr
    | expression op=(EQ | NEQ | LT | LE | GT | GE) expression         # comparisonExpr
    | expression IS NOT? NULL                                          # isNullExpr
    | expression NOT? LIKE expression                                  # likeExpr
    | expression NOT? IN LPAREN (expressionList | selectStatement) RPAREN  # inExpr
    | expression NOT? BETWEEN expression AND expression                # betweenExpr
    | EXISTS LPAREN selectStatement RPAREN                             # existsExpr
    | expression AND expression                                        # andExpr
    | expression OR expression                                         # orExpr
    ;

expressionList
    : expression (COMMA expression)*
    ;

primaryExpression
    : literal                                                           # literalPrimary
    | columnRef                                                         # columnRefPrimary
    | functionCall                                                      # functionCallPrimary
    | LPAREN selectStatement RPAREN                                     # subqueryPrimary
    | LPAREN expression RPAREN                                          # parenPrimary
    | CASE caseExpr=expression?
      (WHEN whenCondition+=expression THEN whenResult+=expression)+
      (ELSE elseExpr=expression)?
      END                                                               # caseWhenPrimary
    | CAST LPAREN expression AS dataType RPAREN                         # castPrimary
    ;

literal
    : INTEGER_LITERAL       # intLiteral
    | DECIMAL_LITERAL       # decLiteral
    | STRING_LITERAL        # stringLiteral
    | HEX_LITERAL           # hexLiteral
    | TRUE                  # trueLiteral
    | FALSE                 # falseLiteral
    | NULL                  # nullLiteral
    ;

columnRef
    : (tableName DOT)? columnName
    ;

functionCall
    : functionName LPAREN STAR RPAREN                       # countStarCall
    | functionName LPAREN DISTINCT? functionArgs? RPAREN    # regularFunctionCall
    ;

functionName
    : identifier
    ;

functionArgs
    : expression (COMMA expression)*
    ;

// ============================================================
// Identifier & name rules
// ============================================================

tableName
    : (schema=identifier DOT)? table=identifier
    ;

columnName
    : identifier
    ;

identifier
    : IDENTIFIER
    | BACKTICK_IDENTIFIER
    // keywords that can also be used as identifiers
    | STATUS
    | COMMENT
    | ENGINE
    | CHARSET
    | COLLATE
    | COLUMNS
    | FIELDS
    | VARIABLES
    | WARNINGS
    | TABLES
    | DATABASES
    | KEY
    | INDEX
    | DATE
    | DATETIME
    | TIMESTAMP
    | TIME
    | YEAR
    | TEXT
    | BLOB
    | BOOLEAN
    | BOOL
    | SIGNED
    | UNSIGNED
    | WORK
    | BEGIN
    | AFTER
    | FIRST
    | OFFSET
    | SESSION
    | GLOBAL
    | TRANSACTION
    | CASCADE
    | RESTRICT
    | CONVERT
    | FULL
    | OPTIMISTIC
    | PESSIMISTIC
    | REAL
    | NUMERIC
    | SCHEMA
    | AUTO_INCREMENT
    | CHARACTER
    | BINARY
    | VARBINARY
    | TINYINT
    | SMALLINT
    | MEDIUMINT
    | INT
    | INTEGER
    | BIGINT
    | FLOAT
    | DOUBLE
    | DECIMAL
    | CHAR
    | VARCHAR
    | TINYTEXT
    | MEDIUMTEXT
    | LONGTEXT
    | TINYBLOB
    | MEDIUMBLOB
    | LONGBLOB
    ;
