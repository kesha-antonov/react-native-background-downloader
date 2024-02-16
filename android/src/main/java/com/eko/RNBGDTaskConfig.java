package com.eko;

import java.io.Serializable;

public class RNBGDTaskConfig implements Serializable {
    public String id;
    public String destination;
    public String metadata = "{}";
    public boolean reportedBegin;

    public RNBGDTaskConfig(String id, String destination, String metadata) {
        this.id = id;
        this.destination = destination;
        this.metadata = metadata;
        this.reportedBegin = false;
    }
}
