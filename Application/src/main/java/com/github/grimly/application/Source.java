package com.github.grimly.application;

/**
 *
 * @author Grimly
 */
public class Source {

    boolean pong = true;

    public String ping(String emitter, int number) {
        pong = ! pong;
        return emitter + ": #" + number + " " + (pong ? "hello" : "goodbye");
    }
}
