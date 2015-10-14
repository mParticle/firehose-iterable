package com.mparticle.iterable;

import java.util.Map;

public class IterableApiResponse {

    public static String SUCCESS_MESSAGE = "Success";

    public String msg;
    public String code;
    public Map<String, String> params;

    public boolean isSuccess() {
        return code != null && code.equalsIgnoreCase(SUCCESS_MESSAGE);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (code != null) {
            builder.append(code + ": ");
        }
        if (msg != null) {
            builder.append(msg + "\n");
        }
        if (params != null) {
            builder.append(params.toString());
        }
        return builder.toString();
    }
}
