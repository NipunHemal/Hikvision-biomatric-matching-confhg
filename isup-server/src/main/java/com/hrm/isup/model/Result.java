package com.hrm.isup.model;

/** Outcome of a device operation: ok flag + the device's raw reply (JSON/XML). */
public final class Result {
    public final boolean ok;
    public final String body;

    public Result(boolean ok, String body) {
        this.ok = ok;
        this.body = body;
    }

    public static Result ok(String body) { return new Result(true, body); }
    public static Result fail(String body) { return new Result(false, body); }
}
