package com.eko;

public interface ProgressCallback {
    void onProgress(String configId, int bytesDownloaded, int bytesTotal);
}
