package graphql.kickstart.tools.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PrimitivesTest {
    @Test
    public void testWrapPrimitive() {
        Class<?> clazz = Primitives.wrap(int.class);

        assertEquals(Integer.class, clazz);
    }

    @Test
    public void testWrapNonPrimitive() {
        Class<?> clazz = Primitives.wrap(String.class);

        assertEquals(String.class, clazz);
    }

    @Test
    public void testWrapNull() {
        Class<?> clazz = Primitives.wrap(null);

        assertNull(clazz);
    }
}
