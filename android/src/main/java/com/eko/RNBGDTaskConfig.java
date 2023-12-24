package com.eko;

import java.io.Serializable;

public class RNBGDTaskConfig implements Serializable {
    public String id;
    public String url;
    public String destination;
    public String metadata = "{}";
    public boolean reportedBegin;
    public int progressInterval;

    public RNBGDTaskConfig(String id, String url, String destination, String metadata, int progressInterval) {
        this.id = id;
        this.url = url;
        this.destination = destination;
        this.metadata = metadata;
        this.reportedBegin = false;
        this.progressInterval = progressInterval;
    }
}
