package com.eko;

public interface ProgressCallback {
    void onProgress(String configId, long bytesDownloaded, long bytesTotal);
}
