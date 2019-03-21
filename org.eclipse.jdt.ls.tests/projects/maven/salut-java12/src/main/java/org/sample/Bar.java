package org.sample;

/**
 * This is Bar
 */
public class Bar {

    public static void main(String[] args) {
        var qty = switch (args ==null?0:args.length) {
        	case 0 -> "Zero";
        	case 1 -> "One";
        	default -> "Many";
        };
        System.out.print(qty + " args");
    }
}
