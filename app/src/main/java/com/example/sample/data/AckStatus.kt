package com.example.sample.data

enum class AckStatus(val ackCode: String, val condition: Int, val text: String) {
    ACCEPTED("AA", 0, "Message accepted"),

    SEGMENT_SEQUENCE_ERROR("AE", 100, "Segments sequence error"),
    REQUIRED_FIELD_MISSING("AE", 101, "Required field missing"),
    DATA_TYPE_ERROR("AE", 102, "Data type error"),
    TABLE_VALUE_NOT_FOUND("AE", 103, "table value not found"),

    UNSUPPORTED_MESSAGE_TYPE("AR", 200, "Unsupported message type"),
    UNSUPPORTED_EVENT_CODE("AR", 201, "Unsupported event code"),
    UNSUPPORTED_PROCESSING_ID("AR", 202, "Unsupported processing id"),
    UNSUPPORTED_VERSION_ID("AR", 203, "Unsupported version id"),
    UNKNOWN_KEY_IDENTIFIER("AR", 204, "Unknown key identifier"),
    DUPLICATE_KEY_IDENTIFIER("AR", 205, "Duplicate key identifier"),
    APPLICATION_RECORD_LOCKED("AR", 206, "Application record locked"),
    APPLICATION_INTERNAL_ERROR("AR", 207, "Application internal error")
}