package demo;

public class Greet {
    private final String name;
    private int count = 0;

    public Greet(String name) {
        this.name = name;
    }

    public String greet() {
        count++;
        return "Hello, " + name + "! (#" + count + ")";
    }

    public int getCount() {
        return count;
    }
}
