package net.paoding.rose.mock.controllers.for_interceptors_test2;

import net.paoding.rose.mock.controllers.for_interceptors_test2.annotation.RequiredAnnotation;

public class SimpleController {

    public static final String RETURN = "returned-by-SimpleController";

    public String index() {
        return RETURN + ".index";
    }

    @RequiredAnnotation
    public String method() {
        return RETURN + ".method";
    }
}
