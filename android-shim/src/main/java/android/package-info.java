/**
 * The audited desktop boundary for the Android framework: the smallest set of {@code android.*} types the
 * FTC SDK and its libraries touch while a robot program runs, plus the pieces of the Android runtime
 * ({@code dalvik.system.DexFile}, {@code BaseDexClassLoader} and {@code PathClassLoader}) the SDK and its
 * libraries use to list and load the classes of the robot program. Behaviour that
 * matters is implemented faithfully; everything else fails loudly with
 * {@link java.lang.UnsupportedOperationException} naming the member, never with a silent default, so an
 * unsupported call is a visible diagnostic rather than wrong data.
 */
package android;
