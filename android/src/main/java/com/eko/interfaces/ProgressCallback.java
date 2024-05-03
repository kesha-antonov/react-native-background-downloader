package com.eko.interfaces;

public interface ProgressCallback {
    void onProgress(String configId, long bytesDownloaded, long bytesTotal);
}
