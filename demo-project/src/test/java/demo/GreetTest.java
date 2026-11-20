package demo;

public class GreetTest {
    public static void main(String[] args) {
        Greet g = new Greet("test");
        if (!g.greet().contains("test")) {
            throw new AssertionError("greeting failed");
        }
        System.out.println("ok");
    }
}
