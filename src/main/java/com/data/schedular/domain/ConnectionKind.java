package com.data.schedular.domain;

/** Whether a connection can be read from (NoSQL source) or written to (SQL target). */
public enum ConnectionKind {
    SOURCE,
    TARGET
}
