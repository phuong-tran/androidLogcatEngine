package com.core.logcat.capture;

interface ILogControl {
    void updateLiteral(String text);
    void startLogging(String tags, String regex);

    // Regex updates are hot-swapped. Tag changes restart capture because tags
    // are part of the logcat command line.
    void updateFilters(String tags, String regex);
    void stopLogging();
}
