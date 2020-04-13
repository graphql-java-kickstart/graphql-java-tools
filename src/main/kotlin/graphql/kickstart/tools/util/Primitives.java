package graphql.kickstart.tools.util;

public final class Primitives {

    @SuppressWarnings("unchecked")
    public static <T> Class<T> wrap(Class<T> type) {
        if (boolean.class.equals(type)) {
            return (Class<T>) Boolean.class;
        } else if (byte.class.equals(type)) {
            return (Class<T>) Byte.class;
        } else if (char.class.equals(type)) {
            return (Class<T>) Character.class;
        } else if (double.class.equals(type)) {
            return (Class<T>) Double.class;
        } else if (float.class.equals(type)) {
            return (Class<T>) Float.class;
        } else if (int.class.equals(type)) {
            return (Class<T>) Integer.class;
        } else if (long.class.equals(type)) {
            return (Class<T>) Long.class;
        } else if (short.class.equals(type)) {
            return (Class<T>) Short.class;
        } else if (void.class.equals(type)) {
            return (Class<T>) Void.class;
        } else {
            return type;
        }
    }
}
