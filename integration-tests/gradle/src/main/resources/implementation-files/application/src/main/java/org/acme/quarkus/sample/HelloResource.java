package org.acme.quarkus.sample;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.acme.common.CommonBean;

@Path("/hello")
public class HelloResource {

    @Inject
    CommonBean common;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "hello " + common.getName();
    }
}
