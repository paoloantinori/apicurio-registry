package com.squareup.wire.schema;

import java.util.List;

/**
 * Bridge to antiwire's package-private OneOf constructor. Apicurio synthesizes the synthetic
 * one-of of a proto3 optional field, which requires building a OneOf around an already linked
 * Field. This class lives in OneOf's package only to reach that constructor and is deleted once
 * antiwire exposes a public factory for it.
 */
public final class OneOfFactory {

    private OneOfFactory() {
    }

    public static OneOf create(String name, String documentation, List<Field> fields,
            Location location, Options options) {
        return new OneOf(name, documentation, fields, location, options);
    }
}
