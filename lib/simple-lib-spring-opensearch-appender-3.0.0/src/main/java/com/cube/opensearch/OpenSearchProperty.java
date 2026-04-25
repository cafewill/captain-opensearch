package com.cube.opensearch;

public class OpenSearchProperty {
    public enum Type {
        STRING,
        INT,
        FLOAT,
        BOOLEAN;

        static Type from(String value) {
            if (value == null || value.isBlank()) {
                return STRING;
            }
            try {
                return Type.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return STRING;
            }
        }
    }

    private String name;
    private String value;
    private boolean allowEmpty = true;
    private Type type = Type.STRING;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isAllowEmpty() {
        return allowEmpty;
    }

    public void setAllowEmpty(boolean allowEmpty) {
        this.allowEmpty = allowEmpty;
    }

    public Type getType() {
        return type;
    }

    public void setType(String type) {
        this.type = Type.from(type);
    }
}
