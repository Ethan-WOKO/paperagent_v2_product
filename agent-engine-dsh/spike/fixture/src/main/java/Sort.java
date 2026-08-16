import ch.qos.logback.core.rolling.TimeBasedFileNamingAndTriggeringPolicy;

import java.util.Arrays;

/** Simple sort utility that compiles standalone once logback is on the classpath. */
public class Sort {

    public static void sort(int[] values) {
        Arrays.sort(values);
    }

    public static void main(String[] args) {
        int[] data = {3, 1, 2};
        sort(data);
        System.out.println(Arrays.toString(data));
    }
}
