package dev.ftcsim.runtime;

/**
 * One JDK method whose call sites are redirected to a static method on one of the hook classes.
 *
 * @param owner          internal name of the class declaring the JDK method
 * @param name           JDK method name, or {@code <init>} for a constructor
 * @param descriptor     JDK method descriptor
 * @param hookOwner      internal name of the class holding the replacement
 * @param hookName       name of the replacement
 * @param hookDescriptor descriptor of the replacement; for an instance method the receiver becomes the first
 *                       parameter, which matches how it already sits on the operand stack, and for a
 *                       constructor the replacement returns the new object
 * @param instance       whether the JDK method is an instance method
 */
record RewriteRule(
        String owner,
        String name,
        String descriptor,
        String hookOwner,
        String hookName,
        String hookDescriptor,
        boolean instance) {

    static final String CONSTRUCTOR = "<init>";

    /** A rule whose replacement is on the general hooks facade. */
    RewriteRule(String owner, String name, String descriptor, String hookName, String hookDescriptor, boolean instance) {
        this(owner, name, descriptor, RewriteRules.HOOKS, hookName, hookDescriptor, instance);
    }

    boolean isConstructor() {
        return CONSTRUCTOR.equals(name);
    }

    /**
     * Stands for any receiver type. Used for methods that no class can override, such as {@code Object.wait},
     * whose call sites name whatever static type the receiver happens to have.
     */
    static final String ANY_OWNER = "*";

    String key() {
        return key(owner, name, descriptor);
    }

    static String key(String owner, String name, String descriptor) {
        return owner + '.' + name + descriptor;
    }
}
