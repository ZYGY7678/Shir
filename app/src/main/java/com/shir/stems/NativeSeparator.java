package com.shir.stems;

public final class NativeSeparator {
    static { System.loadLibrary("shir-native"); }
    private NativeSeparator() {}
    public static native boolean nativeSeparate(String input, String model, String output);
    public static native float nativeProgress();
    public static native String nativeStatus();
}
