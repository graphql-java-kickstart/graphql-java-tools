package graphql.kickstart.tools.util;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class BiMapTest {
    private BiMap<String, Integer> bimap;

    @Before
    public void setUp() {
        bimap = BiMap.create();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDoesNotPermitDuplicateValues() {
        bimap.put("foo", 1);
        bimap.put("bar", 1);
    }

    @Test
    @SuppressWarnings("OverwrittenKey")
    public void testPutSameKeySameValue() {
        bimap.put("foo", 1);

        // should not fail
        bimap.put("foo", 1);
    }

    @Test
    @SuppressWarnings({"ConstantConditions"})
    public void testPutSameKeyNewValue() {
        bimap.put("foo", 1);
        bimap.put("bar", 2);

        int old = bimap.put("bar", 3);

        // old value should have been returned
        assertEquals(2, old);

        // inverse should have correct keys
        Set<Integer> expected = new HashSet<>(Arrays.asList(1, 3));
        assertEquals(expected, bimap.values());
        assertEquals(expected, bimap.inverse().keySet());
    }

    @Test
    public void testInverse() {
        bimap.put("foo", 1);
        bimap.put("bar", 2);
        bimap.put("baz", 3);

        BiMap<Integer, String> inverse = bimap.inverse();
        assertTrue(inverse.containsKey(1));
        assertTrue(inverse.containsKey(2));
        assertTrue(inverse.containsKey(3));

        assertThat(inverse.get(1), is("foo"));
        assertThat(inverse.get(2), is("bar"));
        assertThat(inverse.get(3), is("baz"));
    }

    @Test
    public void testValues() {
        bimap.put("foo", 1);
        bimap.put("bar", 2);
        bimap.put("baz", 3);

        Set<Integer> values = bimap.values();
        Set<Integer> expected = new HashSet<>(Arrays.asList(1, 2, 3));
        assertThat(values, is(expected));
    }
}
