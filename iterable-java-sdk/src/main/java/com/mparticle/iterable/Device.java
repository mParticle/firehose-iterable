package com.mparticle.iterable;


import java.util.Map;

public class Device {
    public String token;
    public String platform;
    public Map<String, String> dataFields;
    public static String PLATFORM_APNS = "APNS";
    public static String PLATFORM_APNS_SANDBOX = "APNS_SANDBOX";
    public static String PLATFORM_GCM = "GCM";
}
