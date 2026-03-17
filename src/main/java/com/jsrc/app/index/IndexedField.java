package com.jsrc.app.index;

/**
 * Compact field metadata stored in the index.
 *
 * @param name field name
 * @param type field type (simple name, generics stripped)
 */
public record IndexedField(String name, String type) {}
