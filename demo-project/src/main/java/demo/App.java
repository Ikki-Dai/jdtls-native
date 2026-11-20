package demo;

import org.apache.commons.lang3.StringUtils;

public class App {
    public static void main(String[] args) {
        Greet g = new Greet("jdtls-native");
        System.out.println(g.greet());
        String msg = "jdtls-native-e2e";
        if (StringUtils.isNotEmpty(msg)) {
            System.out.println(StringUtils.upperCase(msg));
        }
    }
}
