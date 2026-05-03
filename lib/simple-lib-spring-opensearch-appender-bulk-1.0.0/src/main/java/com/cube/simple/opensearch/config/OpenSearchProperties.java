package com.cube.simple.opensearch.config;

import java.util.ArrayList;
import java.util.List;

/**
 * this holds the information from the appender/properties tag (in logback.xml)
 */
public class OpenSearchProperties {

    private List<Property> properties;

    public OpenSearchProperties() {
        this.properties = new ArrayList<Property>();
    }

    public List<Property> getProperties() {
        return properties;
    }

    /**
     * Adds an OpenSearch property.
     *
     * @param property this is called by logback for each property tag contained in the properties tag
     */
    public void addProperty(Property property) {
        properties.add(property);
    }

    /**
     * Adds an OpenSearch property.
     *
     * @param property this is called by logback for each property tag contained in the properties tag
     */
    public void addOpenSearchProperty(Property property) {
        properties.add(property);
    }
}
