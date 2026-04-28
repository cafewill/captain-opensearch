package com.cube.simple.opensearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OpenSearchProperties {
    private final List<OpenSearchProperty> properties = new ArrayList<>();

    public List<OpenSearchProperty> getProperties() {
        return Collections.unmodifiableList(properties);
    }

    public void addProperty(OpenSearchProperty property) {
        if (property != null) {
            properties.add(property);
        }
    }

    public void addOpenSearchProperty(OpenSearchProperty property) {
        addProperty(property);
    }

    public void addField(OpenSearchProperty property) {
        addProperty(property);
    }
}
