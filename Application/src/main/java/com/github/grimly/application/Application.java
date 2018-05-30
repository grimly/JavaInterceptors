package com.github.grimly.application;

/**
 *
 * @author Grimly
 */
public class Application {

    public static void main(String[] args) {
        Source source = new Source();
        System.out.println(source.ping("mike", 45));
    }
}
