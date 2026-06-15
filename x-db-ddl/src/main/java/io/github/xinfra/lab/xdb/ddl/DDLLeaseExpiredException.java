package io.github.xinfra.lab.xdb.ddl;

public class DDLLeaseExpiredException extends RuntimeException {
    public DDLLeaseExpiredException(String message) {
        super(message);
    }
}
