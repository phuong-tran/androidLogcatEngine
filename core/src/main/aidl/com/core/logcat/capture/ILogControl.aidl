package com.core.logcat.capture;
import android.os.ParcelFileDescriptor;

interface ILogControl {
    void updateLiteral(String text);
    void startLogging(String tags, String regex);
    void updateFilters(String tags, String regex);
    void stopLogging();
}
