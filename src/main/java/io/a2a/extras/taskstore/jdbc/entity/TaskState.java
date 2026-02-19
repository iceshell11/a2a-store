package io.a2a.extras.taskstore.jdbc.entity;

public enum TaskState {
    SUBMITTED,
    WORKING,
    INPUT_REQUIRED,
    AUTH_REQUIRED,
    COMPLETED,
    CANCELED,
    FAILED,
    REJECTED,
    UNKNOWN
}
