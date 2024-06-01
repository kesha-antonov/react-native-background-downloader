package com.eko.handlers;

import java.io.Serializable;

public class OnProgressState implements Serializable {
    public String id;
    public long bytesDownloaded;
    public long bytesTotal;
    public OnProgressState(String id, long bytesDownloaded, long bytesTotal) {
        this.id = id;
        this.bytesDownloaded = bytesDownloaded;
        this.bytesTotal = bytesTotal;
    }
}
