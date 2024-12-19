package com.eko;

import java.io.Serializable;

public class RNBGDTaskConfig implements Serializable {
    public String id;
    public String url;
    public String destination;
    public String metadata = "{}";
    public String notificationTitle;
    public boolean reportedBegin;

    public RNBGDTaskConfig(String id, String url, String destination, String metadata, String notificationTitle) {
        this.id = id;
        this.url = url;
        this.destination = destination;
        this.metadata = metadata;
        this.notificationTitle = notificationTitle;
        this.reportedBegin = false;
    }
}
