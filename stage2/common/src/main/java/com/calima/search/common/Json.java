package com.calima.search.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.http.Context;

/** The single JSON configuration of the project; absent values are written as explicit nulls. */
public final class Json {

    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Json() {
    }

    public static void respond(Context ctx, int status, Object body) {
        ctx.status(status).contentType("application/json").result(GSON.toJson(body));
    }
}
